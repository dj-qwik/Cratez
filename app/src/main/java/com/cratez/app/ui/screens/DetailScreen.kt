package com.cratez.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.Art
import com.cratez.app.ui.components.EnergyBar
import com.cratez.app.ui.components.KeyBadge
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.Panel
import com.cratez.app.ui.components.SectionLabel
import com.cratez.app.ui.components.StatTile
import com.cratez.app.ui.components.Tag
import com.cratez.app.ui.components.Waveform
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.components.cyberBackdrop
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.core.genre.EdmClassifier
import com.cratez.core.genre.Genre
import com.cratez.core.music.MusicKey

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: AppViewModel, id: Long, nav: Nav) {
    val all by vm.tracks.collectAsState()
    val settings by vm.settings.collectAsState()
    val ui by vm.player.ui.collectAsState()
    val t = all.firstOrNull { it.id == id }
    var genrePicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().cyberBackdrop().clickable(remember { MutableInteractionSource() }, null) {}) {
        if (t == null) { androidx.compose.runtime.LaunchedEffect(Unit) { nav.pop() }; return@Box }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.Electric) }
                Text("TRACK FILE", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = C.TextDim, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.push(Overlay.Share(listOf(t.id))) }) { Icon(Icons.Default.Share, "Share", tint = C.Electric) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(t.artPath, Modifier.size(132.dp).shadow(24.dp, cut(14.dp), ambientColor = C.Electric, spotColor = C.Electric), cutSize = 14.dp)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(t.displayTitle, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = C.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(t.displayArtist, fontFamily = F.Body, fontSize = 17.sp, color = C.Electric, maxLines = 2)
                    val meta = listOf(t.album, t.year, t.label).filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, fontFamily = F.Body, fontSize = 14.sp, color = C.TextDim, maxLines = 2)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(if (ui.currentId == t.id) "Playing" else "Play", icon = Icons.Default.PlayArrow, filled = true, modifier = Modifier.weight(1f)) {
                    val list = vm.visible.value.ifEmpty { listOf(t) }
                    val idx = list.indexOfFirst { it.id == t.id }
                    if (idx >= 0) vm.play(list, idx) else vm.play(listOf(t), 0)
                    nav.push(Overlay.NowPlaying)
                }
                NeonButton("Rescan", icon = Icons.Default.Refresh, modifier = Modifier.weight(1f)) { vm.reanalyze(listOf(t.id)) }
            }
            if (t.status == Status.PENDING || t.status == Status.ANALYZING) Text("Analyzing… results appear here in a few seconds.", color = C.Electric, fontFamily = F.Body)
            if (t.status == Status.ERROR) Text("Analysis problem: ${t.error}", color = C.Hot, fontFamily = F.Body)

            // ---- Stats ----
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("BPM", Modifier.weight(1f)) {
                    Text(if (t.bpm > 0) EdmClassifier.fmtBpm(t.bpm) else "--", fontFamily = F.Mono, fontSize = 30.sp, color = C.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Tag("½×", C.TextDim, onClick = { vm.scaleBpm(t, 0.5) }); Tag("2×", C.TextDim, onClick = { vm.scaleBpm(t, 2.0) })
                    }
                }
                StatTile("Key", Modifier.weight(1f)) {
                    KeyBadge(t.musicalKey, settings.camelotKeys, big = true)
                    Text(if (t.musicalKey >= 0) "${MusicKey.longName(t.musicalKey)} · ${t.keySource}" else "", fontFamily = F.Body, fontSize = 12.sp, color = C.TextDim, modifier = Modifier.padding(top = 4.dp))
                }
                StatTile("Energy", Modifier.weight(1f)) {
                    Text(if (t.energy > 0) "${t.energy}" else "--", fontFamily = F.Mono, fontSize = 30.sp, color = C.Text)
                    EnergyBar(t.energy, Modifier.padding(top = 4.dp), segH = 9.dp)
                }
            }

            // ---- Genre ----
            Panel(Modifier.fillMaxWidth().clickable { genrePicker = true }, edge = if (t.genreConfidence < 0.5 && !t.genreLocked) C.Warn else C.ElectricDim) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GENRE", fontFamily = F.Display, fontSize = 9.sp, letterSpacing = 1.5.sp, color = C.TextDim, modifier = Modifier.weight(1f))
                        Text(if (t.genreLocked) "SET BY YOU" else "${(t.genreConfidence * 100).toInt()}% SURE", fontFamily = F.Mono, fontSize = 11.sp,
                            color = if (t.genreConfidence < 0.5 && !t.genreLocked) C.Warn else C.Electric)
                        Icon(Icons.Default.Edit, "Change genre", tint = C.TextDim, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                    }
                    Text(t.genre.ifBlank { "—" }, fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = C.Text, modifier = Modifier.padding(vertical = 6.dp))
                    if (t.genreReasons.isNotBlank()) t.genreReasons.lines().forEach {
                        Text("› $it", fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim)
                    }
                }
            }

            // ---- Waveform ----
            Waveform(t.waveform, if (ui.currentId == t.id && ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f,
                Modifier.fillMaxWidth().height(64.dp),
                onSeek = if (ui.currentId == t.id) { f -> vm.player.seekTo((f * ui.durationMs).toLong()) } else null)

            // ---- Quality + file ----
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TRUE QUALITY", fontFamily = F.Display, fontSize = 9.sp, letterSpacing = 1.5.sp, color = C.TextDim, modifier = Modifier.weight(1f))
                        if (t.qualitySuspicious) Icon(Icons.Default.Warning, null, tint = C.Warn, modifier = Modifier.size(16.dp))
                    }
                    Text(t.qualityLabel.ifBlank { "—" }, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (t.qualitySuspicious) C.Warn else C.Ok)
                    if (t.cutoffHz > 0) Text("Frequency cutoff ${"%.1f".format(t.cutoffHz / 1000.0)} kHz" +
                        (if (t.qualitySuspicious) " — likely upscaled from a lower bitrate. Not club-ready." else ""), fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim)
                    Text(listOfNotNull(
                        if (t.bitrateKbps > 0) "${t.bitrateKbps} kbps" else null,
                        if (t.sampleRate > 0) "${t.sampleRate / 1000.0} kHz" else null,
                        if (t.durationMs > 0) "%d:%02d".format(t.durationMs / 60000, (t.durationMs / 1000) % 60) else null,
                        "%.1f MB".format(t.sizeBytes / 1048576.0),
                    ).joinToString(" · "), fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
                    Text(t.fileName, fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.tagNote.ifBlank { "Tags not written yet" }, fontFamily = F.Body, fontSize = 13.sp, color = if (t.tagsWritten) C.Ok else C.TextDim, modifier = Modifier.weight(1f))
                        if (!t.tagsWritten && t.status == Status.DONE) Tag("Write tags", C.Electric, onClick = { vm.writeTags(t) })
                    }
                }
            }

            // ---- Harmonic matches ----
            val matches = remember(t.id, t.musicalKey, t.bpm, all.size) { vm.harmonicMatches(t) }
            SectionLabel("Mixes well with (${matches.size})")
            if (matches.isEmpty()) Text(if (t.musicalKey < 0) "Needs a key first." else "No harmonic + tempo matches in your library yet.", color = C.TextDim, fontFamily = F.Body)
            matches.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TrackRow(m.track, settings.camelotKeys, onClick = { nav.push(Overlay.Detail(m.track.id)) }, onPlay = { vm.play(listOf(m.track), 0) })
                    }
                }
                Text("${m.compat.label} · ${if (m.bpmDiff < 0.005) "same tempo" else "±%.1f%% tempo".format(m.bpmDiff * 100)}",
                    fontFamily = F.Mono, fontSize = 11.sp, color = if (m.compat == MusicKey.Compat.ENERGY) C.Warn else C.Electric, modifier = Modifier.padding(start = 8.dp))
            }
            Box(Modifier.height(40.dp))
        }
        if (genrePicker) GenrePicker(t, { genrePicker = false }) { g -> vm.setGenre(t, g); genrePicker = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenrePicker(t: TrackEntity, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val alts = t.genreAlternatives.split('|').filter { it.isNotBlank() }
    Box(Modifier.fillMaxSize().background(Color(0xDD000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.Center).padding(16.dp).background(C.Panel, cut(16.dp)).border(1.dp, C.Electric, cut(16.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SET GENRE", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric)
            if (alts.isNotEmpty()) {
                Text("Close calls", color = C.TextDim, fontFamily = F.Body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    alts.forEach { GenreChip(it, C.Warn) { onPick(it) } }
                }
            }
            Text("Electronic", color = C.TextDim, fontFamily = F.Body)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Genre.entries.filter { it.edm }.forEach { g -> GenreChip(g.label, if (g.label == t.genre) C.Hot else C.Electric) { onPick(g.label) } }
            }
            Text("Everything else", color = C.TextDim, fontFamily = F.Body)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Genre.entries.filter { !it.edm }.forEach { g -> GenreChip(g.label, if (g.label == t.genre) C.Hot else C.TextDim) { onPick(g.label) } }
            }
            NeonButton("Close", modifier = Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}

@Composable
private fun GenreChip(label: String, color: Color, onClick: () -> Unit) {
    Text(label, fontFamily = F.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.08f), cut(6.dp)).border(1.dp, color.copy(alpha = 0.6f), cut(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp))
}
