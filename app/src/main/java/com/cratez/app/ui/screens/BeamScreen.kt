package com.cratez.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.share.Peer
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.Panel
import com.cratez.app.ui.components.ProgressLine
import com.cratez.app.ui.components.SectionLabel
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.cos
import kotlin.math.sin

fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF1EC8FF.toInt() else 0xFF04060A.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun BeamScreen(vm: AppViewModel, nav: Nav) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val beam by vm.beam.state.collectAsState()
    val cloud by vm.cloud.state.collectAsState()
    val allTransfers = cloud.transfers + beam.transfers
    val friends by vm.friends.collectAsState()
    val all by vm.tracks.collectAsState()
    var hasPerm by remember { mutableStateOf(vm.beam.hasPermissions()) }
    var addOpen by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    val sending = all.filter { it.id in nav.beamSend }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasPerm = vm.beam.hasPermissions()
        if (hasPerm) { if (s.beamVisible) vm.beam.startAdvertising(); vm.beam.startDiscovery() }
        else vm.toast("Beam needs Nearby devices permission")
    }
    DisposableEffect(hasPerm) {
        if (hasPerm) { vm.beam.startDiscovery(); if (s.beamVisible) vm.beam.startAdvertising() }
        onDispose { vm.beam.stopDiscovery() }
    }

    fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b -> if (!vm.addFriend(b.rawValue ?: "", null)) vm.toast("That's not a Cratez code") }
            .addOnFailureListener { vm.toast("Scanner unavailable: ${it.message}") }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("BEAM", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = C.Electric)
            Text("Share tracks phone-to-phone with friends. No internet, no size limit, and everything arrives already analyzed and tagged.",
                color = C.TextDim, fontFamily = F.Body, fontSize = 15.sp)

            if (sending.isNotEmpty()) Panel(Modifier.fillMaxWidth(), edge = C.Hot) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("READY TO BEAM ${sending.size} TRACK${if (sending.size == 1) "" else "S"}", fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = C.Hot)
                        Text("Tap a friend below. They need Cratez open.", fontFamily = F.Body, color = C.TextDim, fontSize = 14.sp)
                    }
                    IconButton(onClick = { nav.beamSend = emptyList() }) { Icon(Icons.Default.Close, "Cancel", tint = C.TextDim) }
                }
            }

            if (!hasPerm) {
                Panel(Modifier.fillMaxWidth(), edge = C.Warn) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Beam uses Bluetooth + Wi-Fi Direct to find friends nearby.", fontFamily = F.Body, color = C.Text)
                        NeonButton("Allow nearby devices", icon = Icons.Default.WifiTethering, filled = true, modifier = Modifier.fillMaxWidth()) {
                            permLauncher.launch(vm.beam.requiredPermissions())
                        }
                    }
                }
            } else {
                SectionLabel("Nearby Cratez users")
                Radar(beam.discovering)
                if (beam.peers.isEmpty()) Text("Scanning… ask your friend to open Cratez → Beam.", color = C.TextDim, fontFamily = F.Body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                beam.peers.forEach { p -> PeerRow(p, sending.isNotEmpty()) {
                    if (sending.isEmpty()) vm.toast("Select tracks in Crates, then Share → Beam") else { vm.beam.send(p, sending); nav.beamSend = emptyList() }
                } }
            }

            // ---- Online inbox ----
            SectionLabel("Online (friend codes)", color = C.Hot)
            Panel(Modifier.fillMaxWidth(), edge = C.Hot.copy(alpha = 0.6f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val on = vm.cloud.configured(s)
                    Text(if (!on) "Online sharing is off or not set up (Setup tab)." else
                        "Friends anywhere can send to ${s.myCode}. Cratez checks every ~15 min and whenever you open it." +
                            (if (cloud.lastCheck > 0) " Last check ${android.text.format.DateUtils.getRelativeTimeSpanString(cloud.lastCheck)}." else ""),
                        fontFamily = F.Body, color = C.Text, fontSize = 14.sp)
                    cloud.lastError?.let { Text(it, fontFamily = F.Body, color = C.Warn, fontSize = 13.sp) }
                    if (on) NeonButton(if (cloud.checking) "Checking…" else "Check inbox now", color = C.Hot, enabled = !cloud.checking, modifier = Modifier.fillMaxWidth()) { vm.checkInbox() }
                }
            }

            if (allTransfers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Transfers", Modifier.weight(1f))
                    Text("CLEAR", fontFamily = F.Display, fontSize = 10.sp, color = C.TextDim, modifier = Modifier.clickable { vm.beam.clearFinished(); vm.cloud.clearFinished() }.padding(6.dp))
                }
                allTransfers.take(30).forEach { tr ->
                    Column {
                        Row {
                            Text((if (tr.incoming) "⇣ " else "⇡ ") + tr.title, fontFamily = F.Body, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(when (tr.status) { 1 -> "DONE"; 2 -> "FAILED"; else -> if (tr.total > 0) "${tr.bytes * 100 / tr.total}%" else "…" },
                                fontFamily = F.Mono, fontSize = 12.sp, color = when (tr.status) { 1 -> C.Ok; 2 -> C.Hot; else -> C.Electric })
                        }
                        ProgressLine(if (tr.status == 1) 1f else if (tr.total > 0) tr.bytes.toFloat() / tr.total else null, color = if (tr.incoming) C.Hot else C.Electric)
                        Text(if (tr.incoming) "from ${tr.peer}" else "to ${tr.peer}", fontFamily = F.Body, fontSize = 12.sp, color = C.TextDim)
                    }
                }
            }

            // ---- My code ----
            SectionLabel("Your friend code")
            Panel(Modifier.fillMaxWidth(), edge = C.ElectricDim) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val qr = remember(s.myCode, s.myName) { if (s.myCode.isBlank()) null else qrBitmap("cratez://friend/${s.myCode}?name=${Uri.encode(s.myName)}") }
                    if (qr != null) Image(qr.asImageBitmap(), "Friend QR code", Modifier.size(120.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(s.myCode, fontFamily = F.Mono, fontSize = 22.sp, color = C.Electric)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { editName = true }) {
                            Text(s.myName, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Text)
                            Icon(Icons.Default.Edit, "Edit name", tint = C.TextDim, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                        }
                        Text("Friends scan this to add you. Friends are auto-accepted when they beam you tracks.", fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("Scan QR", icon = Icons.Default.QrCodeScanner, modifier = Modifier.weight(1f)) { scanQr() }
                NeonButton("Enter code", icon = Icons.Default.PersonAdd, modifier = Modifier.weight(1f)) { addOpen = true }
            }

            SectionLabel("Friends (${friends.size})")
            if (friends.isEmpty()) Text("No friends yet. Add someone's code and their phone will show up starred when nearby.", color = C.TextDim, fontFamily = F.Body)
            friends.forEach { f ->
                Row(Modifier.fillMaxWidth().background(C.Panel, cut(8.dp)).border(1.dp, C.Line, cut(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null, tint = C.Warn, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(f.name, fontFamily = F.Body, fontWeight = FontWeight.Bold, color = C.Text)
                        Text(f.code, fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
                    }
                    val online = beam.peers.any { it.code == f.code }
                    if (online) Text("NEARBY", fontFamily = F.Display, fontSize = 9.sp, color = C.Ok, modifier = Modifier.padding(end = 6.dp))
                    if (sending.isNotEmpty()) Text("SEND ONLINE ›", fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = C.Hot,
                        modifier = Modifier.clickable { vm.sendOnline(f.code, f.name, nav.beamSend); nav.beamSend = emptyList() }.padding(6.dp))
                    IconButton(onClick = { vm.removeFriend(f.code) }) { Icon(Icons.Default.Delete, "Remove", tint = C.TextDim) }
                }
            }
            Text("Friend far away? Share → Send to a friend's code works anywhere. Email / Text / Other apps work too, and the tags travel inside the files.",
                color = C.TextDim, fontFamily = F.Body, fontSize = 13.sp)
            Box(Modifier.height(20.dp))
        }

        if (addOpen) {
            var code by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            Dialog({ addOpen = false }) {
                Text("ADD FRIEND", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
                Field(code, { code = it }, "Friend code (CRZ-XXXX-XXXX)")
                Field(name, { name = it }, "Their name (optional)")
                NeonButton("Add", filled = true, modifier = Modifier.fillMaxWidth()) {
                    if (vm.addFriend(code, name)) addOpen = false else vm.toast("Codes look like CRZ-7KQ2-X9AB")
                }
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
private fun Radar(active: Boolean) {
    val tr = rememberInfiniteTransition(label = "radar")
    val a by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "sweep")
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.height / 2 - 4
        for (i in 1..3) drawCircle(C.ElectricDim, r * i / 3, c, style = Stroke(1.5f))
        drawLine(C.ElectricDim, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(C.ElectricDim, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        if (active) {
            val rad = Math.toRadians(a.toDouble())
            drawLine(Brush.linearGradient(listOf(Color.Transparent, C.Electric), c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())),
                c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), 4f)
        }
        drawCircle(C.Electric, 5f, c)
    }
}

@Composable
private fun PeerRow(p: Peer, sendMode: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (sendMode) C.ElectricDim.copy(alpha = 0.3f) else C.Panel, cut(10.dp))
            .border(1.dp, if (p.isFriend) C.Warn else C.Electric, cut(10.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (p.isFriend) Icons.Default.Star else Icons.Default.WifiTethering, null, tint = if (p.isFriend) C.Warn else C.Electric)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(p.name, fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.Text)
            Text(if (p.isFriend) "Friend · ${p.code}" else p.code, fontFamily = F.Mono, fontSize = 12.sp, color = C.TextDim)
        }
        if (sendMode) Text("BEAM ›", fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = C.Hot)
    }
}

@Composable
fun Dialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().background(C.Panel, cut(14.dp)).border(1.dp, C.Electric, cut(14.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String) {
    Box(Modifier.fillMaxWidth().background(C.Bg, cut(6.dp)).border(1.dp, C.Line, cut(6.dp)).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, color = C.TextDim, fontFamily = F.Body, fontSize = 16.sp)
        BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(C.Electric),
            textStyle = TextStyle(color = C.Text, fontFamily = F.Body, fontSize = 16.sp, fontWeight = FontWeight.Medium), modifier = Modifier.fillMaxWidth())
    }
}
