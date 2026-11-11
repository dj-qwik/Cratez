@file:OptIn(UnstableApi::class)

package com.cratez.app.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.cratez.core.dsp.Fft
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Live audio frame for the visualizers. */
class AudioFrame {
    val wave = FloatArray(256)
    val spectrum = FloatArray(64)
    var bass = 0f; var mid = 0f; var treb = 0f
    /** Relative to their recent averages (1 = normal), like MilkDrop's bass_att etc. */
    var bassAtt = 1f; var midAtt = 1f; var trebAtt = 1f
    var beat = 0f
    var level = 0f
}

/** Taps PCM out of ExoPlayer's audio pipeline (no microphone permission needed). */
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    private const val N = 2048
    private val ring = FloatArray(N)
    private var w = 0
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    @Volatile var lastAudioAt = 0L
    private val lock = Any()

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) { channels = max(1, channelCount); this.encoding = encoding }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        synchronized(lock) {
            val ch = channels
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = b.asFloatBuffer()
                val frames = fb.remaining() / ch
                for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += fb.get(f * ch + c); ring[w] = s / ch; w = (w + 1) % N }
            } else if (encoding == C.ENCODING_PCM_16BIT) {
                val sb = b.asShortBuffer()
                val frames = sb.remaining() / ch
                for (f in 0 until frames) { var s = 0f; for (c in 0 until ch) s += sb.get(f * ch + c) / 32768f; ring[w] = s / ch; w = (w + 1) % N }
            }
        }
        lastAudioAt = System.currentTimeMillis()
    }

    // ---- Analysis for the renderer (called on the GL thread) ----
    private val fft = Fft(1024)
    private val tmp = FloatArray(1024)
    private val mag = FloatArray(513)
    private var avgBass = 0.05f; private var avgMid = 0.05f; private var avgTreb = 0.05f
    private var beatAvg = 0.05f
    private var beatCool = 0
    private var waveGain = 1f

    fun analyze(out: AudioFrame) {
        val silent = System.currentTimeMillis() - lastAudioAt > 300
        synchronized(lock) {
            for (i in 0 until 1024) tmp[i] = if (silent) 0f else ring[(w - 1024 + i + N) % N]
        }
        // Waveform (auto-gained)
        var peak = 0.001f
        for (i in 0 until 1024) peak = max(peak, kotlin.math.abs(tmp[i]))
        waveGain = waveGain * 0.95f + (0.9f / peak).coerceAtMost(8f) * 0.05f
        for (i in 0 until 256) out.wave[i] = (tmp[i * 4] * waveGain).coerceIn(-1.5f, 1.5f)

        fft.magnitudes(tmp, 0, mag)
        // 44.1k / 1024 ≈ 43 Hz per bin
        fun band(a: Int, b: Int): Float { var s = 0f; for (i in a until b) s += mag[i]; return s / (b - a) / 40f }
        val bass = band(1, 6); val mid = band(6, 47); val treb = band(47, 200)
        out.bass = out.bass * 0.6f + bass * 0.4f
        out.mid = out.mid * 0.6f + mid * 0.4f
        out.treb = out.treb * 0.6f + treb * 0.4f
        avgBass = avgBass * 0.985f + bass * 0.015f
        avgMid = avgMid * 0.985f + mid * 0.015f
        avgTreb = avgTreb * 0.985f + treb * 0.015f
        out.bassAtt = (out.bass / max(avgBass, 0.004f)).coerceIn(0f, 3f)
        out.midAtt = (out.mid / max(avgMid, 0.004f)).coerceIn(0f, 3f)
        out.trebAtt = (out.treb / max(avgTreb, 0.004f)).coerceIn(0f, 3f)
        out.level = min(1f, (bass + mid + treb) * 2f)

        // Log-spaced 64-band spectrum
        for (i in 0 until 64) {
            val lo = (1.5f * 2f.pow(i / 64f * 8.2f)).toInt().coerceIn(1, 500)
            val hi = (1.5f * 2f.pow((i + 1) / 64f * 8.2f)).toInt().coerceIn(lo + 1, 512)
            var s = 0f; for (k in lo until hi) s = max(s, mag[k])
            val v = (ln(1f + s) / 4.5f).coerceIn(0f, 1f)
            out.spectrum[i] = max(v, out.spectrum[i] * 0.86f)
        }

        // Beat: bass jumps clearly above its running level.
        beatAvg = beatAvg * 0.9f + bass * 0.1f
        if (beatCool > 0) beatCool--
        if (bass > beatAvg * 1.35f && bass > avgBass * 1.1f && beatCool == 0 && !silent) { out.beat = 1f; beatCool = 12 }
        else out.beat *= 0.88f
    }
}
