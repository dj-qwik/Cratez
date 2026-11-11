package com.cratez.core.genre

import com.cratez.core.dsp.AudioFeatures
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** Rule-based EDM sub-genre classifier working on measured groove + spectrum features. */
object EdmClassifier {
    private class Profile(val genre: Genre, val bpm: Double, val sigma: Double, val terms: Map<String, Double>)

    private fun p(g: Genre, bpm: Double, sigma: Double, vararg t: Pair<String, Double>) = Profile(g, bpm, sigma, t.toMap())

    private val profiles = listOf(
        p(Genre.HOUSE, 124.0, 4.0, "kick" to 1.5, "off" to 0.8, "bb" to 0.3),
        p(Genre.DEEP_HOUSE, 121.0, 4.0, "kick" to 1.5, "bright" to -0.8, "loud" to -0.6, "tonal" to 0.4),
        p(Genre.TECH_HOUSE, 126.0, 3.0, "kick" to 1.6, "off" to 0.6, "tonal" to -0.5, "sub" to 0.5, "hat" to 0.3),
        p(Genre.PROGRESSIVE_HOUSE, 126.0, 3.0, "kick" to 1.4, "tonal" to 0.8, "bright" to 0.5),
        p(Genre.BASS_HOUSE, 126.0, 3.0, "kick" to 1.4, "dist" to 0.8, "sub" to 0.6, "loud" to 0.4),
        p(Genre.ELECTRO_HOUSE, 128.0, 3.0, "kick" to 1.3, "dist" to 0.8, "bright" to 0.7, "loud" to 0.5),
        p(Genre.BIG_ROOM, 128.0, 2.0, "kick" to 1.6, "loud" to 0.9, "bright" to 0.4, "hat" to -0.5),
        p(Genre.FUTURE_HOUSE, 125.0, 3.0, "kick" to 1.4, "sub" to 0.4, "bright" to 0.5, "bb" to 0.2),
        p(Genre.AFRO_HOUSE, 121.0, 3.0, "kick" to 1.2, "hat" to 0.7, "tonal" to 0.3, "bright" to -0.3),
        p(Genre.MELODIC_TECHNO, 123.0, 3.0, "kick" to 1.5, "tonal" to 0.9, "bright" to -0.4, "bb" to -0.3),
        p(Genre.TECHNO, 132.0, 5.0, "kick" to 1.8, "bb" to -0.6, "tonal" to -0.4, "bright" to -0.4, "hat" to 0.5),
        p(Genre.HARD_TECHNO, 148.0, 8.0, "kick" to 1.8, "dist" to 1.0, "loud" to 0.8, "tonal" to -0.3),
        p(Genre.MINIMAL, 125.0, 3.0, "kick" to 1.4, "loud" to -0.8, "tonal" to -0.6, "sub" to -0.5),
        p(Genre.TRANCE, 138.0, 4.0, "kick" to 1.5, "tonal" to 1.0, "off" to 0.7, "bright" to 0.4),
        p(Genre.PSYTRANCE, 143.0, 4.0, "kick" to 1.5, "roll" to 2.0, "bright" to 0.4),
        p(Genre.DUBSTEP, 140.0, 4.0, "kick" to -1.2, "ht" to 1.6, "sub" to 0.8, "dist" to 0.6, "hat" to -0.2),
        p(Genre.RIDDIM, 145.0, 6.0, "kick" to -1.0, "ht" to 1.5, "dist" to 1.0, "tonal" to -0.4, "hat" to -0.3),
        p(Genre.DRUM_AND_BASS, 174.0, 4.0, "kick" to -0.8, "bb" to 1.2, "hat" to 0.5, "sub" to 0.5),
        p(Genre.JUNGLE, 165.0, 6.0, "kick" to -1.0, "bb" to 0.8, "hat" to 0.8, "loud" to -0.3),
        p(Genre.BREAKBEAT, 132.0, 6.0, "kick" to -1.0, "bb" to 0.8, "hat" to 0.3),
        p(Genre.UK_GARAGE, 132.0, 3.0, "kick" to -0.6, "bb" to 0.9, "hat" to 0.6, "sub" to 0.3),
        p(Genre.TRAP, 145.0, 8.0, "kick" to -1.0, "ht" to 1.4, "hat" to 0.8, "sub" to 1.0, "dist" to -0.3),
        p(Genre.FUTURE_BASS, 150.0, 8.0, "kick" to -0.8, "ht" to 1.2, "tonal" to 1.0, "bright" to 0.6, "dist" to -0.5),
        p(Genre.HARDSTYLE, 152.0, 4.0, "kick" to 1.8, "dist" to 1.2, "loud" to 1.0),
        p(Genre.HARDCORE, 175.0, 15.0, "kick" to 1.8, "dist" to 1.0, "loud" to 0.8),
        p(Genre.MOOMBAHTON, 108.0, 4.0, "kick" to -0.3, "bb" to 0.8, "sub" to 0.3),
        p(Genre.AMAPIANO, 113.0, 3.0, "kick" to -0.2, "hat" to 0.6, "sub" to 0.8, "tonal" to 0.3),
        p(Genre.ELECTRO, 128.0, 8.0, "kick" to -0.7, "bb" to 0.8, "bright" to 0.4),
        p(Genre.SYNTHWAVE, 105.0, 15.0, "kick" to 0.4, "bb" to 1.0, "tonal" to 0.8, "bright" to 0.4, "sub" to -0.6),
        p(Genre.DOWNTEMPO, 92.0, 12.0, "loud" to -0.5, "kick" to -0.4, "tonal" to 0.4),
        p(Genre.AMBIENT, 85.0, 20.0, "loud" to -1.5, "kick" to -1.5, "tonal" to 0.8, "hat" to -1.0),
        p(Genre.NU_DISCO, 118.0, 4.0, "kick" to 1.2, "bb" to 0.5, "tonal" to 0.5, "bright" to 0.6),
    )
    private val byGenre = profiles.associateBy { it.genre }

    fun normalized(f: AudioFeatures): Map<String, Double> = mapOf(
        "kick" to f.kick,
        "off" to ((f.offbeatHat - 0.3) / 0.5).coerceIn(0.0, 1.0),
        "bb" to f.backbeat,
        "ht" to f.halfTime,
        "roll" to (f.roll / 0.8).coerceIn(0.0, 1.0),
        "hat" to (f.hatRate / 3.0).coerceIn(0.0, 1.0),
        "sub" to (f.subBass / 0.3).coerceIn(0.0, 1.0),
        "bright" to ((f.centroidHz - 800) / 2500).coerceIn(0.0, 1.0),
        "dist" to ((f.flatness - 0.05) / 0.3).coerceIn(0.0, 1.0),
        "loud" to ((f.loudnessDb + 20) / 12).coerceIn(0.0, 1.0),
        "tonal" to f.keyConfidence.coerceIn(0.0, 1.0),
    )

    /** Probability per EDM sub-genre (sums to 1). Empty when there is no usable tempo. */
    fun classify(f: AudioFeatures): Map<Genre, Double> {
        if (f.bpm <= 0) return emptyMap()
        val n = normalized(f)
        val raw = profiles.associate { pr ->
            val z = (f.bpm - pr.bpm) / pr.sigma
            var s = -0.5 * z * z
            for ((k, w) in pr.terms) s += w * ((n[k] ?: 0.5) - 0.5) * 2
            pr.genre to s
        }
        val mx = raw.values.max()
        val ex = raw.mapValues { exp((it.value - mx) * 1.3) }
        val sum = ex.values.sum()
        return ex.mapValues { it.value / sum }
    }

    /** Typical DJ tempo range for a genre, or null when there's no audio profile for it. */
    fun typicalRange(g: Genre): ClosedFloatingPointRange<Double>? {
        val pr = byGenre[g] ?: return null
        val half = maxOf(pr.sigma * 2.0, 5.0)
        return (pr.bpm - half)..(pr.bpm + half)
    }

    /**
     * If [bpm] doesn't fit [genre] but one of the detected related tempos does (e.g. 116.7 → 175 for
     * drum & bass), returns that tempo. Otherwise null.
     */
    fun fixTempoForGenre(genre: Genre, bpm: Double, candidates: List<Pair<Double, Double>>): Double? {
        val range = typicalRange(genre) ?: return null
        if (bpm <= 0 || bpm in range) return null
        val fromCandidates = candidates.filter { it.first in range && it.second >= 0.45 }.maxByOrNull { it.second }?.first
        if (fromCandidates != null) return fromCandidates
        // Fall back to exact musical ratios of the detected tempo.
        return listOf(2.0, 0.5, 1.5, 2.0 / 3, 4.0 / 3, 0.75).map { com.cratez.core.dsp.AudioAnalyzer.roundBpm(bpm * it) }
            .filter { it in range }.minByOrNull { kotlin.math.abs(it - (range.start + range.endInclusive) / 2) }
    }

    /** How plausible a genre label is for this tempo (0.3..1). Catches wrong online labels. */
    fun tempoPlausibility(g: Genre, bpm: Double): Double {
        val pr = byGenre[g] ?: return 1.0
        if (bpm <= 0) return 1.0
        val z = listOf(bpm, bpm / 2, bpm * 2).minOf { abs(it - pr.bpm) } / (pr.sigma * 1.5 + 3)
        return max(0.05, exp(-0.5 * z * z))
    }

    /** 0..1: does this sound like electronic dance music at all? */
    fun edmLikelihood(f: AudioFeatures): Double {
        if (f.bpm <= 0) return 0.3
        val n = normalized(f)
        val fourFloor = n["kick"]!! * (if (f.bpm in 115.0..160.0) 1.0 else 0.6)
        val bassMusic = n["ht"]!! * n["sub"]!! * (if (f.bpm in 135.0..155.0) 1.0 else 0.4) * 1.4
        val dnb = n["bb"]!! * (if (f.bpm in 160.0..180.0) 1.0 else 0.2) * (0.5 + n["hat"]!! * 0.5) * 1.3
        val loud = n["loud"]!!
        return (max(fourFloor, max(bassMusic, dnb)) * 0.8 + loud * 0.2).coerceIn(0.0, 1.0)
    }

    fun describe(f: AudioFeatures): String {
        val bits = mutableListOf<String>()
        if (f.bpm > 0) bits += "${fmtBpm(f.bpm)} BPM"
        when {
            f.kick > 0.75 -> bits += "4/4 kick"
            f.halfTime > 0.5 -> bits += "half-time drums"
            f.backbeat > 0.4 && f.kick < 0.5 -> bits += "broken beat"
        }
        if (f.roll > 0.5 && f.kick > 0.6) bits += "rolling bass"
        if (f.offbeatHat > 0.6) bits += "off-beat hats"
        if (f.subBass > 0.2) bits += "heavy sub"
        if (f.flatness > 0.25) bits += "distorted"
        return bits.joinToString(" · ")
    }

    fun fmtBpm(b: Double) = if (b % 1.0 == 0.0) b.toInt().toString() else String.format(java.util.Locale.US, "%.1f", b)
}

data class GenreVote(val source: String, val raw: String, val genre: Genre, val weight: Double)

data class GenreVerdict(
    val genre: Genre,
    val confidence: Double,
    val reasons: List<String>,
    /** Runner-up suggestions for quick re-labelling. */
    val alternatives: List<Genre>,
)

/** Combines file tags, online databases and audio analysis into one genre decision. */
object GenreFusion {
    /**
     * Like [decide], but when a confident named source (file tag / database) says a genre whose
     * tempo range only fits a related tempo (×1.5, ×2…), the tempo is corrected first.
     * Returns the verdict and the (possibly corrected) audio features.
     */
    fun decideWithTempoFix(votes: List<GenreVote>, audio: AudioFeatures?): Pair<GenreVerdict, AudioFeatures?> {
        if (audio == null || audio.bpm <= 0) return decide(votes, audio) to audio
        val strong = votes.filter { it.genre.edm && !GenreNormalizer.isGenericElectronic(it.genre) }
            .groupBy { it.genre }.mapValues { e -> e.value.sumOf { it.weight } }.maxByOrNull { it.value }
        if (strong != null && strong.value >= 2.0) {
            val fixed = EdmClassifier.fixTempoForGenre(strong.key, audio.bpm, audio.bpmCandidates)
            if (fixed != null) {
                val a2 = audio.copy(bpm = fixed)
                return decide(votes, a2).let { v -> v.copy(reasons = v.reasons + "Tempo corrected ${EdmClassifier.fmtBpm(audio.bpm)} → ${EdmClassifier.fmtBpm(fixed)} to fit ${strong.key.label}") } to a2
            }
        }
        return decide(votes, audio) to audio
    }

    fun decide(votes: List<GenreVote>, audio: AudioFeatures?): GenreVerdict {
        val audioProbs = audio?.let { EdmClassifier.classify(it) } ?: emptyMap()
        val edmAudio = audio?.let { EdmClassifier.edmLikelihood(it) }
        val reasons = votes.groupBy { it.source }.map { (src, v) -> "$src: ${v.joinToString(", ") { it.raw }.take(60)}" }.toMutableList()
        audio?.let { if (it.bpm > 0) reasons += "Audio: ${EdmClassifier.describe(it)}" }

        var edmScore = votes.filter { it.genre.edm }.sumOf { it.weight }
        var otherScore = votes.filter { !it.genre.edm }.sumOf { it.weight }
        if (edmAudio != null) {
            // Audio evidence matters most when online sources are silent.
            val w = if (votes.isEmpty()) 2.5 else 1.2
            edmScore += edmAudio * w
            otherScore += (1 - edmAudio) * w * 0.6
        }

        if (edmScore >= otherScore && (edmScore > 0)) {
            val bpm = audio?.bpm ?: 0.0
            val specific = votes.filter { it.genre.edm && !GenreNormalizer.isGenericElectronic(it.genre) }
            val scores = HashMap<Genre, Double>()
            for (v in specific) scores.merge(v.genre, v.weight * EdmClassifier.tempoPlausibility(v.genre, bpm), Double::plus)
            val audioWeight = if (specific.isEmpty()) 3.0 else 2.0
            for ((g, p) in audioProbs) scores.merge(g, p * audioWeight, Double::plus)
            if (scores.isEmpty()) {
                val generic = votes.firstOrNull { it.genre.edm }?.genre ?: Genre.ELECTRONIC
                return GenreVerdict(generic, 0.4, reasons, emptyList())
            }
            val total = scores.values.sum()
            val ranked = scores.entries.sortedByDescending { it.value }
            val best = ranked[0]
            val share = best.value / total
            val familyShare = edmScore / (edmScore + otherScore + 1e-9)
            // Agreement between a named source and the audio is a strong signal.
            val agree = specific.any { it.genre == best.key } && (audioProbs[best.key] ?: 0.0) >= 0.12
            val conf = ((share * 0.7 + 0.3) * familyShare + if (agree) 0.15 else 0.0).coerceIn(0.05, 0.99)
            return GenreVerdict(best.key, conf, reasons, ranked.drop(1).take(4).map { it.key })
        }

        val nonEdm = votes.filter { !it.genre.edm }
        if (nonEdm.isNotEmpty()) {
            val scores = nonEdm.groupBy { it.genre }.mapValues { e -> e.value.sumOf { it.weight } }
            val ranked = scores.entries.sortedByDescending { it.value }
            val total = scores.values.sum()
            val conf = (ranked[0].value / total * 0.8 * (otherScore / (edmScore + otherScore + 1e-9)) + 0.15).coerceIn(0.05, 0.95)
            return GenreVerdict(ranked[0].key, conf, reasons, ranked.drop(1).take(4).map { it.key })
        }
        // No metadata and the audio doesn't sound like club music: rough guess only.
        val guess = when {
            audio == null || audio.bpm <= 0 -> Genre.OTHER
            audio.bpm in 78.0..105.0 && audio.backbeat > 0.3 && audio.subBass > 0.12 -> Genre.HIP_HOP
            audio.loudnessDb < -18 -> Genre.AMBIENT
            else -> Genre.OTHER
        }
        return GenreVerdict(guess, 0.2, reasons, audioProbs.entries.sortedByDescending { it.value }.take(4).map { it.key })
    }
}
