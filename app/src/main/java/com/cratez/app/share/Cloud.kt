package com.cratez.app.share

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cratez.app.CratezApp
import com.cratez.app.MainActivity
import com.cratez.app.R
import com.cratez.app.analysis.AnalysisEngine
import com.cratez.app.analysis.TagFile
import com.cratez.app.data.AppSettings
import com.cratez.app.data.CratezDb
import com.cratez.app.data.Settings
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import com.cratez.app.library.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class CloudState(
    val transfers: List<Transfer> = emptyList(),
    val checking: Boolean = false,
    val lastCheck: Long = 0,
    val lastError: String? = null,
)

/**
 * Sends tracks to a friend's code through the Cratez relay (Cloudflare Worker) and collects tracks
 * that friends sent to this phone. Files travel in 8 MB chunks and wait up to 7 days on the relay.
 */
class Cloud(
    private val ctx: Context,
    private val db: CratezDb,
    private val settings: Settings,
    private val importer: Importer,
    private val engine: AnalysisEngine,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state
    private val inboxLock = Mutex()
    private val json = "application/json".toMediaType()
    private val octet = "application/octet-stream".toMediaType()

    class RelayException(msg: String) : IOException(msg)

    private suspend fun s(): AppSettings = settings.ensureIdentity()

    private fun req(s: AppSettings, path: String) = Request.Builder().url(s.relayUrl.trimEnd('/') + path)
        .header("x-cratez-code", s.myCode).header("x-cratez-secret", s.secret).header("User-Agent", "Cratez/1.0")

    private fun call(r: Request): JSONObject = http.newCall(r).execute().use { resp ->
        val body = resp.body?.string() ?: ""
        val j = runCatching { JSONObject(body) }.getOrElse { JSONObject().put("error", "Relay error ${resp.code}") }
        if (!resp.isSuccessful) throw RelayException(j.optString("error", "Relay error ${resp.code}"))
        j
    }

    fun configured(s: AppSettings) = s.onlineSharing && s.relayUrl.startsWith("https://") && !s.relayUrl.contains("REPLACE")

    suspend fun register(): Boolean = withContext(Dispatchers.IO) {
        val s = s()
        if (!configured(s)) return@withContext false
        runCatching {
            call(Request.Builder().url(s.relayUrl.trimEnd('/') + "/v1/register")
                .post(JSONObject().put("code", s.myCode).put("secret", s.secret).put("name", s.myName).toString().toRequestBody(json)).build())
            true
        }.onFailure { e -> Log.w("Cratez", "register", e); _state.update { it.copy(lastError = e.message) } }.getOrDefault(false)
    }

    /** Returns the friend's display name if their code is registered, else null. */
    suspend fun lookup(code: String): String? = withContext(Dispatchers.IO) {
        val s = s()
        val j = call(req(s, "/v1/lookup/$code").get().build())
        if (j.optBoolean("exists")) j.optString("name").ifBlank { code } else null
    }

    private fun newId(): String {
        val r = SecureRandom(); val a = "abcdefghijkmnopqrstuvwxyz23456789"
        return (1..20).map { a[r.nextInt(a.length)] }.joinToString("")
    }

    /** Upload tracks and drop them into a friend's inbox. Returns an error message or null. */
    suspend fun send(toCode: String, toName: String, tracks: List<TrackEntity>): String? = withContext(Dispatchers.IO) {
        val s = s()
        if (!configured(s)) return@withContext "Online sharing isn't set up yet (Setup → Relay)"
        if (!register()) return@withContext _state.value.lastError ?: "Couldn't reach the relay"
        val items = JSONArray()
        val plan = tracks.map { it to newId() }
        _state.update { st -> st.copy(transfers = plan.map { (t, id) -> Transfer(id.hashCode().toLong(), toName, t.displayTitle, false, 0, t.sizeBytes) } + st.transfers) }
        try {
            for ((t, id) in plan) {
                val tid = id.hashCode().toLong()
                var sent = 0L; var n = 0
                ctx.contentResolver.openInputStream(Uri.parse(t.uri))?.use { ins ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        var fill = 0
                        while (fill < CHUNK) { val k = ins.read(buf, fill, CHUNK - fill); if (k < 0) break; fill += k }
                        if (fill == 0) break
                        call(req(s, "/v1/blob/$id/$n").put(buf.copyOf(fill).toRequestBody(octet)).build())
                        n++; sent += fill
                        _state.update { st -> st.copy(transfers = st.transfers.map { if (it.payloadId == tid) it.copy(bytes = sent, total = maxOf(it.total, sent)) else it }) }
                        if (fill < CHUNK) break
                    }
                } ?: throw RelayException("Can't open ${t.fileName}")
                if (n == 0) throw RelayException("${t.fileName} is empty")
                var hasArt = false
                t.artPath?.let { p -> runCatching { File(p).readBytes() }.getOrNull()?.let { TagFile.scaleJpeg(it, 500, 85) } }?.let { art ->
                    call(req(s, "/v1/blob/$id/art").put(art.toRequestBody(octet)).build()); hasArt = true
                }
                items.put(JSONObject().put("id", id).put("chunks", n).put("size", sent).put("meta", meta(t).put("hasArt", hasArt)))
            }
            val r = call(req(s, "/v1/send").post(JSONObject().put("to", toCode).put("items", items).toString().toRequestBody(json)).build())
            _state.update { st -> st.copy(transfers = st.transfers.map { tr -> if (plan.any { it.second.hashCode().toLong() == tr.payloadId }) tr.copy(status = 1, bytes = tr.total) else tr }) }
            Log.i("Cratez", "Delivered ${r.optInt("delivered")} to $toCode")
            null
        } catch (e: Exception) {
            Log.w("Cratez", "cloud send", e)
            _state.update { st -> st.copy(transfers = st.transfers.map { tr -> if (tr.status == 0 && plan.any { it.second.hashCode().toLong() == tr.payloadId }) tr.copy(status = 2) else tr }) }
            e.message ?: "Send failed"
        }
    }

    private fun meta(t: TrackEntity) = JSONObject().apply {
        put("fileName", t.fileName); put("mime", t.mime); put("title", t.title); put("artist", t.artist); put("album", t.album)
        put("year", t.year); put("label", t.label); put("genre", t.genre); put("conf", t.genreConfidence); put("bpm", t.bpm)
        put("key", t.musicalKey); put("energy", t.energy); put("quality", t.qualityLabel); put("cutoff", t.cutoffHz)
        put("durationMs", t.durationMs); put("bitrate", t.bitrateKbps); put("lossless", t.lossless)
        t.waveform?.let { put("wave", Base64.encodeToString(it, Base64.NO_WRAP)) }
    }

    /** Downloads everything waiting in this phone's inbox. Returns (count, sender names). */
    suspend fun checkInbox(): Pair<Int, Set<String>> = inboxLock.withLock {
        withContext<Pair<Int, Set<String>>>(Dispatchers.IO) {
            val s = s()
            if (!configured(s)) return@withContext 0 to emptySet()
            _state.update { it.copy(checking = true) }
            var got = 0
            val names = HashSet<String>()
            try {
                register()
                val items = call(req(s, "/v1/inbox").get().build()).optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val from = item.getString("from"); val id = item.getString("id"); val chunks = item.getInt("chunks")
                    val fromName = item.optString("fromName", from)
                    val meta = item.optJSONObject("meta") ?: JSONObject()
                    val tid = id.hashCode().toLong()
                    _state.update { st -> st.copy(transfers = listOf(Transfer(tid, fromName, meta.optString("title").ifBlank { meta.optString("fileName") }, true, 0, item.optLong("size"))) + st.transfers.filter { tr -> tr.payloadId != tid }) }
                    val tmp = File(ctx.cacheDir, "inbox_$id.part")
                    try {
                        tmp.outputStream().use { out ->
                            var bytes = 0L
                            for (n in 0 until chunks) {
                                http.newCall(req(s, "/v1/blob/$from/$id/$n").get().build()).execute().use { r ->
                                    if (!r.isSuccessful) throw RelayException("Download failed (${r.code})")
                                    r.body!!.byteStream().use { ins -> bytes += ins.copyTo(out, 1 shl 16) }
                                }
                                _state.update { st -> st.copy(transfers = st.transfers.map { tr -> if (tr.payloadId == tid) tr.copy(bytes = bytes) else tr }) }
                            }
                        }
                        val art = if (meta.optBoolean("hasArt")) runCatching {
                            http.newCall(req(s, "/v1/blob/$from/$id/art").get().build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
                        }.getOrNull() else null
                        saveIncoming(meta, tmp, fromName, art)
                        call(req(s, "/v1/ack").post(JSONObject().put("items", JSONArray().put(JSONObject().put("from", from).put("id", id))).toString().toRequestBody(json)).build())
                        got++; names += fromName
                        _state.update { st -> st.copy(transfers = st.transfers.map { tr -> if (tr.payloadId == tid) tr.copy(status = 1, bytes = tr.total) else tr }) }
                    } catch (e: Exception) {
                        Log.w("Cratez", "inbox item", e)
                        _state.update { st -> st.copy(transfers = st.transfers.map { tr -> if (tr.payloadId == tid) tr.copy(status = 2) else tr }) }
                    } finally { tmp.delete() }
                }
                _state.update { it.copy(checking = false, lastCheck = System.currentTimeMillis(), lastError = null) }
            } catch (e: Exception) {
                Log.w("Cratez", "inbox", e)
                _state.update { it.copy(checking = false, lastError = e.message) }
            }
            got to names
        }
    }

    private suspend fun saveIncoming(meta: JSONObject, file: File, from: String, art: ByteArray?) {
        val name = meta.optString("fileName").ifBlank { "cratez_${System.currentTimeMillis()}.mp3" }
        val mime = meta.optString("mime").ifBlank { TagFile.guessMime(name) }
        val dest = file.inputStream().use { importer.copyIntoMusic(it, name, mime) } ?: throw IOException("Couldn't save $name")
        val bpm = meta.optDouble("bpm", 0.0)
        val entity = TrackEntity(
            uri = dest.toString(), fileName = name, folder = "Sent to me", mime = mime, sizeBytes = file.length(),
            durationMs = meta.optLong("durationMs"), bitrateKbps = meta.optInt("bitrate"), lossless = meta.optBoolean("lossless"),
            title = meta.optString("title"), artist = meta.optString("artist"), album = meta.optString("album"),
            year = meta.optString("year"), label = meta.optString("label"), genre = meta.optString("genre"),
            genreConfidence = meta.optDouble("conf", 0.0), genreReasons = "Sent online by $from", bpm = bpm,
            musicalKey = meta.optInt("key", -1), keySource = "Friend", energy = meta.optInt("energy"),
            qualityLabel = meta.optString("quality"), cutoffHz = meta.optInt("cutoff"),
            waveform = meta.optString("wave").takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.NO_WRAP) },
            status = if (bpm > 0 && meta.optString("genre").isNotBlank()) Status.DONE else Status.PENDING,
            tagsWritten = true, tagNote = "Arrived tagged from $from", received = true, receivedFrom = from, analyzedAt = System.currentTimeMillis(),
        )
        val id = importer.insertReceived(dest, entity)
        if (id > 0) {
            if (art != null) {
                val f = engine.artFile(id); f.writeBytes(art)
                db.tracks().get(id)?.let { db.tracks().update(it.copy(artPath = f.absolutePath)) }
            }
            if (entity.status == Status.PENDING) engine.kick()
        }
    }

    fun clearFinished() = _state.update { st -> st.copy(transfers = st.transfers.filter { it.status == 0 }) }

    companion object {
        const val CHUNK = 8 * 1024 * 1024
        const val CHANNEL = "cratez_inbox"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("cratez-inbox", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun notify(ctx: Context, count: Int, names: Set<String>) {
            if (count == 0) return
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tracks from friends", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val who = names.joinToString(", ")
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("$who sent you $count track${if (count == 1) "" else "s"}")
                .setContentText("Already tagged and waiting in your Received crate")
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(4242, n)
        }
    }
}

/** Background check of the online inbox every ~15 minutes. */
class InboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CratezApp
        val (n, names) = app.cloud.checkInbox()
        Cloud.notify(applicationContext, n, names)
        return Result.success()
    }
}
