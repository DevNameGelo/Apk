# Changelog

## 1.2.8
- Playing a local song while online music is playing now pauses the online player completely: it stands down, its bar disappears and the local player takes over the notification — before, the online bar stayed on screen forever and the local player looked like it "didn't play".
- Switching to another online song always starts it at 0:00. Late progress reports from the previous video used to overwrite the new track's position and even make the watch-page fallback resume at the old timestamp (the "skips to 50 seconds" bug).
- The download button now shows a live percentage, 0% to 100%: converter progress during preparation and real byte-level progress while the MP3 is transferred.
- A welcome screen is shown on a brand-new install: what the app does, where downloads go, and "Made by Angelo". Settings → About now credits "Made by Angelo" too.
- The online service fully clears the player state when it stops, so a dead online bar can no longer reappear after the app is killed and restarted.

## 1.2.7
- One media notification instead of two: local and online playback now share a single notification owner, so the Online tab player and the local player can never both claim the shade or the headset buttons at the same time.
- The Online tab was redesigned: a clean modern layout with a rounded now-playing card (cover, title, channel, seek bar, controls and download) that matches the local player style — the YouTube video itself is never shown in the app any more; the player bar below the tabs now shows whichever player is active (online included).
- Library updates instantly when files change: a MediaStore observer refreshes the song list the moment an MP3 is added or deleted from storage, instead of waiting for a tab switch or resume.
- Toast spam fixed: every toast now goes through one queue that drops duplicates, throttles bursts and shows messages one at a time.
- Downloads now carry their metadata: the MP3 is saved with the title, artist and the video thumbnail embedded as the ID3 cover, so it shows a proper title, artist and artwork in the library instead of "Unknown artist" and a grey tile.
- When an online song finishes and the queue is empty, a similar song (same artist or related music) is found and played automatically.
- Ads: the embedded player hides its own controls (the app's card is the interface), and the watch-page fallback now hides and skips ad slots; the stale "stuck on an old video" state after ads or a track change is gone because the tab no longer shows the video surface.

## 1.2.6
- Online player now lives in a steady background window (needs "Display over other apps"). It is no longer moved between windows, so it does not pause when you switch tabs, close the app or reopen it. The Online tab shows the cover instead of the video while this is active; a hint in the tab asks for the permission if it is off.
- Download rewritten to match the converter's current flow: auth (no key first, then the saved key), init, convert, redirect, progress, then the MP3 is streamed straight into Music/Music Rage. Every failure now says which step failed and what the server answered.

## 1.2.5
- Fixed the build error in `OnlineApi.kt` (a missing closing brace). Added the temporary mp3juice key.

## 1.2.4
- Online videos no longer start muted: the player is unmuted and set to full volume on every start.
- Online music keeps playing when you switch tabs: the parked player keeps a real size instead of 1px and no longer swallows touches.
- Online music keeps playing after the app is closed from recents: the player moves into an overlay window owned by the app (needs "Display over other apps"; a toast explains this once if it is off).
- Download now uses the mp3juice converter (192 kbps MP3): auth, init, convert, wait for progress, then the file goes to the Download folder. Set `MP3JUICE_API_KEY` in `OnlineApi.kt` first. The archive.org lookup is gone.

## 1.2.3
- Fixed Online search showing "No results": the results parser now reads YouTube's current page layout, then falls back to YouTube's web search endpoint and public Piped/Invidious mirrors. Still no API key needed.
- Fixed "This video can't be played in the embedded player": when a video blocks embedding, it now plays from the normal YouTube watch page (video only) and keeps playing in the background. The toast only appears if the video really cannot play.
- Removed the YouTube API key setting and dialog.

## 1.2.2
- Fixed online search failing with a wall of `x22` text: the mobile results page escapes its data, which is now decoded.

## 1.2.1
- Online search and recommendations no longer need a YouTube API key; they read the public YouTube results page. The key is now optional (Settings > Online player).

## 1.2.0
- New Online tab: search YouTube music, recommendations, "Continue playing", embedded YouTube player. It keeps playing in the background with a notification (previous, play/pause, next), lock screen and headset controls.
- If Android or a game kills the app while online music plays, the service restarts, shows the same recovery toasts and reloads the video at its saved position (a watchdog alarm covers the case where the system does not restart it).
- Download button appears only when the song is found on archive.org; it saves the MP3 to the Download folder so it can be played offline in the library.
- Requires the INTERNET permission.

## 1.1.2
- Multi-select delete now removes every selected song (Android 10 is handled file by file; Android 11+ is verified and retried), with a "Deleted X of Y" message.
- Rename changes the name shown everywhere in the app immediately, and also tries to rename the file.
- Adding to a playlist or Liked Songs now reports "Already added" instead of adding twice; the playlist picker marks lists that already contain the song.
- Playlist covers are always a 2x2 grid (top-left, top-right, bottom-left, bottom-right).
- New Songs tab: every audio file across all folders, folder filters, filter box, Select and Select all for fast adding to playlists and favorites.
- Same signing key as 1.1.0, so it installs over the previous build.

## 1.1.0
- Redesigned the whole app in a Spotify-style layout: Home, Search and Your Library tabs, shelves, album/artist/playlist pages, bottom-sheet actions, a docked mini player and a full-screen player tinted from the cover art.
- Album art is now shown everywhere (list rows, mini player, full player, notification, lock screen, floating player). Playlists get a cover built from their songs' albums: one cover, a layered stack for two or three, or a 2x2 mosaic.
- Library scan no longer filters on `IS_MUSIC`, so every audio file Android has indexed shows up (the old build hid anything not tagged as music). Files that were never indexed are found and submitted to the media scanner.
- Fixed the player controls: play/pause, previous, next, shuffle, repeat and the seek bar now read from one live connection that reconnects by itself if the playback service dies. Time and progress update continuously.
- Playback speed moved into the player (bottom sheet with slider, step buttons and presets) and was removed from Settings.
- Playback recovery rewritten. If Android or a game kills the app while music is playing, the service restarts, shows "Music Rage was closed in the background", resumes the queue at the saved position and shows "Playback recovered". A watchdog alarm covers the case where the system does not restart the service. The retry-attempts setting is gone.
- Floating player rebuilt: the whole card drags, the buttons work, it remembers its position and hides while the app is open.
- New settings: play alongside other apps (ignore audio focus), unrestricted battery shortcut, rescan, backup/restore of playlists and favorites.
- Replaced the Material icon set with a custom outline icon set; removed filler copy.
- Release builds are now signed with one fixed key (`signing/music-rage.keystore`) so every update installs over the previous one.

## 1.0.1
- Removed the in-app file-open choice dialog; files opened from other apps start in the full player.
- Natural speed/pitch scaling and speed presets.
