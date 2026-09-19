package io.github.eladimany.spindle.ui.navigation

object Routes {
    const val TRACKS = "tracks"
    const val ARTISTS = "artists"
    const val ALBUMS = "albums"
    const val FOLDERS = "folders"
    const val PLAYLISTS = "playlists"
    const val MANAGE_FOLDERS = "manage_folders"
    const val NOW_PLAYING = "now_playing"

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
