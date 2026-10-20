package io.github.eladimany.spindle.data.trackinfo

import io.github.eladimany.spindle.data.db.dao.TrackDao
import io.github.eladimany.spindle.data.db.entity.TrackEntity
import io.github.eladimany.spindle.data.library.LibraryRepository
import io.github.eladimany.spindle.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What a MusicBrainz lookup said about one row. */
sealed interface RowLookup {
    data object NotYet : RowLookup
    data class Found(val album: String?, val year: Int?, val genre: String?, val swapped: Boolean) : RowLookup
    data object NotFound : RowLookup
}

/** One suggested fix on the review screen. */
data class FixRow(
    val trackId: Long,
    val fileKey: String,
    /** The file's own title — kept so a split stops applying if the file is retagged. */
    val fileTitle: String,
    val durationMs: Long,
    /** "Deep Purple - Highway Star", or the file name — what the split was read from. */
    val readFrom: String,
    val source: FixSource,
    val artist: String,
    val title: String,
    /** False: neither side is a known artist; the screen asks the user to check. */
    val sure: Boolean,
    val selected: Boolean,
    val lookup: RowLookup = RowLookup.NotYet,
)

/** A track with no artist and nothing in its title or file name to go on. */
data class NothingRow(val trackId: Long, val title: String, val fileName: String, val folderName: String)

data class LookupProgress(val done: Int, val total: Int, val running: Boolean, val error: String? = null)

data class FixSession(
    /** Artist found in the title or file name. */
    val splits: List<FixRow>,
    val nothing: List<NothingRow>,
    /** Tracks with an artist but no year/genre that MusicBrainz could fill (after a lookup). */
    val fillIns: List<FixRow>,
    val lookup: LookupProgress?,
    val applying: Boolean = false,
) {
    val selectedCount: Int get() = splits.count { it.selected } + fillIns.count { it.selected }
}

/**
 * "Fix track info" (mockup screens 2–5): suggests fixes for tracks whose file has no
 * artist, optionally checks them — and fills in missing year/genre — with MusicBrainz,
 * and saves what the user accepts as [TrackInfoFixEntity] rows, then rescans so the
 * library shows them. Music files are never written.
 *
 * A singleton so a lookup keeps running (and its results stay) while the user browses.
 */
@Singleton
class TrackInfoFixer @Inject constructor(
    private val trackDao: TrackDao,
    private val fixDao: TrackInfoFixDao,
    private val musicBrainz: MusicBrainzClient,
    private val library: LibraryRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lookupJob: Job? = null

    private val _session = MutableStateFlow<FixSession?>(null)
    val session: StateFlow<FixSession?> = _session.asStateFlow()

    /** Tracks with no artist and no fix — the Tracks notice. */
    val missingArtistCount: Flow<Int> = trackDao.observeMissingArtistCount()

    /** Tracks the fill-in could help: an artist, but no year or genre, and no fix yet. */
    val fillInCount: Flow<Int> = combine(trackDao.observeMissingYearOrGenreKeys(), fixDao.observeKeys()) { keys, fixed ->
        val done = fixed.toHashSet()
        keys.count { it !in done }
    }

    /** Builds the review list from the library (cheap — no network). Keeps a running lookup's session. */
    fun load() {
        if (lookupJob?.isActive == true) return
        scope.launch { _session.value = buildSession() }
    }

    private suspend fun buildSession(): FixSession = withContext(io) {
        val known = trackDao.taggedArtistNames().mapTo(HashSet()) { TrackInfoMerge.norm(it) }
        val splits = mutableListOf<FixRow>()
        val nothing = mutableListOf<NothingRow>()
        for (track in trackDao.missingArtist()) {
            val split = TitleSplitter.suggest(track.title, track.fileName, known)
            if (split == null) {
                nothing += NothingRow(track.id, track.title, track.fileName, track.folderName)
            } else {
                splits += FixRow(
                    trackId = track.id,
                    fileKey = track.fileKey,
                    fileTitle = track.title,
                    durationMs = track.durationMs,
                    readFrom = split.readFrom,
                    source = split.source,
                    artist = split.artist,
                    title = split.title,
                    sure = split.sure,
                    // A file name is a weaker clue than the title: the user opts in.
                    selected = split.source == FixSource.TITLE,
                )
            }
        }
        FixSession(splits, nothing, fillIns = emptyList(), lookup = null)
    }

    fun toggle(trackId: Long) = updateRows(trackId) { it.copy(selected = !it.selected) }

    fun swap(trackId: Long) = updateRows(trackId) { it.copy(artist = it.title, title = it.artist, sure = true) }

    fun selectAll(selected: Boolean) = _session.update { s ->
        s?.copy(splits = s.splits.map { it.copy(selected = selected) }, fillIns = s.fillIns.map { it.copy(selected = selected) })
    }

    private fun updateRows(trackId: Long, change: (FixRow) -> FixRow) = _session.update { s ->
        s?.copy(
            splits = s.splits.map { if (it.trackId == trackId) change(it) else it },
            fillIns = s.fillIns.map { if (it.trackId == trackId) change(it) else it },
        )
    }

    /**
     * Checks the selected splits with MusicBrainz (confirms the artist, turns a reversed
     * split around, adds album/year/genre); with [alsoFillIn], also looks up every track
     * that has an artist but no year or genre. One request per second, in the background.
     */
    fun startLookup(includeSplits: Boolean, alsoFillIn: Boolean) {
        val start = _session.value ?: return
        if (lookupJob?.isActive == true) return
        lookupJob = scope.launch {
            // A second run (after an error) skips what an earlier one already answered.
            val splitTargets = if (includeSplits) start.splits.filter { it.selected && it.lookup == RowLookup.NotYet } else emptyList()
            val alreadyFilled = start.fillIns.mapTo(HashSet()) { it.trackId }
            val fillTargets = if (alsoFillIn) fillInTargets().filter { it.trackId !in alreadyFilled } else emptyList()
            val total = splitTargets.size + fillTargets.size
            var done = 0
            // One track failing (MusicBrainz busy, a slow reply) skips that track; only
            // failures in a row mean MusicBrainz is out of reach, and stop the run.
            var failuresInARow = 0
            var skipped = 0
            _session.update { it?.copy(lookup = LookupProgress(0, total, running = true)) }

            /** The answer, and false when this track was skipped. */
            suspend fun lookUp(row: FixRow, maybeSwapped: Boolean): Pair<LookupResult?, Boolean> = try {
                val result = withContext(io) { musicBrainz.lookup(row.artist, row.title, maybeSwapped) }
                failuresInARow = 0
                result to true
            } catch (e: IOException) {
                if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) throw e
                Timber.i(e, "MusicBrainz lookup skipped a track")
                skipped++
                null to false
            }

            try {
                for (row in splitTargets) {
                    val (result, answered) = lookUp(row, maybeSwapped = true)
                    if (answered) updateRows(row.trackId) { current -> current.withLookup(result) }
                    done++
                    _session.update { it?.copy(lookup = LookupProgress(done, total, running = true)) }
                }
                for (row in fillTargets) {
                    val (result, answered) = lookUp(row, maybeSwapped = false)
                    val filled = row.withLookup(result)
                    if (answered && filled.lookup is RowLookup.Found) _session.update { it?.copy(fillIns = it.fillIns + filled) }
                    done++
                    _session.update { it?.copy(lookup = LookupProgress(done, total, running = true)) }
                }
                val note = when (skipped) {
                    0 -> null
                    1 -> "MusicBrainz was busy for 1 track — tap Look up again to retry it."
                    else -> "MusicBrainz was busy for $skipped tracks — tap Look up again to retry them."
                }
                _session.update { it?.copy(lookup = LookupProgress(done, total, running = false, error = note)) }
            } catch (e: CancellationException) {
                _session.update { it?.copy(lookup = it.lookup?.copy(running = false)) }
                throw e
            } catch (e: IOException) {
                Timber.w(e, "MusicBrainz lookup stopped")
                val message = if (e is MusicBrainzBusyException) {
                    "MusicBrainz is too busy right now. Try again in a few minutes — what was found so far is kept."
                } else {
                    "Couldn't reach MusicBrainz. Check the internet connection — what was found so far is kept."
                }
                _session.update { it?.copy(lookup = LookupProgress(done, total, running = false, error = message)) }
            }
        }
    }

    fun stopLookup() {
        lookupJob?.cancel()
    }

    private suspend fun fillInTargets(): List<FixRow> = withContext(io) {
        val fixed = fixDao.getAll().mapTo(HashSet()) { it.fileKey }
        trackDao.missingYearOrGenre()
            .filter { it.fileKey !in fixed }
            .map { it.toFillInRow() }
    }

    private fun TrackEntity.toFillInRow() = FixRow(
        trackId = id,
        fileKey = fileKey,
        fileTitle = title,
        durationMs = durationMs,
        readFrom = "",
        source = FixSource.MUSICBRAINZ,
        artist = artistName,
        title = title,
        sure = true,
        selected = true,
    )

    private fun FixRow.withLookup(result: LookupResult?): FixRow {
        if (result == null) return copy(lookup = RowLookup.NotFound)
        val match = result.match
        return copy(
            // MusicBrainz's spelling ("deep purple" → "Deep Purple"); turned around if needed.
            artist = match.artist,
            title = match.title,
            sure = true,
            lookup = RowLookup.Found(match.album, match.year, result.genre, result.swapped),
        )
    }

    /** Saves every selected row as a fix and rescans; the applied rows then drop off the list. */
    fun apply() {
        val current = _session.value ?: return
        if (current.applying || current.lookup?.running == true) return
        _session.value = current.copy(applying = true)
        scope.launch {
            val now = System.currentTimeMillis()
            val fixes = current.splits.filter { it.selected }.map { row ->
                val found = row.lookup as? RowLookup.Found
                TrackInfoFixEntity(
                    fileKey = row.fileKey,
                    title = row.title,
                    artist = row.artist,
                    album = found?.album,
                    year = found?.year,
                    genre = found?.genre,
                    source = (if (found != null) FixSource.MUSICBRAINZ else row.source).name,
                    splitFrom = row.fileTitle,
                    updatedAtMs = now,
                )
            } + current.fillIns.filter { it.selected }.mapNotNull { row ->
                val found = row.lookup as? RowLookup.Found ?: return@mapNotNull null
                // Only fills what the file lacks (TrackInfoMerge) — file tags still win.
                TrackInfoFixEntity(
                    fileKey = row.fileKey,
                    album = found.album,
                    year = found.year,
                    genre = found.genre,
                    source = FixSource.MUSICBRAINZ.name,
                    updatedAtMs = now,
                )
            }
            withContext(io) {
                fixDao.upsertAll(fixes)
                library.rescan().collect { }
            }
            _session.value = buildSession()
        }
    }

    // ---- One track, by hand (screen 5) -------------------------------------------------

    suspend fun track(trackId: Long): TrackEntity? = withContext(io) { trackDao.getById(trackId) }

    suspend fun fixFor(fileKey: String): TrackInfoFixEntity? = withContext(io) { fixDao.get(fileKey) }

    /** Saves what the user typed; it wins over the file. Blank fields fall back to the file. */
    suspend fun saveManual(track: TrackEntity, title: String, artist: String, album: String, year: Int?, genre: String) {
        withContext(io) {
            fixDao.upsertAll(
                listOf(
                    TrackInfoFixEntity(
                        fileKey = track.fileKey,
                        title = title.trim().ifEmpty { null },
                        artist = artist.trim().ifEmpty { null },
                        album = album.trim().ifEmpty { null },
                        year = year,
                        genre = genre.trim().ifEmpty { null },
                        manual = true,
                        source = FixSource.MANUAL.name,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                ),
            )
            library.rescan().collect { }
        }
    }

    /** Drops Spindle's fix: the track shows the file's own info again. */
    suspend fun useFileInfo(track: TrackEntity) {
        withContext(io) {
            fixDao.delete(track.fileKey)
            library.rescan().collect { }
        }
    }

    /** "Look up the rest" on the edit sheet. Throws [IOException] when offline. */
    suspend fun lookupOne(artist: String, title: String): LookupResult? =
        withContext(io) { musicBrainz.lookup(artist, title, maybeSwapped = true) }

    private companion object {
        /** Each failure already retried for ~45 s (MusicBrainzClient), so 3 in a row ≈ out of reach. */
        const val MAX_FAILURES_IN_A_ROW = 3
    }
}
