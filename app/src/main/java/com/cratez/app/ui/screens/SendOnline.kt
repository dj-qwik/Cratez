package com.cratez.app.ui.screens

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.data.Settings
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import kotlinx.coroutines.launch

/** Pick a friend (or type a code) to send tracks to over the internet. */
@Composable
fun SendOnlineScreen(vm: AppViewModel, ids: List<Long>, nav: Nav) {
    val friends by vm.friends.collectAsState()
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val ready = vm.cloud.configured(settings)
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null) { nav.pop() }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {}
                .background(C.Panel, cut(18.dp)).border(1.dp, C.Hot, cut(18.dp))
                .windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SEND ${ids.size} TRACK${if (ids.size == 1) "" else "S"} ONLINE", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Hot)
            if (!ready) {
                Text("Online sharing is off or the relay isn't set. Turn it on in Setup.", color = C.Warn, fontFamily = F.Body)
                return@Column
            }
            Text("They don't need to be nearby. It shows up in their Cratez within ~15 minutes, or instantly when they open the app. Waits up to 7 days.",
                color = C.TextDim, fontFamily = F.Body, fontSize = 14.sp)
            if (friends.isEmpty()) Text("No friends saved yet — enter a code below.", color = C.TextDim, fontFamily = F.Body)
            friends.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().background(C.PanelHi, cut(10.dp)).border(1.dp, C.Line, cut(10.dp))
                        .clickable { vm.sendOnline(f.code, f.name, ids); nav.pop() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, null, tint = C.Warn, modifier = Modifier.size(20.dp))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(f.name, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Text)
                        Text(f.code, fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
                    }
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = C.Hot)
                }
            }
            Text("Or a friend code", color = C.TextDim, fontFamily = F.Body)
            Field(code, { code = it }, "CRZ-XXXX-XXXX")
            NeonButton(if (checking) "Checking…" else "Send", color = C.Hot, filled = true, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                val parsed = Settings.parseFriendCode(code)
                if (parsed == null) { vm.toast("Codes look like CRZ-7KQ2-X9AB"); return@NeonButton }
                checking = true
                scope.launch {
                    val name = vm.lookupFriend(parsed.first)
                    checking = false
                    if (name == null) vm.toast("No Cratez user with that code is online yet")
                    else { vm.addFriend(parsed.first, name); vm.sendOnline(parsed.first, name, ids); nav.pop() }
                }
            }
        }
    }
}
