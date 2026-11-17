package com.musicrage.app.data

import java.io.ByteArrayOutputStream

/**
 * Minimal ID3v2.3 tag writer (no library dependency). The old download saved the raw MP3 the converter
 * returned, so the file had no title, artist or cover — the library showed "Unknown artist" with a grey
 * tile. This writes TIT2/TPE1/TALB text frames and an APIC cover frame at the front of the file so
 * MediaStore and the gallery of Music Rage pick the metadata up immediately.
 */
object Id3Writer {

    private fun synchsafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte()
    )

    private fun textFrame(id: String, text: String): ByteArray {
        // encoding 3 = UTF-8, then the text with a trailing NUL.
        val payload = ByteArrayOutputStream()
        payload.write(3)
        payload.write(text.toByteArray(Charsets.UTF_8))
        payload.write(0)
        return frame(id, payload.toByteArray())
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        // 4-byte big-endian size, flags = 0.
        for (shift in listOf(24, 16, 8, 0)) out.write((payload.size shr shift) and 0xFF)
        out.write(0); out.write(0)
        out.write(payload)
        return out.toByteArray()
    }

    private fun coverFrame(mime: String, description: String, image: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0) // encoding = ISO-8859-1 for the description
        payload.write(mime.toByteArray(Charsets.US_ASCII))
        payload.write(0)
        payload.write(3) // picture type = front cover
        payload.write(description.toByteArray(Charsets.US_ASCII))
        payload.write(0)
        payload.write(image)
        return frame("APIC", payload.toByteArray())
    }

    /** Builds the complete ID3v2.3 tag; returns null when there is nothing worth writing. */
    fun buildTag(title: String, artist: String, album: String, cover: ByteArray?): ByteArray? {
        val frames = ByteArrayOutputStream()
        var any = false
        if (title.isNotBlank()) { frames.write(textFrame("TIT2", title)); any = true }
        if (artist.isNotBlank()) { frames.write(textFrame("TPE1", artist)); any = true }
        if (album.isNotBlank()) { frames.write(textFrame("TALB", album)); any = true }
        if (cover != null && cover.size > 100) {
            val mime = if (cover.size > 3 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
            frames.write(coverFrame(mime, "Cover", cover))
            any = true
        }
        if (!any) return null
        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(0) // version 2.3, revision 0
        out.write(0) // flags
        out.write(synchsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /** Prepends [tag] to an MP3 that may already start with an ID3v2 tag; the old tag is replaced. */
    fun prependTag(mp3: ByteArray, tag: ByteArray): ByteArray {
        val hasId3 = mp3.size > 10 &&
            mp3[0] == 'I'.code.toByte() && mp3[1] == 'D'.code.toByte() && mp3[2] == '3'.code.toByte()
        val start = if (hasId3) {
            // Skip the existing tag: 10-byte header plus its synchsafe size.
            val size = ((mp3[6].toInt() and 0x7F) shl 21) or ((mp3[7].toInt() and 0x7F) shl 14) or
                ((mp3[8].toInt() and 0x7F) shl 7) or (mp3[9].toInt() and 0x7F)
            (10 + size).coerceAtMost(mp3.size)
        } else 0
        val out = ByteArrayOutputStream(tag.size + (mp3.size - start))
        out.write(tag)
        out.write(mp3, start, mp3.size - start)
        return out.toByteArray()
    }
}
