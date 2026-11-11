package com.cratez.app.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cratez.core.tags.Id3
import com.cratez.core.tags.TagData
import com.cratez.core.tags.TagFormat
import com.cratez.core.tags.TagIO
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max

data class FileInfo(
    val tags: TagData,
    val durationMs: Long,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val mime: String,
)

/** Reads tags from and safely writes tags into audio files behind content URIs. */
object TagFile {
    fun readInfo(ctx: Context, uri: Uri, fileName: String): FileInfo {
        val mmr = MediaMetadataRetriever()
        var t = TagData()
        var dur = 0L; var br = 0; var sr = 0; var mime = ""
        try {
            mmr.setDataSource(ctx, uri)
            fun m(k: Int) = runCatching { mmr.extractMetadata(k) }.getOrNull()?.trim()?.ifBlank { null }
            dur = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            br = ((m(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0) / 1000).toInt()
            if (android.os.Build.VERSION.SDK_INT >= 31) sr = m(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            mime = m(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            t = TagData(
                title = m(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = m(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = m(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = m(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4),
                art = runCatching { mmr.embeddedPicture }.getOrNull(),
            )
        } catch (_: Exception) {
        } finally { runCatching { mmr.release() } }

        // Our own parser picks up DJ fields Android doesn't expose (BPM, key, label, energy).
        val head = readHead(ctx, uri)
        val own = head?.let { runCatching { TagIO.read(it) }.getOrNull() }
        if (own != null) t = TagData(
            title = own.title ?: t.title, artist = own.artist ?: t.artist, album = own.album ?: t.album,
            genre = own.genre ?: t.genre, bpm = own.bpm, key = own.key, year = own.year ?: t.year,
            label = own.label, isrc = own.isrc, energy = own.energy, comment = own.comment,
            art = own.art ?: t.art, artMime = own.artMime,
        )
        if (mime.isBlank()) mime = ctx.contentResolver.getType(uri) ?: guessMime(fileName)
        return FileInfo(t, dur, br, sr, mime)
    }

    fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "aif", "aiff" -> "audio/aiff"
        "m4a", "aac", "alac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; else -> "audio/*"
    }

    /** First part of the file holding the tag block (ID3 size from header, else up to 4 MB). */
    private fun readHead(ctx: Context, uri: Uri): ByteArray? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val hdr = ByteArray(10)
            var r = 0
            while (r < 10) { val k = ins.read(hdr, r, 10 - r); if (k < 0) break; r += k }
            val want = if (Id3.hasTag(hdr)) Id3.tagLength(hdr) + 4096 else 4 * 1024 * 1024
            val cap = want.coerceAtMost(24 * 1024 * 1024)
            val out = ByteArray(cap)
            System.arraycopy(hdr, 0, out, 0, r)
            var pos = r
            while (pos < cap) { val k = ins.read(out, pos, cap - pos); if (k < 0) break; pos += k }
            out.copyOf(pos)
        }
    }.getOrNull()

    fun isWritable(fileName: String, mime: String): Boolean =
        TagFormat.of(fileName) != TagFormat.OTHER || TagFormat.of(mime) != TagFormat.OTHER

    /**
     * Rewrites the file with new tags. Verifies the result and keeps a backup copy until the write
     * succeeds, so a failure can never leave a damaged file. Returns null on success or an error.
     */
    fun write(ctx: Context, uri: Uri, fileName: String, mime: String, update: TagData): String? {
        val format = TagFormat.of(fileName).takeIf { it != TagFormat.OTHER } ?: TagFormat.of(mime)
        if (format == TagFormat.OTHER) return "Format keeps tags in Cratez only"
        val old = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Can't read file"
        val new = runCatching { TagIO.write(old, format, update) }.getOrElse { return "Tag build failed: ${it.message}" }
            ?: return "Unsupported file layout"
        // Sanity checks: tags parse back, and the audio tail is byte-identical.
        val check = runCatching { TagIO.read(new) }.getOrNull() ?: return "Verification failed"
        if (update.genre != null && check.genre != update.genre) return "Verification failed"
        val tail = minOf(65536, old.size / 2)
        for (i in 1..tail) if (old[old.size - i] != new[new.size - i]) return "Verification failed (audio)"

        val backup = File(ctx.cacheDir, "tagbackup.bin")
        backup.writeBytes(old)
        return try {
            writeBytes(ctx, uri, new)
            backup.delete()
            null
        } catch (e: Exception) {
            runCatching { writeBytes(ctx, uri, old) }
            backup.delete()
            "Write blocked: ${e.message}"
        }
    }

    private fun writeBytes(ctx: Context, uri: Uri, bytes: ByteArray) {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
        if (pfd != null) {
            pfd.use { p ->
                FileOutputStream(p.fileDescriptor).channel.use { ch ->
                    ch.position(0)
                    val bb = ByteBuffer.wrap(bytes)
                    while (bb.hasRemaining()) ch.write(bb)
                    ch.truncate(bytes.size.toLong())
                }
            }
            return
        }
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    /** Scales art so the longest side is <= [maxSide] and re-encodes as JPEG. */
    fun scaleJpeg(src: ByteArray, maxSide: Int, quality: Int = 90): ByteArray? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, opts)
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }.getOrNull()

    fun imageSide(src: ByteArray): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, o)
        return max(o.outWidth, o.outHeight)
    }
}
