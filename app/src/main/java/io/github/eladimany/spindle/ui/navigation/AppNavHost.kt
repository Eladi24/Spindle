package io.github.eladimany.spindle.ui.navigation

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.eladimany.spindle.core.model.PlaybackState
import io.github.eladimany.spindle.playback.OutputTarget
import io.github.eladimany.spindle.playback.QueueManager
import io.github.eladimany.spindle.ui.components.FloatingNavBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.MiniPlayerBar
import io.github.eladimany.spindle.ui.components.NavTab
import io.github.eladimany.spindle.ui.components.countLabel
import io.github.eladimany.spindle.ui.components.glassSurface
import io.github.eladimany.spindle.ui.components.spindleGlassStyle
import io.github.eladimany.spindle.ui.folders.FoldersScreen
import io.github.eladimany.spindle.ui.library.AlbumDetailScreen
import io.github.eladimany.spindle.ui.library.AlbumsScreen
import io.github.eladimany.spindle.ui.library.ArtistDetailScreen
import io.github.eladimany.spindle.ui.library.ArtistsScreen
import io.github.eladimany.spindle.ui.library.FolderBrowseScreen
import io.github.eladimany.spindle.ui.library.FolderDetailScreen
import io.github.eladimany.spindle.ui.library.TracksScreen
import io.github.eladimany.spindle.ui.output.OutputPickerSheet
import io.github.eladimany.spindle.ui.output.displayName
import io.github.eladimany.spindle.ui.player.MiniPlayerHeight
import io.github.eladimany.spindle.ui.player.NowPlayingScreen
import io.github.eladimany.spindle.ui.player.PlayerSheet
import io.github.eladimany.spindle.ui.player.PlayerSheetValue
import io.github.eladimany.spindle.ui.player.PlayerViewModel
import io.github.eladimany.spindle.ui.player.QueueScreen
import io.github.eladimany.spindle.ui.playlists.PlaylistDetailScreen
import io.github.eladimany.spindle.ui.playlists.PlaylistsScreen
import io.github.eladimany.spindle.ui.search.SearchScope
import io.github.eladimany.spindle.ui.search.SearchScreen
import io.github.eladimany.spindle.ui.smartplaylists.AiSettingsScreen
import io.github.eladimany.spindle.ui.smartplaylists.DraftPlaylistScreen
import kotlinx.coroutines.launch

private val bottomTabs = listOf(
    NavTab(Routes.ARTISTS, "Artists", Icons.Default.Person),
    NavTab(Routes.ALBUMS, "Albums", Icons.Default.Album),
    NavTab(Routes.TRACKS, "Tracks", Icons.Default.MusicNote),
    NavTab(Routes.FOLDERS, "Folders", Icons.Default.Folder),
    NavTab(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    // Activity-scoped: the mini-player and Now Playing share one instance across the graph.
    val playerViewModel: PlayerViewModel = hiltViewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in Routes.topLevel
    // The mini-player belongs everywhere except the two screens that already show full
    // playback UI of their own — it was previously gated on isTopLevel, which hid it on
    // every detail screen (album/artist/folder/playlist) and Search too.
    val showMiniPlayer = currentRoute != Routes.QUEUE
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val queueState by playerViewModel.queueState.collectAsStateWithLifecycle()
    val outputTarget by playerViewModel.outputTarget.collectAsStateWithLifecycle()
    // Something to show in the player: a loaded track, or a queue that has played out.
    val hasPlayer = playbackState.let {
        it is PlaybackState.Playing || it is PlaybackState.Paused || it is PlaybackState.Buffering
    } || (queueState.finished && queueState.items.isNotEmpty())
    val showSheet = showMiniPlayer && hasPlayer

    // The player sheet (see PlayerSheet) is drawn over everything; the bottom bar only
    // keeps an empty slot where the collapsed sheet — the mini-player — sits.
    val sheetState = remember { AnchoredDraggableState(PlayerSheetValue.Collapsed) }
    val scope = rememberCoroutineScope()
    var rootTopPx by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableFloatStateOf(0f) }
    var miniSlotTopPx by remember { mutableFloatStateOf(0f) }
    val sheetFraction: () -> Float = {
        val offset = sheetState.offset
        if (offset.isNaN() || miniSlotTopPx <= 0f) 0f else (1f - offset / miniSlotTopPx).coerceIn(0f, 1f)
    }
    var showOutputPicker by remember { mutableStateOf(false) }
    if (showOutputPicker) OutputPickerSheet(onDismiss = { showOutputPicker = false })
    // Previewed by the mini-player's swipe crossfade — wrap on repeat-all, otherwise
    // null past either end, matching QueueManager.next()/previous()'s own boundary rules.
    val nextTrack = queueState.items.getOrNull(queueState.currentIndex + 1)?.track
        ?: queueState.items.firstOrNull()?.track.takeIf { queueState.repeatMode == QueueManager.RepeatMode.ALL }
    val previousTrack = queueState.items.getOrNull(queueState.currentIndex - 1)?.track
        ?: queueState.items.lastOrNull()?.track.takeIf { queueState.repeatMode == QueueManager.RepeatMode.ALL }

    // Screen content is the blur source; the mini-player and nav island are frosted
    // glass floating over it, so rows scroll visibly behind them.
    val hazeState = rememberHazeState()
    val glassStyle = spindleGlassStyle()

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootTopPx = it.positionInRoot().y
                screenHeightPx = it.size.height.toFloat()
            },
    ) {
        Scaffold(
            bottomBar = {
                // Top-level routes are a subset of showMiniPlayer's, so this gates the nav island too.
                if (showMiniPlayer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (hasPlayer) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .height(MiniPlayerHeight)
                                    .onGloballyPositioned { miniSlotTopPx = it.positionInRoot().y - rootTopPx },
                            )
                        }
                        if (isTopLevel) {
                            FloatingNavBar(
                                tabs = bottomTabs,
                                selectedRoute = currentRoute,
                                onSelect = { tab ->
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                                    // Slides away under the rising player.
                                    .graphicsLayer {
                                        val f = sheetFraction()
                                        translationY = f * size.height * 1.6f
                                        alpha = 1f - f
                                    }
                                    .glassSurface(hazeState, glassStyle, RoundedCornerShape(32.dp)),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            // Where the overlay is shown, content runs all the way to the bottom edge and
            // each list pads itself via LocalBottomOverlayPadding instead. Now Playing and
            // Queue have no overlay and keep the plain inset padding they always had.
            val overlayBottom = innerPadding.calculateBottomPadding()
            CompositionLocalProvider(LocalBottomOverlayPadding provides if (showMiniPlayer) overlayBottom else 0.dp) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.ARTISTS,
                    modifier = Modifier
                        .hazeSource(hazeState)
                        // No top padding: every screen's own top bar leaves room for the status
                        // bar, so a screen can also draw under it (the artist photo does).
                        .padding(bottom = if (showMiniPlayer) 0.dp else overlayBottom)
                        .consumeWindowInsets(PaddingValues(bottom = innerPadding.calculateBottomPadding())),
                ) {
                    composable(Routes.TRACKS) {
                        TracksScreen(onSearchClick = { navController.navigate(Routes.search(SearchScope.TRACKS)) })
                    }

                    composable(Routes.ARTISTS) {
                        ArtistsScreen(
                            onArtistClick = { navController.navigate(Routes.artistDetail(it)) },
                            onSearchClick = { navController.navigate(Routes.search(SearchScope.ARTISTS)) },
                        )
                    }
                    composable(
                        Routes.ARTIST_DETAIL_PATTERN,
                        arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
                    ) {
                        ArtistDetailScreen(
                            onBack = { navController.popBackStack() },
                            onAlbumClick = { navController.navigate(Routes.albumDetail(it)) },
                        )
                    }

                    composable(Routes.ALBUMS) {
                        AlbumsScreen(
                            onAlbumClick = { navController.navigate(Routes.albumDetail(it)) },
                            onSearchClick = { navController.navigate(Routes.search(SearchScope.ALBUMS)) },
                        )
                    }
                    composable(
                        Routes.ALBUM_DETAIL_PATTERN,
                        arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
                    ) {
                        AlbumDetailScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.FOLDERS) {
                        FolderBrowseScreen(
                            onFolderClick = { navController.navigate(Routes.folderDetail(it)) },
                            onManageFolders = { navController.navigate(Routes.MANAGE_FOLDERS) },
                            onSearchClick = { navController.navigate(Routes.search()) },
                        )
                    }
                    composable(
                        Routes.FOLDER_DETAIL_PATTERN,
                        arguments = listOf(navArgument("folderId") { type = NavType.LongType }),
                    ) {
                        FolderDetailScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.PLAYLISTS) {
                        PlaylistsScreen(
                            onPlaylistClick = { navController.navigate(Routes.playlistDetail(it)) },
                            onSearchClick = { navController.navigate(Routes.search()) },
                            onDraftMade = { navController.navigate(Routes.PLAYLIST_DRAFT) },
                            onOpenAiSettings = { navController.navigate(Routes.AI_SETTINGS) },
                        )
                    }
                    composable(Routes.AI_SETTINGS) {
                        AiSettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.PLAYLIST_DRAFT) {
                        DraftPlaylistScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { id ->
                                navController.navigate(Routes.playlistDetail(id)) {
                                    popUpTo(Routes.PLAYLIST_DRAFT) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable(
                        Routes.PLAYLIST_DETAIL_PATTERN,
                        arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
                    ) {
                        PlaylistDetailScreen(onBack = { navController.popBackStack() })
                    }

                    composable(Routes.MANAGE_FOLDERS) { FoldersScreen(onBack = { navController.popBackStack() }) }

                    composable(Routes.QUEUE) {
                        QueueScreen(onBack = { navController.popBackStack() }, viewModel = playerViewModel)
                    }

                    composable(
                        Routes.SEARCH_PATTERN,
                        arguments = listOf(navArgument("scope") { type = NavType.StringType; defaultValue = SearchScope.ALL.name }),
                    ) {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onArtistClick = { navController.navigate(Routes.artistDetail(it)) },
                            onAlbumClick = { navController.navigate(Routes.albumDetail(it)) },
                        )
                    }
                }
            }
        }

        // Only once the mini-player's slot has been measured — the sheet's anchors come from it.
    if (showSheet && miniSlotTopPx > 0f) {
            PlayerSheet(
                state = sheetState,
                collapsedTopPx = miniSlotTopPx,
                screenHeightPx = screenHeightPx,
                containerModifier = { shape -> Modifier.glassSurface(hazeState, glassStyle, shape) },
                miniPlayer = { expand ->
                    MiniPlayerBar(
                        playbackState = playbackState,
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onPrevious = playerViewModel::previous,
                        onOpenNowPlaying = expand,
                        fetchArtworkUri = playerViewModel::artworkUriFor,
                        nextTrack = nextTrack,
                        previousTrack = previousTrack,
                        outputName = outputTarget.displayName(),
                        outputIsNode = outputTarget is OutputTarget.Node,
                        onOpenOutputPicker = { showOutputPicker = true },
                        finished = queueState.finished,
                        finishedSubtitle = "${countLabel(queueState.items.size, "track")} · tap ▶ to play again",
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                player = { collapse ->
                    NowPlayingScreen(
                        viewModel = playerViewModel,
                        onBack = collapse,
                        onOpenQueue = {
                            scope.launch { sheetState.snapTo(PlayerSheetValue.Collapsed) }
                            navController.navigate(Routes.QUEUE)
                        },
                    )
                },
            )
        }
    }
}
