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
 * A song found on YouTube. [id] is the 11-character video id, [channel] the artist/uploading channel,
 * [thumb] the cover thumbnail (i.ytimg.com), [permalink] the youtube.com/watch?v= page, [duration]
 * the video length in seconds.
 */
data class OnlineVideo(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String,
    val permalink: String = "",
    val duration: Double = 0.0,
    val playable: Boolean = true,
    val likes: Long = 0L,
    /** The album the song belongs to (from the video title when YouTube exposes one). */
    val album: String = ""
)

object OnlineApi {
    class ApiException(message: String) : Exception(message)

    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---- HTTP ------------------------------------------------------------------------------------------------------------

    private fun get(url: String, browser: Boolean = false, headers: Map<String, String> = emptyMap(), range: String? = null, fast: Boolean = false): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        // "fast" is for endpoints only used as extra sources: a dead one must fail in seconds,
        // not stall the whole search while its 20-second read timeout slowly runs out.
        connection.connectTimeout = if (fast) 4_000 else 10_000
        connection.readTimeout = if (fast) 5_000 else 20_000
        if (browser) {
            connection.setRequestProperty("User-Agent", BROWSER_AGENT)
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        } else {
            connection.setRequestProperty("User-Agent", "MusicRage/1.3")
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

    /** POST + response headers, for APIs that return their token in a header (yt-api.org). */
    private fun postJson(url: String, body: String?, authHeader: String? = null, referer: String): Pair<String, Map<String, List<String>>> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        // The converter's /api endpoint only accepts urlencoded requests with an empty body —
        // a JSON body there gets answered with 401 Unauthorized.
        connection.setRequestProperty("Content-Type", if (body == null) "application/x-www-form-urlencoded" else "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        connection.setRequestProperty("User-Agent", BROWSER_AGENT)
        connection.setRequestProperty("Origin", "https://yt-api.org")
        connection.setRequestProperty("Referer", referer)
        if (authHeader != null) connection.setRequestProperty("Authorization", authHeader)
        if (body != null) connection.doOutput = true
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // headerFields conveniently skips the status line; keys are already case-insensitive there
            // but normalized here anyway to be safe.
            val headers = (connection.headerFields ?: emptyMap())
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
            if (code !in 200..299) throw ApiException(errorMessage(text, code))
            return text to headers
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

    // ---- YouTube search: mp3juice (primary) + Piped (fallback) ---------------------------------------------------------

    private const val MP3JUICE_API_KEY = "mp3juice_c1ce77421b808bc5b89a307da142f3211313469053730109c30c3c4fc9abff3b"
    private const val SITE = "https://mp3juice.sc"
    private const val PIPED_API = "https://api.piped.private.coffee"

    /** The id part of a Piped "/watch?v=ID" url. */
    private fun pipedId(url: String): String {
        val m = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)
        return m?.groupValues?.get(1) ?: ""
    }

    /**
     * Searches YouTube for songs. mp3juice's yt results are the primary source (fast, official),
     * Piped's public search is the fallback when mp3juice does not answer. Titles are cleaned of the
     * "Official Video / Lyric Video" noise so the list reads like a music app.
     */
    fun search(query: String, playableOnly: Boolean = true): List<OnlineVideo> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, OnlineVideo>()
        // 1) Piped search — fast and live (mp3juice's search started answering 403 in late 2026,
        //    which made every query wait for a dead endpoint before falling through).
        for (video in runCatching { pipedSearch(text) }.getOrDefault(emptyList())) {
            if (video.id.isNotEmpty() && video.id !in out) out[video.id] = video
        }
        // 2) mp3juice yt search — the former primary, now the top-up when Piped comes back thin.
        if (out.size < 5) {
            for (video in runCatching { mp3juiceSearch(text) }.getOrDefault(emptyList())) {
                if (video.id.isNotEmpty() && video.id !in out) out[video.id] = video
            }
        }
        val results = out.values.toList()
        return rankByRelevance(text, results)
    }

    /** mp3juice's YouTube results: {count, ytCount, yt:[{id,title,duration}], sc…}. */
    private fun mp3juiceSearch(text: String): List<OnlineVideo> {
        val b64 = java.util.Base64.getEncoder().encodeToString(encode(text).toByteArray(Charsets.UTF_8))
        val raw = get(
            "$SITE/api/v1/search?api_key=${encode(MP3JUICE_API_KEY)}&y=y&q=$b64",
            browser = true,
            // Secondary source: when this endpoint is down (it started returning 403 in late 2026)
            // the search must not sit through a long timeout — Piped's results already fill the list.
            fast = true,
            headers = mapOf("Origin" to SITE, "Referer" to "$SITE/", "Accept" to "application/json, text/plain, */*")
        )
        val items = JSONObject(raw).optJSONArray("yt") ?: JSONArray()
        val out = ArrayList<OnlineVideo>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.length != 11) continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            out.add(OnlineVideo(
                id = id,
                title = cleanTitle(title),
                // Channel is not in mp3juice's reply; oEmbed fills it when the video is played or downloaded.
                channel = channelFromTitle(title),
                thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                permalink = "https://www.youtube.com/watch?v=$id",
                duration = parseDuration(item.optString("duration"))
            ))
        }
        return out
    }

    /** Piped's public search: {items:[{url,title,uploaderName,duration,thumbnail}]}. */
    private fun pipedSearch(text: String): List<OnlineVideo> {
        val raw = get("$PIPED_API/search?q=${encode(text)}&filter=music_videos", browser = true)
        val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
        val out = ArrayList<OnlineVideo>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = pipedId(item.optString("url"))
            if (id.length != 11) continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            val channel = item.optString("uploaderName").trim()
            out.add(OnlineVideo(
                id = id,
                title = cleanTitle(title),
                channel = if (channel.isBlank()) channelFromTitle(title) else stripTopic(channel),
                thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                permalink = "https://www.youtube.com/watch?v=$id",
                duration = item.optDouble("duration", 0.0)
            ))
        }
        return out
    }

    /** "4:05" / "1:02:03" → seconds. */
    private fun parseDuration(text: String): Double {
        val parts = text.split(":").map { it.toIntOrNull() ?: 0 }
        if (parts.isEmpty() || parts.any { it < 0 }) return 0.0
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1].toDouble()
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2].toDouble()
            else -> 0.0
        }
    }

    /**
     * "Multo - Cup of Joe (Official Lyric Video)" → "Cup of Joe": YouTube music titles are
     * "Song - Artist" far more often than the reverse, and the artist is what metadata needs.
     */
    private fun channelFromTitle(rawTitle: String): String {
        val title = rawTitle.trim()
        val m = SITE_PREFIX.find(title)
        val cleaned = (if (m != null) title.removeRange(m.range) else title).trim()
        val dash = cleaned.split(Regex("\\s+[-–—]\\s+"))
        return if (dash.size >= 2) dash[1].trim() else ""
    }

    /** "- Topic" channels are YouTube's auto-generated artist channels; the real artist is the name before it. */
    private fun stripTopic(channel: String): String =
        channel.removeSuffix(" - Topic").removeSuffix("- Topic").trim()

    /** Removes the bracket noise YouTube titles carry; keeps Ft/feat parts (they name real artists). */
    private fun cleanTitle(title: String): String {
        var t = title
        for (noise in listOf(
            Regex("""\((?:official\s+)?(?:music\s+)?(?:lyric[s]?\s+)?video\)""", RegexOption.IGNORE_CASE),
            Regex("""\[official\s+(?:music\s+)?video\]""", RegexOption.IGNORE_CASE),
            Regex("""\(official\s+audio\)""", RegexOption.IGNORE_CASE),
            Regex("""\(lyric[s]?\s+video\)""", RegexOption.IGNORE_CASE),
            Regex("""\(audio\s+only\)""", RegexOption.IGNORE_CASE)
        )) t = t.replace(noise, "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val SITE_PREFIX = Regex("""^\s*[\w-]+(\.[\w-]+)*\.(com|net|org|io|cc|to|me|app|co|info|xyz)\s*[-–|:]\s*""", RegexOption.IGNORE_CASE)

    /** Official uploads and exact matches first, covers/lyrics/8d versions after. */
    private fun rankByRelevance(query: String, videos: List<OnlineVideo>): List<OnlineVideo> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        return videos.sortedWith(compareByDescending<OnlineVideo> { v ->
            val hay = (v.title + " " + v.channel).lowercase(Locale.ROOT)
            words.count { hay.contains(it) }
        }.thenByDescending { v -> v.duration in 90.0..500.0 }.thenBy { it.title.length })
    }

    // ---- Suggestions (auto-correct while typing) ------------------------------------------------------------------------

    /**
     * Suggestions while typing: YouTube's own completion endpoint (the same one youtube.com's search
     * box uses). "multo cup of" suggests "multo cup of joe" — a half-typed word auto-corrects to the
     * real song name.
     */
    fun suggest(query: String): List<String> {
        val text = query.trim()
        if (text.length < 2) return emptyList()
        val raw = runCatching {
            get("https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&xhr=t&q=${encode(text)}", browser = true)
        }.getOrNull() ?: return emptyList()
        // The reply is JSON-ish: ["multo cup of",[["multo cup of joe",0,[512]],...]]
        val suggestions = LinkedHashSet<String>()
        val array = runCatching { JSONArray(raw).optJSONArray(1) }.getOrNull()
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONArray(i) ?: continue
                val completed = entry.optString(0).trim()
                if (completed.isBlank() || completed.equals(text, ignoreCase = true)) continue
                suggestions.add(completed)
            }
        }
        if (suggestions.isEmpty()) {
            // Some replies wrap the data differently (window.google.suggest.p...); catch plain pairs.
            for (m in Regex("\\[\"([^\"]{2,60})\",0").findAll(raw)) {
                val completed = m.groupValues[1].trim()
                if (!completed.equals(text, ignoreCase = true)) suggestions.add(completed)
            }
        }
        return suggestions.toList().take(6)
    }

    fun recommended(): List<OnlineVideo> = search("OPM love songs")

    /** One video's metadata, refreshed through oEmbed (title + artist + thumbnail). */
    fun track(id: String): OnlineVideo? {
        val oembed = oembed(id) ?: return null
        return OnlineVideo(
            id = id,
            title = cleanTitle(oembed.first),
            channel = oembed.second,
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            permalink = "https://www.youtube.com/watch?v=$id",
            album = albumFrom(oembed.first, oembed.second)
        )
    }

    /** oEmbed gives (title, author) for a video id; both are what the file metadata needs. */
    private fun oembed(id: String): Pair<String, String>? {
        val raw = runCatching {
            get("https://www.youtube.com/oembed?url=${encode("https://www.youtube.com/watch?v=$id")}&format=json", browser = true)
        }.getOrNull() ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val title = obj.optString("title").trim()
        if (title.isBlank()) return null
        return title to obj.optString("author_name").trim()
    }

    /**
     * The album for a YouTube song: music videos rarely state one, so a title marker like
     * "Multo (from 'Silakbo')" wins when present. Otherwise the song title stands in — a downloaded
     * file must never carry a made-up album name.
     */
    private fun albumFrom(title: String, artist: String): String {
        val fromMarker = Regex("""[(\["'](?:from|in|album[:\s])\s*['"']?([^)\]"']{2,60})""", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.trim()
        if (!fromMarker.isNullOrEmpty()) return fromMarker
        return cleanTitle(title)
    }

    // ---- Related / similar songs (auto-next) ----------------------------------------------------------------------------

    /**
     * Songs similar to this one. Piped's related endpoint is unreliable, so similar songs are
     * searched directly: the artist's other songs plus songs sharing the title. This is what
     * auto-next picks from after a song ends.
     */
    fun related(video: OnlineVideo): List<OnlineVideo> {
        val out = LinkedHashMap<String, OnlineVideo>()
        // Each search source is tried independently and merges fast; Piped (live) answers first,
        // so the artist's other songs come up even when the secondary search host is down.
        // 1) The artist's other songs.
        if (video.channel.isNotBlank()) {
            for (v in runCatching { search("${video.channel} songs") }.getOrDefault(emptyList())) {
                if (v.id != video.id) out[v.id] = v
            }
        }
        // 2) Songs whose title matches (covers, versions, the same song on other channels).
        for (v in runCatching { search(songTitle(video)) }.getOrDefault(emptyList())) {
            if (v.id != video.id) out[v.id] = v
        }
        // 3) The genre the song belongs to, when nothing else came up.
        if (out.isEmpty() && video.channel.isNotBlank()) {
            for (v in runCatching { search("${video.channel} similar songs") }.getOrDefault(emptyList())) {
                if (v.id != video.id) out[v.id] = v
            }
        }
        return out.values.toList().take(12)
    }

    /** The song name without the artist ("Multo - Cup of Joe" → "Multo"). */
    private fun songTitle(video: OnlineVideo): String {
        val dash = video.title.split(Regex("\\s+[-–—]\\s+"))
        return dash.first().trim().ifBlank { video.title }
    }

    /** Alternative uploads of the same song (for when the chosen one refuses to play). */
    fun alternatives(video: OnlineVideo, avoid: Set<String>): List<OnlineVideo> =
        related(video).filter { it.id !in avoid }

    // ---- Cover art -------------------------------------------------------------------------------------------------------

    /** The best-resolution cover art of a video, downloaded (embedded into saved files). */
    fun coverArt(video: OnlineVideo): ByteArray? {
        if (video.thumb.isBlank()) return null
        val url = video.thumb.replace("hqdefault.jpg", "maxresdefault.jpg")
        val bytes = getBytes(url) ?: getBytes(video.thumb) ?: return null
        if (bytes.size < 200) return null
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            // not a JPEG (404 pages come back as HTML) — fall back to the small one
            val small = getBytes(video.thumb) ?: return null
            return if (small.size > 200) small else null
        }
        return bytes
    }

    // ---- File saves -------------------------------------------------------------------

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
            arrayOf("$name.mp3", "$name.m4a", "Music/Music Rage%"),
            null
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** Bumped after every finished download so the buttons re-check their state. */
    var downloadVersion: Int by mutableIntStateOf(0)
        private set

    /**
     * Saves the MP3 the converter produced. The ID3 tag (title, artist, real album, embedded APIC
     * cover) is written by [Id3Writer], so the file is complete the moment it lands — a real MP3
     * with real metadata, not an m4a and not "Unknown artist".
     */
    fun saveMp3(context: Context, url: String, video: OnlineVideo, cover: ByteArray?) {
        val safe = safeName(video)
        val album = video.album.ifBlank { safe }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.mp3")
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.ARTIST, video.channel)
            put(MediaStore.Audio.Media.ALBUM, album)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Music Rage")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (video.duration > 0) put(MediaStore.Audio.Media.DURATION, (video.duration * 1000).toLong())
        }
        val target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException("Save failed: could not create the file")
        try {
            val bytes = downloadBytesWithProgress(url)
            val out = bytes ?: throw ApiException("Download failed: nothing was received")
            val tagged = runCatching {
                val tag = Id3Writer.buildTag(safe, video.channel, album, cover)
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

    // ---- Download orchestration ------------------------------------------------------------

    /** The song whose download is running right now (drives the button label). */
    var preparingId: String? by mutableStateOf(null)
        private set

    /** Progress of the running download, 0..100. -1 while only "preparing" (no percentage yet). */
    var downloadProgress: Int by mutableIntStateOf(-1)
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
     * Starts a download that keeps going even if the person leaves the Online tab. The video is
     * converted to MP3 by the mp3juice/thetacloud converter, and the file is saved with its full
     * ID3 metadata (title, artist, album) and the embedded cover art. When the converter cannot
     * handle the video a clear error message is shown.
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
                // Fill artist/album when the search list did not have them (mp3juice gives id+title only).
                val full = enrich(video)
                val cover = runCatching { coverArt(full) }.getOrNull()
                val link = prepareMp3(full.permalink.ifEmpty { "https://www.youtube.com/watch?v=${full.id}" })
                say(context, "Downloading…")
                saveMp3(context, link, full, cover)
                setProgress(100)
                say(context, "Saved to Music/Music Rage")
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

    /** A video from the search list, with artist/album filled in from oEmbed. */
    private fun enrich(video: OnlineVideo): OnlineVideo {
        if (video.channel.isNotBlank() && video.album.isNotBlank()) return video
        val meta = runCatching { oembed(video.id) }.getOrNull() ?: return video
        val artist = video.channel.ifBlank { meta.second }
        return video.copy(channel = artist, album = albumFrom(meta.first, meta.second))
    }

    // ---- Converter (mp3juice / thetacloud): YouTube video → MP3 -------------------------------------------------------

    private const val CONVERT_HOST = "https://theta.thetacloud.org/api/v1"

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

    /**
     * New converter: yt-api.org (the API behind the mp3juice widget, verified working after the old
     * thetacloud host started returning 403 for everyone). Flow, exactly as the widget's own JS does it:
     * POST / with Authorization = base64({videoId, format}) — the JWT token comes back in the
     * "authorization" response header; POST /api with that token returns the downloadUrl directly.
     */
    private const val YTAPI_HOST = "https://yt-api.org"

    /** Extracts the 11-char video id from any YouTube URL (or passes a bare id through). */
    private fun videoIdFrom(sourceUrl: String): String {
        val direct = Regex("([A-Za-z0-9_-]{11})").find(sourceUrl)?.groupValues?.get(1)
        return if (sourceUrl.length == 11) sourceUrl else direct ?: sourceUrl
    }

    private fun prepareMp3ViaYtApi(videoId: String): String {
        setProgress(8)
        // The converter's own widget does POST / (token) then POST /api (convert) — the second call
        // answers directly with the downloadUrl, no progress polling needed.
        val body = """{"videoId":"$videoId","format":"mp3"}"""
        val auth = android.util.Base64.encodeToString(body.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val (_, tokenHeaders) = postJson("$YTAPI_HOST/", body, authHeader = auth, referer = "$YTAPI_HOST/button/mp3/$videoId")
        val token = tokenHeaders["authorization"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw ApiException("the converter returned no token")
        setProgress(16)

        val (convertReply, _) = postJson("$YTAPI_HOST/api", body = null, authHeader = token, referer = "$YTAPI_HOST/button/mp3/$videoId")
        val downloadUrl = runCatching { JSONObject(convertReply).optString("downloadUrl") }.getOrDefault("")
        if (downloadUrl.isBlank()) throw ApiException("the server returned no download link")
        setProgress(30)
        return downloadUrl
    }

    /** auth -> init -> convert (-> redirect) (-> progress). Returns the final MP3 link. Call off the main thread. */
    fun prepareMp3(sourceUrl: String): String {
        val id = encode(sourceUrl)
        setProgress(5)

        // New converter first (verified working); the old thetacloud flow stays as a fallback in
        // case yt-api.org is ever the one that goes down. Keep its error for the toast if both fail.
        val ytApiError = runCatching { prepareMp3ViaYtApi(videoIdFrom(sourceUrl)) }
        ytApiError.getOrNull()?.let { return it }
        setProgress(10)

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
        if (token.isBlank()) throw ApiException(ytApiError.exceptionOrNull()?.message?.let { "Auth failed: $it" } ?: "Auth failed: $authProblem")
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

    /** Plain byte download with progress; used for the converter MP3. */
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
            } else if (url.contains("hei.lat") || url.contains("yt-api.org")) {
                // The new converter serves from its own CDN: it needs its own origin, not mp3juice's.
                connection.setRequestProperty("Referer", "$YTAPI_HOST/")
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
