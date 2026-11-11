package com.cratez.app.dupes

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.cratez.core.dupes.DupeFile
import com.cratez.core.dupes.DupeFinder
import com.cratez.core.dupes.DupeGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class DupeState(
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val groups: List<DupeGroup> = emptyList(),
    val hasScanned: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val message: String? = null,
)

/** Finds duplicate songs across the whole phone using the system music index (MediaStore). */
class DupeScanner(private val ctx: Context) {
    private val _state = MutableStateFlow(DupeState())
    val state: StateFlow<DupeState> = _state

    fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    suspend fun scan() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(scanning = true, scanned = 0, message = null)
        val files = ArrayList<DupeFile>()
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 30) cols += MediaStore.Audio.Media.BITRATE
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toTypedArray(),
                "${MediaStore.Audio.Media.SIZE} > 200000", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(5) ?: ""
                    val mime = c.getString(6) ?: ""
                    val artist = c.getString(2)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    val size = c.getLong(4)
                    val dur = c.getLong(3)
                    val br = if (Build.VERSION.SDK_INT >= 30) (c.getLong(8) / 1000).toInt() else if (dur > 0) (size * 8 / dur).toInt() else 0
                    val path = c.getString(7) ?: name
                    val lossless = mime.contains("flac") || mime.contains("wav") || name.endsWith(".flac", true) || name.endsWith(".wav", true)
                    files += DupeFile(c.getLong(0), c.getString(1)?.ifBlank { null } ?: name.substringBeforeLast('.'), artist, dur, size, br, lossless, path)
                }
            }
        }.onFailure { _state.value = _state.value.copy(scanning = false, message = "Can't read music library: ${it.message}"); return@withContext }
        _state.value = _state.value.copy(scanned = files.size)
        val toHash = DupeFinder.needsHash(files).map { it.id }.toSet()
        val hashed = files.map { f -> if (f.id in toHash) f.copy(quickHash = quickHash(uriOf(f.id), f.sizeBytes)) else f }
        val groups = DupeFinder.group(hashed)
        _state.value = DupeState(scanning = false, scanned = files.size, groups = groups, hasScanned = true,
            message = if (groups.isEmpty()) "No duplicates found in ${files.size} songs" else null)
    }

    private fun quickHash(uri: Uri, size: Long): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(65536)
            // A 64 KB slice from the middle (past any tags).
            ins.skip(maxOf(0L, size / 2 - 32768L))
            val n = ins.read(buf); if (n > 0) md.update(buf, 0, n)
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()

    fun toggle(id: Long) { val s = _state.value.selected; _state.value = _state.value.copy(selected = if (id in s) s - id else s + id) }
    fun selectAllExtras() { _state.value = _state.value.copy(selected = _state.value.groups.flatMap { g -> g.extras.map { it.id } }.toSet()) }
    fun clearSelection() { _state.value = _state.value.copy(selected = emptySet()) }

    /** Android shows its own confirmation dialog for deleting other apps' files (API 30+). */
    fun deleteRequest(ids: Collection<Long>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(ctx.contentResolver, ids.map { uriOf(it) }) else null

    /** Pre-Android 11 direct delete. */
    suspend fun deleteDirect(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        ids.sumOf { runCatching { ctx.contentResolver.delete(uriOf(it), null, null) }.getOrDefault(0) }
    }

    /** Removes deleted files from the result list after the user confirms. */
    fun markDeleted(ids: Collection<Long>) {
        val set = ids.toSet()
        val groups = _state.value.groups.mapNotNull { g ->
            val left = g.files.filter { it.id !in set }
            if (left.size < 2) null else g.copy(files = left, keep = if (g.keep.id in set) left.maxBy { it.bitrateKbps } else g.keep)
        }
        _state.value = _state.value.copy(groups = groups, selected = _state.value.selected - set,
            message = "Deleted ${set.size} file${if (set.size == 1) "" else "s"}")
    }

    fun deletedSize(ids: Collection<Long>): List<Pair<String, Long>> {
        val all = _state.value.groups.flatMap { it.files }.associateBy { it.id }
        return ids.mapNotNull { all[it] }.map { it.path.substringAfterLast('/') to it.sizeBytes }
    }
}
