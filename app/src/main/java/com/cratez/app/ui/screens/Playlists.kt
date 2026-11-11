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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.Art
import com.cratez.app.ui.components.KeyBadge
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.components.cyberBackdrop
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.core.genre.EdmClassifier

private fun totalTime(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsScreen(vm: AppViewModel, nav: Nav) {
    val lists by vm.playlists.collectAsState()
    val all by vm.tracks.collectAsState()
    val crates by vm.crates.collectAsState()
    var newOpen by remember { mutableStateOf(false) }
    var fromCrate by remember { mutableStateOf(false) }
    val byId = all.associateBy { it.id }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PLAYLISTS", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric, modifier = Modifier.weight(1f))
                NeonButton("New", icon = Icons.Default.Add, filled = true) { newOpen = true }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("Auto-mix a crate", icon = Icons.Default.AutoAwesome, color = C.Hot, modifier = Modifier.weight(1f)) { fromCrate = true }
                NeonButton("Shuffle all", icon = Icons.Default.Shuffle, modifier = Modifier.weight(1f)) {
                    if (all.isNotEmpty()) vm.player.playList(all, all.indices.random(), shuffle = true)
                }
            }
            if (lists.isEmpty()) {
                Text("No playlists yet. Make one here, or select tracks in Crates (long-press) and tap + LIST. \"Auto-mix a crate\" builds a DJ-ordered set for you.",
                    color = C.TextDim, fontFamily = F.Body, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lists, key = { it.id }) { p ->
                    val ts = p.ids.mapNotNull { byId[it] }
                    Row(
                        Modifier.fillMaxWidth().background(C.Panel, cut(10.dp)).border(1.dp, C.Line, cut(10.dp))
                            .clickable { nav.push(Overlay.Playlist(p.id)) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Art(ts.firstOrNull { it.artPath != null }?.artPath, Modifier.size(56.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${ts.size} tracks · ${totalTime(ts.sumOf { it.durationMs })}", fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
                            val genres = ts.groupingBy { it.genre }.eachCount().entries.sortedByDescending { it.value }.take(2).joinToString(" · ") { it.key }
                            if (genres.isNotBlank()) Text(genres, fontFamily = F.Body, fontSize = 13.sp, color = C.Electric, maxLines = 1)
                        }
                        IconButton(onClick = { vm.playPlaylist(p) }) { Icon(Icons.Default.PlayArrow, "Play", tint = C.Electric, modifier = Modifier.size(30.dp)) }
                    }
                }
            }
        }
        if (newOpen) {
            var name by remember { mutableStateOf("") }
            Dialog({ newOpen = false }) {
                Text("NEW PLAYLIST", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
                Field(name, { name = it }, "Name (e.g. Friday warm-up)")
                NeonButton("Create", filled = true, modifier = Modifier.fillMaxWidth()) { vm.newPlaylistWith(name, emptyList()); newOpen = false }
            }
        }
        if (fromCrate) Dialog({ fromCrate = false }) {
            Text("AUTO-MIX A CRATE", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Hot)
            Text("Pick a crate. Cratez orders it like a DJ set: compatible keys, close tempos, energy building.", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                crates.forEach { (name, count) ->
                    Text("$name ($count)", fontFamily = F.Body, fontWeight = FontWeight.SemiBold, color = C.Text, fontSize = 14.sp,
                        modifier = Modifier.background(C.PanelHi, cut(6.dp)).border(1.dp, C.Line, cut(6.dp))
                            .clickable { vm.resetFilters(); vm.setQuery(""); vm.setCrate(name); vm.playlistFromCrate(); fromCrate = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
fun PlaylistDetail(vm: AppViewModel, id: Long, nav: Nav) {
    val lists by vm.playlists.collectAsState()
    val all by vm.tracks.collectAsState()
    val settings by vm.settings.collectAsState()
    val ui by vm.player.ui.collectAsState()
    val p = lists.firstOrNull { it.id == id }
    var editing by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().cyberBackdrop().clickable(remember { MutableInteractionSource() }, null) {}) {
        if (p == null) { androidx.compose.runtime.LaunchedEffect(Unit) { nav.pop() }; return@Box }
        val byId = all.associateBy { it.id }
        val ts = p.ids.mapNotNull { byId[it] }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.Electric) }
                Column(Modifier.weight(1f).clickable { rename = true }) {
                    Text(p.name, fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${ts.size} tracks · ${totalTime(ts.sumOf { it.durationMs })}", fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
                }
                IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Close else Icons.Default.Edit, "Edit", tint = if (editing) C.Hot else C.TextDim) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete playlist", tint = C.TextDim) }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Play", icon = Icons.Default.PlayArrow, filled = true, modifier = Modifier.weight(1f)) { vm.playPlaylist(p) }
                NeonButton("Shuffle", icon = Icons.Default.Shuffle, modifier = Modifier.weight(1f)) { vm.playPlaylist(p, shuffle = true) }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Harmonic order", icon = Icons.Default.AutoAwesome, color = C.Hot, modifier = Modifier.weight(1f)) { vm.harmonicOrder(p) }
                NeonButton("Queue all", icon = Icons.AutoMirrored.Filled.QueueMusic, modifier = Modifier.weight(1f)) { vm.addToQueue(p.ids) }
            }
            if (ts.isEmpty()) Text("Empty. In Crates, long-press tracks to select them, then tap + LIST.", color = C.TextDim, fontFamily = F.Body, modifier = Modifier.padding(20.dp))
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(ts, key = { _, t -> t.id }) { i, t ->
                    val playing = ui.currentId == t.id
                    Row(
                        Modifier.fillMaxWidth().background(C.Panel, cut(8.dp)).border(1.dp, if (playing) C.Hot else C.Line, cut(8.dp))
                            .clickable { if (!editing) vm.playPlaylist(p, i) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}".padStart(2), fontFamily = F.Mono, color = C.TextDim, fontSize = 13.sp, modifier = Modifier.width(26.dp))
                        Art(t.artPath, Modifier.size(42.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(t.displayTitle, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (playing) C.Hot else C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.displayArtist, fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim, maxLines = 1)
                        }
                        if (editing) {
                            IconButton(onClick = { vm.moveInPlaylist(p, i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = C.Text) }
                            IconButton(onClick = { vm.moveInPlaylist(p, i, i + 1) }, enabled = i < ts.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down", tint = C.Text) }
                            IconButton(onClick = { vm.removeFromPlaylist(p, t.id) }) { Icon(Icons.Default.Close, "Remove", tint = C.Hot) }
                        } else {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (t.bpm > 0) EdmClassifier.fmtBpm(t.bpm) else "--", fontFamily = F.Mono, fontSize = 14.sp, color = C.Text)
                                KeyBadge(t.musicalKey, settings.camelotKeys)
                            }
                        }
                    }
                }
            }
        }
        if (rename) {
            var name by remember { mutableStateOf(p.name) }
            Dialog({ rename = false }) {
                Text("RENAME", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
                Field(name, { name = it }, "Playlist name")
                NeonButton("Save", filled = true, modifier = Modifier.fillMaxWidth()) { vm.renamePlaylist(p, name); rename = false }
            }
        }
        if (confirmDelete) Dialog({ confirmDelete = false }) {
            Text("DELETE \"${p.name}\"?", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Hot)
            Text("Only the playlist goes. Tracks stay in your library.", fontFamily = F.Body, color = C.Text)
            NeonButton("Delete", color = C.Hot, filled = true, modifier = Modifier.fillMaxWidth()) { vm.deletePlaylist(p); confirmDelete = false; nav.pop() }
        }
    }
}

/** "Play next / Add to queue / Add to a playlist" for selected tracks. */
@Composable
fun AddToPlaylistSheet(vm: AppViewModel, ids: List<Long>, nav: Nav) {
    val lists by vm.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null) { nav.pop() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}
                .background(C.Panel, cut(18.dp)).border(1.dp, C.Electric, cut(18.dp))
                .windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${ids.size} TRACK${if (ids.size == 1) "" else "S"}", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Play next", icon = Icons.Default.SkipNext, modifier = Modifier.weight(1f)) { vm.playNext(ids); nav.pop() }
                NeonButton("Add to queue", icon = Icons.AutoMirrored.Filled.QueueMusic, modifier = Modifier.weight(1f)) { vm.addToQueue(ids); nav.pop() }
            }
            Text("Add to playlist", fontFamily = F.Body, color = C.TextDim)
            lists.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().background(C.PanelHi, cut(8.dp)).border(1.dp, C.Line, cut(8.dp))
                        .clickable { vm.addToPlaylist(p.id, ids); nav.pop() }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = C.Electric)
                    Text(p.name, fontFamily = F.Body, fontWeight = FontWeight.Bold, color = C.Text, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
                    Text("${p.ids.size}", fontFamily = F.Mono, color = C.TextDim, fontSize = 12.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(newName, { newName = it }, "New playlist name") }
                Box(Modifier.padding(start = 8.dp)) {
                    NeonButton("Create", icon = Icons.AutoMirrored.Filled.PlaylistAdd, filled = true) { vm.newPlaylistWith(newName, ids); nav.pop() }
                }
            }
        }
    }
}
