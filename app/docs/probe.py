#!/usr/bin/env python3
"""
Phase 0 probe for the BluOS / Node streaming project.

Serves a folder of audio files over HTTP (with Range support, which Python's
stdlib SimpleHTTPRequestHandler does NOT provide) and walks through the tests
that decide the app's queue architecture.

Usage:
    python probe.py --node 192.168.1.50 --dir ./testmusic --mp3
    python probe.py --node 192.168.1.50 --dir ./testmusic --flac

Put two .mp3 files and two .flac files in the folder. The script picks the
first two of the chosen format alphabetically, and picks the highest-resolution
FLAC for the hi-res test. Run it once with --mp3 and once with --flac.
"""

import argparse
import http.server
import os
import re
import socket
import socketserver
import sys
import threading
import time
import urllib.parse
import urllib.request
import xml.dom.minidom

PORT = 8080


# --------------------------------------------------------------------------
# HTTP server with Range support
# --------------------------------------------------------------------------

DISCONNECTS = (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)

# Extra response headers to send (used by the ICY metadata test).
EXTRA_HEADERS = {}

# Per-filename ICY headers: {"song.mp3": {"icy-name": "Artist - Title"}}
ICY_BY_FILE = {}


def is_hijacked(xml_text):
    """True if something (Bluetooth, optical...) has taken over the Node."""
    return bool(xml_text) and "<serviceType>AudioInputs</serviceType>" in xml_text


class QuietTCPServer(socketserver.ThreadingTCPServer):
    """Clients dropping mid-stream is normal here, not an error."""
    allow_reuse_address = True
    daemon_threads = True

    def handle_error(self, request, client_address):
        if isinstance(sys.exc_info()[1], DISCONNECTS):
            sys.stderr.write("    [server] client closed the connection (normal)\n")
            return
        super().handle_error(request, client_address)


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    """SimpleHTTPRequestHandler + HTTP Range, so seeking can be tested."""

    def log_message(self, fmt, *args):
        # Seeing the Node's requests is the whole point - keep them, but tidy.
        if "favicon" in getattr(self, "path", ""):
            return
        sys.stderr.write("    [server] %s\n" % (fmt % args))

    def copyfile(self, source, outputfile):
        try:
            super().copyfile(source, outputfile)
        except DISCONNECTS:
            pass  # the Node stopped or skipped; expected

    def send_head(self):
        for h in ("User-Agent", "Range", "Icy-MetaData", "Connection"):
            if h in self.headers:
                sys.stderr.write(f"    [server]   <- {h}: {self.headers[h]}\n")
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            return super().send_head()
        try:
            f = open(path, "rb")
        except OSError:
            self.send_error(404, "Not found")
            return None

        size = os.fstat(f.fileno()).st_size
        ctype = self.guess_type(path)
        range_header = self.headers.get("Range")

        base = os.path.basename(urllib.parse.unquote(self.path.split("?")[0]))
        extra = dict(EXTRA_HEADERS)
        extra.update(ICY_BY_FILE.get(base, {}))

        if not range_header:
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(size))
            self.send_header("Accept-Ranges", "bytes")
            for k, v in extra.items():
                self.send_header(k, v)
            self.end_headers()
            return f

        m = re.match(r"bytes=(\d*)-(\d*)", range_header)
        if not m:
            self.send_error(400, "Bad Range")
            f.close()
            return None

        start = int(m.group(1)) if m.group(1) else 0
        end = int(m.group(2)) if m.group(2) else size - 1
        end = min(end, size - 1)
        if start > end:
            self.send_error(416, "Range not satisfiable")
            f.close()
            return None

        f.seek(start)
        self.send_response(206)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        for k, v in extra.items():
            self.send_header(k, v)
        self.end_headers()
        return f


def guess_types_fix():
    """Android/Windows often lack MIME entries for lossless audio."""
    RangeHandler.extensions_map.update({
        ".flac": "audio/flac",
        ".m4a": "audio/mp4",
        ".alac": "audio/mp4",
        ".wav": "audio/wav",
        ".aiff": "audio/aiff",
        ".ogg": "audio/ogg",
        ".opus": "audio/opus",
    })


def start_server(directory):
    guess_types_fix()
    handler = lambda *a, **kw: RangeHandler(*a, directory=directory, **kw)
    httpd = QuietTCPServer(("0.0.0.0", PORT), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def local_ip_towards(node_ip):
    """The IP of whichever interface actually routes to the Node."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((node_ip, 11000))
        return s.getsockname()[0]
    finally:
        s.close()


def pretty(xml_text):
    try:
        return xml.dom.minidom.parseString(xml_text).toprettyxml(indent="  ")
    except Exception:
        return xml_text


def bluos(node, command, http_timeout=15, quiet=False, **params):
    """http_timeout is the socket timeout. A BluOS long-poll 'timeout=N' goes
    in **params and must be SHORTER than http_timeout.
    quiet=True suppresses the request/response dump (for long sweeps)."""
    url = f"http://{node}:11000/{command}"
    if params:
        # quote_via=quote gives %20 for spaces, not '+'. The Node re-parses this
        # value and issues its own request with it, so form-encoding is wrong here.
        url += "?" + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
    if not quiet:
        print(f"\n  GET {url}")
    try:
        with urllib.request.urlopen(url, timeout=http_timeout) as r:
            body = r.read().decode("utf-8", "replace")
        if not quiet:
            print(pretty(body))
        return body
    except Exception as e:
        print(f"  !! FAILED ({command}): {e}")
        return None


def media_url(pc_ip, filename):
    return f"http://{pc_ip}:{PORT}/{filename}"


def etag_of(xml_text):
    if not xml_text:
        return None
    m = re.search(r'etag="([^"]+)"', xml_text)
    return m.group(1) if m else None


def pause(msg="Press Enter to continue..."):
    try:
        input(f"\n>>> {msg}")
    except (EOFError, KeyboardInterrupt):
        print("\n(no input available - continuing)")


def flac_info(path):
    """Read sample rate / bit depth / duration straight from FLAC STREAMINFO."""
    try:
        with open(path, "rb") as f:
            if f.read(4) != b"fLaC":
                return None
            f.read(4)                       # metadata block header
            d = f.read(34)                  # STREAMINFO payload
        rate = (d[10] << 12) | (d[11] << 4) | (d[12] >> 4)
        chans = ((d[12] >> 1) & 0x07) + 1
        bits = (((d[12] & 0x01) << 4) | (d[13] >> 4)) + 1
        samples = ((d[13] & 0x0F) << 32) | int.from_bytes(d[14:18], "big")
        return {
            "rate": rate,
            "bits": bits,
            "channels": chans,
            "seconds": samples / rate if rate else 0,
        }
    except Exception:
        return None


def describe(directory, name):
    """Human-readable spec line for a test file."""
    if name.lower().endswith(".flac"):
        i = flac_info(os.path.join(directory, name))
        if i:
            return (f"{i['bits']}-bit/{i['rate'] / 1000:g}kHz, "
                    f"{i['channels']}ch, {i['seconds']:.0f}s")
    size = os.path.getsize(os.path.join(directory, name))
    return f"{size / 1_048_576:.1f} MB" if size >= 1_048_576 else f"{size // 1024} KB"


def find_tracks(directory, ext):
    """Pick the first two files with the given extension, case-insensitively."""
    files = sorted(f for f in os.listdir(directory)
                   if f.lower().endswith("." + ext.lower())
                   and os.path.isfile(os.path.join(directory, f)))
    return files


def best_hires(directory):
    """Highest-resolution FLAC in the folder, for the hi-res test."""
    best, best_score, best_info = None, 0, None
    for f in find_tracks(directory, "flac"):
        i = flac_info(os.path.join(directory, f))
        if not i:
            continue
        score = i["rate"] * i["bits"]
        if score > best_score:
            best, best_score, best_info = f, score, i
    return best, best_info


def check_source(xml_text):
    """Warn if the Node is on Bluetooth/optical/etc - readings would be meaningless."""
    if not xml_text:
        return
    svc = re.search(r"<serviceType>([^<]*)</serviceType>", xml_text)
    if svc and svc.group(1) == "AudioInputs":
        name = re.search(r"<title1>([^<]*)</title1>", xml_text)
        print("\n  " + "!" * 60)
        print(f"  WARNING: the Node is on its '{name.group(1) if name else '?'}' input,")
        print("  not playing your stream. canSeek/skip/title readings here describe")
        print("  that input, NOT a URL stream. Switch the Node to any network source")
        print("  in the BluOS app (or just re-run test B) before trusting results.")
        print("  " + "!" * 60)


def section(n, title):
    print("\n" + "=" * 70)
    print(f" TEST {n} - {title}")
    print("=" * 70)


# --------------------------------------------------------------------------
# Tests
# --------------------------------------------------------------------------

def test_a_add(node, pc_ip, track_a, track_b):
    section("A", "Does /Add?url= exist? (THE important one)")
    print("Not in the published spec. If it works, the Node owns the queue")
    print("and the app gets much simpler. Listen for track B following track A.")

    bluos(node, "Clear")
    for f in (track_a, track_b):
        bluos(node, "Add", url=media_url(pc_ip, f))
    print("\n--- queue contents after two /Add calls ---")
    bluos(node, "Playlist")

    print("\nAlso trying documented-looking variants:")
    bluos(node, "Add", url=media_url(pc_ip, track_a), playnow=1)
    bluos(node, "Add", url=media_url(pc_ip, track_b), where="last")
    bluos(node, "Playlist")

    bluos(node, "Play")
    pause("Let track A finish. Did B start automatically? Then press Enter.")
    bluos(node, "Status")
    bluos(node, "Skip")
    print("\nRECORD: did /Playlist list 2 songs? did B auto-follow A? did /Skip work?")


def test_b_play_url(node, pc_ip, track_a):
    section("B", "What does /Play?url= actually report?")
    print("Samples status several times - the Node buffers for a few seconds,")
    print("so the first reading often isn't the settled one.")
    bluos(node, "Stop")
    time.sleep(1)
    t0 = time.time()
    bluos(node, "Play", url=media_url(pc_ip, track_a))

    body = None
    for target in (2, 5, 10, 20):
        wait = target - (time.time() - t0)
        if wait > 0:
            time.sleep(wait)
        print(f"\n--- status at ~{target}s after /Play ---")
        body = bluos(node, "Status")
        check_source(body)
        m = re.search(r"<secs>(\d+)</secs>", body or "")
        if m and int(m.group(1)) > 0:
            print(f"  (playback position is advancing: secs={m.group(1)})")

    print("\nRECORD from the LAST status block above:")
    print("  - is there a <streamUrl> element, and does it hold your URL?")
    print("  - <state> should be 'stream'")
    print("  - <canSeek> and <totlen>  <-- the last open design question")
    print("  - <title1> <title2> <title3>   (what the Node displays)")
    print("  - <quality>, <streamFormat>")
    return body


def test_c_seek(node, pc_ip, track_a):
    section("C", "Seeking")
    print("Seeks to a position far from where playback currently is, so the")
    print("result can't be confused with normal elapsed time.")
    bluos(node, "Stop")
    time.sleep(1)
    bluos(node, "Play", url=media_url(pc_ip, track_a))
    time.sleep(5)

    body = bluos(node, "Status")
    if not body:
        print("  !! no status; aborting")
        return
    before = int((re.search(r"<secs>(\d+)</secs>", body) or [0, 0])[1] or 0)
    totlen = re.search(r"<totlen>(\d+)</totlen>", body)
    totlen = int(totlen.group(1)) if totlen else 0
    can = re.search(r"<canSeek>(\d+)</canSeek>", body)
    print(f"\n  position={before}s  totlen={totlen}s  "
          f"canSeek={can.group(1) if can else '?'}")

    target = int(totlen * 0.7) if totlen > 40 else 120
    print(f"  Seeking to {target}s - listen for the audio to jump.")
    bluos(node, "Play", seek=target)
    time.sleep(4)

    body = bluos(node, "Status")
    after = int((re.search(r"<secs>(\d+)</secs>", body or "") or [0, 0])[1] or 0)
    print(f"\n  position before={before}s  requested={target}s  after={after}s")
    if abs(after - target) <= 12:
        print("  >>> SEEK WORKS. Progress bar and scrubbing are viable.")
    elif after > before + 20:
        print("  >>> position moved a lot but not to the target - partial support.")
    else:
        print("  >>> SEEK DID NOT TAKE EFFECT. Note this - it changes the UI design.")
    print("\n  Also check the server log above for a '206' line: that's the Node")
    print("  re-requesting with a Range header, which is how the seek is served.")


def test_d_track_end(node, pc_ip, track_a):
    section("D", "What happens when a track ends?")
    print("This is the signal your auto-advance logic will key off, so we watch")
    print("for it automatically using long-polling. No need to press anything.")
    print("Use a SHORT track for this one, or it takes as long as the song.")
    bluos(node, "Stop")
    time.sleep(1)
    bluos(node, "Play", url=media_url(pc_ip, track_a))
    time.sleep(3)

    body = bluos(node, "Status")
    state = re.search(r"<state>([^<]*)</state>", body or "")
    state = state.group(1) if state else "?"
    print(f"\n  starting state: {state}  - now waiting for it to change...")

    t0 = time.time()
    deadline = t0 + 420
    while time.time() < deadline:
        tag = etag_of(body)
        body = (bluos(node, "Status", http_timeout=115, timeout=100, etag=tag)
                if tag else None)
        if not body:
            print("  (long-poll returned nothing - falling back to slow polling)")
            time.sleep(10)
            body = bluos(node, "Status")
            if not body:
                break
        new = re.search(r"<state>([^<]*)</state>", body)
        new = new.group(1) if new else "?"
        if is_hijacked(body):
            print("\n  !! INVALID: the Node switched to Bluetooth/an input mid-test.")
            print("     These state changes are the input connecting, NOT the track")
            print("     ending. Unpair the phone from the Node and run this again.")
            return
        if new != state:
            print(f"\n  >>> STATE CHANGED after {time.time() - t0:.0f}s: "
                  f"{state} -> {new}")
            state = new
            if new in ("stop", "pause"):
                print("\n  Final status at end of track:")
                bluos(node, "Status")
                break
    print("\nRECORD: what did <state> become when the track finished?")
    print("That exact value is what your Kotlin auto-advance will trigger on.")


def test_e_longpoll(node):
    section("E", "Long-polling")
    print("The spec forbids polling faster than 30s without this, so the")
    print("whole sync layer depends on it working.")
    body = bluos(node, "Status")
    tag = etag_of(body)
    if not tag:
        print("  !! No etag found - cannot test long-polling.")
        return
    print(f"\n  etag = {tag}")
    print("  Opening a 60s long-poll. It should HANG until something changes.")
    print("  >>> Go press pause/play on the Node or in the BluOS app now. <<<")
    t0 = time.time()
    body = bluos(node, "Status", http_timeout=75, timeout=60, etag=tag)
    elapsed = time.time() - t0
    print(f"\n  Returned after {elapsed:.1f}s")
    new_tag = etag_of(body)
    if elapsed < 2 and new_tag == tag:
        print("  >>> BROKEN: returned instantly with the same etag.")
    elif new_tag != tag:
        print(f"  >>> WORKS: returned on change after {elapsed:.0f}s, new etag.")
    else:
        print("  >>> Timed out with no change - that's correct behaviour if you")
        print("      didn't touch anything. Re-run and press pause on the Node.")


def test_g_icy(node, pc_ip, track_a):
    section("G", "Can we control what the Node DISPLAYS? (ICY metadata)")
    print("Test B showed no <title1>/<title2>/<title3> at all - the Node knows")
    print("the format and length but not the track. Shoutcast-style ICY headers")
    print("are the standard way an HTTP audio stream announces itself, and BluOS")
    print("plays internet radio, so it may well read them. If it does, your app")
    print("can show proper artist/title on the Node instead of nothing.")

    EXTRA_HEADERS.clear()
    EXTRA_HEADERS.update({
        "icy-name": "PROBE-TITLE-12345",
        "icy-description": "PROBE-ARTIST-12345",
        "icy-genre": "PROBE-GENRE",
        "icy-url": "http://example.invalid",
        "icy-br": "320",
        "icy-pub": "0",
    })

    bluos(node, "Stop")
    time.sleep(1)
    bluos(node, "Play", url=media_url(pc_ip, track_a))
    time.sleep(8)
    body = bluos(node, "Status") or ""
    EXTRA_HEADERS.clear()

    hits = [f"<{t}>" for t in ("title1", "title2", "title3", "name")
            if f"<{t}>" in body]
    if "PROBE-" in body:
        print("\n  >>> SUCCESS: the Node picked up the ICY headers.")
        for t in ("title1", "title2", "title3", "name"):
            m = re.search(rf"<{t}>([^<]*)</{t}>", body)
            if m:
                print(f"      {t} = {m.group(1)}")
        print("  Your Ktor server should emit these per track.")
    elif hits:
        print(f"\n  >>> PARTIAL: title fields exist {hits} but not from our headers.")
    else:
        print("\n  >>> NO EFFECT: still no title fields. The Node ignores ICY")
        print("      headers on a /Play?url= stream. Metadata on the Node's own")
        print("      display isn't achievable this way - the app's own UI would")
        print("      carry the now-playing info instead.")
    print("\n  Check the server log above: did the Node send 'Icy-MetaData: 1'?")
    print("  That would mean it actively wants metadata.")


def _secs(body):
    m = re.search(r"<secs>(\d+)</secs>", body or "")
    return int(m.group(1)) if m else -1


def _state(body):
    m = re.search(r"<state>([^<]*)</state>", body or "")
    return m.group(1) if m else "?"


def test_h_autoadvance(node, pc_ip, track_a, track_b):
    section("H", "AUTO-ADVANCE: track A -> track B (your app's core loop)")
    print("This is exactly what NodeOutput + QueueManager will do in Kotlin:")
    print("  play A -> long-poll for the end -> immediately play B.")
    print("To avoid waiting out the whole song, it seeks to near the end of A.")
    print("Listen for track B starting on its own, and note the gap length.\n")

    ICY_BY_FILE.clear()
    ICY_BY_FILE[track_a] = {"icy-name": "TRACK A - should play first"}
    ICY_BY_FILE[track_b] = {"icy-name": "TRACK B - auto-advanced!"}

    bluos(node, "Stop")
    time.sleep(1)
    print(f"--- starting A: {track_a}")
    bluos(node, "Play", url=media_url(pc_ip, track_a))
    time.sleep(5)

    body = bluos(node, "Status")
    if is_hijacked(body):
        print("\n  !! Node is on Bluetooth/an input. Unpair the phone and retry.")
        return
    totlen = re.search(r"<totlen>(\d+)</totlen>", body or "")
    totlen = int(totlen.group(1)) if totlen else 0
    if totlen > 30:
        jump = totlen - 15
        print(f"\n--- A is {totlen}s long; skipping to {jump}s to save time")
        bluos(node, "Play", seek=jump)
        time.sleep(3)
        body = bluos(node, "Status")

    print(f"\n--- waiting for A to end (position {_secs(body)}s of {totlen}s)")
    t0 = time.time()
    ended = False
    deadline = t0 + 180
    while time.time() < deadline:
        tag = etag_of(body)
        if not tag:
            break
        body = bluos(node, "Status", http_timeout=60, timeout=45, etag=tag)
        if is_hijacked(body):
            print("\n  !! HIJACKED mid-test - Bluetooth grabbed the Node.")
            print("     Unpair the S25 from the Node and run this again.")
            return
        st = _state(body)
        print(f"      state={st} secs={_secs(body)}  (t+{time.time() - t0:.0f}s)")
        if st != "stream":
            ended = True
            t_end = time.time()
            print(f"\n  >>> A ENDED after {t_end - t0:.0f}s. state is now '{st}'")
            print(f"      THIS is the trigger value for your Kotlin auto-advance.")
            break

    if not ended:
        print("\n  Timed out waiting for A to end. Inconclusive.")
        return

    print(f"\n--- firing B immediately: {track_b}")
    bluos(node, "Play", url=media_url(pc_ip, track_b))
    for _ in range(10):
        time.sleep(2)
        body = bluos(node, "Status")
        if _state(body) == "stream" and _secs(body) > 0:
            gap = time.time() - t_end
            url = re.search(r"<streamUrl>([^<]*)</streamUrl>", body or "")
            ok = url and track_b in urllib.parse.unquote(url.group(1))
            print(f"\n  >>> B IS PLAYING. Gap between tracks: ~{gap:.1f}s")
            print(f"      streamUrl matches track B: {'YES' if ok else 'NO'}")
            print("\n  This is the full architecture working end to end.")
            print("  That gap is your gapless penalty - note whether it's")
            print("  tolerable, since it's inherent to this approach.")
            ICY_BY_FILE.clear()
            return
    print("\n  >>> B did not start. Something is wrong with the handoff.")
    ICY_BY_FILE.clear()


AUDIO_EXTS = (".mp3", ".flac", ".m4a", ".mp4", ".wav", ".aiff", ".aif",
              ".ogg", ".opus", ".alac", ".wma")


def all_audio_files(directory):
    return sorted(f for f in os.listdir(directory)
                  if os.path.splitext(f)[1].lower() in AUDIO_EXTS
                  and os.path.isfile(os.path.join(directory, f)))


def _tag(body, name, default=None):
    m = re.search(rf"<{name}>([^<]*)</{name}>", body or "")
    return m.group(1) if m else default


def _sweep_report(rows):
    if not rows:
        return
    print("\n" + "=" * 78)
    print(" SWEEP RESULTS")
    print("=" * 78)
    head = f"{'#':>3} {'file':<28} {'fmt':<12} {'len':>5} {'sk':>3} {'seek':>11} {'end':>4}"
    print(head)
    print("-" * 78)
    for r in rows:
        name = r["file"][:27]
        seek = "-"
        if r["seek_to"] is not None:
            seek = (f"{r['seek_to']}->{r['seek_hit']}"
                    if r["seek_hit"] is not None else f"{r['seek_to']}->?")
        print(f"{r['n']:>3} {name:<28} {str(r['fmt'])[:12]:<12} "
              f"{r['totlen']:>5} {str(r['canSeek']):>3} {seek:>11} "
              f"{'yes' if r['ended'] else 'no':>4}")
    print("-" * 78)

    total = len(rows)
    played = sum(1 for r in rows if r["started"])
    seeked = sum(1 for r in rows if r["seek_ok"])
    ended = sum(1 for r in rows if r["ended"])
    print(f"  started OK : {played}/{total}")
    print(f"  seek OK    : {seeked}/{total}")
    print(f"  reached end: {ended}/{total}")
    lat = [r["latency"] for r in rows if r["latency"]]
    if lat:
        print(f"  start latency: min {min(lat):.1f}s  avg "
              f"{sum(lat) / len(lat):.1f}s  max {max(lat):.1f}s")
    bad = [r for r in rows if not r["started"]]
    if bad:
        print("\n  FILES THAT FAILED TO PLAY (check format support):")
        for r in bad:
            print(f"    - {r['file']}")
    formats = {}
    for r in rows:
        formats.setdefault(str(r["fmt"]), 0)
        formats[str(r["fmt"])] += 1
    print("\n  formats seen:", ", ".join(f"{k} x{v}" for k, v in formats.items()))


def test_i_sweep(node, pc_ip, directory, limit=None):
    section("I", "FULL QUEUE SWEEP - every file: 5s head, then last 3s")
    print("Treats the whole folder as an app-managed queue (the Node has none)")
    print("and walks it: play 5s, seek to the last 3s, let it end, advance.")
    print("This is your QueueManager's job, run against every file you have.\n")

    files = all_audio_files(directory)
    if limit:
        files = files[:limit]
    if not files:
        print("  No audio files found.")
        return
    est = len(files) * 13
    print(f"  {len(files)} files queued. Estimated runtime "
          f"~{est // 60}m{est % 60:02d}s. Ctrl+C to stop early.\n")

    ICY_BY_FILE.clear()
    for i, f in enumerate(files, 1):
        ICY_BY_FILE[f] = {
            "icy-name": f"{i}/{len(files)} - {os.path.splitext(f)[0][:55]}"
        }

    rows = []
    bluos(node, "Stop", quiet=True)
    time.sleep(1)

    try:
        for i, f in enumerate(files, 1):
            r = {"n": i, "file": f, "started": False, "totlen": 0,
                 "canSeek": "?", "fmt": "-", "seek_to": None, "seek_hit": None,
                 "seek_ok": False, "ended": False, "latency": None}
            rows.append(r)
            print(f"\n[{i}/{len(files)}] {f}")

            t_fire = time.time()
            resp = bluos(node, "Play", quiet=True, url=media_url(pc_ip, f))
            if resp is None:
                print("   !! no response from the Node - it is unreachable.")
                print("      Check the IP, the network, and that it is powered on.")
                print("      (This is NOT a file format problem.)")
                rows.pop()
                _sweep_report(rows)
                ICY_BY_FILE.clear()
                return

            body = None
            for _ in range(14):
                time.sleep(1)
                body = bluos(node, "Status", quiet=True)
                if is_hijacked(body):
                    print("   !! HIJACKED by Bluetooth/an input - aborting sweep.")
                    print("      Unpair the phone from the Node and rerun.")
                    _sweep_report(rows)
                    ICY_BY_FILE.clear()
                    return
                if _state(body) == "stream" and _secs(body) > 0:
                    r["started"] = True
                    r["latency"] = time.time() - t_fire
                    break

            if not r["started"]:
                print(f"   !! did NOT start (state={_state(body)}) - "
                      f"format probably unsupported")
                continue

            r["totlen"] = int(_tag(body, "totlen", 0) or 0)
            r["canSeek"] = _tag(body, "canSeek", "?")
            r["fmt"] = _tag(body, "streamFormat", _tag(body, "quality", "-"))
            print(f"   playing: {r['fmt']}, {r['totlen']}s, "
                  f"canSeek={r['canSeek']}, started in {r['latency']:.1f}s")

            # let the first 5 seconds play
            while _secs(body) < 5 and time.time() - t_fire < 20:
                time.sleep(1)
                body = bluos(node, "Status", quiet=True)

            if r["totlen"] > 12 and r["canSeek"] == "1":
                r["seek_to"] = r["totlen"] - 3
                print(f"   seeking to {r['seek_to']}s (last 3 seconds)")
                bluos(node, "Play", quiet=True, seek=r["seek_to"])
                time.sleep(2.5)
                body = bluos(node, "Status", quiet=True)
                r["seek_hit"] = _secs(body)
                r["seek_ok"] = abs(r["seek_hit"] - r["seek_to"]) <= 12
                if not r["seek_ok"]:
                    print(f"   !! seek missed: wanted {r['seek_to']}s, "
                          f"got {r['seek_hit']}s")
            else:
                why = ("shorter than 12s" if r["totlen"] <= 12
                       else f"canSeek={r['canSeek']}")
                print(f"   (no seek: {why}; letting it play out)")

            # wait for the track to finish
            t_wait = time.time()
            while time.time() - t_wait < 25:
                tag = etag_of(body)
                if not tag:
                    break
                body = bluos(node, "Status", quiet=True, http_timeout=25,
                             timeout=15, etag=tag)
                if is_hijacked(body):
                    print("   !! HIJACKED mid-track - aborting sweep.")
                    _sweep_report(rows)
                    ICY_BY_FILE.clear()
                    return
                if _state(body) != "stream":
                    r["ended"] = True
                    print(f"   ended after {time.time() - t_wait:.0f}s "
                          f"(state={_state(body)})")
                    break
            if not r["ended"]:
                print("   (did not reach end in time; moving on)")
    except KeyboardInterrupt:
        print("\n  Interrupted - reporting what we have.")

    bluos(node, "Stop", quiet=True)
    ICY_BY_FILE.clear()
    _sweep_report(rows)


def test_f_hires(node, pc_ip, hires, info):
    section("F", "Hi-res passthrough")
    if not hires:
        print("  Skipped - no readable FLAC in the folder.")
        return
    if info and info["rate"] <= 48000 and info["bits"] <= 16:
        print(f"  NOTE: '{hires}' is {info['bits']}-bit/{info['rate'] / 1000:g}kHz,")
        print("  i.e. CD quality or below. This test only proves something with")
        print("  a 24-bit/96kHz+ file. Running it anyway.")
    if info:
        print(f"  Ground truth: {info['bits']}-bit / {info['rate'] / 1000:g} kHz "
              f"/ {info['channels']}ch")
    bluos(node, "Stop")
    bluos(node, "Play", url=media_url(pc_ip, hires))
    time.sleep(4)
    bluos(node, "Status")
    print("\nRECORD: <quality> should be 'hd' for >CD resolution, not a bitrate.")
    print("Cross-check against the ground truth above and against the sample")
    print("rate shown in the BluOS app's now-playing view.")


# --------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--node", required=True, help="Node IP, e.g. 192.168.1.50")
    ap.add_argument("--dir", default=".", help="folder holding the test tracks")
    fmt = ap.add_mutually_exclusive_group()
    fmt.add_argument("--mp3", dest="ext", action="store_const", const="mp3",
                     help="test with the .mp3 files in the folder (default)")
    fmt.add_argument("--flac", dest="ext", action="store_const", const="flac",
                     help="test with the .flac files in the folder")
    ap.add_argument("--only", help="run tests, e.g. c,e,g (default: all)")
    ap.add_argument("--limit", type=int,
                    help="test i: cap how many files the sweep walks")
    ap.add_argument("--log", help="also write all output to this file")
    ap.set_defaults(ext="mp3")
    args = ap.parse_args()

    if args.log:
        class Tee:
            def __init__(self, *streams):
                self.streams = streams

            def write(self, data):
                for s in self.streams:
                    s.write(data)
                    s.flush()

            def flush(self):
                for s in self.streams:
                    s.flush()

        logfile = open(args.log, "w", encoding="utf-8")
        sys.stdout = Tee(sys.__stdout__, logfile)
        sys.stderr = Tee(sys.__stderr__, logfile)
        print(f"(logging to {args.log})")

    directory = os.path.abspath(args.dir)
    found = find_tracks(directory, args.ext)
    if len(found) < 2:
        print(f"ERROR: need at least two .{args.ext} files in {directory}, "
              f"found {len(found)}.")
        print("Folder contains:", ", ".join(sorted(os.listdir(directory))) or "(empty)")
        sys.exit(1)

    track_a, track_b = found[0], found[1]
    if len(found) > 2:
        print(f"Note: {len(found)} .{args.ext} files present; using the first two "
              f"alphabetically.")
    print(f"\nFormat under test: .{args.ext}")
    print(f"  A: {track_a}   ({describe(directory, track_a)})")
    print(f"  B: {track_b}   ({describe(directory, track_b)})")

    hires, hires_info = best_hires(directory)
    if hires:
        print(f"  Hi-res (test F): {hires}   ({describe(directory, hires)})")

    pc_ip = local_ip_towards(args.node)
    start_server(directory)

    print(f"\nChecking the Node is reachable at {args.node}:11000 ...")
    if bluos(args.node, "Status", http_timeout=8, quiet=True) is None:
        print(f"  !! Cannot reach the Node at {args.node}:11000.")
        print("     Check the IP (BluOS app > Settings > Diagnostics), that it")
        print("     is on the same network, and that it is powered on.")
        sys.exit(1)
    print("  Node responded OK.")

    print(f"Serving {directory} on http://{pc_ip}:{PORT}/")
    print(f"Node:    http://{args.node}:11000/")
    print(f"\nSanity check - open this on your PHONE's browser first:")
    print(f"    http://{pc_ip}:{PORT}/{urllib.parse.quote(track_a)}")
    print("If that doesn't play, stop here: it's a firewall or network issue,")
    print("not a BluOS one. Allow Python through Windows Firewall on Private.")
    pause("Sanity check passed? Press Enter to begin.")

    tests = {
        "a": lambda: test_a_add(args.node, pc_ip, track_a, track_b),
        "b": lambda: test_b_play_url(args.node, pc_ip, track_a),
        "c": lambda: test_c_seek(args.node, pc_ip, track_a),
        "d": lambda: test_d_track_end(args.node, pc_ip, track_a),
        "e": lambda: test_e_longpoll(args.node),
        "f": lambda: test_f_hires(args.node, pc_ip, hires, hires_info),
        "g": lambda: test_g_icy(args.node, pc_ip, track_a),
        "h": lambda: test_h_autoadvance(args.node, pc_ip, track_a, track_b),
        "i": lambda: test_i_sweep(args.node, pc_ip, directory, args.limit),
    }

    try:
        for key in (args.only.split(",") if args.only else "abcdefgh"):
            tests[key.strip()]()
            if not args.only:
                pause()
    except KeyboardInterrupt:
        print("\n\nInterrupted. Stopping the Node.")
        try:
            bluos(args.node, "Stop", http_timeout=5)
        except Exception:
            pass
        return

    bluos(args.node, "Stop")
    print("\nDone. Server still running - Ctrl+C to quit.")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
