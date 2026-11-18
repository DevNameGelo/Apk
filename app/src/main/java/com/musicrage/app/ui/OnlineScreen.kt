package com.musicrage.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicrage.app.MainActivity
import com.musicrage.app.R
import com.musicrage.app.data.ArtworkLoader
import com.musicrage.app.data.OnlineApi
import com.musicrage.app.data.OnlineVideo
import com.musicrage.app.online.OnlineAdState
import com.musicrage.app.online.OnlineLoading
import com.musicrage.app.online.OnlinePlayer
import com.musicrage.app.online.OnlineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Shared bits ------------------------------------------------------------------------------------------------

@Composable
fun NetImage(url: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(if (url.isEmpty()) null else ArtworkLoader.peekUrl(url)) }
    LaunchedEffect(url) {
        if (url.isNotEmpty() && bitmap == null) bitmap = withContext(Dispatchers.IO) { ArtworkLoader.loadUrl(url) }
    }
    Box(modifier.clip(shape).background(Rage.SurfaceHigh), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) {
            Image(bitmap = loaded.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(placeholderBrush(url.hashCode())))
        }
    }
}

/** A dark, saturated tone taken from a web thumbnail; tints the now-playing card. */
private fun tintOf(bitmap: Bitmap): Int {
    val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    val hsv = FloatArray(3)
    for (y in 0 until small.height) {
        for (x in 0 until small.width) {
            val pixel = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(pixel, hsv)
            val weight = (hsv[1] * hsv[2]).toDouble() + 0.04
            r += android.graphics.Color.red(pixel) * weight
            g += android.graphics.Color.green(pixel) * weight
            b += android.graphics.Color.blue(pixel) * weight
            total += weight
        }
    }
    if (small !== bitmap) small.recycle()
    val average = android.graphics.Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    android.graphics.Color.colorToHSV(average, hsv)
    hsv[1] = hsv[1].coerceAtMost(0.75f)
    hsv[2] = hsv[2].coerceIn(0.26f, 0.5f)
    return android.graphics.Color.HSVToColor(hsv)
}

@Composable
private fun rememberUrlTint(url: String): Color {
    var tint by remember(url) { mutableStateOf(Color(0xFF2A2433)) }
    LaunchedEffect(url) {
        if (url.isNotEmpty()) {
            val value = withContext(Dispatchers.IO) { ArtworkLoader.loadUrl(url)?.let { runCatching { tintOf(it) }.getOrNull() } }
            if (value != null) tint = Color(value)
        }
    }
    return tint
}

@Composable
private fun EqualizerBars(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val first by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "first")
    val second by transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(430), RepeatMode.Reverse), label = "second")
    val third by transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(610), RepeatMode.Reverse), label = "third")
    Row(Modifier.height(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(first, second, third).forEach { level ->
            Box(
                Modifier.width(3.dp).height(12.dp * (if (active) level else 0.35f))
                    .clip(RoundedCornerShape(1.dp)).background(Rage.Accent)
            )
        }
    }
}

@Composable
private fun SkeletonRows(count: Int) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column {
        repeat(count) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(12.dp)).background(Rage.SurfaceHigh.copy(alpha = alpha)))
                Column(Modifier.padding(start = 12.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Rage.SurfaceHigh.copy(alpha = alpha)))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Rage.SurfaceHigh.copy(alpha = alpha)))
                }
            }
        }
    }
}

// ---- Screen -----------------------------------------------------------------------------------------------------

@Composable
fun OnlineScreen(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OnlineVideo>?>(null) }
    var recommended by remember { mutableStateOf(emptyList<OnlineVideo>()) }
    var searching by remember { mutableStateOf(false) }
    var loadingRecommended by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = OnlinePlayer.state
    val current = state.video
    var historyTick by remember { mutableIntStateOf(0) }
    val history = remember(current?.id, historyTick) { OnlineStore.history(activity) }
    // Search suggestions while typing (SoundCloud autocomplete: "kalapa" offers "kalapastanganan").
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var suggestionTick by remember { mutableIntStateOf(0) }

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) {
            results = null
            return
        }
        scope.launch {
            searching = true
            error = null
            try {
                results = withContext(Dispatchers.IO) {
                    val found = OnlineApi.search(text)
                    val dead = OnlineStore.dead(activity)
                    found.filter { it.id !in dead }.ifEmpty { found }
                }
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            searching = false
        }
    }

    LaunchedEffect(query) {
        suggestions = emptyList()
        val typed = query.trim()
        if (typed.length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        if (typed != query.trim()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) { runCatching { OnlineApi.suggest(typed) }.getOrDefault(emptyList()) }
        if (typed == query.trim() && results == null) suggestions = found
    }

    fun loadRecommended() {
        scope.launch {
            loadingRecommended = true
            try {
                recommended = withContext(Dispatchers.IO) { OnlineApi.recommended() }
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not load recommendations"
            }
            loadingRecommended = false
        }
    }

    LaunchedEffect(Unit) {
        if (recommended.isEmpty()) loadRecommended()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        // Header
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)) {
                Text("Online", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Search and stream music", color = Rage.TextDim, fontSize = 13.sp)
            }
        }

        // Search
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Songs, artists, albums", color = Rage.TextDim) },
                leadingIcon = { RIcon(R.drawable.ic_search, tint = Rage.TextDim, size = 20.dp) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconAction(R.drawable.ic_x, { query = ""; results = null; error = null; suggestions = emptyList() }, tint = Rage.TextDim, size = 18.dp, contentDescription = "Clear")
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(Rage.Accent)
                                    .clickable { suggestions = emptyList(); search() }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) { Text("Go", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Rage.Surface,
                    unfocusedContainerColor = Rage.Surface,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Rage.Accent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        // Search suggestions: tap one to complete the word and search it right away.
        if (results == null && suggestions.isNotEmpty()) {
            item(key = "suggestions") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Did you mean", color = Rage.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(suggestions, key = { "s-" + it }) { word ->
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(Rage.SurfaceHigh)
                                    .clickable {
                                        query = word
                                        suggestions = emptyList()
                                        search()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(word, color = Rage.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // Now playing
        if (current != null) {
            item(key = "now-playing") { OnlineNowPlaying(current, state.isPlaying) }
        }

        // Background permission
        if (!OnlinePlayer.inOverlay && !OnlinePlayer.overlayAllowed()) {
            item(key = "overlay-hint") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Rage.Surface)
                        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RIcon(R.drawable.ic_pip, tint = Rage.Accent, size = 22.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Keep playing in the background", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Allow \"Display over other apps\"", color = Rage.TextDim, fontSize = 12.sp)
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Rage.Accent)
                            .clickable { activity.requestOverlayForPlayer() }.padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text("Allow", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // Error
        val message = error
        if (message != null) {
            item(key = "error") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Rage.Danger.copy(alpha = 0.12f))
                        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(message, color = Rage.Danger, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 10.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                            .clickable { if (query.isNotBlank()) search() else loadRecommended() }.padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text("Retry", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        val shown = results
        if (searching) {
            item(key = "skeleton-search") {
                OnlineSectionTitle("Searching")
                SkeletonRows(5)
            }
        } else if (shown != null) {
            item(key = "results-title") { OnlineSectionTitle("Results") }
            if (shown.isEmpty()) {
                item(key = "no-results") {
                    Text("No results. Try another search.", color = Rage.TextDim, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }
            items(shown, key = { "r-" + it.id }) { video ->
                OnlineRow(video, current?.id == video.id, state.isPlaying) { OnlinePlayer.play(video, shown) }
            }
        } else {
            if (history.isNotEmpty()) {
                item(key = "history-title") { OnlineSectionTitle("Continue playing") }
                item(key = "history-row") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(history, key = { "h-" + it.id }) { video ->
                            OnlineShelfCard(
                                video = video,
                                isCurrent = current?.id == video.id,
                                onRemove = {
                                    OnlineStore.removeHistory(activity, video.id)
                                    historyTick += 1
                                }
                            ) { OnlinePlayer.play(video, history) }
                        }
                    }
                }
            }
            item(key = "recommended-title") { OnlineSectionTitle("Recommended") }
            if (recommended.isEmpty() && loadingRecommended) {
                item(key = "skeleton-recommended") { SkeletonRows(5) }
            }
            items(recommended, key = { "p-" + it.id }) { video ->
                OnlineRow(video, current?.id == video.id, state.isPlaying) { OnlinePlayer.play(video, recommended) }
            }
        }
    }
}

// ---- Now playing card -------------------------------------------------------------------------------------------

@Composable
private fun OnlineNowPlaying(video: OnlineVideo, playing: Boolean) {
    val duration = OnlinePlayer.durationSec
    val position = OnlinePlayer.positionSec
    val loading = OnlineLoading.active
    val adShowing = OnlineAdState.showing
    val tint = rememberUrlTint(video.thumb)
    var drag by remember { mutableStateOf<Float?>(null) }
    val live = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val shown = drag ?: live

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(tint, Rage.Surface)))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    NetImage(video.thumb, Modifier.size(80.dp), RoundedCornerShape(14.dp))
                    if (loading) {
                        Box(
                            Modifier.size(80.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(video.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            adShowing -> "Ads — please wait"
                            loading -> "Loading…"
                            else -> video.channel
                        },
                        color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SeekBar(
                fraction = shown,
                enabled = duration > 0.0,
                onSeek = { drag = it },
                onSeekFinished = {
                    OnlinePlayer.seekTo(duration * it)
                    drag = null
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(((if (drag != null) duration * shown else position) * 1000).toLong()), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                Text(formatDuration((duration * 1000).toLong()), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(R.drawable.ic_skip_prev, { OnlinePlayer.previous() }, size = 26.dp, contentDescription = "Previous")
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(Color.White).clickable { OnlinePlayer.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                    } else {
                        RIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, tint = Color.Black, size = 26.dp, contentDescription = if (playing) "Pause" else "Play")
                    }
                }
                Spacer(Modifier.width(6.dp))
                IconAction(R.drawable.ic_skip_next, { OnlinePlayer.next() }, size = 26.dp, contentDescription = "Next")
                Spacer(Modifier.weight(1f))
                DownloadButton(video)
            }
        }
    }
}

@Composable
private fun DownloadButton(video: OnlineVideo) {
    val context = LocalContext.current
    val version = OnlineApi.downloadVersion
    var downloaded by remember(video.id, version) { mutableStateOf(false) }
    LaunchedEffect(video.id, version) {
        downloaded = withContext(Dispatchers.IO) { OnlineApi.isDownloaded(context, video) }
    }
    val mine = OnlineApi.preparingId == video.id
    val busy = OnlineApi.preparingId != null
    val progress = OnlineApi.downloadProgress
    val background = when {
        downloaded && !mine -> Color.White.copy(alpha = 0.14f)
        busy -> Color.White.copy(alpha = 0.14f)
        else -> Rage.Accent
    }
    val foreground = if (downloaded || busy) Color.White else Color.Black
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(background)
            .clickable(enabled = !busy) { OnlineApi.startDownload(context, video) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(if (downloaded && !mine) R.drawable.ic_check else R.drawable.ic_download, tint = foreground, size = 17.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            when {
                mine && progress >= 0 -> "$progress%"
                mine -> "…"
                downloaded -> "Saved"
                else -> "MP3"
            },
            color = foreground, fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
    }
}

// ---- Lists ------------------------------------------------------------------------------------------------------

@Composable
private fun OnlineSectionTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun OnlineShelfCard(video: OnlineVideo, isCurrent: Boolean, onRemove: () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(156.dp).clickable(onClick = onClick)) {
        Box {
            NetImage(video.thumb, Modifier.width(156.dp).height(88.dp), RoundedCornerShape(14.dp))
            // Remove from "Continue playing". The song comes back the next time it is played.
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) { RIcon(R.drawable.ic_x, tint = Color.White, size = 14.dp, contentDescription = "Remove") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            video.title,
            color = if (isCurrent) Rage.Accent else Color.White,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(video.channel, color = Rage.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OnlineRow(video: OnlineVideo, isCurrent: Boolean, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetImage(video.thumb, Modifier.width(120.dp).height(68.dp), RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                video.title,
                color = if (isCurrent) Rage.Accent else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    EqualizerBars(playing)
                    Spacer(Modifier.width(6.dp))
                }
                Text(video.channel, color = Rage.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- Mini bar ---------------------------------------------------------------------------------------------------

/** Compact controls shown above the bottom bar while online music plays and another tab is open. */
@Composable
fun OnlineMiniBar(onOpen: () -> Unit) {
    val state = OnlinePlayer.state
    val video = state.video ?: return
    val loading = OnlineLoading.active
    val tint = rememberUrlTint(video.thumb)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(tint).clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NetImage(video.thumb, Modifier.size(42.dp), RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(video.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (loading) "Loading…" else "Online · " + video.channel,
                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (loading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(end = 14.dp).size(20.dp))
            } else {
                IconAction(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, { OnlinePlayer.toggle() }, size = 24.dp, contentDescription = "Play or pause")
            }
            IconAction(R.drawable.ic_skip_next, { OnlinePlayer.next() }, size = 24.dp, contentDescription = "Next")
        }
        val duration = OnlinePlayer.durationSec
        val fraction = if (duration > 0.0) (OnlinePlayer.positionSec / duration).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Color.White))
        }
    }
}
