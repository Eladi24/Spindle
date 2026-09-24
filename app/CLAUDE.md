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
Navigation Compose · Media3 (ExoPlayer + Session) · Coil 3 · DataStore · Haze (blur)
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

### Phase 1 punch list — manual rescan, fast-scroll, M3U — 2026-09-22

- **Manual rescan** — a refresh icon on the Manage Folders screen
  (`FoldersScreen`'s TopAppBar), backed by `FoldersViewModel.rescanLibrary()`
  and `isScanning`. Folded the folder-exclusion-toggle rescan and this one
  into the same private `runRescan()` so both paths track `isScanning`
  identically instead of the toggle path silently not reporting progress.
- **Fast-scroll alphabet index on Tracks** — a draggable A-Z/# rail,
  title-sort only (the anchors are positions into the title-ordered list, so
  they don't line up under date-added/year sort — the rail hides itself
  then). `TracksViewModel.sectionIndex` builds anchors off
  `LibraryRepository.tracks` (already title-ordered, already reactive) rather
  than adding a new query — same list backs "Shuffle All"'s ordering
  assumptions elsewhere.
  - Needed `enablePlaceholders = true` on the tracks `Pager` (was `false`) so
    `LazyPagingItems` has a real `itemCount` to jump into and Paging 3 can
    load pages around a distant target; added `jumpThreshold = 180` (3
    pages) so a big scrub jump triggers a fresh load near the target instead
    of paginating through every intervening page. The pre-existing
    null-item branch in `TracksScreen` (`if (track != null) … else
    CircularProgressIndicator()`) was dead code before this — placeholders
    were off, so `tracks[index]` was never actually null — fixed its
    `Modifier.fillMaxSize()` (would try to fill the remaining viewport
    inside a `LazyColumn` item) to a fixed 76dp row matching `TrackRow`'s
    real height, now that unloaded placeholder rows genuinely render.
  - Gesture is one `awaitFirstDown()` + `drag()` in a single
    `awaitEachGesture` block, not separate tap/drag detectors — a stationary
    tap needs to jump immediately, which a plain `detectDragGestures` won't
    do (it waits for touch-slop movement before firing `onDragStart`).
  - Shows a large centered letter bubble while scrubbing, same idea as
    Contacts-style fast scroll — pure UX feedback, not wired to anything else.
- **M3U import/export for playlists** — export via SAF
  `ActivityResultContracts.CreateDocument` from a playlist's TopAppBar
  (`PlaylistRepository.exportM3u`); import via SAF `OpenDocument` from the
  Playlists screen's TopAppBar, always creating a *new* playlist named after
  the file (`PlaylistRepository.importM3u`).
  - `Track` has no filesystem path (`core/model/Track.kt`'s `uri` is a
    content URI — see CLAUDE.md's domain model note), so an exported row's
    location line is the content URI, not a path. That round-trips exactly
    on re-import into Spindle. The `#EXTINF:<durationSec>,<artist> - <title>`
    line is there for everyone else — and doubles as this app's own import
    fallback (`TrackDao.findByTitleLike`, preferring a matching artist) when
    the URI doesn't resolve, e.g. a file written by another player or a
    stale id after a rescan.
  - `M3uFormat` (`data/playlists/M3uFormat.kt`) is pure parsing/formatting,
    no Android imports — same "keep I/O at the edges" shape as the rest of
    `data/`. `PlaylistsScreen`/`PlaylistDetailScreen` do the actual
    `ContentResolver` reads/writes and hand `M3uFormat`/`PlaylistRepository`
    plain strings.

### Now Playing hero redesign — 2026-09-22

First screen out of the "modern UI" concepts board (see the Deferred discussion
entry below) — user picked this one to build first, from a mocked-up
Artifact comparison.

- Full-bleed dark violet gradient backdrop (`HeroBackdrop`) plus two blurred
  color blobs (`Modifier.blur`), replacing the plain `MaterialTheme.colorScheme`
  surface. **Deliberately not theme-conditional** — Now Playing keeps this
  fixed dark identity regardless of the app's light/dark setting, same as
  Spotify/Apple Music's player screen. All foreground colors on this screen
  are therefore hardcoded white/lavender constants (`HeroOnBackdrop*`,
  `HeroAccent`) rather than `MaterialTheme.colorScheme.*` — those flip with
  system theme and would lose contrast against the fixed dark backdrop.
- The backdrop gradient is a fixed approximation, not derived from the actual
  album art color — real per-track extraction would need `androidx.palette`
  (not in the stack, needs sign-off) run on the artwork bitmap already loaded
  by `TrackArtwork`/Coil. Left as a possible follow-up.
- Added `onBack` to `NowPlayingScreen` (wired to `popBackStack()` in
  `AppNavHost`) — the screen had no way back before except the system
  gesture; a hero screen this immersive needs an explicit control.
- `CircleThumb`/`repeatIconFor` took explicit color params instead of reading
  `MaterialTheme.colorScheme` directly, so this screen's fixed hero palette
  and any future theme-following screen can both use them.

### Tracks list restyle + app icon + splash screen — 2026-09-22

- **Tracks list**, second screen from the UI refresh concepts board: "Shuffle
  All" is now a flat tonal pill (`surfaceContainerHigh` + a hairline
  `primary`-alpha border) instead of a filled gradient with a heavy drop
  shadow — the gradient read as "cheap" per user feedback on the first
  (lighter) mockup. Dropped the `HorizontalDivider()` between rows entirely;
  `TrackRow`'s own rounded-card padding already separates rows, and a divider
  under a card-style current-track row looked wrong. Scoped to `TracksScreen`
  only for now — Album/Artist/Folder/Playlist detail and Search still use
  `TrackRow` with dividers; same staged-rollout call as the shape/type pass.
- **Animated equalizer glyph** on the current track's row
  (`TrackRow`'s new `EqualizerGlyph`) — 3 bars, each its own out-of-phase
  `rememberInfiniteTransition`, animated while `isPlaying`, frozen at a fixed
  height when the current track is loaded but paused. `isCurrent` alone
  already existed per-row; `isPlaying` is new
  (`PlaybackController.isPlaying`, `TracksViewModel.isPlaying`) and is only
  wired up in `TracksScreen` — other `TrackRow` call sites default it to
  `false`, so their current row shows the static (not stale-animating) glyph
  for free without extra wiring.
- **App icon** — replaced the stock template's Android-robot adaptive icon.
  `ic_launcher_background.xml` is now flat brand indigo (`#4B3FD1`);
  `ic_launcher_foreground.xml` is the "Spindle Signal" mark (center pin +
  three broadcast rings — same concept as the logo board's #1, scaled down
  from its 100x100 sketch to fit the adaptive icon's ~66dp safe zone). Reused
  as-is for the Android 13+ monochrome/themed-icon layer, since it's already
  a pure white silhouette on transparent. The mipmap-*dpi `.webp` fallback
  icons are stock and now stale, but **unreachable** — minSdk 26 means
  `mipmap-anydpi-v26` always wins — so left alone rather than regenerated.
- **Splash screen** — `ui/splash/SplashScreen.kt`, shown for a fixed 900ms in
  `MainActivity` before the existing permission/scan/nav branching, not the
  system `SplashScreen` API: that API is built for a single icon on a solid
  color and actively discourages text, and the wordmark lockup the user
  picked (logo concept #6) is icon-plus-text. Fixed brand-indigo background
  regardless of theme, same reasoning as Now Playing's hero backdrop.
  `ic_spindle_signal.xml` is a **second copy** of the launcher mark, cropped
  tight instead of safe-zone-padded — rendering the launcher's own
  `ic_launcher_foreground` directly here would show it small and off-center,
  since that drawable assumes the OS's adaptive-icon crop/scale step. Keep
  both in sync if the mark changes. No Poppins on Android (no font file to
  bundle without a design-system asset source) — the wordmark uses the
  existing bold system-font style from `ui/theme/Type.kt` instead; visually
  close but not pixel-identical to the mockup.

### Now Playing hero — centering and track-thickness fixes — 2026-09-22

User feedback after installing on the A73: artwork looked off-center, and
both sliders were fatter than the mockup.

- **Centering bug**: the hero content `Column` (artwork + title/artist) had
  `Modifier.weight(1f)` but no `.fillMaxWidth()`. A `Column` without an
  explicit width only wraps to its widest child — here, the artwork at 72%
  of screen width — so despite the Column's own `horizontalAlignment =
  CenterHorizontally`, the whole (narrower-than-parent) block sat at the
  *outer* Column's default `Alignment.Start`, reading as shifted left with
  empty space on the right. Every sibling (top bar `Row`, controls `Row`,
  `VolumeRow`) already had its own `fillMaxWidth()` so this only hit the one
  weighted child. Fixed by adding `.fillMaxWidth()` alongside the `.weight(1f)`.
- **Slider track thickness**: this Compose BOM's Material3 `Slider` default
  track is the newer "expressive" ~16dp pill, not the thin line the mockup
  showed. Added a custom `track = {}` slot (`ThinTrack`, 4dp, same pattern as
  the existing custom `thumb = {}` slot) on both the seek and volume sliders
  instead of trying to reconfigure the built-in one.

### Rotation + process-death testing on the A73 — 2026-09-22

Driven live over adb (device connected mid-session) rather than just reasoned
about statically — see `docs/PHASE1.md` for the checklist state this left.

- **Rotation**: forced landscape/portrait via `adb shell settings put system
  user_rotation`. No crash either direction; playback (queue position,
  current track) survived two Activity recreations with zero audible
  interruption — confirmed both by screenshots and by `dumpsys media_session`
  position advancing exactly with wall-clock time across the transition.
- **Found via rotation**: Now Playing's artwork was sized with
  `fillMaxWidth(0.72f).aspectRatio(1f)` — fine in portrait, but in landscape
  "width" is the long edge, so the artwork blew up far past the screen and
  overlapped the controls. Fixed with `BoxWithConstraints` sizing off
  `minOf(maxWidth, maxHeight)` instead of width alone.
- **Follow-on landscape rough edge, not fully fixed**: even after that, a
  short landscape window doesn't have room for artwork *and* three lines of
  title/artist/"track N of M" without one or the other shrinking a lot.
  Current state: the hero content is wrapped in `verticalScroll` as a
  correctness backstop (title/artist can no longer render *behind* the
  slider, which is what a plain non-scrolling `Column` was doing — Compose
  doesn't clip an overflowing child by default) — but in a short landscape
  window that means the text sits below the fold and needs a manual scroll
  to see, which isn't a good landscape experience. The real fix is a
  landscape-specific layout (artwork left, text+controls right, like most
  music apps do) rather than continuing to tune a single portrait-shaped
  layout's size fractions — that's a design pass, not a bug fix, and hasn't
  been done.
- **Process-death**: attempted via `adb shell settings put global
  always_finish_activities 1` (the standard technique for this). It did not
  actually destroy `MainActivity` on this Samsung/Android build while
  `PlaybackService` was an active foreground service — same PID before/after
  backgrounding, no destroy/create pair in logcat — so this pass did not
  actually exercise `SavedStateHandle`/back-stack restoration through a real
  kill. Left `always_finish_activities` reset to `0` afterward.
- **Found instead, unrelated to app code**: Samsung's "Freecess"
  background-process-freeze mechanism (`FreecessHandler: freeze
  io.github.eladimany.spindle`, firing ~6s after backgrounding) paused
  playback during a plain Home-and-return cycle, confirmed authoritatively
  via `dumpsys media_session` (`state=PAUSED` at a position matching the
  freeze timestamp) rather than just the UI's play/pause icon. `
  PlaybackService` is correctly declared as a foreground media-playback
  service; this is Samsung's own aggressive battery management overriding
  that, not a bug in `PlaybackController`/`PlaybackService`. No code fix
  exists for this from the app side — the standard mitigation is the user
  exempting the app from battery optimization (Settings → Apps → Spindle →
  Battery → Unrestricted).

### Lock screen controls fix — 2026-09-22

User testing on the A73: the lock screen media widget had no skip/next
button, and "previous" just restarted the current track instead of going
back.

- **Root cause**: `LocalOutput.play()` calls `player.setMediaItem(mediaItem)`
  — one item at a time. `QueueManager`, not ExoPlayer's own `Timeline`, owns
  next/previous. A raw `ExoPlayer` with a single-item timeline correctly
  reports `hasNextMediaItem()/hasPreviousMediaItem() == false`, so every
  system surface that reads `Player.availableCommands` (lock screen,
  notification, Bluetooth AVRCP, Android Auto) hides the skip button, and
  `Player.seekToPrevious()`'s own default fallback for "no previous item" is
  to just seek to `0` on the current item — exactly the reported symptom.
- **Fix has two parts, for two different real control paths** — found by
  testing them separately, one didn't cover the other:
  - `QueueAwareForwardingPlayer` (`PlaybackService.kt`), a `ForwardingPlayer`
    wrapping the real `ExoPlayer`, given to `MediaSession.Builder` instead of
    the raw player. Overrides `getAvailableCommands()` to always advertise
    seek-to-next/previous, and routes `seekToNext()`/`seekToNextMediaItem()`/
    `seekToPrevious()`/`seekToPreviousMediaItem()` to
    `PlaybackController.next()/previous()`. This is the path a **tap on the
    lock screen/notification widget's own buttons** takes (MediaController
    transport-control calls, bridged by Media3 into Player command calls).
  - `MediaSession.Callback.onMediaButtonEvent` (also `PlaybackService.kt`),
    intercepting the raw `KeyEvent` for `KEYCODE_MEDIA_NEXT`/`_PREVIOUS`
    directly and calling `PlaybackController` before Media3's default
    key-to-player-command translation runs. This is the path a **physical
    Bluetooth/wired headset button** takes — confirmed via `adb shell input
    keyevent KEYCODE_MEDIA_PREVIOUS` that this path was *not* fixed by the
    ForwardingPlayer overrides alone (still just restarted the track), before
    this callback was added.
  - Both intentionally always report/act as if next/previous are available,
    same simplification as the in-app Now Playing screen's own prev/next
    buttons (never disabled at queue boundaries either).
- **Verified on-device**: `dumpsys media_session`'s actions bitmask gained
  the `SKIP_TO_NEXT` bit (32) that was absent before; hardware media-key
  simulation correctly walked a real 3-track span both directions
  (Castellorizon → Children Of The Sea → Green Onions and back), confirmed
  by the session's reported metadata changing tracks, not just position; a
  screenshot of the actual lock screen widget now shows the skip button that
  was previously reported missing entirely.

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
│   ├── prefs/               SettingsRepository (DataStore — folder exclusions)
│   ├── server/              MediaHttpServer, TokenRegistry, HttpRange,
│   │                        NetworkAddress (Phase 2, see below)
│   ├── bluos/               BluOsClient, BluOsXmlParser, BluOsStatus,
│   │                        BluOsSyncStatus, BluOsDiscovery, NodeTrackEnd,
│   │                        IcyName (Phase 2, see below)
│   └── artists/             ArtistArtworkRepository, DeezerArtistArtworkClient,
│                            DeezerArtistArt (see "Artist artwork" below)
├── playback/                AudioOutput, MediaSource, LocalOutput, NodeOutput,
│                            AudioOutputSwitcher, LocalAudioRoutes, QueueManager,
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
    ├── output/              OutputPickerSheet/VM — pick "This phone" vs. a
    │                        discovered BluOS Node (Phase 2, see below)
    └── playlists/           PlaylistsScreen/VM, PlaylistDetailScreen/VM
```

Phase 2 is functionally complete: `MediaHttpServer`/`TokenRegistry`/
`BluOsClient`/`BluOsDiscovery`/`NodeOutput`/`AudioOutputSwitcher`/
`OutputPickerSheet` are all built and wired end to end — see below. **None
of it has been exercised against a real Node yet** — that's the next step,
and where the unverified bits noted throughout this section (the
`state == "pause"` guess, the 2s end-of-track tolerance, non-ASCII
`icy-name`, the multicast lock, `NEARBY_WIFI_DEVICES` actually surfacing
players) get confirmed or fixed.

### Phase 2 — MediaHttpServer + TokenRegistry — 2026-09-22

First slice of Phase 2: the file-serving half, independent of `BluOsClient`/
discovery. Verified by unit test and a clean `assembleDebug`; **not yet
exercised against the real Node** — that needs `NodeOutput` wired up to
actually issue a `/Play?url=` at one of these URLs.

- Added the `ktor-server-core` / `ktor-server-cio` dependencies (pinned
  `3.5.2` — compiles clean against Kotlin 2.2.10, no stdlib-version trap like
  Coil's). `INTERNET` and `ACCESS_NETWORK_STATE` added to the manifest.
- `TokenRegistry` — in-memory `UUID → content-URI-string` map. `tokenFor()`
  issues a fresh token per call (not idempotent per URI — cheap, and a track
  is re-resolved at most once per play, not per queue build). `clear()` drops
  everything; not wired to a call site yet — that belongs to whatever in
  `NodeOutput`/`PlaybackController` owns "the queue changed," not to the
  server itself.
- `MediaHttpServer` — Ktor CIO `embeddedServer`, started with `port = 0` and
  the actual bound port read back via `embedded.engine.resolvedConnectors()`
  (a suspend member on `ApplicationEngine`, not a top-level import — cost a
  compile error the first time). Serves `GET /t/{token}`, resolving through
  `TokenRegistry` and opening the URI via
  `ContentResolver.openFileDescriptor` — real `FileInputStream` off the fd's
  descriptor so `FileChannel.position(start)` can seek, rather than an
  `AssetFileDescriptor` stream (which doesn't reliably expose a seekable
  channel for content URIs). `start(host)` takes the WLAN IP explicitly
  (from `NetworkAddress`) — never binds `0.0.0.0` (hard constraint: don't let
  the server answer on any interface but the LAN one, e.g. a VPN).
- `HttpRange` — pulled the `Range: bytes=start-end` parsing out to its own
  file with zero Android imports specifically so the EOF-clamp logic (hard
  constraint #4) gets real unit tests, same reasoning as `QueueManager`.
  Handles bounded (`bytes=100-499`), open-ended (`bytes=500-`), and suffix
  (`bytes=-500`) forms; a multi-range header uses only the first range (the
  Node has never been observed sending more than one). 12 tests in
  `HttpRangeTest`, including the actual overshoot-past-EOF case the hard
  constraint exists for.
- `NetworkAddress.wlanIpv4()` — reads the WLAN IPv4 off
  `ConnectivityManager.activeNetwork`'s `LinkProperties`, gated on
  `NetworkCapabilities.TRANSPORT_WIFI`; returns `null` off WiFi (cellular,
  no connection) rather than guessing.

### Phase 2 — BluOsClient + BluOsDiscovery — 2026-09-22

Second slice: talking to the Node. Independent of `MediaHttpServer` above;
`NodeOutput` is what will wire the two together. **Not yet exercised against
a real Node** — `BluOsXmlParserTest`'s fixtures are the exact response shapes
recorded in `bluos-api.md` from Phase 0's `probe.py` sweep, but nothing has
made a live request yet.

- `BluOsClient` — a stateless Ktor (CIO engine) wrapper; every method takes
  the target `BluOsPlayer` explicitly rather than binding to one player at
  construction, since discovery can re-resolve a player's address at any
  time and (eventually) more than one player can exist. `expectSuccess =
  true` so a non-2xx surfaces as an exception rather than silently returning
  a body no one checked.
  - `status()` long-polls `/Status?timeout=&etag=`; the request's own socket
    timeout is always `timeout=` + 15s, never just the BluOS `timeout=`
    value itself — bluos-api.md is explicit that the socket timeout must
    exceed it, and a poll parked at `timeout=100` needs a client willing to
    actually wait that long.
  - `playUrl()`/`seek()`/`pause()`/`stop()`/`resume()`/`setVolume()` cover
    every *verified* command from the endpoint reference. `/Skip`/`/Back`
    are deliberately not implemented — hard constraint #5, they're
    meaningless once a custom `streamUrl` is playing.
  - **Deliberately does not implement `/SyncStatus`** (volume, player
    identity, grouping). `bluos-api.md` only confirms the long-poll
    *mechanism* behaves like `/Status`; the actual field names (volume in
    particular) were never captured against real hardware in Phase 0. Adding
    it now would mean guessing a schema, which is exactly what this project
    doesn't do — see the file's own opening line. Build it when `NodeOutput`
    needs volume, verified against the Node first.
- `BluOsXmlParser` — pure `javax.xml` (`DocumentBuilderFactory`/DOM), zero
  Android imports specifically so it has real unit tests
  (`BluOsXmlParserTest`, 5 cases including the collapsed `stop` document and
  the Bluetooth-hijack case) without needing Robolectric. `android.util.Xml`
  would have been the more idiomatic Android choice but isn't callable from
  a plain JVM unit test.
- `BluOsStatus` only exposes fields `bluos-api.md` actually verified. `state`
  is a raw `String`, not an enum — the full set of values was never
  enumerated (Phase 0 only ever saw `stream`/`stop`), so branching should
  compare against known constants rather than exhaustively matching.
  `totalSeconds`/`streamUrl`/`streamFormat` are all nullable because they
  **disappear from the document entirely**, not just go empty, once `state`
  collapses to `stop` — confirmed by `BluOsXmlParserTest`.
- `BluOsDiscovery` — NSD (`_musc._tcp`) via `NsdManager`, emitting the
  current resolved player set as a `Flow<List<BluOsPlayer>>`. A fresh
  `NsdManager.ResolveListener` per `onServiceFound` call, not one shared
  instance — `NsdManager` rejects reusing a listener across concurrent
  in-flight `resolveService` calls, and several services can be found before
  any of them finishes resolving. Holds a `WifiManager.MulticastLock` for
  the duration of discovery (some devices otherwise silently drop mDNS
  multicast packets — the target Samsung devices have a history of exactly
  this class of bug elsewhere in the codebase, e.g. the Freecess background
  freeze noted above, so this was treated as cheap insurance rather than
  guessed-unnecessary).
  - **`NEARBY_WIFI_DEVICES` (`neverForLocation`) is required on API 33+** —
    without it `NsdManager.discoverServices()` throws. Nothing extra is
    needed pre-33; unlike `WifiP2pManager`, plain NSD never required a
    location permission on older Android versions. `hasNearbyWifiPermission()`
    checks this before starting and returns an empty list rather than
    crashing if it's missing — **the actual runtime permission *request* UI
    doesn't exist yet**, deliberately deferred to whenever the output-switcher
    screen is built, since that's the natural point a rationale/request flow
    belongs (same reasoning as `AudioPermissionScreen` for library scanning).
  - `resolveService`/`NsdServiceInfo.host` are deprecated in favor of API
    34's `registerServiceInfoCallback`/`getHostAddresses()` — suppressed
    deliberately, not fixed, since minSdk here is 26.

### Phase 2 — NodeOutput — 2026-09-22

Wires `BluOsClient` + `MediaHttpServer` into an `AudioOutput`. **Not wired
into `PlaybackController`/DI, and not yet exercised against a real
Node** — see the package-layout note above for why.

- **All mutable state is confined to one `Dispatchers.Main.immediate`
  scope** (`currentItem`, `lastEtag`, `lastKnownSecs`/`lastKnownTotalSeconds`,
  `_state`) — the long-poll loop runs continuously in the background while
  `play()`/`pause()`/`stop()`/etc. get called directly, and both sides touch
  the same vars. Rather than adding locks, this reuses the confinement
  `PlaybackController` already relies on (its own scope is
  `Main.immediate`, and it's the only thing that calls into `NodeOutput`'s
  suspend functions) — one thread, no races, by construction rather than by
  discipline.
- **`connect(player)`/`disconnect()` are extra public API, not part of
  `AudioOutput`** — the interface has no notion of "which Node," only "the
  current output." `connect` starts `MediaHttpServer` bound to the phone's
  WLAN IP and throws if the phone isn't on WiFi at all (never silently
  no-ops there — whoever ends up calling this, the output-switcher UI, is
  expected to catch it and tell the user). `disconnect` is the real
  "stop using this output" lifecycle hook; the interface's own `stop()`
  only stops playback and leaves the Node connection alive, same
  distinction `LocalOutput.stop()` already draws for the player instance.
- **Auto-advance and the `state == "stop"` ambiguity** (bluos-api.md:
  it means both "track ended" and "user pressed stop in the BluOS app",
  and `totlen`/`secs` disappear from the document the instant it collapses
  to `stop`, so they must be captured from the *previous* status first) —
  pulled out to `NodeTrackEnd.isNaturalEnd()`, pure and unit tested
  (`NodeTrackEndTest`, 6 cases), same reasoning as `HttpRange`. Its
  2-second tolerance is a starting heuristic, not a Phase-0 measurement —
  tune it once this is actually tested against the Node.
- **`status.state == "pause"` is an unverified guess** — Phase 0 only ever
  observed `"stream"`/`"stop"` (see `BluOsStatus`'s own doc comment). Kept
  because the failure mode if wrong is graceful: it just falls through to
  "unrecognized state, keep the last known one" instead of doing anything
  actively wrong. Needs confirming on real hardware before this is trusted.
- **`icy-name` is now sent per track**, closing the loop `MediaHttpServer`
  left open: `IcyName.forTrack()` (pure, unit tested — `IcyNameTest`, 3
  cases) builds the one-line `"Artist - Title"` and strips control
  characters, since a `\r`/`\n` smuggled in from a file's tags could
  otherwise inject a second header into the HTTP response.
  `TokenRegistry`/`MediaHttpServer` both updated: a token now resolves to a
  `ServedTrack(uri, icyName)` instead of a bare URI string, and the server
  sends that `icy-name` header before serving the file.
- **`capabilities` is a static `OutputCapabilities(canSeek = true,
  canSetVolume = true, isGapless = false)`**, not derived from the current
  track's actual per-source `canSeek` in `/Status` — the `AudioOutput`
  interface only exposes a plain `val`, not a `StateFlow`, so this is a
  known simplification pending an interface change, not a guess.
  `isGapless = false` is the one confirmed hard fact in that line
  (bluos-api.md: "gapless is not achievable this way").
- **`volume` has no ground truth** — same reason `BluOsClient` doesn't
  implement `/SyncStatus`. `setVolume()` fires `/Volume?level=` and updates
  the local `StateFlow` optimistically; it reflects what we last asked for,
  not what the Node actually reports.

### Phase 2 — AudioOutputSwitcher — 2026-09-22

The piece that actually makes switching real: `PlaybackBindingsModule` now
binds `AudioOutput` to `AudioOutputSwitcher`, not `LocalOutput` directly.
`PlaybackController` is unchanged — it still just holds "an `AudioOutput`"
and has no idea `NodeOutput`/`LocalOutput`/switching exist at all, exactly
per the architecture note above. **Nothing calls `switchTo()` yet** — that's
the picker UI, the one remaining Phase 2 piece, deliberately not started
without a mockup pass first (see the Deferred/UI-process notes elsewhere in
this file).

- `state`/`volume` are built with `_target.flatMapLatest { outputFor(it).state }`
  `.stateIn(scope, SharingStarted.Eagerly, ...)` rather than a manually
  updated `MutableStateFlow` — switching `_target` automatically resubscribes
  to the new output's own flow and cancels the old subscription, so there's
  no code path that could forward a stale value from the output just
  switched away from. `capabilities` doesn't need this: the interface
  exposes it as a plain `val`, so `get() = active.capabilities` reads live
  on every access with no flow needed.
- `switchTo(target)` carries over what's currently loaded: reads its own
  `state.value` for the item + position *before* touching anything, stops
  the old output, connects the new one if it's a Node, then `play()`s (and
  `seek()`s back close to the same position) on the new output. This is a
  **fresh load on the new output, not a seamless handoff** — neither output
  can hand the other a mid-decode stream — so switching mid-track will
  always have a brief gap/rebuffer. Documented as a known limitation rather
  than something to fix now.
- Switching Node → Local calls `nodeOutput.disconnect()` explicitly (tears
  down `MediaHttpServer` and the status long-poll). Switching Node A → Node
  B deliberately does **not** call `disconnect()` from this class —
  `NodeOutput.connect()` already disconnects any previous session as its
  first step, so an extra call here would race and tear down the *new*
  connection instead of the old one. Local → Node needs no explicit
  disconnect at all (nothing was connected).

### Phase 2 — OutputPickerSheet — 2026-09-22

The picker UI, from a mocked-up Artifact comparison — user picked the
status-cards style (each output as its own tonally-elevated card, reusing
the exact treatment `TrackRow`/`QueueRow` already use for the current
track) over a plain checkmark-list. Reachable by tapping Now Playing's top
bar — the existing static "PLAYING FROM / Library" label is now a live,
tappable chip showing "PLAYING ON" + the active output's name.

- `PlayerViewModel` now also injects `AudioOutputSwitcher` directly (not
  routed through `PlaybackController`) just to read `target` for that chip
  — `PlaybackController` itself still never sees `AudioOutputSwitcher`,
  `NodeOutput`, or `LocalOutput`, same boundary as everywhere else in this
  section.
- `OutputPickerViewModel.startDiscovery()`/`stopDiscovery()` are called
  from a `DisposableEffect` keyed on the granted-permission state in
  `OutputPickerSheet`, not from the `ViewModel`'s own lifecycle — a
  `hiltViewModel()` call here is Activity-scoped like every other screen
  ViewModel in this app (no nav-graph back-stack entry backs it), so the
  instance outlives the sheet being open; without this the network scan
  would keep running in the background after the sheet is dismissed.
- **The `NEARBY_WIFI_DEVICES` runtime request finally lands here** — it's
  requested locally in `OutputPickerSheet` via
  `rememberLauncherForActivityResult`, not at `MainActivity`'s root like
  the audio-library permission gate, since it's genuinely contextual: nothing
  else in the app needs it, and gating the whole app launch on it (like
  `AudioPermissionScreen` does) would be wrong for a permission only the
  Node feature needs. Below API 33 `hasNearbyWifiPermission()` returns
  `true` unconditionally and the permission section always shows outputs.
- Manual IP entry: typed in as a bare host string, wrapped as
  `BluOsPlayer(name = host, host = host)` — no format validation. If it's
  wrong, `NodeOutput.connect()`'s failure surfaces through the same
  `errorMessage` path as a real discovered player failing to connect,
  rather than a separate validation error.
- **Not yet tested against a real Node** — same caveat as `NodeOutput`
  itself. First real run is where to check whether `NEARBY_WIFI_DEVICES` +
  the multicast lock actually surface a player on your network at all.

### In-app Bluetooth-vs-speaker routing — 2026-09-22, found on-device (A73)

User feedback after installing: with Bluetooth earbuds connected, "This
phone" showed as active but didn't mean speaker — and the picker had no
way to choose between the speaker and the earbuds at all. This is a
separate axis from `OutputTarget` (Local vs. a BluOS Node) — it's *which
local device* "This phone" actually plays through — so it doesn't touch
`AudioOutputSwitcher`.

- **`LocalAudioRoutes`** (new) watches `AudioManager.getDevices()` +
  `AudioDeviceCallback` for the built-in speaker, a connected Bluetooth
  device, or wired headphones. **`LocalOutput.setPreferredRoute(route)`**
  pins ExoPlayer's actual output via `ExoPlayer.setPreferredAudioDevice()`
  (`route = null` = clear the pin, back to Android's own automatic
  routing).
- **First on-device bug: two rows both read "Bluetooth device"**, and one
  of them silently played through the speaker instead of the earbuds. Cause:
  the same physical earbuds show up as **two separate `AudioDeviceInfo`
  entries** — `TYPE_BLUETOOTH_A2DP` (music) and `TYPE_BLUETOOTH_SCO` (the
  voice-call profile: mono, 8/16kHz). Pinning ExoPlayer's *media* AudioTrack
  to the SCO one doesn't actually route media there; it silently falls back
  to the speaker. Fix: `LocalAudioRoutes` only maps `TYPE_BLUETOOTH_A2DP` to
  `LocalRouteKind.BLUETOOTH`; SCO is excluded entirely, not merged.
- **Second: "This phone" was ambiguous.** It used to clear the pin
  (`null` = "automatic"), and Android's automatic routing *prefers a
  connected Bluetooth device* — so "This phone" silently meant Bluetooth
  whenever one was connected, not the speaker, which is what the label
  actually promises. Fixed: `OutputPickerViewModel.selectLocal()` now
  looks up the actual speaker `LocalRoute` and pins it explicitly, same
  call path as picking any other route. The now-redundant "Speaker" row is
  filtered out of the "OUTPUT DEVICE" sub-section for the same reason (it's
  exactly what "This phone" already does) — that section only lists routes
  *other than* the speaker, and only appears when at least one exists.
- **Third: the real device name never showed, even after granting
  permissions once.** `AudioDeviceInfo.getProductName()` needs
  `BLUETOOTH_CONNECT` (API 31+) for a real Bluetooth name. That request was
  bundled into the *same* "Grant access" button as `NEARBY_WIFI_DEVICES` —
  but that button is gated on `!hasNearbyWifiPermission`, so on a device
  that had already granted the network permission in an earlier test
  session, the button (and therefore the Bluetooth request) never showed at
  all. Fixed by decoupling: a separate "Show the Bluetooth device's real
  name" `TextButton` appears under the Bluetooth row specifically when
  `!hasBluetoothConnectPermission`, independent of the network permission's
  own card. (Confirmed on-device: both are ordinary persistent runtime
  grants, not the one-time location/camera/mic-style prompts — once
  granted, neither button reappears.)
- **Fourth, unrelated to the picker itself: the volume slider went stale
  across a Bluetooth connect/disconnect**, only correcting itself (with a
  visible jump) the next time the user touched it. Cause: Android keeps a
  *separate remembered `STREAM_MUSIC` level per output route* — a Bluetooth
  device typically reports its own "absolute volume" distinct from the
  phone speaker's — and switching routes updates what
  `AudioManager.getStreamVolume()` returns without reliably firing
  `VOLUME_CHANGED_ACTION`. `LocalOutput` only ever refreshed `_volume` from
  that broadcast, construction time, or its own `setVolume()` call, so a
  route-driven change fell through every path. Fixed by also registering an
  `AudioDeviceCallback` on `LocalOutput` itself (separate registration from
  `LocalAudioRoutes`' own — different concerns, no need to couple them) that
  re-reads `currentSystemVolumePercent()` on every device add/remove.

### Node output — first real-hardware run — 2026-09-22

Streamed to the actual Bluesound Node for the first time. Surfaced two real
bugs and closed the one deliberately-deferred piece:

- **Switching outputs mid-track jumped back to a stale position** (reported:
  sometimes to where it was on the previous output, sometimes to the very
  start). `AudioOutputSwitcher.switchTo()` read `PlaybackState.Playing
  .positionMs` bare — but that field is a *snapshot* as of `capturedAtMs`
  (see the type's own doc comment), never updated except on a real state
  change, not every second. Neither output emits a new `Playing` state while
  just steadily playing, so by the time you switched minutes in, the
  snapshot was from whenever playback last actually changed state. Fixed by
  interpolating the same way the seek bar does:
  `positionMs + (now - capturedAtMs)`, clamped to duration, before handing
  it to the new output's `seek()`.
- **`/SyncStatus` implemented for real** — deferred in the earlier
  `BluOsClient` entry specifically until it could be verified; a real
  response, captured against a Node (model `N132`/`NODE`, firmware
  4.16.22):
  ```xml
  <SyncStatus etag="66" syncStat="66" version="4.16.22" id="10.0.0.9:11000" db="0"
    volume="100" name="Living Room HI-Fi" model="N132" modelName="NODE"
    class="streamer" icon="/images/players/N125_nt.png" brand="Bluesound"
    schemaVersion="34" initialized="true" mac="90:56:82:98:B4:42">
    <pairWithSub/> <bluetoothOutput/>
  </SyncStatus>
  ```
  Unlike `/Status`, every field is a **root-element attribute**, not a child
  element — confirms it needed its own verified parser rather than reusing
  `/Status`'s shape. Only `etag`/`volume`/`name` are modeled in
  `BluOsSyncStatus`; the rest (`model`, `mac`, `pairWithSub` grouping, ...)
  exist in the real response but aren't consumed yet. `NodeOutput` runs this
  as a second, independent long-poll loop (`syncStatusLoop`, its own etag
  chain) started from `connect()` rather than lazily from `play()` like the
  `/Status` loop — volume matters even before anything's loaded.
- **Quality/bit-perfect passthrough** — by design, not yet independently
  verified. `MediaHttpServer` streams the original file bytes untouched; no
  transcoding happens anywhere in this app's path, so Bluetooth-style
  lossy-codec concerns don't apply (BluOS is a WiFi network player, not a
  Bluetooth one). `BluOsStatus.quality`/`streamFormat` already exist in the
  parsed data but aren't surfaced in the UI yet — the check for now is the
  official BluOS Controller app's own now-playing view, same as `docs/PLAN.md`
  §Phase 2 already calls for ("verify a 24/192 FLAC reports as hi-res").

## Phase 3 — robustness (started 2026-09-23)

Scope/order: `docs/PLAN.md` §6 Phase 3. Built while away from the Node, so
everything here is unit-tested but **not yet verified on real hardware** — see
the checklist at the end of this section.

### External change detection — 2026-09-23

Someone else takes over the Node (BluOS app plays Tidal/radio/its own queue,
another controller, a physical input) → Spindle steps back instead of fighting.
- **Ownership = `streamUrl` contains `/t/{ourCurrentToken}`.** Pure, tested
  classifier `NodeOwnership.classify()` (`NodeOwnershipTest`, 8 cases) →
  OURS / TAKEN_OVER / UNKNOWN. Phase 0 showed the Node echoes the requested URL
  verbatim (`BluOsXmlParserTest`'s captured `/Status`), so a UUID token survives.
  **If that assumption is ever wrong, every Node track would read as taken
  over ~10 s in** — first thing to check at the Node.
- TAKEN_OVER: `isHijacked` (AudioInputs), any non-matching `streamUrl`, or
  `state` "stream"/"play" with no URL (BluOS queue playback). **"stop"/"pause"
  without a URL are UNKNOWN**, never takeover — `streamUrl` vanishes on stop
  (bluos-api.md) and pause is unverified, so our own stream could look like that.
- **Grace window (10 s, `TAKEOVER_GRACE_MS`) after each `/Play`:** a foreign
  status only counts once our stream has been seen (`seenOurs`) or the window
  passed — right after `/Play` the Node may still report what it was doing
  before. A pre-confirmation "stop" is likewise ignored inside the window.
  Natural-end auto-advance now also requires `seenOurs`.
- **On takeover:** state becomes `Paused` at `lastKnownSecs` (not `Error`, which
  made the mini-player vanish), and status is ignored until our next `play()`.
  `pause()`/`stop()` don't touch the Node (not our music); `seek()` only moves
  the resume point; **`resume()` takes the Node back** — replays the item and
  seeks to the resume point (same play-then-seek as `AudioOutputSwitcher`).
  The queue never auto-advances over someone else's playback.
- Not done (needs a mockup first): any visible "Node is playing something else"
  message — for now it just shows as paused.

### WiFi + CPU locks while streaming to the Node — 2026-09-23

`NodeStreamingLocks` (playback/): a `WIFI_MODE_FULL_HIGH_PERF` WifiLock **plus**
a `PARTIAL_WAKE_LOCK` — on Node output nothing plays locally, so with the screen
off the CPU can sleep too, and a WiFi lock alone wouldn't keep `MediaHttpServer`
serving. Same pair Media3 uses for `WAKE_MODE_NETWORK`. HIGH_PERF, not
LOW_LATENCY: LOW_LATENCY only applies in the foreground with the screen on.
- Held only while `NodeOutput.state` is Playing or Buffering (a collector in
  `NodeOutput.init`); every other state — pause, idle, takeover, disconnect —
  releases. Non-reference-counted; `setHeld` is idempotent since Playing emits
  repeatedly.
- Adds `WAKE_LOCK` to the manifest (normal permission, no prompt).
- Verified on the A73 (no Node): app starts, nothing held while idle
  (`dumpsys wifi` / `dumpsys power` show no `Spindle:NodeStreaming`).

### Phone IP change / WiFi drop recovery — 2026-09-23

`MediaHttpServer` is bound to one WLAN IP (hard constraint: never `0.0.0.0`).
`NodeOutput` registers a default-network callback from `connect()` (unregistered
in `disconnect()`); on `onAvailable`/`onLinkPropertiesChanged` it calls
`moveServerToCurrentAddress()`, which rebinds when **the IP changed or the
server stopped listening** (local 500 ms socket connect to its own port).
- **Found on the A73: WiFi off/on returns the *same* IP but the listen socket is
  dead** (connection refused). The first version only compared addresses and
  would have silently lost the stream on every WiFi blip — hence the liveness check.
- After a rebind, the current item is re-issued by state: Playing → replay at the
  interpolated position; Buffering → replay from 0; Idle that came from a
  mid-track "stop" within 15 s (`IP_CHANGE_STOP_WINDOW_MS` — the real Node will
  report stop when our stream dies) → replay at `lastKnownSecs`; Paused →
  `reloadOnResume`, so `resume()` replays instead of `/Play` (the Node's paused
  stream points at the dead URL); taken over → nothing (`resume()` replays anyway).
- `play()` also calls `moveServerToCurrentAddress()` first, so a track change never
  builds a URL on a stale address even if the callback hasn't fired yet.
- One change fires several callbacks and the rebind suspends, so it's serialized
  with a `Mutex` — otherwise two callbacks could both rebind and both replay.
  Ktor start/stop block (~1.2 s), so they run on IO.
- **Tested on the A73 against a fake Node** (Python stand-in on the PC — the guest
  WiFi isolates clients, so BluOS traffic went over `adb reverse tcp:11000` and
  Spindle connected via manual IP `127.0.0.1`; the phone's own server was probed
  from inside the phone with `toybox nc`). WiFi off/on → "stopped listening —
  restarting", new port, fresh `/Play?url=` + `/Play?seek=` at the right
  position, new URL serves 206 with the right `icy-name`. Also confirmed there:
  item 1 reads our echoed URL as OURS past the 10 s grace, and item 2's WifiLock
  + wake lock are held while streaming and released after.
- Not testable here without root: an actual *different* IP (DHCP decides).

### Node unreachable / reconnection — 2026-09-23

- **Crash fixed first:** `BluOsClient` throws on any network failure, and
  `PlaybackController` calls outputs inside `scope.launch` with no handler — so
  pressing pause with the Node gone **crashed the app** (reproduced on the A73:
  `ConnectException` on main). Every Node call in `NodeOutput` now goes through
  `nodeCommand()`, which logs and enters outage handling instead of throwing.
  **Rule: no `AudioOutput` method may throw.**
- **Outage** (`enterOutage`): entered on any failed command, or 2 consecutive
  failed `/SyncStatus` polls (that loop always runs while connected, so it's the
  watchdog; the `/Status` loop only backs off). Records a resume point — Playing
  → interpolated position, Buffering → its item from 0 — and shows Buffering.
  Retry backoff is now 5/10/20/30 s (was a flat 5 s) for both loops.
- While in outage: `/Status` results are not applied; `play()` just updates the
  resume point (no waiting out a timeout); `seek()` moves it; `stop()` clears it;
  **`pause()` turns it into a plain Paused** — a user pause must not auto-resume
  when the Node returns (`pauseAtResumePoint`).
- **Recovery** (`recoverFromOutage`, on the first successful poll): one immediate
  `/Status`; if it's still our token streaming → just continue, else replay the
  resume point (play + seek).
- **Rediscovery at a new IP:** `BluOsDiscovery` (NSD) in 60 s windows with 60 s
  gaps while in outage; a candidate at a different address is the same Node if its
  `/SyncStatus` `mac` matches (`BluOsSyncStatus.mac` — verified in the captured
  real XML), else the same `name`. Adopting it restarts both loops at once. Needs
  `NEARBY_WIFI_DEVICES` on 33+; without it, rediscovery finds nothing and the loops
  keep retrying the old address.
- **Give-up after 10 min** (`OUTAGE_GIVE_UP_MS`): stop rediscovery and turn the
  pending resume into Paused — Buffering holds the WiFi/CPU locks, and music
  shouldn't start by itself hours later. Play replays from the resume point.
- Known gap: after adopting a new address, `AudioOutputSwitcher.target` still
  holds the old `BluOsPlayer` (same name, old host) — cosmetic unless the picker
  compares hosts.
- **Tested on the A73 against the fake Node:** (A) Node gone + pause → no crash,
  Paused at 0:23. (B) play while gone, fake Node restarted fresh (= rebooted) →
  "reachable again (ourStreamSurvived=false)", `/Play?url=` + `seek=23`, playing
  again. (C) ~20 s blip with the stream intact → outage detected in ~6 s,
  recovered with **no** new `/Play`, playback continued.
- Not testable with the fake Node: NSD rediscovery (mDNS doesn't cross
  `adb reverse`) — on the real-Node checklist.

Testing tip: `adb shell svc power stayon usb` keeps the screen from locking
mid-test (restore with `svc power stayon false`; the user's value was 0).

### Seek sync on the Node — 2026-09-24

User report (real Node, 2026-09-23): switching to the Node mid-song gave a
garbled "alien" noise, then playback from 0:00 while the app showed +8 s.
`AudioOutputSwitcher` (and every replay path) sends `/Play?url=` then
`/Play?seek=` within milliseconds — but bluos-api.md says seek only works with
`canSeek=1`, and Phase 0 only ever seeked an already-playing stream. Best
explanation: the early seek made the Node fetch a second byte range mid-decode.
**Not proven on hardware** — the Node test plan has the check.
- `NodeOutput.seek()` now **holds** the seek (`pendingSeekSeconds`, state stays
  Buffering) until a status shows our stream with `canSeek=1`, then sends it
  from `applyStatus`. Dropped after 6 s (`PENDING_SEEK_TIMEOUT_MS`) if the stream
  never becomes seekable — follow the Node rather than hang in Buffering.
- After any seek, the UI shows the target (`seekTarget`) until the Node's `secs`
  land within [target−1, target+5] (it lands ~+2 s) or 8 s pass — no snap back
  to the pre-seek position. Seek while paused updates the Paused position.
- `MediaHttpServer` logs every `GET /t/{token}` with its `Range` header — the
  evidence to capture if the glitch recurs.
- Remaining by design: ~1 s of the song's start may be heard before the seek
  lands (no single-call play-at-offset in the documented API; trying
  `/Play?url=…&seek=N` at the Node is listed as an experiment).

### Samsung battery guidance — 2026-09-24

Item 6. From the "Spindle battery guidance" mockup canvas: the user picked A + B.
- `BatteryGuidance` (data/prefs): `isUnrestricted()` =
  `PowerManager.isIgnoringBatteryOptimizations` (One UI "Unrestricted" → true,
  Optimized/Restricted → false; checked on the A73). The button opens
  `ACTION_APPLICATION_DETAILS_SETTINGS` (App info → Battery). **Never
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — Play restricts it to a few app
  kinds, not music players.
- **B (one-time):** after the first successful switch to a Node while restricted,
  the Play on sheet's content becomes `BatterySetupContent` (3 steps). The
  `battery_setup_shown` DataStore flag is set when it shows. Coming back from
  settings with Unrestricted set closes the sheet (`LifecycleResumeEffect` →
  `refreshBatteryState`). Any dismissal ends the step (`finishBatterySetup`) —
  the VM outlives the sheet, so a stale flag once made the sheet close itself on
  reopen.
- **A (persistent):** `BatteryWarningCard` after the Node list while target is a
  Node and still restricted. "Not now" hides it until the next app start (in memory).
- Amber caution colour is local to `BatteryGuidanceViews.kt` (darker in light theme).
- **Fixed along the way (older bug):** `NowPlayingScreen` returned early while
  nothing was loaded, and a switch passes through Idle — so `showOutputPicker`
  reset and **every output switch closed the picker**. The picker state and sheet
  now sit above that return. The sheet also opens fully expanded now (B's buttons
  were below the fold half-expanded).
- Verified on the A73 with the fake Node: B after the switch → Open settings →
  App info → Battery → Unrestricted → back, and the sheet closes; A appears when
  restricted, "Not now" hides it. Test tip: reset the flag by trimming
  `files/datastore/settings.preferences_pb` via `run-as` (read it with `adb
  exec-out`, not `adb shell` — the pty turns `\n` into `\r\n`).
- Known gap: a Node connected by manual IP isn't listed as its own ACTIVE card
  (only discovered players are), so card A sits alone in that case.

### Verification at the Node

**Full step-by-step plan: `docs/PHASE3-NODE-TESTS.md`** (setup, Logcat filter,
expected log lines, how to change the phone's and the Node's IP). Run it when the
user is at the Node; record results back here.

## Artist artwork — Deezer, opt-in per artist — 2026-09-22

User wanted the Artists screen to show a band/artist photo. Explicit design
constraint from the user: **no automatic scan, ever** — every fetch is a
deliberate per-artist tap, never a one-time background pass, since it means
sending library artist names to a third-party service.

- **`ArtistArtworkEntity`/`ArtistArtworkDao` are a separate Room table, not
  a column on `ArtistEntity`.** `MediaStoreScanner` upserts a fresh
  `ArtistEntity` for every artist on every rescan (manual or
  `ContentObserver`-triggered) — a column living on that row would get
  silently wiped back to null on the next scan. The separate table is never
  touched by a scan, and Room's Paging invalidation still picks up writes to
  it automatically (a `LazyPagingItems` re-diffs when the underlying tables
  it was built from change — here that's `artist_artwork`, joined at the UI
  layer via a `Map<Long, String>` from `ArtistArtworkRepository
  .artworkByArtistId`, not in the `PagingSource` query itself). Schema
  bumped to v4; destructive migration (existing policy, see `docs/PLAN.md`
  §7 / the `DatabaseModule` comment) — a clean rescan, not a real migration.
- **`DeezerArtistArtworkClient`** — Deezer's public `/search/artist`, no API
  key required for this endpoint. **`DeezerArtistArt`** is the pure
  JSON-parsing half, pulled out specifically so it's unit tested
  (`DeezerArtistArtTest`) without needing a live network call — same
  reasoning as `HttpRange`/`BluOsXmlParser`. Needed `testImplementation(libs
  .json)` (the real `org.json` jar): Android's own `org.json` classes are
  stubbed to throw `"Stub!"` on the plain-JVM unit-test classpath, only the
  real device/emulator runtime has a working implementation.
- **Found on-device: remote images loaded nothing, silently, no error.**
  Coil 3 splits network image loading into a separate artifact from
  `coil-compose` — without `coil-network-ktor3`, `AsyncImage` has no
  `Fetcher` for `http(s)://` models at all, so it fails silently rather than
  showing a broken-image state (worth remembering — this project's other
  artwork, `TrackArtwork`/`AlbumArtwork`, only ever loads local `content://`
  URIs, so this gap was invisible until a *remote* URL was tried for the
  first time). Added at the exact same pinned version as `coil-compose`
  (3.3.0) — see CLAUDE.md's "Version pins" section for why that pin exists
  and matters. Confirmed working on the A73 after adding it.
- **UI**: `ArtistRow`'s circular avatar is the one and only fetch trigger —
  a dashed-outline "+" placeholder when there's no artwork yet, a spinner
  while `ArtistsViewModel.fetchingIds` contains that artist's id, the real
  photo once `artworkByArtistId` has an entry for it. No separate button,
  no badge — picked over a global "fetch all" banner in the mockup
  comparison specifically so every single fetch stays an individual,
  visible, undo-free user action.

## Bottom nav bar — floating glass island — 2026-09-22/23

Replaced the stock `NavigationBar` (first pass was just an 85%-alpha
`surfaceContainer`; user wanted "more transparent, more shine/uniqueness").
Built from the "Spindle bottom bar concepts" mockup canvas — the shipped
version combines all three concepts: floating island (B), a glowing line over
the active tab (C), and a real frosted blur (A).

- **`FloatingNavBar`** (`ui/components/FloatingNavBar.kt`): a rounded island
  inset 12dp from the screen edges; only the active tab shows its label,
  inside a tinted pill; a short `setShadowLayer` glow line on the island's top
  edge marks it too. The mini-player is a matching floating glass card above it.
- **Blur via Haze** (`dev.chrisbanes.haze:haze`, new dependency, 1.7.2):
  `hazeSource` on the `NavHost`, `hazeEffect` through `Modifier.glassSurface()`
  on the island and the mini-player. Real blur needs API 31+; below that
  Haze falls back to a near-opaque tint (`spindleGlassStyle().fallbackTint`).
- **Content scrolls visibly behind the overlay**: the `NavHost` no longer
  takes the Scaffold's bottom inset; instead every scrolling screen adds
  `LocalBottomOverlayPadding.current` as bottom `contentPadding` (the Tracks
  fast-scroll rail too) so its last row stays reachable. Any new list screen
  must do the same.
- **Don't animate the active label's width.** The first version used
  `AnimatedVisibility(expandHorizontally() + fadeIn())`; that relayouts the
  island every frame and made tab switches measurably janky on the A73 (90th
  percentile frame 13–22ms vs. 7–9ms on the old bar; fade-only still spiked
  to ~22ms at the 95th). The label now appears instantly; pill color, icon
  tint and glow alpha still animate. Measured by tapping through all 5 tabs
  with `dumpsys gfxinfo` reset/dump, 3 rounds after a warm-up. The blur
  itself only costs ~2ms/frame (GPU percentiles unchanged).

## Stray taps while flicking — ScrollTapGuard — 2026-09-23

User report (A73): fast scrolling kept opening artists / playing tracks by
accident. A raw `getevent` recording of the user's own flicks showed the
cause: between rapid strokes the panel reports **zero-movement touches of
8–36ms**, often within 8–17ms of the previous flick's lift-off. No movement
means Compose can only read them as taps. (Synthetic `adb input` gestures
never reproduced it — they're too clean.) Short lists make it worse: the
list hits its end almost instantly, so the stray touch lands on a settled list.

`ui/components/ScrollTapGuard.kt` — `rememberScrollTapGuard(listState)` +
`tapGuard.guard { ... }` drops a row tap while the list is scrolling or
within 400ms of it stopping. Applied to the 5 tab lists (Artists incl. the
Deezer "+" avatar, Albums, Tracks incl. Shuffle All, Folders, Playlists);
any new list screen should use it too. Doesn't catch the stray touches that
came 0.5–1s after a flick — those are indistinguishable from real taps. User
accepted it as "works okay", will report if it recurs.

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

From Claude Code on this machine, run `sh ./gradlew ...` from the Bash tool:
`gradlew.bat` fails with "-classpath requires class path specification" (it
passes an empty `-classpath` that the installed JDK rejects). adb is at
`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe` (not on PATH); prefix adb
shell calls with `MSYS_NO_PATHCONV=1` so Git Bash doesn't mangle `/sdcard/...`.

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

## Smart shuffle + AI playlists — direction agreed 2026-09-23 (smart shuffle built 2026-09-24)

Raised 2026-09-22 as a deferred discussion; direction agreed with the user
2026-09-23. **Order: finish Phase 3 first, then design these** (mockups before UI,
per working style). Smart shuffle, play-history logging and the AI-playlist matcher +
fallback builder are built (below).

User's constraints (non-negotiable):
- **Size:** bounded, small storage. Budget ~1–2 MB for all history/stats. Never
  bundle an LLM in the APK (a 0.5–1.5 GB model violates both size and speed).
- **Speed:** normal app use must feel exactly as fast as today. AI generation may
  take up to a few minutes worst case, but always in the background with the app
  fully usable meanwhile.
- **Cost:** the user will not pay for cloud AI.

Agreed design direction:
- **Smart shuffle = no AI.** Rules + listening history, fully on-device: spread
  same artist/album apart, weight favourites up, recently-skipped down. Ordering
  ~3900 tracks is milliseconds — same feel as current Fisher-Yates shuffle
  (`QueueManager.setShuffled`, which guarantees each track once per pass — keep
  that property).
- **AI playlists = Gemini Nano (on-device, via Android's system AICore — 0 MB
  added to the app)** as the primary path. Small context window, so it can't see
  the whole library: use it to interpret the request (mood/genre/era/tempo
  attributes), then the app selects matching tracks locally. The AI must only
  ever pick from tracks the user owns. Availability on the S25+ is expected but
  must be verified on-device before building on it; the ML Kit GenAI dependency
  needs the usual ask-before-adding.
- **Fallback when the phone lacks Gemini Nano:** optional cloud AI where the
  *end user* registers and supplies their own API key (and pays, or uses a
  provider's free tier). The developer never pays and never ships a key in the APK.
  If cloud is used, library metadata leaves the phone → privacy policy must say so
  (Phase 4).
- Optional later: on-device audio analysis (tempo/energy) — only while charging +
  idle, incrementally; ~1–2 MB of features. Not in the first version.

### Smart shuffle — built 2026-09-24

From the "Spindle smart shuffle" mockup canvas; the user picked A + B + C and held D
(per-track "why" tags in the queue — revisit once there's a few weeks of history).
- **`SmartShuffle`** (playback/, pure, `SmartShuffleTest`): weighted shuffle
  (Efraimidis–Spirakis, `ln(u)/w`) so every track still plays exactly once, then
  `spread()`. Weights: no history = 1; favourites ×(1 + 2·fullListens/plays); often
  skipped (skips ≥ half of plays) ×0.25 — both only from 2+ plays; rediscover ×2 if
  unheard 60+ days, ×0.5 if heard in the last day.
- **`spread()` is not plain greedy** — greedy left one artist's leftovers bunched at
  the end even when an alternating order existed (caught by the test). Each slot takes
  the earliest non-clashing track (artist *or* album), but an artist needing every
  other remaining slot (`count * 2 > remaining`) goes first.
- **History summary:** `PlayEventDao.statsByTrackKey()` — full listen = ≥80% heard,
  skip = SKIPPED before half (listenedMs, not endReason alone). Keyed by `trackKey`.
- **`QueueManager.ShuffleMode` OFF/SHUFFLE/SMART** with a pluggable `ShuffleOrderer`
  (plain shuffle = `RANDOM`, unchanged behaviour; `isShuffled` is derived). Shuffle ↔
  smart reorders with the current track pinned first; OFF restores the original order.
  `SmartShuffler` loads rules + stats (IO) and returns the orderer, so
  `PlaybackController.setShuffleMode(SMART)` / `playTracksSmartShuffled` launch.
- **Rules** (4 booleans, default on) in `SettingsRepository.smartShuffleRules`; they
  apply from the next smart shuffle, not to the queue already playing.
- **UI:** Now Playing's shuffle button cycles Off → Shuffle → Smart (`ShuffleModeButton`,
  sparkle badge + tinted circle when smart, the new mode's name for 1.5 s per tap);
  Tracks has "Shuffle all" + "Smart shuffle" pills. The mode label pops up centred
  in the time row (overlaid via `matchParentSize` + unbounded) — next to the button
  it covered the elapsed time. **Long-press** either smart
  control → `SmartShuffleSheet` (ui/shuffle/). `SparkleIcon` and `Modifier.glow`
  live in ui/components. Checked on the A73: pills, sheet (reads the real listen
  count; rule changes persist), button cycle, label placement.

### AI playlists — matcher + no-AI fallback — built 2026-09-24

From the "Spindle AI playlists" mockup canvas (https://claude.ai/artifact/DoqEZnW4A6NU6hEGtwM6wD):
the user picked A (AI card on Playlists + describe sheet) + C ("Make a playlist like
this") + D (draft) + E (engine settings), with B (chip builder) as the fallback, and
asked for the "modern/exciting" v2 look. **Built so far: B, D, the card (fallback
form), the matcher. Not built: A's free-text prompt, C, E** — they need an AI engine.
- **Genre was never scanned** — `MediaStoreScanner` wrote `genre = null` since
  Phase 1. Now: `MediaStore.Audio.Media.GENRE` on API 30+, the `Genres`/`Members`
  tables on 26–29. Fills on the next scan (the app rescans at startup).
- **`data/smartplaylists/`** (pure parts unit tested in `PlaylistMatcherTest`, 13 cases):
  `Genres` splits free-text tags (`;` `,` `/` `|`, dedupe case-insensitively — the A73
  has "Rock, Rock"); `PlaylistCriteria` (decades, genres, length 20 tracks / ~1 h /
  ~2 h, leanOnHistory; empty = any); `PlaylistMatcher.pick` = filter → weighted draw
  (`SmartShuffle.weight`, all rules on, when leaning on history) → fill to length
  with ≤ max(2, n/5) tracks per artist, relaxed if short → `SmartShuffle.spread`.
  `LibraryFacets` builds the chips from `TrackDao.allTags()` (genre+year only).
  `SmartPlaylistGenerator` (singleton) runs it and **holds the draft in memory**
  (shared by sheet and draft screen; lost on process death — the screen says so).
- **`AiPlaylistEngine`** interface + `AiEngineStatus` (Ready/Downloading/Unavailable);
  `NoAiPlaylistEngine` is bound (always Unavailable). Gemini Nano replaces the binding.
  Track picking never moves into the engine — it only produces `PlaylistCriteria`.
- **UI (`ui/smartplaylists/`):** `MakePlaylistCard` tops the Playlists list;
  `BuildPlaylistSheet` (era/genre chips only for values that exist, live "N tracks
  match"); `DraftPlaylistScreen` (route `playlist_draft`): mosaic, DRAFT badge, tap
  name to rename, "built from" chips (✕ drops a filter and regenerates; a typed name
  survives, a suggested one follows the filters), Adjust reopens the sheet, Play /
  smart shuffle / Remix, ✕ per track, Save → normal playlist, replaces the draft in
  the back stack. The "new playlist" + moved from the FAB to the top bar, left of import.
- **Look (`ui/components/AiGlow.kt`):** `edgeGlint` (rotating sweep-gradient light on a
  hairline border), `aiGlowBehind` (two drifting radial blobs), `TwinkleSparkle`.
  Blob centres stay one radius below the element's top: a `LazyColumn` clips what's
  drawn above its own top, which showed as a hard line under the top bar.
- Checked on the A73 (10 real tracks; the other 167 audio files are excluded voice
  notes): card, sheet with real genre/decade chips, whole-library and Rock drafts,
  drop chip, Save → playlist detail, Back → Playlists. Left a "Library mix" test
  playlist on the A73.

### AI playlists — Gemini Nano — built 2026-09-24, NOT yet run on a supported phone

- **Dependency:** `com.google.mlkit:genai-prompt:1.0.0-beta4` (approved by the user;
  beta, no SLA). Compiles against Kotlin 2.2.10 — no stdlib trap like Coil's. The model
  lives in Android's AICore, not the APK. Supported devices include the Galaxy S25
  family. Limits: input < ~4000 tokens, per-app inference quota, no unlocked bootloaders.
- **API as actually shipped** (read from the jar with javap — the docs page is thin):
  `Generation.getClient()` → `GenerativeModel`; suspend `checkStatus(): Int`
  (`FeatureStatus.AVAILABLE/DOWNLOADABLE/DOWNLOADING/UNAVAILABLE`); `download():
  Flow<DownloadStatus>` (`DownloadStarted(bytesToDownload)`, `DownloadProgress(
  totalBytesDownloaded)`, `DownloadCompleted`, `DownloadFailed(e)`); suspend
  `generateContent(generateContentRequest(SystemInstruction(..), TextPart(..)) {
  temperature; topK; maxOutputTokens })` → `candidates[0].text`; `GenAiException`
  has `errorCode` and `retryDelay`.
- **`GeminiNanoEngine`** (bound as `AiPlaylistEngine`): status on creation (any throw
  → Unavailable), `startDownload()`, `interpret()` never throws — failures become a
  user-facing message.
- **`CriteriaPrompt`** (pure, `CriteriaPromptTest` 6 cases): system instruction + a user
  text listing the library's own genres (top 80) and decades, then the request. The
  model answers JSON `{genres, decades, length, name}`; the parser tolerates fences
  and chatter, maps genres through `Genres.key` to real library names and **drops
  anything invented**, accepts "90s"/"'70s"/1990. Worst case of a bad answer = a
  wider playlist, never a foreign track.
- **Flow:** `SmartPlaylistGenerator.makeFromRequest` runs in the generator's own scope
  (the user can leave the screen) and exposes `aiRequest` (Working / Failed / Ready);
  Playlists shows it as `AiRequestRow` under the card. Retry reuses the sheet's
  length/history choices. Drafts carry `request` → "AI DRAFT" badge, the request in
  quotes, "Spindle heard" chips; Remix/drop keep it.
- **UI:** `MakePlaylistCard` switches on status — Ready: prompt pill → `DescribePlaylistSheet`,
  one-tap ideas, "Or pick era and genres yourself"; otherwise the builder button (+ a
  "Set up on-device AI" link when Downloadable, progress when Downloading). The gear
  opens `AiSettingsScreen` (route `ai_settings`, mockup E) — only the on-device card;
  **own-key cloud engine not built**. Enter in the describe box sends (Samsung's
  keyboard typed a newline despite `ImeAction.Done`).
- **Verified on the A73:** real engine → "Gemini Nano status: Unavailable" (clean API
  answer, not an exception), fallback card + E's "Not available". The Ready UI was
  checked with a temporary fake engine (deleted, never committed): card, sheet, working
  row, ready row → AI draft (invented "Shoegaze" dropped), failure row + Retry.
- **Open, for the S25+:** real status/download, reply quality and latency, whether
  inference works with the app backgrounded, the quota. **Known weakness:** filters are
  ANDed over tags, so sparse tags give tiny drafts (70s + Rock = 1 track on the A73) —
  consider relaxing to OR / widening when a draft falls far short of its length.

### Play history logging — built 2026-09-23

Started early so real data accumulates before the features above are built.
- Separate Room DB **`spindle-history.db`** (`data/history/HistoryDatabase`),
  *not* `AppDatabase`: the library DB uses destructive migration (it's a
  rescannable cache); history can't be rebuilt, so **schema changes here need a
  real `Migration`, never destructive fallback.** Schema JSON exported under
  `app/schemas/...HistoryDatabase/`.
- One `play_events` row per listen, written when the listen ends:
  `trackId`, `trackKey` (FNV-1a 64-bit of normalized artist/album/title — survives
  MediaStore id changes), `startedAtMs`, `listenedMs` (time actually in Playing,
  pauses excluded), `trackDurationMs`, `endReason` (COMPLETED / SKIPPED / PREVIOUS /
  REPLACED / ERROR). ~50 bytes/row. Aggregates are computed with SQL on demand,
  not stored.
- `PlayHistoryRecorder` (playback/) is driven by `PlaybackController`:
  `onItemStarted` in `playItem`, `onState` for every output state, `endCurrent`
  with SKIPPED in `next()`, PREVIOUS in `previous()`, COMPLETED in `advance()`;
  anything else that starts a new item closes the old listen as REPLACED. Only the
  insert runs on IO. Listens with 0 ms heard (tapped through while buffering) are
  dropped. A listen in progress at process death is lost — acceptable.
- Retention: events older than 365 days are pruned on startup.
- Works identically for local and Node output (both emit Playing/Ended).
- No UI yet. A "clear listening history" setting belongs in Phase 4 (privacy).

## UI pass — titles, detail headers, playlists, artist — 2026-09-24

From the "Spindle UI pass" mockup canvas (https://claude.ai/artifact/R11zdFyrzmZg74XJuuNRvn):
the user picked Playlists A (mosaic rows), Detail A (side-by-side header), Titles C
(title + count + glass buttons), Artist A (full-bleed photo). Checked on the A73.
- **`LargeTitleBar`** (ui/components) replaces TopAppBar on all 5 tabs: 34sp black
  title, a count line (`countLabel`/`durationLabel`; Tracks uses `TrackDao.observeStats`,
  Albums/Artists `observeCount`), round `GlassIconButton`s. Pinned, not collapsing.
- **`DetailHeader` + `DetailTopBar`** (album, folder, playlist): art left, label, bold
  title, accent line, meta, `GlowPlayButton` + `SmartShuffleButton` (these screens gained
  smart shuffle). **The list must start at the screen top** — pass the Scaffold's top
  padding as `contentPadding`, not a modifier — so the header's violet glow reaches under
  the clear top bar; the bar fills in and shows the title once item 0 scrolls away.
- **Rows are transparent now** (`TrackRow`, `PlaylistTrackRow`) unless current/dragged:
  an opaque `Surface` cut the header glow off with a hard edge.
- **Playlists list:** `CoverMosaic` (shared with the draft screen) from
  `PlaylistRepository.coverTracks` (first 40 tracks, one per album, lazily per row),
  "N tracks · duration" (`PlaylistWithCount.totalDurationMs`), rename/delete in a ⋮ menu.
- **Artist screen:** photo 400dp full-bleed under the top bar with top + bottom scrims,
  44sp name; no photo → album-cover backdrop + "Find photo" chip (same opt-in Deezer
  fetch as the Artists tab). Albums `LazyRow`, then "Most played" (history plays per
  `trackKey`, loaded once) or "Tracks", top 5 + "All N tracks".
- **Deezer placeholder:** for artists without a photo Deezer returns a grey silhouette
  whose picture id is `d41d8cd98f00b204e9800998ecf8427e` (MD5 of ""). Filtered in
  `DeezerArtistArt.isPlaceholder` both on fetch and when reading saved rows.
- Known: the status bar strip stays solid above the artist photo — `AppNavHost` pads the
  NavHost by the status-bar inset for every screen; changing that is app-wide.

### Shuffle pill, queue swipes, boost — 2026-09-24

From rows 5–6 of the same canvas: user picked shuffle C and the swipe design.
- **`ShufflePair`** ("Shuffle | ✦ Smart", ui/components/DetailHeader.kt) next to the
  play button on album/folder/playlist/artist; Play = in order. Long-press Smart →
  `SmartShuffleSheet`. VMs gained `playShuffled()` (`playTracksShuffled`).
- **`SwipeToRemoveOrBoost`** (ui/components/SwipeActions.kt) replaces the trash icon on
  queue and playlist rows: left = remove + Undo snackbar, right = boost/un-boost (springs
  back). Two traps hit on the A73:
  - **Don't use `SwipeToDismissBox`'s `onDismiss`** — its effect is keyed on the lambda,
    so every recomposition while settled refired it (boost flipped back and forth, row
    stuck half-open). A `LaunchedEffect(state.settledValue)` with `rememberUpdatedState`.
  - **Don't use `rememberSwipeToDismissBoxState`** — it's saveable, and a lazy list keeps
    saved state per key, so a row restored by Undo came back already dismissed and removed
    itself again. Plain `remember { SwipeToDismissBoxState(Settled, threshold) }`.
  - A clear row (playlist rows, for the header glow) must go opaque while swiping, or the
    action underneath shows through — the content lambda gets `swiping`.
- **Undo:** queue → `QueueManager.restore(id, index)` (remove only drops the index from
  `order`, the item stays in `items`; `QueueManagerTest` case). Playlist →
  `PlaylistRepository.restoreEntry` re-inserts the same cross-ref id + position.
- **Boost:** `SettingsRepository.boostedTrackKeys` (DataStore string set of history
  `trackKey`s). `SmartShuffle.weight(..., boosted)` × `BOOST` (3.0) when the new
  `SmartShuffleRules.boosted` rule is on (5th switch on the sheet); `SmartShuffler` passes
  the set. **One-shot:** `PlayHistoryRecorder` clears a track's boost when a heard listen
  is recorded. Queue/playlist rows show a `BoostTag`. Affects the next smart shuffle only;
  the queue already playing isn't reordered.
- Verified on the A73: pill, boost + tag + snackbar, remove + Undo on both screens, drag
  reorder still works beside the swipes, boost used up after a few seconds of play, rule
  in the sheet. The test playlist "Library mix" on the A73 lost 4 tracks during testing.

### Sliding player, mini-player output + progress, end of queue — 2026-09-24

Rows 7–9 of the canvas: user picked mini-player A, the sliding player, end-of-queue A.
- **`PlayerSheet`** (ui/player) replaces the `now_playing` route (removed). One sheet drawn
  over the Scaffold in `AppNavHost`: collapsed it *is* the mini-player, in a 64dp slot the
  bottom bar reserves (`MiniPlayerHeight`, measured with `onGloballyPositioned`); it grows
  to full screen as it's dragged/tapped, the mini-player fading along its top edge, the
  nav island sliding away, the list dimming. `NowPlayingScreen` is laid out full-screen
  inside and revealed from the top (`wrapContentHeight(Top, unbounded) + requiredHeight` —
  without it the taller child is centred and the middle of the player showed mid-slide).
  Back / ⌄ (was ←) collapse it; the queue button snaps it shut and navigates to `queue`.
  - The **sheet** carries `anchoredDraggable`, not the mini-player: the mini-player is
    removed once faded, and a gesture on a removed node is cancelled.
  - **Settling is by direction, not velocity**: moved ≥12% from where it rested → finish
    the move (`settleTarget`, custom `SheetFling`). The sheet moves with the finger, so the
    velocity measured on it was ~0 and a quick flick up snapped back (A73).
  - `SheetNestedScroll` hands drags on the player's scrollable art/title block to the sheet.
- **Mini-player**: output pill (phone/speaker icon + name → `OutputPickerSheet`) and a 2dp
  progress line with a glow (`rememberPlaybackProgress`, interpolated twice a second).
- **End of queue**: `PlaybackController.finishQueue()` stops the output but **keeps the
  queue** (`QueueState.finished`) — it used to clear it, and Now Playing's early return then
  showed a black screen. Now Playing shows `FinishedNowPlaying` ("Queue finished", Spindle
  mark, "Play again" / "Shuffle again" / "Smart shuffle again"); the mini-player shows
  "Queue finished · N tracks". Play → `replay()` → `QueueManager.restart(orderer)`: back to
  the top, re-ordered by a fresh shuffle / smart shuffle when a shuffle mode is on
  (`QueueManagerTest` cases).
- **Boost now needs a real listen** to be used up: ≥ min(30 s, half the track) — a 1 s
  skip-through spent it on the A73.
- README: `.github/assets/spindle-logo.svg` (the app icon as SVG) beside the h1.
- Verified on the A73: pill + progress, slow drag both ways (follows the finger), tap to
  open, short flicks both ways, Back, queue from the player, output pill → picker, skip
  past the end → finished player + mini-player, Play again (in order), Shuffle again
  (new order). The player now draws edge to edge under the status bar; the artist-photo
  strip is a separate, still-open issue (AppNavHost pads every route by the top inset).
