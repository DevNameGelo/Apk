@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicrage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.musicrage.app.R
import com.musicrage.app.playback.PlayerConnection
import com.musicrage.app.playback.PlayerSnapshot
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(
    connection: PlayerConnection,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClose: () -> Unit,
    onFloating: () -> Unit,
    showClose: Boolean = true
) {
    val snap = connection.snapshot
    val tint = rememberArtColor(snap.artworkUri)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Rage.Bg)
            .background(Brush.verticalGradient(listOf(tint, Rage.Bg), startY = 0f, endY = 1800f))
    ) {
        // Wide and short (a phone on its side, a tablet): art on the left, controls on the right.
        val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
        val short = maxHeight < 600.dp
        if (landscape) {
            Row(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    ArtImage(
                        uri = snap.artworkUri,
                        modifier = Modifier.fillMaxHeight(0.9f).aspectRatio(1f),
                        size = ArtSize.Large,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).fillMaxHeight().widthIn(max = 520.dp), verticalArrangement = Arrangement.Center) {
                    NowPlayingHeader(snap, showClose, onClose, onFloating)
                    NowPlayingControls(connection, snap, favorite, onToggleFavorite, compact = true)
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.TopCenter).widthIn(max = 520.dp).fillMaxSize()
                    .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NowPlayingHeader(snap, showClose, onClose, onFloating)
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = if (short) 6.dp else 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ArtImage(
                        uri = snap.artworkUri,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        size = ArtSize.Large,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                NowPlayingControls(connection, snap, favorite, onToggleFavorite, compact = short)
            }
        }
    }
}

@Composable
private fun NowPlayingHeader(snap: PlayerSnapshot, showClose: Boolean, onClose: () -> Unit, onFloating: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showClose) {
            IconAction(R.drawable.ic_chevron_down, onClose, size = 28.dp, contentDescription = "Close player")
        } else {
            Spacer(Modifier.size(44.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Now playing", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, letterSpacing = 1.2.sp)
            if (snap.album.isNotBlank()) {
                Text(snap.album, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconAction(R.drawable.ic_pip, onFloating, size = 22.dp, contentDescription = "Floating player")
    }
}

@Composable
private fun NowPlayingControls(
    connection: PlayerConnection,
    snap: PlayerSnapshot,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    compact: Boolean
) {
    var showSpeed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(snap.title.ifBlank { "Nothing playing" }, color = Color.White, fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(snap.artist, color = Color.White.copy(alpha = 0.7f), fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(
            if (favorite) R.drawable.ic_heart_fill else R.drawable.ic_heart,
            onToggleFavorite,
            tint = if (favorite) Rage.Accent else Color.White,
            size = 26.dp,
            contentDescription = if (favorite) "Remove from favorites" else "Add to favorites"
        )
    }

    Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
    ProgressSection(connection)
    Spacer(Modifier.height(4.dp))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconAction(
            R.drawable.ic_shuffle, { connection.toggleShuffle() },
            tint = if (snap.shuffle) Rage.Accent else Color.White, size = 24.dp, box = 48.dp, contentDescription = "Shuffle"
        )
        IconAction(R.drawable.ic_skip_prev, { connection.previous() }, size = 32.dp, box = 56.dp, contentDescription = "Previous")
        Box(
            Modifier.size(if (compact) 60.dp else 72.dp).clip(CircleShape).background(Color.White).clickable { connection.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            RIcon(
                if (snap.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                tint = Color.Black, size = 32.dp,
                contentDescription = if (snap.isPlaying) "Pause" else "Play"
            )
        }
        IconAction(R.drawable.ic_skip_next, { connection.next() }, size = 32.dp, box = 56.dp, contentDescription = "Next")
        IconAction(
            if (snap.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            { connection.cycleRepeat() },
            tint = if (snap.repeatMode != Player.REPEAT_MODE_OFF) Rage.Accent else Color.White,
            size = 24.dp, box = 48.dp, contentDescription = "Repeat"
        )
    }

    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))
                .clickable { showSpeed = true }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RIcon(R.drawable.ic_gauge, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(speedLabel(snap.speed), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    if (showSpeed) {
        SpeedSheet(speed = snap.speed, onSpeed = { connection.setSpeed(it) }, onDismiss = { showSpeed = false })
    }
}

@Composable
private fun ProgressSection(connection: PlayerConnection) {
    val duration = connection.snapshot.durationMs
    val position = connection.positionMs
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val liveFraction = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val shown = dragFraction ?: liveFraction
    Column(Modifier.fillMaxWidth()) {
        SeekBar(
            fraction = shown,
            enabled = duration > 0L,
            onSeek = { dragFraction = it },
            onSeekFinished = {
                connection.seekToFraction(it)
                dragFraction = null
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownPosition = if (dragFraction != null) (duration * shown).toLong() else position
            Text(formatDuration(shownPosition), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            Text(formatDuration(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

fun speedLabel(speed: Float): String {
    val text = String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "$text×"
}

private fun speedToFraction(speed: Float): Float = (ln(speed / 0.25f) / ln(40f)).coerceIn(0f, 1f)

private fun fractionToSpeed(fraction: Float): Float {
    val raw = 0.25 * 40.0.pow(fraction.toDouble())
    var snapped = (raw * 20.0).roundToInt() / 20f
    if (abs(snapped - 1f) < 0.04f) snapped = 1f
    return snapped.coerceIn(0.25f, 10f)
}

@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var local by remember { mutableFloatStateOf(speed) }
    LaunchedEffect(speed) { local = speed }

    fun apply(value: Float) {
        val next = value.coerceIn(0.25f, 10f)
        if (abs(next - local) >= 0.005f) {
            local = next
            onSpeed(next)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Rage.SurfaceHigh,
        contentColor = Color.White
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Playback speed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(speedLabel(local), color = Rage.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−") { apply(local - 0.05f) }
                SeekBar(
                    fraction = speedToFraction(local),
                    onSeek = { apply(fractionToSpeed(it)) },
                    onSeekFinished = { apply(fractionToSpeed(it)) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    activeColor = Rage.Accent
                )
                StepButton("+") { apply(local + 0.05f) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 5f, 10f).forEach { preset ->
                    FilterPill(label = speedLabel(preset), selected = abs(local - preset) < 0.005f) { apply(preset) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Pitch follows speed: slower sounds deeper, faster sounds higher.", color = Rage.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center) }
}

@Composable
fun MiniPlayer(
    connection: PlayerConnection,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpen: () -> Unit
) {
    val snap = connection.snapshot
    val tint = rememberArtColor(snap.artworkUri)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint)
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtImage(snap.artworkUri, Modifier.size(42.dp), ArtSize.Small, RoundedCornerShape(5.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(snap.title.ifBlank { "Nothing playing" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snap.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconAction(
                if (favorite) R.drawable.ic_heart_fill else R.drawable.ic_heart,
                onToggleFavorite,
                tint = if (favorite) Rage.Accent else Color.White,
                size = 22.dp, contentDescription = "Favorite"
            )
            IconAction(
                if (snap.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                { connection.togglePlayPause() },
                size = 24.dp, contentDescription = if (snap.isPlaying) "Pause" else "Play"
            )
        }
        MiniProgress(connection, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun MiniProgress(connection: PlayerConnection, modifier: Modifier) {
    val duration = connection.snapshot.durationMs
    val fraction = if (duration > 0L) (connection.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Color.White))
    }
}
