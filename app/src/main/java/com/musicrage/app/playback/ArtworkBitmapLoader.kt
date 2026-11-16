package com.musicrage.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.musicrage.app.data.ArtworkLoader
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** Supplies notification / lock-screen artwork from the audio file itself. */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor = Executors.newSingleThreadExecutor()

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = submit {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IllegalStateException("Could not decode artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = submit {
        ArtworkLoader.load(context.applicationContext, uri, 512) ?: throw IllegalStateException("No artwork")
    }

    private fun submit(work: () -> Bitmap): ListenableFuture<Bitmap> {
        val task = BitmapFuture(Callable { work() })
        executor.execute(task)
        return task
    }

    /** Minimal ListenableFuture built on FutureTask so no extra Guava classes are required. */
    private class BitmapFuture(callable: Callable<Bitmap>) : FutureTask<Bitmap>(callable), ListenableFuture<Bitmap> {
        private val pending = ArrayList<Pair<Runnable, Executor>>()
        private var finished = false

        override fun addListener(listener: Runnable, executor: Executor) {
            val runNow = synchronized(this) {
                if (finished) true else {
                    pending.add(listener to executor)
                    false
                }
            }
            if (runNow) executor.execute(listener)
        }

        override fun done() {
            val toRun = synchronized(this) {
                finished = true
                val copy = ArrayList(pending)
                pending.clear()
                copy
            }
            toRun.forEach { (listener, executor) -> executor.execute(listener) }
        }
    }
}
