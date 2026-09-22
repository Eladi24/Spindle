# Local Music Player with BluOS Streaming — Project Plan

**Status:** v4 — Phase 0 and Phase 1 complete (local playback, working
end-to-end on-device; see `PHASE1.md` for the two items left postponed —
accessibility pass, S25+ full-library run). Phase 2 is functionally built:
`MediaHttpServer`, `TokenRegistry`, `BluOsClient`, `BluOsDiscovery`,
`NodeOutput`, `AudioOutputSwitcher`, and the `OutputPickerSheet` UI are all
in place and wired together. **None of it has been exercised against a
real Node yet** — that's the next step, and several deliberately-deferred
or best-guess pieces (noted throughout CLAUDE.md's Phase 2 sections) get
confirmed or fixed there.
Protocol detail: `bluos-api.md`. Design/decision log: `CLAUDE.md`'s dated
Phase 2 entries.
**Target device:** Galaxy S25+ (primary), Galaxy A73 (secondary test device)
**Target player:** Bluesound Node (BluOS), LAN only

---

## 1. What the app is

An Android music player that indexes music stored on the phone and plays it either
on the phone itself or on a BluOS player over WiFi at full original quality.

**The core insight:** the app is a *library browser and remote control*, not a
playback engine. When the Node is the output, the Node does the decoding. The app
serves the file over HTTP and issues commands.

### Naming constraint

Lenbrook's BluOS API Use Policy prohibits using their name or marks in an
integration's name or logo.

- Acceptable: `Resonance (works with BluOS)`
- Not acceptable: `Resonance – BluOS`, `BluOS Streamer`, `Node Player`

Pick a neutral name now; it appears in the package ID and is painful to change later.

---

## 2. Capabilities

### v1.0 (Play Store release)

| Area | Capability |
|---|---|
| Library | Scan via MediaStore **and** user-picked folders via SAF (see §5.1) |
| Library | Read tags directly from files, not only from Android's index |
| Browse | Artists → Albums → Tracks; Folders view; Genres; All Tracks |
| Browse | Search across artist / album / title |
| Playlists | Create, edit, reorder, delete; import/export M3U |
| Queue | Add, reorder, remove, clear; shuffle; repeat off/all/one |
| Output | Switch between **This phone** and a discovered BluOS player |
| Node | Auto-discovery on LAN, plus manual IP entry as fallback |
| Node | Transport controls, volume, seek (confirmed working), now-playing metadata |
| Quality | Bit-perfect passthrough — no transcoding, no resampling |
| Background | Playback continues with screen off / app backgrounded |
| Integration | Handles `ACTION_SEND` / `ACTION_VIEW` for audio MIME types |

### Explicitly out of scope for v1

- YouTube / YouTube Music (incompatible with Play Store distribution)
- Audio capture of other apps (quality loss, latency, Spotify blocks it)
- Multi-room grouping (the API supports it; defer to v1.1)
- Cloud sync, scrobbling, lyrics, equalizer

---

## 3. Tech stack

### Environment

| | |
|---|---|
| IDE | Android Studio (latest stable) |
| Language | Kotlin 2.x |
| Build | Gradle Kotlin DSL + version catalog (`libs.versions.toml`) |
| VCS | Git + GitHub (private until release) |
| minSdk | 26 (Android 8.0) |
| targetSdk | Latest stable |
| Testing devices | S25+ and A73, both physical. **The emulator cannot be used for Node testing** — it sits behind NAT and the Node can't reach it. |

### Libraries

| Concern | Choice | Note |
|---|---|---|
| UI | Jetpack Compose + Material 3 | |
| Architecture | MVVM, ViewModel + StateFlow | Unidirectional data flow |
| DI | Hilt | Koin is gentler to learn; Hilt is the industry default |
| Async | Coroutines + Flow | |
| Database | Room | Library cache, playlists, settings |
| HTTP server | **Ktor server (CIO engine)** | Must support HTTP Range requests |
| HTTP client | Ktor client | Same library, one less thing to learn |
| XML parsing | `XmlPullParser` (built-in) | BluOS responses are XML, not JSON |
| Local playback | Media3 / ExoPlayer | Only for the "This phone" output |
| Tag reading | `MediaMetadataRetriever` first; JAudiotagger (Android fork) as fallback | Needed for formats Android's scanner mishandles |
| Artwork | Coil | |
| Discovery | `NsdManager` (built-in) → jmDNS fallback | Services: `_musc._tcp`, `_musp._tcp` |
| Logging | Timber | |
| Testing | JUnit, Turbine (Flow), Compose UI tests | |

**Not using React Native.** The core of this app is a background HTTP server and
precise network state management — exactly what RN handles worst. Kotlin also gives
you the more transferable skill.

---

## 4. Project structure

Single Gradle module to start, but packaged so it could be split into
`:core`, `:data`, `:playback`, `:ui` modules later without moving logic around.

```
app/src/main/
├── AndroidManifest.xml
├── res/
└── java/com/<you>/<appname>/
    │
    ├── App.kt                        # @HiltAndroidApp
    ├── MainActivity.kt               # single activity, Compose host
    │
    ├── di/
    │   ├── AppModule.kt
    │   ├── DatabaseModule.kt
    │   └── NetworkModule.kt
    │
    ├── core/
    │   ├── model/                    # Track, Album, Artist, Playlist,
    │   │                             #   QueueItem, PlaybackState, BluOsPlayer
    │   └── util/                     # extensions, Result wrapper, formatters
    │
    ├── data/
    │   ├── library/
    │   │   ├── MediaStoreScanner.kt  # Android's index
    │   │   ├── SafFolderScanner.kt   # NOT in v1 - see §5.1
    │   │   ├── TagReader.kt          # reads tags from the file itself
    │   │   ├── ArtworkExtractor.kt
    │   │   └── LibraryRepository.kt  # merges + deduplicates both sources
    │   │
    │   ├── db/
    │   │   ├── AppDatabase.kt
    │   │   ├── dao/                  # TrackDao, PlaylistDao, FolderDao
    │   │   └── entity/
    │   │
    │   ├── bluos/
    │   │   ├── BluOsDiscovery.kt     # NSD, _musc._tcp / _musp._tcp
    │   │   ├── BluOsClient.kt        # HTTP GETs, XML parsing
    │   │   ├── BluOsStatusPoller.kt  # LONG-POLL with etag — see §5.2
    │   │   └── dto/                  # StatusResponse, SyncStatusResponse...
    │   │
    │   ├── server/
    │   │   ├── MediaHttpServer.kt    # Ktor CIO, Range support
    │   │   ├── TokenRegistry.kt      # opaque token → file URI (don't expose paths)
    │   │   └── NetworkAddress.kt     # find the WLAN IP to advertise
    │   │
    │   └── prefs/
    │       └── SettingsRepository.kt # DataStore
    │
    ├── playback/
    │   ├── AudioOutput.kt            # ← the key abstraction
    │   ├── LocalOutput.kt            # Media3 implementation
    │   ├── NodeOutput.kt             # HTTP server + BluOS implementation
    │   ├── QueueManager.kt           # shuffle / repeat / next / prev — app-side
    │   ├── PlaybackController.kt     # coordinates queue + output
    │   └── PlaybackService.kt        # MediaSessionService, foreground
    │
    └── ui/
        ├── theme/
        ├── navigation/AppNavHost.kt
        ├── components/               # TrackRow, AlbumGrid, OutputChip...
        ├── library/                  # ArtistsScreen, AlbumsScreen, TracksScreen,
        │                             #   FoldersScreen + one ViewModel each
        ├── nowplaying/
        ├── queue/
        ├── playlists/
        ├── search/
        └── settings/                 # folder picker, output picker, rescan
```

### The central abstraction

```kotlin
interface AudioOutput {
    val state: StateFlow<PlaybackState>
    suspend fun play(item: QueueItem)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
    suspend fun seek(seconds: Int)
    suspend fun setVolume(percent: Int)
    val capabilities: OutputCapabilities   // canSeek, canSetVolume, isGapless
}
```

`LocalOutput` wraps ExoPlayer. `NodeOutput` wraps the HTTP server plus `BluOsClient`.
`PlaybackController` holds a `QueueManager` and one active `AudioOutput`, and knows
nothing about which is which. Switching outputs mid-track is then a small, testable
operation rather than a rewrite.

`capabilities` matters because the two outputs genuinely differ — the Node's seek
support is reported per-source via `canSeek` in `/Status`, and gapless is only
possible locally.

---

## 5. Key design decisions — Phase 0 complete

**Phase 0 finished 2026-09-19.** Every mechanism below was measured against the
real Node with `probe.py`. Protocol detail lives in `bluos-api.md`; this section
records only what it means for the app.

| Question | Answer | Consequence |
|---|---|---|
| Can the Node queue custom URLs? | **No.** `/Add?url=` doesn't exist | App owns the queue |
| Can it seek? | **Yes**, MP3 and FLAC, `canSeek=1` | Real progress bar + scrubbing |
| Does it know track length? | **Yes**, `<totlen>` | Progress bar is accurate |
| Can we set what it displays? | **Yes**, via `icy-name` only | One line: "Artist — Title" |
| Does long-polling work? | **Yes**, returns on change | Sync layer is viable |
| What signals track end? | `state == "stop"` | Auto-advance trigger |
| Does FLAC differ from MP3? | **No** | No format-specific code |
| Inter-track gap | **2.1–2.5s** | Not gapless; acceptable |
| Is MediaStore healthy? | **Yes** — 3906 tracks, 431 folders, no `.nomedia` | MediaStore is the primary scanner |

Two traps that must be designed for, not discovered:

1. **The Node overshoots EOF when seeking.** It computes a byte range from the
   bitrate and asks past the end of the file. The HTTP server must clamp to
   `size-1`, or seeking near the end of a track returns 416 and playback dies.
2. **`state == "stop"` is ambiguous** — it means both "track ended" and "user
   pressed stop in the BluOS app". Compare the last known `secs` against
   `totlen` to tell them apart, or the app will restart music the user stopped.

### Superseded detail (kept for reference)

### 5.1 Library scanning — RESOLVED

**Musicolet found 3906 tracks in 431 folders with no `.nomedia` files.**
MediaStore is healthy. The BluOS Controller's failure to see phone music is its
own limitation — Bluesound's documentation points users at Bluetooth for
phone-stored files, and their support forum confirms the app cannot play them.

So `MediaStoreScanner` is the **primary and only** scanner for v1.
`SafFolderScanner` drops out of Phase 1 entirely; keep the interface so it can be
added if a file ever turns up missing, but don't build it.

The app's value is not fixing Android's index. It is doing what BluOS doesn't
offer at all: phone-local files streamed over WiFi at full quality.

*Still worth checking once: compare 3906 against a recursive file count of the
music folders. If it's meaningfully short, revisit this.*

### 5.1b Original reasoning (superseded)

Requirement: find files that other apps miss.

Design for two independent scanners, merged and deduplicated:

1. **MediaStoreScanner** — fast, gives you Android's index for free.
2. **SafFolderScanner** — the user grants a folder tree via
   `ACTION_OPEN_DOCUMENT_TREE`, and the app walks it itself, reading tags directly
   from each file. This catches anything Android's MediaScanner skipped:
   `.nomedia` folders, files with unusual tags, formats the scanner ignores, files
   copied over MTP that were never rescanned, SD-card content.

**Do not use `MANAGE_EXTERNAL_STORAGE` (All Files Access).** Google restricts it to
file managers, backup and antivirus apps; a music player will be rejected. SAF is
the sanctioned route and works fine.

*Open question: what exactly is being missed today? See the questions in chat — the
answer determines how much of the app's value lives in this component.*

### 5.2 Status synchronisation

The API allows at most one `/Status` request per 30 seconds without long-polling.
With long-polling (`/Status?timeout=100&etag=<previous>`), the request stays open
and returns the moment anything changes.

Consequence: `BluOsStatusPoller` runs a long-poll loop in a coroutine, feeding a
`Flow<BluOsStatus>`. Track-end detection, external-change detection (someone used
the BluOS app), and progress sync all derive from that one flow.

Note: the spec says `secs` (playback position) does **not** affect the etag, so the
UI must interpolate the progress bar locally between responses rather than expecting
a push per second.

### 5.3 Queue architecture — CONFIRMED by Phase 0

Since `/Play?url=` produces a `<streamUrl>` source, the Node reports that
next/previous are unavailable and shuffle/repeat are meaningless. So:

- **`QueueManager` lives entirely in the app.** Shuffle and repeat are the app's
  logic; never call `/Shuffle` or `/Repeat`.
- On track end (detected via the status flow), the app issues the next
  `/Play?url=`.
- Expect a short gap between tracks. Gapless is not achievable on this path.
- Seek: `/Play?seek=<seconds>` works only when `/Status` reports `<totlen>` and
  `canSeek=1`. Must be tested for custom stream URLs.

*If Phase 0 shows `/Add?url=` works undocumented, revisit all of the above — the
Node would hold the queue and gapless might come free.*

### 5.4 Metadata on the Node's display — SOLVED

Without ICY headers the Node shows nothing — `/Status` has no `<title1>` at all.

Sending `icy-name` as a response header puts that text on the Node's display and
into `<title1>`. **Only `icy-name` is read** — `icy-description` and the rest are
ignored. So `MediaHttpServer` emits one line per track:

```
icy-name: Jimi Hendrix — Angel
```

Per-track values work; the display updates as the queue advances.

### 5.5 Security of the embedded server

The HTTP server exposes files on the LAN. Mitigations:

- Serve only opaque tokens (`/t/{uuid}`), never real file paths, so path traversal
  is impossible by construction.
- Tokens expire and are scoped to the current queue.
- Bind to the WLAN interface only.
- Run the server only while a Node output session is active.
- `networkSecurityConfig` permitting cleartext to private address ranges only.

---

## 6. Phases

### Phase 0 — Probe ✅ COMPLETE (2026-09-19)
Every mechanism verified against real hardware with `probe.py`. Final sweep:
4/4 files started, seeked and ended correctly across MP3 and FLAC.
Findings in `bluos-api.md`; consequences in §5 above.

### Phase 1 — The player, offline ← **CURRENT**
MediaStore scanning (SAF dropped, see §5.1), Room cache, browse hierarchy,
search, playlists, queue, shuffle/repeat, `LocalOutput` via Media3, foreground
service, Compose UI. **No networking at all.**

Full task breakdown: **`PHASE1.md`**.

### Phase 2 — `NodeOutput`
Discovery, `BluOsClient`, `MediaHttpServer` with Range support, output switcher.
Verify a 24/192 FLAC reports as hi-res in the BluOS app's now-playing view.

### Phase 3 — Robustness
Long-poll status sync, auto-advance between tracks, external change detection,
WiFi lock, IP change recovery, reconnection, screen-off behaviour, Samsung battery
optimisation guidance.

### Phase 4 — Polish and release prep
Share-sheet intents, artwork caching, sorting options, empty/error states,
accessibility, privacy policy, store listing, closed testing.

### Phase 5 — Later
Multi-room grouping, presets, M3U import/export improvements, tablet layout.

---

## 7. Play Store checklist

| Item | Note |
|---|---|
| Developer account | $25 one-time |
| **Closed testing** | Personal accounts created after 13 Nov 2023 must run a closed test with **at least 12 testers opted in continuously for 14 days** before applying for production access. Organization accounts are exempt but need a D-U-N-S number. Recruit 15+ for buffer — the clock resets if you drop below 12. |
| Privacy policy | Required by Google **and** by Lenbrook's API Use Policy |
| Data safety form | App collects nothing; declare accordingly |
| Foreground service | Declare `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and the FGS type; Android 14+ requires justification |
| Permissions | `READ_MEDIA_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`. **Not** `MANAGE_EXTERNAL_STORAGE` |
| Naming | No BluOS/Bluesound in app name, icon, or listing title |
| ⚠️ Lenbrook policy | The API terms include "You will not access our APIs to compete with our Service." A third-party controller app is arguably in tension with that. Worth an email to Lenbrook before release. |

Start the closed test as early as Phase 4 — the 14-day clock is wall-clock time you
can't compress.

---

## 8. BluOS endpoint cheat sheet

Base: `http://<player-ip>:11000/`  · all GET · all responses XML

| Endpoint | Purpose |
|---|---|
| `/Status?timeout=100&etag=<e>` | Long-poll playback state. Key fields: `state`, `secs`, `totlen`, `canSeek`, `streamUrl`, `song`, `pid`, `title1/2/3`, `quality`, `streamFormat`, `image` |
| `/SyncStatus?timeout=100&etag=<e>` | Player identity, name, volume, grouping |
| `/Play` | Resume (works from `pause`, not from `stop`) |
| `/Play?url=<urlencoded>` | Play a custom stream URL ← the core mechanism |
| `/Play?seek=<secs>` | Seek, if `canSeek=1` |
| `/Pause` , `/Pause?toggle=1` , `/Stop` | |
| `/Skip` , `/Back` | Queue only — unavailable when `streamUrl` is present |
| `/Volume?level=0..100` | Also `?db=`, `?abs_db=`, `?mute=0\|1` |
| `/Playlist?length=1` | Queue status; `?start=&end=` for pagination |
| `/Clear` , `/Delete?id=` , `/Move?new=&old=` , `/Save?name=` | Queue management |
| `/Presets` , `/Preset?id=` | User presets |
| `/Browse?key=` | Browse services and the Node's own library |

Discovery: mDNS `_musc._tcp` (players) and `_musp._tcp` (secondary nodes).
Lenbrook also ships LSDP, a UDP broadcast protocol on port 11430, because mDNS is
unreliable on some home networks — a possible fallback if NSD proves flaky.

Full spec: BluOS Custom Integration API v1.7 (bluos.io)
