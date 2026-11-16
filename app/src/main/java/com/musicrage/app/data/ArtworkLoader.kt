package com.musicrage.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads cover art for an audio URI. Tries the MediaStore thumbnail first (embedded art or folder art) and
 * falls back to reading the embedded picture straight from the file, which also covers files opened from
 * other apps.
 */
object ArtworkLoader {
    private val bitmaps = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 10L).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val colors = ConcurrentHashMap<String, Int>()

    private fun cacheKey(uri: Uri, size: Int) = "$uri#$size"

    fun peek(uri: Uri, size: Int): Bitmap? = bitmaps.get(cacheKey(uri, size))

    fun load(context: Context, uri: Uri, size: Int): Bitmap? {
        val key = cacheKey(uri, size)
        bitmaps.get(key)?.let { return it }
        if (key in missing) return null
        var bitmap: Bitmap? = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(size, size), null)
        }.getOrNull()
        if (bitmap == null) bitmap = embeddedPicture(context, uri, size)
        if (bitmap == null) {
            missing.add(key)
            return null
        }
        bitmaps.put(key, bitmap)
        return bitmap
    }

    /** Downloads a thumbnail from the web (online player rows). */
    fun loadUrl(url: String): Bitmap? {
        val key = "url:$url"
        bitmaps.get(key)?.let { return it }
        val bitmap = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            try {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (bitmap != null) bitmaps.put(key, bitmap)
        return bitmap
    }

    fun peekUrl(url: String): Bitmap? = bitmaps.get("url:$url")

    private fun embeddedPicture(context: Context, uri: Uri, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** A dark, saturated tone pulled from the cover, used to tint the player background. */
    fun dominantColor(context: Context, uri: Uri): Int? {
        val cached = colors[uri.toString()]
        if (cached != null) return cached
        val source = load(context, uri, 160) ?: return null
        val small = Bitmap.createScaledBitmap(source, 24, 24, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weightSum = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val pixel = small.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val weight = (hsv[1] * hsv[2]).toDouble() + 0.04
                r += android.graphics.Color.red(pixel) * weight
                g += android.graphics.Color.green(pixel) * weight
                b += android.graphics.Color.blue(pixel) * weight
                weightSum += weight
            }
        }
        if (small !== source) small.recycle()
        if (weightSum <= 0.0) return null
        val average = android.graphics.Color.rgb((r / weightSum).toInt(), (g / weightSum).toInt(), (b / weightSum).toInt())
        android.graphics.Color.colorToHSV(average, hsv)
        hsv[1] = hsv[1].coerceAtMost(0.78f)
        hsv[2] = hsv[2].coerceIn(0.28f, 0.58f)
        val result = android.graphics.Color.HSVToColor(hsv)
        colors[uri.toString()] = result
        return result
    }
}
