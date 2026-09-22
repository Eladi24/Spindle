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

`PlaybackState` (in `core/model`) is the shared vocabulary both outputs speak:
`Idle`, `Buffering`, `Playing`, `Paused`, `Ended`, `Error`. `Ended` exists
specifically so `PlaybackController` has one clean signal for "advance the
queue" regardless of which output produced it — `Idle` is ambiguous between
"stopped" and "nothing loaded". `Playing.positionMs` is a snapshot as of
`capturedAtMs`; the UI interpolates locally (`positionMs + (now -
capturedAtMs)`) rather than expecting a push every second — this is required
for Phase 2 (BluOS's long-poll doesn't push `secs`), so the habit is already
built into the local-only path.

- `LocalOutput` — Media3/ExoPlayer, phone speaker/headphones. **Built.**
- `NodeOutput` — Ktor HTTP server + BluOS REST. **Not built yet (Phase 2).**
- `QueueManager` owns shuffle/repeat/next/previous/move/remove. Shuffle
  permutes an index list over a stable backing list of `QueueItem`s, never the
  list itself, so disabling shuffle restores the exact original order. Fully
  unit tested (`QueueManagerTest`, 14 cases) — no coroutines needed, it's
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

## Deferred discussion — once local playback is robust and the user is satisfied

Raised 2026-09-22, deliberately not acted on yet:

- **Revisit the shuffle feature.** Current behavior: "Shuffle All" (Tracks tab)
  and the shuffle toggle (Now Playing) both call `QueueManager.setShuffled`,
  which Fisher-Yates permutes an index list once and keeps that order until
  toggled off. The user wants to discuss this further once the player feels
  solid — don't assume the current behavior is final.
- **AI-generated playlists** — a possible future feature. Not scoped at all;
  raise it with the user before doing any design or implementation work on it.
