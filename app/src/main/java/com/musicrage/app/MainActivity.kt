package com.musicrage.app

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.musicrage.app.data.AudioTrack
import com.musicrage.app.data.MusicPreferences
import com.musicrage.app.data.cleanTitle
import com.musicrage.app.online.OnlinePlayer
import com.musicrage.app.playback.AppVisibility
import com.musicrage.app.playback.PlaybackService
import com.musicrage.app.playback.PlayerConnection
import com.musicrage.app.ui.MusicRageApp
import com.musicrage.app.ui.MusicRageTheme

class MainActivity : ComponentActivity() {
    lateinit var connection: PlayerConnection
        private set

    internal val audioPermissionState = mutableStateOf(false)
    internal val scanVersion = mutableIntStateOf(0)
    internal val dataVersion = mutableIntStateOf(0)
    internal val settingsVersion = mutableIntStateOf(0)
    internal val openFullPlayerState = mutableStateOf(false)
    internal val openOnlineState = mutableStateOf(false)
    internal var tracksSnapshot: List<AudioTrack> = emptyList()

    private var pendingOverlayEnable = false
    private var pendingExternalUri: Uri? = null
    private var pendingDeleteUris: List<Uri> = emptyList()
    private var pendingRename: Triple<Uri, String, String>? = null
    private var lastResumeScan = 0L

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeBackup(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        connection = PlayerConnection(this)
        OnlinePlayer.init(this)
        OnlinePlayer.pauseLocal = { connection.pause() }
        if (intent?.getBooleanExtra(EXTRA_OPEN_ONLINE, false) == true) openOnlineState.value = true
        pendingExternalUri = extractAudioUri(intent)
        audioPermissionState.value = hasAudioPermission()
        setContent {
            MusicRageTheme {
                MusicRageApp(activity = this)
            }
        }
        // An ACTION_VIEW grant is enough to play that one file; do not interrupt it with a library permission prompt.
        if (!audioPermissionState.value && pendingExternalUri == null) requestAudioPermission()
        runCatching { OnlinePlayer.attach(this) }
        connection.start()
        pendingExternalUri?.let {
            pendingExternalUri = null
            openExternalAudio(it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ONLINE, false)) openOnlineState.value = true
        extractAudioUri(intent)?.let { openExternalAudio(it) }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.set(true)
        connection.start()
    }

    override fun onStop() {
        AppVisibility.set(false)
        connection.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val granted = hasAudioPermission()
        if (audioPermissionState.value != granted) audioPermissionState.value = granted
        val now = System.currentTimeMillis()
        if (granted && now - lastResumeScan > 4_000L) {
            lastResumeScan = now
            scanVersion.intValue += 1
        }
        settingsVersion.intValue += 1
        runCatching { OnlinePlayer.syncHost(this) }
        if (pendingOverlayEnable && Settings.canDrawOverlays(this)) {
            pendingOverlayEnable = false
            MusicPreferences.setFloatingEnabled(this, true)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            connection.release()
            OnlinePlayer.detach()
        }
        super.onDestroy()
    }

    // ---- Incoming audio -----------------------------------------------------------------------------------------

    private fun extractAudioUri(source: Intent?): Uri? {
        if (source?.action != Intent.ACTION_VIEW) return null
        val uri = source.data ?: return null
        val type = source.type.orEmpty()
        if (!type.startsWith("audio/") && type.isNotBlank()) return null
        if (uri.scheme == "content" && source.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            val grantFlags = source.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (grantFlags != 0) runCatching { contentResolver.takePersistableUriPermission(uri, grantFlags) }
        }
        return uri
    }

    internal fun openExternalAudio(uri: Uri) {
        Thread {
            val fallbackName = displayName(uri)
            var title = fallbackName.substringBeforeLast('.', fallbackName)
            var artist = ""
            var album = ""
            var duration = 0L
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (error: Throwable) {
                // Metadata is optional; the file name is enough.
            } finally {
                runCatching { retriever.release() }
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(cleanTitle(title))
                .setArtist(artist.ifBlank { "Unknown artist" })
                .setAlbumTitle(album.ifBlank { "Unknown album" })
                .setDurationMs(duration.takeIf { it > 0L })
                .setArtworkUri(uri)
                .build()
            val item = MediaItem.Builder().setMediaId(uri.toString()).setUri(uri).setMediaMetadata(metadata).build()
            runOnUiThread {
                connection.playSingle(item)
                openFullPlayerState.value = true
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Audio file"
    }

    // ---- Permissions --------------------------------------------------------------------------------------------

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    internal fun requestAudioPermission() {
        val permissions = buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        requestPermissions(permissions, REQUEST_AUDIO)
    }

    @Deprecated("Uses the platform permission callback for Android 10+ compatibility")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            audioPermissionState.value = hasAudioPermission()
            if (audioPermissionState.value) scanVersion.intValue += 1
        }
    }

    // ---- Settings actions ---------------------------------------------------------------------------------------

    internal fun setFloatingPlayer(enabled: Boolean) {
        if (!enabled) {
            MusicPreferences.setFloatingEnabled(this, false)
            settingsVersion.intValue += 1
            return
        }
        if (Settings.canDrawOverlays(this)) {
            MusicPreferences.setFloatingEnabled(this, true)
            settingsVersion.intValue += 1
            toast("The floating player appears when you leave the app")
        } else {
            pendingOverlayEnable = true
            toast("Allow \"Display over other apps\" for Music Rage")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    /** Opens the "Display over other apps" screen so the online player can live in a steady background window. */
    internal fun requestOverlayForPlayer() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    }

    /** Tells the running playback service to re-read settings that affect it (audio focus, overlay). */
    internal fun applyServiceSettings() {
        runCatching { startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_SYNC_OVERLAY)) }
    }

    internal fun isBatteryUnrestricted(): Boolean {
        val manager = getSystemService(PowerManager::class.java)
        return manager?.isIgnoringBatteryOptimizations(packageName) == true
    }

    internal fun requestBatteryUnrestricted() {
        if (isBatteryUnrestricted()) {
            toast("Battery usage is already unrestricted")
            return
        }
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try {
            startActivity(direct)
        } catch (error: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    internal fun startBackupExport() = exportLauncher.launch("music-rage-backup.json")

    internal fun startBackupImport() = importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))

    private fun writeBackup(uri: Uri) {
        val json = MusicPreferences.exportBackup(this, tracksSnapshot)
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        toast(if (ok) "Backup saved" else "Could not save the backup")
    }

    private fun readBackup(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text != null && MusicPreferences.importBackup(this, text, tracksSnapshot)) {
            dataVersion.intValue += 1
            toast("Backup restored")
        } else {
            toast("That file is not a Music Rage backup")
        }
    }

    internal fun toast(message: String) {
        Toaster.show(this, message)
    }

    // ---- File operations ----------------------------------------------------------------------------------------

    private var deleteRetried = false
    private var deleteQueue = ArrayDeque<Uri>()
    private var deleteTotal = 0
    private var deleteDone = 0
    private var awaitingLegacyDelete: Uri? = null

    internal fun deleteAudioTracks(tracks: List<AudioTrack>) {
        val uris = tracks.map { it.uri }.distinct()
        if (uris.isEmpty()) return
        pendingDeleteUris = uris
        deleteTotal = uris.size
        deleteDone = 0
        deleteRetried = false
        if (Build.VERSION.SDK_INT >= 30) {
            requestSystemDelete(uris)
        } else {
            // Android 10 asks for permission file by file, so the files are processed one after another.
            deleteQueue = ArrayDeque(uris)
            processLegacyDelete()
        }
    }

    private fun requestSystemDelete(uris: List<Uri>) {
        try {
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            startIntentSenderForResult(request.intentSender, REQUEST_DELETE, null, 0, 0, 0)
        } catch (error: Exception) {
            toast("Could not delete the selected music")
        }
    }

    private fun processLegacyDelete() {
        while (deleteQueue.isNotEmpty()) {
            val uri = deleteQueue.first()
            try {
                contentResolver.delete(uri, null, null)
                deleteQueue.removeFirst()
                deleteDone += 1
            } catch (security: SecurityException) {
                if (Build.VERSION.SDK_INT == 29 && security is RecoverableSecurityException) {
                    awaitingLegacyDelete = uri
                    try {
                        startIntentSenderForResult(security.userAction.actionIntent.intentSender, REQUEST_DELETE, null, 0, 0, 0)
                    } catch (error: Exception) {
                        deleteQueue.clear()
                        finishDelete()
                    }
                    return
                }
                deleteQueue.removeFirst()
            } catch (error: Exception) {
                deleteQueue.removeFirst()
            }
        }
        finishDelete()
    }

    private fun finishDelete() {
        scanVersion.intValue += 1
        toast("Deleted $deleteDone of $deleteTotal")
    }

    private fun uriExists(uri: Uri): Boolean = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** Renames the song everywhere in the app at once, then also tries to rename the file itself. */
    internal fun renameAudioTrack(track: AudioTrack, newTitle: String) {
        val title = newTitle.trim().ifBlank { return }
        MusicPreferences.setTitleOverride(this, track.key, title)
        scanVersion.intValue += 1

        val extension = track.displayName.substringAfterLast('.', "")
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { return }
        val fileName = if (extension.isNotEmpty()) "$safe.$extension" else safe
        pendingRename = Triple(track.uri, fileName, title)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createWriteRequest(contentResolver, listOf(track.uri))
                startIntentSenderForResult(request.intentSender, REQUEST_RENAME, null, 0, 0, 0)
            } else {
                updateFileName(track.uri, fileName, title)
            }
        } catch (security: SecurityException) {
            if (Build.VERSION.SDK_INT == 29 && security is RecoverableSecurityException) {
                runCatching { startIntentSenderForResult(security.userAction.actionIntent.intentSender, REQUEST_RENAME, null, 0, 0, 0) }
            } else {
                toast("Renamed in the app. Android did not allow renaming the file.")
            }
        } catch (error: Exception) {
            toast("Renamed in the app. The file name could not be changed.")
        }
    }

    private fun updateFileName(uri: Uri, fileName: String, title: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.TITLE, title)
        }
        val changed = runCatching { contentResolver.update(uri, values, null, null) }.getOrDefault(0)
        pendingRename = null
        toast(if (changed > 0) "Renamed" else "Renamed in the app. The file name could not be changed.")
        scanVersion.intValue += 1
    }

    @Deprecated("Uses the platform intent-sender result for MediaStore user approval")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE) {
            if (Build.VERSION.SDK_INT >= 30) {
                if (resultCode == RESULT_OK) {
                    val remaining = pendingDeleteUris.filter { uriExists(it) }
                    deleteDone = deleteTotal - remaining.size
                    if (remaining.isNotEmpty() && !deleteRetried) {
                        deleteRetried = true
                        pendingDeleteUris = remaining
                        requestSystemDelete(remaining)
                        return
                    }
                    finishDelete()
                } else {
                    toast("Delete cancelled")
                }
                pendingDeleteUris = emptyList()
            } else {
                val uri = awaitingLegacyDelete
                awaitingLegacyDelete = null
                if (resultCode == RESULT_OK && uri != null) {
                    runCatching { contentResolver.delete(uri, null, null) }
                    if (deleteQueue.isNotEmpty()) deleteQueue.removeFirst()
                    deleteDone += 1
                    processLegacyDelete()
                } else {
                    deleteQueue.clear()
                    finishDelete()
                }
            }
        } else if (requestCode == REQUEST_RENAME) {
            val pending = pendingRename
            if (resultCode == RESULT_OK && pending != null) {
                updateFileName(pending.first, pending.second, pending.third)
            } else {
                pendingRename = null
                toast("Renamed in the app. The file name was not changed.")
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_ONLINE = "open_online"
        private const val REQUEST_AUDIO = 1201
        private const val REQUEST_DELETE = 1203
        private const val REQUEST_RENAME = 1204
    }
}
