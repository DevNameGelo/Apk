package com.musicrage.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class OnlineVideo(val id: String, val title: String, val channel: String, val thumb: String)

object OnlineApi {
    class ApiException(message: String) : Exception(message)

    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun get(url: String, browser: Boolean = false, headers: Map<String, String> = emptyMap()): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        if (browser) {
            connection.setRequestProperty("User-Agent", BROWSER_AGENT)
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        } else {
            connection.setRequestProperty("User-Agent", "MusicRage/1.2")
        }
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(errorMessage(body, code))
            return body
        } finally {
            connection.disconnect()
        }
    }

    /** Pulls a readable message out of a server reply (JSON error fields or the start of the body). */
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

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ---- Keyless search ---------------------------------------------------------------------------------------------
    // Tries, in order: the public YouTube results page, YouTube's own web search endpoint, then public Piped/Invidious
    // mirrors. The first one that returns videos wins. No account or API key is needed.

    private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val FALLBACK_CLIENT_VERSION = "2.20260101.00.00"
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")

    private fun thumbFor(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun search(query: String): List<OnlineVideo> {
        val text = query.trim()
        var lastError: Exception? = null
        var anyOk = false
        val attempts = listOf<() -> List<OnlineVideo>>(
            { searchPage(text) },
            { searchInnerTube(text, null, null) },
            { searchPiped(text) },
            { searchInvidious(text) }
        )
        for (attempt in attempts) {
            try {
                val found = attempt()
                anyOk = true
                if (found.isNotEmpty()) return found
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anyOk && lastError != null) {
            throw ApiException(
                if (lastError is java.io.IOException) "Could not reach YouTube. Check your connection and try again."
                else "Search is unavailable right now. Try again in a moment."
            )
        }
        return emptyList()
    }

    fun recommended(): List<OnlineVideo> = search("top music hits")

    private fun post(url: String, body: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", BROWSER_AGENT)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
        connection.setRequestProperty("Origin", "https://www.youtube.com")
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiException(errorMessage(response, code))
            return response
        } finally {
            connection.disconnect()
        }
    }

    /** Reads the results page like a browser. Also remembers the page's own client version for the fallback call. */
    private fun searchPage(query: String): List<OnlineVideo> {
        val html = get("https://www.youtube.com/results?search_query=${encode(query)}&sp=EgIQAQ%253D%253D", browser = true)
        val data = extractInitialData(html)
        if (data != null) {
            val found = LinkedHashMap<String, OnlineVideo>()
            collectVideos(data, found)
            if (found.isNotEmpty()) return found.values.take(30)
        }
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val version = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val viaApi = searchInnerTube(query, key, version)
        if (viaApi.isNotEmpty()) return viaApi
        return rawIds(html)
    }

    /** Last resort for the page: ids with titles pulled out of the raw text. */
    private fun rawIds(html: String): List<OnlineVideo> {
        val found = LinkedHashMap<String, OnlineVideo>()
        val pattern = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\".{0,1500}?\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (match in pattern.findAll(html)) {
            val id = match.groupValues[1]
            if (found.containsKey(id)) continue
            val title = unescapeJs(match.groupValues[2])
            if (title.isNotBlank()) found[id] = OnlineVideo(id, title, "", thumbFor(id))
            if (found.size >= 30) break
        }
        return found.values.toList()
    }

    private fun searchInnerTube(query: String, pageKey: String?, pageVersion: String?): List<OnlineVideo> {
        val key = pageKey?.takeIf { it.isNotBlank() } ?: INNERTUBE_KEY
        val version = pageVersion?.takeIf { it.isNotBlank() } ?: FALLBACK_CLIENT_VERSION
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject().put("clientName", "WEB").put("clientVersion", version).put("hl", "en").put("gl", "US")
                )
            )
            .put("query", query)
            .put("params", "EgIQAQ==")
            .toString()
        val response = post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false&key=${encode(key)}", body)
        val found = LinkedHashMap<String, OnlineVideo>()
        collectVideos(JSONObject(response), found)
        return found.values.take(30)
    }

    private val PIPED_HOSTS = listOf("https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee")
    private val INVIDIOUS_HOSTS = listOf("https://inv.nadeko.net", "https://yewtu.be")

    private fun searchPiped(query: String): List<OnlineVideo> {
        for (host in PIPED_HOSTS) {
            val result = runCatching {
                val items = JSONObject(get("$host/search?q=${encode(query)}&filter=videos")).optJSONArray("items") ?: return@runCatching emptyList<OnlineVideo>()
                buildList<OnlineVideo> {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val id = item.optString("url").substringAfter("v=", "").substringBefore("&")
                        val title = item.optString("title")
                        if (!ID_PATTERN.matches(id) || title.isBlank()) continue
                        add(OnlineVideo(id, title, item.optString("uploaderName"), thumbFor(id)))
                    }
                }
            }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun searchInvidious(query: String): List<OnlineVideo> {
        for (host in INVIDIOUS_HOSTS) {
            val result = runCatching {
                val items = JSONArray(get("$host/api/v1/search?q=${encode(query)}&type=video"))
                buildList<OnlineVideo> {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val id = item.optString("videoId")
                        val title = item.optString("title")
                        if (!ID_PATTERN.matches(id) || title.isBlank()) continue
                        add(OnlineVideo(id, title, item.optString("author"), thumbFor(id)))
                    }
                }
            }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    /** Finds the ytInitialData object in the page, whether it is a plain object or a quoted, escaped string. */
    private fun extractInitialData(html: String): JSONObject? {
        for (marker in listOf("ytInitialData = ", "ytInitialData\"] = ", "ytInitialData=")) {
            var from = 0
            while (true) {
                val at = html.indexOf(marker, from)
                if (at < 0) break
                from = at + marker.length
                val start = from
                if (start >= html.length) break
                val parsed = runCatching {
                    if (html[start] == '\'' || html[start] == '"') {
                        val quote = html[start]
                        var end = start + 1
                        while (end < html.length) {
                            if (html[end] == '\\') { end += 2; continue }
                            if (html[end] == quote) break
                            end += 1
                        }
                        JSONObject(unescapeJs(html.substring(start + 1, end)))
                    } else if (html[start] == '{') {
                        JSONObject(balanced(html, start))
                    } else null
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private fun balanced(text: String, start: Int): String {
        var depth = 0
        var inString = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i += 1 else if (c == '"') inString = false
            } else {
                if (c == '"') inString = true
                else if (c == '{') depth += 1
                else if (c == '}') {
                    depth -= 1
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i += 1
        }
        throw ApiException("Unexpected page format")
    }

    private fun unescapeJs(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\\' || i + 1 >= text.length) {
                out.append(c)
                i += 1
                continue
            }
            val next = text[i + 1]
            when {
                next == 'x' && i + 3 < text.length -> {
                    out.append(text.substring(i + 2, i + 4).toInt(16).toChar())
                    i += 4
                }
                next == 'u' && i + 5 < text.length -> {
                    out.append(text.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 6
                }
                next == 'n' -> { out.append('\n'); i += 2 }
                next == 't' -> { out.append('\t'); i += 2 }
                next == 'r' -> { out.append('\r'); i += 2 }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /** Text of a YouTube text object: simpleText, runs[] or content. */
    private fun textOf(node: JSONObject?): String {
        if (node == null) return ""
        node.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        node.optString("content").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return ""
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
    }

    private fun addVideo(out: MutableMap<String, OnlineVideo>, id: String, title: String, channel: String) {
        if (ID_PATTERN.matches(id) && title.isNotBlank() && !out.containsKey(id)) {
            out[id] = OnlineVideo(id, title.trim(), channel.trim(), thumbFor(id))
        }
    }

    private fun collectVideos(node: Any?, out: MutableMap<String, OnlineVideo>) {
        if (out.size >= 40) return
        when (node) {
            is JSONObject -> {
                // Classic renderers (desktop, mobile and compact lists).
                for (name in listOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "videoWithContextRenderer")) {
                    val renderer = node.optJSONObject(name) ?: continue
                    val title = textOf(renderer.optJSONObject("title")).ifEmpty { textOf(renderer.optJSONObject("headline")) }
                    val channel = textOf(renderer.optJSONObject("ownerText"))
                        .ifEmpty { textOf(renderer.optJSONObject("shortBylineText")) }
                        .ifEmpty { textOf(renderer.optJSONObject("longBylineText")) }
                    addVideo(out, renderer.optString("videoId"), title, channel)
                }
                // Newer "lockup" layout used by current YouTube pages.
                val lockup = node.optJSONObject("lockupViewModel")
                if (lockup != null) {
                    val type = lockup.optString("contentType")
                    if (type.isEmpty() || type.contains("VIDEO")) {
                        val meta = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                        val title = textOf(meta?.optJSONObject("title"))
                        val rows = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
                        val channel = textOf(rows?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)?.optJSONObject("text"))
                        addVideo(out, lockup.optString("contentId"), title, channel)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out)
            }
            is JSONArray -> for (index in 0 until node.length()) collectVideos(node.opt(index), out)
        }
    }

    // ---- MP3 download (mp3juice / thetacloud converter) ---------------------------------------------------------------

    /**
     * Only used as a fallback: the converter's auth step is tried without a key first (as the site does today), and
     * with this key if that fails. Replace it here if the service asks for a different one.
     */
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

    /** Runs one step of the download chain and names the step in any error, so the toast shows what really failed. */
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
    fun prepareMp3(videoId: String): String {
        val id = encode(videoId)
        setProgress(5)

        // 1. Auth. Tried without a key first, then with the saved one.
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

        // 2. Init.
        val init = step("Init") { JSONObject(get("$CONVERT_HOST/init?_=${clock()}", browser = true, headers = siteHeaders(token))) }
        val convertUrl = init.optString("convertURL")
        if (convertUrl.isBlank()) throw ApiException("Init failed: ${serverError(init) ?: "no convert address returned"}")
        setProgress(20)

        // 3. Convert.
        val convert = step("Convert") {
            JSONObject(get(joinQuery(convertUrl, "v=$id&f=mp3&_=${clock() / 1000}"), browser = true, headers = siteHeaders()))
        }
        var progressUrl = convert.optString("progressURL")
        var downloadUrl = convert.optString("downloadURL")
        var lastReply = convert
        setProgress(30)

        // 3b. Some replies point to one more address that holds the real links.
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

        // 4. Wait until the server says the file is ready. The converter's own 0..3 progress maps to 35..95 %.
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

    /** Streams the MP3, embeds its metadata (title, artist, cover) and saves it into Music/Music Rage. */
    fun saveMp3(context: Context, url: String, video: OnlineVideo) {
        val safe = video.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "download" }.take(120)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.mp3")
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.ARTIST, video.channel)
            put(MediaStore.Audio.Media.ALBUM, "Music Rage Downloads")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Music Rage")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException("Save failed: could not create the file")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", BROWSER_AGENT)
            connection.setRequestProperty("Referer", "$SITE/")
            connection.setRequestProperty("Origin", SITE)
            var bytes: ByteArray? = null
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw ApiException("Download failed: " + errorMessage(body, code))
                }
                val type = connection.contentType.orEmpty().lowercase(Locale.ROOT)
                if (type.contains("json") || type.contains("html") || type.startsWith("text")) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    throw ApiException("Download failed: the server sent text instead of audio (" + errorMessage(body, code) + ")")
                }
                val buffer = java.io.ByteArrayOutputStream()
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
                            // Real byte-level progress during the transfer; monotonic so it continues
                            // from where the converter phase ended instead of jumping back.
                            setProgressAtLeast(95 + ((received * 5) / contentLength).toInt().coerceAtMost(5))
                        }
                    }
                }
                val raw = buffer.toByteArray()
                if (raw.size < 100_000) throw ApiException("Download failed: the file was empty (${raw.size} bytes)")
                // Embed metadata before the file lands in MediaStore, so the library shows the real
                // title, artist and cover instead of "Unknown artist" with a grey tile.
                val tag = Id3Writer.buildTag(safe, video.channel, "Music Rage Downloads", downloadCover(video.thumb))
                bytes = if (tag != null) Id3Writer.prependTag(raw, tag) else raw
            } finally {
                connection.disconnect()
            }
            val out = bytes ?: throw ApiException("Download failed: nothing was received")
            resolver.openOutputStream(target)?.use { it.write(out) }
                ?: throw ApiException("Save failed: could not write the file")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    /** Downloads the video thumbnail (maxres, then the smaller ones) to embed as the cover. */
    private fun downloadCover(thumb: String): ByteArray? {
        for (url in listOf(thumb.replace("/mqdefault.jpg", "/maxresdefault.jpg"), thumb.replace("/mqdefault.jpg", "/hqdefault.jpg"), thumb)) {
            val data = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", BROWSER_AGENT)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) null else connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (data != null && data.size > 100) return data
        }
        return null
    }

    /** The video whose download is being prepared right now (drives the button label). */
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

    /** Like [setProgress] but never goes backwards: the transfer phase must continue from where the converter ended. */
    private fun setProgressAtLeast(value: Int) {
        val target = value.coerceIn(0, 100)
        mainHandler.post {
            if (target > downloadProgress) downloadProgress = target
        }
    }

    /** Starts a download that keeps going even if the person leaves the Online tab. */
    fun startDownload(context: Context, video: OnlineVideo) {
        if (preparingId != null) {
            say(context, "A download is already in progress")
            return
        }
        preparingId = video.id
        downloadProgress = 0
        say(context, "Preparing download…")
        downloadScope.launch {
            try {
                val link = prepareMp3(video.id)
                say(context, "Downloading…")
                saveMp3(context, link, video)
                setProgress(100)
                say(context, "Saved to Music/Music Rage")
            } catch (e: Exception) {
                say(context, e.message?.takeIf { it.isNotBlank() } ?: "Download failed")
            } finally {
                mainHandler.post {
                    preparingId = null
                    downloadProgress = -1
                }
            }
        }
    }
}
