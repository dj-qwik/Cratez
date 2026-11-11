package com.cratez.app.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.cratez.app.analysis.AnalysisEngine
import com.cratez.app.analysis.TagFile
import com.cratez.app.data.CratezDb
import com.cratez.app.data.Settings
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import com.cratez.app.library.Importer
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Peer(val endpointId: String, val code: String, val name: String, val isFriend: Boolean)

data class Transfer(
    val payloadId: Long,
    val peer: String,
    val title: String,
    val incoming: Boolean,
    val bytes: Long = 0,
    val total: Long = 0,
    /** 0 running, 1 done, 2 failed */
    val status: Int = 0,
)

data class BeamRequest(val endpointId: String, val code: String, val name: String)

data class BeamState(
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val peers: List<Peer> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val request: BeamRequest? = null,
    val message: String? = null,
)

/**
 * Cratez-to-Cratez transfer over Google Nearby Connections (Bluetooth + Wi-Fi Direct, no internet).
 * Each phone is identified by its friend code; friends are auto-accepted, strangers must be approved.
 * Tracks travel with their full analysis (genre, BPM, key, energy, art), so they land ready to play.
 */
class Beam(
    private val ctx: Context,
    private val db: CratezDb,
    private val settings: Settings,
    private val importer: Importer,
    private val engine: AnalysisEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { Nearby.getConnectionsClient(ctx) }
    private val strategy = Strategy.P2P_POINT_TO_POINT
    private val serviceId = "com.cratez.beam"

    private val _state = MutableStateFlow(BeamState())
    val state: StateFlow<BeamState> = _state

    private var friendCodes = emptySet<String>()
    private val peerInfo = HashMap<String, Pair<String, String>>() // endpointId -> code, name
    private val pendingSend = HashMap<String, List<TrackEntity>>()
    private val outgoing = HashMap<String, MutableSet<Long>>()
    private val manifests = HashMap<Long, JSONObject>()            // payloadId -> track meta
    private val incomingFiles = HashMap<Long, Payload>()
    private val finishedFiles = HashSet<Long>()
    private val senderName = HashMap<Long, String>()

    init {
        scope.launch { db.friends().observeAll().collect { list -> friendCodes = list.map { it.code }.toSet(); refreshPeers() } }
    }

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private suspend fun endpointName(): String {
        val s = settings.ensureIdentity()
        return "CRZ|${s.myCode}|${s.myName.replace("|", " ")}"
    }

    private fun parseName(n: String): Pair<String, String>? {
        val p = n.split('|')
        return if (p.size >= 3 && p[0] == "CRZ") p[1] to p.drop(2).joinToString("|") else null
    }

    fun startAdvertising() {
        if (!hasPermissions() || _state.value.advertising) return
        scope.launch {
            client.startAdvertising(endpointName(), serviceId, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
                .addOnSuccessListener { _state.update { it.copy(advertising = true) } }
                .addOnFailureListener { e -> Log.w("Cratez", "advertise", e); _state.update { it.copy(advertising = false) } }
        }
    }

    fun stopAdvertising() { runCatching { client.stopAdvertising() }; _state.update { it.copy(advertising = false) } }

    fun startDiscovery() {
        if (!hasPermissions() || _state.value.discovering) return
        client.startDiscovery(serviceId, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { _state.update { it.copy(discovering = true) } }
            .addOnFailureListener { e -> Log.w("Cratez", "discover", e); _state.update { it.copy(discovering = false, message = "Couldn't start scanning: ${e.message}") } }
    }

    fun stopDiscovery() { runCatching { client.stopDiscovery() }; _state.update { it.copy(discovering = false, peers = emptyList()) }; peerInfo.clear() }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun clearFinished() = _state.update { s -> s.copy(transfers = s.transfers.filter { it.status == 0 }) }

    private fun refreshPeers() {
        _state.update { s -> s.copy(peers = peerInfo.map { (id, cn) -> Peer(id, cn.first, cn.second, cn.first in friendCodes) }.sortedByDescending { it.isFriend }) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val p = parseName(info.endpointName) ?: return
            peerInfo[endpointId] = p; refreshPeers()
        }
        override fun onEndpointLost(endpointId: String) { peerInfo.remove(endpointId); refreshPeers() }
    }

    /** Sends tracks to a nearby Cratez user. */
    fun send(peer: Peer, tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        pendingSend[peer.endpointId] = tracks
        scope.launch {
            client.requestConnection(endpointName(), peer.endpointId, lifecycle)
                .addOnFailureListener { e -> pendingSend.remove(peer.endpointId); _state.update { it.copy(message = "Couldn't reach ${peer.name}: ${e.message}") } }
        }
        _state.update { it.copy(message = "Connecting to ${peer.name}…") }
    }

    fun answerRequest(accept: Boolean, addFriend: Boolean) {
        val r = _state.value.request ?: return
        _state.update { it.copy(request = null) }
        if (accept) {
            if (addFriend) scope.launch { db.friends().upsert(com.cratez.app.data.FriendEntity(r.code, r.name)) }
            client.acceptConnection(r.endpointId, payloads)
        } else client.rejectConnection(r.endpointId)
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val p = parseName(info.endpointName)
            if (p == null) { client.rejectConnection(endpointId); return }
            peerInfo[endpointId] = p
            if (!info.isIncomingConnection || p.first in friendCodes) client.acceptConnection(endpointId, payloads)
            else _state.update { it.copy(request = BeamRequest(endpointId, p.first, p.second)) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                pendingSend.remove(endpointId)
                _state.update { it.copy(message = "Connection declined or failed") }
                return
            }
            pendingSend.remove(endpointId)?.let { tracks -> scope.launch { doSend(endpointId, tracks) } }
        }

        override fun onDisconnected(endpointId: String) {
            outgoing.remove(endpointId)
            _state.update { s -> s.copy(transfers = s.transfers.map { if (it.status == 0 && it.peer == (peerInfo[endpointId]?.second ?: "")) it.copy(status = 2) else it }) }
        }
    }

    private suspend fun doSend(endpointId: String, tracks: List<TrackEntity>) {
        val peerName = peerInfo[endpointId]?.second ?: "friend"
        val s = settings.ensureIdentity()
        val arr = JSONArray()
        val files = ArrayList<Pair<Payload, TrackEntity>>()
        for (t in tracks) {
            val pfd = runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(t.uri), "r") }.getOrNull() ?: continue
            val payload = Payload.fromFile(pfd)
            files += payload to t
            arr.put(JSONObject().apply {
                put("pid", payload.id); put("fileName", t.fileName); put("mime", t.mime); put("size", t.sizeBytes)
                put("title", t.title); put("artist", t.artist); put("album", t.album); put("year", t.year); put("label", t.label)
                put("genre", t.genre); put("conf", t.genreConfidence); put("bpm", t.bpm); put("key", t.musicalKey)
                put("energy", t.energy); put("quality", t.qualityLabel); put("cutoff", t.cutoffHz); put("durationMs", t.durationMs)
                put("bitrate", t.bitrateKbps); put("lossless", t.lossless)
                t.waveform?.let { put("wave", Base64.encodeToString(it, Base64.NO_WRAP)) }
                t.artPath?.let { p -> runCatching { File(p).readBytes() }.getOrNull()?.let { TagFile.scaleJpeg(it, 400, 85) }?.let { put("art", Base64.encodeToString(it, Base64.NO_WRAP)) } }
            })
        }
        val manifest = JSONObject().put("type", "manifest").put("from", s.myCode).put("name", s.myName).put("tracks", arr)
        client.sendPayload(endpointId, Payload.fromBytes(manifest.toString().toByteArray()))
        val ids = HashSet<Long>()
        val newTransfers = files.map { (p, t) -> ids += p.id; Transfer(p.id, peerName, t.displayTitle, false, 0, t.sizeBytes) }
        outgoing[endpointId] = ids
        _state.update { it.copy(transfers = newTransfers + it.transfers, message = "Beaming ${files.size} track${if (files.size == 1) "" else "s"} to $peerName") }
        for ((p, _) in files) client.sendPayload(endpointId, p)
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> runCatching {
                    val j = JSONObject(String(payload.asBytes()!!))
                    if (j.optString("type") != "manifest") return
                    val from = j.optString("name", "friend")
                    val arr = j.getJSONArray("tracks")
                    val list = ArrayList<Transfer>()
                    for (i in 0 until arr.length()) {
                        val t = arr.getJSONObject(i)
                        val pid = t.getLong("pid")
                        manifests[pid] = t; senderName[pid] = from
                        list += Transfer(pid, from, t.optString("title").ifBlank { t.optString("fileName") }, true, 0, t.optLong("size"))
                        if (pid in finishedFiles) scope.launch { finishIncoming(pid) }
                    }
                    _state.update { it.copy(transfers = list + it.transfers, message = "$from is beaming you ${list.size} track${if (list.size == 1) "" else "s"}") }
                }
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                else -> {}
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            val status = when (u.status) { PayloadTransferUpdate.Status.SUCCESS -> 1; PayloadTransferUpdate.Status.IN_PROGRESS -> 0; else -> 2 }
            _state.update { s -> s.copy(transfers = s.transfers.map { if (it.payloadId == u.payloadId) it.copy(bytes = u.bytesTransferred, total = maxOf(u.totalBytes, it.total), status = if (status == 1 && it.incoming) 0 else status) else it }) }
            if (status == 1 && incomingFiles.containsKey(u.payloadId)) {
                finishedFiles += u.payloadId
                if (manifests.containsKey(u.payloadId)) scope.launch { finishIncoming(u.payloadId) }
            }
            if (status != 0) outgoing[endpointId]?.let { set ->
                set.remove(u.payloadId)
                if (set.isEmpty()) {
                    outgoing.remove(endpointId)
                    scope.launch { delay(1500); runCatching { client.disconnectFromEndpoint(endpointId) } ; _state.update { it.copy(message = "Beam complete ✓") } }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun finishIncoming(pid: Long) {
        val payload = incomingFiles.remove(pid) ?: return
        val meta = manifests.remove(pid) ?: return
        finishedFiles.remove(pid)
        val from = senderName.remove(pid) ?: "friend"
        try {
            val f = payload.asFile() ?: throw IllegalStateException("No file")
            val name = meta.optString("fileName", "beam_$pid.mp3")
            val mime = meta.optString("mime", TagFile.guessMime(name))
            val srcUri = if (Build.VERSION.SDK_INT >= 29) f.asUri() else null
            val dest = if (srcUri != null) ctx.contentResolver.openInputStream(srcUri)?.use { importer.copyIntoMusic(it, name, mime) }
            else f.asJavaFile()?.inputStream()?.use { importer.copyIntoMusic(it, name, mime) }
            if (srcUri != null) runCatching { ctx.contentResolver.delete(srcUri, null, null) } else runCatching { f.asJavaFile()?.delete() }
            dest ?: throw IllegalStateException("Couldn't save")
            val bpm = meta.optDouble("bpm", 0.0)
            val entity = TrackEntity(
                uri = dest.toString(), fileName = name, folder = "Beamed", mime = mime, sizeBytes = meta.optLong("size"),
                durationMs = meta.optLong("durationMs"), bitrateKbps = meta.optInt("bitrate"), lossless = meta.optBoolean("lossless"),
                title = meta.optString("title"), artist = meta.optString("artist"), album = meta.optString("album"),
                year = meta.optString("year"), label = meta.optString("label"), genre = meta.optString("genre"),
                genreConfidence = meta.optDouble("conf", 0.0), genreReasons = "Beamed from $from", bpm = bpm,
                musicalKey = meta.optInt("key", -1), keySource = "Beam", energy = meta.optInt("energy"),
                qualityLabel = meta.optString("quality"), cutoffHz = meta.optInt("cutoff"),
                waveform = meta.optString("wave").takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.NO_WRAP) },
                status = if (bpm > 0 && meta.optString("genre").isNotBlank()) Status.DONE else Status.PENDING,
                tagsWritten = true, tagNote = "Arrived tagged via Beam", received = true, receivedFrom = from, analyzedAt = System.currentTimeMillis(),
            )
            val id = importer.insertReceived(dest, entity)
            if (id > 0) {
                meta.optString("art").takeIf { it.isNotBlank() }?.let { b64 ->
                    val file = engine.artFile(id); file.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
                    db.tracks().get(id)?.let { db.tracks().update(it.copy(artPath = file.absolutePath)) }
                }
                if (entity.status == Status.PENDING) engine.kick()
            }
            _state.update { s -> s.copy(transfers = s.transfers.map { if (it.payloadId == pid) it.copy(status = 1, bytes = it.total) else it }) }
        } catch (e: Exception) {
            Log.e("Cratez", "receive", e)
            _state.update { s -> s.copy(transfers = s.transfers.map { if (it.payloadId == pid) it.copy(status = 2) else it }) }
        }
    }
}
