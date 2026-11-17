package com.musicrage.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.musicrage.app.data.MusicPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Persists the play queue and the "was music supposed to be playing" flags used for crash/kill recovery. */
object PlaybackStore {
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"
    private const val KEY_SESSION_ALIVE = "session_alive"
    private const val KEY_HEARTBEAT = "heartbeat"

    class Saved(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(MusicPreferences.FILE, Context.MODE_PRIVATE)

    fun saveQueue(context: Context, player: Player) {
        val queue = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val uri = item.localConfiguration?.uri ?: continue
            val meta = item.mediaMetadata
            queue.put(
                JSONObject()
                    .put("uri", uri.toString())
                    .put("id", item.mediaId)
                    .put("title", meta.title?.toString().orEmpty())
                    .put("artist", meta.artist?.toString().orEmpty())
                    .put("album", meta.albumTitle?.toString().orEmpty())
                    .put("duration", meta.durationMs ?: 0L)
            )
        }
        prefs(context).edit().putString(KEY_QUEUE, queue.toString()).apply()
        saveProgress(context, player)
    }

    fun saveProgress(context: Context, player: Player) {
        prefs(context).edit()
            .putInt(KEY_INDEX, player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .putInt(KEY_REPEAT, player.repeatMode)
            .putLong(KEY_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): Saved? {
        val p = prefs(context)
        val raw = p.getString(KEY_QUEUE, null) ?: return null
        val items = runCatching {
            val array = JSONArray(raw)
            buildList<MediaItem> {
                for (index in 0 until array.length()) {
                    val entry = array.opt(index)
                    if (entry is JSONObject) {
                        val uri = Uri.parse(entry.optString("uri"))
                        val duration = entry.optLong("duration", 0L)
                        val metadata = MediaMetadata.Builder()
                            .setTitle(entry.optString("title"))
                            .setArtist(entry.optString("artist"))
                            .setAlbumTitle(entry.optString("album"))
                            .setDurationMs(duration.takeIf { it > 0L })
                            .setArtworkUri(uri)
                            .build()
                        val id = entry.optString("id").ifEmpty { uri.toString() }
                        add(MediaItem.Builder().setUri(uri).setMediaId(id).setMediaMetadata(metadata).build())
                    } else if (entry is String) {
                        add(MediaItem.fromUri(Uri.parse(entry)))
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (items.isEmpty()) return null
        return Saved(
            items = items,
            index = p.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex),
            positionMs = p.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffle = p.getBoolean(KEY_SHUFFLE, false),
            repeatMode = p.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
        )
    }

    /**
     * True while the service believes music should be playing. If a new service instance starts and this is still
     * true, the previous process was killed without cleaning up.
     */
    fun isSessionAlive(context: Context) = prefs(context).getBoolean(KEY_SESSION_ALIVE, false)

    fun setSessionAlive(context: Context, alive: Boolean) {
        prefs(context).edit().putBoolean(KEY_SESSION_ALIVE, alive).apply()
    }

    fun heartbeatAgeMs(context: Context): Long {
        val last = prefs(context).getLong(KEY_HEARTBEAT, 0L)
        return if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last
    }
}
