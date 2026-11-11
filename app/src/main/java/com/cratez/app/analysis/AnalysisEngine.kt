package com.cratez.app.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cratez.app.data.CratezDb
import com.cratez.app.data.Settings
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import com.cratez.core.dsp.AudioAnalyzer
import com.cratez.core.dsp.AudioFeatures
import com.cratez.core.dsp.QualityAnalyzer
import com.cratez.core.genre.Genre
import com.cratez.core.genre.GenreFusion
import com.cratez.core.genre.GenreNormalizer
import com.cratez.core.genre.GenreVote
import com.cratez.core.genre.LibraryModel
import com.cratez.core.music.MusicKey
import com.cratez.core.tags.TagData
import com.cratez.core.text.TrackText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs

data class EngineState(val running: Boolean = false, val current: List<String> = emptyList(), val doneThisRun: Int = 0)

/** Background queue: decode → measure → look up → decide genre → fetch art → write tags. */
class AnalysisEngine(private val ctx: Context, private val db: CratezDb, private val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lookup = Lookup()
    private val claim = Mutex()
    private var workers: List<Job> = emptyList()
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    private val artDir = File(ctx.filesDir, "art").apply { mkdirs() }

    fun artFile(id: Long) = File(artDir, "$id.jpg")

    fun kick() {
        if (workers.any { it.isActive }) return
        _state.value = EngineState(running = true)
        workers = List(2) { scope.launch { workLoop() } }
        scope.launch {
            workers.forEach { it.join() }
            runCatching { refineFromLibrary() }
            _state.value = _state.value.copy(running = false, current = emptyList())
        }
    }

    suspend fun resumeInterrupted() { db.tracks().resetInterrupted(); kick() }

    private suspend fun workLoop() {
        while (scope.isActive) {
            val t = claim.withLock {
                db.tracks().nextPending()?.also { db.tracks().setStatus(it.id, Status.ANALYZING) }
            } ?: return
            val label = t.displayTitle
            _state.value = _state.value.copy(current = _state.value.current + label)
            try {
                process(t)
            } catch (e: Throwable) {
                Log.e("Cratez", "Analysis failed for ${t.fileName}", e)
                db.tracks().get(t.id)?.let { db.tracks().update(it.copy(status = Status.ERROR, error = e.message ?: e.javaClass.simpleName)) }
            }
            _state.value = _state.value.copy(current = _state.value.current - label, doneThisRun = _state.value.doneThisRun + 1)
        }
    }

    private suspend fun process(t0: TrackEntity) = coroutineScope {
        val s = settings.current()
        val uri = Uri.parse(t0.uri)
        val info = TagFile.readInfo(ctx, uri, t0.fileName)
        val tags = info.tags
        val parsed = TrackText.parseFileName(t0.fileName)
        val artist = tags.artist ?: t0.artist.ifBlank { null } ?: parsed.artist ?: ""
        val title = tags.title ?: t0.title.ifBlank { null } ?: parsed.title

        // Kick off online lookups while the audio decodes.
        val online = if (s.onlineLookup && artist.isNotBlank() && title.isNotBlank()) listOf(
            async { runCatching { lookup.itunes(artist, title, info.durationMs) }.getOrNull() },
            async { runCatching { lookup.deezer(artist, title, info.durationMs) }.getOrNull() },
            async { runCatching { lookup.musicBrainz(artist, title, info.durationMs) }.getOrNull() },
        ) else emptyList()

        val decoded = runCatching { Decoder.decode(ctx, uri, info.durationMs) }.onFailure { Log.w("Cratez", "decode", it) }.getOrNull()
        val feats: AudioFeatures? = decoded?.let { if (it.samples.size > it.rate * 8) AudioAnalyzer.analyze(it.samples, it.rate) else null }
        val lossless = info.mime.contains("flac") || info.mime.contains("wav") || info.mime.contains("aiff") ||
            t0.fileName.lowercase().let { it.endsWith(".flac") || it.endsWith(".wav") || it.endsWith(".aif") || it.endsWith(".aiff") }
        val quality = decoded?.spectrum?.let { QualityAnalyzer.judge(it, decoded.nativeRate, info.bitrateKbps, lossless) }
        val hits = online.mapNotNull { it.await() }

        // ---- Genre ----
        val votes = ArrayList<GenreVote>()
        tags.genre?.let { g ->
            GenreNormalizer.normalize(g)?.let { n ->
                // Downloaded bootlegs often carry junk non-dance tags ("Blues" on a trap edit).
                val w = when { n.edm && !GenreNormalizer.isGenericElectronic(n) -> 3.0; n.edm -> 1.0; else -> 0.8 }
                votes += GenreVote("File tag", g, n, w)
            }
        }
        // Folder names ("Dubstep", "Free Breaks Collection") and file names ("… DNB Remix").
        t0.folder.takeIf { it.isNotBlank() && it != "Picked" }?.let { f ->
            GenreNormalizer.fromHint(f, isFolder = true)?.let { votes += GenreVote("Folder", f, it, 2.5) }
        }
        GenreNormalizer.fromHint(t0.fileName.substringBeforeLast('.'))?.let { votes += GenreVote("File name", t0.fileName, it, 1.5) }
        for (h in hits) {
            val base = when (h.source) { "MusicBrainz" -> 2.5; "iTunes" -> 1.5; else -> 1.0 }
            for ((g, w) in h.genres) GenreNormalizer.normalize(g)?.let { votes += GenreVote(h.source, g, it, base * w) }
        }
        val cur0 = db.tracks().get(t0.id)
        // A genre the user picked counts as a strong vote for tempo correction.
        if (cur0 != null && cur0.genreLocked) Genre.fromLabel(cur0.genre)?.let { votes += GenreVote("You", it.label, it, 4.0) }
        // First pass fixes the tempo; then ask the user's own library what this sounds like.
        val (_, featsTempo) = GenreFusion.decideWithTempoFix(votes, feats)
        val vec = featsTempo?.let { LibraryModel.vector(if (cur0?.bpmLocked == true && cur0.bpm > 0) it.copy(bpm = cur0.bpm) else it) }
        if (vec != null) LibraryModel.vote(vec, references(t0.id))?.let { v ->
            votes += GenreVote("Your library", "${v.neighbours} similar tracks are ${v.genre.label}", v.genre, v.weight)
        }
        val (verdict, featsFixed) = GenreFusion.decideWithTempoFix(votes, feats)
        val trusted = (cur0?.genreLocked == true) ||
            votes.any { it.source != "Your library" && it.weight >= 2.0 && it.genre == verdict.genre }

        // ---- BPM & key: trust precise DJ-software tags when they agree with what we hear ----
        var bpm = featsFixed?.bpm ?: 0.0
        tags.bpm?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 50.0..250.0 }?.let { tb ->
            if (bpm <= 0 || MusicKey.tempoMatch(bpm, tb, 0.03)) bpm = AudioAnalyzer.roundBpm(tb)
        }
        if (cur0 != null && cur0.bpmLocked && cur0.bpm > 0) bpm = cur0.bpm
        val alts = altsString(featsFixed?.bpmCandidates.orEmpty(), bpm)
        var key = feats?.key ?: -1
        var keySource = if (key >= 0) "Audio" else ""
        MusicKey.parse(tags.key)?.let { key = it; keySource = "File tag" }

        // ---- Metadata fill-ins (never overwrite what the file already says) ----
        val bestHit = hits.maxByOrNull { it.score }
        fun pick(own: String?, sel: (OnlineHit) -> String?) = own ?: hits.sortedByDescending { it.score }.firstNotNullOfOrNull(sel)
        val album = pick(tags.album) { it.album }
        val year = pick(tags.year) { it.year }
        val label = pick(tags.label) { it.label }
        val isrc = pick(tags.isrc) { it.isrc }
        val finalArtist = artist.ifBlank { bestHit?.artist ?: "" }

        // ---- Album art ----
        var artBytesForFile: ByteArray? = null
        val existingArt = tags.art?.takeIf { it.size > 1000 }
        val artFile = artFile(t0.id)
        val existingSide = existingArt?.let { TagFile.imageSide(it) } ?: 0
        if (existingArt != null && (existingSide >= 500 || !s.replaceLowResArt || bestHit == null)) {
            TagFile.scaleJpeg(existingArt, 1000)?.let { artFile.writeBytes(it) }
        } else if (s.onlineLookup) {
            val urls = hits.sortedBy { when (it.source) { "Deezer" -> 0; "iTunes" -> 1; else -> 2 } }.mapNotNull { it.artUrl }
            for (u in urls) {
                val bytes = lookup.download(u) ?: continue
                val side = TagFile.imageSide(bytes)
                if (side < 200 || side <= existingSide) continue
                TagFile.scaleJpeg(bytes, 1000)?.let { artFile.writeBytes(it) }
                artBytesForFile = TagFile.scaleJpeg(bytes, 600, 88)
                break
            }
            if (!artFile.exists() && existingArt != null) TagFile.scaleJpeg(existingArt, 1000)?.let { artFile.writeBytes(it) }
        }

        val cur = db.tracks().get(t0.id) ?: return@coroutineScope
        val genreLabel = if (cur.genreLocked) cur.genre else verdict.genre.label
        val energy = feats?.energy ?: 0
        var updated = cur.copy(
            title = title, artist = finalArtist, album = album ?: "", year = year ?: "", label = label ?: "", isrc = isrc ?: "",
            durationMs = decoded?.durationMs ?: info.durationMs, bitrateKbps = info.bitrateKbps,
            sampleRate = if (info.sampleRate > 0) info.sampleRate else decoded?.nativeRate ?: 0, mime = info.mime, lossless = lossless,
            genre = genreLabel,
            genreConfidence = if (cur.genreLocked) 1.0 else verdict.confidence,
            genreReasons = verdict.reasons.joinToString("\n"),
            genreAlternatives = verdict.alternatives.joinToString("|") { it.label },
            bpm = bpm, bpmAlts = alts, featVec = vec?.let { LibraryModel.encode(LibraryModel.withBpm(it, bpm)) } ?: "",
            genreTrusted = trusted, musicalKey = key, keySource = keySource, energy = energy, loudnessDb = feats?.loudnessDb ?: 0.0,
            qualityLabel = quality?.label ?: (if (info.bitrateKbps > 0) "${info.bitrateKbps} kbps" else ""),
            cutoffHz = quality?.cutoffHz ?: 0, qualitySuspicious = quality?.suspicious ?: false,
            artPath = if (artFile.exists()) artFile.absolutePath else null,
            waveform = decoded?.waveform, status = Status.DONE, error = if (decoded == null) "Couldn't decode audio" else null,
            analyzedAt = System.currentTimeMillis(),
        )

        // ---- Write tags back into the file ----
        if (s.writeTags) {
            val keyText = if (key >= 0) (if (s.camelotKeys) MusicKey.camelot(key) else MusicKey.shortName(key)) else null
            val upd = TagData(
                title = if (tags.title == null && title.isNotBlank()) title else null,
                artist = if (tags.artist == null && finalArtist.isNotBlank()) finalArtist else null,
                album = if (tags.album == null) album else null,
                year = if (tags.year == null) year else null,
                label = if (tags.label == null) label else null,
                isrc = if (tags.isrc == null) isrc else null,
                genre = genreLabel.ifBlank { null },
                bpm = if (bpm > 0) EdmBpm.tagText(bpm) else null,
                key = keyText,
                energy = if (energy > 0) energy.toString() else null,
                art = artBytesForFile, artMime = if (artBytesForFile != null) "image/jpeg" else null,
            )
            val err = TagFile.write(ctx, uri, t0.fileName, info.mime, upd)
            updated = updated.copy(tagsWritten = err == null, tagNote = err ?: "Tags written to file")
        } else updated = updated.copy(tagsWritten = false, tagNote = "Tag writing is off")
        db.tracks().update(updated)
    }

    /** Tracks with a trusted EDM genre, as (fingerprint, genre). */
    private suspend fun references(excludeId: Long): List<Pair<FloatArray, Genre>> =
        db.tracks().all().mapNotNull { t ->
            if (t.id == excludeId || !t.genreTrusted) return@mapNotNull null
            val g = Genre.fromLabel(t.genre)?.takeIf { it.edm && !GenreNormalizer.isGenericElectronic(it) } ?: return@mapNotNull null
            LibraryModel.decode(t.featVec)?.let { it to g }
        }

    /**
     * After a scan, re-check every track whose genre came from audio alone against the library's
     * trusted tracks. No re-decoding, so it's quick. Returns how many genres changed.
     */
    suspend fun refineFromLibrary(): Int = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val all = db.tracks().all()
        val refs = all.mapNotNull { t ->
            if (!t.genreTrusted) return@mapNotNull null
            val g = Genre.fromLabel(t.genre)?.takeIf { it.edm && !GenreNormalizer.isGenericElectronic(it) } ?: return@mapNotNull null
            LibraryModel.decode(t.featVec)?.let { Triple(t.id, it, g) }
        }
        if (refs.size < 5) return@withContext 0
        val writeTags = settings.current().writeTags
        var changed = 0
        for (t in all) {
            if (t.genreTrusted || t.genreLocked || t.status != Status.DONE) continue
            val v = LibraryModel.decode(t.featVec) ?: continue
            val vote = LibraryModel.vote(v, refs.filter { it.first != t.id }.map { it.second to it.third }) ?: continue
            if (vote.share < 0.55 || vote.neighbours < 4 || vote.genre.label == t.genre) continue
            val tempoOk = com.cratez.core.genre.EdmClassifier.tempoPlausibility(vote.genre, t.bpm) >= 0.3
            if (!tempoOk) continue
            val reason = "Your library: ${vote.neighbours} similar tracks are ${vote.genre.label}"
            val u = t.copy(genre = vote.genre.label, genreConfidence = (0.45 + vote.share * 0.45).coerceAtMost(0.9),
                genreReasons = (t.genreReasons.lines().filterNot { it.startsWith("Your library") } + reason).joinToString("\n"))
            db.tracks().update(u)
            if (writeTags) writeTagsNow(u)
            changed++
        }
        changed
    }

    private fun altsString(c: List<Pair<Double, Double>>, chosen: Double): String {
        val list = c.toMutableList()
        if (chosen > 0 && list.none { kotlin.math.abs(it.first - chosen) / chosen < 0.015 }) list.add(0, chosen to 1.0)
        return list.joinToString("|") { "${it.first}:${"%.2f".format(java.util.Locale.US, it.second)}" }
    }

    data class SectionResult(val bpm: Double, val key: Int, val keyConfidence: Double, val candidates: List<Pair<Double, Double>>, val genreHint: Double?)

    /** Analyzes just part of a track (BPM + key), e.g. a clean drop picked on the waveform. */
    suspend fun analyzeSection(t: TrackEntity, startMs: Long, lenMs: Long): SectionResult = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val (samples, rate) = Decoder.decodeRange(ctx, Uri.parse(t.uri), startMs, lenMs)
        require(samples.size > rate * 6) { "Section too short — pick at least 8 seconds" }
        val f = AudioAnalyzer.analyze(samples, rate)
        val g = Genre.fromLabel(t.genre)
        val fixed = g?.let { com.cratez.core.genre.EdmClassifier.fixTempoForGenre(it, f.bpm, f.bpmCandidates) }
        SectionResult(fixed ?: f.bpm, f.key, f.keyConfidence, f.bpmCandidates, if (fixed != null) f.bpm else null)
    }

    /** Re-writes tags for a track after the user edits genre/BPM. */
    suspend fun writeTagsNow(t: TrackEntity): String? {
        val s = settings.current()
        val keyText = if (t.musicalKey >= 0) (if (s.camelotKeys) MusicKey.camelot(t.musicalKey) else MusicKey.shortName(t.musicalKey)) else null
        val err = TagFile.write(
            ctx, Uri.parse(t.uri), t.fileName, t.mime,
            TagData(genre = t.genre.ifBlank { null }, bpm = if (t.bpm > 0) EdmBpm.tagText(t.bpm) else null, key = keyText,
                energy = if (t.energy > 0) t.energy.toString() else null),
        )
        db.tracks().update(t.copy(tagsWritten = err == null, tagNote = err ?: "Tags written to file"))
        return err
    }
}

object EdmBpm {
    fun tagText(bpm: Double): String = if (abs(bpm - Math.round(bpm)) < 0.05) Math.round(bpm).toString() else String.format(java.util.Locale.US, "%.1f", bpm)
}

fun Genre.Companion.labels() = Genre.entries.map { it.label }
