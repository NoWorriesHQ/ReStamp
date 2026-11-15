package com.noworrieshq.restamp.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.noworrieshq.restamp.core.match.MediaFile

/** Lists the photos and videos in a folder the person picked with the system folder picker. */
class FolderScanner(private val context: Context) {

    data class Scan(val files: List<MediaFile>, val skipped: Int)

    /** Keeps access to [tree] after the app restarts. Write access is needed later to fix the edited copies. */
    fun keepAccess(tree: Uri) {
        val resolver = context.contentResolver
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(tree, readWrite) }
            .recoverCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun hasAccess(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    /**
     * JPEG and MP4 files directly in [tree], plus those in subfolders when [includeSubfolders].
     * Other files are counted in [Scan.skipped], since the engine can't write them.
     */
    fun scan(tree: Uri, includeSubfolders: Boolean = false): Scan {
        val files = mutableListOf<MediaFile>()
        var skipped = 0
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> if (includeSubfolders) pending.add(id)
                        name.startsWith(".") -> Unit // hidden and trashed files (".trashed-…")
                        isSupported(name, mime) ->
                            files += MediaFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name)
                        else -> skipped++
                    }
                }
            }
        }
        return Scan(files, skipped)
    }

    private fun isSupported(name: String, mime: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return mime == "image/jpeg" || mime == "video/mp4" || ext in SUPPORTED_EXTENSIONS
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "jpe", "mp4", "m4v")

        /** "primary:DCIM/Camera" → "DCIM/Camera", for showing which folder was picked. */
        fun displayName(tree: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
            val path = id.substringAfter(':', id)
            return path.ifEmpty { "Internal storage" }
        }
    }
}
