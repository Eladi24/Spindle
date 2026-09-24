package io.github.eladimany.spindle.ui.navigation

import io.github.eladimany.spindle.ui.search.SearchScope

object Routes {
    const val TRACKS = "tracks"
    const val ARTISTS = "artists"
    const val ALBUMS = "albums"
    const val FOLDERS = "folders"
    const val PLAYLISTS = "playlists"
    const val MANAGE_FOLDERS = "manage_folders"
    const val QUEUE = "queue"
    const val SEARCH_PATTERN = "search?scope={scope}"
    fun search(scope: SearchScope = SearchScope.ALL) = "search?scope=${scope.name}"
    const val PLAYLIST_DRAFT = "playlist_draft"
    const val AI_SETTINGS = "ai_settings"

    const val ARTIST_DETAIL_PATTERN = "artist/{artistId}"
    fun artistDetail(id: Long) = "artist/$id"

    const val ALBUM_DETAIL_PATTERN = "album/{albumId}"
    fun albumDetail(id: Long) = "album/$id"

    const val FOLDER_DETAIL_PATTERN = "folder/{folderId}"
    fun folderDetail(id: Long) = "folder/$id"

    const val PLAYLIST_DETAIL_PATTERN = "playlist/{playlistId}"
    fun playlistDetail(id: Long) = "playlist/$id"

    val topLevel = listOf(TRACKS, ARTISTS, ALBUMS, FOLDERS, PLAYLISTS)
}
