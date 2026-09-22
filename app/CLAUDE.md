# CLAUDE.md

Android music player. Indexes music on the phone, plays it either on the phone
or on a BluOS network player (Bluesound Node) over WiFi at original quality.

**The app is a library browser + remote control. The Node is the player.**
When output is the Node, the app serves the file over HTTP and the Node decodes it.
Do not write a playback engine for that path.

Protocol facts were measured against real hardware — see `docs/bluos-api.md`.
Trust that file over any assumption about BluOS.

## Stack

Kotlin 2.2.10 · AGP 9.4.1 (built-in Kotlin) · Jetpack Compose + Material 3
MVVM (ViewModel + StateFlow) · Hilt · Coroutines/Flow · Room · Paging 3
Navigation Compose · Media3 (ExoPlayer + Session) · Coil 3 · DataStore
Ktor server (CIO, Phase 2) · Ktor client (Phase 2) · NsdManager (Phase 2)
Timber · minSdk 26 · compileSdk/targetSdk 37

Gradle Kotlin DSL with a version catalog (`libs.versions.toml`).

### Version pins — do not casually bump

These were hit and fixed during Phase 1. Bumping without checking will
reintroduce the same failures.

- **KSP must be ≥ 2.3.x** (the new Kotlin-version-independent line), not the
  `2.2.10-2.0.2` matched-to-Kotlin release. AGP 9's built-in Kotlin conflicts
  with older KSP over who owns `kotlin.sourceSets`, and fails the build with
  *"Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with
  built-in Kotlin"*.
- **Coil pinned to 3.3.0 — do not go past 3.5.x.** Coil 3.6.0 bumped its
  Kotlin stdlib dependency to 2.4.x, which this project's Kotlin 2.2.10
  compiler cannot read (`Class 'kotlin.Unit' was compiled with an
  incompatible version of Kotlin`). Check `io.coil-kt.coil3:coil-android`'s
  transitive `kotlin-stdlib` version before upgrading Coil.
- **`hiltViewModel()` comes from `androidx.hilt.lifecycle.viewmodel.compose`,
  not `androidx.hilt.navigation.compose`.** The navigation-specific package is
  deprecated as of `androidx.hilt:hilt-lifecycle-viewmodel-compose:1.4.0`; the
  unified one works identically inside and outside a `NavHost` (it reads
  `LocalViewModelStoreOwner`, which Navigation Compose already sets per
  destination) and correctly wires `SavedStateHandle` nav args either way.
- **Material3's `TopAppBar` etc. are opted in at the module level**
  (`kotlin { compilerOptions { freeCompilerArgs.addAll(...) } }` in
  `app/build.gradle.kts`), not per-file — every screen uses it.

## Hard constraints — do not violate

1. **Use KSP, never kapt.** For both Hilt and Room.
2. **Never poll `/Status` faster than once per 30s.** Use long-polling
   (`/Status?timeout=100&etag=<last>`). This is an API requirement, not a
   preference.
3. **Never request `MANAGE_EXTERNAL_STORAGE`.** Google rejects music players for
   it. Use `READ_MEDIA_AUDIO` plus SAF (`ACTION_OPEN_DOCUMENT_TREE`).
4. **The HTTP server must support HTTP Range, including bounded ranges, and must
   clamp the end offset to the file size.** The Node overshoots past EOF when
   seeking; an unclamped server returns 416 and playback dies.
5. **The app owns the play queue.** The Node cannot queue custom URLs. Never call
   `/Shuffle`, `/Repeat`, `/Skip` or `/Back` for URL playback — they do nothing.
6. **Never expose real file paths over HTTP.** Serve opaque tokens (`/t/{uuid}`)
   that map to a URI. Tokens expire with the queue.
7. **No "BluOS" or "Bluesound" in the app name, package id, icon or store
   listing.** Lenbrook's API terms forbid it. "(works with BluOS)" in the
   description is allowed.
8. **No localStorage-style assumptions about the Node holding state.** Every
   restart of a stream is app-driven.

## Architecture

Two interfaces decide everything. Keep them clean.

```kotlin
interface AudioOutput {                  // where sound comes out
    val state: StateFlow<PlaybackState>
    val volume: StateFlow<Int>            // 0-100, this output's own source of truth
    val capabilities: OutputCapabilities // canSeek, canSetVolume, isGapless
    suspend fun play(item: QueueItem)
    suspend fun pause(); suspend fun resume(); suspend fun stop()
    suspend fun seek(seconds: Int)
    suspend fun setVolume(percent: Int)
}

interface MediaSource {                  // where audio comes from
    suspend fun resolveUrl(item: QueueItem): String
}
```

`volume` is a `StateFlow`, not a plain getter, because `LocalOutput` needs to push
live updates when the hardware volume buttons are pressed (see below) — a
one-shot getter couldn't represent that.

`PlaybackState` (in `core/model`) is the shared vocabulary both outputs speak:
`Idle`, `Buffering`, `Playing`, `Paused`, `Ended`, `Error`. `Ended` exists
specifically so `PlaybackController` has one clean signal for "advance the
queue" regardless of which output produced it — `Idle` is ambiguous between
"stopped" and "nothing loaded". `Playing.positionMs` is a snapshot as of
`capturedAtMs`; the UI interpolates locally (`positionMs + (now -
capturedAtMs)`) rather than expecting a push every second — this is required
for Phase 2 (BluOS's long-poll doesn't push `secs`), so the habit is already
built into the local-only path.

- `LocalOutput` — Media3/ExoPlayer, phone speaker/headphones. **Built.** Volume
  goes through `AudioManager`/`STREAM_MUSIC` directly, **not** an ExoPlayer-internal
  gain multiplier — a gain on top of system volume is why an app slider can show
  100% while the phone is really at 30%, and can't be pushed any louder. A
  `BroadcastReceiver` on `android.media.VOLUME_CHANGED_ACTION` (not public API,
  but a stable string used this way across the ecosystem) keeps the app's
  slider in sync when the hardware buttons are pressed.
- `NodeOutput` — Ktor HTTP server + BluOS REST. **Not built yet (Phase 2).** Its
  volume will come from `/SyncStatus` polling, unrelated to phone system volume.
- `QueueManager` owns shuffle/repeat/next/previous/move/remove. Shuffle
  permutes an index list over a stable backing list of `QueueItem`s, never the
  list itself, so disabling shuffle restores the exact original order. Fully
  unit tested (`QueueManagerTest`, 15 cases) — no coroutines needed, it's
  synchronous.
- `PlaybackController` holds the `QueueManager` and one active `AudioOutput`
  (injected via Hilt as the `AudioOutput` interface — it does not know it's
  `LocalOutput`), exposes `playbackState` and `queueState` as `StateFlow`, and
  starts `PlaybackService` (foreground) on first playback. Screens that need
  to play something inject `PlaybackController` directly and call
  `playTracks(list, startIndex)` — don't rebuild `QueueItem` wrapping in every
  ViewModel.
- `PlaybackService` (`MediaSessionService`) posts its own notification via
  `MediaStyleNotificationHelper` and calls `startForeground()` synchronously
  in `onCreate()`. **Do not rely on Media3's automatic notification/foreground
  promotion** — it only fires once a `MediaController` connects to the
  session, which never happens in this same-process setup, and
  `startForegroundService()` without a timely `startForeground()` crashes with
  `ForegroundServiceDidNotStartInTimeException` (hit and fixed on-device).

### Drag-to-reorder in a `LazyColumn` (QueueScreen) — two gotchas hit here

- **Never toggle a wrapping composable between `Modifier` and `Modifier.animateItem()`
  based on drag state.** Swapping modifier *chains* (not just a parameter) on an
  ancestor while a child's `pointerInput` gesture is active tears down and
  recreates that child's pointer-input node, cancelling the gesture the instant
  it starts. Always call `animateItem()` unconditionally; vary its
  `placementSpec` (`null` while dragging that item) to disable just the
  placement animation without changing the modifier's identity.
- **The drag handle's touch target must be a real 48dp**, not just the icon's
  natural ~24dp. `detectDragGesturesAfterLongPress` on a bare small `Icon`
  works fine in principle (verified via `adb shell input draganddrop` hitting
  its exact bounds) but is unreliable for an actual finger — wrap it in a
  `Box(Modifier.size(48.dp))`.
- **Same modifier-chain-identity trap, one level up: don't toggle
  `LazyColumn`'s `userScrollEnabled` based on drag state either.** This looked
  like a reasonable idea (stop the list's own scroll from competing with an
  active drag) but flipping it the moment `onDragStart` fires changes the
  *LazyColumn's own* modifier chain while a descendant's `pointerInput` is
  mid-gesture — same class of cancellation as the `animateItem()` issue above.
  Symptom was subtle: worked for a short drag, silently died partway through a
  longer one (more time for the recomposition to actually land mid-gesture).
  Fixed by just not touching `userScrollEnabled` — the drag handle's own
  `pointerInput` consuming the drag is enough. Gave the dragged row a visible
  lifted state (`shadow`, `scale`, `primaryContainer` background, tinted
  handle icon) driven purely by `isDragging`, since there'd been no feedback
  at all before.
- **Compute the swap threshold from a row height measured once at drag start
  (via `LazyListState.layoutInfo`), not a value cached from whichever row
  happened to report it first** (the original bug — a stale/wrong height
  miscalibrates the threshold and reads as "jumpy"). But don't re-read
  `layoutInfo` *inside* the per-swap loop either — a state update to the
  backing list doesn't retroactively fix up `layoutInfo.visibleItemsInfo`
  until the next measure/layout pass, so a loop that re-queries it mid-swap
  is acting on stale offsets and silently swaps the wrong pair. A fast drag
  with few pointer-move events (real flicks, and `adb shell input
  draganddrop` alike) spans several rows per `onDrag` callback, so the swap
  step still needs its own `while` loop over the cached height — a single
  conditional swap per callback under-corrects and the drag quietly stalls
  partway there.

### Playback and navigation fixes — 2026-09-22

- **Mini-player was hidden on every detail screen.** `AppNavHost`'s bottom bar
  was gated entirely on `currentRoute in Routes.topLevel` (the 5 bottom-tab
  routes), so album/artist/folder/playlist detail and Search all lost it.
  Split the condition: the mini-player now shows everywhere except
  `NOW_PLAYING`/`QUEUE` (which already show full playback UI); the
  `NavigationBar` itself stays top-level-only.
- **`QueueManager.next()` replayed the last track instead of stopping.** At
  the last position with repeat off, it fell into an `else -> currentItem`
  branch — same bug whether you got there by shuffling or just playing
  through a short queue. Now returns `null` there, same contract as
  `onTrackEnded()`; `PlaybackController` stops output and clears the queue
  when that happens, whether the queue ended naturally or via manual skip.
- **"Shuffle All" could start on any track, including the last one.**
  `TracksViewModel.shuffleAll()` played the sorted list's first track, *then*
  shuffled — so the just-started track could land anywhere in the new order.
  Added `QueueManager.setQueueShuffled()` / `PlaybackController
  .playTracksShuffled()`, which shuffle before picking track 1.
- **`QueueManager.addAll()` / `PlaybackController.addToQueue()`** — appends
  tracks to the running queue without interrupting playback, for the new
  long-press "Add to queue" action. Queue-slot ids there are
  `"q<trackId>-<seq>"` (a monotonic counter), not the plain `"q<trackId>"`
  used elsewhere — appending a track already in the queue would otherwise
  collide on id, which shuffle/reorder/remove all key off of.
- **Long-press on a track** (`TrackActionsSheet`, in `ui/components`) now
  offers "Add to queue" or "Add to playlist" instead of jumping straight to
  the playlist picker — used from Tracks, Album/Folder detail, Search, and
  Playlist detail. Album/Artist detail's TopAppBar "add whole thing to
  playlist" button is unchanged (still goes straight to `AddToPlaylistSheet`).
- **Now Playing** has an "add to playlist" button (top row, next to Queue)
  for the currently playing track.
- **Mini-player swipe-to-skip**: horizontal drag on the mini-player (not Now
  Playing) skips next/previous, via `Modifier.draggable` layered under the
  existing tap-to-open-Now-Playing `clickable` — `draggable` only claims the
  gesture past touch slop, so a plain tap still reaches the click handler
  underneath it. Dedicated prev/next `IconButton`s were removed entirely once
  this landed; play/pause moved to the trailing edge and got bigger (56dp
  button, 32dp icon), same for the title/artist text (`titleMedium`/
  `bodyMedium`, up from `bodyLarge`/`bodySmall`).
  - Commits past **35dp** of drag, or on a fast flick (velocity ≥ 800dp/s)
    even if it didn't travel the full distance — matches how swipe-to-skip
    feels in other music apps, and was tuned down twice from an initial 64dp
    after user feedback that it felt like "fighting" the gesture.
  - The title/artist crossfades with the drag: current fades out and slides
    with the finger (clamped to the threshold distance) while the
    next/previous track's title fades in from the edge, reaching full
    opacity exactly at the commit threshold — so the fade is always complete
    by the time the skip actually happens, never a jump-cut. A fling-commit
    with a short drag distance plays a quick 120ms tween to finish that
    slide before committing, for the same reason.
  - Dragging toward an end with nothing to skip to (no next/previous track)
    gets rubber-band resistance (25% of the delta) rather than a hard stop.
  - `AppNavHost` computes the previewed next/previous `Track` from
    `queueState` (wrapping on `RepeatMode.ALL`, else null past either end)
    and passes it down — the mini-player itself has no queue logic.

### Shuffle now keeps the playing track first, not wherever it lands — 2026-09-22

`QueueManager.setShuffled(true)` used to shuffle *all* indices including the
currently-playing one, then search for wherever it ended up — so toggling
shuffle mid-playback could put the track you're already listening to third,
last, anywhere. Fixed: the current track is pulled out first and pinned to
position 0; only the *rest* of the queue is shuffled behind it. Toggling
shuffle off is unchanged — restores original order, finds the current
track's position in it. ("Shuffle All" from the Tracks tab was already
correct — see `QueueManager.setQueueShuffled()` above — since there's no
"currently playing" track to preserve when starting a shuffle from scratch.)
Covered by `QueueManagerTest`'s new case asserting `currentIndex == 0` and
`queue.first()` after shuffling on.

### Visual design — 2026-09-22 pass

User feedback: the stock-template look ("ancient and square") needed a
refresh. Direction chosen: Material You dynamic color (already wired in
`Theme.kt` since the project template, `dynamicColor = true` by default on
API 31+ — nothing to add there) plus Material 3 "expressive" shapes.

- `ui/theme/Shape.kt`: a custom `Shapes()` bumped up from Material3's
  defaults (4/8/12/16/28dp → 8/12/16/20/28dp), passed into `MaterialTheme`.
  Affects every component reading `MaterialTheme.shapes.*` automatically.
- `ui/theme/Type.kt`: bolder weights on the styles actually used for
  titles/row text (`headlineSmall`, `titleLarge` → Bold; `titleMedium` →
  SemiBold; `bodyLarge` → Medium) instead of Material3's default Normal.
- `TrackArtwork`/`AlbumArtwork` default corner radius 8dp → 12dp; bigger
  artwork in `TrackRow` (48→56dp), `QueueRow` (44→52dp), `MiniPlayerBar`
  (40→48dp), Now Playing (16→24dp corner radius).
- **Tonal elevation instead of a flat background-color swap for
  "currently playing"/"currently dragging" rows** (`TrackRow`, `QueueRow`)
  — wrapped in a `Surface` with `tonalElevation` and a rounded, inset shape
  rather than an edge-to-edge `Modifier.background()` rectangle. This reads
  as a raised card, which was the specific "flat gray panel" complaint.
- Artists/Albums/Folders/Playlists list screens were **not** touched in this
  pass beyond the global shape/type changes — they're plain text or
  artwork-grid rows with no "current" state to elevate. A further pass here
  is possible later but wasn't judged worth the scope right now.
- **Found and fixed while wiring the new "currently playing" card style**:
  `TracksScreen`, `AlbumDetailScreen`, `FolderDetailScreen`, and
  `PlaylistDetailScreen` all hardcoded `TrackRow(isCurrent = false, ...)` —
  the current-track highlight had never actually worked outside the Queue
  screen. Fixed by adding `PlaybackController.currentTrackId: StateFlow<Long?>`
  (single derivation of "what's loaded right now" from `playbackState`,
  shared by every screen instead of each one re-deriving it) and wiring it
  through each screen's ViewModel.

### Color scheme — 2026-09-22, replaced dynamic color

User feedback: still looked "old and grey" after the shape/type pass above.
Root cause: `dynamicColor = true` pulls the whole palette from the device
wallpaper (Material You) — on a neutral wallpaper the app desaturates to grey
regardless of any other theme code.

- `dynamicColor` now defaults to **false**. `Color.kt`/`Theme.kt` define a
  full hand-authored Material 3 scheme (every role — not just
  primary/secondary/tertiary — since `lightColorScheme()`/`darkColorScheme()`
  default any omitted role to Material3's own baseline purple, not something
  derived from the roles you do pass; a partial override reads as a
  mismatched patchwork).
- Identity: deep indigo/violet (`primary` #4B3FD1 light / #C3BFFF dark) — one
  swappable place (`Color.kt`) if this isn't to taste.
- The dynamic-color code path is still there (`dynamicColor: Boolean`
  parameter on `SpindleTheme`), just off by default, in case Material You is
  wanted back later.

### Now Playing sliders — 2026-09-22

- **Circular thumb** on both the seek and volume `Slider`s, via the `thumb =`
  slot — Material3's default thumb is a thin vertical bar, which read as
  fiddly to grab. Custom `CircleThumb` composable, shared by both.
- **Play/pause is a `FilledIconButton`** now (72dp, `shape = CircleShape`),
  not a bare `IconButton` — bigger tap target, and a filled primary-color
  circle instead of just a glyph.
- **Volume slider felt stiff/steppy while dragging.** `STREAM_MUSIC` only has
  ~15 real steps on most phones; feeding the rounded system readback straight
  back into the slider's `value` made it visibly snap between those steps
  mid-drag. Fixed with the same pattern the seek bar already used: a local
  `displayVolume` float tracks the drag smoothly, and only gets overwritten
  by the real (coarse) system value when the user isn't actively dragging.
- **Haptic ticks while dragging** either slider (`rememberHapticTicker`) —
  fires once per crossing of one of 30 evenly-spaced buckets across the
  slider's range, not once per raw value change, which would buzz
  continuously instead of ticking. Drives `Vibrator.vibrate(VibrationEffect
  .createOneShot(15, 130))` directly rather than Compose's semantic
  `HapticFeedbackType.SegmentTick` — confirmed on-device (A73, Android 16)
  that constant renders as too weak to feel at all, even with the global
  haptic-feedback setting on and the vibrator motor itself confirmed working
  (`adb shell cmd vibrator_manager synced oneshot -a 300 200` was clearly
  felt). Needs `VIBRATE` in the manifest, unlike the permission-free
  `performHapticFeedback` route this replaced.

## Package layout (current)

```
io/github/eladimany/spindle/
├── App.kt, MainActivity.kt
├── di/                      AppModule (dispatchers), DatabaseModule, PlaybackModule
├── core/model/              Track, Album, Artist, Playlist, Folder, QueueItem,
│                            PlaybackState, OutputCapabilities, TrackSort
├── data/
│   ├── db/                  AppDatabase, dao/, entity/
│   ├── library/             MediaStoreScanner, LibraryRepository,
│   │                        ArtworkExtractor, ArtworkRepository, EntityMappers
│   ├── playlists/           PlaylistRepository
│   └── prefs/               SettingsRepository (DataStore — folder exclusions)
├── playback/                AudioOutput, MediaSource, LocalOutput, QueueManager,
│                            PlaybackController, PlaybackService
└── ui/
    ├── navigation/          Routes, AppNavHost (bottom bar + NavHost)
    ├── components/          TrackArtwork/AlbumArtwork, TrackRow, MiniPlayerBar
    ├── permission/          AudioPermission, AudioPermissionScreen
    ├── library/             MainViewModel + Tracks/Artists/Albums/FolderBrowse
    │                        screens+ViewModels, and their *Detail counterparts
    ├── folders/             FoldersScreen — folder **exclusion settings**,
    │                        reached via a gear icon on the Folders tab; distinct
    │                        from ui/library/FolderBrowseScreen (browsing)
    ├── player/              PlayerViewModel (shared, Activity-scoped),
    │                        NowPlayingScreen
    └── playlists/           PlaylistsScreen/VM, PlaylistDetailScreen/VM
```

`NodeOutput`, `MediaHttpServer`, `TokenRegistry`, `BluOsClient`,
`BluOsDiscovery` don't exist yet — Phase 2.

## Library scanning — folder exclusion (not in the original plan, now permanent)

MediaStore's `IS_MUSIC` filter still lets through WhatsApp voice notes, the
stock Voice Recorder app's output, and similar noise. `MediaStoreScanner`
records **every** folder it sees (`FolderEntity`, regardless of exclusion) and
skips building `Track`/`Album`/`Artist` rows for folders in
`SettingsRepository.excludedFolderIds` (DataStore). Toggling a folder in the
exclusion UI triggers an immediate rescan. `LibraryRepository.browsableFolders`
(non-excluded, non-empty) is what the Folders *browse* tab shows — separate
from the exclusion settings screen.

## Conventions

- One ViewModel per screen (or reusable widget, like the shared
  `PlayerViewModel` for the mini-player/Now Playing). UI state is a single
  immutable data class exposed as `StateFlow`. No mutable state escaping the
  ViewModel.
- Repositories return `Flow`; suspend functions for one-shot work.
- All disk and network work off the main thread, via injected dispatchers
  (`@IoDispatcher`/`@DefaultDispatcher` qualifiers in `di/AppModule.kt`).
- Room is the single source of truth for library data. MediaStore is scanned
  into Room, never queried directly from the UI.
- Prefer `sealed interface` for states and results.
- Compose: hoist state, no `ViewModel` references inside composables below the
  screen level, stable keys in every `LazyColumn`/`LazyVerticalGrid`
  (`itemKey` for Paging lists).
- Long lists (Tracks) are paged from Room via Paging 3; small lists (Albums,
  Artists, per-album/artist/folder track lists) use plain `Flow<List<T>>` —
  don't add paging where the row count is naturally bounded to a few hundred.
- Album artwork is extracted lazily (`ArtworkExtractor`, `MediaMetadataRetriever`)
  and cached to one file per album on first display, never during scan — doing
  it eagerly for every track would blow the scan-time budget.

## Scale

~3900 tracks, ~430 folders on the target device. Scan in the background, cache in
Room, page from Room. Never load the whole library into memory.

## Build

```
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

Physical devices only for anything involving the Node — the emulator is behind
NAT and the Node cannot reach a server running inside it.

## Working style

Work phase by phase; `docs/PHASE1.md` is the current task list. Finish and verify
one task before starting the next. Ask before adding a dependency that is not in
the stack list above.

Watch the conversation's context budget. If a session has been running long
(many rounds of edit/build/adb-test cycles, large file reads, long back-and-forth),
proactively tell the user it's getting full and this is a good point to wrap up —
don't wait for things to visibly degrade first. Raised 2026-09-22.

## Deferred discussion — once local playback is robust and the user is satisfied

Raised 2026-09-22, deliberately not acted on yet:

- **Revisit the shuffle feature — "smart shuffle."** Current behavior: "Shuffle
  All" (Tracks tab) and the shuffle toggle (Now Playing) both call
  `QueueManager.setShuffled`, which Fisher-Yates permutes an index list once
  and keeps that order until toggled off — this already guarantees each track
  appears exactly once per shuffle pass (confirmed 2026-09-22: `order` is a
  permutation of `items.indices`, never resampled with replacement). The user
  wants to design a "smart shuffle" feature on top of this later — not scoped
  at all yet, raise it with the user before doing any design or implementation
  work on it.
- **AI-generated playlists** — a possible future feature. Not scoped at all;
  raise it with the user before doing any design or implementation work on it.
