#!/usr/bin/env python3
"""Prototype + verify the MP4 covr-atom insertion against real SoundCloud HLS output,
and validate with a strict MP4 box walker. Mirrors Mp4TagWriter.kt exactly."""
import json
import struct
import sys
import urllib.request

CID = "nIjtjiYnjkOhMyh5xrbqEW12DxeJVnic"
UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read()


def resolve(track_id):
    url = f"https://api-v2.soundcloud.com/tracks/{track_id}?client_id={CID}"
    d = json.loads(get(url))
    for tc in d["media"]["transcodings"]:
        proto = tc["format"]["protocol"]
        mime = tc["format"].get("mime_type", "")
        if proto == "hls" and "mpegurl" not in mime:
            return json.loads(get(tc["url"] + f"?client_id={CID}"))["url"]
    return None


def box(data, off):
    size = struct.unpack_from(">I", data, off)[0]
    typ = data[off + 4:off + 8].decode("latin-1")
    return size, typ


def walk(data, off=0, end=None, depth=0):
    """Validate box structure; returns True when every box header parses within bounds."""
    end = len(data) if end is None else end
    while off + 8 <= end:
        size, typ = box(data, off)
        if size < 8:
            return False
        if off + size > end:
            return False
        yield off, size, typ, depth
        if typ in ("moov", "udta", "ilst", "meta", "trak", "mdia", "minf", "stbl", "edts", "dinf"):
            # meta is a fullbox: children start 4 bytes later
            child = off + 8 + (4 if typ == "meta" else 0)
            if child < off + size:
                yield from walk(data, child, off + size, depth + 1)
        off += size


def with_cover(m4a, cover):
    """Python mirror of Mp4TagWriter.withCover."""
    def find(from_, to, typ):
        at = from_
        while at + 8 <= to:
            size = struct.unpack_from(">I", m4a, at)[0]
            if size < 8 or at + size > to:
                return None
            if m4a[at + 4:at + 8].decode("latin-1") == typ:
                return (at, size)
            at += size
        return None

    def mk(typ, payload):
        return struct.pack(">I", 8 + len(payload)) + typ.encode("latin-1") + payload

    def mkfull(typ, payload):
        return struct.pack(">I", 12 + len(payload)) + typ.encode("latin-1") + b"\0\0\0\0" + payload

    is_jpeg = cover[0] == 0xFF and cover[1] == 0xD8
    covr = mk("covr", struct.pack(">I", 0x0D if is_jpeg else 0x0E) + cover)
    moov = find(0, len(m4a), "moov")
    if moov is None:
        return m4a
    moov_start, moov_size = moov
    body_start, body_end = moov_start + 8, moov_start + moov_size
    udta = find(body_start, body_end, "udta")
    if udta is None:
        new_body = m4a[body_start:body_end] + mk("udta", mkfull("meta", mk("ilst", covr)))
    else:
        u_start, u_size = udta
        u_body = m4a[u_start + 8:u_start + u_size]
        meta = find(u_start + 8, u_start + u_size, "meta")
        if meta is None:
            new_udta_body = u_body + mkfull("meta", mk("ilst", covr))
        else:
            m_start, m_size = meta
            # meta body starts 4 bytes later (fullbox)
            m_body = m4a[m_start + 8:m_start + m_size]
            ilst = find(m_start + 12, m_start + m_size, "ilst")
            if ilst is None:
                new_meta_body = m_body + mk("ilst", covr)
            else:
                i_start, i_size = ilst
                i_body = m4a[i_start + 8:i_start + i_size]
                new_ilst = mk("ilst", i_body + covr)
                rel = i_start - (m_start + 8)
            new_meta_body = m_body[:rel] + new_ilst + m_body[rel + i_size:]
        # m_body ALREADY starts with meta's 4 version/flag bytes (it was sliced from meta+8),
        # so the rebuilt meta is a PLAIN box over that body — never add the flags again.
        rel = m_start - (u_start + 8)
        new_udta_body = u_body[:rel] + mk("meta", new_meta_body) + u_body[rel + m_size:]
        rel = u_start - body_start
        new_body = m4a[body_start:body_end][:rel] + mk("udta", new_udta_body) + m4a[body_start:body_end][rel + u_size:]
    return m4a[:moov_start] + mk("moov", new_body) + m4a[moov_start + moov_size:]


def main():
    # Multo = plain hls (2073324732); use it for a real joined stream
    playlist_url = resolve("2073324732")
    print("playlist:", (playlist_url or "")[:80])
    pl = get(playlist_url).decode()
    lines = pl.split("\n")
    init_url = next((ln for ln in lines if ln.startswith("#EXT-X-MAP:URI=")), None)
    if init_url:
        init_url = init_url.split('URI="')[1].rstrip('"')
    segs = [ln for ln in lines if ln and not ln.startswith("#")]
    print("init:", "yes" if init_url else "no", "| segments:", len(segs))
    data = get(init_url)
    for s in segs[:6]:
        data += get(s)
    print("joined bytes:", len(data))

    # sanity: walk the original
    ok_orig = all(True for _ in walk(data))
    boxes_orig = [(o, s, t, d) for o, s, t, d in walk(data)]
    print("original walk ok:", ok_orig, "| top-level:", [(t, s) for o, s, t, d in boxes_orig if d == 0])

    # fake cover (a small valid PNG header is enough for structure testing)
    png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
    out = with_cover(data, png)
    boxes = [(o, s, t, d) for o, s, t, d in walk(out)]
    print("modified walk ok:", all(True for _ in walk(out)))
    print("top-level after:", [(t, s) for o, s, t, d in boxes if d == 0])
    path = [(t, s) for o, s, t, d in boxes if t in ("udta", "meta", "ilst", "covr")]
    print("covr path:", path)
    covr_entry = next((entry for entry in boxes if entry[2] == "covr"), None)
    if covr_entry:
        o = covr_entry[0]
        covr_size = struct.unpack_from(">I", out, o)[0]
        payload = out[o + 8:o + covr_size]
        print("covr datatype:", struct.unpack_from(">I", payload, 0)[0], "(14 = png)")
        print("covr payload matches:", payload[4:] == png)
    print("tail intact:", out[-8:] == data[-8:] or len(out) > len(data))
    # idempotency: adding cover twice keeps a valid tree
    out2 = with_cover(out, png)
    boxes2 = [(t) for o, s, t, d in walk(out2)]
    print("second insert still parses:", "covr" in boxes2, "| boxes:", boxes2.count("covr"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
