# BluOS protocol notes — measured, not assumed

Everything here was verified against a real Bluesound Node on 2026-09-19 using
`probe.py`. Where this disagrees with intuition, this file is right.

Base URL: `http://<player-ip>:11000/` — all GET, all responses XML.
Official spec: BluOS Custom Integration API v1.7 (bluos.io). It documents a
*subset* of the full protocol.

---

## The core mechanism

```
GET /Play?url=<urlencoded http url>
```

The Node fetches the URL itself and decodes it. Confirmed working for MP3 320 and
FLAC 16/44.1. Response is `<state>stream</state>`.

While playing, `/Status` reports:

```xml
<canSeek>1</canSeek>
<quality>320000</quality>
<secs>19</secs>              <!-- advances once per second -->
<service>http</service>
<state>stream</state>
<streamFormat>MP3 320 kb/s</streamFormat>
<streamUrl>http://10.0.0.11:8080/07 - Angel.mp3</streamUrl>
<totlen>268</totlen>
<title1>…from icy-name…</title1>
```

`<streamUrl>` present means the play queue is NOT the source: next/previous are
unavailable and shuffle/repeat are meaningless.

## There is no way to queue a custom URL

`/Add?url=…` **does not exist.** Tested with `playnow=1` and `where=last` too.
The Node accepts the request, returns `<playlist length="0">`, and ignores it.
`/Add` in the spec is service-scoped (`/Add?service=Deezer&albumid=…`).

**Therefore the app owns the queue.** Play one track, watch for the end, play the
next.

## Seeking — works, with a trap

`/Play?seek=<seconds>` works whenever `canSeek=1`. Verified on MP3 and FLAC.

Measured: requested 187s → landed at 190s. Requested 253s → 255s. Consistently
about +2s, which is buffering, not error.

The Node seeks by issuing a **bounded HTTP Range request** computed from the
bitrate:

```
<- Range: bytes=7484038-10725788      (file is 10707552 bytes)
```

Note the end offset is **past EOF**. The server must clamp it to `size-1` and
return 206. An unclamped server returns 416 and playback stops. This is the
single most likely bug in a hand-rolled server.

Also seen: `Range: bytes=0-` (open-ended) on initial fetch by some clients.

## Metadata on the Node's display — ICY headers

With no ICY headers, `/Status` contains **no `<title1>` at all**; the Node shows
nothing about the track.

Send `icy-name` as a response header and it appears as `<title1>` and
`<twoline_title1>`, and on the Node's display.

**Only `icy-name` is read.** `icy-description`, `icy-genre`, `icy-url`, `icy-br`
were all sent and all ignored — they did not become `title2`/`title3`.

So pack everything into one line:

```
icy-name: Jimi Hendrix — Angel
```

Per-track headers work: changing `icy-name` between tracks updates the display.

## What the Node sends to your server

```
User-Agent: iTunes/4.7.1 (Linux; N; Debian; i386; EN; utf8) MusicServer/0/0
Icy-MetaData: 1
Connection: close
```

Useful for recognising the Node's requests. It closes the connection after each
fetch, so do not rely on keep-alive.

## End of track

When a track finishes, `/Status` collapses to a minimal document:

```xml
<canSeek>0</canSeek> <quality>0</quality> <secs>0</secs> <state>stop</state>
```

`<service>`, `<streamUrl>`, `<totlen>` and `<streamFormat>` **disappear entirely**.

**Trigger for auto-advance: `state == "stop"`.**

⚠️ **Ambiguity:** `state == "stop"` is also what you get when the user presses
stop in the BluOS app. The app cannot distinguish them from state alone. Compare
the last known `secs` against `totlen`:

- last `secs` near `totlen` → track ended → advance
- last `secs` mid-track → user stopped → do not advance

## Long-polling — required

```
GET /Status?timeout=100&etag=<etag from last response>
```

The request blocks until something changes, then returns immediately with a new
etag. Measured: returned after 21.1s when the state changed.

Rules:
- Without long-polling, **at most one request per 30 seconds**.
- Never two requests for the same resource less than 1s apart.
- `<secs>` does **not** affect the etag — interpolate the progress bar locally
  between responses rather than expecting a push per second.
- Socket timeout must exceed the `timeout=` value.

`/SyncStatus?timeout=…&etag=…` works the same way for player identity, name,
volume and grouping.

## Timing

| | |
|---|---|
| `/Play?url=` → audio out | 2.1 – 2.5s (measured over 4 files) |
| Inter-track gap (reactive advance) | ~2 – 2.5s |
| Seek → audio at new position | ~2s |

Gapless is not achievable this way. Possible future mitigation: fire the next
`/Play?url=` a second or two *before* `totlen` is reached, trading a clipped tail
for a shorter silence. Untested.

## Things that will interrupt playback

- **Bluetooth.** A paired phone auto-connecting takes over the Node. `/Status`
  then shows `<serviceType>AudioInputs</serviceType>`, `<title1>Bluetooth</title1>`,
  `<quality>APTX</quality>`. The HTTP stream dies. The app should detect
  `serviceType == "AudioInputs"` and surface it rather than fighting it.
- **Pressing skip** on the Node or in the BluOS app during a URL stream appears
  to **restart the stream** (a second `GET` for the same file). `secs` resets to
  0 without a state change — do not treat that as a new track.

## Discovery

mDNS: `_musc._tcp` (players) and `_musp._tcp` (secondary). Use `NsdManager`;
jmDNS as fallback.

Lenbrook also ship **LSDP**, a UDP broadcast protocol on port 11430, because mDNS
is unreliable on some home networks. Worth implementing as a fallback later.
Always keep manual IP entry in Settings.

## URL encoding

Encode the inner URL with `%20` for spaces. `+` also worked (the Node decoded it),
but `%20` is correct and safer. The Node re-encodes as `%20` when it fetches.

## Endpoint reference

| Endpoint | Notes |
|---|---|
| `/Status?timeout=&etag=` | Long-poll playback state |
| `/SyncStatus?timeout=&etag=` | Player identity, volume, grouping |
| `/Play` | Resume from `pause` (not from `stop`) |
| `/Play?url=` | Play a custom stream URL ← core mechanism |
| `/Play?seek=<secs>` | Seek, when `canSeek=1` |
| `/Pause`, `/Pause?toggle=1`, `/Stop` | |
| `/Volume?level=0..100` | also `?db=`, `?abs_db=`, `?mute=0\|1` |
| `/Skip`, `/Back` | **Unavailable for URL streams** |
| `/Playlist`, `/Clear`, `/Delete?id=`, `/Move?new=&old=`, `/Save?name=` | Queue — not usable for custom URLs |
| `/Presets`, `/Preset?id=` | |

## Legal

Lenbrook's API Use Policy requires a privacy policy, forbids their marks in the
integration's name or logo, and states "You will not access our APIs to compete
with our Service." Consider contacting Lenbrook before a public release.

## Still unverified

- 24-bit/96kHz `<quality>` reporting (no hi-res file available at test time).
  Expect `hd` rather than a bitrate number.
- Whether `/Play?url=` handles very large files or slow networks gracefully.
- Behaviour when the serving device's IP changes mid-stream.
