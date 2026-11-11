package com.cratez.core.genre

import com.cratez.core.dsp.AudioFeatures
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "Learns your library": tracks whose genre is trusted (DJ-store tags, folder names, or set by you)
 * become references, and a track is compared to its nearest neighbours by tempo, groove and sound.
 */
object LibraryModel {
    const val DIM = 10

    fun vector(f: AudioFeatures): FloatArray = floatArrayOf(
        (ln(f.bpm.coerceAtLeast(40.0) / 130.0) / ln(2.0) * 6).toFloat(),
        f.halfTime.toFloat(),
        (f.backbeat * 0.8).toFloat(),
        (f.kick * 0.8).toFloat(),
        (f.roll.coerceAtMost(1.5) * 0.6).toFloat(),
        (f.hatRate / 3 * 0.6).toFloat(),
        (f.subBass * 1.5).toFloat(),
        (f.centroidHz / 1000 * 0.8).toFloat(),
        (f.flatness * 3).toFloat(),
        ((f.loudnessDb + 12) / 10 * 0.6).toFloat(),
    )

    fun encode(v: FloatArray): String = v.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
    fun decode(s: String): FloatArray? = s.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == DIM }?.toFloatArray()

    /** Moves the tempo dimension when the BPM is corrected later. */
    fun withBpm(v: FloatArray, bpm: Double): FloatArray = v.copyOf().also { it[0] = (ln(bpm.coerceAtLeast(40.0) / 130.0) / ln(2.0) * 6).toFloat() }

    data class Vote(val genre: Genre, val share: Double, val neighbours: Int, val weight: Double)

    fun vote(target: FloatArray, refs: List<Pair<FloatArray, Genre>>, k: Int = 7): Vote? {
        if (refs.size < 3) return null
        val near = refs.asSequence().map { (v, g) -> dist(target, v) to g }.filter { it.first < 2.5 }
            .sortedBy { it.first }.take(k).toList()
        if (near.size < 3) return null
        val scores = HashMap<Genre, Double>()
        for ((d, g) in near) scores.merge(g, 1.0 / (0.3 + d), Double::plus)
        val total = scores.values.sum()
        val best = scores.maxBy { it.value }
        val share = best.value / total
        // Strong agreement among many close neighbours = strong vote.
        val weight = 2.2 * share * (near.size / k.toDouble()).coerceAtMost(1.0)
        return Vote(best.key, share, near.size, weight)
    }

    private fun dist(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in 0 until minOf(a.size, b.size)) { val d = (a[i] - b[i]).toDouble(); s += d * d }
        return sqrt(s)
    }
}
