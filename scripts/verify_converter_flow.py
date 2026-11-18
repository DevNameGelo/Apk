#!/usr/bin/env python3
"""Live test of the full mp3juice.sc / thetacloud.org converter flow the user documented:
  Search: GET https://mp3juice.sc/api/v1/search?api_key=<key>&y=y&q=<base64(urlencode(query))>
  Auth:   GET https://theta.thetacloud.org/api/v1/auth?api_key=<key> -> {geo, key}
  Init:   GET https://theta.thetacloud.org/api/v1/init + Bearer -> {convertURL}
  Convert:GET <convertURL>&v=<videoId>&f=mp3 -> {progressURL, downloadURL}
  Poll progress until >= 3, then download downloadURL&v=&f=&r=mp3juice.sc
Then prepend the Id3Writer tag (exactly the Kotlin bytes) and verify with mutagen:
TIT2/TPE1/TALB/APIC must read back."""
import base64
import json
import struct
import time
import urllib.parse
import urllib.request

KEY = "mp3juice_c1ce77421b808bc5b89a307da142f3211313469053730109c30c3c4fc9abff3b"
SITE = "https://mp3juice.sc"
CONVERT_HOST = "https://theta.thetacloud.org/api/v1"
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")

def http(url, headers=None, method="GET"):
    h = {"User-Agent": UA}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read(), dict(resp.headers), resp.status

def jhttp(url, headers=None):
    body, hdrs, code = http(url, headers)
    return json.loads(body.decode("utf-8", "replace"))

print("== 1) search (mp3juice) ==")
q = urllib.parse.quote("Multo Cup of Joe")
b64 = base64.b64encode(q.encode()).decode()
search_url = f"{SITE}/api/v1/search?api_key={KEY}&y=y&q={b64}"
try:
    res = jhttp(search_url, headers={"Origin": SITE, "Referer": SITE + "/", "Accept": "application/json"})
    items = res.get("results") or res.get("items") or res if isinstance(res, list) else []
    print("  search reply keys:", list(res.keys()) if isinstance(res, dict) else type(res))
    if isinstance(res, dict):
        for k in ("results", "items", "data"):
            if res.get(k):
                v = res[k]
                print("  first results:", v[:2])
                break
except Exception as e:
    print("  search failed:", e)

print("== 2) auth (thetacloud) ==")
auth = jhttp(f"{CONVERT_HOST}/auth?api_key={KEY}&_={int(time.time()*1000)}",
             headers={"Origin": SITE, "Referer": SITE + "/", "Accept": "application/json"})
print("  auth:", {k: (v[:20] + '…' if isinstance(v, str) and len(v) > 20 else v) for k, v in auth.items() if k in ("geo", "key", "token", "status")})
token = auth.get("key") or auth.get("token")

print("== 3) init ==")
init = jhttp(f"{CONVERT_HOST}/init?_={int(time.time()*1000)}",
             headers={"Origin": SITE, "Referer": SITE + "/", "Accept": "application/json",
                      "Authorization": f"Bearer {token}"})
convert_url = init.get("convertURL")
print("  convertURL:", convert_url)

print("== 4) convert (SoundCloud permalink as v) ==")
v_param = urllib.parse.quote("https://soundcloud.com/cupofjoeband/multo", safe="")
conv = jhttp(f"{convert_url}&v={v_param}&f=mp3",
             headers={"Origin": SITE, "Referer": SITE + "/", "Accept": "application/json",
                      "Authorization": f"Bearer {token}"})
progress_url = conv.get("progressURL")
download_url = conv.get("downloadURL")
print("  progressURL:", progress_url)
print("  downloadURL:", download_url)

print("== 5) poll progress ==")
for i in range(40):
    prog = jhttp(progress_url, headers={"Origin": SITE, "Referer": SITE + "/", "Accept": "application/json"})
    p = int(str(prog.get("progress", 0)).strip() or 0)
    print(f"  progress: {p}")
    if p >= 3:
        break
    time.sleep(2)

print("== 6) download mp3 ==")
dl = f"{download_url}&v=&f=mp3&r=mp3juice.sc" if download_url else None
if dl:
    mp3, hdrs, code = http(dl, headers={"Origin": SITE, "Referer": SITE + "/", "Authorization": f"Bearer {token}"})
    print("  downloaded", len(mp3), "bytes; content-type:", hdrs.get("Content-Type"))
    with open("/tmp/conv_multo.mp3", "wb") as f:
        f.write(mp3)
else:
    print("  no downloadURL — flow stopped here")
    mp3 = open("/dev/null", "rb").read()

# ---------- Id3Writer bytes, exactly as Kotlin writes them ----------
def synchsafe(size):
    return bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])

def frame(fid, payload):
    return fid.encode() + struct.pack(">I", len(payload)) + b"\0\0" + payload

def text_frame(fid, text):
    return frame(fid, b"\x03" + text.encode("utf-8") + b"\x00")

def cover_frame(mime, desc, image):
    return frame("APIC", b"\x00" + mime.encode() + b"\x00" + b"\x03" + desc.encode() + b"\x00" + image)

def build_tag(title, artist, album, cover):
    body = b""
    any_ = False
    if title.strip():
        body += text_frame("TIT2", title); any_ = True
    if artist.strip():
        body += text_frame("TPE1", artist); any_ = True
    if album.strip():
        body += text_frame("TALB", album); any_ = True
    if cover and len(cover) > 100:
        mime = "image/jpeg" if cover[:2] == b"\xff\xd8" else "image/png"
        body += cover_frame(mime, "Cover", cover); any_ = True
    if not any_:
        return None
    return b"ID3" + bytes([3, 0, 0]) + synchsafe(len(body)) + body

if mp3 and len(mp3) > 100:
    # SoundCloud cover for the tag
    cov_req = urllib.request.Request(
        "https://i1.sndcdn.com/artworks-J5WyGVFm2CzznDqm-t500x500.jpg",  # placeholder below replaces
        headers={"User-Agent": UA})
    # fetch the real one from the track API instead
    CID = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"
    t = jhttp(f"https://api-v2.soundcloud.com/tracks/2073324732?client_id={CID}")
    art = (t.get("artwork_url") or "").replace("-large", "-t500x500")
    cover = None
    if art:
        try:
            cover, _, _ = http(art)
        except Exception:
            cover = None
    tag = build_tag("Multo - Cup of Joe", "Marv", "Multo - Cup of Joe", cover)
    # prependTag: replace any existing ID3v2
    if mp3[:3] == b"ID3":
        old_size = ((mp3[6] & 0x7F) << 21) | ((mp3[7] & 0x7F) << 14) | ((mp3[8] & 0x7F) << 7) | (mp3[9] & 0x7F)
        start = min(10 + old_size, len(mp3))
    else:
        start = 0
    tagged = tag + mp3[start:]
    with open("/tmp/conv_multo_tagged.mp3", "wb") as f:
        f.write(tagged)

    import mutagen
    mf = mutagen.File("/tmp/conv_multo_tagged.mp3")
    print("\n== mutagen reads the tagged converter MP3 ==")
    print("  title :", mf.get("TIT2"))
    print("  artist:", mf.get("TPE1"))
    print("  album :", mf.get("TALB"))
    apic = mf.get("APIC:Cover") or next((v for k, v in mf.items() if k.startswith("APIC")), None)
    print("  cover :", (apic.mime, len(apic.data)) if apic else None)
    print("ROUND-TRIP:", "PASS ✅" if (mf.get("TIT2") and mf.get("TPE1") and mf.get("TALB")) else "FAIL ❌")
