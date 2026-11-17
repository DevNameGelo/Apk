#!/usr/bin/env python3
"""Static sanity check over the edited Kotlin files:
- balanced braces/parens per file (comments and strings stripped)
- key symbols exist where expected
"""
import sys
from pathlib import Path

ROOT = Path("/home/z/my-project/apk/app/src/main/java/com/musicrage/app")

FILES = [
    "online/OnlinePlayer.kt",
    "online/OnlineService.kt",
    "playback/PlaybackSupervisor.kt",
    "playback/PlaybackService.kt",
    "playback/PlayerConnection.kt",
    "data/OnlineApi.kt",
    "data/MusicPreferences.kt",
    "ui/OnlineScreen.kt",
    "ui/MusicRageApp.kt",
    "ui/Screens.kt",
    "MainActivity.kt",
]

fail = 0

def strip_code(text: str) -> str:
    out = []
    i = 0
    n = len(text)
    in_line_comment = in_block_comment = in_string = in_char = False
    triple = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                out.append(c)
            i += 1
            continue
        if in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if c == "\\":
                i += 2
                continue
            if triple:
                if text.startswith('"""', i):
                    in_string = False
                    triple = False
                    i += 3
                    continue
                i += 1
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if text.startswith('"""', i):
            in_string = True
            triple = True
            i += 3
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "'":
            in_char = True
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


for rel in FILES:
    path = ROOT / rel
    text = path.read_text(encoding="utf-8")
    problems = []
    for marker in ("TODO", "FIXME", "<<<", ">>>"):
        if marker in text:
            problems.append(f"leftover marker {marker}")
    code = strip_code(text)
    for opener, closer in [("{", "}"), ("(", ")"), ("[", "]")]:
        if code.count(opener) != code.count(closer):
            problems.append(f"unbalanced {opener}{closer}: {code.count(opener)} vs {code.count(closer)}")
    if problems:
        fail += 1
        print(f"FAIL {rel}: {', '.join(problems)}")
    else:
        print(f"OK   {rel}")

checks = [
    ("data/OnlineApi.kt", "api-v2.soundcloud.com"),
    ("data/OnlineApi.kt", "private fun refreshClientId()"),
    ("data/OnlineApi.kt", "fun downloadHlsAudio(context: Context, video: OnlineVideo, progress: (Int) -> Unit): DownloadedAudio?"),
    ("data/OnlineApi.kt", "fun hlsPlaylist(trackId: String): String?"),
    ("data/OnlineApi.kt", "fun saveM4a(context: Context, bytes: ByteArray, video: OnlineVideo, seconds: Double)"),
    ("data/OnlineApi.kt", "fun prepareMp3(sourceUrl: String): String"),
    ("online/OnlinePlayer.kt", "w.soundcloud.com/player/api.js"),
    ("online/OnlinePlayer.kt", "SC.Widget.Events.FINISH"),
    ("online/OnlinePlayer.kt", "h.endsWith(\"soundcloud.com\")"),
    ("online/OnlinePlayer.kt", "loadDataWithBaseURL(\"https://w.soundcloud.com\""),
    ("online/OnlinePlayer.kt", "internal fun handlePageVideo(id: String)"),
    ("online/OnlineService.kt", "45_000L"),
    ("online/OnlineService.kt", "beatAgeMs(this) >= 8_000L"),
    ("ui/Screens.kt", "Music Rage 1.2.19"),
]
for rel, needle in checks:
    text = (ROOT / rel).read_text(encoding="utf-8")
    if needle not in text:
        fail += 1
        print(f"FAIL {rel}: missing '{needle}'")
    else:
        print(f"OK   {rel}: has '{needle}'")

checks += [
    ("online/OnlinePlayer.kt", "h.endsWith(\"soundcloud.cloud\")"),
    ("online/OnlinePlayer.kt", "media-streaming.soundcloud.cloud"),
    ("online/OnlinePlayer.kt", "A pause does NOT clear the alive marker"),
    ("data/OnlineApi.kt", "playable: Boolean = true"),
    ("data/OnlineApi.kt", "private fun hasPlayableTranscoding"),
    ("data/OnlineApi.kt", "fun search(query: String, playableOnly: Boolean = true)"),
    ("data/OnlineApi.kt", "full_duration"),
    ("data/OnlineApi.kt", "class DownloadedAudio(val bytes: ByteArray, val seconds: Double)"),
    ("data/OnlineApi.kt", "if (body.contains(\"#EXT-X-KEY:METHOD=\")) return null"),
    ("data/OnlineApi.kt", "if (seconds > 0) put(MediaStore.Audio.Media.DURATION"),
]

checks += [
    ("online/OnlinePlayer.kt", "Forbidden numeric value"),
    ("online/OnlinePlayer.kt", "optDouble(\"duration\", 0.0).takeUnless { it.isNaN() } ?: 0.0"),
    ("online/OnlinePlayer.kt", "video.duration.isNaN()"),
]

# No stale YouTube/watch-mode references may remain.
stale = [
    ("online/OnlinePlayer.kt", "watchMode"),
    ("online/OnlinePlayer.kt", "loadWatchPage"),
    ("online/OnlinePlayer.kt", "loadVideoById"),
    ("online/OnlinePlayer.kt", "youtube.com"),
    ("online/OnlinePlayer.kt", "CookieManager"),
    ("data/OnlineApi.kt", "INNERTUBE"),
    ("data/OnlineApi.kt", "searchPiped"),
    ("data/OnlineApi.kt", "searchInvidious"),
]
for rel, needle in stale:
    text = (ROOT / rel).read_text(encoding="utf-8")
    if needle in text:
        fail += 1
        print(f"FAIL {rel}: stale YouTube reference '{needle}'")
    else:
        print(f"OK   {rel}: no '{needle}'")

sys.exit(1 if fail else 0)
