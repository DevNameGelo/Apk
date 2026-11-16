package com.musicrage.app.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.mutableLongStateOf

/**
 * Watches MediaStore for audio changes and tells the app immediately. Before this, the library only
 * re-read MediaStore when a tab was reopened or the app resumed, so deleting an MP3 in a file manager
 * left the song listed for several seconds. With the observer the list refreshes the moment MediaStore
 * drops the row (which itself happens right after the file is gone).
 */
object LibraryObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private val bump = Runnable { version.longValue += 1L }

    /** Bumped every time MediaStore reports any audio change (insert, delete or update). */
    val version = mutableLongStateOf(0L)

    fun start(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val watch = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                // Debounce: MediaStore fires a burst of notifications for one action.
                handler.removeCallbacks(bump)
                handler.postDelayed(bump, 150L)
            }
        }
        observer = watch
        runCatching {
            app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, watch)
        }
    }

    fun stop(context: Context) {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }
}
