package com.cratez.app.analysis

import android.util.Log
import com.cratez.core.text.TrackText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One matching record from an online music database. */
data class OnlineHit(
    val source: String,
    val artist: String,
    val title: String,
    val album: String? = null,
    val year: String? = null,
    val label: String? = null,
    val isrc: String? = null,
    /** Genre strings with a relative weight (sums to ~1 per hit). */
    val genres: List<Pair<String, Double>> = emptyList(),
    val artUrl: String? = null,
    val score: Double = 0.0,
)

/** Free, key-less lookups: iTunes Search, Deezer, MusicBrainz (+ Cover Art Archive). */
class Lookup {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Cratez/1.0 (Android music organizer)").header("Accept", "application/json").build())
        }.build()

    private class Throttle(val gapMs: Long) {
        private val m = Mutex(); private var last = 0L
        suspend fun <T> run(block: suspend () -> T): T = m.withLock {
            val wait = last + gapMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try { block() } finally { last = System.currentTimeMillis() }
        }
    }
    private val itunesGate = Throttle(3100)
    private val deezerGate = Throttle(150)
    private val mbGate = Throttle(1100)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun getJson(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.onFailure { Log.w("Cratez", "GET failed $url: ${it.message}") }.getOrNull()
    }

    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "image/*").build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
    }

    private fun durFactor(a: Long, b: Long): Double {
        if (a <= 0 || b <= 0) return 1.0
        val d = abs(a - b)
        return when { d < 4000 -> 1.0; d < 15000 -> 0.9; d < 45000 -> 0.75; else -> 0.55 }
    }

    suspend fun itunes(artist: String, title: String, durationMs: Long): OnlineHit? {
        val term = "${TrackText.searchArtist(artist)} ${TrackText.searchTitle(title)}"
        val body = itunesGate.run { getJson("https://itunes.apple.com/search?media=music&entity=song&limit=15&term=${enc(term)}") } ?: return null
        val arr = runCatching { JSONObject(body).getJSONArray("results") }.getOrNull() ?: return null
        var best: OnlineHit? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ca = o.optString("artistName"); val ct = o.optString("trackName")
            val sc = TrackText.matchScore(artist, title, ca, ct) * durFactor(durationMs, o.optLong("trackTimeMillis"))
            if (sc > (best?.score ?: 0.0)) {
                best = OnlineHit(
                    "iTunes", ca, ct, o.optString("collectionName").ifBlank { null },
                    o.optString("releaseDate").take(4).ifBlank { null }, null, null,
                    listOfNotNull(o.optString("primaryGenreName").ifBlank { null }?.let { it to 1.0 }),
                    o.optString("artworkUrl100").ifBlank { null }?.replace("100x100bb", "1000x1000bb"), sc,
                )
            }
        }
        return best?.takeIf { it.score >= 0.6 }
    }

    suspend fun deezer(artist: String, title: String, durationMs: Long): OnlineHit? {
        val a = TrackText.searchArtist(artist); val t = TrackText.searchTitle(title)
        var body = deezerGate.run { getJson("https://api.deezer.com/search?limit=15&q=${enc("artist:\"$a\" track:\"$t\"")}") }
        var arr = body?.let { runCatching { JSONObject(it).getJSONArray("data") }.getOrNull() }
        if (arr == null || arr.length() == 0) {
            body = deezerGate.run { getJson("https://api.deezer.com/search?limit=15&q=${enc("$a $t")}") }
            arr = body?.let { runCatching { JSONObject(it).getJSONArray("data") }.getOrNull() } ?: return null
        }
        var bestObj: JSONObject? = null; var bestSc = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sc = TrackText.matchScore(artist, title, o.optJSONObject("artist")?.optString("name") ?: "", o.optString("title")) *
                durFactor(durationMs, o.optLong("duration") * 1000)
            if (sc > bestSc) { bestSc = sc; bestObj = o }
        }
        val o = bestObj ?: return null
        if (bestSc < 0.6) return null
        val album = o.optJSONObject("album")
        var genres = emptyList<Pair<String, Double>>()
        var label: String? = null; var year: String? = null; var isrc: String? = null
        album?.optLong("id")?.takeIf { it > 0 }?.let { id ->
            deezerGate.run { getJson("https://api.deezer.com/album/$id") }?.let { ab ->
                runCatching {
                    val j = JSONObject(ab)
                    label = j.optString("label").ifBlank { null }
                    year = j.optString("release_date").take(4).ifBlank { null }
                    val g = j.optJSONObject("genres")?.optJSONArray("data") ?: JSONArray()
                    genres = (0 until g.length()).map { g.getJSONObject(it).optString("name") to 1.0 / g.length() }
                }
            }
        }
        o.optLong("id").takeIf { it > 0 }?.let { id ->
            deezerGate.run { getJson("https://api.deezer.com/track/$id") }?.let { tb -> isrc = runCatching { JSONObject(tb).optString("isrc").ifBlank { null } }.getOrNull() }
        }
        return OnlineHit(
            "Deezer", o.optJSONObject("artist")?.optString("name") ?: artist, o.optString("title"),
            album?.optString("title"), year, label, isrc, genres, album?.optString("cover_xl")?.ifBlank { null }, bestSc,
        )
    }

    suspend fun musicBrainz(artist: String, title: String, durationMs: Long): OnlineHit? {
        val a = TrackText.searchArtist(artist).replace("\"", ""); val t = TrackText.searchTitle(title).replace("\"", "")
        val q = "recording:\"$t\" AND artist:\"$a\""
        val body = mbGate.run { getJson("https://musicbrainz.org/ws/2/recording/?fmt=json&limit=8&query=${enc(q)}") } ?: return null
        val recs = runCatching { JSONObject(body).getJSONArray("recordings") }.getOrNull() ?: return null
        var best: JSONObject? = null; var bestSc = 0.0
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val credit = r.optJSONArray("artist-credit")
            val ca = (0 until (credit?.length() ?: 0)).joinToString(" ") { credit!!.getJSONObject(it).optString("name") }
            val sc = TrackText.matchScore(artist, title, ca, r.optString("title")) * durFactor(durationMs, r.optLong("length")) *
                (r.optInt("score", 50) / 100.0).coerceIn(0.5, 1.0)
            if (sc > bestSc) { bestSc = sc; best = r }
        }
        val r = best ?: return null
        if (bestSc < 0.55) return null
        val id = r.getString("id")
        val genres = ArrayList<Pair<String, Double>>()
        fun collect(j: JSONObject, key: String, weight: Double) {
            val arr = j.optJSONArray(key) ?: return
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("count", 1) > 0 }
            val total = items.sumOf { it.optInt("count", 1).coerceAtLeast(1) }.toDouble()
            for (it in items) genres += it.optString("name") to weight * it.optInt("count", 1).coerceAtLeast(1) / total
        }
        mbGate.run { getJson("https://musicbrainz.org/ws/2/recording/$id?fmt=json&inc=genres+tags+isrcs") }?.let { d ->
            runCatching { val j = JSONObject(d); collect(j, "genres", 1.0); if (genres.isEmpty()) collect(j, "tags", 0.6) }
        }
        if (genres.isEmpty()) {
            val artistId = r.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")?.optString("id")
            if (!artistId.isNullOrBlank()) mbGate.run { getJson("https://musicbrainz.org/ws/2/artist/$artistId?fmt=json&inc=genres+tags") }?.let { d ->
                runCatching { val j = JSONObject(d); collect(j, "genres", 0.5); if (genres.isEmpty()) collect(j, "tags", 0.3) }
            }
        }
        val rel = r.optJSONArray("releases")?.optJSONObject(0)
        val relId = rel?.optString("id")
        return OnlineHit(
            "MusicBrainz", artist, r.optString("title"), rel?.optString("title"),
            (r.optString("first-release-date").ifBlank { rel?.optString("date") ?: "" }).take(4).ifBlank { null },
            null, r.optJSONArray("isrcs")?.optString(0), genres,
            relId?.let { "https://coverartarchive.org/release/$it/front-500" }, bestSc,
        )
    }
}
