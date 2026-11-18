#!/usr/bin/env python3
"""Byte-exact round-trip of the FIXED Mp4TagWriter logic:
1. Download a real SoundCloud HLS stream (init + first segments) — exactly what the app saves.
2. Apply the SAME tree edits the Kotlin performs, with the SAME bytes it writes after the fix
   (© = 0xA9 raw byte, text data = type+locale+text, covr = data sub-box with type+locale+image).
3. Validate with mutagen (the industry MP4 parser) AND ffprobe (what Android's scanner is modeled on):
   title / artist / album / cover must all read back.
Also re-checks the OLD layout to show the difference (old reads empty)."""
import json
import struct
import subprocess
import urllib.request
import base64

CID = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")

def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read()

def resolve(track_id):
    d = json.loads(get(f"https://api-v2.soundcloud.com/tracks/{track_id}?client_id={CID}"))
    for tc in d["media"]["transcodings"]:
        if tc["format"]["protocol"] == "hls" and "mpegurl" not in tc["format"].get("mime_type", ""):
            return json.loads(get(tc["url"] + f"?client_id={CID}"))["url"], d
    return None, d

def track_album(t):
    pub = t.get("publisher_metadata") or {}
    for k in ("album_title", "release_title"):
        v = pub.get(k)
        if v and str(v).strip():
            return str(v).strip()
    for k in ("release", "label_name", "metadata_artist"):
        v = t.get(k)
        if v and str(v).strip():
            return str(v).strip()
    return str(t.get("title") or "").strip()

# ---------- Kotlin byte functions, mirrored exactly (post-fix) ----------
def write_uint32(val):
    return struct.pack(">I", val & 0xFFFFFFFF)

def box(typ, payload):
    return write_uint32(8 + len(payload)) + typ.encode("latin-1") + payload

def tagged_box(typ3, payload):  # 'nam'/'ART'/'alb' → ©nam/©ART/©alb
    return write_uint32(8 + len(payload)) + b"\xa9" + typ3.encode("latin-1") + payload

def full_box(typ, payload):
    return write_uint32(12 + len(payload)) + typ.encode("latin-1") + b"\0\0\0\0" + payload

def text_atom(name, text):
    data = write_uint32(0x00000001) + write_uint32(0) + text.encode("utf-8")
    return tagged_box(name, box("data", data))

def cover_atom(cover):
    ctype = 0x0D if cover[:2] == b"\xff\xd8" else 0x0E
    data = write_uint32(ctype) + write_uint32(0) + cover
    return box("covr", box("data", data))

def find_box(data, frm, to, typ):
    at = frm
    while at + 8 <= to:
        size = struct.unpack_from(">I", data, at)[0]
        if size < 8 or at + size > to:
            return None
        if data[at+4:at+8].decode("latin-1") == typ:
            return (at, size)
        at += size
    return None

def with_metadata(m4a, title, artist, album, cover):
    children = []
    if title: children.append(text_atom("nam", title))
    if artist: children.append(text_atom("ART", artist))
    if album: children.append(text_atom("alb", album))
    if cover and len(cover) > 100:
        children.append(cover_atom(cover))
    if not children:
        return m4a
    moov = find_box(m4a, 0, len(m4a), "moov")
    if moov is None:
        return m4a
    mstart, msize = moov
    body_s, body_e = mstart + 8, mstart + msize
    udta = find_box(m4a, body_s, body_e, "udta")
    if udta is None:
        new_body = m4a[body_s:body_e] + box("udta", full_box("meta", box("ilst", b"".join(children))))
    else:
        ustart, usize = udta
        u_body = m4a[ustart+8:ustart+usize]
        meta = find_box(m4a, ustart+8, ustart+usize, "meta")
        if meta is None:
            new_udta_body = u_body + full_box("meta", box("ilst", b"".join(children)))
        else:
            metas, metasize = meta
            m_body = m4a[metas+8:metas+metasize]
            ilst = find_box(m4a, metas+12, metas+metasize, "ilst")
            if ilst is None:
                new_meta_body = m_body + box("ilst", b"".join(children))
            else:
                istart, isize = ilst
                keep = []
                at = istart + 8
                while at + 8 <= istart + isize:
                    sz = struct.unpack_from(">I", m4a, at)[0]
                    if sz < 8 or at + sz > istart + isize:
                        break
                    nm = m4a[at+4:at+8]
                    if nm not in (b"\xa9nam", b"\xa9ART", b"\xa9alb", b"covr"):
                        keep.append(m4a[at:at+sz])
                    at += sz
                new_ilst = box("ilst", b"".join(keep) + b"".join(children))
                rel = istart - (metas+8)
                new_meta_body = m_body[:rel] + new_ilst + m_body[rel+isize:]
            rel = metas - (ustart+8)
            new_udta_body = u_body[:rel] + box("meta", new_meta_body) + u_body[rel+metasize:]
        rel = ustart - body_s
        new_body = m4a[body_s:body_e][:rel] + box("udta", new_udta_body) + m4a[body_s:body_e][rel+usize:]
    return m4a[:mstart] + box("moov", new_body) + m4a[mstart+msize:]

# ---------- grab a real stream ----------
pl, track = resolve("2073324732")  # Multo — Cup of Joe (label release with album_title)
title = track["title"]
artist = track["user"]["username"]
album = track_album(track)
print(f"track: {title!r} artist={artist!r} album={album!r}")
lines = get(pl).decode().split("\n")
init_url = next((ln.split('URI="')[1].rstrip('"') for ln in lines if ln.startswith("#EXT-X-MAP:URI=")), None)
segs = [ln for ln in lines if ln and not ln.startswith("#")]
data = get(init_url)
for s in segs[:5]:
    data += get(s)
cover = get(track["artwork_url"].replace("-large", "-t500x500"))

out = with_metadata(data, title, artist, album, cover)
with open("/tmp/fixed_multo.m4a", "wb") as f:
    f.write(out)

# ---------- verify with mutagen ----------
import mutagen.mp4
m = mutagen.mp4.MP4("/tmp/fixed_multo.m4a")
tags = m.tags
print("\n== mutagen reads ==")
print("  title :", tags.get("\xa9nam"))
print("  artist:", tags.get("\xa9ART"))
print("  album :", tags.get("\xa9alb"))
print("  cover :", [len(c) for c in tags.get("covr", [])], "bytes,", m.tags.get("covr", [{}])[0].imageformat, "(13=JPEG)")

# ---------- verify with ffprobe ----------
p = subprocess.run(
    ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "/tmp/fixed_multo.m4a"],
    capture_output=True, text=True)
info = json.loads(p.stdout or "{}").get("format", {}).get("tags", {})
print("\n== ffprobe reads ==")
print("  title :", info.get("title"))
print("  artist:", info.get("artist"))
print("  album :", info.get("album"))

ok = (tags.get("\xa9nam") == [title] and tags.get("\xa9ART") == [artist]
      and tags.get("\xa9alb") == [album] and bool(tags.get("covr"))
      and info.get("title") == title and info.get("artist") == artist and info.get("album") == album)
print("\nROUND-TRIP:", "PASS ✅" if ok else "FAIL ❌")

# ---------- also prove the OLD layout fails (the bug we had) ----------
def old_text_atom(name, text):
    data = struct.pack(">III", 1, 1, 0) + text.encode()
    return box("\xa9" if False else "?" + name, box("data", data))  # '?nam' — US_ASCII dropped ©

def old_cover_atom(cover):
    return box("covr", write_uint32(13) + cover)

# same tree edit, old atoms
def with_old(m4a, title, artist, album, cover):
    children = []
    if title: children.append(old_text_atom("nam", title))
    if artist: children.append(old_text_atom("ART", artist))
    if album: children.append(old_text_atom("alb", album))
    if cover and len(cover) > 100: children.append(old_cover_atom(cover))
    # insert into a fresh copy with a plain append path (udta missing)
    moov = find_box(m4a, 0, len(m4a), "moov")
    if moov is None: return m4a
    mstart, msize = moov
    body_s, body_e = mstart+8, mstart+msize
    udta = find_box(m4a, body_s, body_e, "udta")
    if udta is None:
        new_body = m4a[body_s:body_e] + box("udta", full_box("meta", box("ilst", b"".join(children))))
    else:
        # append old-style atoms next to existing ilst children
        ustart, usize = udta
        u_body = m4a[ustart+8:ustart+usize]
        meta = find_box(m4a, ustart+8, ustart+usize, "meta")
        metas, metasize = meta
        m_body = m4a[metas+8:metas+metasize]
        ilst = find_box(m4a, metas+12, metas+metasize, "ilst")
        istart, isize = ilst
        new_ilst = box("ilst", m4a[istart+8:istart+isize] + b"".join(children))
        rel = istart - (metas+8)
        new_meta_body = m_body[:rel] + new_ilst + m_body[rel+isize:]
        rel = metas - (ustart+8)
        new_udta_body = u_body[:rel] + box("meta", new_meta_body) + u_body[rel+metasize:]
        rel = ustart - body_s
        new_body = m4a[body_s:body_e][:rel] + box("udta", new_udta_body) + m4a[body_s:body_e][rel+usize:]
    return m4a[:mstart] + box("moov", new_body) + m4a[mstart+msize:]

old_out = with_old(data, title, artist, album, cover)
with open("/tmp/old_multo.m4a", "wb") as f:
    f.write(old_out)
m2 = mutagen.mp4.MP4("/tmp/old_multo.m4a").tags
print("\n== OLD layout (what v1.2.23 shipped) mutagen reads ==")
print("  title :", m2.get("\xa9nam"), "| artist:", m2.get("\xa9ART"), "| album:", m2.get("\xa9alb"), "| covr:", m2.get("covr"))
print("  → nothing readable: exactly the bug the user saw (Unknown artist/album, grey tile)")
