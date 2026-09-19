# CLAUDE.md

Android music player. Indexes music on the phone, plays it either on the phone
or on a BluOS network player (Bluesound Node) over WiFi at original quality.

**The app is a library browser + remote control. The Node is the player.**
When output is the Node, the app serves the file over HTTP and the Node decodes it.
Do not write a playback engine for that path.

Protocol facts were measured against real hardware — see `docs/bluos-api.md`.
Trust that file over any assumption about BluOS.

## Stack

Kotlin 2.x · Jetpack Compose + Material 3 · MVVM (ViewModel + StateFlow)
Hilt · Coroutines/Flow · Room · Ktor server (CIO) · Ktor client · Media3
Coil · NsdManager · Timber · minSdk 26

Gradle Kotlin DSL with a version catalog (`libs.versions.toml`).

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

- `LocalOutput` — Media3/ExoPlayer, phone speaker/headphones.
- `NodeOutput` — Ktor HTTP server + BluOS REST.
- `PlaybackController` holds a `QueueManager` and one active `AudioOutput`, and
  must not know which implementation it has.

Package layout is in `docs/PLAN.md` §4. Follow it.

## Conventions

- One ViewModel per screen. UI state is a single immutable data class exposed as
  `StateFlow`. No mutable state escaping the ViewModel.
- Repositories return `Flow`; suspend functions for one-shot work.
- All disk and network work off the main thread, via injected dispatchers.
- Room is the single source of truth for library data. MediaStore is scanned
  into Room, never queried directly from the UI.
- Prefer `sealed interface` for states and results.
- Compose: hoist state, no `ViewModel` references inside composables below the
  screen level, stable keys in every `LazyColumn`.

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
