# Music Rage

Offline local music player for Android. Kotlin, Jetpack Compose, Material 3, AndroidX Media3.

- Package: `com.musicrage.app`
- Version: `1.2.6` (build 11)
- Minimum Android: 10 (API 29)

## Features
- Home, Search and Your Library tabs; albums, artists, playlists, Liked Songs.
- Cover art from the audio files, shown in the app, notification and lock screen.
- Long-press to multi-select; add to playlist, like, rename or delete files.
- Playback speed (0.25x to 10x) from the player; pitch follows speed.
- Floating player over other apps (draggable).
- Backup and restore of playlists and favorites.

## Playback recovery
The playback service is a foreground media service. It saves the queue, position and a "should be playing" flag. If the process is killed while music was playing, the restarted service detects it, shows a toast, resumes at the saved position and shows a second toast when sound is back. A watchdog alarm restarts the service if Android does not.

Android decides whether a killed app may restart. Some phone brands block this unless the app is allowed to auto-start and has unrestricted battery use. Use **Settings > Unrestricted battery usage**, and on Realme/Oppo/Xiaomi/Vivo also allow auto-start for Music Rage in the system app settings.

## Signing and updates
Every build is signed with `signing/music-rage.keystore` (alias `musicrage`), so a new APK installs over the old one and keeps playlists, favorites and settings. The first install after switching to this key requires uninstalling the older, differently signed build once.

The keystore and its password are in this repository so the workflow works with no setup. If the repository is public, anyone can sign an APK with your key. To use a private key instead, add these repository secrets and the workflow uses them automatically: `KEYSTORE_BASE64` (base64 of your .keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Choose the key once; changing it later forces another uninstall.

## Build
GitHub Actions: push to `main`, or run **Actions > Build Music Rage APKs**. Artifacts: `Music-Rage-arm64-v8a`, `Music-Rage-Universal`, `Music-Rage-All-APKs`. The log prints the signing certificate; it must be the same on every run.

Locally: `./gradlew :app:assembleRelease`

Toolchain: AGP 9.5.0, Gradle 9.6.0, JDK 17, Kotlin 2.4.21, Compose BOM 2026.09.00, Media3 1.11.1, compileSdk 37, targetSdk 36.
