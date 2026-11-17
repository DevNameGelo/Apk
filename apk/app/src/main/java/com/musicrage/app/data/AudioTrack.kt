package com.musicrage.app.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** A row from Android MediaStore. The URI string is the stable key used by favorites and playlists. */
data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumId: Long,
    val sizeBytes: Long,
    val displayName: String,
    val mimeType: String,
    val dateAdded: Long,
    val folder: String = ""
) {
    val key: String get() = uri.toString()

    fun toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs.takeIf { it > 0L })
            // The audio URI doubles as the artwork key; ArtworkBitmapLoader/ArtworkLoader resolve it.
            .setArtworkUri(uri)
            .build()
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}

data class MusicPlaylist(
    val id: String,
    val name: String,
    val songKeys: List<String>
)

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

private val SITE_PREFIX = Regex("""^\s*[\w-]+(\.[\w-]+)*\.(com|net|org|io|cc|to|me|app|co|info|xyz)\s*[-–|:]\s*""", RegexOption.IGNORE_CASE)

/** MediaStore uses the literal "<unknown>" for missing tags, and downloaders often prefix titles with their domain. */
fun cleanTitle(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "Unknown title"
    val stripped = value.replace(SITE_PREFIX, "").trim()
    return stripped.ifEmpty { value }
}

fun cleanTag(raw: String?, fallback: String): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || value.equals("<unknown>", ignoreCase = true)) return fallback
    return value
}
