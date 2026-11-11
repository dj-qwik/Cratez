package com.cratez.core.tags

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Tag fields Cratez reads and writes. Null = unknown / leave unchanged. */
data class TagData(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val bpm: String? = null,
    val key: String? = null,
    val year: String? = null,
    val label: String? = null,
    val isrc: String? = null,
    val energy: String? = null,
    val comment: String? = null,
    val art: ByteArray? = null,
    val artMime: String? = null,
)

enum class TagFormat { MP3, FLAC, OTHER;
    companion object {
        fun of(fileNameOrMime: String?): TagFormat {
            val s = fileNameOrMime?.lowercase() ?: return OTHER
            return when {
                s.endsWith(".mp3") || s == "audio/mpeg" || s == "audio/mp3" -> MP3
                s.endsWith(".flac") || s.contains("flac") -> FLAC
                else -> OTHER
            }
        }
    }
}

object TagIO {
    fun read(bytes: ByteArray): TagData? = when {
        Id3.hasTag(bytes) && !isFlacAfterId3(bytes) -> Id3.read(bytes)
        Flac.isFlac(bytes) -> Flac.read(bytes)
        else -> null
    }

    /** Returns a full new file image with updated tags, or null if the format isn't writable. */
    fun write(bytes: ByteArray, format: TagFormat, update: TagData): ByteArray? = when {
        Flac.isFlac(bytes) -> Flac.write(bytes, update)
        format == TagFormat.MP3 -> Id3.write(bytes, update)
        else -> null
    }

    private fun isFlacAfterId3(b: ByteArray): Boolean {
        val end = Id3.totalTagLength(b)
        return end + 4 <= b.size && String(b, end, 4, Charsets.ISO_8859_1) == "fLaC"
    }
}

internal fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
    ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
internal fun be24(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
internal fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
internal fun syncsafe(b: ByteArray, o: Int) = ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
    ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

internal fun ByteArrayOutputStream.be32(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
internal fun ByteArrayOutputStream.be24(v: Int) { write(v ushr 16); write(v ushr 8); write(v) }
internal fun ByteArrayOutputStream.le32(v: Int) { write(v); write(v ushr 8); write(v ushr 16); write(v ushr 24) }
internal fun ByteArrayOutputStream.syncsafe(v: Int) { write((v ushr 21) and 0x7F); write((v ushr 14) and 0x7F); write((v ushr 7) and 0x7F); write(v and 0x7F) }

/** ID3v2.2/2.3/2.4 reader and ID3v2.3 writer (most compatible with Rekordbox, Serato, Traktor, Windows). */
object Id3 {
    private class Frame(val id: String, val flags: Int, val data: ByteArray)

    fun hasTag(b: ByteArray) = b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    /** Size of the tag including header (and footer) from its 10-byte header, or 0. */
    fun tagLength(header: ByteArray, offset: Int = 0): Int {
        if (header.size < offset + 10 || header[offset] != 'I'.code.toByte() || header[offset + 1] != 'D'.code.toByte() || header[offset + 2] != '3'.code.toByte()) return 0
        val footer = if ((header[offset + 5].toInt() and 0x10) != 0) 10 else 0
        return 10 + syncsafe(header, offset + 6) + footer
    }

    /** Length of all consecutive ID3v2 tags at the start of the file. */
    fun totalTagLength(b: ByteArray): Int {
        var pos = 0
        while (true) {
            val l = tagLength(b, pos)
            if (l == 0 || pos + l > b.size) return pos
            pos += l
            // Some files have zero padding beyond the declared size.
            while (pos < b.size && pos < 64 * 1024 * 1024 && b[pos].toInt() == 0 && !(pos + 3 < b.size && b[pos + 1] == 'I'.code.toByte())) {
                if (pos + 1 < b.size && (b[pos + 1].toInt() and 0xFF) == 0xFF) { pos++; break }
                pos++
            }
        }
    }

    private fun unsync(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            out.write(src[i].toInt())
            if ((src[i].toInt() and 0xFF) == 0xFF && i + 1 < src.size && src[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun parse(b: ByteArray): Pair<Int, List<Frame>>? {
        if (!hasTag(b)) return null
        val ver = b[3].toInt()
        val flags = b[5].toInt() and 0xFF
        val size = syncsafe(b, 6)
        if (10 + size > b.size || ver !in 2..4) return null
        var body = b.copyOfRange(10, 10 + size)
        if ((flags and 0x80) != 0 && ver < 4) body = unsync(body)
        var pos = 0
        if ((flags and 0x40) != 0 && ver >= 3) {
            pos = if (ver == 3) 4 + be32(body, 0) else syncsafe(body, 0)
        }
        val frames = ArrayList<Frame>()
        val hdr = if (ver == 2) 6 else 10
        while (pos + hdr <= body.size) {
            if (body[pos].toInt() == 0) break
            val id = String(body, pos, if (ver == 2) 3 else 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val fsize = when (ver) { 2 -> be24(body, pos + 3); 3 -> be32(body, pos + 4); else -> syncsafe(body, pos + 4) }
            val fflags = if (ver == 2) 0 else ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
            if (fsize < 0 || pos + hdr + fsize > body.size) break
            var data = body.copyOfRange(pos + hdr, pos + hdr + fsize)
            if (ver == 4) {
                var ok = true
                if ((fflags and 0x0001) != 0) data = if (data.size >= 4) data.copyOfRange(4, data.size) else ByteArray(0)
                if ((fflags and 0x0002) != 0 || (flags and 0x80) != 0) data = unsync(data)
                if ((fflags and 0x000C) != 0) ok = false // compressed/encrypted
                frames += Frame(id, if (ok) 0 else fflags, data)
            } else frames += Frame(id, fflags, data)
            pos += hdr + fsize
        }
        return ver to frames
    }

    private fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1
    }

    private fun decodeText(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val s = String(data, 1, data.size - 1, charset(data[0].toInt()))
        val parts = s.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.joinToString(", ").ifBlank { null }
    }

    /** Index just after a null terminator for [enc], starting at [from]. */
    private fun skipString(d: ByteArray, from: Int, enc: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) {
            while (i + 1 < d.size) { if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) return i + 2; i += 2 }
            return d.size
        }
        while (i < d.size) { if (d[i].toInt() == 0) return i + 1; i++ }
        return d.size
    }

    fun read(b: ByteArray): TagData? {
        val (ver, frames) = parse(b) ?: return null
        fun text(vararg ids: String) = ids.firstNotNullOfOrNull { id -> frames.firstOrNull { it.id == id }?.let { decodeText(it.data) } }
        fun txxx(vararg names: String): String? = frames.filter { it.id == "TXXX" && it.data.isNotEmpty() }.firstNotNullOfOrNull { f ->
            val enc = f.data[0].toInt()
            val end = skipString(f.data, 1, enc)
            val desc = String(f.data, 1, (end - 1 - (if (enc == 1 || enc == 2) 2 else 1)).coerceAtLeast(0), charset(enc)).trim()
            if (names.any { it.equals(desc, true) }) String(f.data, end, f.data.size - end, charset(enc)).trim('\u0000', ' ') else null
        }
        var art: ByteArray? = null; var mime: String? = null
        val pics = frames.filter { it.id == "APIC" || it.id == "PIC" }
        val pic = pics.firstOrNull { pictureType(it, ver) == 3 } ?: pics.firstOrNull()
        if (pic != null && pic.data.size > 4) {
            val d = pic.data
            val enc = d[0].toInt()
            var p: Int
            if (ver == 2) { mime = if (String(d, 1, 3, Charsets.ISO_8859_1).equals("PNG", true)) "image/png" else "image/jpeg"; p = 4 }
            else { val e = skipString(d, 1, 0); mime = String(d, 1, e - 2, Charsets.ISO_8859_1); p = e }
            p += 1
            p = skipString(d, p, enc)
            if (p < d.size) art = d.copyOfRange(p, d.size)
        }
        var comment: String? = null
        frames.firstOrNull { it.id == "COMM" || it.id == "COM" }?.data?.let { d ->
            if (d.size > 4) { val enc = d[0].toInt(); val p = skipString(d, 4, enc); if (p < d.size) comment = String(d, p, d.size - p, charset(enc)).trim('\u0000', ' ') }
        }
        return TagData(
            title = text("TIT2", "TT2"), artist = text("TPE1", "TP1"), album = text("TALB", "TAL"),
            genre = text("TCON", "TCO"), bpm = text("TBPM", "TBP"), key = text("TKEY", "TKE") ?: txxx("INITIALKEY", "KEY"),
            year = (text("TDRC", "TYER", "TYE", "TDOR"))?.take(4), label = text("TPUB", "TPB"), isrc = text("TSRC", "TRC"),
            energy = txxx("ENERGY", "EnergyLevel"), comment = comment, art = art, artMime = mime,
        )
    }

    private fun pictureType(f: Frame, ver: Int): Int {
        val d = f.data
        if (d.size < 5) return -1
        return if (ver == 2) d[4].toInt() else { val e = skipString(d, 1, 0); if (e < d.size) d[e].toInt() else -1 }
    }

    private val V24_ONLY = setOf("SEEK", "ASPI", "SIGN", "EQU2", "RVA2", "TDRC", "TDOR")

    fun write(file: ByteArray, u: TagData): ByteArray {
        val parsed = parse(file)
        val ver = parsed?.first ?: 3
        val old = parsed?.second ?: emptyList()
        val audioStart = totalTagLength(file)
        val out = ByteArrayOutputStream()
        val replaced = HashSet<String>()
        fun textFrame(id: String, value: String?) {
            if (value == null) return
            replaced += id
            if (value.isBlank()) return
            writeFrame(out, id, encodeText(value))
        }
        textFrame("TIT2", u.title); textFrame("TPE1", u.artist); textFrame("TALB", u.album)
        textFrame("TCON", u.genre); textFrame("TBPM", u.bpm); textFrame("TKEY", u.key)
        textFrame("TPUB", u.label); textFrame("TSRC", u.isrc)
        val oldYear = if (ver == 4) old.firstOrNull { it.id == "TDRC" }?.let { decodeText(it.data)?.take(4) } else null
        (u.year ?: oldYear)?.let { textFrame("TYER", it) }
        if (u.energy != null) writeTxxx(out, "ENERGY", u.energy)
        if (u.art != null) {
            replaced += "APIC"
            val mime = u.artMime ?: "image/jpeg"
            val d = ByteArrayOutputStream()
            d.write(0); d.write(mime.toByteArray(Charsets.ISO_8859_1)); d.write(0); d.write(3); d.write(0); d.write(u.art)
            writeFrame(out, "APIC", d.toByteArray())
        }
        if (ver >= 3) for (f in old) {
            if (f.id in replaced || (f.id == "TYER" && "TYER" in replaced)) continue
            if (f.id == "TXXX" && u.energy != null && txxxDesc(f).equals("ENERGY", true)) continue
            if (ver == 4) {
                if (f.id in V24_ONLY) continue
                if (f.flags != 0) continue
                if (f.id.startsWith("T") && f.id != "TXXX") {
                    val v = decodeText(f.data) ?: continue
                    writeFrame(out, f.id, encodeText(v)); continue
                }
                if (f.id == "TXXX" || f.id == "COMM" || f.id == "USLT" || f.id == "APIC") {
                    // Re-encode UTF-8 (3) to UTF-16 (1) where v2.3 doesn't allow it.
                    writeFrame(out, f.id, if (f.data.isNotEmpty() && f.data[0].toInt() == 3) reencodeUtf8(f) else f.data); continue
                }
                writeFrame(out, f.id, f.data)
            } else {
                writeRawFrame(out, f.id, f.flags, f.data)
            }
        }
        val frames = out.toByteArray()
        val padding = 2048
        val tag = ByteArrayOutputStream(frames.size + padding + 10)
        tag.write('I'.code); tag.write('D'.code); tag.write('3'.code); tag.write(3); tag.write(0); tag.write(0)
        tag.syncsafe(frames.size + padding)
        tag.write(frames)
        tag.write(ByteArray(padding))
        val res = ByteArrayOutputStream(tag.size() + file.size - audioStart)
        tag.writeTo(res)
        res.write(file, audioStart, file.size - audioStart)
        return res.toByteArray()
    }

    private fun txxxDesc(f: Frame): String {
        if (f.data.isEmpty()) return ""
        val enc = f.data[0].toInt()
        val end = skipString(f.data, 1, enc)
        return String(f.data, 1, (end - 1 - (if (enc == 1 || enc == 2) 2 else 1)).coerceAtLeast(0), charset(enc)).trim()
    }

    private fun reencodeUtf8(f: Frame): ByteArray {
        val d = f.data
        return when (f.id) {
            "TXXX" -> {
                val end = skipString(d, 1, 3)
                val desc = String(d, 1, (end - 2).coerceAtLeast(0), Charsets.UTF_8)
                val value = String(d, end, d.size - end, Charsets.UTF_8).trim('\u0000')
                txxxData(desc, value)
            }
            "COMM", "USLT" -> {
                val lang = d.copyOfRange(1, 4)
                val end = skipString(d, 4, 3)
                val desc = String(d, 4, (end - 5).coerceAtLeast(0), Charsets.UTF_8)
                val value = String(d, end, d.size - end, Charsets.UTF_8).trim('\u0000')
                val o = ByteArrayOutputStream(); o.write(1); o.write(lang)
                o.write(utf16z(desc)); o.write(utf16(value)); o.toByteArray()
            }
            else -> { // APIC
                val mEnd = skipString(d, 1, 0)
                val type = d[mEnd]
                val dEnd = skipString(d, mEnd + 1, 3)
                val o = ByteArrayOutputStream(); o.write(0); o.write(d, 1, mEnd - 1); o.write(type.toInt()); o.write(0)
                o.write(d, dEnd, d.size - dEnd); o.toByteArray()
            }
        }
    }

    private fun latin1Ok(s: String) = s.all { it.code in 32..255 }
    private fun utf16(s: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
    private fun utf16z(s: String): ByteArray = utf16(s) + byteArrayOf(0, 0)

    private fun encodeText(v: String): ByteArray =
        if (latin1Ok(v)) byteArrayOf(0) + v.toByteArray(Charsets.ISO_8859_1) else byteArrayOf(1) + utf16(v)

    private fun txxxData(desc: String, value: String): ByteArray =
        if (latin1Ok(desc) && latin1Ok(value)) byteArrayOf(0) + desc.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
        else byteArrayOf(1) + utf16z(desc) + utf16(value)

    private fun writeTxxx(out: ByteArrayOutputStream, desc: String, value: String) = writeFrame(out, "TXXX", txxxData(desc, value))

    private fun writeFrame(out: ByteArrayOutputStream, id: String, data: ByteArray) = writeRawFrame(out, id, 0, data)

    private fun writeRawFrame(out: ByteArrayOutputStream, id: String, flags: Int, data: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1)); out.be32(data.size); out.write(flags ushr 8); out.write(flags); out.write(data)
    }
}

/** FLAC Vorbis-comment + PICTURE reader/writer. */
object Flac {
    fun isFlac(b: ByteArray): Boolean {
        val off = Id3.totalTagLength(b)
        return b.size >= off + 4 && String(b, off, 4, Charsets.ISO_8859_1) == "fLaC"
    }

    private data class Block(val type: Int, val data: ByteArray)

    private fun blocks(b: ByteArray): Pair<List<Block>, Int>? {
        var pos = Id3.totalTagLength(b)
        if (String(b, pos, 4, Charsets.ISO_8859_1) != "fLaC") return null
        pos += 4
        val list = ArrayList<Block>()
        while (pos + 4 <= b.size) {
            val h = b[pos].toInt() and 0xFF
            val len = be24(b, pos + 1)
            if (pos + 4 + len > b.size) return list to -1 // truncated read: metadata only
            list += Block(h and 0x7F, b.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
            if ((h and 0x80) != 0) break
        }
        return list to pos
    }

    private fun comments(d: ByteArray): Pair<String, List<String>> {
        var p = 0
        val vlen = le32(d, p); p += 4
        val vendor = String(d, p, vlen, Charsets.UTF_8); p += vlen
        val n = le32(d, p); p += 4
        val out = ArrayList<String>()
        repeat(n) {
            if (p + 4 > d.size) return vendor to out
            val l = le32(d, p); p += 4
            if (p + l > d.size) return vendor to out
            out += String(d, p, l, Charsets.UTF_8); p += l
        }
        return vendor to out
    }

    fun read(b: ByteArray): TagData? {
        val (bl, _) = blocks(b) ?: return null
        val vc = bl.firstOrNull { it.type == 4 }?.let { comments(it.data).second } ?: emptyList()
        fun get(vararg k: String) = k.firstNotNullOfOrNull { key -> vc.firstOrNull { it.substringBefore('=').equals(key, true) }?.substringAfter('=')?.trim()?.ifBlank { null } }
        var art: ByteArray? = null; var mime: String? = null
        val pics = bl.filter { it.type == 6 }
        val pic = pics.firstOrNull { be32(it.data, 0) == 3 } ?: pics.firstOrNull()
        if (pic != null) runCatching {
            val d = pic.data; var p = 4
            val ml = be32(d, p); p += 4; mime = String(d, p, ml, Charsets.ISO_8859_1); p += ml
            val dl = be32(d, p); p += 4 + dl + 16
            val len = be32(d, p); p += 4
            art = d.copyOfRange(p, p + len)
        }
        return TagData(
            title = get("TITLE"), artist = get("ARTIST"), album = get("ALBUM"), genre = get("GENRE"),
            bpm = get("BPM", "TEMPO"), key = get("INITIALKEY", "KEY"), year = get("DATE", "YEAR")?.take(4),
            label = get("LABEL", "ORGANIZATION", "PUBLISHER"), isrc = get("ISRC"), energy = get("ENERGY"),
            comment = get("COMMENT", "DESCRIPTION"), art = art, artMime = mime,
        )
    }

    fun write(b: ByteArray, u: TagData): ByteArray? {
        val (bl, end) = blocks(b) ?: return null
        if (end < 0 || bl.isEmpty() || bl[0].type != 0) return null
        val (vendor, old) = bl.firstOrNull { it.type == 4 }?.let { comments(it.data) } ?: ("Cratez" to emptyList())
        val set = linkedMapOf<String, String?>(
            "TITLE" to u.title, "ARTIST" to u.artist, "ALBUM" to u.album, "GENRE" to u.genre, "BPM" to u.bpm,
            "INITIALKEY" to u.key, "DATE" to u.year, "LABEL" to u.label, "ISRC" to u.isrc, "ENERGY" to u.energy,
        ).filterValues { it != null }
        val kept = old.filter { c -> set.keys.none { it.equals(c.substringBefore('='), true) } }
        val newComments = kept + set.filterValues { !it.isNullOrBlank() }.map { "${it.key}=${it.value}" }
        val vc = ByteArrayOutputStream()
        val vb = vendor.toByteArray(Charsets.UTF_8)
        vc.le32(vb.size); vc.write(vb); vc.le32(newComments.size)
        for (c in newComments) { val cb = c.toByteArray(Charsets.UTF_8); vc.le32(cb.size); vc.write(cb) }

        val out = ByteArrayOutputStream(b.size + 8192)
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        val keep = bl.filter { it.type != 4 && it.type != 1 && !(it.type == 6 && u.art != null && be32(it.data, 0) == 3) }
        val all = ArrayList<Block>()
        all += keep.first { it.type == 0 }
        all += Block(4, vc.toByteArray())
        all += keep.filter { it.type != 0 }
        if (u.art != null) {
            val p = ByteArrayOutputStream()
            val mime = (u.artMime ?: "image/jpeg").toByteArray(Charsets.ISO_8859_1)
            p.be32(3); p.be32(mime.size); p.write(mime); p.be32(0); p.be32(0); p.be32(0); p.be32(0); p.be32(0)
            p.be32(u.art.size); p.write(u.art)
            all += Block(6, p.toByteArray())
        }
        all += Block(1, ByteArray(4096))
        all.forEachIndexed { i, blk ->
            out.write((if (i == all.lastIndex) 0x80 else 0) or blk.type)
            out.be24(blk.data.size); out.write(blk.data)
        }
        out.write(b, end, b.size - end)
        return out.toByteArray()
    }
}
