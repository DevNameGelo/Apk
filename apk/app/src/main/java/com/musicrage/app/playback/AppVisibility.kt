package com.musicrage.app.playback

import android.os.Handler
import android.os.Looper

/** Lets the Activity tell the (same-process) service whether the app UI is on screen, so the overlay can hide itself. */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
        private set

    @Volatile
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun set(value: Boolean) {
        visible = value
        main.post { listener?.invoke() }
    }
}
