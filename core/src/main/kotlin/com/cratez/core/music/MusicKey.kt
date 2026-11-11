package com.cratez.core.music

import kotlin.math.abs

/**
 * Musical key as an index 0..23: 0..11 = major (pitch class C..B), 12..23 = minor (pitch class C..B).
 */
object MusicKey {
    private val NAMES = arrayOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    fun isMinor(key: Int) = key >= 12
    fun pitchClass(key: Int) = key % 12

    /** Camelot wheel number 1..12. */
    fun camelotNumber(key: Int): Int {
        val majorPc = if (isMinor(key)) (pitchClass(key) + 3) % 12 else pitchClass(key)
        return ((majorPc * 7) % 12 + 7) % 12 + 1
    }

    fun camelot(key: Int): String = "${camelotNumber(key)}${if (isMinor(key)) "A" else "B"}"

    /** e.g. "Am", "F#", "Ebm". */
    fun shortName(key: Int): String = NAMES[pitchClass(key)] + if (isMinor(key)) "m" else ""

    /** e.g. "A Minor". */
    fun longName(key: Int): String = NAMES[pitchClass(key)] + if (isMinor(key)) " Minor" else " Major"

    fun fromCamelot(code: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})\\s*([ABab])\\s*$").find(code) ?: return null
        val n = m.groupValues[1].toInt()
        if (n !in 1..12) return null
        val minor = m.groupValues[2].equals("A", true)
        for (k in 0 until 24) if (camelotNumber(k) == n && isMinor(k) == minor) return k
        return null
    }

    /** Parses "8A", "Am", "A minor", "F#m", "Gb", "C#maj", Open Key "1m"/"1d". */
    fun parse(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        fromCamelot(t)?.let { return it }
        Regex("^(\\d{1,2})([mdMD])$").find(t)?.let { m ->
            // Open Key: 1d = C major, 1m = A minor; each step is a fifth.
            val n = m.groupValues[1].toInt()
            if (n in 1..12) {
                val majorPc = ((n - 1) * 7) % 12
                return if (m.groupValues[2].equals("m", true)) 12 + (majorPc + 9) % 12 else majorPc
            }
        }
        val m = Regex("^([A-Ga-g])\\s*([#♯b♭]?)\\s*(.*)$").find(t) ?: return null
        val base = when (m.groupValues[1].uppercase()) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; else -> 11
        }
        val acc = when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
        val rest = m.groupValues[3].trim().lowercase()
        val minor = rest.startsWith("m") && !rest.startsWith("maj") || rest.startsWith("min")
        if (rest.isNotEmpty() && !minor && !rest.startsWith("maj")) return null
        val pc = ((base + acc) % 12 + 12) % 12
        return if (minor) 12 + pc else pc
    }

    /** Harmonic-mixing compatibility (Camelot rules). */
    fun compatibility(a: Int, b: Int): Compat {
        val na = camelotNumber(a); val nb = camelotNumber(b)
        val sameLetter = isMinor(a) == isMinor(b)
        val diff = ((nb - na) % 12 + 12) % 12
        return when {
            a == b -> Compat.PERFECT
            sameLetter && (diff == 1 || diff == 11) -> Compat.GREAT
            !sameLetter && na == nb -> Compat.GREAT
            sameLetter && (diff == 2 || diff == 7) -> Compat.ENERGY
            else -> Compat.NONE
        }
    }

    enum class Compat(val label: String) {
        PERFECT("Same key"), GREAT("Harmonic"), ENERGY("Energy boost"), NONE("Clash")
    }

    /** Mixed-In-Key style wheel colour (ARGB) for a key. */
    fun wheelColor(key: Int): Long {
        val colors = longArrayOf(
            0xFF56F1DA, 0xFF7DF2AA, 0xFFAEF589, 0xFFE8DAA1, 0xFFFDBFA7, 0xFFFDAFCC,
            0xFFF0B3F0, 0xFFD1BAFE, 0xFFBAD0FF, 0xFF9EE6FE, 0xFF67F6F6, 0xFF4CF4F0
        )
        return colors[camelotNumber(key) - 1]
    }

    /** Whether two tempos are mixable within [tolerance] (fraction), allowing half/double time. */
    fun tempoMatch(a: Double, b: Double, tolerance: Double = 0.06): Boolean {
        if (a <= 0 || b <= 0) return false
        return listOf(b, b * 2, b / 2).any { abs(it - a) / a <= tolerance }
    }
}
