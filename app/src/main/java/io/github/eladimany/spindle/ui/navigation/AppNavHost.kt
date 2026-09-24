package io.github.eladimany.spindle.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import io.github.eladimany.spindle.ui.components.FloatingNavBar
import io.github.eladimany.spindle.ui.components.LocalBottomOverlayPadding
import io.github.eladimany.spindle.ui.components.MiniPlayerBar
import io.github.eladimany.spindle.ui.components.NavTab
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
import io.github.eladimany.spindle.ui.player.NowPlayingScreen
import io.github.eladimany.spindle.ui.player.PlayerViewModel
import io.github.eladimany.spindle.ui.player.QueueScreen
import io.github.eladimany.spindle.ui.playlists.PlaylistDetailScreen
import io.github.eladimany.spindle.ui.playlists.PlaylistsScreen
import io.github.eladimany.spindle.ui.search.SearchScreen
import io.github.eladimany.spindle.ui.smartplaylists.AiSettingsScreen
import io.github.eladimany.spindle.ui.smartplaylists.DraftPlaylistScreen
import io.github.eladimany.spindle.playback.QueueManager

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
    val showMiniPlayer = currentRoute != Routes.NOW_PLAYING && currentRoute != Routes.QUEUE
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()
    val queueState by playerViewModel.queueState.collectAsStateWithLifecycle()
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
                    MiniPlayerBar(
                        playbackState = playbackState,
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onPrevious = playerViewModel::previous,
                        onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                        fetchArtworkUri = playerViewModel::artworkUriFor,
                        nextTrack = nextTrack,
                        previousTrack = previousTrack,
                        modifier = Modifier.glassSurface(hazeState, glassStyle, RoundedCornerShape(20.dp)),
                    )
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
                    .padding(top = innerPadding.calculateTopPadding(), bottom = if (showMiniPlayer) 0.dp else overlayBottom)
                    .consumeWindowInsets(innerPadding),
            ) {
                composable(Routes.TRACKS) {
                    TracksScreen(onSearchClick = { navController.navigate(Routes.SEARCH) })
                }

                composable(Routes.ARTISTS) {
                    ArtistsScreen(
                        onArtistClick = { navController.navigate(Routes.artistDetail(it)) },
                        onSearchClick = { navController.navigate(Routes.SEARCH) },
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
                        onSearchClick = { navController.navigate(Routes.SEARCH) },
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
                        onSearchClick = { navController.navigate(Routes.SEARCH) },
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
                        onSearchClick = { navController.navigate(Routes.SEARCH) },
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

                composable(Routes.NOW_PLAYING) {
                    NowPlayingScreen(
                        viewModel = playerViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenQueue = { navController.navigate(Routes.QUEUE) },
                    )
                }

                composable(Routes.QUEUE) {
                    QueueScreen(onBack = { navController.popBackStack() }, viewModel = playerViewModel)
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                        onBack = { navController.popBackStack() },
                        onArtistClick = { navController.navigate(Routes.artistDetail(it)) },
                        onAlbumClick = { navController.navigate(Routes.albumDetail(it)) },
                    )
                }
            }
        }
    }
}
