package com.cratez.core.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything Cratez measures from the audio signal itself. */
data class AudioFeatures(
    val bpm: Double,
    val bpmConfidence: Double,
    val key: Int,
    val keyConfidence: Double,
    val energy: Int,
    val loudnessDb: Double,
    /** Fraction of power below 90 Hz. */
    val subBass: Double,
    /** Fraction of power 90-250 Hz. */
    val bass: Double,
    /** Fraction of power above 5 kHz. */
    val high: Double,
    val centroidHz: Double,
    /** Spectral flatness 200 Hz-4 kHz (0 tonal .. 1 noisy/distorted). */
    val flatness: Double,
    /** How consistently a kick lands on every beat (0..1). Four-on-the-floor is ~0.85+. */
    val kick: Double,
    /** Share of hi-hat energy on the off-beat (0..1, 0.5 = even). */
    val offbeatHat: Double,
    /** Snare/clap on 2 & 4 (0..1). */
    val backbeat: Double,
    /** Snare on 3 only, half-time feel (0..1). */
    val halfTime: Double,
    /** Rolling 16th-note bass between kicks, psytrance style (0..~1). */
    val roll: Double,
    /** Hi-hat onsets per beat. */
    val hatRate: Double,
    /** Onsets per second. */
    val onsetRate: Double,
) {
    companion object {
        val EMPTY = AudioFeatures(0.0, 0.0, -1, 0.0, 1, -60.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

object AudioAnalyzer {
    const val TARGET_RATE = 22050
    const val MIN_BPM = 78.0
    const val MAX_BPM = 190.0

    private const val N = 1024
    private const val HOP = 256

    fun analyze(x: FloatArray, sr: Int): AudioFeatures {
        val frames = if (x.size < N) 0 else (x.size - N) / HOP + 1
        if (frames < 300) return AudioFeatures.EMPTY
        val fps = sr.toDouble() / HOP
        val fft = Fft(N)
        val half = N / 2
        val mag = FloatArray(half + 1)
        val prevLog = FloatArray(half + 1)
        val curLog = FloatArray(half + 1)
        val binHz = sr.toDouble() / N
        fun bin(hz: Double) = min(half, max(1, (hz / binHz).roundToInt()))
        val subEnd = bin(90.0); val bassEnd = bin(250.0); val lowFluxEnd = bin(150.0)
        val snareA = bin(1000.0); val snareB = bin(4000.0); val highA = bin(5000.0)
        val flatA = bin(200.0); val flatB = bin(4000.0)

        val flux = DoubleArray(frames)
        val lowF = DoubleArray(frames)
        val midF = DoubleArray(frames)
        val highF = DoubleArray(frames)
        var pSub = 0.0; var pBass = 0.0; var pHigh = 0.0; var pTot = 0.0
        var centroidNum = 0.0
        var flatSum = 0.0; var flatCount = 0
        var sumSq = 0.0
        for (s in x) sumSq += s.toDouble() * s

        for (f in 0 until frames) {
            fft.magnitudes(x, f * HOP, mag)
            var fl = 0.0; var lo = 0.0; var mi = 0.0; var hi = 0.0
            var framePow = 0.0
            var logSum = 0.0; var linSum = 0.0
            for (b in 1..half) {
                val m = mag[b]
                val p = m.toDouble() * m
                framePow += p
                if (b <= subEnd) pSub += p else if (b <= bassEnd) pBass += p
                if (b >= highA) pHigh += p
                centroidNum += p * b * binHz
                curLog[b] = ln(1f + m)
                val d = curLog[b] - prevLog[b]
                if (d > 0) {
                    fl += d
                    if (b <= lowFluxEnd) lo += d
                    if (b in snareA..snareB) mi += d
                    if (b >= highA) hi += d
                }
                if (b in flatA..flatB) { logSum += ln(p + 1e-12); linSum += p }
            }
            pTot += framePow
            if (linSum > 1e-6) {
                val cnt = (flatB - flatA + 1).toDouble()
                flatSum += kotlin.math.exp(logSum / cnt) / (linSum / cnt)
                flatCount++
            }
            if (f > 0) { flux[f] = fl; lowF[f] = lo; midF[f] = mi; highF[f] = hi }
            System.arraycopy(curLog, 0, prevLog, 0, half + 1)
        }

        val onset = detrend(flux, (fps * 0.3).roundToInt())
        val low = normalizeMean(detrend(lowF, (fps * 0.3).roundToInt()))
        val mid = normalizeMean(detrend(midF, (fps * 0.3).roundToInt()))
        val high = normalizeMean(detrend(highF, (fps * 0.3).roundToInt()))

        // ---- Tempo ----
        val (bpmRaw, conf) = estimateTempo(onset, fps)
        fun midOf(bpm: Double): Pair<Double, Double> {
            val m = DoubleArray(4); val mc = IntArray(4)
            val (fine, ph) = refineTempo(onset, fps, bpm)
            val per = fps * 60.0 / fine
            var tt = ph; var i = 0
            while (tt + per < frames - 2) { m[i % 4] += peakAt(mid, tt); mc[i % 4]++; tt += per; i++ }
            for (j in 0..3) if (mc[j] > 0) m[j] /= mc[j]
            val sum = m.sum().coerceAtLeast(1e-9)
            return ((max(m[0] + m[2], m[1] + m[3]) / sum - 0.5) * 2).coerceIn(0.0, 1.0) to fine
        }
        // Octave check: the right tempo puts the snare/clap on the backbeat (2 & 4).
        var chosen = bpmRaw
        val (bbMain, _) = midOf(bpmRaw)
        for (alt in listOf(bpmRaw * 2, bpmRaw / 2)) {
            if (alt < MIN_BPM || alt > MAX_BPM) continue
            val (bbAlt, _) = midOf(alt)
            if (bbAlt > 0.35 && bbAlt > bbMain + 0.3) { chosen = alt; break }
        }
        val (bpmFine, phase) = refineTempo(onset, fps, chosen)
        val bpm = roundBpm(bpmFine)
        val period = fps * 60.0 / bpmFine

        // ---- Groove features on the beat grid ----
        val beats = ArrayList<Double>()
        var t = phase
        while (t + period < frames - 2) { beats.add(t); t += period }
        fun at(arr: DoubleArray, pos: Double) = peakAt(arr, pos)
        val kOn = beats.map { at(low, it) }
        val kOff = beats.map { at(low, it + period / 2) }
        val kQuarter = beats.map { (at(low, it + period / 4) + at(low, it + 3 * period / 4)) / 2 }
        val all = (kOn + kOff).sorted()
        val p90 = if (all.isEmpty()) 0.0 else all[(all.size * 0.9).toInt().coerceAtMost(all.size - 1)]
        val thr = 0.3 * p90
        val consistency = if (kOn.isEmpty()) 0.0 else kOn.indices.count { kOn[it] > thr && kOn[it] > 1.3 * kOff[it] }.toDouble() / kOn.size
        val kick = consistency.coerceIn(0.0, 1.0)
        val roll = if (kOn.isNotEmpty() && kOn.average() > 1e-9) (kQuarter.average() / kOn.average()).coerceIn(0.0, 1.5) else 0.0

        val hOn = beats.map { at(high, it) }.average()
        val hOff = beats.map { at(high, it + period / 2) }.average()
        val offbeatHat = if (hOn + hOff > 1e-9) hOff / (hOn + hOff) else 0.5

        val m = DoubleArray(4)
        val mc = IntArray(4)
        beats.forEachIndexed { i, b -> m[i % 4] += at(mid, b); mc[i % 4]++ }
        for (j in 0..3) if (mc[j] > 0) m[j] /= mc[j]
        val mSum = m.sum().coerceAtLeast(1e-9)
        val backbeat = ((max(m[0] + m[2], m[1] + m[3]) / mSum - 0.5) * 2).coerceIn(0.0, 1.0)
        val jMax = m.indices.maxBy { m[it] }
        val opp = m[(jMax + 2) % 4]
        val halfTime = ((m[jMax] - opp) / (m[jMax] + opp + 1e-9)).coerceIn(0.0, 1.0)

        val hatPeaks = countPeaks(high)
        val hatRate = if (beats.isEmpty()) 0.0 else hatPeaks.toDouble() / beats.size
        val onsetRate = countPeaks(normalizeMean(onset)) / (frames / fps)

        // ---- Spectrum ----
        val tot = pTot.coerceAtLeast(1e-12)
        val subBass = pSub / tot
        val bass = pBass / tot
        val highRatio = pHigh / tot
        val centroid = centroidNum / tot
        val flatness = if (flatCount > 0) flatSum / flatCount else 0.0
        val rms = sqrt(sumSq / x.size)
        val loudness = 20 * log10(rms.coerceAtLeast(1e-9))

        // ---- Key ----
        val (key, keyConf) = KeyDetector.detect(x, sr)

        // ---- Energy 1..10 ----
        val loudN = ((loudness + 22) / 15).coerceIn(0.0, 1.0)
        val densN = (onsetRate / 6.0).coerceIn(0.0, 1.0)
        val brightN = ((centroid - 600) / 2600).coerceIn(0.0, 1.0)
        val bpmN = ((bpm - 90) / 80).coerceIn(0.0, 1.0)
        val e = 0.45 * loudN + 0.22 * densN + 0.18 * brightN + 0.15 * bpmN
        val energy = (1 + e * 9).roundToInt().coerceIn(1, 10)

        return AudioFeatures(
            bpm = bpm, bpmConfidence = conf, key = key, keyConfidence = keyConf,
            energy = energy, loudnessDb = loudness, subBass = subBass, bass = bass, high = highRatio,
            centroidHz = centroid, flatness = flatness, kick = kick, offbeatHat = offbeatHat,
            backbeat = backbeat, halfTime = halfTime, roll = roll, hatRate = hatRate, onsetRate = onsetRate,
        )
    }

    private fun peakAt(arr: DoubleArray, pos: Double): Double {
        val c = pos.roundToInt()
        var m = 0.0
        for (i in c - 1..c + 1) if (i in arr.indices && arr[i] > m) m = arr[i]
        return m
    }

    fun roundBpm(b: Double): Double {
        val r = b.roundToInt().toDouble()
        return if (abs(b - r) < 0.3) r else (b * 10).roundToInt() / 10.0
    }

    private fun detrend(a: DoubleArray, radius: Int): DoubleArray {
        val out = DoubleArray(a.size)
        val prefix = DoubleArray(a.size + 1)
        for (i in a.indices) prefix[i + 1] = prefix[i] + a[i]
        for (i in a.indices) {
            val lo = max(0, i - radius); val hi = min(a.size, i + radius + 1)
            val avg = (prefix[hi] - prefix[lo]) / (hi - lo)
            out[i] = max(0.0, a[i] - avg)
        }
        return out
    }

    private fun normalizeMean(a: DoubleArray): DoubleArray {
        val mean = a.average()
        if (mean <= 1e-12) return a
        return DoubleArray(a.size) { a[it] / mean }
    }

    private fun countPeaks(a: DoubleArray): Int {
        if (a.isEmpty()) return 0
        val mean = a.average()
        var sd = 0.0
        for (v in a) sd += (v - mean) * (v - mean)
        sd = sqrt(sd / a.size)
        val thr = mean + 1.0 * sd
        var c = 0
        var i = 2
        while (i < a.size - 2) {
            val v = a[i]
            if (v > thr && v >= a[i - 1] && v >= a[i - 2] && v > a[i + 1] && v >= a[i + 2]) { c++; i += 2 }
            i++
        }
        return c
    }

    private fun prior(bpm: Double): Double {
        val d = log2(bpm / 128.0) / 0.55
        return kotlin.math.exp(-0.5 * d * d)
    }

    /** Coarse tempo from a comb over the onset autocorrelation. Returns bpm and a 0..1 confidence. */
    internal fun estimateTempo(onset: DoubleArray, fps: Double): Pair<Double, Double> {
        val mean = onset.average()
        val o = DoubleArray(onset.size) { onset[it] - mean }
        val maxLag = min(o.size - 1, ceil(fps * 60.0 / MIN_BPM * 4).toInt() + 2)
        val acf = DoubleArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var s = 0.0
            for (i in 0 until o.size - lag) s += o[i] * o[i + lag]
            acf[lag] = s / (o.size - lag)
        }
        val a0 = acf[0].coerceAtLeast(1e-12)
        fun acfAt(l: Double): Double {
            val i = floor(l).toInt()
            if (i + 1 > maxLag) return 0.0
            val fr = l - i
            return (acf[i] * (1 - fr) + acf[i + 1] * fr) / a0
        }
        var best = 120.0; var bestS = -1e9
        val scores = ArrayList<Double>()
        var b = MIN_BPM
        while (b <= MAX_BPM) {
            val lag = fps * 60.0 / b
            var s = 0.0
            for (k in 1..4) s += acfAt(lag * k) / k
            // Penalise if the half-beat is as strong as the beat (we're probably at half tempo).
            val sc = s * (0.35 + prior(b))
            scores.add(sc)
            if (sc > bestS) { bestS = sc; best = b }
            b += 0.25
        }
        val sorted = scores.sorted()
        val median = sorted[sorted.size / 2]
        val conf = if (bestS > 0) ((bestS - median) / bestS).coerceIn(0.0, 1.0) else 0.0
        return best to conf
    }

    /** Fine tempo + beat phase by fitting a pulse train to the onset envelope. */
    internal fun refineTempo(onset: DoubleArray, fps: Double, coarse: Double): Pair<Double, Double> {
        fun interp(pos: Double): Double {
            val i = floor(pos).toInt()
            if (i < 0 || i + 1 >= onset.size) return 0.0
            val fr = pos - i
            return onset[i] * (1 - fr) + onset[i + 1] * fr
        }
        fun fit(bpm: Double): Pair<Double, Double> {
            val p = fps * 60.0 / bpm
            var bestS = -1.0; var bestPh = 0.0
            var ph = 0.0
            while (ph < p) {
                var s = 0.0; var n = 0
                var pos = ph
                while (pos < onset.size - 1) { s += interp(pos); n++; pos += p }
                val avg = if (n > 0) s / n else 0.0
                if (avg > bestS) { bestS = avg; bestPh = ph }
                ph += 0.5
            }
            return bestS to bestPh
        }
        var best = coarse; var bestS = -1.0; var bestPh = 0.0
        var b = coarse - 1.0
        while (b <= coarse + 1.0) {
            val (s, ph) = fit(b)
            if (s > bestS) { bestS = s; best = b; bestPh = ph }
            b += 0.05
        }
        // Polish to 0.01 BPM.
        var b2 = best - 0.05
        val top = best + 0.05
        while (b2 <= top) {
            val (s, ph) = fit(b2)
            if (s > bestS) { bestS = s; best = b2; bestPh = ph }
            b2 += 0.01
        }
        return best to bestPh
    }
}

/** Chroma-based key detection (Krumhansl-Kessler profiles). */
object KeyDetector {
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun chroma(x: FloatArray, sr: Int): DoubleArray {
        val n = 8192
        val hop = 4096
        val fft = Fft(n)
        val mag = FloatArray(n / 2 + 1)
        val logm = DoubleArray(n / 2 + 1)
        val binHz = sr.toDouble() / n
        val lo = max(1, (60 / binHz).toInt())
        val hi = min(n / 2 - 1, (2100 / binHz).toInt())
        val pc = IntArray(n / 2 + 1) { -1 }
        val w = DoubleArray(n / 2 + 1)
        for (b in lo..hi) {
            val midi = 69 + 12 * log2(b * binHz / 440.0)
            val r = midi.roundToInt()
            val dev = abs(midi - r)
            pc[b] = ((r % 12) + 12) % 12
            w[b] = (1.0 - dev * 1.6).coerceAtLeast(0.0)
        }
        val chroma = DoubleArray(12)
        var off = 0
        while (off + n <= x.size) {
            fft.magnitudes(x, off, mag)
            for (b in 0..n / 2) logm[b] = ln(1.0 + mag[b])
            val frame = DoubleArray(12)
            val rad = 12
            for (b in lo..hi) {
                var s = 0.0; var c = 0
                for (k in b - rad..b + rad) if (k in 1 until n / 2) { s += logm[k]; c++ }
                val v = logm[b] - s / c
                if (v > 0 && pc[b] >= 0) frame[pc[b]] += v * w[b]
            }
            val sum = frame.sum()
            if (sum > 1e-6) for (i in 0..11) chroma[i] += frame[i] / sum
            off += hop
        }
        return chroma
    }

    /** Returns key index (0..23) and confidence (0..1). */
    fun detect(x: FloatArray, sr: Int): Pair<Int, Double> = detectFromChroma(chroma(x, sr))

    fun detectFromChroma(chroma: DoubleArray): Pair<Int, Double> {
        if (chroma.sum() <= 0) return -1 to 0.0
        val corr = DoubleArray(24)
        for (k in 0 until 24) {
            val prof = if (k < 12) MAJOR else MINOR
            val root = k % 12
            val rotated = DoubleArray(12) { prof[((it - root) % 12 + 12) % 12] }
            corr[k] = pearson(chroma, rotated)
        }
        val order = corr.indices.sortedByDescending { corr[it] }
        val best = order[0]
        val gap = corr[best] - corr[order[1]]
        val conf = (corr[best].coerceAtLeast(0.0) * 0.6 + (gap * 4).coerceIn(0.0, 0.4)).coerceIn(0.0, 1.0)
        return best to conf
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
    }
}

/** "True quality" check: finds the encoder low-pass cutoff in a full-rate spectrum. */
object QualityAnalyzer {
    data class Result(val cutoffHz: Int, val label: String, val suspicious: Boolean)

    /**
     * [avgPower] = average power spectrum (size fftSize/2+1) at [sampleRate].
     * [declaredKbps] = bitrate reported by the container (0 if unknown), [lossless] for FLAC/WAV/AIFF.
     */
    fun judge(avgPower: DoubleArray, sampleRate: Int, declaredKbps: Int, lossless: Boolean): Result {
        val bins = avgPower.size - 1
        val binHz = sampleRate / 2.0 / bins
        val db = DoubleArray(avgPower.size) { 10 * log10(avgPower[it] + 1e-20) }
        val refBins = (2000 / binHz).toInt()..(6000 / binHz).toInt().coerceAtMost(bins)
        val ref = refBins.map { db[it] }.sorted().let { it[it.size / 2] }
        val band = max(1, (250 / binHz).toInt())
        var cutoff = 0.0
        var b = bins - band
        while (b > (4000 / binHz)) {
            var s = 0.0
            for (i in b until b + band) s += db[i]
            if (s / band > ref - 55) { cutoff = (b + band) * binHz; break }
            b -= band
        }
        val nyq = sampleRate / 2.0
        val c = cutoff.roundToInt()
        val label = when {
            cutoff >= min(19800.0, nyq - 500) -> if (lossless) "Lossless" else "320 kbps grade"
            cutoff >= 18800 -> "~256-320 kbps grade"
            cutoff >= 17200 -> "~192 kbps grade"
            cutoff >= 15500 -> "~128 kbps grade"
            else -> "Low quality"
        }
        val suspicious = (lossless && cutoff < 19000 && nyq > 20000) || (declaredKbps >= 256 && cutoff < 17500)
        return Result(c, label, suspicious)
    }
}
