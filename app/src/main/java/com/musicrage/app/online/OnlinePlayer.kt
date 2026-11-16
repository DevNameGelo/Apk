package com.musicrage.app.online

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.data.OnlineVideo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * WebView that keeps believing it is visible, so the embedded player keeps playing when the app is in the
 * background (the foreground service keeps the process alive).
 */
class BackgroundWebView(context: Context) : WebView(context) {
    /** While parked the view is invisible and must not swallow touches meant for the app underneath. */
    var passThrough = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (passThrough) false else super.dispatchTouchEvent(event)
}

@Immutable
data class OnlineState(
    val video: OnlineVideo? = null,
    val isPlaying: Boolean = false,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false
)

/**
 * The loading overlay state for the Online tab: true from the moment a video is requested until the
 * page reports it is actually playing. Null when nothing is loading.
 */
object OnlineLoading {
    var active by mutableStateOf(false)
        internal set
}
/** Saved online-player state, used for "Continue playing" and for recovery after the app is killed. */
object OnlineStore {
    private const val KEY_LAST = "online_last"
    private const val KEY_POSITION = "online_position"
    private const val KEY_ALIVE = "online_alive"
    private const val KEY_BEAT = "online_beat"
    private const val KEY_HISTORY = "online_history"

    private fun prefs(context: Context) = context.getSharedPreferences(MusicPreferences.FILE, Context.MODE_PRIVATE)

    private fun toJson(video: OnlineVideo) =
        JSONObject().put("id", video.id).put("title", video.title).put("channel", video.channel).put("thumb", video.thumb)

    private fun fromJson(obj: JSONObject) =
        OnlineVideo(obj.optString("id"), obj.optString("title"), obj.optString("channel"), obj.optString("thumb"))

    fun saveLast(context: Context, video: OnlineVideo, positionSec: Double) {
        prefs(context).edit().putString(KEY_LAST, toJson(video).toString()).putFloat(KEY_POSITION, positionSec.toFloat()).apply()
    }

    fun savePosition(context: Context, positionSec: Double) {
        prefs(context).edit().putFloat(KEY_POSITION, positionSec.toFloat()).apply()
    }

    /** Removes the "continue playing" pointer so a stopped player cannot be resurrected by mistake. */
    fun clearLast(context: Context) {
        prefs(context).edit().remove(KEY_LAST).remove(KEY_POSITION).apply()
    }

    fun loadLast(context: Context): Pair<OnlineVideo, Double>? {
        val raw = prefs(context).getString(KEY_LAST, null) ?: return null
        val video = runCatching { fromJson(JSONObject(raw)) }.getOrNull() ?: return null
        if (video.id.isEmpty()) return null
        return video to prefs(context).getFloat(KEY_POSITION, 0f).toDouble()
    }

    fun history(context: Context): List<OnlineVideo> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList<OnlineVideo> { for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i))) }
        }.getOrDefault(emptyList())
    }

    fun addHistory(context: Context, video: OnlineVideo) {
        val updated = (listOf(video) + history(context).filter { it.id != video.id }).take(15)
        val array = JSONArray()
        updated.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun isAlive(context: Context) = prefs(context).getBoolean(KEY_ALIVE, false)
    fun setAlive(context: Context, alive: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALIVE, alive).apply()
    }

    fun beat(context: Context) {
        prefs(context).edit().putLong(KEY_BEAT, System.currentTimeMillis()).apply()
    }

    fun beatAgeMs(context: Context): Long {
        val last = prefs(context).getLong(KEY_BEAT, 0L)
        return if (last == 0L) Long.MAX_VALUE else System.currentTimeMillis() - last
    }

    private const val KEY_BLOCKED = "online_blocked"

    /** IDs that refuse to play in the embedded player, remembered so a restart goes straight to the watch page. */
    fun blocked(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()

    fun addBlocked(context: Context, id: String) {
        prefs(context).edit().putStringSet(KEY_BLOCKED, blocked(context) + id).apply()
    }
}

private const val PLAYER_HTML = """
<!DOCTYPE html>
<html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
<body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player = null;
var pending = null;
var loadId = null;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%',
    playerVars: { playsinline: 1, autoplay: 1, controls: 0, rel: 0, origin: 'https://www.youtube.com', enablejsapi: 1, iv_load_policy: 3, modestbranding: 1 },
    events: {
      onReady: function () { forceSound(); Android.ready(); },
      onStateChange: function (e) { if (e.data === 1) { forceSound(); } Android.stateChanged(e.data); },
      onError: function (e) { Android.failed(e.data); }
    }
  });
}
function forceSound() { try { if (player && player.isMuted && player.isMuted()) { player.unMute(); } if (player && player.setVolume) { player.setVolume(100); } } catch (e) {} }
function load(id, start) {
  loadId = id;
  pending = null;
  if (player && player.loadVideoById) { player.loadVideoById(id, start || 0); forceSound(); }
  else { pending = [id, start]; }
}
function playVideo2() { if (player) { player.playVideo(); forceSound(); } }
function pauseVideo2() { if (player) player.pauseVideo(); }
function seek(s) { if (player) player.seekTo(s, true); }
setInterval(function () {
  if (player && player.getCurrentTime && player.getDuration) {
    // Only report the video the app actually asked for. While a new video is still loading the
    // iframe keeps answering for the old one, and those numbers used to leak into the new track.
    if (loadId === null || loadId === (player.getVideoData ? player.getVideoData().video_id : loadId)) {
      Android.time(player.getCurrentTime(), player.getDuration());
    }
  }
}, 1000);
</script></body></html>
"""


/**
 * Injected into the normal YouTube watch page when a video refuses to play in the embedded player (most official
 * music videos). It shows only the video and reports state back through the same bridge.
 */
private const val WATCH_JS = """
(function () {
  if (window.__mr) { return; }
  window.__mr = true;
  try {
    Object.defineProperty(document, 'hidden', { get: function () { return false; } });
    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; } });
    document.addEventListener('visibilitychange', function (e) { e.stopImmediatePropagation(); }, true);
  } catch (e) {}
  var st = document.createElement('style');
  // Hide everything except the video, and blank out the ad slots that survive on the mobile watch page.
  st.textContent = 'html,body{background:#000!important;overflow:hidden!important}' +
    'body *{visibility:hidden!important}' +
    'video{visibility:visible!important;position:fixed!important;top:0!important;left:0!important;' +
    'width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important;' +
    'object-fit:contain!important}' +
    '.ad-showing, .ad-interrupting, ytmusic-video-player, #player-ads, .ytp-ad-module, ' +
    '.video-ads, .ytp-ad-overlay-container, .ytp-ad-image-overlay, .ytp-ad-progress, ' +
    '.ytp-ad-text, ytm-playlist-video-renderer, ytm-notification, .masthead-ad, #masthead-ad{display:none!important}';
  (document.head || document.documentElement).appendChild(st);
  function forceSound() {
    try {
      var mp = document.getElementById('movie_player');
      if (mp && mp.isMuted && mp.isMuted()) { mp.unMute(); }
      if (mp && mp.setVolume) { mp.setVolume(100); }
      var v = document.querySelector('video');
      if (v) { if (v.muted) { v.muted = false; } if (v.volume < 1) { v.volume = 1; } }
    } catch (e) {}
  }
  function adShowing() { return !!document.querySelector('.ad-showing, .ad-interrupting'); }
  function pageVideoId() {
    try {
      if (window.ytcfg && ytcfg.data_ && ytcfg.data_.videoId) { return ytcfg.data_.videoId; }
      var m = /[?&]v=([A-Za-z0-9_-]{11})/.exec(location.search);
      if (m) { return m[1]; }
    } catch (e) {}
    return null;
  }
  function skipAd() {
    // Click "Skip" if an ad grows one; harmless otherwise.
    try {
      var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
      if (b) { b.click(); }
      // Ads on the mobile page sometimes sit in an overlay the page itself opens; close it.
      var c = document.querySelector('.ytp-ad-overlay-close-button, [aria-label="Close"]');
      if (c) { c.click(); }
    } catch (e) {}
  }
  function hook() {
    var v = document.querySelector('video');
    if (!v || v.__mr) { return; }
    v.__mr = true;
    v.addEventListener('playing', function () { forceSound(); Android.stateChanged(1); });
    v.addEventListener('pause', function () { if (!v.ended && !adShowing()) { Android.stateChanged(2); } });
    v.addEventListener('ended', function () { if (!adShowing()) { Android.stateChanged(0); } });
    try { var r = v.play(); if (r && r.catch) { r.catch(function () {}); } } catch (e) {}
  }
  window.mrPlay = function () { var v = document.querySelector('video'); if (v) { v.play(); } };
  window.mrPause = function () { var v = document.querySelector('video'); if (v) { v.pause(); } };
  window.mrSeek = function (s) { var v = document.querySelector('video'); if (v) { v.currentTime = s; } };
  setInterval(function () {
    hook();
    forceSound();
    if (adShowing()) { skipAd(); }
    var v = document.querySelector('video');
    if (v && v.duration > 0 && isFinite(v.duration) && !adShowing()) {
      var id = pageVideoId();
      // The watch page auto-advances to a related video when one ends, and ads can force their own
      // navigation. Tell the app which video the page is REALLY on so the card and notification
      // can follow it instead of showing a stale title.
      Android.pageVideo(id || '');
      Android.time(v.currentTime, v.duration);
    }
  }, 1000);
})();
"""

private class Bridge {
    @JavascriptInterface
    fun ready() = OnlinePlayer.handleReady()

    @JavascriptInterface
    fun stateChanged(code: Int) = OnlinePlayer.handleState(code)

    @JavascriptInterface
    fun time(position: Double, duration: Double) = OnlinePlayer.handleTime(position, duration)

    @JavascriptInterface
    fun failed(code: Int) = OnlinePlayer.handleError(code)

    @JavascriptInterface
    fun pageVideo(id: String) = OnlinePlayer.handlePageVideo(id)
}

object OnlinePlayer {
    private const val PARK_W = 320
    private const val PARK_H = 180
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var web: BackgroundWebView? = null
    private var pageReady = false
    private var pendingLoad: Pair<String, Double>? = null
    private var queue: List<OnlineVideo> = emptyList()
    private var index = -1
    private var lastSave = 0L
    private var lastPlaced: IntArray? = null
    private var watchMode = false
    private var watchToken = 0
    private var failStreak = 0

    /**
     * Rises every time a new video starts or playback is stopped. Events still arriving from the page
     * (the JS interval keeps reporting the OLD video while the new one loads) carry an older generation
     * and are dropped — this is what made a new track inherit the previous track's position
     * ("skip to 50 seconds") and what could re-light a player the user had just replaced.
     */
    private var generation = 0L

    var state by mutableStateOf(OnlineState())
        private set
    var positionSec by mutableDoubleStateOf(0.0)
        private set
    var durationSec by mutableDoubleStateOf(0.0)
        private set

    /** Called on the main thread whenever playback state changes (the service refreshes its notification). */
    var listener: (() -> Unit)? = null

    /** Lets this player pause the local-file player when online playback starts. */
    var pauseLocal: (() -> Unit)? = null

    /** Called by the supervisor when local playback takes over the notification: pause without touching its own state. */
    fun pauseForTakeover() {
        jsPause()
    }

    /** Called by the supervisor's ONLINE claim so a queued local start does not keep playing under the online video. */
    fun pauseLocalForTakeover() {
        pauseLocal?.invoke()
    }

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): BackgroundWebView {
        web?.let {
            if (it.parent == null && !overlayHosted && overlayAllowed()) hostInBackground(warn = false)
            return it
        }
        val context = appContext ?: throw IllegalStateException("OnlinePlayer.init was not called")
        val view = BackgroundWebView(context)
        view.setBackgroundColor(Color.BLACK)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        // Sandbox: the page can only talk to YouTube (player, thumbnail and API delivery). Ad and tracker
        // hosts are refused outright, so their scripts never run and their videos never get requested.
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                return !isAllowedHost(host)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return BLOCKED_RESPONSE
                return if (isAllowedHost(host)) null else BLOCKED_RESPONSE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // Kill any pop-up the page managed to open on top of the video, then re-inject the controls.
                if (watchMode && url != null && url.contains("youtube.com/watch")) {
                    view.evaluateJavascript(
                        "(function(){try{var w=document.querySelector('.ytp-popup, ytm-popup-container, dialog, [role=dialog]');if(w){w.remove();}}catch(e){}})();",
                        null
                    )
                    view.evaluateJavascript(WATCH_JS, null)
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            // A sandboxed player never opens new windows: every pop-up ad dies here.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, result: android.os.Message): Boolean {
                // Returning false refuses the window; nothing is sent through the result message.
                return false
            }
        }
        view.addJavascriptInterface(Bridge(), "Android")
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(view, true)
            cookies.setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/")
            cookies.setCookie("https://www.youtube.com", "CONSENT=YES+1; Domain=.youtube.com; Path=/")
        }
        loadEmbedPage(view)
        web = view
        if (overlayAllowed()) hostInBackground(warn = false)
        return view
    }

    /** YouTube pages plus the few CDN/API hosts the player and thumbnails genuinely need. */
    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com") || h.endsWith("ytimg.com") ||
            h.endsWith("googlevideo.com") || h.endsWith("ggpht.com") || h.endsWith("gstatic.com") ||
            h.endsWith("googleapis.com") || h.endsWith("google.com")
        ) {
            // Refuse the known ad-serving subdomains even inside otherwise-allowed domains.
            val adMarkers = listOf("ads", "adserver", "admob", "doubleclick", "googlesyndication", "adservice", "partnerads")
            return adMarkers.none { marker -> h == marker || h.startsWith("$marker.") || h.contains(".$marker.") }
        }
        return false
    }

    /** Empty 200 response served in place of every blocked request. */
    private val BLOCKED_RESPONSE: WebResourceResponse by lazy {
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun loadEmbedPage(view: WebView) {
        watchMode = false
        pageReady = false
        view.loadDataWithBaseURL("https://www.youtube.com", PLAYER_HTML, "text/html", "UTF-8", null)
    }

    private fun loadWatchPage(id: String, startSec: Double) {
        val view = web ?: return
        watchMode = true
        pageReady = false
        val start = startSec.toInt()
        view.loadUrl("https://m.youtube.com/watch?v=$id" + if (start > 0) "&t=${start}s" else "")
    }

    // ---- Window placement ---------------------------------------------------------------------------------------

    /** Adds the player view on top of the Activity content. It is parked as a 1px view when not on screen. */
    fun attach(activity: Activity) {
        val view = webView()
        if (overlayAllowed()) {
            if (!overlayHosted) {
                (view.parent as? ViewGroup)?.removeView(view)
                hostInBackground(warn = false)
            }
            if (overlayHosted) return
        }
        releaseOverlay()
        (view.parent as? ViewGroup)?.removeView(view)
        lastPlaced = null
        view.alpha = 0.02f
        view.passThrough = true
        activity.addContentView(view, FrameLayout.LayoutParams(PARK_W, PARK_H).apply { gravity = Gravity.TOP or Gravity.START })
    }

    /** Called when the Activity goes away. If music is playing, the player view moves to a tiny overlay window. */
    fun detach() {
        if (overlayHosted) return
        web?.let { (it.parent as? ViewGroup)?.removeView(it) }
        lastPlaced = null
        if (state.isPlaying) hostInBackground()
    }

    private var overlayHosted = false

    /** True while the player view lives in the app-owned background window (the Online tab then shows the cover). */
    var inOverlay by mutableStateOf(false)
        private set

    fun releaseHost() = releaseOverlay()

    fun overlayAllowed(): Boolean = appContext?.let { Settings.canDrawOverlays(it) } == true

    /**
     * Keeps the player in the background window for good whenever "Display over other apps" is allowed. The view is
     * then never moved between windows, which is what made playback pause when switching tabs or reopening the app.
     */
    fun syncHost(activity: Activity) {
        val view = web ?: return
        if (overlayAllowed()) {
            if (!overlayHosted) {
                (view.parent as? ViewGroup)?.removeView(view)
                lastPlaced = null
                hostInBackground(warn = false)
            }
        } else if (overlayHosted) {
            releaseOverlay()
            attach(activity)
        }
    }
    private var warnedOverlay = false

    private fun releaseOverlay() {
        val view = web ?: return
        if (!overlayHosted) return
        val context = appContext ?: return
        runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(view) }
        overlayHosted = false
        inOverlay = false
    }

    /**
     * Keeps the player view attached to a window after the app was closed, so the page keeps believing it is on
     * screen and keeps playing. Needs "Display over other apps"; without it the view is only kept resumed.
     */
    fun hostInBackground(warn: Boolean = true) {
        val view = web ?: return
        val context = appContext ?: return
        if (view.parent != null || overlayHosted) return
        view.onResume()
        view.resumeTimers()
        if (!Settings.canDrawOverlays(context)) {
            if (warn && !warnedOverlay) {
                warnedOverlay = true
                com.musicrage.app.Toaster.show(context, "Turn on \"Display over other apps\" for Music Rage so online music keeps playing after the app is closed", long = true)
            }
            return
        }
        runCatching {
            val params = WindowManager.LayoutParams(
                PARK_W, PARK_H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            view.passThrough = true
            view.alpha = 0.02f
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
            overlayHosted = true
            inOverlay = true
        }
    }

    /** The video surface is never placed on screen any more; the Online tab draws its cover instead. */
    fun place(x: Int, y: Int, width: Int, height: Int) { }

    fun park() {
        if (overlayHosted) return
        val view = web ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val wanted = intArrayOf(0, 0, PARK_W, PARK_H)
        if (lastPlaced?.contentEquals(wanted) == true) return
        // Keep a real player size while parked (a 1px player gets paused by the page); it is invisible and touch-proof.
        params.width = PARK_W
        params.height = PARK_H
        params.leftMargin = 0
        params.topMargin = 0
        view.passThrough = true
        view.alpha = 0.02f
        view.layoutParams = params
        lastPlaced = wanted
    }

    // ---- Playback -----------------------------------------------------------------------------------------------

    private fun runJs(script: String) {
        main.post { web?.evaluateJavascript(script, null) }
    }

    fun play(video: OnlineVideo, list: List<OnlineVideo>) {
        queue = list
        index = list.indexOfFirst { it.id == video.id }
        if (index < 0) {
            queue = listOf(video)
            index = 0
        }
        startVideo(video, 0.0, true)
    }

    /** Used by the service after the process was killed: reload the last video at its saved position. */
    fun recover(): Boolean {
        val context = appContext ?: return false
        val last = OnlineStore.loadLast(context) ?: return false
        queue = listOf(last.first)
        index = 0
        startVideo(last.first, last.second, false)
        return true
    }

    private fun startVideo(video: OnlineVideo, startSec: Double, startService: Boolean) {
        if (!Regex("[A-Za-z0-9_-]{6,20}").matches(video.id)) return
        val context = appContext ?: return
        // Any event still on its way from the previous video is now obsolete.
        generation += 1
        val gen = generation
        failStreak = 0
        // Claim the single media notification before anything starts.
        com.musicrage.app.playback.PlaybackSupervisor.claim(context, com.musicrage.app.playback.PlaybackSupervisor.Owner.ONLINE)
        state = OnlineState(video, isPlaying = true, hasPrev = index > 0, hasNext = index < queue.size - 1)
        positionSec = startSec
        durationSec = 0.0
        OnlineLoading.active = true
        OnlineStore.addHistory(context, video)
        OnlineStore.saveLast(context, video, startSec)
        OnlineStore.setAlive(context, true)
        if (startService) {
            runCatching { context.startForegroundService(Intent(context, OnlineService::class.java)) }
        }
        val view = webView()
        watchToken += 1
        pendingLoad = null
        if (OnlineStore.blocked(context).contains(video.id)) {
            main.post { loadWatchPage(video.id, startSec) }
            armWatchTimeout(gen)
        } else {
            if (watchMode) main.post { loadEmbedPage(view) }
            if (pageReady && !watchMode) {
                runJs("load('${video.id}',${startSec.toInt()})")
            } else {
                pendingLoad = video.id to startSec
            }
        }
        listener?.invoke()
    }

    /** If even the watch page never starts, give up on this video and move on. */
    private fun armWatchTimeout(gen: Long = 0L) {
        val token = watchToken
        main.postDelayed({
            if (token == watchToken && gen == generation && OnlineLoading.active) {
                val context = appContext
                if (context != null && state.video != null) {
                    com.musicrage.app.Toaster.show(context, "This video can't be played")
                }
                if (index + 1 < queue.size) next() else {
                    OnlineLoading.active = false
                    state = state.copy(isPlaying = false)
                    listener?.invoke()
                }
            }
        }, 25_000L)
    }

    private fun jsPlay() = runJs(if (watchMode) "window.mrPlay && mrPlay()" else "playVideo2()")
    private fun jsPause() = runJs(if (watchMode) "window.mrPause && mrPause()" else "pauseVideo2()")

    fun toggle() {
        if (state.isPlaying) {
            jsPause()
        } else {
            // Take the single notification back from the local player if it holds it.
            appContext?.let { com.musicrage.app.playback.PlaybackSupervisor.claim(it, com.musicrage.app.playback.PlaybackSupervisor.Owner.ONLINE) }
            pauseLocal?.invoke()
            jsPlay()
        }
    }

    fun pause() {
        if (state.isPlaying) jsPause()
    }

    fun toggleIfPaused() {
        if (!state.isPlaying) toggle()
    }

    fun next() {
        if (index + 1 < queue.size) {
            index += 1
            startVideo(queue[index], 0.0, false)
        }
    }

    fun previous() {
        if (positionSec > 5.0 || index <= 0) {
            seekTo(0.0)
        } else {
            index -= 1
            startVideo(queue[index], 0.0, false)
        }
    }

    fun seekTo(seconds: Double) {
        positionSec = seconds
        runJs(if (watchMode) "window.mrSeek && mrSeek(${seconds.toInt()})" else "seek(${seconds.toInt()})")
    }

    // ---- Callbacks from the page (called on a WebView thread) ------------------------------------------------------

    internal fun handleReady() {
        main.post {
            if (watchMode) return@post
            pageReady = true
            pendingLoad?.let { runJs("load('${it.first}',${it.second.toInt()})") }
            pendingLoad = null
        }
    }

    internal fun handleState(code: Int) {
        // Capture the generation on the WebView thread, before the hop to the main thread.
        val gen = generation
        main.post {
            // The user started another video or stopped playback while this event was in flight: drop it.
            if (gen != generation) return@post
            val context = appContext
            when (code) {
                1 -> {
                    // The page is really playing now: the new track is live and the loading overlay goes away.
                    OnlineLoading.active = false
                    failStreak = 0
                    state = state.copy(isPlaying = true)
                    context?.let { OnlineStore.setAlive(it, true) }
                }
                2 -> {
                    state = state.copy(isPlaying = false)
                    context?.let { OnlineStore.setAlive(it, false) }
                }
                0 -> {
                    // Track finished: continue with the queue, or find something similar on YouTube.
                    if (index + 1 < queue.size) {
                        next()
                    } else {
                        state = state.copy(isPlaying = false)
                        context?.let { OnlineStore.setAlive(it, false) }
                        autoplaySimilar(context)
                    }
                }
            }
            listener?.invoke()
        }
    }

    /** After the last queue item ends, quietly loads a related song so playback never just stops. */
    private fun autoplaySimilar(context: Context?) {
        val finished = state.video ?: return
        if (context == null) return
        val seed = finished.title.substringBefore(" (").substringBefore(" [").substringBefore("Official").trim()
        val query = if (finished.channel.isNotBlank()) "${finished.channel} music" else seed.ifEmpty { return }
        val blocked = OnlineStore.blocked(context)
        Thread {
            val found = runCatching { com.musicrage.app.data.OnlineApi.search(query) }.getOrDefault(emptyList())
            val pick = found.firstOrNull { it.id != finished.id && !blocked.contains(it.id) }
            main.post {
                if (pick != null && state.video?.id == finished.id && !state.isPlaying) {
                    queue = listOf(finished, pick)
                    index = 1
                    startVideo(pick, 0.0, false)
                }
            }
        }.start()
    }

    internal fun handleTime(position: Double, duration: Double) {
        // Capture the generation on the WebView thread, before the hop to the main thread.
        val gen = generation
        main.post {
            // Reports from the previous video (still ticking while the new one loads) must not
            // overwrite the new track's position — that was the "skips to 50 seconds" bug.
            if (gen != generation) return@post
            positionSec = position
            durationSec = if (duration > 0.0) duration else durationSec
            // The player is clearly playing once the clock advances. Some pages never send the "playing"
            // event for this video, which left the spinner on forever while the song was audible.
            if (OnlineLoading.active && position > 0.4 && state.video != null) {
                OnlineLoading.active = false
                failStreak = 0
                listener?.invoke()
            }
            val now = System.currentTimeMillis()
            val context = appContext
            if (context != null && state.isPlaying && now - lastSave > 5_000L) {
                lastSave = now
                OnlineStore.savePosition(context, position)
            }
        }
    }

    /**
     * The watch page reports which video it is really on. YouTube auto-advances to a related video when
     * one ends (and ads can force their own navigation), so the card, mini bar and notification used to
     * keep showing the OLD title while something else played. Follow the page instead.
     */
    internal fun handlePageVideo(id: String) {
        val gen = generation
        main.post {
            if (gen != generation || id.isBlank() || watchMode) return@post
            val current = state.video ?: return@post
            if (id == current.id) return@post
            // The page moved on. Update the shown video so the UI and notification follow it.
            val nextVideo = OnlineVideo(id, "Playing next", "Online", current.thumb)
            state = state.copy(video = nextVideo)
            queue = listOf(nextVideo)
            index = 0
            positionSec = 0.0
            durationSec = 0.0
            OnlineLoading.active = true
            val context = appContext
            if (context != null) {
                OnlineStore.addHistory(context, nextVideo)
                OnlineStore.saveLast(context, nextVideo, 0.0)
                // Fill in the real title in the background so the card does not stay generic.
                Thread {
                    val found = runCatching { com.musicrage.app.data.OnlineApi.search(id) }.getOrDefault(emptyList())
                    val match = found.firstOrNull { it.id == id }
                    if (match != null) main.post {
                        if (state.video?.id == id) {
                            state = state.copy(video = match)
                            OnlineStore.saveLast(context, match, 0.0)
                            listener?.invoke()
                        }
                    }
                }.start()
            }
            listener?.invoke()
        }
    }

    internal fun handleError(code: Int) {
        val gen = generation
        main.post {
            if (gen != generation) return@post
            val video = state.video
            val context = appContext
            // No online song is loading (fresh start, or the local player just took over): an error from the
            // idle page is not worth a toast.
            if (video == null) return@post
            // The song is already audible; a stray error from an old page must not interrupt it.
            if (!OnlineLoading.active && state.isPlaying && positionSec > 1.0) return@post
            // Embedding is blocked or misconfigured for this video: play it from the normal watch page instead.
            if (!watchMode && video != null && context != null && !OnlineStore.blocked(context).contains(video.id)) {
                OnlineStore.addBlocked(context, video.id)
                watchToken += 1
                loadWatchPage(video.id, 0.0)
                armWatchTimeout(gen)
                return@post
            }
            // The watch page itself failed: fall back to the embed player for the next attempt.
            if (watchMode && video != null) {
                watchToken += 1
                loadEmbedPage(web ?: return@post)
                pendingLoad = video.id to positionSec
            }
            // Several attempts failed in a row: stop trying instead of looping through the same
            // failure (the "can't be played" toast spam).
            failStreak += 1
            if (failStreak >= 3) {
                failStreak = 0
                OnlineLoading.active = false
                if (context != null) com.musicrage.app.Toaster.show(context, "This video can't be played")
                stop(clearView = false)
                return@post
            }
            if (context != null) com.musicrage.app.Toaster.show(context, "This video can't be played")
            if (index + 1 < queue.size) next() else {
                OnlineLoading.active = false
                state = state.copy(isPlaying = false)
                listener?.invoke()
            }
        }
    }

    /** Full stop used when the LOCAL player takes over: clears state so the app shows the local player again. */
    fun stop(clearView: Boolean = true) {
        generation += 1
        watchToken += 1
        jsPause()
        OnlineLoading.active = false
        state = OnlineState()
        positionSec = 0.0
        durationSec = 0.0
        queue = emptyList()
        index = -1
        failStreak = 0
        val context = appContext
        if (context != null) {
            OnlineStore.setAlive(context, false)
            OnlineStore.savePosition(context, 0.0)
            OnlineStore.clearLast(context)
            if (clearView && watchMode) {
                // Put the WebView back to a clean embed page so a later start never resumes the old video.
                pendingLoad = null
                web?.let { loadEmbedPage(it) }
            }
        }
        listener?.invoke()
    }
}
