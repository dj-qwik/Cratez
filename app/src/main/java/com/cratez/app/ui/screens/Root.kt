package com.cratez.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.data.TrackEntity
import com.cratez.app.share.ShareSender
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.Art
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.ProgressLine
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.components.cyberBackdrop
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import kotlinx.coroutines.delay

sealed interface Overlay {
    data class Detail(val id: Long) : Overlay
    data object NowPlaying : Overlay
    data object Viz : Overlay
    data class Share(val ids: List<Long>) : Overlay
    data class SendOnline(val ids: List<Long>) : Overlay
    data class Playlist(val id: Long) : Overlay
    data class AddToList(val ids: List<Long>) : Overlay
}

class Nav {
    val stack = mutableStateListOf<Overlay>()
    var tab by mutableStateOf(0)
    var beamSend by mutableStateOf<List<Long>>(emptyList())
    fun push(o: Overlay) { stack.add(o) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

@Composable
fun CratezRoot(vm: AppViewModel) {
    val nav = remember { Nav() }
    var tabSaved by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(nav.tab) { tabSaved = nav.tab }
    LaunchedEffect(Unit) { nav.tab = tabSaved }
    val ui by vm.player.ui.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val current = tracks.firstOrNull { it.id == ui.currentId }
    val toast by vm.toast.collectAsState()

    Box(Modifier.fillMaxSize().cyberBackdrop()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Box(Modifier.weight(1f)) {
                when (nav.tab) {
                    0 -> LibraryScreen(vm, nav)
                    1 -> BeamScreen(vm, nav)
                    2 -> DupesScreen(vm)
                    4 -> PlaylistsScreen(vm, nav)
                    else -> SettingsScreen(vm)
                }
            }
            if (current != null) MiniPlayer(vm, current, ui.isPlaying, if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f, nav)
            BottomBar(nav)
        }

        nav.stack.forEachIndexed { i, o ->
            Box(Modifier.fillMaxSize()) {
                when (o) {
                    is Overlay.Detail -> DetailScreen(vm, o.id, nav)
                    Overlay.NowPlaying -> NowPlayingScreen(vm, nav)
                    Overlay.Viz -> VizScreen(vm, nav)
                    is Overlay.Share -> ShareSheet(vm, o.ids, nav)
                    is Overlay.SendOnline -> SendOnlineScreen(vm, o.ids, nav)
                    is Overlay.Playlist -> PlaylistDetail(vm, o.id, nav)
                    is Overlay.AddToList -> AddToPlaylistSheet(vm, o.ids, nav)
                }
            }
        }
        BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
        val beam by vm.beam.state.collectAsState()
        LaunchedEffect(beam.message) { beam.message?.let { vm.toast(it); vm.beam.clearMessage() } }
        beam.request?.let { r ->
            Dialog({ vm.beam.answerRequest(false, false) }) {
                Text("INCOMING BEAM", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Hot)
                Text("${r.name} (${r.code}) wants to send you tracks.", fontFamily = F.Body, color = C.Text, fontSize = 16.sp)
                NeonButton("Accept & add friend", filled = true, modifier = Modifier.fillMaxWidth()) { vm.beam.answerRequest(true, true) }
                NeonButton("Accept once", modifier = Modifier.fillMaxWidth()) { vm.beam.answerRequest(true, false) }
                NeonButton("Decline", color = C.Hot, modifier = Modifier.fillMaxWidth()) { vm.beam.answerRequest(false, false) }
            }
        }

        AnimatedVisibility(toast != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 140.dp, start = 24.dp, end = 24.dp)
                .background(C.PanelHi, cut(8.dp)).border(1.dp, C.Electric, cut(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(toast ?: "", color = C.Text, fontFamily = F.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        LaunchedEffect(toast) { if (toast != null) { delay(2600); vm.clearToast() } }
    }
}

@Composable
private fun BottomBar(nav: Nav) {
    val items = listOf(
        Triple("Crates", Icons.Default.LibraryMusic, 0), Triple("Lists", Icons.AutoMirrored.Filled.QueueMusic, 4), Triple("Beam", Icons.Default.WifiTethering, 1),
        Triple("Dupes", Icons.Default.ContentCopy, 2), Triple("Setup", Icons.Default.Settings, 3),
    )
    Row(
        Modifier.fillMaxWidth().background(Color(0xF205080D)).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        for ((label, icon, idx) in items) {
            val sel = nav.tab == idx
            Column(
                Modifier.clickable(remember { MutableInteractionSource() }, null) { nav.tab = idx; nav.stack.clear() }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(28.dp).height(2.dp).background(if (sel) C.Electric else Color.Transparent))
                Icon(icon, label, tint = if (sel) C.Electric else C.TextDim, modifier = Modifier.padding(top = 5.dp).size(22.dp))
                Text(label.uppercase(), fontFamily = F.Display, fontSize = 9.sp, letterSpacing = 1.2.sp, color = if (sel) C.Electric else C.TextDim)
            }
        }
    }
}

@Composable
private fun MiniPlayer(vm: AppViewModel, t: TrackEntity, playing: Boolean, progress: Float, nav: Nav) {
    Column(Modifier.fillMaxWidth().background(C.Panel).clickable { nav.push(Overlay.NowPlaying) }) {
        ProgressLine(progress)
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t.artPath, Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.Text)
                Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = C.TextDim, fontFamily = F.Body)
            }
            IconButton(onClick = { nav.push(Overlay.Viz) }) { Icon(Icons.Default.AutoAwesome, "Visualizer", tint = C.Hot) }
            IconButton(onClick = { vm.player.toggle() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = C.Electric, modifier = Modifier.size(30.dp)) }
            IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, "Next", tint = C.Text) }
        }
    }
}

@Composable
fun ShareSheet(vm: AppViewModel, ids: List<Long>, nav: Nav) {
    val ctx = LocalContext.current
    val all by vm.tracks.collectAsState()
    val tracks = all.filter { it.id in ids }
    val total = tracks.sumOf { it.sizeBytes }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null) { nav.pop() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}
                .background(C.Panel, cut(18.dp)).border(1.dp, C.Electric, cut(18.dp))
                .windowInsetsPadding(WindowInsets.navigationBars).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SHARE ${tracks.size} TRACK${if (tracks.size == 1) "" else "S"}", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric)
            Text("${"%.1f".format(total / 1048576.0)} MB · tags, art, BPM & key travel inside the files", color = C.TextDim, fontSize = 14.sp, fontFamily = F.Body)
            ShareOption(Icons.Default.WifiTethering, "Beam to a friend", "Phone-to-phone, no internet. Fastest, arrives fully analyzed.", C.Electric) {
                nav.beamSend = ids; nav.stack.clear(); nav.tab = 1; vm.clearSelection()
            }
            ShareOption(Icons.Default.Public, "Send to a friend's code", "Over the internet, anywhere. Lands tagged in their Cratez (checks every ~15 min).", C.Hot) {
                nav.pop(); nav.push(Overlay.SendOnline(ids))
            }
            ShareOption(Icons.Default.Email, "Email", if (total > ShareSender.EMAIL_LIMIT) "Over 25 MB — most email will bounce. Try Beam or Other apps." else "Attach to an email", if (total > ShareSender.EMAIL_LIMIT) C.Warn else C.Text) {
                ShareSender.email(ctx, tracks); nav.pop()
            }
            ShareOption(Icons.Default.Sms, "Text message", "Sends as attachments in your messaging app (RCS handles big files best)", C.Text) {
                ShareSender.text(ctx, tracks); nav.pop()
            }
            ShareOption(Icons.Default.Share, "Other apps", "WhatsApp, Drive, Quick Share, Discord…", C.Text) {
                ShareSender.anyApp(ctx, tracks); nav.pop()
            }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(C.PanelHi, cut(10.dp)).border(1.dp, C.Line, cut(10.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = color, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(sub, color = C.TextDim, fontFamily = F.Body, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Filled.Send, null, tint = C.TextDim, modifier = Modifier.size(16.dp))
    }
}
