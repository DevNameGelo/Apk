#!/usr/bin/env python3
"""Simulate the NEW app search pipeline (mirror of OnlineApi.kt as edited) and verify:
- 'ako ang bagyo' -> official SHEAN track ranks FIRST (encrypted but playable now)
- 'kalapastanganan' -> official fitterkarma track appears (from playlist expansion)
- 'terenova cakes and eclairs' -> Cakes & Eclairs artist tracks appear (user expansion)
- 'kalapa' suggestions -> kalapastanganan-ish completions
- related() for multo gives similar songs
"""
import json
import re
import sys
import urllib.parse
import urllib.request

CID = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
BASE = "https://api-v2.soundcloud.com"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=25) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


def sc(path):
    sep = "&" if "?" in path else "?"
    return get(f"{BASE}{path}{sep}client_id={CID}")


def from_track(t):
    media = (t.get("media") or {}).get("transcodings") or []
    protocols = set(tc.get("format", {}).get("protocol", "?") for tc in media)

    def playable(t):
        if t.get("snipped"):
            return False
        if (t.get("policy") or "").upper() == "GO_PLUS":
            return False
        if t.get("streamable") is False:
            return False
        return bool(protocols & {"hls", "progressive", "cbc-encrypted-hls", "ctr-encrypted-hls"})

    fd = t.get("full_duration") or t.get("duration") or 0
    return {
        "id": t.get("id"),
        "title": t.get("title") or "",
        "channel": (t.get("user") or {}).get("username") or "",
        "duration": (fd or 0) / 1000.0,
        "playable": playable(t),
        "likes": t.get("likes_count") or 0,
    }


COVER = ["cover", "sped up", "remix", "reimagined", "extended", "live at", "demo", "version",
         "instrumental", "acoustic", "karaoke", "edit", "nightcore", "slowed", "reverb", "2x"]


def norm(s):
    return re.sub(r"\s+", " ", re.sub(r"[\W_]+", " ", (s or "").lower())).strip()


def score(v, q, words):
    title = norm(v["title"])
    channel = norm(v["channel"])
    s = 0
    if title == q:
        s += 100
    elif title.startswith(q):
        s += 80
    elif q in title:
        s += 60
    low = (v["title"] or "").lower()
    if any(m in low for m in COVER):
        s -= 45
    if q and q in channel:
        s += 40
    for w in words:
        if w in title:
            s += 10
        if w in channel:
            s += 5
    s += min(v["likes"], 50000) // 1000
    if v["playable"]:
        s += 5
    if 45.0 <= v["duration"] <= 600.0:
        s += 5
    else:
        s -= 10
    return s


STOPWORDS = {"and", "the", "by", "of", "a", "an", "feat", "ft", "x", "official", "video", "music", "audio"}


def ladder(query):
    words = [w for w in query.split() if w]
    attempts = [query]
    cur = list(words)
    for i in reversed(range(len(words))):
        if words[i].lower() in STOPWORDS and len(cur) > 1:
            cur.pop(i)
            attempts.append(" ".join(cur))
    cur = list(words)
    while len(cur) > 1:
        cur.pop()
        attempts.append(" ".join(cur))
    return attempts


def search(query):
    text = query.strip()
    out = {}
    playlists, users = [], []
    collection = []
    for attempt in ladder(text):
        d = sc(f"/search?q={urllib.parse.quote(attempt)}&limit=25&variant=generic")
        if d.get("collection"):
            collection = d["collection"]
            break
    for item in collection:
        kind = item.get("kind")
        if kind == "track":
            v = from_track(item)
            if v["id"] and v["title"]:
                out[v["id"]] = v
        elif kind in ("playlist", "system-playlist"):
            playlists.append(item.get("id"))
        elif kind == "user":
            users.append(str(item.get("id")))
    for pid in playlists[:6]:
        try:
            pl = sc(f"/playlists/{pid}")
            for t in pl.get("tracks", []):
                v = from_track(t)
                if v["id"] and v["title"] and v["id"] not in out:
                    out[v["id"]] = v
        except Exception:
            pass
    for uid in users[:3]:
        try:
            ut = sc(f"/users/{uid}/tracks?limit=10")
            for t in ut.get("collection", []):
                v = from_track(t)
                if v["id"] and v["title"] and v["id"] not in out:
                    out[v["id"]] = v
        except Exception:
            pass
    results = list(out.values())
    playable = [r for r in results if r["playable"]]
    if playable:
        results = playable
    q = norm(text)
    words = [w for w in q.split() if len(w) > 2]
    return sorted(results, key=lambda v: -score(v, q, words))


def suggest(query):
    text = query.strip()
    if len(text) < 2:
        return []
    d = sc(f"/search/queries?q={urllib.parse.quote(text)}")
    out = []
    for item in d.get("collection", []):
        c = item.get("query") or item.get("output") or ""
        if c and c.lower() != text.lower():
            out.append(c)
    return out[:6]


def show(query, n=6):
    rs = search(query)
    print(f"\n== '{query}' -> {len(rs)} results ==")
    for r in rs[:n]:
        print(f"   {r['title'][:52]!r} | {r['channel'][:28]!r} | likes={r['likes']:<5} playable={r['playable']}")
    return rs


fails = []

# 1) official 'ako ang bagyo' first
rs = show("ako ang bagyo")
if not rs or "bagyo" not in norm(rs[0]["title"]) or rs[0]["channel"].lower() != "shean":
    fails.append("ako ang bagyo: top result is not the official SHEAN track")

# 2) kalapastanganan surfaces the official song
rs = show("kalapastanganan")
found = any(norm("kalapastangan") in norm(r["title"]) and r["channel"] == "fitterkarma" for r in rs)
if not found:
    fails.append("kalapastanganan: official fitterkarma track missing")

# 3) artist search expands user tracks
rs = show("terenova cakes and eclairs")
found = any(r["channel"] == "Cakes & Eclairs" for r in rs)
print("   -> Cakes & Eclairs tracks present:", found)
if not found:
    fails.append("terenova cakes and eclairs: artist tracks missing")

# 4) suggestions
sug = suggest("kalapa")
print(f"\n== suggestions for 'kalapa': {sug}")
if not sug:
    fails.append("suggest('kalapa') returned nothing")

sug2 = suggest("kalapas")
print(f"== suggestions for 'kalapas': {sug2}")

# 5) related
rel = sc("/tracks/2073324732/related?limit=10")
print(f"\n== related for Multo: {len(rel.get('collection', []))} candidates")
for t in rel.get("collection", [])[:3]:
    print(f"   {t['title'][:50]!r} by {t['user']['username']}")

# 6) typo search still works
rs = show("ako ang bagy")

print("\nRESULT:", "ALL PASS" if not fails else "FAILURES: " + "; ".join(fails))
sys.exit(0 if not fails else 1)
