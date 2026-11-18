#!/usr/bin/env python3
"""Verify Id3Writer's exact bytes on a real MP3 (the converter path's output).
Builds a real MP3 (ffmpeg sine wave), prepends the ID3 tag exactly as Kotlin writes it,
and checks mutagen + ffprobe read TIT2/TPE1/TALB/APIC back."""
import json
import struct
import subprocess
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")

def http(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read()

# a real MP3 payload (not a converter file, but valid MPEG audio + real cover)
subprocess.run(["ffmpeg", "-y", "-v", "quiet", "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
                "-b:a", "192k", "/tmp/base.mp3"], check=True)
mp3 = open("/tmp/base.mp3", "rb").read()

CID = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"
t = json.loads(http(f"https://api-v2.soundcloud.com/tracks/2073324732?client_id={CID}"))
cover = http((t.get("artwork_url") or "").replace("-large", "-t500x500"))

# ---- Id3Writer.kt byte-for-byte ----
def synchsafe(size):
    return bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])

def frame(fid, payload):
    return fid.encode("ascii") + struct.pack(">I", len(payload)) + b"\x00\x00" + payload

def text_frame(fid, text):
    return frame(fid, b"\x03" + text.encode("utf-8") + b"\x00")

def cover_frame(mime, image):
    return frame("APIC", b"\x00" + mime.encode("ascii") + b"\x00" + b"\x03" + b"Cover" + b"\x00" + image)

def build_tag(title, artist, album, cover):
    body = bytearray()
    if title.strip(): body += text_frame("TIT2", title)
    if artist.strip(): body += text_frame("TPE1", artist)
    if album.strip(): body += text_frame("TALB", album)
    if cover and len(cover) > 100:
        mime = "image/jpeg" if cover[:2] == b"\xff\xd8" else "image/png"
        body += cover_frame(mime, cover)
    return b"ID3" + bytes([3, 0, 0]) + synchsafe(len(body)) + bytes(body)

def prepend_tag(mp3, tag):
    if mp3[:3] == b"ID3":
        old = ((mp3[6] & 0x7F) << 21) | ((mp3[7] & 0x7F) << 14) | ((mp3[8] & 0x7F) << 7) | (mp3[9] & 0x7F)
        start = min(10 + old, len(mp3))
    else:
        start = 0
    return tag + mp3[start:]

tag = build_tag("Multo - Cup of Joe", "Marv", "Multo - Cup of Joe", cover)
out = prepend_tag(mp3, tag)
open("/tmp/id3_test.mp3", "wb").write(out)

import mutagen
mf = mutagen.File("/tmp/id3_test.mp3")
print("== mutagen reads ID3v2.3 ==")
print("  title :", mf.get("TIT2"))
print("  artist:", mf.get("TPE1"))
print("  album :", mf.get("TALB"))
apic = mf.get("APIC:Cover") or next((v for k, v in mf.items() if k.startswith("APIC")), None)
print("  cover :", (apic.mime, apic.type, len(apic.data)) if apic else None)

p = subprocess.run(["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "/tmp/id3_test.mp3"],
                   capture_output=True, text=True)
info = json.loads(p.stdout or "{}").get("format", {}).get("tags", {})
print("== ffprobe reads ==")
print("  title :", info.get("title"), "| artist:", info.get("artist"), "| album:", info.get("album"))
ok = (mf.get("TIT2") and mf.get("TPE1") and mf.get("TALB") and apic
      and info.get("album") == "Multo - Cup of Joe")
print("ID3 ROUND-TRIP:", "PASS ✅" if ok else "FAIL ❌")
