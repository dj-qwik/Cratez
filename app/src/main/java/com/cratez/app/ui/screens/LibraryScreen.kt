package com.cratez.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.data.Status
import com.cratez.app.data.TrackEntity
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.SortBy
import com.cratez.app.ui.components.Art
import com.cratez.app.ui.components.EnergyBar
import com.cratez.app.ui.components.KeyBadge
import com.cratez.app.ui.components.Logo
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.Panel
import com.cratez.app.ui.components.ProgressLine
import com.cratez.app.ui.components.Tag
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.core.genre.EdmClassifier

@Composable
fun LibraryScreen(vm: AppViewModel, nav: Nav) {
    val visible by vm.visible.collectAsState()
    val all by vm.tracks.collectAsState()
    val crates by vm.crates.collectAsState()
    val filter by vm.filter.collectAsState()
    val selection by vm.selection.collectAsState()
    val engine by vm.engine.state.collectAsState()
    val queued by vm.queued.collectAsState()
    val settings by vm.settings.collectAsState()
    val ui by vm.player.ui.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.addFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.addTree(uri)
    }
    val selecting = selection.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        // ---- Header ----
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Cancel", tint = C.Text) }
                Text("${selection.size} SELECTED", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.selectAllVisible() }) { Icon(Icons.Default.SelectAll, "Select all", tint = C.Text) }
            } else {
                Column(Modifier.weight(1f)) {
                    Logo(size = 24)
                    Text("${all.size} TRACKS · ${crates.size - 1} CRATES", fontFamily = F.Mono, fontSize = 11.sp, color = C.TextDim)
                }
                NeonButton("Add", icon = Icons.Default.Add, filled = true) { showAdd = true }
            }
        }
        // ---- Analysis progress ----
        if (engine.running || queued > 0) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScanDot()
                    Text("ANALYZING · $queued LEFT", fontFamily = F.Display, fontSize = 10.sp, letterSpacing = 1.5.sp, color = C.Electric, modifier = Modifier.padding(start = 8.dp))
                    Text(engine.current.firstOrNull() ?: "", fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp).weight(1f))
                }
                ProgressLine(null, Modifier.padding(top = 6.dp))
            }
        }

        if (all.isEmpty()) { EmptyLibrary({ pickFiles.launch(arrayOf("audio/*")) }, { pickFolder.launch(null) }); if (showAdd) AddSheet({ showAdd = false }, { pickFiles.launch(arrayOf("audio/*")) }, { pickFolder.launch(null) }); return@Column }

        // ---- Search + sort ----
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).background(C.Panel, cut(8.dp)).border(1.dp, C.Line, cut(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = C.TextDim, modifier = Modifier.size(18.dp))
                Box(Modifier.padding(start = 8.dp).weight(1f)) {
                    if (filter.query.isEmpty()) Text("Search title, artist, genre, 8A…", color = C.TextDim, fontFamily = F.Body, fontSize = 15.sp)
                    BasicTextField(filter.query, vm::setQuery, singleLine = true, cursorBrush = SolidColor(C.Electric),
                        textStyle = TextStyle(color = C.Text, fontFamily = F.Body, fontSize = 15.sp, fontWeight = FontWeight.Medium), modifier = Modifier.fillMaxWidth())
                }
                if (filter.query.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = C.TextDim, modifier = Modifier.size(18.dp).clickable { vm.setQuery("") })
            }
            Box {
                IconButton(onClick = { sortOpen = true }) {
                    Text("⇅", color = C.Electric, fontSize = 22.sp)
                }
                DropdownMenu(sortOpen, { sortOpen = false }, modifier = Modifier.background(C.PanelHi)) {
                    SortBy.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label, color = if (s == filter.sort) C.Electric else C.Text, fontFamily = F.Body, fontSize = 16.sp) },
                            onClick = { vm.setSort(s); sortOpen = false })
                    }
                }
            }
            IconButton(onClick = { showFilter = !showFilter }) {
                Icon(Icons.Default.Tune, "Filter", tint = if (filter.bpmMin > 60 || filter.bpmMax < 200 || filter.minEnergy > 0) C.Hot else C.Electric)
            }
        }
        if (showFilter) FilterPanel(vm)

        // ---- Crates ----
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            items(crates, key = { it.first }) { (name, count) ->
                val sel = filter.crate == name
                val color = when (name) { "Review" -> C.Warn; "Received" -> C.Hot; else -> C.Electric }
                Row(
                    Modifier.background(if (sel) color else C.Panel, cut(7.dp)).border(1.dp, if (sel) color else C.Line, cut(7.dp))
                        .clickable { vm.setCrate(name) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name.uppercase(), fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = if (sel) Color.Black else C.Text)
                    Text(" $count", fontFamily = F.Mono, fontSize = 11.sp, color = if (sel) Color.Black else C.TextDim)
                }
            }
        }

        // ---- Tracks ----
        Box(Modifier.weight(1f)) {
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = if (selecting) 90.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(visible, key = { _, t -> t.id }) { i, t ->
                    TrackRow(
                        t, settings.camelotKeys, selected = t.id in selection, selecting = selecting, playing = ui.currentId == t.id,
                        onClick = { if (selecting) vm.toggleSelect(t.id) else nav.push(Overlay.Detail(t.id)) },
                        onLong = { vm.toggleSelect(t.id) },
                        onPlay = { vm.play(visible, i) },
                    )
                }
                if (visible.isEmpty()) item {
                    Text("Nothing in this crate matches.", color = C.TextDim, fontFamily = F.Body, modifier = Modifier.fillMaxWidth().padding(30.dp), textAlign = TextAlign.Center)
                }
            }
            if (selecting) SelectionBar(vm, nav, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (showAdd) AddSheet({ showAdd = false }, { pickFiles.launch(arrayOf("audio/*")) }, { pickFolder.launch(null) })
}

@Composable
private fun ScanDot() {
    val tr = rememberInfiniteTransition(label = "dot")
    val a by tr.animateFloat(0.2f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).alpha(a).background(C.Electric))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    t: TrackEntity, camelot: Boolean, selected: Boolean = false, selecting: Boolean = false, playing: Boolean = false,
    onClick: () -> Unit, onLong: () -> Unit = {}, onPlay: () -> Unit,
) {
    val edge = when { selected -> C.Electric; playing -> C.Hot; else -> C.Line }
    Row(
        Modifier.fillMaxWidth().background(if (selected) C.ElectricDim.copy(alpha = 0.35f) else C.Panel, cut(10.dp)).border(1.dp, edge, cut(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp).clickable { if (selecting) onClick() else onPlay() }) {
            Art(t.artPath, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(26.dp).background(Color(0x99000000), cut(4.dp)), contentAlignment = Alignment.Center) {
                Icon(if (selecting && selected) Icons.Default.Check else Icons.Default.PlayArrow, null, tint = if (playing) C.Hot else C.Electric, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (playing) C.Hot else C.Text)
            Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = F.Body, fontSize = 14.sp, color = C.TextDim)
            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                when (t.status) {
                    Status.PENDING, Status.ANALYZING -> Tag(if (t.status == Status.ANALYZING) "Scanning" else "Queued", C.TextDim)
                    Status.ERROR -> Tag("Error", C.Hot)
                    else -> {
                        if (t.genre.isNotBlank()) Tag(t.genre, if (t.genreConfidence < 0.5 && !t.genreLocked) C.Warn else C.Electric)
                        if (t.qualitySuspicious) Icon(Icons.Default.Warning, "Quality warning", tint = C.Warn, modifier = Modifier.size(14.dp))
                        if (t.received) Tag("From ${t.receivedFrom ?: "friend"}", C.Hot)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (t.bpm > 0) EdmClassifier.fmtBpm(t.bpm) else "---", fontFamily = F.Mono, fontSize = 18.sp, color = C.Text)
            Box(Modifier.height(3.dp))
            KeyBadge(t.musicalKey, camelot)
            Box(Modifier.height(4.dp))
            EnergyBar(t.energy, segH = 7.dp, segW = 2.5.dp)
        }
    }
}

@Composable
private fun SelectionBar(vm: AppViewModel, nav: Nav, modifier: Modifier) {
    val sel by vm.selection.collectAsState()
    Row(
        modifier.fillMaxWidth().padding(12.dp).background(C.PanelHi, cut(12.dp)).border(1.dp, C.Electric, cut(12.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BarAction(Icons.Default.Share, "Share", C.Electric) { nav.push(Overlay.Share(sel.toList())) }
        BarAction(Icons.Default.PlayArrow, "Play") { vm.play(vm.selectedTracks(), 0); vm.clearSelection() }
        BarAction(Icons.Default.Refresh, "Rescan") { vm.reanalyze(sel); vm.clearSelection() }
        BarAction(Icons.Default.Delete, "Remove", C.Hot) { vm.remove(sel) }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color = C.Text, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = color)
        Text(label.uppercase(), fontFamily = F.Display, fontSize = 9.sp, color = color, letterSpacing = 1.sp)
    }
}

@Composable
private fun FilterPanel(vm: AppViewModel) {
    val f by vm.filter.collectAsState()
    Panel(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth(), edge = C.ElectricDim) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BPM ${f.bpmMin.toInt()} – ${f.bpmMax.toInt()}", fontFamily = F.Mono, color = C.Text, modifier = Modifier.weight(1f))
                Text("RESET", fontFamily = F.Display, fontSize = 10.sp, color = C.Hot, modifier = Modifier.clickable { vm.resetFilters() }.padding(4.dp))
            }
            RangeSlider(f.bpmMin..f.bpmMax, { vm.setBpm(it.start, it.endInclusive) }, valueRange = 60f..200f,
                colors = SliderDefaults.colors(thumbColor = C.Electric, activeTrackColor = C.Electric, inactiveTrackColor = C.Line))
            Text("MIN ENERGY ${f.minEnergy}", fontFamily = F.Mono, color = C.Text)
            Slider(f.minEnergy.toFloat(), { vm.setMinEnergy(it.toInt()) }, valueRange = 0f..10f, steps = 9,
                colors = SliderDefaults.colors(thumbColor = C.Electric, activeTrackColor = C.Electric, inactiveTrackColor = C.Line))
        }
    }
}

@Composable
private fun EmptyLibrary(onFiles: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("YOUR CRATES ARE EMPTY", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric, textAlign = TextAlign.Center)
        Text("Point Cratez at your music. It detects genre (deep on EDM), BPM, key, energy, grabs album art and writes it all into your files.",
            color = C.TextDim, fontFamily = F.Body, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 18.dp))
        NeonButton("Select songs", icon = Icons.Default.LibraryAdd, filled = true, modifier = Modifier.fillMaxWidth(), onClick = onFiles)
        Box(Modifier.height(12.dp))
        NeonButton("Select a folder", icon = Icons.Default.CreateNewFolder, modifier = Modifier.fillMaxWidth(), onClick = onFolder)
        Text("Tip: pick your Download or Music folder to grab everything at once.", color = C.TextDim, fontSize = 13.sp, fontFamily = F.Body, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun AddSheet(onDismiss: () -> Unit, onFiles: () -> Unit, onFolder: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(C.Panel, cut(18.dp)).border(1.dp, C.Electric, cut(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ADD MUSIC", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric)
            NeonButton("Select songs", icon = Icons.Default.LibraryAdd, filled = true, modifier = Modifier.fillMaxWidth()) { onDismiss(); onFiles() }
            NeonButton("Select a whole folder", icon = Icons.Default.CreateNewFolder, modifier = Modifier.fillMaxWidth()) { onDismiss(); onFolder() }
            Text("Cratez keeps access so it can write tags back into the files.", color = C.TextDim, fontSize = 13.sp, fontFamily = F.Body)
            Box(Modifier.width(1.dp).height(8.dp))
        }
    }
}
