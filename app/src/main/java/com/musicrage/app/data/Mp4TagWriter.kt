package com.musicrage.app.data

import java.io.ByteArrayOutputStream

/**
 * Minimal MP4 (m4a) metadata writer: inserts `moov.udta.meta.ilst` atoms carrying the track's
 * title (©nam), artist (©ART), ALBUM (©alb) and cover art (covr) into the joined HLS stream the
 * downloader saves. Without them every downloaded song showed a grey tile and "Unknown" album —
 * MediaStore (and every other player) reads these atoms, so the file carries its real metadata
 * the moment it lands.
 *
 * The HLS join produces a fragmented MP4 (`ftyp`, `moov`, then `styp` + fragments). A metadata
 * atom lives inside `moov.udta.meta.ilst`; adding them changes the length of every box on that
 * path, so each enclosing box is rebuilt from the original bytes plus the new children, and the
 * finished moov replaces the old one. No other atom moves.
 */
object Mp4TagWriter {

    /** Returns [m4a] with a cover atom added (or the input unchanged when it has no recognizable moov). */
    fun withCover(m4a: ByteArray, cover: ByteArray): ByteArray =
        withMetadata(m4a, null, null, null, cover)

    /**
     * Returns [m4a] with title/artist/album text atoms and the cover atom added. Blank fields are
     * skipped, so a track without an album name keeps whatever it already had. The input is
     * returned unchanged when the file has no recognizable moov box.
     */
    fun withMetadata(m4a: ByteArray, title: String?, artist: String?, album: String?, cover: ByteArray?): ByteArray {
        val children = ArrayList<ByteArray>()
        title?.takeIf { it.isNotBlank() }?.let { children.add(textAtom("nam", it)) }
        artist?.takeIf { it.isNotBlank() }?.let { children.add(textAtom("ART", it)) }
        album?.takeIf { it.isNotBlank() }?.let { children.add(textAtom("alb", it)) }
        cover?.takeIf { it.size > 100 }?.let { children.add(coverAtom(it)) }
        if (children.isEmpty()) return m4a
        val moov = findBox(m4a, 0, m4a.size, "moov") ?: return m4a
        val udta = findBox(m4a, moov.bodyStart, moov.bodyEnd, "udta")
        val newMoovBody: ByteArray
        if (udta == null) {
            newMoovBody = slice(m4a, moov.bodyStart, moov.bodyEnd) + box("udta", fullBox("meta", box("ilst", children)))
        } else {
            // meta is a FullBox: 8-byte header then 4 version/flag bytes, children after those.
            val meta = findBox(m4a, udta.bodyStart, udta.bodyEnd, "meta")
            val newUdtaBody: ByteArray
            if (meta == null) {
                newUdtaBody = slice(m4a, udta.bodyStart, udta.bodyEnd) + fullBox("meta", box("ilst", children))
            } else {
                val ilst = findBox(m4a, meta.bodyStart + 4, meta.bodyEnd, "ilst")
                val newMetaBody: ByteArray
                if (ilst == null) {
                    newMetaBody = slice(m4a, meta.bodyStart, meta.bodyEnd) + box("ilst", children)
                } else {
                    // The stream's own ilst may already carry ©nam/©ART (SoundCloud writes them);
                    // keep the atoms we do NOT rewrite (genre, year…), drop the ones we do, then
                    // append ours — so the file ends up with exactly one of each, which is what
                    // every reader (and MediaStore) expects.
                    val keep = ArrayList<ByteArray>()
                    var at = ilst.bodyStart
                    while (at + 8 <= ilst.bodyEnd) {
                        val size = readUint32(m4a, at)
                        if (size < 8 || at + size > ilst.bodyEnd) break
                        if (!isMetadataAtom(m4a, at)) {
                            keep.add(slice(m4a, at, at + size))
                        }
                        at += size
                    }
                    val newIlst = box("ilst", keep + children)
                    newMetaBody = replaceRange(
                        slice(m4a, meta.bodyStart, meta.bodyEnd),
                        ilst.start - meta.bodyStart until ilst.end - meta.bodyStart,
                        newIlst
                    )
                }
                newUdtaBody = replaceRange(
                    slice(m4a, udta.bodyStart, udta.bodyEnd),
                    meta.start - udta.bodyStart until meta.end - udta.bodyStart,
                    // newMetaBody was sliced from meta+8, so it already begins with meta's
                    // 4 version/flag bytes: rebuild meta as a PLAIN box over it (adding the
                    // flags again would shift every child by four bytes and corrupt the tree).
                    box("meta", newMetaBody)
                )
            }
            newMoovBody = replaceRange(
                slice(m4a, moov.bodyStart, moov.bodyEnd),
                udta.start - moov.bodyStart until udta.end - moov.bodyStart,
                box("udta", newUdtaBody)
            )
        }
        return replaceRange(m4a, moov.start until moov.end, box("moov", newMoovBody))
    }

    // ---- Box walking (absolute offsets in the original array) ----------------------------------------------------------

    private class Box(val start: Int, val size: Int) {
        val end get() = start + size
        val bodyStart get() = start + 8
        val bodyEnd get() = end
    }

    /** First box of [type] inside [from, to), or null. Stops at the first malformed header. */
    private fun findBox(data: ByteArray, from: Int, to: Int, type: String): Box? {
        var at = from
        while (at + 8 <= to) {
            val size = readUint32(data, at)
            if (size < 8 || at + size > to) return null
            if (nameAt(data, at) == type) return Box(at, size)
            at += size
        }
        return null
    }

    /** The 4-char name of the box at [at]: plain ASCII letters as-is, the © byte as '©'. */
    private fun nameAt(data: ByteArray, at: Int): String {
        val sb = StringBuilder(4)
        for (i in 0 until 4) {
            val b = data[at + 4 + i]
            sb.append(if (b == 0xA9.toByte()) '©' else ((b.toInt() and 0xFF) as Char))
        }
        return sb.toString()
    }

    /** True when the ilst child at [at] is one of the atoms we rewrite (©nam/©ART/©alb/covr). */
    private fun isMetadataAtom(data: ByteArray, at: Int): Boolean {
        val name = nameAt(data, at)
        return name == "©nam" || name == "©ART" || name == "©alb" || name == "covr"
    }

    // ---- Box building ---------------------------------------------------------------------------------------------------

    /**
     * A text metadata atom (©nam/©ART/©alb…). Its body is a "data" sub-box whose own body is
     * 4 type/flag bytes (1 = UTF-8 text), 4 locale bytes (0), then the UTF-8 bytes of the text —
     * the exact layout iTunes/ffmpeg/mutagen/MediaStore all read.
     */
    private fun textAtom(name: String, text: String): ByteArray {
        val data = ByteArrayOutputStream()
        writeUint32(data, 0x00000001) // data type: UTF-8 text
        writeUint32(data, 0) // locale
        data.write(text.toByteArray(Charsets.UTF_8))
        val payload = box("data", data.toByteArray())
        return taggedBox(name, payload)
    }

    /**
     * The cover atom. Its body is a "data" sub-box whose own body is 4 type/flag bytes
     * (13 = JPEG, 14 = PNG), 4 locale bytes (0), then the image bytes — the layout every MP4
     * reader treats as embedded artwork.
     */
    private fun coverAtom(cover: ByteArray): ByteArray {
        val data = ByteArrayOutputStream()
        writeUint32(data, if (isJpeg(cover)) 0x0000000D else 0x0000000E) // data type: JPEG/PNG
        writeUint32(data, 0) // locale
        data.write(cover)
        return box("covr", box("data", data.toByteArray()))
    }

    private fun isJpeg(cover: ByteArray): Boolean =
        cover.size > 3 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte()

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUint32(out, 8 + payload.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    /**
     * A metadata atom whose 4-byte name starts with the © byte (0xA9): the © is written as a raw
     * byte — `"...".toByteArray(US_ASCII)` would turn it into '?' (0x3F) and no reader would
     * recognize the atom. Name [type] here is the 3 chars after the ©.
     */
    private fun taggedBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUint32(out, 8 + payload.size)
        out.write(0xA9) // ©
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    /** ilst accepts several children; each is appended in order. */
    private fun box(type: String, payload: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        var size = 8
        for (p in payload) size += p.size
        writeUint32(out, size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        for (p in payload) out.write(p)
        return out.toByteArray()
    }

    /** A FullBox box: the usual header, then 4 version/flag bytes, then the payload. */
    private fun fullBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeUint32(out, 12 + payload.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(payload)
        return out.toByteArray()
    }

    // ---- Byte helpers ---------------------------------------------------------------------------------------------------

    private fun slice(data: ByteArray, from: Int, to: Int) = data.copyOfRange(from, to)

    private fun replaceRange(data: ByteArray, range: IntRange, replacement: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(data, 0, range.first)
        out.write(replacement)
        out.write(data, range.last + 1, data.size - range.last - 1)
        return out.toByteArray()
    }

    private fun writeUint32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun readUint32(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
}
