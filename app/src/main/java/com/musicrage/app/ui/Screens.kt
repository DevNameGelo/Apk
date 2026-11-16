@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicrage.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicrage.app.MainActivity
import com.musicrage.app.R
import com.musicrage.app.data.AudioTrack
import com.musicrage.app.data.MusicPlaylist
import com.musicrage.app.data.MusicPreferences
import java.util.Calendar

@Immutable
class LibraryData(
    val tracks: List<AudioTrack>,
    val albums: List<AlbumGroup>,
    val artists: List<ArtistGroup>,
    val playlists: List<MusicPlaylist>,
    val favorites: Set<String>
) {
    private val byKey: Map<String, AudioTrack> = tracks.associateBy { it.key }
    val liked: List<AudioTrack> = tracks.filter { it.key in favorites }
    fun playlistTracks(playlist: MusicPlaylist): List<AudioTrack> = playlist.songKeys.mapNotNull { byKey[it] }
}

@Immutable
class TrackActions(
    val currentId: String?,
    val favorites: Set<String>,
    val selected: Set<String>,
    val selectMode: Boolean,
    val onPlay: (List<AudioTrack>, Int) -> Unit,
    val onLongPress: (AudioTrack) -> Unit,
    val onMore: (AudioTrack) -> Unit
)

private fun LazyListScope.trackItems(tracks: List<AudioTrack>, a: TrackActions) {
    items(tracks, key = { it.key }) { track ->
        TrackRow(
            track = track,
            isCurrent = track.key == a.currentId,
            favorite = track.key in a.favorites,
            selected = track.key in a.selected,
            selectMode = a.selectMode,
            onClick = { a.onPlay(tracks, tracks.indexOf(track)) },
            onLongClick = { a.onLongPress(track) },
            onMore = { a.onMore(track) }
        )
    }
}

@Composable
private fun ScreenHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

private fun playlistSubtitle(count: Int) = if (count == 1) "Playlist · 1 song" else "Playlist · $count songs"

@Composable
private fun LikedCover(modifier: Modifier = Modifier) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF5B2BD1), Color(0xFFB65BFF)))),
        contentAlignment = Alignment.Center
    ) { RIcon(R.drawable.ic_heart_fill, modifier = Modifier.fillMaxSize(0.42f), size = androidx.compose.ui.unit.Dp.Unspecified) }
}

// ---- Home --------------------------------------------------------------------------------------------------------

private class QuickTile(val title: String, val cover: @Composable () -> Unit, val target: DetailTarget)

@Composable
fun HomeScreen(
    lib: LibraryData,
    actions: TrackActions,
    permissionGranted: Boolean,
    loaded: Boolean,
    onOpen: (DetailTarget) -> Unit,
    onSettings: () -> Unit,
    onGrant: () -> Unit,
    onRescan: () -> Unit
) {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val recentAlbums = remember(lib.albums) { lib.albums.sortedByDescending { album -> album.tracks.maxOf { it.dateAdded } } }
    val tiles = remember(lib.playlists, recentAlbums, lib.tracks) {
        val list = ArrayList<QuickTile>()
        list += QuickTile("Liked Songs", { LikedCover(Modifier.fillMaxSize()) }, DetailTarget.Liked)
        lib.playlists.take(3).forEach { playlist ->
            val sources = coverSources(lib.playlistTracks(playlist))
            list += QuickTile(playlist.name, { CoverArt(sources, Modifier.fillMaxSize(), ArtSize.Small, RoundedCornerShape(0.dp)) }, DetailTarget.Playlist(playlist.id))
        }
        recentAlbums.forEach { album ->
            if (list.size < 6) {
                list += QuickTile(album.name, { ArtImage(album.tracks.first().uri, Modifier.fillMaxSize(), ArtSize.Small, RoundedCornerShape(0.dp)) }, DetailTarget.Album(album.key))
            }
        }
        list
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ScreenHeader(greeting) {
                IconAction(R.drawable.ic_settings, onSettings, contentDescription = "Settings")
            }
        }
        if (!permissionGranted) {
            item { EmptyState("Allow access to your music", "Music Rage needs permission to read audio files on this device.", "Allow access", onGrant) }
        } else if (lib.tracks.isEmpty()) {
            if (loaded) item { EmptyState("No music found", "Add audio files to this device, then rescan.", "Rescan", onRescan) }
        } else {
            item {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { tile ->
                                Row(
                                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp))
                                        .background(Rage.SurfaceHigh).clickable { onOpen(tile.target) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(56.dp)) { tile.cover() }
                                    Text(
                                        tile.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp)
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item { SectionTitle("Recently added", Modifier.padding(top = 12.dp)) }
            item {
                val recent = lib.tracks.take(15)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { it.key }) { track ->
                        ShelfCard(track.uri, track.title, track.artist, false) { actions.onPlay(recent, recent.indexOf(track)) }
                    }
                }
            }
            if (lib.albums.isNotEmpty()) {
                item { SectionTitle("Albums", Modifier.padding(top = 12.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(recentAlbums.take(20), key = { it.key }) { album ->
                            ShelfCard(album.tracks.first().uri, album.name, album.artist, false) { onOpen(DetailTarget.Album(album.key)) }
                        }
                    }
                }
            }
            if (lib.artists.isNotEmpty()) {
                item { SectionTitle("Artists", Modifier.padding(top = 12.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(lib.artists.take(20), key = { it.name }) { artist ->
                            ShelfCard(artist.tracks.first().uri, artist.name, "Artist", true) { onOpen(DetailTarget.Artist(artist.name)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(art: Uri, title: String, subtitle: String, round: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        ArtImage(art, Modifier.size(140.dp), ArtSize.Medium, if (round) CircleShape else RoundedCornerShape(6.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Rage.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Search ------------------------------------------------------------------------------------------------------

private val browseColors = listOf(
    Color(0xFF8D3BCB), Color(0xFF2D6CDF), Color(0xFFC2305F), Color(0xFF1E8E7A),
    Color(0xFFC77D1B), Color(0xFF5F3DC4), Color(0xFFB0413E), Color(0xFF2B8A3E)
)

@Composable
fun SearchScreen(lib: LibraryData, actions: TrackActions, onOpen: (DetailTarget) -> Unit) {
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val songs = remember(lib.tracks, trimmed) {
        if (trimmed.isEmpty()) emptyList() else lib.tracks.filter {
            it.title.contains(trimmed, true) || it.artist.contains(trimmed, true) || it.album.contains(trimmed, true)
        }
    }
    val albums = remember(lib.albums, trimmed) { if (trimmed.isEmpty()) emptyList() else lib.albums.filter { it.name.contains(trimmed, true) } }
    val artists = remember(lib.artists, trimmed) { if (trimmed.isEmpty()) emptyList() else lib.artists.filter { it.name.contains(trimmed, true) } }
    val playlists = remember(lib.playlists, trimmed) { if (trimmed.isEmpty()) emptyList() else lib.playlists.filter { it.name.contains(trimmed, true) } }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = imeBottom + 16.dp)) {
        item { ScreenHeader("Search") }
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Songs, artists, albums, playlists", color = Color(0xFF6E6E78)) },
                leadingIcon = { RIcon(R.drawable.ic_search, tint = Color(0xFF3A3A44), size = 22.dp) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconAction(R.drawable.ic_x, { query = "" }, tint = Color(0xFF3A3A44), size = 20.dp, box = 40.dp, contentDescription = "Clear")
                },
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    cursorColor = Color.Black,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (trimmed.isEmpty()) {
            if (lib.albums.isNotEmpty() || lib.artists.isNotEmpty()) {
                item { SectionTitle("Browse", Modifier.padding(top = 8.dp)) }
                val entries = buildList<Triple<String, Uri, DetailTarget>> {
                    lib.artists.forEach { add(Triple(it.name, it.tracks.first().uri, DetailTarget.Artist(it.name))) }
                    lib.albums.forEach { add(Triple(it.name, it.tracks.first().uri, DetailTarget.Album(it.key))) }
                }
                items(entries.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { (name, art, target) ->
                            BrowseTile(name, art, browseColors[Math.floorMod(name.hashCode(), browseColors.size)], Modifier.weight(1f)) { onOpen(target) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else if (songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()) {
            item { EmptyState("No results", "Nothing matches \"$trimmed\".") }
        } else {
            if (songs.isNotEmpty()) {
                item { SectionTitle("Songs") }
                trackItems(songs.take(40), actions)
            }
            if (artists.isNotEmpty()) {
                item { SectionTitle("Artists", Modifier.padding(top = 8.dp)) }
                items(artists.take(10), key = { "artist-" + it.name }) { artist ->
                    CollectionRow({ ArtImage(artist.tracks.first().uri, Modifier.fillMaxSize(), ArtSize.Small, CircleShape) }, artist.name, "Artist", { onOpen(DetailTarget.Artist(artist.name)) }, round = true)
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Albums", Modifier.padding(top = 8.dp)) }
                items(albums.take(10), key = { "album-" + it.key }) { album ->
                    CollectionRow({ ArtImage(album.tracks.first().uri, Modifier.fillMaxSize(), ArtSize.Small) }, album.name, "Album · ${album.artist}", { onOpen(DetailTarget.Album(album.key)) })
                }
            }
            if (playlists.isNotEmpty()) {
                item { SectionTitle("Playlists", Modifier.padding(top = 8.dp)) }
                items(playlists, key = { "playlist-" + it.id }) { playlist ->
                    val sources = coverSources(lib.playlistTracks(playlist))
                    CollectionRow({ CoverArt(sources, Modifier.fillMaxSize()) }, playlist.name, playlistSubtitle(playlist.songKeys.size), { onOpen(DetailTarget.Playlist(playlist.id)) })
                }
            }
        }
    }
}

@Composable
private fun BrowseTile(name: String, art: Uri, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(92.dp).clip(RoundedCornerShape(8.dp)).background(color).clickable(onClick = onClick)) {
        Text(
            name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp).fillMaxWidth(0.62f)
        )
        ArtImage(
            art,
            Modifier.align(Alignment.BottomEnd).size(60.dp).padding(0.dp).offset(x = 10.dp, y = 8.dp).rotate(24f),
            ArtSize.Small, RoundedCornerShape(4.dp)
        )
    }
}

// ---- Library -----------------------------------------------------------------------------------------------------

@Composable
fun LibraryScreen(
    lib: LibraryData,
    actions: TrackActions,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    onOpen: (DetailTarget) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    var alphabetical by remember { mutableStateOf(false) }
    val songs = remember(lib.tracks, alphabetical) { if (alphabetical) lib.tracks.sortedBy { it.title.lowercase() } else lib.tracks }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ScreenHeader("Your Library") {
                IconAction(R.drawable.ic_plus, onCreatePlaylist, size = 26.dp, contentDescription = "Create playlist")
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.values().forEach { option ->
                    FilterPill(option.name, filter == option) { onFilter(option) }
                }
            }
        }
        when (filter) {
            LibraryFilter.Playlists -> {
                item {
                    CollectionRow(
                        cover = { LikedCover(Modifier.fillMaxSize()) },
                        title = "Liked Songs",
                        subtitle = playlistSubtitle(lib.liked.size),
                        onClick = { onOpen(DetailTarget.Liked) }
                    )
                }
                items(lib.playlists, key = { it.id }) { playlist ->
                    val sources = coverSources(lib.playlistTracks(playlist))
                    CollectionRow({ CoverArt(sources, Modifier.fillMaxSize()) }, playlist.name, playlistSubtitle(playlist.songKeys.size), { onOpen(DetailTarget.Playlist(playlist.id)) })
                }
                if (lib.playlists.isEmpty()) {
                    item { EmptyState("Create your first playlist", "Playlists you make appear here.", "Create playlist", onCreatePlaylist) }
                }
            }
            LibraryFilter.Albums -> {
                if (lib.albums.isEmpty()) item { EmptyState("No albums", "Albums appear once your music is scanned.") }
                items(lib.albums, key = { it.key }) { album ->
                    CollectionRow({ ArtImage(album.tracks.first().uri, Modifier.fillMaxSize(), ArtSize.Small) }, album.name, "Album · ${album.artist}", { onOpen(DetailTarget.Album(album.key)) })
                }
            }
            LibraryFilter.Artists -> {
                if (lib.artists.isEmpty()) item { EmptyState("No artists", "Artists appear once your music is scanned.") }
                items(lib.artists, key = { it.name }) { artist ->
                    CollectionRow(
                        { ArtImage(artist.tracks.first().uri, Modifier.fillMaxSize(), ArtSize.Small, CircleShape) },
                        artist.name, "${artist.tracks.size} songs", { onOpen(DetailTarget.Artist(artist.name)) }, round = true
                    )
                }
            }
            LibraryFilter.Songs -> {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${songs.size} songs", color = Rage.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).clickable { alphabetical = !alphabetical }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RIcon(R.drawable.ic_sort, tint = Rage.TextDim, size = 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(if (alphabetical) "A–Z" else "Recently added", color = Rage.TextDim, fontSize = 13.sp)
                        }
                    }
                }
                if (songs.isEmpty()) item { EmptyState("No songs", "Songs appear once your music is scanned.") }
                trackItems(songs, actions)
            }
        }
    }
}

// ---- Detail ------------------------------------------------------------------------------------------------------

@Composable
fun DetailScreen(
    target: DetailTarget,
    lib: LibraryData,
    actions: TrackActions,
    onBack: () -> Unit,
    onPlayAll: (List<AudioTrack>, Boolean) -> Unit,
    onRenamePlaylist: (MusicPlaylist) -> Unit,
    onDeletePlaylist: (MusicPlaylist) -> Unit
) {
    var title = ""
    var subtitle = ""
    var tracks: List<AudioTrack> = emptyList()
    var playlist: MusicPlaylist? = null
    when (target) {
        is DetailTarget.Liked -> {
            title = "Liked Songs"; tracks = lib.liked; subtitle = playlistSubtitle(tracks.size)
        }
        is DetailTarget.Playlist -> {
            playlist = lib.playlists.firstOrNull { it.id == target.id }
            title = playlist?.name ?: "Playlist"
            tracks = playlist?.let { lib.playlistTracks(it) } ?: emptyList()
            subtitle = playlistSubtitle(tracks.size)
        }
        is DetailTarget.Album -> {
            val album = lib.albums.firstOrNull { it.key == target.key }
            title = album?.name ?: "Album"
            tracks = album?.tracks ?: emptyList()
            subtitle = "Album · ${album?.artist.orEmpty()} · ${tracks.size} songs"
        }
        is DetailTarget.Artist -> {
            title = target.name
            tracks = lib.artists.firstOrNull { it.name == target.name }?.tracks ?: emptyList()
            subtitle = "Artist · ${tracks.size} songs"
        }
    }
    val pl: MusicPlaylist? = playlist
    val sources = remember(tracks) { coverSources(tracks) }
    var menu by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF3A2A55), Rage.Bg))).statusBarsPadding().padding(bottom = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_arrow_left, onBack, contentDescription = "Back")
                    Spacer(Modifier.weight(1f))
                    if (pl != null) IconAction(R.drawable.ic_more, { menu = true }, contentDescription = "Playlist options")
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val isLiked = target is DetailTarget.Liked
                    if (isLiked) LikedCover(Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)))
                    else CoverArt(sources, Modifier.size(200.dp), ArtSize.Large, RoundedCornerShape(8.dp), if (target is DetailTarget.Artist) R.drawable.ic_user else R.drawable.ic_list_music)
                }
                Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                Text(subtitle, color = Rage.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(R.drawable.ic_shuffle, { onPlayAll(tracks, true) }, size = 24.dp, box = 48.dp, contentDescription = "Shuffle play")
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Rage.Accent).clickable(enabled = tracks.isNotEmpty()) { onPlayAll(tracks, false) },
                        contentAlignment = Alignment.Center
                    ) { RIcon(R.drawable.ic_play, tint = Color.Black, size = 28.dp, contentDescription = "Play") }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        if (tracks.isEmpty()) {
            item {
                EmptyState(
                    if (target is DetailTarget.Liked) "No liked songs yet" else "Nothing here yet",
                    if (target is DetailTarget.Liked) "Tap the heart on a song to add it." else "Songs you add will show up here."
                )
            }
        }
        trackItems(tracks, actions)
    }

    if (menu && pl != null) {
        val current: MusicPlaylist = pl
        ActionSheet(onDismiss = { menu = false }, header = { Text(current.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }) {
            ActionRow(R.drawable.ic_pencil, "Rename playlist") { menu = false; onRenamePlaylist(current) }
            ActionRow(R.drawable.ic_trash, "Delete playlist", Rage.Danger) { menu = false; onDeletePlaylist(current) }
        }
    }
}

// ---- Sheets ------------------------------------------------------------------------------------------------------

@Composable
fun ActionSheet(onDismiss: () -> Unit, header: @Composable () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Rage.SurfaceHigh, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { header() }
            content()
        }
    }
}

@Composable
fun ActionRow(icon: Int, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(icon, tint = tint, size = 22.dp)
        Spacer(Modifier.width(18.dp))
        Text(label, color = tint, fontSize = 16.sp)
    }
}

@Composable
fun TrackActionsSheet(
    track: AudioTrack,
    favorite: Boolean,
    inPlaylist: Boolean,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ActionSheet(
        onDismiss = onDismiss,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtImage(track.uri, Modifier.size(52.dp), ArtSize.Small, RoundedCornerShape(5.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = Rage.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    ) {
        ActionRow(if (favorite) R.drawable.ic_heart_fill else R.drawable.ic_heart, if (favorite) "Remove from Liked Songs" else "Add to Liked Songs", if (favorite) Rage.Accent else Color.White, onFavorite)
        ActionRow(R.drawable.ic_list_plus, "Add to playlist", onClick = onAddToPlaylist)
        if (inPlaylist) ActionRow(R.drawable.ic_x, "Remove from this playlist", onClick = onRemoveFromPlaylist)
        ActionRow(R.drawable.ic_check, "Select", onClick = onSelect)
        ActionRow(R.drawable.ic_pencil, "Rename file", onClick = onRename)
        ActionRow(R.drawable.ic_trash, "Delete from device", Rage.Danger, onDelete)
    }
}

@Composable
fun PlaylistPickerSheet(playlists: List<MusicPlaylist>, keys: Set<String>, onPick: (MusicPlaylist) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    ActionSheet(onDismiss = onDismiss, header = { Text("Add to playlist", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }) {
        ActionRow(R.drawable.ic_plus, "New playlist", Rage.Accent, onNew)
        playlists.forEach { playlist ->
            val existing = playlist.songKeys.toSet()
            val already = keys.count { it in existing }
            val note = when {
                already == 0 -> null
                already == keys.size -> if (keys.size == 1) "Already added" else "All already added"
                else -> "$already already added"
            }
            Row(
                Modifier.fillMaxWidth().clickable { onPick(playlist) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RIcon(R.drawable.ic_list_music, size = 22.dp)
                Column(Modifier.padding(start = 18.dp)) {
                    Text(playlist.name, color = Color.White, fontSize = 16.sp)
                    if (note != null) Text(note, color = if (already == keys.size) Rage.Accent else Rage.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---- Songs (every file on the device, all folders) -----------------------------------------------------------

@Composable
fun SongsScreen(lib: LibraryData, actions: TrackActions, onStartSelect: () -> Unit, onSelectAll: (List<AudioTrack>) -> Unit) {
    var folder by remember { mutableStateOf<String?>(null) }
    var alphabetical by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val folders = remember(lib.tracks) {
        lib.tracks.groupBy { it.folder }.entries.sortedByDescending { it.value.size }.map { it.key to it.value.size }
    }
    val selectedFolder = folder
    val list = remember(lib.tracks, selectedFolder, alphabetical, query) {
        var base = if (selectedFolder == null) lib.tracks else lib.tracks.filter { it.folder == selectedFolder }
        val q = query.trim()
        if (q.isNotEmpty()) base = base.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        if (alphabetical) base.sortedBy { it.title.lowercase() } else base
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            ScreenHeader("Songs") {
                if (!actions.selectMode) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Rage.SurfaceHigh).clickable(onClick = onStartSelect)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("Select", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Filter songs", color = Rage.TextDim) },
                leadingIcon = { RIcon(R.drawable.ic_search, tint = Rage.TextDim, size = 20.dp) },
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Rage.SurfaceHigh,
                    unfocusedContainerColor = Rage.SurfaceHigh,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Rage.Accent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (folders.size > 1) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterPill("All · ${lib.tracks.size}", selectedFolder == null) { folder = null } }
                    items(folders, key = { it.first }) { entry ->
                        FilterPill("${entry.first} · ${entry.second}", selectedFolder == entry.first) { folder = entry.first }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${list.size} songs", color = Rage.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { onSelectAll(list) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RIcon(R.drawable.ic_check, tint = Rage.TextDim, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Select all", color = Rage.TextDim, fontSize = 13.sp)
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { alphabetical = !alphabetical }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RIcon(R.drawable.ic_sort, tint = Rage.TextDim, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (alphabetical) "A–Z" else "Recent", color = Rage.TextDim, fontSize = 13.sp)
                }
            }
        }
        if (list.isEmpty()) item { EmptyState("No songs", if (lib.tracks.isEmpty()) "Songs appear once your music is scanned." else "Nothing matches this filter.") }
        trackItems(list, actions)
    }
}

// ---- Settings ----------------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(activity: MainActivity, songCount: Int, onBack: () -> Unit, onRescan: () -> Unit) {
    val tick = activity.settingsVersion.intValue
    val autoRecover = remember(tick) { MusicPreferences.autoRecover(activity) }
    val playOver = remember(tick) { MusicPreferences.playOverOthers(activity) }
    val floating = remember(tick) { MusicPreferences.floatingEnabled(activity) }
    val battery = remember(tick) { activity.isBatteryUnrestricted() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(R.drawable.ic_arrow_left, onBack, contentDescription = "Back")
                Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
            }
        }
        item { SettingsGroup("Playback") }
        item {
            SettingToggle("Recover playback", "Restart the music if Android or a game closes the app.", autoRecover) {
                MusicPreferences.setAutoRecover(activity, it)
                activity.settingsVersion.intValue += 1
            }
        }
        item {
            SettingToggle("Play alongside other apps", "Keep playing when another app uses audio.", playOver) {
                MusicPreferences.setPlayOverOthers(activity, it)
                activity.settingsVersion.intValue += 1
                activity.applyServiceSettings()
            }
        }
        item {
            SettingAction(
                R.drawable.ic_zap, "Unrestricted battery usage",
                if (battery) "Allowed. Android won't limit background playback." else "Stops Android from limiting playback in the background.",
                if (battery) null else "Allow"
            ) { activity.requestBatteryUnrestricted() }
        }
        item { SettingsGroup("Floating player") }
        item {
            SettingToggle("Floating player", "Show controls over other apps and games. Needs \"Display over other apps\".", floating) {
                activity.setFloatingPlayer(it)
            }
        }
        item { SettingsGroup("Library") }
        item { SettingAction(R.drawable.ic_refresh, "Rescan music", "$songCount songs found", "Rescan") { onRescan() } }
        item { SettingAction(R.drawable.ic_download, "Back up playlists and favorites", "Save to a file.", "Save") { activity.startBackupExport() } }
        item { SettingAction(R.drawable.ic_upload, "Restore from backup", "Merge a saved backup into this app.", "Restore") { activity.startBackupImport() } }
        item { SettingsGroup("About") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Music Rage 1.2.6", color = Color.White, fontSize = 15.sp)
                Text("Offline music player", color = Rage.TextDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String) {
    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp))
}

@Composable
private fun SettingToggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Rage.TextDim, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Rage.Accent,
                uncheckedThumbColor = Rage.TextDim,
                uncheckedTrackColor = Rage.SurfaceHigh,
                uncheckedBorderColor = Rage.Divider
            )
        )
    }
}

@Composable
private fun SettingAction(icon: Int, title: String, body: String, action: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RIcon(icon, tint = Rage.TextDim, size = 22.dp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Rage.TextDim, fontSize = 13.sp)
        }
        if (action != null) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text(action, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
