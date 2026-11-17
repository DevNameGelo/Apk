#!/usr/bin/env python3
"""Probe the SoundCloud public API: search relevance, related tracks, autocomplete, artwork."""
import json
import re
import sys
import urllib.request

CLIENT_ID = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"
BROWSER_AGENT = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                 "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")


def get(url, browser=True):
    req = urllib.request.Request(url)
    if browser:
        req.add_header("User-Agent", BROWSER_AGENT)
        req.add_header("Accept-Language", "en-US,en;q=0.9")
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


def sc(path):
    sep = "&" if "?" in path else "?"
    return get(f"https://api-v2.soundcloud.com{path}{sep}client_id={CLIENT_ID}")


def show_tracks(label, data, limit=12):
    collection = data.get("collection", []) if isinstance(data, dict) else []
    print(f"\n== {label} ({len(collection)} items) ==")
    for t in collection[:limit]:
        if not isinstance(t, dict):
            print("  -", repr(t)[:80])
            continue
        track = t.get("track") if t.get("kind") == "playlist" else t
        if not isinstance(track, dict):
            print("  -", t.get("kind"), t.get("id"))
            continue
        media = track.get("media", {}).get("transcodings", [])
        protocols = sorted(set(x.get("format", {}).get("protocol", "?") for x in media))
        duration = track.get("full_duration") or track.get("duration")
        print(f"  - id={track.get('id')} dur={duration} policy={track.get('policy')} "
              f"snip={track.get('snipped')} playable={track.get('playable')} proto={protocols} "
              f"official={track.get('is_official')} label={track.get('label_name')} "
              f"genre={track.get('genre')!r} user={track.get('user', {}).get('username')!r} "
              f"title={track.get('title')!r} art={'Y' if track.get('artwork_url') else 'N'}")


# 1. Search as the app does it now (variant=generic, limit=25)
for q in ["terenova", "kalapastanganan", "ako ang bagyo"]:
    try:
        show_tracks(f"search generic: {q}", sc(f"/search?q={urllib.request.quote(q)}&limit=25&variant=generic"))
    except Exception as e:
        print(f"search {q} failed: {e}")

# 2. Search without the variant parameter (plain track search)
for q in ["terenova", "kalapastanganan"]:
    try:
        show_tracks(f"search /tracks: {q}", sc(f"/tracks?q={urllib.request.quote(q)}&limit=12"))
    except Exception as e:
        print(f"tracks search {q} failed: {e}")

# 3. Autocomplete endpoint
for q in ["kalapa", "terenova", "multo"]:
    try:
        data = sc(f"/search?q={urllib.request.quote(q)}&limit=10&variant=autocomplete")
        print(f"\n== autocomplete: {q} ==")
        print(json.dumps(data, ensure_ascii=False)[:600])
    except Exception as e:
        print(f"autocomplete {q} failed: {e}")

# 4. Related tracks for the known tracks (Multo = ?
# find Multo's id via search first)
try:
    found = sc("/tracks?q=multo%20cup%20of%20coffee&limit=3")
    for t in found[:3]:
        print("\nrelated for:", t.get("id"), t.get("title"))
        rid = t.get("id")
        try:
            rel = sc(f"/tracks/{rid}/related?limit=5")
            show_tracks("related", rel)
        except Exception as e:
            print("  related failed:", e)
        break
except Exception as e:
    print("multo lookup failed:", e)
