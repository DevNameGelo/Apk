# Changelog

## 1.2.31
- Fixed: playing a different song while one is already playing sometimes did nothing — the click was silently ignored and the old song kept going (this happened while the player was in its watch-page fallback, which had no way to receive the new song).
- Fixed: after a song ended and auto-next picked the next one, the mini player and notification kept showing the previous song's title and album art while different music was playing.
- Version numbering now follows the app's own scheme, derived automatically from the build number: 1.2.31, …, 1.2.1000 → 1.3.0, …, 1.10.1000 → 2.0.0.

## 1.3.0
- The Online tab is back on YouTube: the embedded YouTube player runs in an invisible sandboxed WebView (only YouTube hosts are ever loaded — ads, trackers and pop-ups are refused before their scripts run), the player never shows and never steals taps, and playback keeps running from the notification after the app is swiped away.
- Ads are handled on their own: while an ad actually runs the player fast-forwards it muted and clicks Skip whenever the button appears, and the Online tab shows an "Ads — please wait" chip for exactly that time (no chip when no ad runs).
- Downloads are real MP3s again: the video is converted through mp3juice/thetacloud and saved with a full ID3 tag — title, artist, album and embedded cover art (the cover from YouTube's own thumbnail), so files open with their metadata everywhere.
- Search runs on YouTube (mp3juice's YouTube results, with a public Piped search as fallback), auto-correct suggests while you type come from YouTube's own suggestion endpoint, and after a song ends the next one is picked from similar music (the artist's other songs, covers, then your most-played artists).
- The self-healing player now has one more rung: a video the embed refuses to play (most official music videos) quietly reloads in the real watch page with only the video visible, and a playback that freezes gets nudged before it is ever reloaded.

## 1.2.24
- Fixed the bug where downloads kept showing "Unknown artist" / no album with a grey tile: the metadata atoms written into the m4a were malformed (the © byte was dropped to '?' by ASCII encoding, the text atoms had a 4-byte misalignment and the cover atom had no data sub-box), so no player — Android's scanner included — could read them. The atoms are now written exactly as iTunes/ffmpeg/mutagen expect them, verified byte-for-byte against those parsers: title, artist, album and cover all read back from a real downloaded file.
- The MP3 fallback path was verified against the same parsers (its ID3v2.3 tag was already correct; title/artist/album/APIC cover all read back).

## 1.2.23
- Downloads now carry the song's REAL album, not a placeholder: the album comes from SoundCloud's own release data (the label's album title, the release title, the label name, or the song title for a single), so a downloaded file is grouped under its actual album in every player.
- m4a downloads embed the full metadata now: title, artist and ALBUM text atoms (©nam/©ART/©alb) are written next to the cover atom, and any title/artist the stream already carried is REPLACED instead of duplicated, so the file has exactly one of each.
- The album, artist and title are also written into the media library row itself, so the Music Rage list and the Android library show them immediately.

## 1.2.22
- Delete now removes the real file. The app reads each song's file path before deleting, and afterwards checks the disk: a song counts as deleted only when its file is really gone. Anything the system dialog left behind is removed directly, and what still cannot be removed is reported ("Deleted 3 of 5") instead of being silently hidden from the list.
- Rename now changes the real file name first. The name shown in the app is updated only after the file on storage was renamed, so the list can no longer disagree with the storage. A renamed file keeps its favorites, playlists and shown name.
- New "All files access" setting (Settings > Library). With it, delete and rename work directly on the files with no system dialog and in any folder; without it the system dialog is still used, and the app asks for it when Android blocks a change.

## 1.2.21
- Songs that showed up but never played (the play button stayed on pause): label-uploaded songs arrive as protected (DRM) streams, and the hidden player never granted the protected-media permission the page asks for, so those streams could not start. The player now grants it to SoundCloud pages only.
- The playable check now follows SoundCloud's own flags: a track whose access is preview or blocked, whose policy is SNIP or BLOCK, or whose only streams are marked snipped (30-second previews) no longer counts as playable. Playable songs are listed first.
- A song that still cannot start is no longer left as a dead button: after a quick retry (about 10 seconds, down from 25) the app quietly plays another upload of the same song, removes the dead one from Continue playing, and hides it from search results for a week.
- Search now finds artists the way the SoundCloud site does: an explicit people search ("terenova") brings in that artist's most popular songs, an artist named inside a longer query still ranks their songs first, and a plain track search backs up the mixed search when it returns few songs.

## 1.2.20
- Search now finds what SoundCloud's site finds: the query is retried with filler words ("and", "official"…) and then with trailing words dropped, so "Terenova cakes and eclairs" returns the artist's songs instead of nothing. Artist profiles and playlists in the results are expanded in — a playlist named after the song you typed (e.g. "kalapastanganan") contributes its tracks, and an artist profile contributes their uploads.
- The original/official upload now ranks first: results are ordered by how exactly the title matches the query, with covers, sped-up and remix versions ranked down and popularity as the tie-breaker. Official label uploads with an encrypted stream play fine (the widget decrypts them itself), so they no longer sink below covers — "ako ang bagyo" shows SHEAN's original at the top.
- Search auto-correct while typing: SoundCloud's own query completion suggests full names as you type ("kalapa" suggests "kalapastanganan", "ako ang b" suggests "ako ang bagyo"). Tap a suggestion to search it straight away; a Go button next to the field runs the search too.
- The Online tab now keeps playing online music forever: when a song ends the next one comes from SoundCloud's own "related tracks" for that song (the same list the site's station uses), falling back to the same-artist / similar / favourite-artist ladder only when that is empty. It never switches back to local downloads.
- Downloads now carry the album art: the m4a save writes a proper MP4 cover atom (moov/udta/meta/ilst/covr) and the converter MP3 gets an ID3 APIC frame, so saved songs show their cover in the library instead of a grey tile.
- The hidden player is now truly invisible: alpha 0 and parked off-screen, so no faint square shows on black screens any more.
- Deleting a song now really deletes the file from storage (any folder), not just from the app's list: after the system delete dialog, anything still indexed is wiped from disk directly so no ghost entry survives a rescan.
- Renaming a song now really renames the file on storage: MediaStore renames the physical file (with the write grant on Android 11+), and when it refuses, the file itself is renamed on disk and rescanned — the new name is what every other app sees, not just Music Rage.

## 1.2.19
- Fixed the crash when opening the Online tab (or right after start-up): entries saved by earlier versions had no stored length, and reading one back produced a corrupted number that Android's JSON writer refuses to save — the crash fired the moment the app rewrote history or the resume pointer. Unknown lengths now read as 0 and are saved as 0 instead of the value that crashed.

## 1.2.18
- Fixed songs that showed 0 duration and never played: SoundCloud delivers its audio from a CDN host (playback.media-streaming.soundcloud.cloud) the player sandbox was refusing. The sandbox now allows SoundCloud's media delivery hosts, so streams actually reach the widget.
- Fixed songs that stopped after ~30 seconds while the card still said "playing": the pause marker written by a stray widget event disabled crash recovery and could also make the watchdog treat a healthy song as frozen. Pauses no longer clear the recovery marker.
- Label-uploaded songs (e.g. some versions of "Ako ang Bagyo") only offer an encrypted stream that cannot play without a SoundCloud login — they are now filtered out of search results and auto-next picks, so what the app offers always really plays. If every result for a query is that kind, they are still shown rather than nothing.
- Downloaded songs no longer claim a length the audio does not have: the saved duration now comes from the stream's own playlist (the sum of its segments), and the library lets the media scanner correct it, so a 3-minute card no longer stops at 30 seconds. Encrypted previews fall back to the converter instead of saving a broken file.

## 1.2.17
- SoundCloud everywhere instead of YouTube: search, playback and cover art all come from SoundCloud now. The old YouTube player (embed, watch-page fallback, ad skipping, embed-block list) is gone — no more "This video can't be played", no ads, no embed-blocked songs. The player is the official SoundCloud widget, which plays every public track.
- Downloads straight from SoundCloud: the song's stream is fetched segment by segment from SoundCloud's CDN and saved as a single file (m4a) in Music/Music Rage, with the real title, artist and duration in the library. The old MP3 converter stays as a fallback for restricted songs (it accepts SoundCloud links too), so the download button keeps working the same way.
- Auto-recovery after the app is killed: the watchdog alarm now fires sooner (45 s instead of 60 s), recovery no longer cancels itself mid-repair, a killed-while-playing state is detected by the heartbeat's age (so reopening the app always resumes), and the song resumes at the exact position where it stopped. The widget also nudges itself back to play if its own audio-focus dance pauses it.
- The client id SoundCloud requires is resolved automatically: a known id is used first and a fresh one is scraped from the widget bundle whenever it stops working, so search and playback keep going after SoundCloud rotates ids.

## 1.2.16
- The "This video can't be played" toast is gone for good. Failures are repaired silently instead: reload the song, switch player type, then move on to another song.
- Auto-next now plays a similar song instead of the next item of the list you tapped in. After a song ends (or Next is pressed with nothing queued) the app picks one from the same artist, songs like it, or the artists you play most, skipping songs you already played, long mixes and charts, and videos that cannot play.
- The made-up "Playing next" entry and its duplicate in Continue playing are gone. If YouTube's own autoplay jumps to another video, the app treats the song as finished and chooses what plays next. Old placeholder entries are cleaned automatically.
- Continue playing cards have an X to remove them; a song returns when you play it again.
- Ads: the hidden player now skips ads by itself. It clicks Skip when offered, otherwise fast-forwards the ad to its end while muted, on both the embedded player and the watch-page fallback. Known ad requests are blocked as well. No tap needed.
- Silent self-healing watchdog: it notices a first-time install, what plays, what gets skipped or finished (used to learn your taste), and repairs a page that never loads, a song stuck on loading, or a song whose clock froze. It never shows a message; a small private log is kept for diagnosis.

## 1.2.10
- Fixed the Online spinner that kept spinning while the song was already playing: loading now ends as soon as the playback clock advances, not only when the page happens to send its "playing" event. The 25-second give-up timer also follows the real loading state now.
- Fixed the false "This video can't be played" toast right after opening the app and when switching to a local song while online music was playing. Errors from the idle player page are ignored unless an online song is actually loading, and a song that is already audible is never interrupted by a stray error.
- Downloads now recognise songs you already saved: tapping MP3 again says "Already downloaded", and the button shows a check and "Saved". If you delete the file, the button goes back to MP3.
- Redesigned the Online tab: pill search, a tinted now-playing card (cover colour gradient, seek bar, controls, download state), a Continue playing shelf, thumbnail rows with an animated playing indicator, skeleton loading placeholders, a retry card for errors and a cleaner background-permission card. The mini bar got the same tint and a progress line.

## 1.2.9
- Fixed the confusing toast spam: opening the app, starting online music or switching to a local song no longer fires "This video can't be played" and "Music Rage was closed in the background" at the same time. A failed recovery is now silent, and the local service only announces a real kill of its own session; the online service no longer "recovers" a video that was already replaced. Repeated embed failures now stop after three tries instead of looping.
- A loading spinner now appears on the Online tab (on the cover, on the play button and in the mini player) while a song is still buffering, and disappears the moment it actually plays.
- The online player now runs in a sandbox: only YouTube player/CDN hosts can load, ad and tracker hosts (doubleclick, googlesyndication, adservice…) are refused before their scripts run, pop-up windows are refused outright, ad overlays are auto-closed and "Skip" is clicked automatically on the watch page.
- The watch page now reports which video it is really on, so when YouTube auto-advances to a related video (or an ad forces its own navigation) the now-playing card, mini bar and notification update to the actual song instead of staying stuck on the old title.
- The position-inheritance bug is fully closed: the page-side time reports are gated by the video id, so the old video's 17 s / 50 s can never reach the new track or the watch-page fallback URL while the new one is still loading.

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
