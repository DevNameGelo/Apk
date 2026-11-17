package com.musicrage.app.playback

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import com.musicrage.app.MainActivity
import com.musicrage.app.R
import com.musicrage.app.data.ArtworkLoader
import com.musicrage.app.data.MusicPreferences
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draggable overlay controller. The whole card can be dragged; the buttons still receive normal taps because the
 * container only intercepts a touch once it moves past the platform touch slop.
 */
class FloatingPlayer(
    private val context: Context,
    private val playerProvider: () -> Player?
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private var root: OverlayLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var art: ImageView? = null
    private var title: TextView? = null
    private var subtitle: TextView? = null
    private var playButton: ImageView? = null
    private var shownMediaId: String? = null

    val isShowing: Boolean get() = root != null

    private fun dp(value: Int): Int = (value * density).roundToInt()

    fun show() {
        if (root != null) {
            refresh()
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        val layout = OverlayLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF2181820.toInt())
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(10).toFloat()
        }

        val artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF2A2A33.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            setOnClickListener { openApp() }
        }
        layout.addView(artView, LinearLayout.LayoutParams(dp(44), dp(44)))

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener { openApp() }
        }
        val titleView = TextView(context).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFFB3B3BE.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        textColumn.addView(titleView, LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))
        textColumn.addView(subtitleView, LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(textColumn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun button(icon: Int, description: String, sizeDp: Int, onClick: () -> Unit): ImageView {
            val view = ImageView(context).apply {
                setImageResource(icon)
                contentDescription = description
                setColorFilter(0xFFFFFFFF.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onClick() }
            }
            layout.addView(view, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
            return view
        }

        button(R.drawable.ic_skip_prev, "Previous", 38) {
            playerProvider()?.let { p ->
                if (p.currentPosition > 3_000L || !p.hasPreviousMediaItem()) p.seekTo(0L) else p.seekToPreviousMediaItem()
            }
        }
        val play = button(R.drawable.ic_play, "Play or pause", 42) {
            playerProvider()?.let { p ->
                if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
                    p.pause()
                } else {
                    if (p.playbackState == Player.STATE_IDLE) p.prepare()
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0L)
                    p.play()
                }
            }
        }
        button(R.drawable.ic_skip_next, "Next", 38) {
            playerProvider()?.let { p ->
                if (p.hasNextMediaItem()) p.seekToNextMediaItem() else p.seekTo(0, 0L)
            }
        }
        button(R.drawable.ic_x, "Close floating player", 34) {
            MusicPreferences.setFloatingEnabled(context, false)
            hide()
        }

        val metrics = context.resources.displayMetrics
        val saved = MusicPreferences.floatingPosition(context)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (saved?.first ?: dp(12)).coerceIn(0, (metrics.widthPixels - dp(60)).coerceAtLeast(0))
            y = (saved?.second ?: dp(160)).coerceIn(0, (metrics.heightPixels - dp(80)).coerceAtLeast(0))
        }

        var startX = 0
        var startY = 0
        layout.onDragStart = {
            startX = layoutParams.x
            startY = layoutParams.y
        }
        layout.onDrag = { dx, dy ->
            val maxX = (metrics.widthPixels - layout.width).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - layout.height).coerceAtLeast(0)
            layoutParams.x = (startX + dx).roundToInt().coerceIn(0, maxX)
            layoutParams.y = (startY + dy).roundToInt().coerceIn(0, maxY)
            runCatching { windowManager.updateViewLayout(layout, layoutParams) }
        }
        layout.onDragEnd = {
            MusicPreferences.setFloatingPosition(context, layoutParams.x, layoutParams.y)
        }

        val added = runCatching { windowManager.addView(layout, layoutParams) }.isSuccess
        if (!added) return
        root = layout
        params = layoutParams
        art = artView
        title = titleView
        subtitle = subtitleView
        playButton = play
        shownMediaId = null
        refresh()
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        params = null
        art = null
        title = null
        subtitle = null
        playButton = null
        shownMediaId = null
    }

    fun refresh() {
        if (root == null) return
        val player = playerProvider()
        val item = player?.currentMediaItem
        title?.text = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Nothing playing"
        subtitle?.text = item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: ""
        val playing = player != null && player.playWhenReady && player.playbackState != Player.STATE_ENDED
        playButton?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        val mediaId = item?.mediaId.orEmpty()
        if (mediaId != shownMediaId) {
            shownMediaId = mediaId
            val artworkUri = item?.mediaMetadata?.artworkUri
            art?.setImageResource(R.drawable.ic_music)
            art?.scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (artworkUri != null) {
                Thread {
                    val bitmap = ArtworkLoader.load(context.applicationContext, artworkUri, 160)
                    main.post {
                        if (shownMediaId == mediaId && bitmap != null) {
                            art?.scaleType = ImageView.ScaleType.CENTER_CROP
                            art?.setImageBitmap(bitmap)
                        }
                    }
                }.start()
            }
        }
    }

    private fun openApp() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }

    private class OverlayLayout(context: Context) : LinearLayout(context) {
        var onDragStart: (() -> Unit)? = null
        var onDrag: ((Float, Float) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null

        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private fun beginDragIfMoved(event: MotionEvent) {
            if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                dragging = true
                onDragStart?.invoke()
            }
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> beginDragIfMoved(event)
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    beginDragIfMoved(event)
                    if (dragging) onDrag?.invoke(event.rawX - downX, event.rawY - downY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDragEnd?.invoke()
                    dragging = false
                }
            }
            return true
        }
    }
}
