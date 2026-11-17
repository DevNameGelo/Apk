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

    private fun toJson(video: OnlineVideo) = JSONObject()
        .put("id", video.id).put("title", video.title).put("channel", video.channel).put("thumb", video.thumb)
        .put("permalink", video.permalink)
        // A NaN here throws JSONException ("Forbidden numeric value") on save and crashed the app on
        // the Online tab when an entry from an older build was rewritten, so it is never stored.
        .put("duration", if (video.duration.isNaN() || video.duration < 0) 0.0 else video.duration)
        .put("playable", video.playable)

    private fun fromJson(obj: JSONObject) = OnlineVideo(
        obj.optString("id"),
        obj.optString("title"),
        obj.optString("channel"),
        obj.optString("thumb"),
        obj.optString("permalink"),
        // Entries saved by 1.2.17 have no "duration"; the no-fallback optDouble would hand back NaN
        // and blow up the very next save. Anything unreadable counts as "unknown" (0), not NaN.
        obj.optDouble("duration", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        obj.optBoolean("playable", true)
    )

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
            // Placeholder entries from older builds ("Playing next") are dropped; one entry per video.
            .filter { it.title != "Playing next" && it.id.isNotEmpty() }
            .distinctBy { it.id }
    }

    /** Removes a song from "Continue playing". Playing it again adds it back. */
    fun removeHistory(context: Context, id: String) {
        val array = JSONArray()
        history(context).filter { it.id != id }.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
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
    private const val KEY_TASTE = "online_taste"
    private const val KEY_DEAD = "online_dead"
    private const val KEY_LOG = "online_log"
    private const val KEY_SEEN = "online_seen"

    /**
     * First run on this install: start from a clean slate so no stale marker from an earlier build can
     * misfire (a phantom "alive" flag, a half-saved last video). Existing users are left untouched.
     */
    fun onboard(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SEEN, false)) return
        val existing = history(context).isNotEmpty()
        val edit = p.edit().putBoolean(KEY_SEEN, true)
        if (!existing) edit.remove(KEY_BLOCKED).remove(KEY_LAST).remove(KEY_POSITION).putBoolean(KEY_ALIVE, false)
        edit.apply()
        migrateToSoundcloud(context)
    }

    /**
     * The switch to SoundCloud: ids that are not SoundCloud track numbers (old YouTube ids, placeholders)
     * can never play again, so they are dropped from history, taste and the "continue playing" pointer.
     */
    private fun migrateToSoundcloud(context: Context) {
        val p = prefs(context)
        val numeric = Regex("\\d{6,20}")
        val oldHistory = history(context)
        if (oldHistory.isNotEmpty() && oldHistory.any { !numeric.matches(it.id) }) {
            val kept = oldHistory.filter { numeric.matches(it.id) }
            val array = JSONArray()
            kept.forEach { array.put(toJson(it)) }
            p.edit().putString(KEY_HISTORY, array.toString()).apply()
        }
        val last = loadLast(context)
        if (last != null && !numeric.matches(last.first.id)) {
            p.edit().remove(KEY_LAST).remove(KEY_POSITION).putBoolean(KEY_ALIVE, false).apply()
        }
        if (p.getStringSet(KEY_DEAD, emptySet())?.orEmpty()?.any { !numeric.matches(it) } == true) {
            p.edit().remove(KEY_DEAD).apply()
        }
        if (p.getStringSet(KEY_BLOCKED, emptySet())?.orEmpty()?.any { !numeric.matches(it) } == true) {
            p.edit().remove(KEY_BLOCKED).apply()
        }
    }

    // ---- What the person listens to ------------------------------------------------------------------------------

    fun taste(context: Context): Map<String, Int> {
        val raw = prefs(context).getString(KEY_TASTE, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optInt(key)
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun bumpTaste(context: Context, channel: String, delta: Int) {
        if (channel.isBlank()) return
        val map = taste(context).toMutableMap()
        map[channel] = ((map[channel] ?: 0) + delta).coerceAtLeast(0)
        val obj = JSONObject()
        map.entries.sortedByDescending { it.value }.take(40).forEach { obj.put(it.key, it.value) }
        prefs(context).edit().putString(KEY_TASTE, obj.toString()).apply()
    }

    fun recordPlay(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, 1)
    fun recordFinished(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, 2)
    fun recordSkip(context: Context, video: OnlineVideo) = bumpTaste(context, video.channel, -1)

    fun favoriteChannels(context: Context, limit: Int): List<String> =
        taste(context).entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(limit).map { it.key }

    /** Videos that could not be played even after every repair step; autoplay never picks them again. */
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
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
iframe{width:100%;height:100%;border:0}</style></head>
<body><iframe id="w" allow="autoplay"
src="https://w.soundcloud.com/player/?url=https%3A%2F%2Fapi.soundcloud.com%2Fplatforms%2Fweb&auto_play=false&buying=false&sharing=false&download=false&show_artwork=false&visual=false&single_active=true"></iframe>
<script src="https://w.soundcloud.com/player/api.js"></script>
<script>
var widget = null;
var pendingLoad = null;
var wantedStart = 0;
var seenPlaying = false;

function ensureWidget() {
  if (widget) { return widget; }
  if (typeof SC === 'undefined' || !SC.Widget) { return null; }
  var frame = document.getElementById('w');
  if (!frame) { return null; }
  try {
    widget = SC.Widget(frame);
    widget.bind(SC.Widget.Events.READY, function () { Android.ready(); });
    widget.bind(SC.Widget.Events.PLAY, function () {
      seenPlaying = true;
      window.__playSeen = true;
      // Resume exactly where the song was cut off: the first PLAY after a load with a wanted start.
      if (wantedStart > 0) {
        try { widget.seekTo(wantedStart * 1000); } catch (e) {}
        wantedStart = 0;
      }
      Android.stateChanged(1);
    });
    widget.bind(SC.Widget.Events.PAUSE, function () { Android.stateChanged(2); });
    widget.bind(SC.Widget.Events.FINISH, function () { Android.stateChanged(0); });
    try {
      widget.bind(SC.Widget.Events.ERROR, function (e) { Android.failed(-1); });
    } catch (e2) { /* older api.js without the ERROR event */ }
    return widget;
  } catch (e) { return null; }
}

// The API script loads after this inline script; retry until the widget can be bound.
var bindTimer = setInterval(function () {
  var w = ensureWidget();
  if (w) {
    clearInterval(bindTimer);
    if (pendingLoad) { var p = pendingLoad; pendingLoad = null; load(p[0], p[1]); }
  }
}, 300);

function load(id, start) {
  wantedStart = start || 0;
  window.__wantPlay = true;
  window.__playSeen = false;
  var w = ensureWidget();
  if (!w) { pendingLoad = [id, start]; return; }
  try {
    w.load('https://api.soundcloud.com/tracks/' + id, {
      auto_play: true,
      buying: false, sharing: false, downloading: false,
      show_artwork: false, visual: false, single_active: true,
      callback: function () {
        seenPlaying = false;
        try { w.play(); } catch (e) {}
        Android.ready();
      }
    });
  } catch (e) { Android.failed(-1); }
}

function playVideo2() { window.__wantPlay = true; if (widget) { try { widget.play(); } catch (e) {} } }
function pauseVideo2() { window.__wantPlay = false; if (widget) { try { widget.pause(); } catch (e) {} } }
function seek(s) { if (widget) { try { widget.seekTo(s * 1000); } catch (e) {} } }

setInterval(function () {
  if (widget) {
    try {
      widget.getPosition(function (pos) {
        widget.getDuration(function (dur) {
          Android.time(pos / 1000, dur / 1000);
        });
      });
    } catch (e) {}
  }
}, 1000);
// The widget occasionally fires a stray PAUSE right after starting (its own audio focus dance).
// While the app believes the song should be playing, keep nudging it back to play; harmless otherwise.
setInterval(function () {
  try {
    if (widget && window.__wantPlay && !window.__playSeen) {
      widget.isPaused(function (paused) {
        if (paused) { try { widget.play(); } catch (e) {} }
      });
    }
  } catch (e) {}
}, 2500);
</script></body></html>
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
        // Sandbox: the page can only talk to SoundCloud (widget, artwork and stream delivery). Everything
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
        loadEmbedPage(view)
        web = view
        if (overlayAllowed()) hostInBackground(warn = false)
        return view
    }

    /** SoundCloud hosts plus the few CDN/API hosts the widget and artworks genuinely need. */
    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        // The audio itself is delivered from SoundCloud's media CDN (playback.media-streaming.soundcloud.cloud)
        // and, for some tracks, its CDN partners — all of these must pass or the widget plays nothing.
        if (h.endsWith("soundcloud.com") || h.endsWith("soundcloud.cloud") || h.endsWith("sndcdn.com") ||
            h.endsWith("sc-cdn.net") || h.endsWith("soundusercontent.com") || h.endsWith("akamaized.net") ||
            h.endsWith("cloudfront.net") || h.endsWith("media-streaming.soundcloud.cloud")
        ) {
            // Refuse the known ad-serving subdomains even inside otherwise-allowed domains.
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
        view.loadDataWithBaseURL("https://w.soundcloud.com", PLAYER_HTML, "text/html", "UTF-8", null)
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
        // Fully invisible: alpha 0 and parked off-screen (negative margins). The widget keeps
        // playing from an off-screen window; a 0.02 alpha used to leave a faint grey square
        // visible whenever the app drew a black screen behind it.
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

    fun play(video: OnlineVideo, list: List<OnlineVideo>) {
        // Only the chosen song is queued. What plays after it is picked from similar music, never from the
        // list it was tapped in (Continue playing, results, ...), which used to jump to unrelated songs.
        queue = listOf(video)
        index = 0
        userInitiated = true
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
        generation += 1
        val gen = generation
        frozenHits = 0
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
        OnlineStore.saveLast(context, video, startSec)
        OnlineStore.setAlive(context, true)
        if (startService) {
            runCatching { context.startForegroundService(Intent(context, OnlineService::class.java)) }
        }
        val view = webView()
        watchToken += 1
        pendingLoad = null
        if (pageReady) {
            runJs("load('${video.id}',${startSec.toInt()})")
        } else {
            pendingLoad = video.id to startSec
        }
        armWatchTimeout(gen)
        listener?.invoke()
    }

    /** If the song still has not started after a while, repair it quietly. */
    private fun armWatchTimeout(gen: Long) {
        val token = watchToken
        main.postDelayed({
            if (token == watchToken && gen == generation && OnlineLoading.active) healFailure(gen)
        }, 25_000L)
    }

    /**
     * Silent repair ladder, nothing is shown to the person: 1) reload the song in the same player,
     * 2) reload the whole player page, 3) give up on that video and move on.
     */
    private fun healFailure(gen: Long) {
        if (gen != generation) return
        val video = state.video ?: return
        val context = appContext ?: return
        healStage += 1
        OnlineStore.log(context, "heal $healStage ${video.id}")
        when (healStage) {
            1 -> startVideo(video, positionSec, false, heal = true)
            2 -> {
                pageReady = false
                web?.let { loadEmbedPage(it) }
                startVideo(video, positionSec, false, heal = true)
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
            state = state.copy(isPlaying = false)
            listener?.invoke()
        }
    }

    private fun jsPlay() = runJs("playVideo2()")
    private fun jsPause() = runJs("pauseVideo2()")

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
        runJs("seek(${seconds.toInt()})")
    }

    // ---- Callbacks from the page (called on a WebView thread) ------------------------------------------------------

    internal fun handleReady() {
        main.post {
            pageReady = true
            // The track asked for while the page was still loading is sent to the widget now.
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
                    // A pause does NOT clear the alive marker: the widget fires stray pauses right after
                    // starting a song, and clearing here would disable crash recovery while the music
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

    /** Titles that are long mixes, charts or streams rather than a single song. */
    private fun looksLikeSong(video: OnlineVideo): Boolean {
        val title = video.title.lowercase()
        val noise = listOf("hours", "hour mix", "top 100", "top 50", "top 20", "top 10", "playlist", " mix", "compilation",
            "nonstop", "non-stop", "non stop", "full album", "best of", "jukebox", "24/7", "live stream", "hits 20", "trending",
            "podcast", "episode", "interview", "set #", "dj set", "recording", "snippet", "preview")
        return title.length < 140 && noise.none { title.contains(it) } && video.playable
    }

    /**
     * After a song ends (or Next is pressed with nothing queued) a similar song is chosen, so the
     * Online tab NEVER hands playback back to the local library. SoundCloud's own "related tracks"
     * list for the song that just finished comes first (that is what the site's station uses);
     * only when that comes up empty does the old ladder run — the same artist, songs like this one,
     * the artists the person plays most, then popular songs. Songs already played, blocked or dead
     * are skipped.
     */
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
            // 1) SoundCloud's own related tracks for the song that just ended.
            val related = runCatching { com.musicrage.app.data.OnlineApi.related(finished.id) }.getOrDefault(emptyList())
            var pick = related.filter { it.id !in seen && it.id !in avoid && looksLikeSong(it) }.randomOrNull()
            // 2) The old ladder, only when related came up empty: same artist, this song, favourites, popular.
            if (pick == null) {
                val queries = buildList<String> {
                    if (artist.isNotBlank()) add(artist)
                    if (seed.isNotBlank()) add(seed)
                    OnlineStore.favoriteChannels(context, 3).filter { !it.equals(artist, ignoreCase = true) }.forEach { add(it) }
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
     * The widget no longer auto-advances behind the app's back (SoundCloud's single_active widget keeps one
     * track at a time), so this callback is only a no-op kept for older saved pages.
     */
    internal fun handlePageVideo(id: String) {
        // Nothing to follow: the app alone decides what plays next.
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
            healFailure(gen)
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
            if (now - loadingSince > 14_000L) {
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
