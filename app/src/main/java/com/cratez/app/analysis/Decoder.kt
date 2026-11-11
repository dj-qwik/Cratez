package com.cratez.app.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.cratez.core.dsp.AudioAnalyzer
import com.cratez.core.dsp.Fft
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DecodeResult(
    /** Mono analysis window at [rate] Hz. */
    val samples: FloatArray,
    val rate: Int,
    /** 240 peak values 0..255 across the whole track. */
    val waveform: ByteArray,
    /** Average power spectrum at the native rate (for the quality check), or null. */
    val spectrum: DoubleArray?,
    val nativeRate: Int,
    val durationMs: Long,
)

/** Decodes any Android-supported audio file to mono PCM for analysis using MediaCodec. */
object Decoder {
    private const val WAVE_BINS = 240
    private const val WINDOW_SEC = 120.0
    private const val SPEC_N = 4096

    fun decode(ctx: Context, uri: Uri, knownDurationMs: Long = 0): DecodeResult {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        require(track >= 0) { "No audio track" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var nativeRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else knownDurationMs * 1000
        val durSec = if (durUs > 0) durUs / 1e6 else 360.0

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var decim = max(1, (nativeRate / AudioAnalyzer.TARGET_RATE.toDouble()).roundToInt())
        var rate = nativeRate / decim
        val winStart = if (durSec <= WINDOW_SEC + 20) 0.0 else min(durSec * 0.22, durSec - WINDOW_SEC)
        val winEnd = winStart + WINDOW_SEC
        var buf = FloatArray(((WINDOW_SEC + 1) * rate).toInt())
        var n = 0
        val wave = FloatArray(WAVE_BINS)
        val waveN = IntArray(WAVE_BINS)
        val specSnips = ArrayList<FloatArray>()
        var nextSnipAt = winStart + 3.0
        var snip: FloatArray? = null
        var snipPos = 0

        var framesDone = 0L
        var acc = 0f; var accN = 0
        var floatPcm = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val deadline = System.currentTimeMillis() + 90_000
        try {
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    nativeRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    val d2 = max(1, (nativeRate / AudioAnalyzer.TARGET_RATE.toDouble()).roundToInt())
                    if (d2 != decim || nativeRate / d2 != rate) {
                        decim = d2; rate = nativeRate / d2
                        buf = FloatArray(((WINDOW_SEC + 1) * rate).toInt()); n = 0
                    }
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val bb = ob.slice().order(ByteOrder.nativeOrder())
                        val frameCount = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        val fb = if (floatPcm) bb.asFloatBuffer() else null
                        val sb = if (!floatPcm) bb.asShortBuffer() else null
                        for (f in 0 until frameCount) {
                            var s = 0f
                            for (c in 0 until channels) {
                                val idx = f * channels + c
                                s += if (fb != null) fb.get(idx) else sb!!.get(idx) / 32768f
                            }
                            s /= channels
                            val t = framesDone.toDouble() / nativeRate
                            val bin = ((t / durSec) * WAVE_BINS).toInt()
                            if (bin in 0 until WAVE_BINS) { wave[bin] += s * s; waveN[bin]++ }
                            if (t >= winStart && t < winEnd) {
                                acc += s; accN++
                                if (accN == decim) { if (n < buf.size) buf[n++] = acc / decim; acc = 0f; accN = 0 }
                                if (snip == null && t >= nextSnipAt && specSnips.size < 24) { snip = FloatArray(SPEC_N); snipPos = 0 }
                                snip?.let { sn ->
                                    sn[snipPos++] = s
                                    if (snipPos == SPEC_N) { specSnips += sn; snip = null; nextSnipAt = t + 4.0 }
                                }
                            }
                            framesDone++
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; ex.release()
        }

        // RMS per slice, contrast-stretched so drops and breakdowns are easy to see.
        for (i in 0 until WAVE_BINS) wave[i] = if (waveN[i] > 0) sqrt(wave[i] / waveN[i]) else 0f
        val maxRms = wave.max().coerceAtLeast(1e-6f)
        val waveBytes = ByteArray(WAVE_BINS) { (Math.pow((wave[it] / maxRms).toDouble(), 1.6) * 255).roundToInt().coerceIn(0, 255).toByte() }

        var spectrum: DoubleArray? = null
        if (specSnips.size >= 4) {
            val fft = Fft(SPEC_N)
            val mag = FloatArray(SPEC_N / 2 + 1)
            val sum = DoubleArray(SPEC_N / 2 + 1)
            for (sn in specSnips) {
                fft.magnitudes(sn, 0, mag)
                for (i in mag.indices) sum[i] += mag[i].toDouble() * mag[i]
            }
            spectrum = DoubleArray(sum.size) { sum[it] / specSnips.size }
        }
        val durMs = if (durUs > 0) durUs / 1000 else (framesDone * 1000 / max(1, nativeRate))
        return DecodeResult(buf.copyOf(n), rate, waveBytes, spectrum, nativeRate, durMs)
    }
}
