package io.github.eladimany.spindle.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.eladimany.spindle.ui.components.MiniPlayerBar
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

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val bottomTabs = listOf(
    BottomTab(Routes.ARTISTS, "Artists", Icons.Default.Person),
    BottomTab(Routes.ALBUMS, "Albums", Icons.Default.Album),
    BottomTab(Routes.TRACKS, "Tracks", Icons.Default.MusicNote),
    BottomTab(Routes.FOLDERS, "Folders", Icons.Default.Folder),
    BottomTab(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
)

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    // Activity-scoped: the mini-player and Now Playing share one instance across the graph.
    val playerViewModel: PlayerViewModel = hiltViewModel()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = currentRoute in Routes.topLevel
    val playbackState by playerViewModel.playbackState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                Column {
                    MiniPlayerBar(
                        playbackState = playbackState,
                        onTogglePlayPause = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::next,
                        onPrevious = playerViewModel::previous,
                        onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                        fetchArtworkUri = playerViewModel::artworkUriFor,
                    )
                    NavigationBar {
                        bottomTabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.ARTISTS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.TRACKS) { TracksScreen() }

            composable(Routes.ARTISTS) {
                ArtistsScreen(onArtistClick = { navController.navigate(Routes.artistDetail(it)) })
            }
            composable(
                Routes.ARTIST_DETAIL_PATTERN,
                arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
            ) {
                ArtistDetailScreen(onAlbumClick = { navController.navigate(Routes.albumDetail(it)) })
            }

            composable(Routes.ALBUMS) {
                AlbumsScreen(onAlbumClick = { navController.navigate(Routes.albumDetail(it)) })
            }
            composable(
                Routes.ALBUM_DETAIL_PATTERN,
                arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
            ) {
                AlbumDetailScreen()
            }

            composable(Routes.FOLDERS) {
                FolderBrowseScreen(
                    onFolderClick = { navController.navigate(Routes.folderDetail(it)) },
                    onManageFolders = { navController.navigate(Routes.MANAGE_FOLDERS) },
                )
            }
            composable(
                Routes.FOLDER_DETAIL_PATTERN,
                arguments = listOf(navArgument("folderId") { type = NavType.LongType }),
            ) {
                FolderDetailScreen()
            }

            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(onPlaylistClick = { navController.navigate(Routes.playlistDetail(it)) })
            }
            composable(
                Routes.PLAYLIST_DETAIL_PATTERN,
                arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
            ) {
                PlaylistDetailScreen()
            }

            composable(Routes.MANAGE_FOLDERS) { FoldersScreen() }

            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(
                    viewModel = playerViewModel,
                    onOpenQueue = { navController.navigate(Routes.QUEUE) },
                )
            }

            composable(Routes.QUEUE) {
                QueueScreen(onBack = { navController.popBackStack() }, viewModel = playerViewModel)
            }
        }
    }
}
