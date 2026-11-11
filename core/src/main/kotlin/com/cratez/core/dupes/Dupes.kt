package com.cratez.core.dupes

import com.cratez.core.text.TrackText
import kotlin.math.abs

data class DupeFile(
    val id: Long,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val bitrateKbps: Int,
    val lossless: Boolean,
    val path: String,
    /** Hash of a slice of the file; filled in only for same-size files. */
    val quickHash: String? = null,
)

data class DupeGroup(val files: List<DupeFile>, val keep: DupeFile, val exact: Boolean) {
    val extras get() = files.filter { it.id != keep.id }
}

object DupeFinder {
    /** Files that share a size and therefore need a quick content hash. */
    fun needsHash(files: List<DupeFile>): List<DupeFile> =
        files.groupBy { it.sizeBytes }.filter { it.key > 0 && it.value.size > 1 }.values.flatten()

    fun group(files: List<DupeFile>, durationToleranceMs: Long = 2500): List<DupeGroup> {
        val parent = IntArray(files.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        val exactPairs = HashSet<Int>()

        // 1) Byte-identical copies.
        files.indices.groupBy { files[it].sizeBytes to files[it].quickHash }
            .filter { it.key.first > 0 && it.key.second != null && it.value.size > 1 }
            .values.forEach { idx -> idx.drop(1).forEach { union(idx[0], it) }; exactPairs += idx }

        // 2) Same song (artist + title), similar length: different bitrate/format/"(1)" copies.
        files.indices.groupBy { TrackText.dupeKey(files[it].artist, files[it].title) }
            .filter { it.key.substringAfter('|').isNotBlank() && it.value.size > 1 }
            .values.forEach { idx ->
                for (i in idx.indices) for (j in i + 1 until idx.size) {
                    val a = files[idx[i]]; val b = files[idx[j]]
                    if (a.durationMs > 0 && b.durationMs > 0 && abs(a.durationMs - b.durationMs) <= durationToleranceMs) union(idx[i], idx[j])
                }
            }

        return files.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { idx ->
            val list = idx.map { files[it] }
            val keep = list.sortedWith(compareByDescending<DupeFile> { it.lossless }.thenByDescending { it.bitrateKbps }
                .thenByDescending { it.sizeBytes }.thenBy { it.path.length }).first()
            DupeGroup(list, keep, idx.all { it in exactPairs })
        }.sortedBy { it.keep.title.lowercase() }
    }
}
