package com.cratez.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT with a cached twiddle table and Hann window. */
class Fft(val size: Int) {
    private val levels: Int
    private val cosT = FloatArray(size / 2)
    private val sinT = FloatArray(size / 2)
    private val rev = IntArray(size)
    val window = FloatArray(size)
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two" }
        var l = 0
        while ((1 shl l) < size) l++
        levels = l
        for (i in 0 until size / 2) {
            cosT[i] = cos(2 * PI * i / size).toFloat()
            sinT[i] = sin(2 * PI * i / size).toFloat()
        }
        for (i in 0 until size) {
            var x = i
            var r = 0
            repeat(levels) { r = (r shl 1) or (x and 1); x = x ushr 1 }
            rev[i] = r
        }
        for (i in 0 until size) window[i] = (0.5 - 0.5 * cos(2 * PI * i / (size - 1))).toFloat()
    }

    /**
     * Windowed magnitude spectrum of [input] starting at [offset] (zero padded past the end).
     * Writes size/2+1 magnitudes into [out].
     */
    fun magnitudes(input: FloatArray, offset: Int, out: FloatArray) {
        for (i in 0 until size) {
            val idx = offset + i
            val v = if (idx in input.indices) input[idx] else 0f
            re[rev[i]] = v * window[i]
            im[rev[i]] = 0f
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = -sinT[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
        for (i in 0..size / 2) out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
    }
}
