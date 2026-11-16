package com.musicrage.app.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object MusicLibrary {
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "amr", "mka", "m4b", "3gp", "ac3"
    )

    /**
     * Reads every audio file Android has indexed. The old build filtered on IS_MUSIC, which silently hid
     * downloaded files and anything without a music tag. Only ringtones, notifications and alarms are excluded.
     */
    fun scan(context: Context): List<AudioTrack> {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val selection = "(${MediaStore.Audio.Media.IS_RINGTONE} IS NULL OR ${MediaStore.Audio.Media.IS_RINGTONE} = 0) AND " +
            "(${MediaStore.Audio.Media.IS_NOTIFICATION} IS NULL OR ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0) AND " +
            "(${MediaStore.Audio.Media.IS_ALARM} IS NULL OR ${MediaStore.Audio.Media.IS_ALARM} = 0)"
        val result = ArrayList<AudioTrack>()
        val overrides = MusicPreferences.titleOverrides(context)
        runCatching {
            resolver.query(collection, projection, selection, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val folderCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "audio"
                    val rawTitle = cursor.getString(titleCol)
                    val title = cleanTitle(
                        if (rawTitle.isNullOrBlank()) displayName.substringBeforeLast('.', displayName) else rawTitle
                    )
                    val uri = ContentUris.withAppendedId(collection, id)
                    val folder = if (folderCol >= 0) cursor.getString(folderCol).orEmpty().trim('/') else ""
                    result += AudioTrack(
                        id = id,
                        title = overrides[uri.toString()] ?: title,
                        artist = cleanTag(cursor.getString(artistCol), UNKNOWN_ARTIST),
                        album = cleanTag(cursor.getString(albumCol), UNKNOWN_ALBUM),
                        durationMs = cursor.getLong(durationCol),
                        uri = uri,
                        albumId = cursor.getLong(albumIdCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        displayName = displayName,
                        mimeType = cursor.getString(mimeCol) ?: "audio/*",
                        dateAdded = cursor.getLong(addedCol),
                        folder = folder.ifEmpty { "Other" }
                    )
                }
            }
        }
        return result
    }

    /**
     * Finds audio files on shared storage that MediaStore has not indexed yet and asks Android to scan them.
     * Runs on the calling (background) thread; [onFinished] receives the number of files submitted.
     */
    fun syncUnindexed(context: Context, onFinished: (Int) -> Unit) {
        val missing = ArrayList<String>()
        runCatching {
            val known = HashSet<String>()
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) cursor.getString(dataCol)?.let { known.add(it) }
            }
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory()
            val budget = AtomicInteger(30_000)
            walk(root, 0, known, missing, budget)
        }
        if (missing.isEmpty()) {
            onFinished(0)
            return
        }
        val remaining = AtomicInteger(missing.size)
        val total = missing.size
        val started = runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, missing.toTypedArray(), null) { _, _ ->
                if (remaining.decrementAndGet() == 0) onFinished(total)
            }
        }.isSuccess
        if (!started) onFinished(0)
    }

    private fun walk(dir: File, depth: Int, known: Set<String>, missing: MutableList<String>, budget: AtomicInteger) {
        if (depth > 8 || budget.get() <= 0) return
        val children = dir.listFiles() ?: return
        if (children.any { it.name == ".nomedia" }) return
        for (child in children) {
            if (budget.decrementAndGet() <= 0) return
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (depth == 0 && name == "Android") continue
                walk(child, depth + 1, known, missing, budget)
            } else {
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension in AUDIO_EXTENSIONS && child.length() > 0L && child.absolutePath !in known) {
                    missing.add(child.absolutePath)
                }
            }
        }
    }
}
