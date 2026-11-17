package com.musicrage.app.ui

import com.musicrage.app.data.AudioTrack
import com.musicrage.app.data.UNKNOWN_ALBUM
import com.musicrage.app.data.UNKNOWN_ARTIST

data class AlbumGroup(val key: String, val name: String, val artist: String, val tracks: List<AudioTrack>)
data class ArtistGroup(val name: String, val tracks: List<AudioTrack>)

sealed interface DetailTarget {
    data object Liked : DetailTarget
    data class Playlist(val id: String) : DetailTarget
    data class Album(val key: String) : DetailTarget
    data class Artist(val name: String) : DetailTarget
}

enum class MainTab { Home, Songs, Search, Online, Library }
enum class LibraryFilter { Playlists, Albums, Artists, Songs }

fun buildAlbums(tracks: List<AudioTrack>): List<AlbumGroup> {
    val groups = tracks.groupBy { if (it.album == UNKNOWN_ALBUM) "unknown" else "${it.albumId}|${it.album.lowercase()}" }
    return groups.map { (key, list) ->
        val name = list.first().album
        val artists = list.map { it.artist }.distinct()
        val artist = if (artists.size == 1) artists.first() else "Various artists"
        AlbumGroup(key, name, artist, list)
    }.sortedWith(compareBy<AlbumGroup> { it.name == UNKNOWN_ALBUM }.thenBy { it.name.lowercase() })
}

fun buildArtists(tracks: List<AudioTrack>): List<ArtistGroup> {
    return tracks.groupBy { it.artist }
        .map { (name, list) -> ArtistGroup(name, list) }
        .sortedWith(compareBy<ArtistGroup> { it.name == UNKNOWN_ARTIST }.thenBy { it.name.lowercase() })
}

fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0L) return "0:00"
    val total = milliseconds / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}
