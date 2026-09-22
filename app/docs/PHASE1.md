# Phase 1 — the player, offline

Goal: a usable local music player on the phone, with **no networking at all**.
Ends with something you'd actually use on headphones. The Node arrives in Phase 2.

Work top to bottom. Each task should end with the app building and running.

---

## 1. Project setup ✅ COMPLETE

- [x] New project, Empty Compose Activity, Kotlin, minSdk 26
- [x] Package name without "bluos"/"bluesound" (see CLAUDE.md)
- [x] Version catalog (`gradle/libs.versions.toml`) for every dependency
- [x] `gradle.properties`: parallel, caching, configuration-cache, `-Xmx4096m`
- [x] Hilt with **KSP**, `@HiltAndroidApp`
- [x] Timber, initialised in `App.kt`
- [x] Git repo, `.gitignore`, first commit
- [ ] Windows Defender exclusions for the project dir, `~/.gradle`, `~/.android`
      — manual step, needs an elevated shell; not something an agent can do.

**Check:** clean build under ~60s incremental — done, ~1-2s cached. ~~app
launches showing "Hello"~~ — superseded, app now launches into the real UI.

## 2. Domain model ✅ COMPLETE

- [x] `core/model/`: `Track`, `Album`, `Artist`, `Playlist`, `QueueItem`,
      `PlaybackState`, `OutputCapabilities` — plus `Folder` and `TrackSort`,
      added when the folder-exclusion feature and track sorting landed.
- [x] `Track` holds a content URI, not a file path
- [x] `playback/AudioOutput.kt` and `MediaSource.kt` interfaces (see CLAUDE.md)

`core/model` stays pure Kotlin, no Android imports.

## 3. Room ✅ COMPLETE

- [x] `AppDatabase`, Room with **KSP**
- [x] Entities: `TrackEntity`, `AlbumEntity`, `ArtistEntity`, `PlaylistEntity`,
      `PlaylistTrackCrossRef` — plus `FolderEntity` (folder exclusion)
- [x] DAOs returning `Flow<List<…>>` and `PagingSource` where lists are long
- [x] Indices on `artistId`, `albumId`, and a sort-name column
- [x] Migration strategy decided now (destructive is fine pre-release) — schema
      is at v3 (folder exclusion, then `dateAddedMs` each bumped it)

**Check:** done — `TrackDaoTest` inserts and queries 5000 rows.

## 4. MediaStore scanning ✅ mostly complete

- [x] `READ_MEDIA_AUDIO` permission with a rationale screen (and the pre-33
      `READ_EXTERNAL_STORAGE` fallback)
- [x] `MediaStoreScanner`: one cursor query, batched insert into Room
- [x] Off the main thread, progress reported as a `Flow`
- [x] `LibraryRepository` exposes library data from Room only
- [x] Manual "rescan" action — a refresh icon on the Manage Folders screen's
      TopAppBar (`FoldersScreen`), backed by `FoldersViewModel.rescanLibrary()`
      / `isScanning`. Shares the same underlying scan as permission-grant and
      folder-exclusion-toggle.
- [x] `ContentObserver` on the audio collection to detect new files

**Extra, not in the original plan:** per-folder library exclusion — see
CLAUDE.md's "Library scanning — folder exclusion" section. This is what
keeps WhatsApp voice notes etc. out, and is now a permanent feature.

**Check:** verified on the A73 (secondary device) at small scale — 177 tracks
scanned in 28-63ms, well under budget. **Not yet verified against the full
~3900-track library on the S25+** — the count-vs-Musicolet comparison from
Phase 0 is still open.

## 5. Browse UI — mostly complete

- [x] Navigation: bottom bar — Tracks / Artists / Albums / Folders / Playlists
      (Navigation Compose, real back stack, `AppNavHost`)
- [x] `ArtistsScreen` → `ArtistDetail` (albums) → `AlbumDetail` (tracks)
- [x] `LazyColumn` / `LazyVerticalGrid` with **stable keys**, paged from Room
      (Tracks, Albums, Artists all paged; per-artist/album/folder track lists
      use plain `Flow<List<T>>` since those are naturally small)
- [x] Coil for artwork, with a placeholder; never decode art on the main thread
- [x] Fast-scroll alphabet index — a draggable A-Z/# rail on the right edge of
      `TracksScreen`, title-sort only. `TracksViewModel.sectionIndex` derives
      anchors from the unpaged title-ordered list; the Paging3 config now runs
      with `enablePlaceholders = true` and a `jumpThreshold` so a scrub jump
      loads pages around the target instead of paginating through everything
      in between.
- [x] Sort options — **Tracks only** (title / date added / year). Albums and
      Artists still sort by name only; add more if it turns out to matter.
- [x] Empty states (e.g. "No tracks yet") and a loading state for first scan
      (full-screen "Scanning your library…")

**Check:** not yet done on the S25+ or against the full library — only tested
against the A73's small local set so far.

## 6. Search ✅ COMPLETE

- [x] Single search field across artist / album / title
- [x] `LIKE` queries with the existing sort-key/name indices — fine at this
      library size; revisit FTS only if it's ever measurably slow
- [x] Debounced input (250ms), results grouped by type (Artists/Albums/Tracks)

Reachable via a search icon on every top-level screen's TopAppBar
(Tracks/Artists/Albums/Folders/Playlists), not a bottom tab — no room left
in the 5-tab bar. `SearchViewModel` combines three debounced DAO queries;
tapping an artist/album navigates to its detail screen, tapping a track
plays the search results as the queue.

## 7. Queue and playback logic ✅ COMPLETE

- [x] `QueueManager`: list, current index, shuffle (Fisher-Yates over an index
      list, not the source list), repeat off/all/one, next/previous, move, remove
- [x] Shuffle must be reversible — keep the original order
- [x] `PlaybackController` holding a `QueueManager` and one `AudioOutput`
- [x] Unit tests for `QueueManager` — this is pure logic, test it properly

**Check:** done — 15 tests, including shuffle+repeat-all wrap-around,
repeat-one, and shuffle pinning the currently-playing track to position 0.

## 8. LocalOutput — mostly complete, some checks outstanding

- [x] `LocalOutput` wrapping Media3 `ExoPlayer`
- [x] `PlaybackService` as a `MediaSessionService`, foreground
- [x] `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + FGS type in the manifest
- [x] Media notification (`MediaStyleNotificationHelper`) — confirmed the
      notification posts with correct title/artist and no crash; the
      transport buttons on the notification/lock screen itself haven't been
      explicitly tap-tested
- [ ] Audio focus, becoming-noisy — wired via `ExoPlayer.Builder` (
      `setAudioAttributes(..., handleAudioFocus = true)`,
      `setHandleAudioBecomingNoisy(true)`) but **not tested with real
      headphones**
- [x] Lock screen controls — tested on-device (A73), found and fixed a real
      bug: no skip button, and "previous" just restarted the current track.
      Root cause and fix in CLAUDE.md's 2026-09-22 entry
      (`QueueAwareForwardingPlayer` + `MediaSession.Callback`). Verified via
      `dumpsys media_session` (actions bitmask now advertises
      `SKIP_TO_NEXT`), hardware media-key simulation (correctly walked the
      real queue both directions), and a screenshot of the actual lock
      screen widget now showing the skip button. One sub-case — tapping the
      widget's own on-screen buttons rather than a hardware key — wasn't
      conclusively verified by adb (a synthetic tap didn't register on the
      secure keyguard surface, likely a testing-tool limit, not an app bug);
      worth one real fingertip tap to fully close out.
- [x] `capabilities` reports `canSeek=true, isGapless=true`

**Check:** confirmed plays with the screen locked and survives navigating away
from the app, across many test cycles on the A73. Headphone-unplug-pauses is
untested (no headphones were plugged in during testing).

## 9. Now Playing ✅ COMPLETE

- [x] Artwork, title/artist/album, progress bar with scrubbing
- [x] Play/pause/next/previous, shuffle and repeat toggles
- [x] Queue screen: reorder by drag (smooth via `animateItem()`, half-item
      hysteresis), remove, jump to track — reachable from a queue icon on
      Now Playing
- [x] Progress interpolated locally (`positionMs` + elapsed since
      `capturedAtMs`) — matters in Phase 2, habit is already built

**Extra, not in the original plan:** volume control (mute toggle + slider).
`AudioOutput.setVolume()` already existed for Phase 2's Node output; this
just adds the UI and an app-side `StateFlow<Int>` to track the last-set level
(neither `AudioOutput` nor the future Node have a way to report it back).

## 10. Playlists — core done, import/export still open

- [x] Create, delete, rename
- [x] Add track / album / artist to playlist — long-press a track anywhere
      (`TrackActionsSheet`) or use the playlist-icon in Album/Artist detail's
      TopAppBar for the whole album/artist; both open `AddToPlaylistSheet`
- [x] Reorder within a playlist — same drag-to-reorder pattern as QueueScreen
- [x] M3U import and export — export via SAF `CreateDocument` from a
      playlist's TopAppBar (`PlaylistRepository.exportM3u`); import via SAF
      `OpenDocument` from the Playlists screen, creating a new playlist
      (`PlaylistRepository.importM3u`). Tracks have no filesystem path (see
      `Track.uri`), so exported rows carry the content URI — exact round-trip
      back into Spindle — plus an `#EXTINF` artist/title line used as a
      title-search fallback when importing a file from elsewhere or after a
      stale URI.

## 11. Polish before Phase 2 — in progress

- [x] Custom color scheme — fixed indigo/violet Material 3 scheme replacing
      the stock template's dynamic-color-only setup (see CLAUDE.md's "Color
      scheme" section); dynamic color is still available but off by default
- [x] Rotation — tested on-device (A73) via adb (`user_rotation`). No crash,
      queue/position survived two Activity recreations without interruption.
      Surfaced and fixed a real bug: Now Playing's artwork sized itself off
      screen *width*, which is the long edge in landscape, and blew up past
      the screen over the controls — see CLAUDE.md's 2026-09-22 entry.
      Landscape now has a secondary, not-yet-fixed rough edge: in a short
      landscape window, title/artist can end up needing a scroll to see
      instead of showing directly — correctness is fine (nothing crashes or
      silently overlaps), but the layout wants a real landscape-specific pass
      (side-by-side art + text) rather than another size tweak.
- [ ] Process-death state restoration — **inconclusive.** `adb shell settings
      put global always_finish_activities 1` (the standard technique) did not
      actually destroy the Activity on this Samsung build while a foreground
      service was active (same PID throughout, no destroy/create pair in
      logcat) — so `SavedStateHandle`/back-stack restoration through a real
      kill is still unverified. Did surface a real, evidenced finding along
      the way: Samsung's "Freecess" background-app-freeze
      (`FreecessHandler: freeze io.github.eladimany.spindle`, ~6s after
      backgrounding) paused playback while backgrounded despite the correctly
      declared foreground service — confirmed via `dumpsys media_session`
      showing `state=PAUSED` at a position matching the freeze timestamp.
      This is a device battery-optimization setting, not an app bug: the fix
      is exempting Spindle from battery restrictions (Settings → Apps →
      Spindle → Battery → Unrestricted), not a code change.
- [ ] Accessibility: content descriptions, touch targets, TalkBack pass — icon
      buttons have `contentDescription`s but no dedicated pass has been done
- [ ] Crash-free run through every screen on both devices — extensively
      exercised on the **A73 only**; the S25+ (primary target device) hasn't
      been touched yet

---

## Done when

You've used the app as your daily player for a week on headphones without
reaching for Musicolet. Then start Phase 2.

## Deliberately not in Phase 1

SAF folder scanning (MediaStore is healthy — revisit only if the count comes up
short), any networking, the Node, ICY, discovery, YouTube.
