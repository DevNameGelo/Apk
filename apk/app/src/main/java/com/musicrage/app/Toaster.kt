package com.musicrage.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Single place for every toast in the app. Fixes the old toast spam, where recovery, downloads and
 * library scans each fired their own toasts that stacked up and arrived late:
 *  - identical messages shown again within [DEDUPE_MS] are dropped;
 *  - messages already waiting in the queue are not added twice;
 *  - toasts are shown one after another with a small gap instead of piling on top of each other;
 *  - the queue is capped, so a burst of system messages can never flood the screen.
 */
object Toaster {
    private const val DEDUPE_MS = 4_000L
    private const val GAP_MS = 350L
    private const val MAX_QUEUE = 3

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var showing = false
    private var lastMessage: String? = null
    private var lastShownAt = 0L

    fun show(context: Context, message: String, long: Boolean = false) {
        val app = context.applicationContext
        handler.post {
            val now = android.os.SystemClock.uptimeMillis()
            if (message == lastMessage && now - lastShownAt < DEDUPE_MS) return@post
            if (message in queue) return@post
            while (queue.size >= MAX_QUEUE) queue.removeFirst()
            queue.add(message)
            pump(app, long)
        }
    }

    private fun pump(context: Context, long: Boolean) {
        if (showing) return
        val message = queue.removeFirstOrNull() ?: return
        showing = true
        lastMessage = message
        lastShownAt = android.os.SystemClock.uptimeMillis()
        runCatching { Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
        val duration = if (long) 3_500L else 2_100L
        handler.postDelayed({
            showing = false
            pump(context, long = false)
        }, duration + GAP_MS)
    }
}
