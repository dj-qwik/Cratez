package com.cratez.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.Logo
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.Panel
import com.cratez.app.ui.components.SectionLabel
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    val all by vm.tracks.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var editRelay by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(size = 22)
            SectionLabel("Tagging")
            Setting("Write tags into files", "Genre, BPM, key, energy and album art go into your MP3/FLAC files so Rekordbox, Serato and Traktor see them.", s.writeTags, vm::setWriteTags)
            Setting("Camelot keys (8A, 5B…)", "Off = musical keys (Am, Eb). Applies to display and new tags.", s.camelotKeys, vm::setCamelot)
            Setting("Online lookup", "Ask iTunes, Deezer and MusicBrainz for genre, label, year and art. Audio analysis always runs.", s.onlineLookup, vm::setOnline)
            Setting("Upgrade low-res art", "Replace embedded artwork smaller than 500px with a 1000px version.", s.replaceLowResArt, vm::setReplaceArt)
            SectionLabel("Beam")
            Setting("Visible to nearby friends", "Lets friends find this phone while Cratez is open.", s.beamVisible, vm::setBeamVisible)
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.myName, fontFamily = F.Body, fontWeight = FontWeight.Bold, color = C.Text, fontSize = 17.sp)
                        Text(s.myCode, fontFamily = F.Mono, color = C.Electric)
                    }
                    NeonButton("Rename") { editName = true }
                }
            }
            SectionLabel("Online sharing")
            Setting("Send & receive by friend code", "Friends anywhere can send you tracks through the Cratez relay. Files wait up to 7 days, then vanish.", s.onlineSharing, vm::setOnlineSharing)
            Panel(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Relay server", fontFamily = F.Body, fontWeight = FontWeight.Bold, color = C.Text, fontSize = 16.sp)
                        Text(s.relayUrl, fontFamily = F.Mono, color = C.TextDim, fontSize = 11.sp)
                    }
                    NeonButton("Edit") { editRelay = true }
                }
            }
            SectionLabel("Library")
            NeonButton("Re-analyze all ${all.size} tracks", icon = Icons.Default.Refresh, modifier = Modifier.fillMaxWidth()) { vm.reanalyzeAll() }
            NeonButton("Re-sort genres from my library", icon = Icons.Default.Refresh, color = C.Hot, modifier = Modifier.fillMaxWidth()) { vm.resortFromLibrary() }
            Text("Cratez learns from tracks with trusted genres: store tags (Beatport etc.), folder names, and every genre you fix. Fixing a few in the Review crate teaches it your style.",
                fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim)
            NeonButton("Clear library", icon = Icons.Default.DeleteForever, color = C.Hot, modifier = Modifier.fillMaxWidth()) { confirmClear = true }
            Text("Clearing only forgets tracks inside Cratez. Your music files are never deleted except from the Dupes tab, and Android asks you first.",
                fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim)
            SectionLabel("About")
            Text("Cratez 1.0 · genre engine: file tags + iTunes + Deezer + MusicBrainz + on-device audio analysis (tempo, groove, kick pattern, bass weight, key) with a dedicated EDM sub-genre model.",
                fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim)
            Box(Modifier.height(30.dp))
        }
        if (confirmClear) Dialog({ confirmClear = false }) {
            Text("CLEAR LIBRARY?", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Hot)
            Text("Cratez forgets all ${all.size} tracks and their analysis. Files stay on your phone.", fontFamily = F.Body, color = C.Text)
            NeonButton("Clear", color = C.Hot, filled = true, modifier = Modifier.fillMaxWidth()) { vm.clearLibrary(); confirmClear = false }
            NeonButton("Cancel", modifier = Modifier.fillMaxWidth()) { confirmClear = false }
        }
        if (editRelay) {
            var url by remember { mutableStateOf(s.relayUrl) }
            Dialog({ editRelay = false }) {
                Text("RELAY SERVER", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
                Field(url, { url = it }, "https://…workers.dev")
                Text("Everyone who shares with each other must use the same relay.", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
                NeonButton("Save", filled = true, modifier = Modifier.fillMaxWidth()) { vm.setRelayUrl(url); editRelay = false }
                NeonButton("Reset to default", modifier = Modifier.fillMaxWidth()) { vm.setRelayUrl(""); editRelay = false }
            }
        }
        if (editName) {
            var name by remember { mutableStateOf(s.myName) }
            Dialog({ editName = false }) {
                Text("YOUR DJ NAME", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
                Field(name, { name = it }, "Name friends see")
                NeonButton("Save", filled = true, modifier = Modifier.fillMaxWidth()) { vm.setName(name); editName = false }
            }
        }
    }
}

@Composable
private fun Setting(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(title, fontFamily = F.Body, fontWeight = FontWeight.Bold, color = C.Text, fontSize = 16.sp)
                Text(sub, fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
            }
            Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = C.Electric, uncheckedTrackColor = C.PanelHi, uncheckedBorderColor = C.Line))
        }
    }
}
