package com.cratez.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.Panel
import com.cratez.app.ui.components.ProgressLine
import com.cratez.app.ui.components.Tag
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.core.dupes.DupeFile
import kotlinx.coroutines.launch

@Composable
fun DupesScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val st by vm.dupes.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirm by remember { mutableStateOf<Set<Long>?>(null) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.scanDupes() else vm.toast("Cratez needs music access to find duplicates")
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) vm.afterDupesDeleted(pendingDelete)
        pendingDelete = emptySet()
    }
    fun scan() {
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) vm.scanDupes() else permLauncher.launch(perm)
    }
    fun delete(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val pi = vm.dupes.deleteRequest(ids)
        if (pi != null) { pendingDelete = ids; deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
        else confirm = ids
    }

    val groups = st.groups
    val selBytes = groups.flatMap { it.files }.filter { it.id in st.selected }.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DUPLICATES", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric)
            Text("Scans every song on your phone for copies — same file twice, \"(1)\" downloads, or the same track at different bitrates. The best copy is marked KEEP.",
                color = C.TextDim, fontFamily = F.Body, fontSize = 15.sp)
            NeonButton(if (st.hasScanned) "Scan again" else "Scan my phone", icon = Icons.Default.Radar, filled = !st.hasScanned, enabled = !st.scanning, modifier = Modifier.fillMaxWidth()) { scan() }
            if (st.scanning) { Text("Scanning ${if (st.scanned > 0) "${st.scanned} songs" else "…"}", fontFamily = F.Mono, color = C.Electric); ProgressLine(null) }
            st.message?.let { Text(it, fontFamily = F.Body, color = C.Ok) }
            if (groups.isNotEmpty()) {
                Text("${groups.size} duplicate set${if (groups.size == 1) "" else "s"} · ${groups.sumOf { it.extras.size }} extra copies · ${"%.0f".format(groups.sumOf { g -> g.extras.sumOf { it.sizeBytes } } / 1048576.0)} MB reclaimable",
                    fontFamily = F.Mono, fontSize = 12.sp, color = C.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Select all extras", modifier = Modifier.weight(1f)) { vm.dupes.selectAllExtras() }
                    NeonButton("Clear", color = C.TextDim) { vm.dupes.clearSelection() }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(groups, key = { it.keep.id }) { g ->
                Panel(Modifier.fillMaxWidth(), edge = if (g.exact) C.Hot else C.ElectricDim) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.keep.title, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = C.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Tag(if (g.exact) "Identical" else "Same song", if (g.exact) C.Hot else C.Electric)
                        }
                        g.files.forEach { f -> DupeRow(f, f.id == g.keep.id, f.id in st.selected, { vm.dupes.toggle(f.id) }) { delete(setOf(f.id)) } }
                    }
                }
            }
        }
        if (st.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                NeonButton("Delete ${st.selected.size} · ${"%.0f".format(selBytes / 1048576.0)} MB", icon = Icons.Default.DeleteSweep, color = C.Hot, filled = true, modifier = Modifier.fillMaxWidth()) {
                    delete(st.selected)
                }
            }
        }
    }
    confirm?.let { ids ->
        Dialog({ confirm = null }) {
            Text("DELETE ${ids.size} FILE${if (ids.size == 1) "" else "S"}?", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Hot)
            Text("This permanently removes them from your phone.", fontFamily = F.Body, color = C.Text)
            NeonButton("Delete", color = C.Hot, filled = true, modifier = Modifier.fillMaxWidth()) {
                scope.launch { vm.dupes.deleteDirect(ids); vm.afterDupesDeleted(ids) }; confirm = null
            }
            NeonButton("Cancel", modifier = Modifier.fillMaxWidth()) { confirm = null }
        }
    }
}

@Composable
private fun DupeRow(f: DupeFile, keep: Boolean, selected: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) C.Hot.copy(alpha = 0.12f) else C.PanelHi, cut(6.dp))
            .border(1.dp, if (selected) C.Hot else C.Line, cut(6.dp)).clickable(onClick = onToggle).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null, tint = if (selected) C.Hot else C.TextDim, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(f.path.substringAfterLast('/'), fontFamily = F.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.path.substringBeforeLast('/').substringAfter("/0/"), fontFamily = F.Mono, fontSize = 11.sp, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (f.lossless) "Lossless" else if (f.bitrateKbps > 0) "${f.bitrateKbps} kbps" else null,
                "%.1f MB".format(f.sizeBytes / 1048576.0), if (f.durationMs > 0) "%d:%02d".format(f.durationMs / 60000, (f.durationMs / 1000) % 60) else null).joinToString(" · "),
                fontFamily = F.Mono, fontSize = 11.sp, color = C.TextDim)
        }
        if (keep) Tag("Keep", C.Ok)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete this file", tint = C.Hot) }
    }
}
