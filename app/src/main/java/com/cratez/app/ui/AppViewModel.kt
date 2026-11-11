package com.cratez.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cratez.app.CratezApp
import com.cratez.app.data.AppSettings
import com.cratez.app.data.FriendEntity
import com.cratez.app.data.Settings
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import com.cratez.core.music.MusicKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

enum class SortBy(val label: String) { ADDED("Recently added"), TITLE("Title"), ARTIST("Artist"), BPM("BPM"), KEY("Key (Camelot)"), ENERGY("Energy"), GENRE("Genre") }

data class Filter(
    val query: String = "",
    val crate: String = CRATE_ALL,
    val sort: SortBy = SortBy.ADDED,
    val bpmMin: Float = 60f,
    val bpmMax: Float = 200f,
    val minEnergy: Int = 0,
)

const val CRATE_ALL = "All"
const val CRATE_RECEIVED = "Received"
const val CRATE_REVIEW = "Review"

data class Match(val track: TrackEntity, val compat: MusicKey.Compat, val bpmDiff: Double)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as CratezApp
    val engine = a.engine
    val beam = a.beam
    val dupes = a.dupes
    val player = a.player
    val cloud = a.cloud

    val tracks: StateFlow<List<TrackEntity>> = a.db.tracks().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> = a.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val friends: StateFlow<List<FriendEntity>> = a.db.friends().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val queued: StateFlow<Int> = a.db.tracks().observeQueued().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val playlists: StateFlow<List<com.cratez.app.data.PlaylistEntity>> = a.db.playlists().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Playlists ----
    fun playlistTracks(p: com.cratez.app.data.PlaylistEntity): List<TrackEntity> {
        val byId = tracks.value.associateBy { it.id }
        return p.ids.mapNotNull { byId[it] }
    }
    suspend fun createPlaylist(name: String, ids: List<Long> = emptyList()): Long =
        a.db.playlists().insert(com.cratez.app.data.PlaylistEntity(name = name.trim().ifBlank { "Playlist ${playlists.value.size + 1}" }, trackIds = ids.joinToString(",")))
    fun addToPlaylist(playlistId: Long, ids: List<Long>) = viewModelScope.launch {
        val p = a.db.playlists().get(playlistId) ?: return@launch
        val existing = p.ids
        val add = ids.filter { it !in existing }
        a.db.playlists().update(p.withIds(existing + add))
        toast(if (add.isEmpty()) "Already in ${p.name}" else "Added ${add.size} to ${p.name}")
        clearSelection()
    }
    fun newPlaylistWith(name: String, ids: List<Long>) = viewModelScope.launch {
        createPlaylist(name, ids); toast("Created \"${name.ifBlank { "playlist" }}\" with ${ids.size} track${if (ids.size == 1) "" else "s"}"); clearSelection()
    }
    fun renamePlaylist(p: com.cratez.app.data.PlaylistEntity, name: String) = viewModelScope.launch { a.db.playlists().update(p.copy(name = name.trim().ifBlank { p.name }, updatedAt = System.currentTimeMillis())) }
    fun deletePlaylist(p: com.cratez.app.data.PlaylistEntity) = viewModelScope.launch { a.db.playlists().delete(p.id); toast("Deleted ${p.name}") }
    fun setPlaylistOrder(p: com.cratez.app.data.PlaylistEntity, ids: List<Long>) = viewModelScope.launch { a.db.playlists().update(p.withIds(ids)) }
    fun removeFromPlaylist(p: com.cratez.app.data.PlaylistEntity, id: Long) = setPlaylistOrder(p, p.ids - id)
    fun moveInPlaylist(p: com.cratez.app.data.PlaylistEntity, from: Int, to: Int) {
        val l = p.ids.toMutableList(); if (from !in l.indices || to !in l.indices) return
        val x = l.removeAt(from); l.add(to, x); setPlaylistOrder(p, l)
    }
    /** Re-order like a DJ set: compatible keys, close tempos, energy building. */
    fun harmonicOrder(p: com.cratez.app.data.PlaylistEntity) = viewModelScope.launch {
        val ts = playlistTracks(p)
        val order = com.cratez.core.music.MixOrder.order(ts.map { com.cratez.core.music.MixOrder.Item(it.id, it.bpm, it.musicalKey, it.energy) }, ts.firstOrNull()?.id)
        a.db.playlists().update(p.withIds(order)); toast("Ordered for smooth mixing")
    }
    fun playPlaylist(p: com.cratez.app.data.PlaylistEntity, start: Int = 0, shuffle: Boolean = false) {
        val ts = playlistTracks(p)
        if (ts.isEmpty()) { toast("This playlist is empty"); return }
        player.playList(ts, if (shuffle) (ts.indices).random() else start, shuffle)
    }
    /** Builds a harmonic-mix playlist from the current crate/filter. */
    fun playlistFromCrate() = viewModelScope.launch {
        val ts = visible.value
        if (ts.isEmpty()) { toast("Nothing in this crate"); return@launch }
        val order = com.cratez.core.music.MixOrder.order(ts.map { com.cratez.core.music.MixOrder.Item(it.id, it.bpm, it.musicalKey, it.energy) })
        val name = "${filter.value.crate} mix"
        createPlaylist(name, order); toast("Created \"$name\" (${order.size} tracks, harmonic order)")
    }
    fun playNext(ids: List<Long>) { player.playNext(tracks.value.filter { it.id in ids }); toast("Playing next"); clearSelection() }
    fun addToQueue(ids: List<Long>) { player.addToQueue(tracks.value.filter { it.id in ids }); toast("Added to queue"); clearSelection() }

    private val _filter = MutableStateFlow(Filter())
    val filter: StateFlow<Filter> = _filter

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun toast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    val visible: StateFlow<List<TrackEntity>> = combine(tracks, _filter) { list, f -> applyFilter(list, f) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Crate name → count. Genres are crates automatically. */
    val crates: StateFlow<List<Pair<String, Int>>> = tracks.map { list ->
        val out = ArrayList<Pair<String, Int>>()
        out += CRATE_ALL to list.size
        val rec = list.count { it.received }
        if (rec > 0) out += CRATE_RECEIVED to rec
        val review = list.count { it.status == Status.DONE && !it.genreLocked && it.genreConfidence < 0.5 }
        if (review > 0) out += CRATE_REVIEW to review
        list.filter { it.genre.isNotBlank() }.groupingBy { it.genre }.eachCount().entries.sortedByDescending { it.value }
            .forEach { out += it.key to it.value }
        out
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun applyFilter(list: List<TrackEntity>, f: Filter): List<TrackEntity> {
        val q = f.query.trim().lowercase()
        var r = list.asSequence()
        when (f.crate) {
            CRATE_ALL -> {}
            CRATE_RECEIVED -> r = r.filter { it.received }
            CRATE_REVIEW -> r = r.filter { it.status == Status.DONE && !it.genreLocked && it.genreConfidence < 0.5 }
            else -> r = r.filter { it.genre == f.crate }
        }
        if (q.isNotEmpty()) r = r.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.fileName.lowercase().contains(q) ||
                it.genre.lowercase().contains(q) || it.album.lowercase().contains(q) || it.label.lowercase().contains(q) ||
                (it.musicalKey >= 0 && MusicKey.camelot(it.musicalKey).equals(q, true))
        }
        if (f.bpmMin > 60f || f.bpmMax < 200f) r = r.filter { it.bpm <= 0 || it.bpm in f.bpmMin..f.bpmMax }
        if (f.minEnergy > 0) r = r.filter { it.energy >= f.minEnergy }
        val l = r.toList()
        return when (f.sort) {
            SortBy.ADDED -> l
            SortBy.TITLE -> l.sortedBy { it.displayTitle.lowercase() }
            SortBy.ARTIST -> l.sortedBy { it.displayArtist.lowercase() }
            SortBy.BPM -> l.sortedBy { if (it.bpm <= 0) 999.0 else it.bpm }
            SortBy.KEY -> l.sortedBy { if (it.musicalKey < 0) 99 else MusicKey.camelotNumber(it.musicalKey) * 2 + if (MusicKey.isMinor(it.musicalKey)) 0 else 1 }
            SortBy.ENERGY -> l.sortedByDescending { it.energy }
            SortBy.GENRE -> l.sortedBy { it.genre.ifBlank { "zzz" } }
        }
    }

    fun setQuery(q: String) { _filter.value = _filter.value.copy(query = q) }
    fun setCrate(c: String) { _filter.value = _filter.value.copy(crate = c) }
    fun setSort(s: SortBy) { _filter.value = _filter.value.copy(sort = s) }
    fun setBpm(min: Float, max: Float) { _filter.value = _filter.value.copy(bpmMin = min, bpmMax = max) }
    fun setMinEnergy(e: Int) { _filter.value = _filter.value.copy(minEnergy = e) }
    fun resetFilters() { _filter.value = Filter(crate = _filter.value.crate) }

    // ---- Selection ----
    fun toggleSelect(id: Long) { val s = _selection.value; _selection.value = if (id in s) s - id else s + id }
    fun selectAllVisible() { _selection.value = visible.value.map { it.id }.toSet() }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectedTracks(): List<TrackEntity> { val s = _selection.value; return tracks.value.filter { it.id in s } }

    // ---- Import ----
    fun addFiles(uris: List<Uri>) = viewModelScope.launch {
        val n = a.importer.addFiles(uris); afterImport(n)
    }
    fun addTree(uri: Uri) = viewModelScope.launch {
        toast("Scanning folder…")
        val n = a.importer.addTree(uri); afterImport(n)
    }
    fun importShared(uris: List<Uri>) = viewModelScope.launch {
        val n = a.importer.importShared(uris); afterImport(n)
    }
    private fun afterImport(n: Int) {
        toast(if (n == 0) "No new tracks found" else "Added $n track${if (n == 1) "" else "s"} · analyzing")
        if (n > 0) engine.kick()
    }

    // ---- Track edits ----
    fun setGenre(t: TrackEntity, genre: String) = viewModelScope.launch {
        var updated = t.copy(genre = genre, genreLocked = true, genreTrusted = true, genreConfidence = 1.0)
        // Smart tempo fix: e.g. picking Drum & Bass on a track read as 116.7 BPM → 175.
        if (!t.bpmLocked) com.cratez.core.genre.Genre.fromLabel(genre)?.let { g ->
            com.cratez.core.genre.EdmClassifier.fixTempoForGenre(g, t.bpm, parseAlts(t.bpmAlts))?.let { nb ->
                toast("BPM ${com.cratez.core.genre.EdmClassifier.fmtBpm(t.bpm)} → ${com.cratez.core.genre.EdmClassifier.fmtBpm(nb)} to fit $genre")
                updated = updated.copy(bpm = nb)
            }
        }
        a.db.tracks().update(updated)
        if (settings.value.writeTags) engine.writeTagsNow(updated)?.let { toast(it) }
    }
    fun scaleBpm(t: TrackEntity, factor: Double) = viewModelScope.launch {
        val nb = (t.bpm * factor * 10).let { Math.round(it) / 10.0 }
        if (nb < 40 || nb > 260) return@launch
        setBpm(t, nb)
    }

    /** Manual BPM (typed, tapped or picked). Locks it so re-analysis won't change it. */
    fun setBpm(t: TrackEntity, bpm: Double) = viewModelScope.launch {
        if (bpm < 40 || bpm > 260) { toast("BPM must be between 40 and 260"); return@launch }
        val cur = a.db.tracks().get(t.id) ?: t
        val nb = Math.round(bpm * 100) / 100.0
        val fv = com.cratez.core.genre.LibraryModel.decode(cur.featVec)?.let { com.cratez.core.genre.LibraryModel.encode(com.cratez.core.genre.LibraryModel.withBpm(it, nb)) } ?: cur.featVec
        val updated = cur.copy(bpm = nb, bpmLocked = true, featVec = fv)
        a.db.tracks().update(updated)
        if (settings.value.writeTags) engine.writeTagsNow(updated)?.let { toast(it) }
    }

    fun unlockBpm(t: TrackEntity) = viewModelScope.launch {
        a.db.tracks().update(t.copy(bpmLocked = false)); reanalyze(listOf(t.id))
    }

    suspend fun analyzeSection(t: TrackEntity, startMs: Long, lenMs: Long) = runCatching { engine.analyzeSection(t, startMs, lenMs) }

    fun applySection(t: TrackEntity, bpm: Double?, key: Int?) = viewModelScope.launch {
        var u = a.db.tracks().get(t.id) ?: t
        if (bpm != null) u = u.copy(bpm = Math.round(bpm * 100) / 100.0, bpmLocked = true)
        if (key != null && key >= 0) u = u.copy(musicalKey = key, keySource = "Section scan")
        a.db.tracks().update(u)
        if (settings.value.writeTags) engine.writeTagsNow(u)?.let { toast(it) }
        toast("Updated ✓")
    }

    fun parseAlts(s: String): List<Pair<Double, Double>> = s.split('|').mapNotNull { p ->
        val parts = p.split(':'); val b = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        b to (parts.getOrNull(1)?.toDoubleOrNull() ?: 0.5)
    }
    fun writeTags(t: TrackEntity) = viewModelScope.launch { toast(engine.writeTagsNow(t) ?: "Tags written ✓") }
    fun reanalyze(ids: Collection<Long>) = viewModelScope.launch { a.db.tracks().requeue(ids.toList()); engine.kick(); toast("Re-scanning ${ids.size} track${if (ids.size == 1) "" else "s"}") }
    fun reanalyzeAll() = reanalyze(tracks.value.map { it.id })
    fun resortFromLibrary() = viewModelScope.launch {
        toast("Re-sorting genres with what your library taught Cratez…")
        val n = engine.refineFromLibrary()
        toast(if (n == 0) "Nothing to change — fix a few genres in Review to teach it more" else "Updated $n genre${if (n == 1) "" else "s"}")
    }
    fun remove(ids: Collection<Long>) = viewModelScope.launch {
        ids.forEach { File(engine.artFile(it).path).delete() }
        a.db.tracks().delete(ids.toList()); clearSelection(); toast("Removed from Cratez (files untouched)")
    }
    fun clearLibrary() = viewModelScope.launch { a.db.tracks().deleteAll(); toast("Library cleared (files untouched)") }

    fun harmonicMatches(t: TrackEntity): List<Match> {
        if (t.musicalKey < 0) return emptyList()
        return tracks.value.asSequence().filter { it.id != t.id && it.musicalKey >= 0 && it.bpm > 0 }
            .map { Match(it, MusicKey.compatibility(t.musicalKey, it.musicalKey), if (t.bpm > 0) bpmDistance(t.bpm, it.bpm) else 0.0) }
            .filter { it.compat != MusicKey.Compat.NONE && (t.bpm <= 0 || it.bpmDiff <= 0.06) }
            .sortedWith(compareBy<Match> { it.compat.ordinal }.thenBy { it.bpmDiff }).take(25).toList()
    }
    private fun bpmDistance(a: Double, b: Double) = listOf(b, b * 2, b / 2).minOf { abs(it - a) / a }

    // ---- Playback ----
    fun play(list: List<TrackEntity>, index: Int) = player.playList(list, index)

    // ---- Settings ----
    fun setName(v: String) = viewModelScope.launch { a.settings.setName(v) }
    fun setWriteTags(v: Boolean) = viewModelScope.launch { a.settings.setWriteTags(v) }
    fun setCamelot(v: Boolean) = viewModelScope.launch { a.settings.setCamelot(v) }
    fun setOnline(v: Boolean) = viewModelScope.launch { a.settings.setOnline(v) }
    fun setReplaceArt(v: Boolean) = viewModelScope.launch { a.settings.setReplaceArt(v) }
    fun setBeamVisible(v: Boolean) = viewModelScope.launch {
        a.settings.setBeamVisible(v); if (v) beam.startAdvertising() else beam.stopAdvertising()
    }

    // ---- Online sharing (friend codes over the internet) ----
    fun sendOnline(code: String, name: String, ids: List<Long>) = viewModelScope.launch {
        val list = tracks.value.filter { it.id in ids }
        toast("Uploading ${list.size} track${if (list.size == 1) "" else "s"} to $name…")
        clearSelection()
        val err = cloud.send(code, name, list)
        toast(err ?: "Sent to $name ✓ — it lands in their Cratez")
    }
    fun checkInbox(quiet: Boolean = false) = viewModelScope.launch {
        val (n, names) = cloud.checkInbox()
        if (n > 0) toast("${names.joinToString()} sent you $n track${if (n == 1) "" else "s"} 🎧")
        else if (!quiet) toast(cloud.state.value.lastError ?: "No new tracks")
    }
    suspend fun lookupFriend(code: String): String? = runCatching { cloud.lookup(code) }.getOrNull()
    fun setRelayUrl(v: String) = viewModelScope.launch { a.settings.setRelayUrl(v); cloud.register() }
    fun setOnlineSharing(v: Boolean) = viewModelScope.launch { a.settings.setOnlineSharing(v); if (v) cloud.register() }

    // ---- Friends ----
    fun addFriend(input: String, name: String?): Boolean {
        val (code, qrName) = Settings.parseFriendCode(input) ?: return false
        if (code == settings.value.myCode) { toast("That's your own code"); return true }
        viewModelScope.launch { a.db.friends().upsert(FriendEntity(code, (name ?: qrName)?.ifBlank { null } ?: "Friend ${code.takeLast(4)}")) }
        toast("Friend added")
        return true
    }
    fun removeFriend(code: String) = viewModelScope.launch { a.db.friends().delete(code) }

    // ---- Dupes ----
    fun scanDupes() = viewModelScope.launch { dupes.scan() }
    fun afterDupesDeleted(ids: Collection<Long>) = viewModelScope.launch {
        val gone = dupes.deletedSize(ids)
        dupes.markDeleted(ids)
        // Drop library entries pointing at the deleted files.
        val lib = a.db.tracks().all()
        val dead = lib.filter { t -> gone.any { (name, size) -> name == t.fileName && (size == t.sizeBytes || t.sizeBytes == 0L) } }.map { it.id }
        if (dead.isNotEmpty()) a.db.tracks().delete(dead)
    }
}
