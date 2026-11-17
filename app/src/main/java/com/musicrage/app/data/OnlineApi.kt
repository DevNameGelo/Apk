package com.musicrage.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * A song found on SoundCloud. [id] is the numeric track id, [permalink] its soundcloud.com path,
 * [duration] the track length in seconds, [playable] whether the widget can play it. Official
 * label uploads only offer AES-encrypted HLS — the widget plays those fine (it decrypts them
 * in-page), so they count as playable; only login-required Go+ exclusives do not.
 */
data class OnlineVideo(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String,
    val permalink: String = "",
    val duration: Double = 0.0,
    val playable: Boolean = true,
    val likes: Long = 0L
)

object OnlineApi {
    class ApiException(message: String) : Exception(message)

    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---- HTTP ------------------------------------------------------------------------------------------------------------

    private fun get(url: String, browser: Boolean = false, headers: Map<String, String> = emptyMap(), range: String? = null): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        if (browser) {
            connection.setRequestProperty("User-Agent", BROWSER_AGENT)
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        } else {
            connection.setRequestProperty("User-Agent", "MusicRage/1.2")
        }
        if (range != null) connection.setRequestProperty("Range", range)
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299 || code == 206) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && code != 206) throw ApiException(errorMessage(body, code))
            return body
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the bytes of a binary resource. Returns null when the server answered nothing usable. */
    private fun getBytes(url: String, range: String? = null, referer: String? = null): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", BROWSER_AGENT)
        if (range != null) connection.setRequestProperty("Range", range)
        if (referer != null) connection.setRequestProperty("Referer", referer)
        return try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) null else connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun serverError(json: JSONObject): String? {
        for (name in listOf("error", "message", "msg", "detail")) {
            val value = json.opt(name) ?: continue
            val text = when (value) {
                is JSONObject -> value.optString("message")
                is String -> value
                else -> ""
            }.trim()
            if (text.isNotEmpty() && text != "0" && text != "false" && !text.equals("ok", true) && !text.equals("success", true)) return text
        }
        return null
    }

    private fun errorMessage(body: String, code: Int): String {
        val fromJson = runCatching { serverError(JSONObject(body)) }.getOrNull()
        if (fromJson != null) return "$fromJson (HTTP $code)"
        val snippet = body.trim().replace(Regex("\\s+"), " ").take(120)
        return if (snippet.isNotEmpty()) "HTTP $code: $snippet" else "HTTP $code"
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    // ---- SoundCloud client_id ---------------------------------------------------------------------------------------------

    /** Known working client ids; a fresh one is scraped from the widget bundle when these stop working. */
    @Volatile
    private var clientId: String = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"

    @Volatile
    private var clientIdCheckedAt = 0L

    private fun soundcloudGet(pathAndQuery: String): String {
        val fresh = System.currentTimeMillis() - clientIdCheckedAt < 30_000L
        try {
            return get("https://api-v2.soundcloud.com$pathAndQuery" + (if (pathAndQuery.contains("?")) "&" else "?") + "client_id=$clientId", browser = true)
        } catch (e: Exception) {
            if (!fresh) throw e
        }
        refreshClientId()
        return get("https://api-v2.soundcloud.com$pathAndQuery" + (if (pathAndQuery.contains("?")) "&" else "?") + "client_id=$clientId", browser = true)
    }

    /**
     * Pulls a brand-new client_id from the SoundCloud widget's JavaScript bundle (the ids are rotated now and
     * then). Called when the saved one starts failing.
     */
    private fun refreshClientId() {
        synchronized(this) {
            try {
                val html = get("https://w.soundcloud.com/player/?url=https%3A//api.soundcloud.com/platforms/web", browser = true)
                val bundles = Regex("https://widget\\.sndcdn\\.com/widget-[^\"]+\\.js").findAll(html).map { it.value }.toList()
                for (bundle in bundles) {
                    val body = get(bundle, browser = true)
                    val found = Regex("client_id[a-z]?\\s*[:=]\\s*[\"']([A-Za-z0-9]{20,50})[\"']").find(body)?.groupValues?.get(1)
                    if (found != null) {
                        clientId = found
                        clientIdCheckedAt = System.currentTimeMillis()
                        return
                    }
                }
            } catch (e: Exception) {
                // keep the old id; the next call will try again
            }
        }
    }

    // ---- Search and track metadata ---------------------------------------------------------------------------------------

    private fun fromTrack(t: JSONObject): OnlineVideo {
        val artwork = t.optString("artwork_url").replace("-large", "-t200x200")
        return OnlineVideo(
            id = t.optLong("id").toString(),
            title = t.optString("title"),
            channel = t.optJSONObject("user")?.optString("username").orEmpty(),
            thumb = artwork,
            permalink = "https://soundcloud.com" + t.optString("permalink_uri", t.optString("permalink")),
            // A preview-only track reports its preview length, not the full one.
            duration = (t.optDouble("full_duration", 0.0).takeIf { it > 0 } ?: t.optDouble("duration", 0.0)) / 1000.0,
            playable = hasPlayableTranscoding(t),
            likes = t.optLong("likes_count", 0L)
        )
    }

    /**
     * True when the track has a stream the widget can actually use. Label-provided tracks only offer
     * AES-encrypted HLS; the widget decrypts those itself (SAMPLE-AES in-page), so they play fine and
     * count as playable. What does NOT play without a SoundCloud login is a Go+ exclusive or a
     * snipped preview.
     */
    private fun hasPlayableTranscoding(t: JSONObject): Boolean {
        if (t.optBoolean("snipped", false)) return false
        if (t.optString("policy").equals("GO_PLUS", ignoreCase = true)) return false
        if (t.optBoolean("streamable", true) == false) return false
        val transcodings = t.optJSONObject("media")?.optJSONArray("transcodings") ?: return false
        for (i in 0 until transcodings.length()) {
            val protocol = transcodings.optJSONObject(i)?.optJSONObject("format")?.optString("protocol").orEmpty()
            if (protocol == "hls" || protocol == "progressive" ||
                protocol == "cbc-encrypted-hls" || protocol == "ctr-encrypted-hls"
            ) return true
        }
        return false
    }

    /**
     * Orders results the way a person expects: the song whose title IS the query first (that is the
     * "original/official" upload — covers add words like "cover", "sped up", "remix"), then songs from
     * the artist the query names, then everything else by popularity. Official label uploads no longer
     * sink below random covers.
     */
    private fun rankByRelevance(query: String, results: List<OnlineVideo>): List<OnlineVideo> {
        val q = normalize(query)
        val words = q.split(Regex("\\s+")).filter { it.length > 2 }
        val coverMarkers = listOf(
            "cover", "sped up", "remix", "reimagined", "extended", "live at", "demo", "version",
            "instrumental", "acoustic", "karaoke", "edit", "nightcore", "slowed", "reverb", "2x"
        )
        fun score(v: OnlineVideo): Int {
            val title = normalize(v.title)
            val channel = normalize(v.channel)
            var s = 0
            if (title == q) s += 100
            else if (title.startsWith(q)) s += 80
            else if (title.contains(q)) s += 60
            val lower = v.title.lowercase()
            if (coverMarkers.any { lower.contains(it) }) s -= 45
            if (channel.contains(q) && q.isNotEmpty()) s += 40
            for (w in words) {
                if (title.contains(w)) s += 10
                if (channel.contains(w)) s += 5
            }
            // Popularity as the tie-breaker, scaled so it can decide between similar titles but
            // never outrank an exact title match (likes up to ~50 act like one extra keyword hit).
            s += (v.likes.coerceAtMost(50_000L) / 1_000L).toInt()
            if (v.playable) s += 5
            if (v.duration in 45.0..600.0) s += 5 else s -= 10
            return s
        }
        return results.sortedByDescending { score(it) }
    }

    private fun normalize(s: String) =
        s.lowercase().trim().replace(Regex("[\\p{Punct}]+"), " ").replace(Regex("\\s+"), " ")

    /**
     * Search SoundCloud for tracks, keeping everything the widget can play — including official
     * label uploads with encrypted streams (the widget decrypts them itself).
     *
     * Beyond plain track results the query is also matched against artists and playlists, the way
     * the SoundCloud site does: when a result playlist looks like it holds the song (its title
     * matches the query), its tracks are expanded in; artist profiles in the results contribute
     * their uploads. That is how "kalapastanganan" and artist-name searches surface the song
     * itself instead of unrelated covers.
     */
    fun search(query: String, playableOnly: Boolean = true): List<OnlineVideo> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, OnlineVideo>()
        // SoundCloud's search ANDs every word, so a full query like "terenova cakes and eclairs"
        // can return zero results while shorter forms find the song. The query is tried as typed,
        // then with filler words dropped, then with trailing words dropped, until something hits.
        val attempts = searchLadder(text)
        var collection: JSONArray = JSONArray()
        for (attempt in attempts) {
            val raw = soundcloudGet("/search?q=${encode(attempt)}&limit=25&variant=generic")
            val items = JSONObject(raw).optJSONArray("collection") ?: JSONArray()
            if (items.length() > 0) {
                collection = items
                break
            }
        }
        val playlists = ArrayList<Long>()
        val users = ArrayList<String>()
        for (i in 0 until collection.length()) {
            val item = collection.optJSONObject(i) ?: continue
            when (item.optString("kind")) {
                "track" -> {
                    val video = fromTrack(item)
                    if (video.id.isNotEmpty() && video.title.isNotBlank()) out[video.id] = video
                }
                "playlist", "system-playlist" -> playlists.add(item.optLong("id"))
                "user" -> users.add(item.optLong("id").toString())
            }
        }
        // The site shows people and playlists too. A playlist whose title matches the query very
        // likely holds exactly the song that was searched for (people name playlists after songs).
        for (id in playlists.take(6)) {
            val expanded = runCatching { playlistTracks(id) }.getOrDefault(emptyList())
            for (video in expanded) if (video.id !in out) out[video.id] = video
        }
        // An artist profile in the results means their uploads belong in the list as well.
        for (id in users.take(3)) {
            val tracks = runCatching { userTracks(id) }.getOrDefault(emptyList())
            for (video in tracks) if (video.id !in out) out[video.id] = video
        }
        var results = out.values.toList()
        if (playableOnly) {
            val playable = results.filter { it.playable }
            if (playable.isNotEmpty()) results = playable
        }
        return rankByRelevance(text, results)
    }

    /** The query as typed, then filler words dropped, then trailing words dropped. */
    private fun searchLadder(query: String): List<String> {
        val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val attempts = LinkedHashSet<String>()
        attempts.add(query)
        val filler = setOf("and", "the", "by", "of", "a", "an", "feat", "ft", "x", "official", "video", "music", "audio")
        // Drop filler words first ("cakes and eclairs" -> "cakes eclairs").
        var cur = words.toMutableList()
        for (i in words.indices.reversed()) {
            if (words[i].lowercase(Locale.ROOT) in filler && cur.size > 1) {
                cur.removeAt(i)
                attempts.add(cur.joinToString(" "))
            }
        }
        // Then drop trailing words one at a time ("terenova cakes eclairs" -> "terenova cakes" -> "terenova").
        cur = words.toMutableList()
        while (cur.size > 1) {
            cur.removeAt(cur.size - 1)
            attempts.add(cur.joinToString(" "))
        }
        return attempts.toList()
    }

    /** The tracks inside a playlist. */
    private fun playlistTracks(playlistId: Long): List<OnlineVideo> {
        val raw = soundcloudGet("/playlists/$playlistId")
        val array = JSONObject(raw).optJSONArray("tracks") ?: return emptyList()
        val out = ArrayList<OnlineVideo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val video = fromTrack(item)
            if (video.id.isNotEmpty() && video.title.isNotBlank()) out.add(video)
        }
        return out
    }

    /** The uploads of one artist profile. */
    private fun userTracks(userId: String): List<OnlineVideo> {
        val raw = soundcloudGet("/users/$userId/tracks?limit=10")
        val array = JSONObject(raw).optJSONArray("collection") ?: return emptyList()
        val out = ArrayList<OnlineVideo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val video = fromTrack(item)
            if (video.id.isNotEmpty() && video.title.isNotBlank()) out.add(video)
        }
        return out
    }

    /**
     * Suggestions while typing: SoundCloud's own query-completion endpoint ("kalapa" suggests
     * "kalapastanganan", "ako ang b" suggests "ako ang bagyo"). Returning the completed queries
     * rather than track titles is what makes a half-typed word auto-correct to the real song name.
     */
    fun suggest(query: String): List<String> {
        val text = query.trim()
        if (text.length < 2) return emptyList()
        val raw = runCatching {
            soundcloudGet("/search/queries?q=${encode(text)}")
        }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONObject(raw).optJSONArray("collection") }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val completed = item.optString("query").ifBlank { item.optString("output") }
            if (completed.isBlank()) continue
            // Only completions that actually extend what was typed; a suggestion equal to the
            // input is pointless.
            if (completed.equals(text, ignoreCase = true)) continue
            out.add(completed)
        }
        return out.toList().take(6)
    }

    fun recommended(): List<OnlineVideo> = search("popular music")

    /** One track by id, refreshed from SoundCloud (used to fill in permalink/duration). */
    fun track(id: String): OnlineVideo? {
        val raw = soundcloudGet("/tracks/$id")
        return runCatching { fromTrack(JSONObject(raw)) }.getOrNull()
    }

    /**
     * Songs SoundCloud itself considers similar to this one (the same endpoint the site's related
     * tracks use). Used for auto-continue: after a song ends the next one comes from here, so the
     * Online tab keeps playing music like the song that was just heard.
     */
    fun related(trackId: String): List<OnlineVideo> {
        val raw = soundcloudGet("/tracks/$trackId/related?limit=10")
        val array = runCatching { JSONObject(raw).optJSONArray("collection") }.getOrNull() ?: return emptyList()
        val out = ArrayList<OnlineVideo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val video = fromTrack(item)
            if (video.id.isNotEmpty() && video.title.isNotBlank()) out.add(video)
        }
        return out
    }

    /**
     * The HLS playlist URL of a track: resolve the transcoding endpoint, then the media CDN URL.
     * Both steps use the public client_id. Returns null when the track is not streamable.
     */
    fun hlsPlaylist(trackId: String): String? {
        val raw = soundcloudGet("/tracks/$trackId")
        val track = JSONObject(raw)
        if (track.optBoolean("snipped", false)) return null
        val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        // Prefer the "sq" aac HLS rendition (160 kbps); fall back to any non-abr HLS.
        var best: JSONObject? = null
        for (i in 0 until transcodings.length()) {
            val tc = transcodings.optJSONObject(i) ?: continue
            if (tc.optJSONObject("format")?.optString("protocol") != "hls") continue
            if (tc.optJSONObject("format")?.optString("mime_type")?.contains("mpegurl") == true) continue
            if (tc.optString("quality") == "sq") best = tc
            if (best == null) best = tc
        }
        if (best == null) return null
        val resolved = JSONObject(get(best.optString("url") + "?client_id=$clientId", browser = true))
        return resolved.optString("url").takeIf { it.isNotBlank() }
    }

    // ---- HLS download (native SoundCloud, no converter) ---------------------------------------------------------------

    /**
     * Downloads a track by fetching its HLS segments (init.mp4 + media .m4s chunks) and joining them into
     * a single playable MP4 stream. Works with plain HTTP and needs no converter service. Returns null
     * when the track has no usable HLS rendition (or it cannot be fetched).
     */
    fun downloadHlsAudio(context: Context, video: OnlineVideo, progress: (Int) -> Unit): DownloadedAudio? {
        val playlist = hlsPlaylist(video.id) ?: return null
        progress(10)
        val body = get(playlist, browser = true)
        progress(20)
        // An AES-encrypted playlist cannot be joined into a playable file by the app (the widget
        // decrypts it in-page for playback, but a saved file must be unencrypted). Hand it to the
        // converter instead.
        if (body.contains("#EXT-X-KEY:METHOD=")) return null
        val initUrl = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val segments = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segments.isEmpty()) return null
        // The true length of the audio, from the playlist itself — NOT the track metadata, which for
        // preview tracks claims far more than the audio actually holds.
        val seconds = Regex("#EXTINF:([\\d.]+)").findAll(body).map { it.groupValues[1].toDouble() }.sum()
        val out = ByteArrayOutputStream()
        // The init segment carries the MP4 headers; without it the file will not decode.
        if (initUrl != null) {
            val init = getBytes(initUrl) ?: return null
            out.write(init)
        }
        val total = segments.size
        for ((index, segment) in segments.withIndex()) {
            val bytes = getBytes(segment) ?: return null
            out.write(bytes)
            progress(20 + (70 * (index + 1) / total))
        }
        progress(90)
        val audio = out.toByteArray()
        if (audio.size < 20_000) return null
        return DownloadedAudio(audio, seconds)
    }

    /** What a native download produced: the audio bytes plus the length the playlist really holds. */
    class DownloadedAudio(val bytes: ByteArray, val seconds: Double)

    // ---- Cover art -------------------------------------------------------------------------------------------------------

    /** The best-resolution cover art of a track, downloaded (embedded into saved files). */
    fun coverArt(video: OnlineVideo): ByteArray? {
        if (video.thumb.isBlank()) return null
        val url = video.thumb.replace("-t200x200", "-t500x500").replace("-large", "-t500x500")
        val bytes = getBytes(url) ?: getBytes(video.thumb) ?: return null
        if (bytes.size < 200) return null
        return bytes
    }

    // ---- File saves ---------------------------------------------------------------

    /** File-system safe name used for the downloaded song (also how a repeat download is recognised). */
    fun safeName(video: OnlineVideo): String =
        video.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "download" }.take(120)

    /** True when this song's file is already in Music/Music Rage (a deleted file counts as not downloaded). */
    fun isDownloaded(context: Context, video: OnlineVideo): Boolean = runCatching {
        val name = safeName(video)
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} IN (?, ?) AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$name.m4a", "$name.mp3", "Music/Music Rage%"),
            null
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** Bumped after every finished download so the buttons re-check their state. */
    var downloadVersion: Int by mutableIntStateOf(0)
        private set

    /**
     * Saves an m4a download. A "moov.udta.meta.ilst.covr" atom with the track's cover art is inserted
     * into the MP4 (see [Mp4TagWriter]), so the file carries its album art and the library shows it
     * immediately instead of a grey tile.
     */
    fun saveM4a(context: Context, bytes: ByteArray, video: OnlineVideo, seconds: Double, cover: ByteArray?) {
        val safe = safeName(video)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.m4a")
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.ARTIST, video.channel)
            put(MediaStore.Audio.Media.ALBUM, "Music Rage Downloads")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Music Rage")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            // The length the HLS playlist really holds, not the track's advertised one (which can be a
            // preview length or an over-estimate); the media scanner corrects it after the save anyway.
            if (seconds > 0) put(MediaStore.Audio.Media.DURATION, (seconds * 1000).toLong())
        }
        val target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException("Save failed: could not create the file")
        try {
            val payload = if (cover != null) runCatching { Mp4TagWriter.withCover(bytes, cover) }.getOrDefault(bytes) else bytes
            resolver.openOutputStream(target)?.use { it.write(payload) }
                ?: throw ApiException("Save failed: could not write the file")
            values.clear()
            putDone(values)
            resolver.update(target, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    private fun putDone(values: ContentValues) {
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
    }

    // ---- Download orchestration --------------------------------------------------------------

    /** The song whose download is running right now (drives the button label). */
    var preparingId: String? by mutableStateOf(null)
        private set

    /** Progress of the running download, 0..100. -1 while only "preparing" (no percentage yet). */
    var downloadProgress: Int by mutableStateOf(-1)
        private set

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun say(context: Context, message: String) {
        mainHandler.post { com.musicrage.app.Toaster.show(context, message, long = true) }
    }

    private fun setProgress(value: Int) {
        mainHandler.post { downloadProgress = value.coerceIn(0, 100) }
    }

    /**
     * Starts a download that keeps going even if the person leaves the Online tab. The audio is pulled
     * straight from SoundCloud's CDN (HLS segments joined into one file) with the cover art embedded;
     * if that is impossible the old converter is tried with the track's SoundCloud page.
     */
    fun startDownload(context: Context, video: OnlineVideo) {
        if (preparingId != null) {
            say(context, "A download is already in progress")
            return
        }
        if (isDownloaded(context, video)) {
            say(context, "Already downloaded")
            return
        }
        preparingId = video.id
        downloadProgress = 0
        say(context, "Preparing download…")
        downloadScope.launch {
            try {
                val native = downloadHlsAudio(context, video) { setProgress(it) }
                val cover = runCatching { coverArt(video) }.getOrNull()
                if (native != null && native.bytes.size > 20_000) {
                    say(context, "Downloading…")
                    saveM4a(context, native.bytes, video, native.seconds, cover)
                    setProgress(100)
                    say(context, "Saved to Music/Music Rage")
                } else {
                    // Native HLS failed (encrypted-only track, preview, restricted): try the converter
                    // with the track's SoundCloud page.
                    val link = prepareMp3(video.permalink.ifEmpty { "https://soundcloud.com" })
                    say(context, "Downloading…")
                    saveMp3(context, link, video, cover)
                    setProgress(100)
                    say(context, "Saved to Music/Music Rage")
                }
            } catch (e: Exception) {
                say(context, e.message?.takeIf { it.isNotBlank() } ?: "Download failed")
            } finally {
                mainHandler.post {
                    preparingId = null
                    downloadProgress = -1
                    downloadVersion += 1
                }
            }
        }
    }

    // ---- Legacy converter (mp3juice / thetacloud) kept as the fallback ----------------------------------------------

    private const val MP3JUICE_API_KEY = "mp3juice_c1ce77421b808bc5b89a307da142f3211313469053730109c30c3c4fc9abff3b"
    private const val CONVERT_HOST = "https://theta.thetacloud.org/api/v1"
    private const val SITE = "https://mp3juice.sc"

    private fun siteHeaders(bearer: String? = null): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Origin"] = SITE
        map["Referer"] = "$SITE/"
        map["Accept"] = "application/json, text/plain, */*"
        if (bearer != null) map["Authorization"] = "Bearer $bearer"
        return map
    }

    private fun joinQuery(base: String, params: String): String {
        val separator = when {
            !base.contains("?") -> "?"
            base.endsWith("?") || base.endsWith("&") -> ""
            else -> "&"
        }
        return base + separator + params
    }

    private fun <T> step(name: String, block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw ApiException("$name failed: ${e.message}")
    } catch (e: java.io.IOException) {
        throw ApiException("$name failed: ${e.message ?: "network error"}")
    } catch (e: org.json.JSONException) {
        throw ApiException("$name failed: unexpected reply from the server")
    }

    private fun clock() = System.currentTimeMillis()

    /** auth -> init -> convert (-> redirect) (-> progress). Returns the final MP3 link. Call off the main thread. */
    fun prepareMp3(sourceUrl: String): String {
        val id = encode(sourceUrl)
        setProgress(5)

        var token = ""
        var authProblem = "no token returned"
        for (withKey in listOf(false, true)) {
            if (withKey && MP3JUICE_API_KEY.isBlank()) continue
            try {
                val url = "$CONVERT_HOST/auth?" + (if (withKey) "api_key=${encode(MP3JUICE_API_KEY)}&" else "") + "_=${clock()}"
                val reply = JSONObject(get(url, browser = true, headers = siteHeaders()))
                token = reply.optString("key")
                if (token.isNotBlank()) break
                authProblem = serverError(reply) ?: "no token returned"
            } catch (e: Exception) {
                authProblem = e.message ?: "network error"
            }
        }
        if (token.isBlank()) throw ApiException("Auth failed: $authProblem")
        setProgress(12)

        val init = step("Init") { JSONObject(get("$CONVERT_HOST/init?_=${clock()}", browser = true, headers = siteHeaders(token))) }
        val convertUrl = init.optString("convertURL")
        if (convertUrl.isBlank()) throw ApiException("Init failed: ${serverError(init) ?: "no convert address returned"}")
        setProgress(20)

        val convert = step("Convert") {
            JSONObject(get(joinQuery(convertUrl, "v=$id&f=mp3&_=${clock() / 1000}"), browser = true, headers = siteHeaders()))
        }
        var progressUrl = convert.optString("progressURL")
        var downloadUrl = convert.optString("downloadURL")
        var lastReply = convert
        setProgress(30)

        val redirectUrl = convert.optString("redirectURL")
        if (downloadUrl.isBlank() && redirectUrl.isNotBlank()) {
            val redirect = step("Redirect") { JSONObject(get(redirectUrl, browser = true, headers = siteHeaders())) }
            lastReply = redirect
            if (progressUrl.isBlank()) progressUrl = redirect.optString("progressURL")
            downloadUrl = redirect.optString("downloadURL")
        }
        if (downloadUrl.isBlank()) {
            throw ApiException("Convert failed: ${serverError(lastReply) ?: serverError(convert) ?: "the server returned no download link"}")
        }

        if (progressUrl.isNotBlank()) {
            val deadline = clock() + 150_000L
            var lastPercent = -1
            while (true) {
                val progress = step("Progress") { JSONObject(get(progressUrl, browser = true, headers = siteHeaders())) }
                val stepValue = progress.optInt("progress", 0)
                val percent = 35 + ((stepValue.coerceIn(0, 3) * 60) / 3)
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(percent)
                }
                if (stepValue >= 3) break
                val failure = progress.opt("error")
                val failureText = when (failure) {
                    is JSONObject -> failure.optString("message")
                    is String -> failure
                    else -> ""
                }.trim()
                if (failureText.isNotEmpty() && failureText != "0" && failureText != "false") throw ApiException("Convert failed: $failureText")
                if (clock() > deadline) throw ApiException("Convert failed: it took too long")
                Thread.sleep(1_500L)
            }
        }
        return if (downloadUrl.contains("v=")) downloadUrl else joinQuery(downloadUrl, "v=$id&f=mp3&r=mp3juice.sc")
    }

    /**
     * Streams the MP3, embeds its metadata and cover art, and saves it into Music/Music Rage.
     * The ID3 tag (title, artist, album, embedded APIC cover) is written by [Id3Writer], so the
     * file is complete the moment it lands — no grey tiles, no "Unknown artist".
     */
    fun saveMp3(context: Context, url: String, video: OnlineVideo, cover: ByteArray?) {
        val safe = safeName(video)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.mp3")
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.ARTIST, video.channel)
            put(MediaStore.Audio.Media.ALBUM, "Music Rage Downloads")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Music Rage")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            // No DURATION here: the converter's file is whatever length it actually is, and the
            // media scanner fills the true value in once the file is complete.
        }
        val target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException("Save failed: could not create the file")
        try {
            val bytes = downloadBytesWithProgress(url)
            val out = bytes ?: throw ApiException("Download failed: nothing was received")
            val tagged = runCatching {
                val tag = Id3Writer.buildTag(safe, video.channel, "Music Rage Downloads", cover)
                if (tag != null) Id3Writer.prependTag(out, tag) else out
            }.getOrDefault(out)
            resolver.openOutputStream(target)?.use { it.write(tagged) }
                ?: throw ApiException("Save failed: could not write the file")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    /** Plain byte download with progress; used for the converter MP3 fallback. */
    private fun downloadBytesWithProgress(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", BROWSER_AGENT)
        connection.setRequestProperty("Referer", "$SITE/")
        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != 206) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ApiException("Download failed: " + errorMessage(body, code))
            }
            val buffer = ByteArrayOutputStream()
            val contentLength = connection.contentLength
            var received = 0L
            connection.inputStream.use { input ->
                val chunk = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    received += read
                    if (contentLength > 0) {
                        val target = 95 + ((received * 5) / contentLength).toInt().coerceAtMost(5)
                        if (target > downloadProgress) mainHandler.post { if (target > downloadProgress) downloadProgress = target }
                    }
                }
            }
            val raw = buffer.toByteArray()
            if (raw.size < 100_000) throw ApiException("Download failed: the file was empty (${raw.size} bytes)")
            return raw
        } finally {
            connection.disconnect()
        }
    }
}
