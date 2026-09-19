# Phase 1 — the player, offline

Goal: a usable local music player on the phone, with **no networking at all**.
Ends with something you'd actually use on headphones. The Node arrives in Phase 2.

Work top to bottom. Each task should end with the app building and running.

---

## 1. Project setup

- [ ] New project, Empty Compose Activity, Kotlin, minSdk 26
- [ ] Package name without "bluos"/"bluesound" (see CLAUDE.md)
- [ ] Version catalog (`gradle/libs.versions.toml`) for every dependency
- [ ] `gradle.properties`: parallel, caching, configuration-cache, `-Xmx4096m`
- [ ] Hilt with **KSP**, `@HiltAndroidApp`
- [ ] Timber, initialised in `App.kt`
- [ ] Git repo, `.gitignore`, first commit
- [ ] Windows Defender exclusions for the project dir, `~/.gradle`, `~/.android`

**Check:** clean build under ~60s incremental; app launches showing "Hello".

## 2. Domain model

- [ ] `core/model/`: `Track`, `Album`, `Artist`, `Playlist`, `QueueItem`,
      `PlaybackState`, `OutputCapabilities`
- [ ] `Track` holds a content URI, not a file path
- [ ] `playback/AudioOutput.kt` and `MediaSource.kt` interfaces (see CLAUDE.md)

No implementations yet. Pure Kotlin, no Android imports in `core/model`.

## 3. Room

- [ ] `AppDatabase`, Room with **KSP**
- [ ] Entities: `TrackEntity`, `AlbumEntity`, `ArtistEntity`, `PlaylistEntity`,
      `PlaylistTrackCrossRef`
- [ ] DAOs returning `Flow<List<…>>` and `PagingSource` where lists are long
- [ ] Indices on `artistId`, `albumId`, and a sort-name column
- [ ] Migration strategy decided now (destructive is fine pre-release)

**Check:** an instrumented test inserts 5000 rows and queries them.

## 4. MediaStore scanning

- [ ] `READ_MEDIA_AUDIO` permission with a rationale screen (and the pre-33
      `READ_EXTERNAL_STORAGE` fallback)
- [ ] `MediaStoreScanner`: one cursor query, batched insert into Room
- [ ] Off the main thread, progress reported as a `Flow`
- [ ] `LibraryRepository` exposes library data from Room only
- [ ] Manual "rescan" action in Settings
- [ ] `ContentObserver` on the audio collection to detect new files

**Check:** scans ~3900 tracks. Record how long it takes — if it's over ~15s,
optimise before moving on. Compare the count against Musicolet's 3906.

## 5. Browse UI

- [ ] Navigation: bottom bar — Artists / Albums / Tracks / Folders / Playlists
- [ ] `ArtistsScreen` → `ArtistDetail` (albums) → `AlbumDetail` (tracks)
- [ ] `LazyColumn` / `LazyVerticalGrid` with **stable keys**, paged from Room
- [ ] Coil for artwork, with a placeholder; never decode art on the main thread
- [ ] Fast-scroll or alphabet index (3900 tracks is a lot of scrolling)
- [ ] Sort options: name, date added, year
- [ ] Empty states and a loading state for first scan

**Check:** scrolling the full track list is smooth on the A73, not just the S25+.

## 6. Search

- [ ] Single search field across artist / album / title
- [ ] Room FTS or `LIKE` with an index — measure before choosing
- [ ] Debounced input, results grouped by type

## 7. Queue and playback logic

- [ ] `QueueManager`: list, current index, shuffle (Fisher-Yates over an index
      list, not the source list), repeat off/all/one, next/previous, move, remove
- [ ] Shuffle must be reversible — keep the original order
- [ ] `PlaybackController` holding a `QueueManager` and one `AudioOutput`
- [ ] Unit tests for `QueueManager` — this is pure logic, test it properly

**Check:** tests cover shuffle+repeat-all wrap-around and repeat-one.

## 8. LocalOutput

- [ ] `LocalOutput` wrapping Media3 `ExoPlayer`
- [ ] `PlaybackService` as a `MediaSessionService`, foreground
- [ ] `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + FGS type in the manifest
- [ ] Media notification with transport controls
- [ ] Audio focus, becoming-noisy (headphone unplug), lock screen controls
- [ ] `capabilities` reports `canSeek=true, isGapless=true`

**Check:** plays with the screen off; survives leaving the app; headphone unplug
pauses.

## 9. Now Playing

- [ ] Artwork, title/artist/album, progress bar with scrubbing
- [ ] Play/pause/next/previous, shuffle and repeat toggles
- [ ] Queue screen: reorder by drag, remove, jump to track
- [ ] Progress interpolated locally (matters in Phase 2 — build the habit now)

## 10. Playlists

- [ ] Create, rename, delete
- [ ] Add track / album / artist to playlist
- [ ] Reorder within a playlist
- [ ] M3U import and export

## 11. Polish before Phase 2

- [ ] Dark theme and dynamic colour
- [ ] Rotation and process-death state restoration
- [ ] Accessibility: content descriptions, touch targets, TalkBack pass
- [ ] Crash-free run through every screen on both devices

---

## Done when

You've used the app as your daily player for a week on headphones without
reaching for Musicolet. Then start Phase 2.

## Deliberately not in Phase 1

SAF folder scanning (MediaStore is healthy — revisit only if the count comes up
short), any networking, the Node, ICY, discovery, YouTube.
