@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.musicrage.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicrage.app.online.OnlinePlayer
import com.musicrage.app.MainActivity
import com.musicrage.app.R
import com.musicrage.app.data.AudioTrack
import com.musicrage.app.data.MusicLibrary
import com.musicrage.app.data.MusicPlaylist
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.data.LibraryObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun MusicRageApp(activity: MainActivity) {
    val connection = activity.connection
    val snap = connection.snapshot
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var loaded by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(MusicPreferences.favorites(activity)) }
    var playlists by remember { mutableStateOf(MusicPreferences.playlists(activity)) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var selectMode by remember { mutableStateOf(false) }

    var tab by remember { mutableStateOf(MainTab.Home) }
    var filter by remember { mutableStateOf(LibraryFilter.Playlists) }
    var showSettings by remember { mutableStateOf(false) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    val detailStack = remember { mutableStateListOf<DetailTarget>() }

    var actionTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var pickerKeys by remember { mutableStateOf<Set<String>?>(null) }
    var pendingAddKeys by remember { mutableStateOf<Set<String>?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var renameTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var renamePlaylist by remember { mutableStateOf<MusicPlaylist?>(null) }
    var deleteTracks by remember { mutableStateOf<List<AudioTrack>?>(null) }
    var deletePlaylist by remember { mutableStateOf<MusicPlaylist?>(null) }

    // First launch only: a short welcome before anything else is shown.
    var showWelcome by remember { mutableStateOf(!MusicPreferences.welcomed(activity)) }

    val permissionGranted = activity.audioPermissionState.value

    // MediaStore observer: refreshes the list the moment a file is added or deleted anywhere.
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) LibraryObserver.start(activity)
    }
    val liveScanVersion = activity.scanVersion.intValue + LibraryObserver.version.longValue.toInt()

    LaunchedEffect(activity.openFullPlayerState.value) {
        if (activity.openFullPlayerState.value) {
            nowPlayingOpen = true
            activity.openFullPlayerState.value = false
        }
    }

    LaunchedEffect(activity.openOnlineState.value) {
        if (activity.openOnlineState.value) {
            tab = MainTab.Online
            detailStack.clear()
            showSettings = false
            activity.openOnlineState.value = false
        }
    }

    // The online video never appears in the UI any more (the tab shows its cover instead), so the WebView
    // stays parked permanently. Playback keeps running from the parked/overlay view.
    LaunchedEffect(Unit) { OnlinePlayer.park() }

    LaunchedEffect(activity.dataVersion.intValue) {
        favorites = MusicPreferences.favorites(activity)
        playlists = MusicPreferences.playlists(activity)
    }

    LaunchedEffect(permissionGranted, liveScanVersion) {
        if (permissionGranted) {
            val result = withContext(Dispatchers.IO) { MusicLibrary.scan(activity) }
            tracks = result
            activity.tracksSnapshot = result
        } else {
            tracks = emptyList()
        }
        loaded = true
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            withContext(Dispatchers.IO) {
                MusicLibrary.syncUnindexed(activity) { count ->
                    if (count > 0) activity.runOnUiThread { activity.scanVersion.intValue += 1 }
                }
            }
        }
    }

    val albums = remember(tracks) { buildAlbums(tracks) }
    val artists = remember(tracks) { buildArtists(tracks) }
    val lib = remember(tracks, albums, artists, playlists, favorites) { LibraryData(tracks, albums, artists, playlists, favorites) }
    val topDetail: DetailTarget? = detailStack.lastOrNull()
    val selectedTracks = tracks.filter { it.key in selectedKeys }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        MusicPreferences.saveFavorites(activity, favorites)
        activity.toast(if (key in favorites) "Added to Liked Songs" else "Removed from Liked Songs")
    }

    fun rescan() {
        activity.toast("Scanning for music…")
        activity.scanVersion.intValue += 1
        scope.launch(Dispatchers.IO) {
            MusicLibrary.syncUnindexed(activity) { count ->
                activity.runOnUiThread {
                    activity.scanVersion.intValue += 1
                    activity.toast(if (count > 0) "Added $count new files" else "Library is up to date")
                }
            }
        }
    }

    val actions = TrackActions(
        currentId = snap.mediaId,
        favorites = favorites,
        selected = selectedKeys,
        selectMode = selectMode,
        onPlay = { list, index ->
            val tapped = list.getOrNull(index)
            if (tapped != null) {
                if (selectMode || selectedKeys.isNotEmpty()) {
                    selectedKeys = if (tapped.key in selectedKeys) selectedKeys - tapped.key else selectedKeys + tapped.key
                } else {
                    connection.playQueue(list, index)
                }
            }
        },
        onLongPress = { selectMode = true; selectedKeys = selectedKeys + it.key },
        onMore = { actionTrack = it }
    )

    BackHandler(enabled = selectedKeys.isNotEmpty() || selectMode || showSettings || detailStack.isNotEmpty() || tab != MainTab.Home) {
        when {
            (selectedKeys.isNotEmpty() || selectMode) -> { selectedKeys = emptySet(); selectMode = false }
            showSettings -> showSettings = false
            detailStack.isNotEmpty() -> detailStack.removeAt(detailStack.lastIndex)
            else -> tab = MainTab.Home
        }
    }
    BackHandler(enabled = nowPlayingOpen) { nowPlayingOpen = false }

    if (showWelcome) {
        WelcomeScreen(onStart = {
            MusicPreferences.setWelcomed(activity)
            showWelcome = false
        })
        return
    }

    val content: @Composable () -> Unit = {
        when {
            showSettings -> SettingsScreen(
                activity = activity,
                songCount = tracks.size,
                onBack = { showSettings = false },
                onRescan = { rescan() }
            )
            topDetail != null -> DetailScreen(
                target = topDetail,
                lib = lib,
                actions = actions,
                onBack = { detailStack.removeAt(detailStack.lastIndex) },
                onPlayAll = { list, shuffle ->
                    if (list.isNotEmpty()) connection.playQueue(list, if (shuffle) Random.nextInt(list.size) else 0, shuffle)
                },
                onRenamePlaylist = { renamePlaylist = it },
                onDeletePlaylist = { deletePlaylist = it }
            )
            else -> when (tab) {
                MainTab.Home -> HomeScreen(
                    lib = lib,
                    actions = actions,
                    permissionGranted = permissionGranted,
                    loaded = loaded,
                    onOpen = { detailStack.add(it) },
                    onSettings = { showSettings = true },
                    onGrant = { activity.requestAudioPermission() },
                    onRescan = { rescan() }
                )
                MainTab.Songs -> SongsScreen(
                    lib = lib,
                    actions = actions,
                    onStartSelect = { selectMode = true },
                    onSelectAll = { list -> selectMode = true; selectedKeys = list.map { it.key }.toSet() }
                )
                MainTab.Online -> OnlineScreen(activity)
                MainTab.Search -> SearchScreen(lib = lib, actions = actions, onOpen = { detailStack.add(it) })
                MainTab.Library -> LibraryScreen(
                    lib = lib,
                    actions = actions,
                    filter = filter,
                    onFilter = { filter = it },
                    onOpen = { detailStack.add(it) },
                    onCreatePlaylist = { showCreate = true }
                )
            }
        }
    }

    // ONE player bar. Whichever player is actually playing owns it, so playing a local song after an online one
    // (or the other way round) always shows the right controls instead of a stale bar from the other player.
    val playerBar: @Composable () -> Unit = {
        val onlineVideo = OnlinePlayer.state.video
        val showOnline = onlineVideo != null && (OnlinePlayer.state.isPlaying || !(snap.hasMedia && snap.isPlaying))
        if (showOnline) {
            OnlineMiniBar { tab = MainTab.Online; detailStack.clear(); showSettings = false }
        } else if (snap.hasMedia) {
            MiniPlayer(
                connection = connection,
                favorite = snap.mediaId != null && snap.mediaId in favorites,
                onToggleFavorite = { snap.mediaId?.let { toggleFavorite(it) } },
                onOpen = { nowPlayingOpen = true }
            )
        }
    }

    val selectTab: (MainTab) -> Unit = {
        tab = it
        detailStack.clear()
        showSettings = false
        selectedKeys = emptySet(); selectMode = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Rage.Bg)) {
        // Tablets and large windows get the desktop layout: a side rail instead of the bottom bar, content in a
        // readable column, and (on very wide screens) a permanent player panel on the right.
        val wide = maxWidth >= 700.dp
        val localPlaying = snap.hasMedia && !(OnlinePlayer.state.video != null && OnlinePlayer.state.isPlaying)
        val panel = maxWidth >= 1000.dp && localPlaying
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(tab, selectTab, onSettings = { showSettings = true; detailStack.clear() })
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 960.dp).fillMaxSize()) { content() }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = 960.dp)) { playerBar() }
                    }
                }
                if (panel) {
                    Box(Modifier.width(340.dp).fillMaxHeight()) {
                        NowPlayingScreen(
                            connection = connection,
                            favorite = snap.mediaId != null && snap.mediaId in favorites,
                            onToggleFavorite = { snap.mediaId?.let { toggleFavorite(it) } },
                            onClose = {},
                            onFloating = { activity.setFloatingPlayer(true) },
                            showClose = false
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                playerBar()
                BottomNav(tab, selectTab)
            }
        }

        if (selectMode || selectedKeys.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Rage.SurfaceHigh).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(R.drawable.ic_x, { selectedKeys = emptySet(); selectMode = false }, contentDescription = "Clear selection")
                Text(if (selectedKeys.isEmpty()) "Select songs" else "${selectedKeys.size} selected", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconAction(R.drawable.ic_heart, {
                    val fresh = selectedKeys - favorites
                    val already = selectedKeys.size - fresh.size
                    if (selectedKeys.isEmpty()) {
                        activity.toast("Select songs first")
                    } else if (fresh.isEmpty()) {
                        activity.toast(if (selectedKeys.size == 1) "Already in Liked Songs" else "All of these are already in Liked Songs")
                    } else {
                        favorites = favorites + fresh
                        MusicPreferences.saveFavorites(activity, favorites)
                        activity.toast("Added ${fresh.size} to Liked Songs" + if (already > 0) ", $already already added" else "")
                        selectedKeys = emptySet(); selectMode = false
                    }
                }, contentDescription = "Add to Liked Songs")
                IconAction(R.drawable.ic_list_plus, {
                    if (selectedKeys.isEmpty()) activity.toast("Select songs first") else pickerKeys = selectedKeys
                }, contentDescription = "Add to playlist")
                val inPlaylist = topDetail as? DetailTarget.Playlist
                if (inPlaylist != null) {
                    IconAction(R.drawable.ic_minus_circle, {
                        playlists = MusicPreferences.removeFromPlaylist(activity, inPlaylist.id, selectedKeys)
                        selectedKeys = emptySet(); selectMode = false
                    }, contentDescription = "Remove from playlist")
                }
                IconAction(R.drawable.ic_trash, { deleteTracks = selectedTracks }, tint = Rage.Danger, contentDescription = "Delete from device")
            }
        }

        AnimatedVisibility(
            visible = nowPlayingOpen && snap.hasMedia,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            NowPlayingScreen(
                connection = connection,
                favorite = snap.mediaId != null && snap.mediaId in favorites,
                onToggleFavorite = { snap.mediaId?.let { toggleFavorite(it) } },
                onClose = { nowPlayingOpen = false },
                onFloating = {
                    activity.setFloatingPlayer(true)
                    nowPlayingOpen = false
                }
            )
        }
    }

    actionTrack?.let { track ->
        val playlistTarget = topDetail as? DetailTarget.Playlist
        TrackActionsSheet(
            track = track,
            favorite = track.key in favorites,
            inPlaylist = playlistTarget != null,
            onFavorite = { toggleFavorite(track.key); actionTrack = null },
            onAddToPlaylist = { pickerKeys = setOf(track.key); actionTrack = null },
            onRemoveFromPlaylist = {
                if (playlistTarget != null) playlists = MusicPreferences.removeFromPlaylist(activity, playlistTarget.id, setOf(track.key))
                actionTrack = null
            },
            onSelect = { selectMode = true; selectedKeys = setOf(track.key); actionTrack = null },
            onRename = { renameTrack = track; actionTrack = null },
            onDelete = { deleteTracks = listOf(track); actionTrack = null },
            onDismiss = { actionTrack = null }
        )
    }

    pickerKeys?.let { keys ->
        PlaylistPickerSheet(
            playlists = playlists,
            keys = keys,
            onPick = { playlist ->
                val existing = playlist.songKeys.toSet()
                val fresh = keys.filter { it !in existing }
                val already = keys.size - fresh.size
                if (fresh.isEmpty()) {
                    activity.toast(if (keys.size == 1) "Already in ${playlist.name}" else "All of these are already in ${playlist.name}")
                } else {
                    playlists = MusicPreferences.addToPlaylist(activity, playlist.id, fresh)
                    activity.toast("Added ${fresh.size} to ${playlist.name}" + if (already > 0) ", $already already added" else "")
                    selectedKeys = emptySet(); selectMode = false
                }
                pickerKeys = null
            },
            onNew = {
                pendingAddKeys = keys
                pickerKeys = null
                showCreate = true
            },
            onDismiss = { pickerKeys = null }
        )
    }

    if (showCreate) {
        NameDialog(
            title = "New playlist",
            hint = "Name",
            initial = "",
            confirm = "Create",
            onDismiss = { showCreate = false; pendingAddKeys = null },
            onConfirm = { name ->
                playlists = MusicPreferences.createPlaylist(activity, name)
                val keys = pendingAddKeys
                val created = playlists.lastOrNull()
                if (keys != null && created != null) {
                    playlists = MusicPreferences.addToPlaylist(activity, created.id, keys)
                    selectedKeys = emptySet(); selectMode = false
                }
                pendingAddKeys = null
                showCreate = false
            }
        )
    }

    renamePlaylist?.let { playlist ->
        NameDialog(
            title = "Rename playlist",
            hint = "Name",
            initial = playlist.name,
            onDismiss = { renamePlaylist = null },
            onConfirm = { name ->
                playlists = MusicPreferences.renamePlaylist(activity, playlist.id, name)
                renamePlaylist = null
            }
        )
    }

    renameTrack?.let { track ->
        NameDialog(
            title = "Rename song",
            hint = "Name",
            initial = track.title,
            onDismiss = { renameTrack = null },
            onConfirm = { name ->
                activity.renameAudioTrack(track, name)
                renameTrack = null
            }
        )
    }

    deleteTracks?.let { list ->
        ConfirmDialog(
            title = if (list.size == 1) "Delete this song?" else "Delete ${list.size} songs?",
            body = "The files are removed from this device. This can't be undone.",
            confirm = "Delete",
            onDismiss = { deleteTracks = null },
            onConfirm = {
                activity.deleteAudioTracks(list)
                deleteTracks = null
                selectedKeys = emptySet(); selectMode = false
            }
        )
    }

    deletePlaylist?.let { playlist ->
        ConfirmDialog(
            title = "Delete \"${playlist.name}\"?",
            body = "The songs stay on your device.",
            confirm = "Delete",
            onDismiss = { deletePlaylist = null },
            onConfirm = {
                playlists = MusicPreferences.deletePlaylist(activity, playlist.id)
                if ((topDetail as? DetailTarget.Playlist)?.id == playlist.id) detailStack.removeAt(detailStack.lastIndex)
                deletePlaylist = null
            }
        )
    }
}

/** First-launch welcome: what the app does, where music goes, and who made it. */
@Composable
private fun WelcomeScreen(onStart: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Rage.Bg).statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(Rage.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                RIcon(R.drawable.ic_stat_music, tint = Rage.Accent, size = 40.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text("Welcome to Music Rage", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Made by Angelo", color = Rage.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Rage.Surface).padding(18.dp)) {
                WelcomeRow(R.drawable.ic_music, "Your offline library", "Play the music already on this device. Playlists, favorites and a player built for speed.")
                Spacer(Modifier.height(14.dp))
                WelcomeRow(R.drawable.ic_globe, "Online music too", "Search and stream from the Online tab, and save any song as an MP3 right into your library.")
                Spacer(Modifier.height(14.dp))
                WelcomeRow(R.drawable.ic_zap, "Keeps playing", "Music continues in the background, and recovers itself if Android closes the app.")
            }
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(50))
                    .background(Rage.Accent).clickable(onClick = onStart),
                contentAlignment = Alignment.Center
            ) {
                Text("Get started", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WelcomeRow(icon: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Rage.Accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            RIcon(icon, tint = Rage.Accent, size = 20.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = Rage.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Rage.SurfaceHigh,
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Text(body, color = Rage.TextDim) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Rage.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Rage.TextDim) } }
    )
}

@Composable
private fun BottomNav(tab: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xF20B0B0E)).navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp)
    ) {
        NavItem("Home", if (tab == MainTab.Home) R.drawable.ic_home_fill else R.drawable.ic_home, tab == MainTab.Home, Modifier.weight(1f)) { onSelect(MainTab.Home) }
        NavItem("Songs", R.drawable.ic_music, tab == MainTab.Songs, Modifier.weight(1f)) { onSelect(MainTab.Songs) }
        NavItem("Online", R.drawable.ic_globe, tab == MainTab.Online, Modifier.weight(1f)) { onSelect(MainTab.Online) }
        NavItem("Search", if (tab == MainTab.Search) R.drawable.ic_search_bold else R.drawable.ic_search, tab == MainTab.Search, Modifier.weight(1f)) { onSelect(MainTab.Search) }
        NavItem("Library", if (tab == MainTab.Library) R.drawable.ic_library_bold else R.drawable.ic_library, tab == MainTab.Library, Modifier.weight(1f)) { onSelect(MainTab.Library) }
    }
}

@Composable
private fun SideRail(tab: MainTab, onSelect: (MainTab) -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.width(92.dp).fillMaxHeight().background(Color(0xFF0E0E12))
            .statusBarsPadding().navigationBarsPadding().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NavItem("Home", if (tab == MainTab.Home) R.drawable.ic_home_fill else R.drawable.ic_home, tab == MainTab.Home, Modifier.fillMaxWidth()) { onSelect(MainTab.Home) }
        NavItem("Songs", R.drawable.ic_music, tab == MainTab.Songs, Modifier.fillMaxWidth()) { onSelect(MainTab.Songs) }
        NavItem("Online", R.drawable.ic_globe, tab == MainTab.Online, Modifier.fillMaxWidth()) { onSelect(MainTab.Online) }
        NavItem("Search", if (tab == MainTab.Search) R.drawable.ic_search_bold else R.drawable.ic_search, tab == MainTab.Search, Modifier.fillMaxWidth()) { onSelect(MainTab.Search) }
        NavItem("Library", if (tab == MainTab.Library) R.drawable.ic_library_bold else R.drawable.ic_library, tab == MainTab.Library, Modifier.fillMaxWidth()) { onSelect(MainTab.Library) }
        Spacer(Modifier.weight(1f))
        IconAction(R.drawable.ic_settings, onSettings, contentDescription = "Settings")
    }
}

@Composable
private fun NavItem(label: String, icon: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RIcon(icon, tint = if (selected) Color.White else Rage.TextDim, size = 24.dp)
        Text(label, color = if (selected) Color.White else Rage.TextDim, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
