package com.musicrage.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Local store for favorites, playlists and settings. The file name and the favorites/playlists/speed keys are
 * unchanged from earlier versions so an in-place update keeps existing data.
 */
object MusicPreferences {
    const val FILE = "music_rage_preferences"
    private const val FAVORITES = "favorite_uris"
    private const val PLAYLISTS = "playlists_json"
    private const val SPEED = "playback_speed"
    private const val AUTO_RECOVER = "auto_recover"
    private const val PLAY_OVER_OTHERS = "play_over_others"
    private const val FLOATING = "floating_enabled"
    private const val FLOATING_X = "floating_x"
    private const val FLOATING_Y = "floating_y"
    private const val TITLE_OVERRIDES = "title_overrides"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun favorites(context: Context): Set<String> =
        prefs(context).getStringSet(FAVORITES, emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(context: Context, values: Set<String>) {
        prefs(context).edit().putStringSet(FAVORITES, values.toSet()).apply()
    }

    fun playlists(context: Context): List<MusicPlaylist> {
        val raw = prefs(context).getString(PLAYLISTS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val songs = item.optJSONArray("songs") ?: JSONArray()
                    add(
                        MusicPlaylist(
                            id = item.optString("id"),
                            name = item.optString("name", "Playlist"),
                            songKeys = buildList { for (j in 0 until songs.length()) add(songs.optString(j)) }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun savePlaylists(context: Context, playlists: List<MusicPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val songs = JSONArray()
            playlist.songKeys.forEach { songs.put(it) }
            array.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("songs", songs))
        }
        prefs(context).edit().putString(PLAYLISTS, array.toString()).apply()
    }

    fun createPlaylist(context: Context, name: String): List<MusicPlaylist> {
        val updated = playlists(context) + MusicPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList())
        savePlaylists(context, updated)
        return updated
    }

    fun renamePlaylist(context: Context, playlistId: String, name: String): List<MusicPlaylist> {
        val updated = playlists(context).map { if (it.id == playlistId) it.copy(name = name.trim()) else it }
        savePlaylists(context, updated)
        return updated
    }

    fun addToPlaylist(context: Context, playlistId: String, songKeys: Collection<String>): List<MusicPlaylist> {
        val updated = playlists(context).map { playlist ->
            if (playlist.id == playlistId) playlist.copy(songKeys = (playlist.songKeys + songKeys).distinct()) else playlist
        }
        savePlaylists(context, updated)
        return updated
    }

    fun removeFromPlaylist(context: Context, playlistId: String, songKeys: Collection<String>): List<MusicPlaylist> {
        val removal = songKeys.toSet()
        val updated = playlists(context).map { playlist ->
            if (playlist.id == playlistId) playlist.copy(songKeys = playlist.songKeys.filterNot { it in removal }) else playlist
        }
        savePlaylists(context, updated)
        return updated
    }

    fun deletePlaylist(context: Context, playlistId: String): List<MusicPlaylist> {
        val updated = playlists(context).filterNot { it.id == playlistId }
        savePlaylists(context, updated)
        return updated
    }

    fun playbackSpeed(context: Context) = prefs(context).getFloat(SPEED, 1f).coerceIn(0.25f, 10f)
    fun setPlaybackSpeed(context: Context, speed: Float) {
        prefs(context).edit().putFloat(SPEED, speed.coerceIn(0.25f, 10f)).apply()
    }

    fun autoRecover(context: Context) = prefs(context).getBoolean(AUTO_RECOVER, true)
    fun setAutoRecover(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(AUTO_RECOVER, value).apply()
    }

    fun playOverOthers(context: Context) = prefs(context).getBoolean(PLAY_OVER_OTHERS, false)
    fun setPlayOverOthers(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(PLAY_OVER_OTHERS, value).apply()
    }

    fun floatingEnabled(context: Context) = prefs(context).getBoolean(FLOATING, false)
    fun setFloatingEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(FLOATING, value).apply()
    }

    fun floatingPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains(FLOATING_X)) return null
        return p.getInt(FLOATING_X, 0) to p.getInt(FLOATING_Y, 0)
    }

    fun setFloatingPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(FLOATING_X, x).putInt(FLOATING_Y, y).apply()
    }

    /** Names the user typed in "Rename". They replace the title tag when listing songs. */
    fun titleOverrides(context: Context): Map<String, String> {
        val raw = prefs(context).getString(TITLE_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap<String, String> { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
        }.getOrDefault(emptyMap())
    }

    fun setTitleOverride(context: Context, key: String, title: String) {
        val current = JSONObject()
        titleOverrides(context).forEach { (k, v) -> current.put(k, v) }
        current.put(key, title)
        prefs(context).edit().putString(TITLE_OVERRIDES, current.toString()).apply()
    }

    // ---- Backup / restore -------------------------------------------------------------------------------------

    /** Serializes favorites and playlists together with enough song info to re-match files after a reinstall. */
    fun exportBackup(context: Context, tracks: List<AudioTrack>): String {
        val byKey = tracks.associateBy { it.key }
        fun describe(key: String): JSONObject {
            val track = byKey[key]
            return JSONObject().put("uri", key)
                .put("title", track?.title.orEmpty())
                .put("artist", track?.artist.orEmpty())
                .put("file", track?.displayName.orEmpty())
        }
        val favorites = JSONArray()
        favorites(context).forEach { favorites.put(describe(it)) }
        val lists = JSONArray()
        playlists(context).forEach { playlist ->
            val songs = JSONArray()
            playlist.songKeys.forEach { songs.put(describe(it)) }
            lists.put(JSONObject().put("name", playlist.name).put("songs", songs))
        }
        return JSONObject().put("app", "music-rage").put("version", 1)
            .put("favorites", favorites).put("playlists", lists).toString(2)
    }

    /** Merges a backup into the current data. Returns false when the file is not a Music Rage backup. */
    fun importBackup(context: Context, json: String, tracks: List<AudioTrack>): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return false
        if (root.optString("app") != "music-rage") return false
        val byKey = tracks.associateBy { it.key }
        val byFile = tracks.associateBy { it.displayName.lowercase() }
        val byTitle = tracks.associateBy { it.title.lowercase() + "|" + it.artist.lowercase() }

        fun resolve(entry: Any?): String? {
            val obj = entry as? JSONObject ?: return null
            val uri = obj.optString("uri")
            if (uri in byKey) return uri
            val file = obj.optString("file").lowercase()
            if (file.isNotEmpty()) byFile[file]?.let { return it.key }
            val title = obj.optString("title").lowercase()
            val artist = obj.optString("artist").lowercase()
            if (title.isNotEmpty()) byTitle["$title|$artist"]?.let { return it.key }
            return uri.takeIf { it.isNotEmpty() }
        }

        val favoriteKeys = HashSet<String>(favorites(context))
        root.optJSONArray("favorites")?.let { array ->
            for (i in 0 until array.length()) resolve(array.opt(i))?.let { favoriteKeys.add(it) }
        }
        saveFavorites(context, favoriteKeys)

        val merged = playlists(context).toMutableList()
        root.optJSONArray("playlists")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").ifBlank { "Playlist" }
                val songs = item.optJSONArray("songs") ?: JSONArray()
                val keys = buildList<String> { for (j in 0 until songs.length()) resolve(songs.opt(j))?.let { add(it) } }.distinct()
                val existing = merged.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                if (existing >= 0) {
                    merged[existing] = merged[existing].copy(songKeys = (merged[existing].songKeys + keys).distinct())
                } else {
                    merged.add(MusicPlaylist(UUID.randomUUID().toString(), name, keys))
                }
            }
        }
        savePlaylists(context, merged)
        return true
    }
}
