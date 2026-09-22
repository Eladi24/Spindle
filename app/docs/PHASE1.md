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
- [ ] Manual "rescan" action in Settings — **not built.** Rescans currently only
      happen on permission grant, on a folder-exclusion toggle, and via the
      `ContentObserver`. Add an explicit button if a stuck/stale scan ever
      needs a manual kick.
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
- [ ] Fast-scroll or alphabet index (3900 tracks is a lot of scrolling) — **not
      built.**
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
- [ ] Lock screen controls — not explicitly tested
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
- [ ] M3U import and export — not built

## 11. Polish before Phase 2 — in progress

- [x] Custom color scheme — fixed indigo/violet Material 3 scheme replacing
      the stock template's dynamic-color-only setup (see CLAUDE.md's "Color
      scheme" section); dynamic color is still available but off by default
- [ ] Rotation and process-death state restoration — untested
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
