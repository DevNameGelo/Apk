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
import android.webkit.PermissionRequest
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
    /** True while the view lives in the app-owned background overlay window (invisible, touch-through). */
    var passThrough = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Always report visible so an invisible player is never auto-paused.
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        if (passThrough) false else super.dispatchTouchEvent(event)
}

data class OnlineState(
    val video: OnlineVideo? = null,
    val isPlaying: Boolean = false,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = true
)

/**
 * The loading overlay state for the Online tab: true from the moment a video is requested until the
 * page reports it is actually playing.
 */
object OnlineLoading {
    var active by mutableStateOf(false)
    internal set
}

/**
 * True while an ad is playing in the embedded player. The Online tab shows its
 * "Ads — please wait" chip from this, and nothing else: when no ad runs the chip is gone.
 */
object OnlineAdState {
    var showing by mutableStateOf(false)
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

    private fun toJson(video: OnlineVideo) = JSONObject()
        .put("id", video.id).put("title", video.title).put("channel", video.channel).put("thumb", video.thumb)
        .put("permalink", video.permalink)
        // A NaN here throws JSONException ("Forbidden numeric value") on save and crashed the app on
        // the Online tab when an entry from an older build was rewritten, so it is never stored.
        .put("duration", if (video.duration.isNaN() || video.duration < 0) 0.0 else video.duration)
        .put("playable", video.playable)
        .put("album", video.album)

    private fun fromJson(obj: JSONObject) = OnlineVideo(
        obj.optString("id"),
        obj.optString("title"),
        obj.optString("channel"),
        obj.optString("thumb"),
        obj.optString("permalink"),
        // Entries saved by older builds have no "duration"; the no-fallback optDouble would hand
        // back NaN and blow up the very next save. Anything unreadable counts as "unknown" (0), not NaN.
        obj.optDouble("duration", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        obj.optBoolean("playable", true),
        obj.optLong("likes", 0L),
        // Older entries have no "album"; the download falls back to the song title when blank.
        obj.optString("album")
    )

    fun saveLast(context: Context, video: OnlineVideo, positionSec: Double) {
        prefs(context).edit().putString(KEY_LAST, toJson(video).toString()).putFloat(KEY_POSITION, positionSec.toFloat()).apply()
    }

    fun savePosition(context: Context, positionSec: Double) {
        prefs(context).edit().putFloat(KEY_POSITION, positionSec.toFloat()).apply()
    }

    /** Removes the "continue playing" pointer so a stopped player cannot be resurrected by mistake. */
    fun clearLast(context: Context) {
        prefs(context).edit().remove(KEY_LAST).remove(KEY_POSITION).putBoolean(KEY_ALIVE, false).apply()
    }

    fun loadLast(context: Context): Pair<OnlineVideo, Double>? {
        val raw = prefs(context).getString(KEY_LAST, null) ?: return null
        val position = prefs(context).getFloat(KEY_POSITION, 0f).toDouble()
        return runCatching { fromJson(JSONObject(raw)) to position }.getOrNull()
    }

    fun history(context: Context): List<OnlineVideo> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun removeHistory(context: Context, id: String) {
        val kept = history(context).filter { it.id != id }
        prefs(context).edit().putString(KEY_HISTORY, JSONArray().apply { kept.forEach { put(toJson(it)) } }.toString()).apply()
    }

    fun addHistory(context: Context, video: OnlineVideo) {
        val list = (history(context).filter { it.id != video.id } + video).takeLast(60)
        prefs(context).edit().putString(KEY_HISTORY, JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()).apply()
    }

    fun isAlive(context: Context) = prefs(context).getBoolean(KEY_ALIVE, false)

    fun setAlive(context: Context, alive: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALIVE, alive).apply()
    }

    /** The moment the app last saw the player alive; recovery uses this after a process kill. */
    fun beat(context: Context) {
        prefs(context).edit().putLong(KEY_BEAT, System.currentTimeMillis()).apply()
    }

    fun beatAgeMs(context: Context): Long =
        System.currentTimeMillis() - prefs(context).getLong(KEY_BEAT, 0L)

    // ---- Self-healing state (blocked ids, listening taste, dead ids, log) ------------------------------------------

    private const val KEY_BLOCKED = "online_blocked"
    private const val KEY_TASTE = "online_taste"
    private const val KEY_DEAD = "online_dead"
    private const val KEY_LOG = "online_log"
    private const val KEY_SEEN = "online_seen_v3"

    /**
     * Runs once per install: drops the SoundCloud-era saved state. SoundCloud track ids are pure
     * numbers and can never play in the YouTube player, so they are removed from history, taste
     * and the "continue playing" pointer (a blank slate, not a crash on the first tap).
     */
    fun onboard(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SEEN, false)) return
        val numeric = Regex("\\d{6,20}")
        val edit = p.edit().putBoolean(KEY_SEEN, true)
        val oldHistory = history(context)
        if (oldHistory.isNotEmpty() && oldHistory.any { numeric.matches(it.id) }) {
            val kept = oldHistory.filter { !numeric.matches(it.id) }
            val array = JSONArray()
            kept.forEach { array.put(toJson(it)) }
            edit.putString(KEY_HISTORY, array.toString())
        }
        val last = loadLast(context)
        if (last != null && numeric.matches(last.first.id)) {
            edit.remove(KEY_LAST).remove(KEY_POSITION).putBoolean(KEY_ALIVE, false)
        }
        if (p.getStringSet(KEY_DEAD, emptySet())?.orEmpty()?.any { numeric.matches(it) } == true) {
            edit.remove(KEY_DEAD)
        }
        if (p.getStringSet(KEY_BLOCKED, emptySet())?.orEmpty()?.any { numeric.matches(it) } == true) {
            edit.remove(KEY_BLOCKED)
        }
        edit.apply()
    }

    // ---- What the person listens to ------------------------------------------------------------------------------

    fun taste(context: Context): Map<String, Int> {
        val raw = prefs(context).getString(KEY_TASTE, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optInt(it) }
        }.getOrDefault(emptyMap())
    }

    private fun bumpTaste(context: Context, channel: String, delta: Int) {
        if (channel.isBlank()) return
        val map = taste(context).toMutableMap()
        map[channel] = (map[channel] ?: 0) + delta
        prefs(context).edit().putString(KEY_TASTE, JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun recordPlay(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, 1)
    fun recordFinished(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, 2)
    fun recordSkip(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, -1)

    fun favoriteChannels(context: Context, limit: Int): List<String> =
        taste(context).entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key }.take(limit)

    fun dead(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DEAD, emptySet())?.toSet() ?: emptySet()

    fun addDead(context: Context, id: String) {
        prefs(context).edit().putStringSet(KEY_DEAD, dead(context) + id).apply()
    }

    /** Small ring buffer of what the watchdog saw and fixed. Never shown; kept for diagnosis. */
    fun log(context: Context, line: String) {
        val lines = (prefs(context).getString(KEY_LOG, "").orEmpty().split("\n").filter { it.isNotBlank() } +
            "${System.currentTimeMillis()} $line").takeLast(40)
        prefs(context).edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }
}

// ---- The embedded YouTube player page -----------------------------------------------------------------------------

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
      onReady: function () { forceSound(); try { Android.kind(0); } catch (e) {} Android.ready(); },
      onStateChange: function (e) {
        if (e.data === 1) { forceSound(); }
        var vid = '';
        try { vid = (player.getVideoData && player.getVideoData().video_id) || ''; } catch (x) {}
        Android.stateOf(e.data, vid);
      },
      onError: function (e) { Android.failed(e.data); }
    }
  });
}
function forceSound() { try { if (player && player.isMuted && player.isMuted()) { player.unMute(); } if (player && player.setVolume) { player.setVolume(100); } } catch (e) {} }
function load(id, start) {
  loadId = id;
  pending = null;
  if (player && player.loadVideoById) {
    // Silence the previous song first so two videos can never overlap.
    try { if (player.stopVideo) { player.stopVideo(); } } catch (e) {}
    player.loadVideoById(id, start || 0);
    forceSound();
  }
  else { pending = [id, start]; }
}
function playVideo2() { if (player) { player.playVideo(); forceSound(); } }
function pauseVideo2() { if (player) player.pauseVideo(); }
function seek(s) { if (player) { player.seekTo(s, true); } }
function frameDoc() { try { var f = document.querySelector('iframe'); return f ? (f.contentDocument || null) : null; } catch (e) { return null; } }
var adWasShowing = false;
// The embed iframe has the same origin as this page, so its ad state can be read and skipped directly:
// click Skip when offered, otherwise fast-forward the ad to its end while muted. No tap is needed.
// The app is told about every ad so its "Ads — please wait" chip appears only while one actually runs.
function killAds() {
  var d = frameDoc();
  if (!d) { return; }
  var showing = !!d.querySelector('.ad-showing, .ad-interrupting');
  if (showing !== adWasShowing) {
    adWasShowing = showing;
    try { Android.adShowing(showing ? 1 : 0); } catch (e) {}
  }
  if (showing) {
    try {
      var v = d.querySelector('video');
      if (v) { v.muted = true; v.playbackRate = 16; if (isFinite(v.duration) && v.duration > 0) { v.currentTime = v.duration; } }
      var b = d.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button');
      if (b) { b.click(); }
      var c = d.querySelector('.ytp-ad-overlay-close-button');
      if (c) { c.click(); }
    } catch (e) {}
  } else if (!adWasShowing) {
    try { var v2 = d.querySelector('video'); if (v2) { v2.playbackRate = 1; v2.muted = false; } } catch (e) {}
    forceSound();
  }
}
setInterval(killAds, 250);
setInterval(function () {
  if (player && player.getCurrentTime && player.getDuration) {
    // Tell the app which page type this is and which video is REALLY loaded, so it can notice (and repair)
    // a screen that shows one song while another is still playing.
    try { Android.kind(0); Android.nowId((player.getVideoData && player.getVideoData().video_id) || ''); } catch (e) {}
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
 * music videos). It shows only the video and reports state back through the same bridge, with the same ad skipping.
 */
private const val WATCH_JS = """
(function () {
  // Only a real watch page runs this (a stale injection into the embed page must do nothing).
  if (location.pathname.indexOf('/watch') !== 0) { return; }
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
    // Fast-forward the ad to its end (muted) and click "Skip" whenever one is offered. No tap is needed.
    try {
      window.__adWas = true;
      var av = document.querySelector('video');
      if (av) { av.muted = true; av.playbackRate = 16; if (isFinite(av.duration) && av.duration > 0) { av.currentTime = av.duration; } }
      var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
      if (b) { b.click(); }
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
    try { Android.kind(1); } catch (e) {}
    hook();
    forceSound();
    var showing = adShowing();
    try { Android.adShowing(showing ? 1 : 0); } catch (e) {}
    if (showing) { skipAd(); }
    else if (window.__adWas) {
      window.__adWas = false;
      var rv = document.querySelector('video');
      if (rv) { rv.playbackRate = 1; rv.muted = false; }
    }
    var v = document.querySelector('video');
    if (v && v.duration > 0 && isFinite(v.duration) && !adShowing()) {
      var id = pageVideoId();
      // The watch page auto-advances to a related video when one ends, and ads can force their own
      // navigation. Tell the app which video the page is REALLY on so the card and notification
      // can follow it instead of showing a stale title.
      Android.pageVideo(id || '');
      Android.time(v.currentTime, v.duration);
    }
  }, 400);
})();
"""

private class Bridge {
    @JavascriptInterface
    fun ready() = OnlinePlayer.handleReady()

    @JavascriptInterface
    fun stateChanged(code: Int) = OnlinePlayer.handleState(code, "")

    @JavascriptInterface
    fun stateOf(code: Int, id: String) = OnlinePlayer.handleState(code, id)

    @JavascriptInterface
    fun kind(kind: Int) = OnlinePlayer.handleKind(kind)

    @JavascriptInterface
    fun nowId(id: String) = OnlinePlayer.handleNowId(id)

    @JavascriptInterface
    fun time(position: Double, duration: Double) = OnlinePlayer.handleTime(position, duration)

    @JavascriptInterface
    fun failed(code: Int) = OnlinePlayer.handleError(code)

    @JavascriptInterface
    fun pageVideo(id: String) = OnlinePlayer.handlePageVideo(id)

    @JavascriptInterface
    fun adShowing(showing: Int) = OnlinePlayer.handleAdShowing(showing == 1)
}

object OnlinePlayer {
    private const val PARK_W = 320
    private const val PARK_H = 180
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var web: BackgroundWebView? = null
    private var pageReady = false
    /** True while the WebView shows the real watch page (the fallback for videos the embed refuses). */
    private var watchMode = false
    private var pendingLoad: Pair<String, Double>? = null
    private var queue: List<OnlineVideo> = emptyList()
    private var index = -1
    private var lastSave = 0L
    private var lastPlaced: IntArray? = null
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
        if (appContext == null) {
            appContext = context.applicationContext
            OnlineStore.onboard(context.applicationContext)
        }
        startWatchdog()
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
        // Sandbox: the page can only talk to YouTube (player, thumbnail and API delivery). Everything
        // else — ads, trackers, pop-ups — is refused before its scripts even run.
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                return !isAllowedHost(host)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return BLOCKED_RESPONSE
                return if (isAllowedHost(host)) null else BLOCKED_RESPONSE
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            // A sandboxed player never opens new windows: every pop-up dies here.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, result: android.os.Message): Boolean {
                // Returning false refuses the window; nothing is sent through the result message.
                return false
            }
        }
        view.addJavascriptInterface(Bridge(), "Android")
        // YouTube asks for consent in some regions; accept it quietly so the player never stalls on it.
        runCatching {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            CookieManager.getInstance().setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/")
            CookieManager.getInstance().setCookie("https://www.youtube.com", "CONSENT=YES+1; Domain=.youtube.com; Path=/")
        }
        loadEmbedPage(view)
        web = view
        if (overlayAllowed()) hostInBackground(warn = false)
        return view
    }

    /** YouTube hosts plus the few CDN hosts the player and thumbnails genuinely need. */
    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.endsWith("youtube.com") || h.endsWith("youtube-nocookie.com") || h.endsWith("ytimg.com") ||
            h.endsWith("googlevideo.com") || h.endsWith("ggpht.com") || h.endsWith("googleapis.com") ||
            h.endsWith("gstatic.com") || h.endsWith("google.ca") || h.endsWith("google.co.uk") || h.endsWith("google.com")
        ) {
            // Refuse the known ad-serving hosts even inside otherwise-allowed domains.
            val adMarkers = listOf("ads", "adserver", "admob", "doubleclick", "googlesyndication", "adservice", "partnerads", "promote", "impression")
            return adMarkers.none { marker -> h == marker || h.startsWith("$marker.") || h.contains(".$marker.") }
        }
        return false
    }

    /** Empty 200 response served in place of every blocked request. */
    private val BLOCKED_RESPONSE: WebResourceResponse by lazy {
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun loadEmbedPage(view: WebView) {
        pageReady = false
        watchMode = false
        OnlineAdState.showing = false
        view.loadDataWithBaseURL("https://www.youtube.com", PLAYER_HTML, "text/html", "UTF-8", null)
    }

    /**
     * Loads the real YouTube watch page and injects [WATCH_JS]: the fallback for videos the embedded
     * player refuses to play (most official music videos).
     */
    private fun loadWatchPage(view: WebView, id: String, start: Double) {
        pageReady = true
        watchMode = true
        view.loadUrl("https://m.youtube.com/watch?v=$id" + if (start > 0) "&t=${start.toInt()}s" else "")
        runJs("javascript:Android.ready()")
        val token = watchToken
        main.postDelayed({ if (token == watchToken) injectWatchJs() }, 1_200L)
        main.postDelayed({ if (token == watchToken) injectWatchJs() }, 3_000L)
    }

    private fun injectWatchJs() {
        if (!watchMode) return
        runJs(WATCH_JS)
    }

    // ---- Window placement ---------------------------------------------------------------------------------------

    /** Adds the player view on top of the Activity content. It is parked as an off-screen invisible view when idle. */
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
        // Fully invisible: alpha 0 and parked off-screen (negative margins). The player keeps
        // playing from an off-screen window; any visible pixel would draw a black box over the app.
        view.alpha = 0f
        view.passThrough = true
        activity.addContentView(view, FrameLayout.LayoutParams(PARK_W, PARK_H).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = -PARK_W * 2
            topMargin = -PARK_H * 2
        })
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
            params.x = -PARK_W * 2
            params.y = -PARK_H * 2
            view.passThrough = true
            view.alpha = 0f
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
        params.leftMargin = -PARK_W * 2
        params.topMargin = -PARK_H * 2
        view.passThrough = true
        view.alpha = 0f
        view.layoutParams = params
        lastPlaced = wanted
    }

    // ---- Playback -----------------------------------------------------------------------------------------------

    private fun runJs(script: String) {
        main.post { web?.evaluateJavascript(script, null) }
    }

    private var userInitiated = true
    private var healStage = 0
    private var autoplayBusy = false
    private var endedGeneration = -1L
    private var frozenHits = 0
    private var idMismatch = 0
    private var startedAtMs = 0L

    fun play(video: OnlineVideo, list: List<OnlineVideo>) {
        // Only the chosen song is queued. What plays after it is picked from similar music, never from the
        // list it was tapped in (Continue playing, results, ...), which used to jump to unrelated songs.
        queue = listOf(video)
        index = 0
        userInitiated = true
        swapCount = 0
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

    private fun startVideo(video: OnlineVideo, startSec: Double, startService: Boolean, heal: Boolean = false) {
        if (!Regex("[A-Za-z0-9_-]{6,20}").matches(video.id)) return
        val context = appContext ?: return
        // Any event still on its way from the previous video is now obsolete.
        if (!heal && state.video != null && state.video?.id != video.id) jsPause()
        generation += 1
        val gen = generation
        frozenHits = 0
        idMismatch = 0
        startedAtMs = android.os.SystemClock.uptimeMillis()
        if (!heal) {
            failStreak = 0
            healStage = 0
            OnlineStore.recordPlay(context, video)
            OnlineStore.addHistory(context, video)
            OnlineStore.log(context, "play ${video.id}")
        }
        // Claim the single media notification before anything starts.
        com.musicrage.app.playback.PlaybackSupervisor.claim(context, com.musicrage.app.playback.PlaybackSupervisor.Owner.ONLINE)
        state = OnlineState(video, isPlaying = true, hasPrev = index > 0, hasNext = true)
        positionSec = startSec
        durationSec = 0.0
        OnlineLoading.active = true
        OnlineAdState.showing = false
        OnlineStore.saveLast(context, video, startSec)
        OnlineStore.setAlive(context, true)
        if (startService) {
            runCatching { context.startForegroundService(Intent(context, OnlineService::class.java)) }
        }
        val view = webView()
        watchToken += 1
        pendingLoad = null
        if (pageReady) {
            // A video blocked in the embed (most official music videos) goes straight to the watch page.
            if (video.id in blockedIds(context)) {
                loadWatchPage(view, video.id, startSec)
            } else if (watchMode) {
                // Leaving the watch page for a NEW song: the watch page has no load() function, so
                // without this the click was silently ignored and the old song kept playing.
                loadEmbedPage(view)
                pendingLoad = video.id to startSec
            } else {
                runJs("load('${video.id}',${startSec.toInt()})")
            }
        } else {
            pendingLoad = video.id to startSec
        }
        armWatchTimeout(gen)
        listener?.invoke()
    }

    /** Ids that refuse the embedded player; remembered so a restart of one goes straight to the watch page. */
    private fun blockedIds(context: Context): Set<String> =
        appContext?.getSharedPreferences(MusicPreferences.FILE, Context.MODE_PRIVATE)
            ?.getStringSet("online_blocked", emptySet())?.toSet() ?: emptySet()

    private fun addBlockedId(id: String) {
        val context = appContext ?: return
        context.getSharedPreferences(MusicPreferences.FILE, Context.MODE_PRIVATE)
            .edit().putStringSet("online_blocked", blockedIds(context) + id).apply()
    }

    /** If the song still has not started after a while, repair it quietly. */
    private fun armWatchTimeout(gen: Long) {
        val token = watchToken
        // The first wait is short: a healthy embed answers within a few seconds, and a reload
        // that is going to fail anyway should not make the person stare at a spinner for 12s
        // (three of those in a row was most of a minute of nothing happening).
        val waitMs = when {
            healStage == 0 && !watchMode -> 6_000L
            watchMode -> 15_000L
            else -> 10_000L
        }
        main.postDelayed({
            if (token == watchToken && gen == generation && OnlineLoading.active) healFailure(gen)
        }, waitMs)
    }

    /**
     * Silent repair ladder, nothing is shown to the person: 1) reload the song in the same player,
     * 2) move to the real watch page, 3) reload the whole player page, 4) give up on that video
     * and move on.
     */
    private fun healFailure(gen: Long) {
        if (gen != generation) return
        val video = state.video ?: return
        val context = appContext ?: return
        healStage += 1
        OnlineStore.log(context, "heal $healStage ${video.id}")
        when (healStage) {
            1 -> {
                // One clean retry: reload the whole player page, then the song.
                pageReady = false
                web?.let { loadEmbedPage(it) }
                startVideo(video, positionSec, false, heal = true)
            }
            2 -> {
                // The embedded player refuses this video (most official music videos): play the real
                // watch page with the video-only styling instead.
                web?.let { loadWatchPage(it, video.id, positionSec) }
                armWatchTimeout(gen)
            }
            else -> giveUp(video)
        }
    }

    private fun giveUp(video: OnlineVideo) {
        val context = appContext ?: return
        OnlineStore.addDead(context, video.id)
        OnlineStore.log(context, "dead ${video.id}")
        OnlineLoading.active = false
        healStage = 0
        if (!userInitiated || index + 1 < queue.size) {
            // The song came from autoplay (or has a successor): quietly pick another one.
            if (index + 1 < queue.size) {
                index += 1
                startVideo(queue[index], 0.0, false)
            } else {
                autoplaySimilar(context)
            }
        } else {
            // The song the person chose cannot play here. Instead of leaving a dead play button, quietly play
            // another upload of the same song.
            OnlineStore.removeHistory(context, video.id)
            swapForAlternative(video)
        }
    }

    private var swapCount = 0

    private fun swapForAlternative(video: OnlineVideo) {
        val context = appContext ?: return
        if (autoplayBusy || swapCount >= 3) {
            OnlineLoading.active = false
            state = state.copy(isPlaying = false)
            listener?.invoke()
            return
        }
        autoplayBusy = true
        swapCount += 1
        val gen = generation
        val avoid = OnlineStore.dead(context)
        OnlineLoading.active = true
        Thread {
            val alternative = runCatching { com.musicrage.app.data.OnlineApi.alternatives(video, avoid) }
                .getOrDefault(emptyList()).firstOrNull()
            main.post {
                autoplayBusy = false
                if (gen != generation) return@post
                if (alternative == null) {
                    OnlineLoading.active = false
                    state = state.copy(isPlaying = false)
                    listener?.invoke()
                } else {
                    queue = listOf(alternative)
                    index = 0
                    userInitiated = true
                    startVideo(alternative, 0.0, false)
                }
            }
        }.start()
    }

    // The embed page and the watch page expose different control functions; watchMode says which
    // one is live. (The old URL sniffing also matched the embed page's own iframe URL.)
    private fun jsPlay() = runJs(
        "try{ if (typeof mrPlay === 'function') { mrPlay(); } if (typeof playVideo2 === 'function') { playVideo2(); } }catch(e){}"
    )
    private fun jsPause() = runJs(
        "try{ if (typeof mrPause === 'function') { mrPause(); } if (typeof pauseVideo2 === 'function') { pauseVideo2(); } }catch(e){}"
    )

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
        val context = appContext
        val video = state.video
        if (context != null && video != null && positionSec < 20.0) OnlineStore.recordSkip(context, video)
        if (index + 1 < queue.size) {
            index += 1
            startVideo(queue[index], 0.0, false)
        } else {
            autoplaySimilar(context)
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
        runJs("try{ if (typeof mrSeek === 'function') { mrSeek(${seconds.toInt()}); } if (typeof seek === 'function') { seek(${seconds.toInt()}); } }catch(e){}")
    }

    // ---- Callbacks from the page (called on a WebView thread) ------------------------------------------------------

    internal fun handleReady() {
        main.post {
            pageReady = true
            // The video asked for while the page was still loading is sent to the player now.
            // (The embed page is fresh here, so watchMode from the previous watch-page song is off.)
            pendingLoad?.let {
                watchMode = false
                runJs("load('${it.first}',${it.second.toInt()})")
            }
            pendingLoad = null
        }
    }

    internal fun handleState(code: Int, videoId: String = "") {
        // Capture the generation on the WebView thread, before the hop to the main thread.
        val gen = generation
        main.post {
            // The user started another video or stopped playback while this event was in flight: drop it.
            if (gen != generation) return@post
            // An event about a different video than the one on screen (the old song still winding down, or a
            // player that never switched) must not make the new song look live.
            val wanted = state.video?.id
            if (videoId.isNotEmpty() && wanted != null && videoId != wanted) return@post
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
                    // A pause does NOT clear the alive marker: the player fires stray pauses right after
                    // starting a video, and clearing here would disable crash recovery while the music
                    // is still going. The heartbeat stops marking the session only on a real stop().
                    state = state.copy(isPlaying = false)
                }
                0 -> onTrackEnded(context)
            }
            listener?.invoke()
        }
    }

    private fun onTrackEnded(context: Context?) {
        if (endedGeneration == generation) return
        endedGeneration = generation
        state = state.copy(isPlaying = false)
        val video = state.video
        if (context != null && video != null) {
            OnlineStore.setAlive(context, false)
            OnlineStore.recordFinished(context, video)
        }
        if (index + 1 < queue.size) {
            index += 1
            startVideo(queue[index], 0.0, false)
        } else {
            autoplaySimilar(context)
        }
    }

    /**
     * After a song ends (or Next is pressed with nothing queued) a similar song is chosen, so the
     * Online tab NEVER hands playback back to the local library. Songs similar to the one that just
     * finished (the artist's other songs, covers of the same song) come first; only when that comes
     * up empty does the old ladder run — the artists the person plays most, then popular songs.
     * Songs already played, blocked or dead are skipped.
     */
    /** True for things that look like a single song (not mixes, streams, playlists or podcasts). */
    private fun looksLikeSong(video: OnlineVideo): Boolean {
        val title = video.title.lowercase()
        val noise = listOf("hours", "hour mix", "top 100", "top 50", "top 20", "top 10", "playlist", " mix", "compilation",
            "nonstop", "non-stop", "non stop", "full album", "best of", "jukebox", "24/7", "live stream", "hits 20", "trending",
            "podcast", "episode", "interview", "set #", "dj set", "recording", "snippet", "preview")
        return title.length < 140 && noise.none { title.contains(it) } && video.playable
    }

    private fun autoplaySimilar(context: Context?) {
        val finished = state.video ?: return
        if (context == null || autoplayBusy) return
        autoplayBusy = true
        val gen = generation
        val seen = OnlineStore.history(context).map { it.id }.toSet() + queue.map { it.id }
        val avoid = OnlineStore.dead(context)
        val artist = finished.channel.trim()
        val seed = finished.title.substringBefore(" (").substringBefore(" [").trim()
        OnlineLoading.active = true
        Thread {
            // 1) Songs similar to the one that just ended.
            val related = runCatching { com.musicrage.app.data.OnlineApi.related(finished) }.getOrDefault(emptyList())
            var pick = related.filter { it.id !in seen && it.id !in avoid && looksLikeSong(it) }.randomOrNull()
            // 2) The old ladder, only when similar came up empty: this song, favourites, popular.
            if (pick == null) {
                val queries = buildList<String> {
                    if (seed.isNotBlank()) add(seed)
                    if (artist.isNotBlank()) add("$artist songs")
                    OnlineStore.favoriteChannels(context, 3).filter { !it.equals(artist, ignoreCase = true) }.forEach { add("$it songs") }
                    add("popular songs")
                }.distinct()
                for (query in queries) {
                    val found = runCatching { com.musicrage.app.data.OnlineApi.search(query) }.getOrDefault(emptyList())
                    val candidates = found.filter { it.id !in seen && it.id !in avoid && looksLikeSong(it) }
                    if (candidates.isNotEmpty()) {
                        pick = candidates.take(5).random()
                        break
                    }
                }
            }
            val chosen = pick
            main.post {
                autoplayBusy = false
                if (gen != generation) return@post
                if (chosen == null) {
                    OnlineLoading.active = false
                    state = state.copy(isPlaying = false)
                    listener?.invoke()
                } else {
                    userInitiated = false
                    queue = queue + chosen
                    index = queue.size - 1
                    startVideo(chosen, 0.0, false)
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
     * The watch page reports which video it is really on. If it moved to a video the app did not ask for
     * (the page auto-advances to a related one, or an ad forced its own navigation), the card, the
     * notification and the saved position follow it — they never show a stale title.
     */
    internal fun handlePageVideo(id: String) {
        if (id.length != 11) return
        val gen = generation
        main.post {
            if (gen != generation) return@post
            if (!watchMode) return@post
            val current = state.video ?: return@post
            if (id == current.id) return@post
            // The page moved somewhere the app did not send it: adopt that video.
            val context = appContext ?: return@post
            val adopted = com.musicrage.app.data.OnlineApi.track(id)
            if (adopted != null) {
                OnlineStore.addHistory(context, adopted)
                OnlineStore.recordPlay(context, adopted)
                OnlineStore.saveLast(context, adopted, 0.0)
                state = state.copy(video = adopted)
                listener?.invoke()
            }
        }
    }

    /** The page itself says whether it is the embed player (0) or the watch page (1), so the flag cannot drift. */
    internal fun handleKind(kind: Int) {
        main.post {
            val watch = kind == 1
            if (watchMode != watch) {
                watchMode = watch
                if (watch) pageReady = true
            }
        }
    }

    /**
     * Once a second the embed page reports which video is really loaded. If it stays different from the song on
     * screen after the switch should long be done, the screen and the sound disagree: repair it quietly.
     */
    internal fun handleNowId(id: String) {
        val gen = generation
        main.post {
            if (gen != generation || id.isBlank()) return@post
            val wanted = state.video?.id ?: return@post
            if (id == wanted) {
                idMismatch = 0
                return@post
            }
            if (android.os.SystemClock.uptimeMillis() - startedAtMs < 4_000L) return@post
            idMismatch += 1
            if (idMismatch >= 3) {
                idMismatch = 0
                appContext?.let { OnlineStore.log(it, "out of sync: page $id, screen $wanted") }
                jsPause()
                healFailure(generation)
            }
        }
    }

    internal fun handleAdShowing(showing: Boolean) {
        val gen = generation
        main.post {
            if (gen != generation && !showing) return@post
            OnlineAdState.showing = showing
        }
    }

    internal fun handleError(code: Int) {
        val gen = generation
        main.post {
            if (gen != generation) return@post
            val video = state.video ?: return@post
            val context = appContext ?: return@post
            // The song is already audible; a stray error from an old page must not interrupt it.
            if (!OnlineLoading.active && state.isPlaying && positionSec > 1.0) return@post
            OnlineStore.log(context, "error $code ${video.id}")
            // 101/150 = the embed refuses this video (most official music videos): go to the watch page.
            if (code == 101 || code == 150 || code == 100) {
                if (healStage == 0) {
                    healStage = 1
                    OnlineStore.log(context, "watch-mode ${video.id}")
                    web?.let { loadWatchPage(it, video.id, positionSec) }
                    armWatchTimeout(gen)
                } else {
                    healFailure(gen)
                }
            } else {
                healFailure(gen)
            }
        }
    }

    // ---- Watchdog -------------------------------------------------------------------------------------------------

    private var watchdogStarted = false
    private var loadingSince = 0L
    private var lastPos = 0.0
    private var lastPosAt = 0L

    /**
     * Watches the online player on its own and repairs problems without telling anyone: a page that never loads,
     * a song stuck on "loading", or a song that is "playing" but whose clock stopped.
     */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        main.postDelayed(object : Runnable {
            override fun run() {
                runCatching { watchdogTick() }
                main.postDelayed(this, 2_000L)
            }
        }, 2_000L)
    }

    private fun watchdogTick() {
        val now = android.os.SystemClock.uptimeMillis()
        val video = state.video
        if (video == null || autoplayBusy) {
            loadingSince = 0L
            lastPosAt = now
            return
        }
        if (OnlineLoading.active) {
            if (loadingSince == 0L) loadingSince = now
            if (now - loadingSince > 9_000L) {
                loadingSince = now
                healFailure(generation)
            }
            lastPos = positionSec
            lastPosAt = now
            return
        }
        loadingSince = 0L
        if (!state.isPlaying || (durationSec > 0.0 && positionSec >= durationSec - 1.5)) {
            lastPos = positionSec
            lastPosAt = now
            frozenHits = 0
            return
        }
        if (kotlin.math.abs(positionSec - lastPos) > 0.2) {
            lastPos = positionSec
            lastPosAt = now
            frozenHits = 0
            return
        }
        if (now - lastPosAt > 9_000L) {
            lastPosAt = now
            frozenHits += 1
            appContext?.let { OnlineStore.log(it, "frozen ${video.id} hit $frozenHits") }
            if (frozenHits == 1) {
                jsPlay()
            } else {
                frozenHits = 0
                healFailure(generation)
            }
        }
    }

    /** Full stop used when the LOCAL player takes over: clears state so the app shows the local player again. */
    fun stop(clearView: Boolean = true) {
        generation += 1
        watchToken += 1
        jsPause()
        OnlineLoading.active = false
        OnlineAdState.showing = false
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
            if (clearView) {
                // Put the WebView back to a clean player page so a later start never resumes the old track.
                pendingLoad = null
                pageReady = false
                web?.let { loadEmbedPage(it) }
            }
        }
        listener?.invoke()
    }
}
