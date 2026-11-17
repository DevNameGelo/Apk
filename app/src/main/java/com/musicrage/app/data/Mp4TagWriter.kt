package com.musicrage.app.data

import java.io.ByteArrayOutputStream

/**
 * Minimal MP4 (m4a) metadata writer: inserts a `moov.udta.meta.ilst.covr` atom carrying the cover
 * art into the joined HLS stream the downloader saves. Without it every downloaded song showed a
 * grey tile — MediaStore does read embedded MP4 cover art, so writing the atom makes the album
 * art appear in the library (and any other player) immediately.
 *
 * The HLS join produces a fragmented MP4 (`ftyp`, `moov`, then `styp` + fragments). A cover atom
 * lives inside `moov.udta.meta.ilst`; adding it changes the length of every box on that path, so
 * each enclosing box is rebuilt from the original bytes plus the new child, and the finished moov
 * replaces the old one. No other atom moves.
 */
object Mp4TagWriter {

    /** Returns [m4a] with a cover atom added (or the input unchanged when it has no recognizable moov). */
    fun withCover(m4a: ByteArray, cover: ByteArray): ByteArray {
        val covr = coverAtom(cover)
        val moov = findBox(m4a, 0, m4a.size, "moov") ?: return m4a
        val udta = findBox(m4a, moov.bodyStart, moov.bodyEnd, "udta")
        val newMoovBody: ByteArray
        if (udta == null) {
            newMoovBody = slice(m4a, moov.bodyStart, moov.bodyEnd) + box("udta", fullBox("meta", box("ilst", covr)))
        } else {
            // meta is a FullBox: 8-byte header then 4 version/flag bytes, children after those.
            val meta = findBox(m4a, udta.bodyStart, udta.bodyEnd, "meta")
            val newUdtaBody: ByteArray
            if (meta == null) {
                newUdtaBody = slice(m4a, udta.bodyStart, udta.bodyEnd) + fullBox("meta", box("ilst", covr))
            } else {
                val ilst = findBox(m4a, meta.bodyStart + 4, meta.bodyEnd, "ilst")
                val newMetaBody: ByteArray
                if (ilst == null) {
                    newMetaBody = slice(m4a, meta.bodyStart, meta.bodyEnd) + box("ilst", covr)
                } else {
                    // Replace the existing ilst with itself plus the covr atom.
                    val newIlst = box("ilst", slice(m4a, ilst.bodyStart, ilst.bodyEnd) + covr)
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
            if (String(data, at + 4, 4, Charsets.US_ASCII) == type) return Box(at, size)
            at += size
        }
        return null
    }

    // ---- Box building ---------------------------------------------------------------------------------------------------

    /** covr is itself a FullBox: 4 type bytes (13 = jpeg, 14 = png) then the image. */
    private fun coverAtom(cover: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        writeUint32(payload, if (isJpeg(cover)) 0x0D else 0x0E)
        payload.write(cover)
        return box("covr", payload.toByteArray())
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
