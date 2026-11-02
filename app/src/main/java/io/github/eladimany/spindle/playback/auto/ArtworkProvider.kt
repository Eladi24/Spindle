package io.github.eladimany.spindle.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toFile
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.eladimany.spindle.data.db.dao.AlbumDao
import io.github.eladimany.spindle.data.library.ArtworkRepository
import io.github.eladimany.spindle.data.library.toDomain
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Album covers for Android Auto (and anything else outside the app showing our media
 * items). The covers are cached as plain files in our cache dir, which another app can't
 * open — so they're served from here as `content://<authority>/album/<albumId>`.
 *
 * Not exported: [LibraryBrowser] grants read access to the connecting app (Android
 * Auto) for the whole `album/` prefix. A cover not cached yet is extracted on the spot —
 * `openFile` runs on a binder thread, so blocking there is fine.
 */
class ArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun albumDao(): AlbumDao
        fun artworkRepository(): ArtworkRepository
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        val segments = uri.pathSegments
        val albumId = segments.getOrNull(1)?.toLongOrNull()
        if (segments.firstOrNull() != ALBUM || albumId == null) throw FileNotFoundException(uri.toString())
        val deps = EntryPointAccessors.fromApplication(context!!.applicationContext, Deps::class.java)
        val cached = runBlocking {
            val album = deps.albumDao().getById(albumId) ?: return@runBlocking null
            deps.artworkRepository().artworkUriForAlbum(album.toDomain())
        } ?: throw FileNotFoundException("No artwork for album $albumId")
        val file = cached.toUri().takeIf { it.scheme == "file" }?.toFile()
            ?: throw FileNotFoundException(cached)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val ALBUM = "album"

        fun authority(context: Context) = "${context.packageName}.artwork"

        fun albumPrefix(context: Context): Uri = Uri.parse("content://${authority(context)}/$ALBUM")

        fun albumUri(context: Context, albumId: Long): Uri =
            albumPrefix(context).buildUpon().appendPath(albumId.toString()).build()
    }
}
