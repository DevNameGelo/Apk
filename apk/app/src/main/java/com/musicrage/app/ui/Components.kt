@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicrage.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicrage.app.R
import com.musicrage.app.data.ArtworkLoader
import com.musicrage.app.data.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- Icons -------------------------------------------------------------------------------------------------------

@Composable
fun RIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 24.dp,
    contentDescription: String? = null
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        tint = tint,
        modifier = if (size != Dp.Unspecified) modifier.size(size) else modifier
    )
}

@Composable
fun IconAction(
    @DrawableRes id: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 24.dp,
    box: Dp = 44.dp,
    contentDescription: String? = null
) {
    Box(
        modifier
            .size(box)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center
    ) {
        RIcon(id, tint = tint, size = size, contentDescription = contentDescription)
    }
}

// ---- Artwork -----------------------------------------------------------------------------------------------------

enum class ArtSize(val px: Int) { Small(160), Medium(400), Large(720) }

private val placeholderPalette = listOf(
    listOf(Color(0xFF3B2A5E), Color(0xFF6A3FA0)),
    listOf(Color(0xFF1F3A5F), Color(0xFF3F74B5)),
    listOf(Color(0xFF5E2A45), Color(0xFFA03F76)),
    listOf(Color(0xFF1F4F4A), Color(0xFF3FA096)),
    listOf(Color(0xFF5E4A2A), Color(0xFFB5883F)),
    listOf(Color(0xFF4A2A2A), Color(0xFFB54F4F))
)

fun placeholderBrush(seed: Int): Brush {
    val colors = placeholderPalette[Math.floorMod(seed, placeholderPalette.size)]
    return Brush.linearGradient(colors)
}

@Composable
fun ArtImage(
    uri: Uri?,
    modifier: Modifier = Modifier,
    size: ArtSize = ArtSize.Small,
    shape: Shape = RoundedCornerShape(6.dp),
    @DrawableRes placeholderIcon: Int = R.drawable.ic_music
) {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(uri, size) { mutableStateOf<Bitmap?>(uri?.let { ArtworkLoader.peek(it, size.px) }) }
    LaunchedEffect(uri, size) {
        if (uri != null && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { ArtworkLoader.load(context, uri, size.px) }
        }
    }
    val brush = remember(uri) { placeholderBrush(uri?.toString().orEmpty().hashCode()) }
    Box(modifier.clip(shape).background(brush), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            RIcon(placeholderIcon, modifier = Modifier.fillMaxSize(0.4f), tint = Color.White.copy(alpha = 0.6f), size = Dp.Unspecified)
        }
    }
}

/**
 * Cover for playlists and other collections: one cover for a single song, otherwise a 2x2 grid
 * (top-left, top-right, bottom-left, bottom-right). With two or three covers they repeat to fill the grid.
 */
@Composable
fun CoverArt(
    sources: List<Uri>,
    modifier: Modifier = Modifier,
    size: ArtSize = ArtSize.Small,
    shape: Shape = RoundedCornerShape(6.dp),
    @DrawableRes fallbackIcon: Int = R.drawable.ic_list_music
) {
    when {
        sources.isEmpty() -> ArtImage(null, modifier, size, shape, fallbackIcon)
        sources.size == 1 -> ArtImage(sources[0], modifier, size, shape, fallbackIcon)
        else -> {
            val cells = List(4) { index -> sources[index % sources.size] }
            Column(modifier.clip(shape)) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        for (col in 0 until 2) {
                            ArtImage(cells[row * 2 + col], Modifier.weight(1f).fillMaxHeight(), size, RectangleShape)
                        }
                    }
                }
            }
        }
    }
}

/** Up to four covers: distinct albums first, then other songs so a small playlist still fills the grid. */
fun coverSources(tracks: List<AudioTrack>): List<Uri> {
    if (tracks.size <= 1) return tracks.map { it.uri }
    val chosen = ArrayList<AudioTrack>()
    tracks.distinctBy { it.albumId }.take(4).forEach { chosen.add(it) }
    if (chosen.size < 4) {
        for (track in tracks) {
            if (chosen.size >= 4) break
            if (chosen.none { it.key == track.key }) chosen.add(track)
        }
    }
    return chosen.map { it.uri }
}

@Composable
fun rememberArtColor(uri: Uri?): Color {
    val context = LocalContext.current.applicationContext
    var target by remember(uri) { mutableStateOf(Color(0xFF2A2433)) }
    LaunchedEffect(uri) {
        if (uri != null) {
            val color = withContext(Dispatchers.IO) { ArtworkLoader.dominantColor(context, uri) }
            if (color != null) target = Color(color)
        }
    }
    return animateColorAsState(target, tween(500), label = "artColor").value
}

// ---- Seek bar ----------------------------------------------------------------------------------------------------

@Composable
fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.25f)
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { offset ->
                    val value = (offset.x / widthPx).coerceIn(0f, 1f)
                    onSeek(value)
                    onSeekFinished(value)
                })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        last = (offset.x / widthPx).coerceIn(0f, 1f)
                        onSeek(last)
                    },
                    onDragEnd = { onSeekFinished(last) },
                    onDragCancel = { onSeekFinished(last) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / widthPx).coerceIn(0f, 1f)
                        onSeek(last)
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackHeight = 4.dp.toPx()
            val centerY = size.height / 2f
            val top = centerY - trackHeight / 2f
            val radius = trackHeight / 2f
            val value = fraction.coerceIn(0f, 1f)
            drawRoundRect(inactiveColor, Offset(0f, top), Size(size.width, trackHeight), androidx.compose.ui.geometry.CornerRadius(radius, radius))
            if (value > 0f) {
                drawRoundRect(activeColor, Offset(0f, top), Size(size.width * value, trackHeight), androidx.compose.ui.geometry.CornerRadius(radius, radius))
            }
            if (enabled) drawCircle(activeColor, 6.dp.toPx(), Offset(size.width * value, centerY))
        }
    }
}

// ---- Rows and small pieces ---------------------------------------------------------------------------------------

@Composable
fun TrackRow(
    track: AudioTrack,
    isCurrent: Boolean,
    favorite: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    selectMode: Boolean = false
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) Rage.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            Box(
                Modifier.padding(end = 12.dp).size(24.dp).clip(CircleShape)
                    .background(if (selected) Rage.Accent else Color.Transparent)
                    .border(2.dp, if (selected) Rage.Accent else Rage.TextDim, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) RIcon(R.drawable.ic_check, tint = Color.Black, size = 16.dp)
            }
        }
        ArtImage(track.uri, Modifier.size(52.dp), ArtSize.Small, RoundedCornerShape(5.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                track.title,
                color = if (isCurrent) Rage.Accent else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (favorite) {
                    RIcon(R.drawable.ic_heart_fill, tint = Rage.Accent, size = 13.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    track.artist,
                    color = Rage.TextDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconAction(R.drawable.ic_more, onMore, tint = Rage.TextDim, size = 20.dp, box = 44.dp, contentDescription = "More options")
    }
}

@Composable
fun CollectionRow(
    cover: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    round: Boolean = false,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(58.dp).clip(if (round) CircleShape else RoundedCornerShape(5.dp))) { cover() }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Rage.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Rage.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RIcon(R.drawable.ic_music, tint = Rage.TextDim, size = 40.dp)
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Rage.TextDim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onAction)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            ) { Text(action, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }
    }
}

@Composable
fun NameDialog(title: String, hint: String, initial: String, confirm: String = "Save", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Rage.SurfaceHigh,
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(hint) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Rage.Accent,
                    unfocusedBorderColor = Rage.Divider,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Rage.Accent,
                    cursorColor = Rage.Accent
                )
            )
        },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) { Text(confirm, color = Rage.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Rage.TextDim) } }
    )
}
