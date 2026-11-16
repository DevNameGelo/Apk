package com.musicrage.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.musicrage.app.online.OnlinePlayer
import com.musicrage.app.online.OnlineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            // Light shimmer placeholder while the thumbnail loads.
            Box(Modifier.fillMaxSize().background(placeholderBrush(url.hashCode())))
        }
    }
}


@Composable
fun OnlineScreen(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OnlineVideo>?>(null) }
    var recommended by remember { mutableStateOf(emptyList<OnlineVideo>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val state = OnlinePlayer.state
    val current = state.video
    val history = remember(current?.id) { OnlineStore.history(activity) }

    LaunchedEffect(Unit) {
        if (recommended.isEmpty()) {
            try {
                recommended = withContext(Dispatchers.IO) { OnlineApi.recommended() }
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not load recommendations"
            }
        }
    }

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        scope.launch {
            loading = true
            error = null
            try {
                results = withContext(Dispatchers.IO) { OnlineApi.search(text) }
            } catch (e: Exception) {
                error = e.message ?: "Search failed"
            }
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        // ---- Header ----------------------------------------------------------------
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Online", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        }

        // ---- Search bar ----------------------------------------------------------------
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search music", color = Rage.TextDim) },
                    leadingIcon = { RIcon(R.drawable.ic_search, tint = Rage.TextDim, size = 20.dp) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconAction(R.drawable.ic_x, { query = "" }, tint = Rage.TextDim, size = 18.dp, contentDescription = "Clear")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Rage.SurfaceHigh,
                        unfocusedContainerColor = Rage.SurfaceHigh,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Rage.Accent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ---- Now-playing card (thumbnail like the local player, never the video) ----
        if (current != null) {
            item(key = "now-" + current.id) { OnlineNowPlaying(current, state.isPlaying) }
        }

        // ---- Hint for background permission ----
        if (!OnlinePlayer.inOverlay && !OnlinePlayer.overlayAllowed()) {
            item {
                Text(
                    "Tap here and allow \"Display over other apps\" so online music keeps playing when you switch tabs or close the app.",
                    color = Rage.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable { activity.requestOverlayForPlayer() }.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }

        // ---- Results / sections ----
        if (loading) item { OnlineSectionLabel("Searching…") }
        val message = error
        if (message != null) item { OnlineMessage(message) }
        val shown = results
        if (shown != null) {
            item { OnlineSectionLabel("Results") }
            if (shown.isEmpty() && !loading) item { OnlineMessage("No results — try another search.") }
            items(shown, key = { "r-" + it.id }) { video -> OnlineRow(video, current?.id == video.id) { OnlinePlayer.play(video, shown) } }
        } else {
            if (history.isNotEmpty()) {
                item { OnlineSectionLabel("Continue playing") }
                items(history, key = { "h-" + it.id }) { video -> OnlineRow(video, current?.id == video.id) { OnlinePlayer.play(video, history) } }
            }
            if (recommended.isNotEmpty()) {
                item { OnlineSectionLabel("Recommended") }
                items(recommended, key = { "p-" + it.id }) { video -> OnlineRow(video, current?.id == video.id) { OnlinePlayer.play(video, recommended) } }
            }
        }
    }
}

/** The unified player card: cover art, title, progress and controls — same style as the local mini player. */
@Composable
private fun OnlineNowPlaying(video: OnlineVideo, playing: Boolean) {
    val duration = OnlinePlayer.durationSec
    val position = OnlinePlayer.positionSec
    var drag by remember { mutableStateOf<Float?>(null) }
    val live = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val shown = drag ?: live

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Rage.Surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Square cover, exactly like the local player's tiles.
            NetImage(video.thumb, Modifier.size(64.dp), RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(video.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(video.channel, color = Rage.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        OnlineProgress(
            fraction = shown,
            enabled = duration > 0.0,
            onSeek = { drag = it },
            onSeekFinished = {
                OnlinePlayer.seekTo(duration * it)
                drag = null
            },
            position = position,
            duration = duration
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconAction(R.drawable.ic_skip_prev, { OnlinePlayer.previous() }, size = 24.dp, contentDescription = "Previous")
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Color.White).clickable { OnlinePlayer.toggle() },
                contentAlignment = Alignment.Center
            ) {
                RIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, tint = Color.Black, size = 24.dp)
            }
            Spacer(Modifier.weight(1f))
            IconAction(R.drawable.ic_skip_next, { OnlinePlayer.next() }, size = 24.dp, contentDescription = "Next")
            Spacer(Modifier.weight(2f))
            DownloadButton(video)
        }
    }
}

@Composable
private fun DownloadButton(video: OnlineVideo) {
    val preparing = OnlineApi.preparingId != null
    val progress = OnlineApi.downloadProgress
    // Read the context in the composable body; the click lambda is not a composable context.
    val context = LocalContext.current
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (preparing) Rage.SurfaceHigh else Rage.Accent)
            .clickable(enabled = !preparing) { OnlineApi.startDownload(context, video) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(R.drawable.ic_download, tint = if (preparing) Rage.TextDim else Color.Black, size = 17.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                preparing && progress >= 0 -> "$progress%"
                preparing -> "…"
                else -> "MP3"
            },
            color = if (preparing) Rage.TextDim else Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun OnlineProgress(fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit, onSeekFinished: (Float) -> Unit, position: Double, duration: Double) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        SeekBar(
            fraction = fraction,
            enabled = enabled,
            onSeek = onSeek,
            onSeekFinished = onSeekFinished
        )
        Row(Modifier.fillMaxWidth().padding(top = 0.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((position * 1000).toLong()), color = Rage.TextDim, fontSize = 11.sp)
            Text(formatDuration((duration * 1000).toLong()), color = Rage.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun OnlineSectionLabel(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun OnlineMessage(text: String) {
    Text(text, color = Rage.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

@Composable
private fun OnlineRow(video: OnlineVideo, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetImage(video.thumb, Modifier.width(104.dp).height(58.dp), RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                video.title,
                color = if (isCurrent) Rage.Accent else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    // Tiny "playing" bars instead of plain accent text.
                    Box(
                        Modifier.width(3.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(Rage.Accent)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(video.channel, color = Rage.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Compact controls shown above the bottom bar while online music plays and another tab is open. */
@Composable
fun OnlineMiniBar(onOpen: () -> Unit) {
    val state = OnlinePlayer.state
    val video = state.video ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(Rage.SurfaceHigh).clickable(onClick = onOpen).padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetImage(video.thumb, Modifier.size(width = 64.dp, height = 36.dp), RoundedCornerShape(5.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(video.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Online · " + video.channel, color = Rage.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, { OnlinePlayer.toggle() }, size = 22.dp, contentDescription = "Play or pause")
        IconAction(R.drawable.ic_skip_next, { OnlinePlayer.next() }, size = 22.dp, contentDescription = "Next")
    }
}
