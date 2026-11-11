package com.cratez.app.library

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.cratez.app.data.CratezDb
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Adds music to the Cratez library from picked files, picked folders, or other apps. */
class Importer(private val ctx: Context, private val db: CratezDb) {
    private val audioExt = setOf("mp3", "flac", "wav", "aif", "aiff", "m4a", "aac", "alac", "ogg", "opus", "wma", "mp4")

    private fun isAudio(name: String, mime: String?) =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in audioExt

    private fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    /** Individual songs picked with the system file picker. Returns number of new tracks. */
    suspend fun addFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (u in uris) {
            persist(u)
            var name = "track"; var size = 0L
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
            if (insert(u, name, size, ctx.contentResolver.getType(u) ?: "", "Picked")) added++
        }
        added
    }

    /** A whole folder (and its sub-folders) picked with the system folder picker. */
    suspend fun addTree(tree: Uri): Int = withContext(Dispatchers.IO) {
        persist(tree)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val folderName = rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
        var added = 0
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId to folderName)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 5000) {
            val (docId, label) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (!name.startsWith(".")) stack.add(id to name); continue }
                    if (!isAudio(name, mime)) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (insert(docUri, name, c.getLong(3), mime ?: "", label)) added++
                }
            }
        }
        added
    }

    private suspend fun insert(uri: Uri, name: String, size: Long, mime: String, folder: String, received: String? = null): Boolean {
        if (!isAudio(name, mime)) return false
        val id = db.tracks().insert(
            TrackEntity(uri = uri.toString(), fileName = name, folder = folder, mime = mime, sizeBytes = size,
                status = Status.PENDING, received = received != null, receivedFrom = received),
        )
        return id > 0
    }

    /**
     * Copies a file we only have temporary access to (shared from another app, or received over Beam)
     * into Music/Cratez so it belongs to the phone's music library. Returns the new content URI.
     */
    fun copyIntoMusic(src: InputStream, displayName: String, mime: String): Uri? {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime.ifBlank { "audio/mpeg" })
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Cratez")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> src.copyTo(out, 1 shl 16) }
            values.clear(); values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Cratez").apply { mkdirs() }
        var f = File(dir, displayName); var i = 1
        while (f.exists()) f = File(dir, displayName.substringBeforeLast('.') + " ($i)." + displayName.substringAfterLast('.')).also { i++ }
        f.outputStream().use { src.copyTo(it) }
        return Uri.fromFile(f)
    }

    /** "Share to Cratez" from email/messaging apps. */
    suspend fun importShared(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (u in uris) {
            var name = "shared_${System.currentTimeMillis()}.mp3"; var size = 0L
            runCatching {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
                }
            }
            val mime = ctx.contentResolver.getType(u) ?: ""
            if (!isAudio(name, mime)) continue
            val dest = ctx.contentResolver.openInputStream(u)?.use { copyIntoMusic(it, name, mime) } ?: continue
            if (insert(dest, name, size, mime, "Shared with me", received = "Shared")) added++
        }
        added
    }

    suspend fun insertReceived(uri: Uri, entity: TrackEntity): Long = db.tracks().insert(entity.copy(uri = uri.toString()))
}
