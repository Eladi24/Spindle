package io.github.eladimany.spindle.data.covers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber

/** An image file next to an album's songs. */
data class FolderImage(val name: String, val uri: Uri, val sizeBytes: Long)

/**
 * Cover images that sit in an album's folder — cover.jpg, folder.jpg, front.jpg…
 * (mockups 10A/10B, 2026-11-02). Spindle may read audio but not images, so these are
 * reached through one folder the user grants once with Android's folder picker (SAF) —
 * no Photos permission, which Play restricts to photo apps. Read-only.
 */
@Singleton
class FolderCoverSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** The images in the folder of [sampleTrackUri], likeliest cover first. Empty when outside [treeUri] or none. */
    suspend fun imagesFor(treeUri: Uri, sampleTrackUri: String): List<FolderImage> = withContext(io) {
        runCatching {
            val folder = relativeFolder(Uri.parse(sampleTrackUri)) ?: return@runCatching emptyList()
            val docId = findFolder(treeUri, folder) ?: return@runCatching emptyList()
            listImages(treeUri, docId).sortedWith(compareBy<FolderImage> { rank(it.name) }.thenByDescending { it.sizeBytes })
        }.onFailure { Timber.w(it, "Folder cover lookup failed") }.getOrDefault(emptyList())
    }

    suspend fun read(image: Uri): ByteArray? = withContext(io) {
        runCatching { context.contentResolver.openInputStream(image)?.use { it.readBytes() } }.getOrNull()
    }

    /** The track's folder as path segments from the storage root, e.g. [Music, Dio, Holy Diver]. */
    private fun relativeFolder(trackUri: Uri): List<String>? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.query(trackUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)?.split('/')?.filter { it.isNotEmpty() }
            }
            return null
        }
        @Suppress("DEPRECATION")
        resolver.query(trackUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
                val path = c.getString(0) ?: return null
                if (!path.startsWith(root)) return null
                return path.removePrefix(root).split('/').filter { it.isNotEmpty() }.dropLast(1)
            }
        }
        return null
    }

    /** Walks from the granted folder down to [folder], matching names without case. */
    private fun findFolder(treeUri: Uri, folder: List<String>): String? {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        // ExternalStorageProvider ids look like "primary:Music/Rock"; the part after ':' is the path.
        val grantedPath = treeDocId.substringAfter(':', "").split('/').filter { it.isNotEmpty() }
        if (folder.size < grantedPath.size) return null
        if (grantedPath.indices.any { !folder[it].equals(grantedPath[it], ignoreCase = true) }) return null
        var docId = treeDocId
        for (segment in folder.drop(grantedPath.size)) {
            docId = children(treeUri, docId).firstOrNull { it.isDir && it.name.equals(segment, ignoreCase = true) }?.id
                ?: return null
        }
        return docId
    }

    private fun listImages(treeUri: Uri, docId: String): List<FolderImage> =
        children(treeUri, docId)
            .filter { !it.isDir && (it.mime.startsWith("image/") || IMAGE_EXT.any { ext -> it.name.endsWith(ext, ignoreCase = true) }) }
            .map { FolderImage(it.name, DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id), it.size) }

    private class Child(val id: String, val name: String, val mime: String, val size: Long) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun children(treeUri: Uri, docId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Child>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), if (c.isNull(3)) 0 else c.getLong(3))
            }
        }
        return out
    }

    companion object {
        private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".webp")
        private val BEST_NAMES = listOf("cover", "folder", "front", "album", "albumart", "art")
        private val BACK_NAMES = listOf("back", "cd", "disc", "disk", "inlay", "inside", "tray", "booklet")

        /** Lower is likelier the front cover. Pure, unit tested. */
        fun rank(fileName: String): Int {
            val stem = fileName.substringBeforeLast('.').lowercase().trim()
            val exact = BEST_NAMES.indexOf(stem)
            return when {
                exact >= 0 -> exact
                stem.startsWith("albumartsmall") -> 30
                BACK_NAMES.any { it in stem } -> 40
                "cover" in stem || "front" in stem || "folder" in stem -> 10
                else -> 20
            }
        }
    }
}
