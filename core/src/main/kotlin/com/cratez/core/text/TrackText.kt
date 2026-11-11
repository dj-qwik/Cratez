package com.cratez.core.text

import java.text.Normalizer

data class ParsedName(val artist: String?, val title: String, val trackNo: Int?)

object TrackText {
    private val junkBrackets = Regex(
        "[\\[(](?:[^\\])]*?(?:www\\.|\\.com|\\.net|free ?download|official|lyric|audio|video|hq|hd|320|kbps|clean|explicit|out now|premiere|visuali[sz]er)[^\\])]*)[\\])]",
        RegexOption.IGNORE_CASE,
    )
    private val plainMix = Regex("\\s*[\\[(](original|extended|radio|club|clean|dirty|album|single)( mix| edit| version)?[\\])]", RegexOption.IGNORE_CASE)
    private val copySuffix = Regex("(\\s*\\(\\d{1,2}\\)|\\s*-\\s*copy(\\s*\\(\\d+\\))?|\\s+copy)$", RegexOption.IGNORE_CASE)

    /** Parses names like "01 - Artist - Title (Extended Mix).mp3" or "Artist_-_Title". */
    fun parseFileName(fileName: String): ParsedName {
        var s = fileName.substringBeforeLast('.', fileName)
        s = s.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        s = copySuffix.replace(s, "").trim()
        s = junkBrackets.replace(s, "").trim()
        var trackNo: Int? = null
        Regex("^(\\d{1,3})(?:\\s*[-.)]\\s*|\\s+)(.+)$").find(s)?.let {
            trackNo = it.groupValues[1].toInt(); s = it.groupValues[2]
        }
        val parts = s.split(Regex("\\s+[-–—]\\s+|\\s*[–—]\\s*"), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
            ParsedName(parts[0].trim(), parts[1].trim(), trackNo)
        else ParsedName(null, s.trim(), trackNo)
    }

    /** Title prepared for an online search: drops "(Original Mix)", promo junk, "feat." parts. */
    fun searchTitle(title: String): String {
        var s = junkBrackets.replace(title, "")
        s = plainMix.replace(s, "")
        s = Regex("\\s*[\\[(]?\\s*(feat\\.?|ft\\.?|featuring)\\s+[^\\])]*[\\])]?", RegexOption.IGNORE_CASE).replace(s, "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun searchArtist(artist: String): String =
        artist.split(Regex("\\s*(,|&| x | feat\\.?| ft\\.?| featuring| vs\\.?| and )\\s*", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }?.trim() ?: artist

    /** Remix/VIP/bootleg descriptor inside a title, lower-cased, or null for the original. */
    fun mixTag(title: String): String? {
        val m = Regex("[\\[(]([^\\])]*(remix|rmx|vip|bootleg|flip|edit|rework|refix|dub)[^\\])]*)[\\])]", RegexOption.IGNORE_CASE).find(title)
            ?: return null
        val t = m.groupValues[1].lowercase()
        if (Regex("^(radio|extended|club|original|clean|dirty|album) (edit|mix)$").matches(t)) return null
        return normalize(t)
    }

    fun normalize(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun tokens(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() && it !in STOP }.toSet()
    private val STOP = setOf("the", "a", "an", "feat", "ft", "featuring", "original", "mix", "extended", "radio", "edit")

    /** Dice similarity on word tokens, 0..1. */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return if (normalize(a) == normalize(b)) 1.0 else 0.0
        val inter = ta.intersect(tb).size
        return 2.0 * inter / (ta.size + tb.size)
    }

    /**
     * Does an online result describe the same recording? Requires artist + title agreement and,
     * when the file is a remix, the same remix.
     */
    fun matchScore(artist: String, title: String, candArtist: String, candTitle: String): Double {
        val a = maxOf(similarity(artist, candArtist), similarity(searchArtist(artist), candArtist), if (normalize(candArtist).contains(normalize(searchArtist(artist)))) 1.0 else 0.0)
        val t = maxOf(similarity(searchTitle(title), searchTitle(candTitle)), similarity(title, candTitle))
        if (a < 0.5 || t < 0.6) return 0.0
        val mixA = mixTag(title); val mixB = mixTag(candTitle)
        val mixPenalty = when {
            mixA == null && mixB == null -> 1.0
            mixA != null && mixB != null -> if (similarity(mixA, mixB) >= 0.5) 1.0 else 0.4
            else -> 0.55
        }
        return (a * 0.45 + t * 0.55) * mixPenalty
    }

    /** Key used to spot the same song saved twice (different files, bitrates or "(1)" copies). */
    fun dupeKey(artist: String?, title: String): String {
        val t = normalize(copySuffix.replace(junkBrackets.replace(plainMix.replace(title, ""), ""), ""))
        val a = artist?.let { normalize(searchArtist(it)) } ?: ""
        return "$a|$t"
    }
}
