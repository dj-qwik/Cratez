package com.cratez.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cratez.app.analysis.AnalysisEngine
import com.cratez.app.data.TrackEntity
import com.cratez.app.player.PlayerUi
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.KeyBadge
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.ProgressLine
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.core.dsp.AudioAnalyzer
import com.cratez.core.genre.EdmClassifier
import com.cratez.core.music.MusicKey
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmtTime(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
private fun SmallChip(label: String, color: Color = C.Electric, onClick: () -> Unit) {
    Text(label, fontFamily = F.Mono, fontSize = 14.sp, color = color, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.08f), cut(5.dp)).border(1.dp, color.copy(alpha = 0.6f), cut(5.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))
}

/** Fix a BPM: tap it out, type it, nudge it, or pick one of the detector's alternatives. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmEditor(vm: AppViewModel, t: TrackEntity, ui: PlayerUi, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(if (t.bpm > 0) EdmClassifier.fmtBpm(t.bpm) else "") }
    val taps = remember { mutableStateListOf<Long>() }
    val tapped: Double? = if (taps.size >= 4) {
        val iv = taps.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val med = iv[iv.size / 2]
        val good = iv.filter { abs(it - med) / med < 0.2 }
        60000.0 / good.average()
    } else null
    val current = value.replace(',', '.').toDoubleOrNull()
    fun set(b: Double) { value = EdmClassifier.fmtBpm(AudioAnalyzer.roundBpm(b)) }
    val playingThis = ui.currentId == t.id && ui.isPlaying

    Dialog(onDismiss) {
        Text("FIX BPM", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)

        // Manual entry
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).background(C.Bg, cut(6.dp)).border(1.dp, C.Electric, cut(6.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                BasicTextField(value, { v -> value = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(C.Electric),
                    textStyle = TextStyle(color = C.Text, fontFamily = F.Mono, fontSize = 34.sp), modifier = Modifier.fillMaxWidth())
            }
            Text("BPM", fontFamily = F.Display, color = C.TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("−1" to -1.0, "−0.1" to -0.1, "+0.1" to 0.1, "+1" to 1.0).forEach { (l, d) -> SmallChip(l, C.TextDim) { current?.let { set(it + d) } } }
            listOf("½×" to 0.5, "2×" to 2.0, "×1.5" to 1.5, "×⅔" to 2.0 / 3).forEach { (l, m) -> SmallChip(l) { current?.let { set(it * m) } } }
        }

        // Detector alternatives
        val alts = vm.parseAlts(t.bpmAlts).filter { abs(it.first - (current ?: 0.0)) > 0.05 }
        if (alts.isNotEmpty()) {
            Text("Other tempos the scan heard", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                alts.forEach { (b, s) -> SmallChip("${EdmClassifier.fmtBpm(b)} · ${(s * 100).roundToInt()}%", C.Warn) { set(b) } }
            }
        }

        // Tap tempo
        Text(if (playingThis) "Tap along to the kick" else "Tap along to the kick (start playback first)", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
        Box(
            Modifier.fillMaxWidth().height(110.dp).background(C.Hot.copy(alpha = if (taps.isNotEmpty()) 0.18f else 0.08f), cut(12.dp))
                .border(1.dp, C.Hot, cut(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val now = System.currentTimeMillis()
                        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                        taps.add(now)
                        if (taps.size > 17) taps.removeAt(0)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tapped?.let { "%.1f".format(it) } ?: "TAP", fontFamily = F.Mono, fontSize = 36.sp, color = C.Hot)
                Text(if (taps.size < 4) "${taps.size}/4 taps" else "${taps.size} taps · keep going for accuracy", fontFamily = F.Body, fontSize = 12.sp, color = C.TextDim)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!playingThis) NeonButton("Play", icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f)) { vm.player.playAt(t, (t.durationMs * 0.3).toLong()) }
            NeonButton("Use tapped", color = C.Hot, enabled = tapped != null, modifier = Modifier.weight(1f)) { tapped?.let { set(it) } }
        }
        NeonButton("Save ${current?.let { EdmClassifier.fmtBpm(it) } ?: ""} BPM", filled = true, enabled = current != null && current in 40.0..260.0, modifier = Modifier.fillMaxWidth()) {
            current?.let { vm.setBpm(t, it) }; onDismiss()
        }
        if (t.bpmLocked) Text("Set by you — rescans keep it. Tap to let the scanner decide again.", fontFamily = F.Body, fontSize = 12.sp, color = C.TextDim,
            modifier = Modifier.clickable { vm.unlockBpm(t); onDismiss() })
    }
}

/** "Rescan whole track" vs "Rescan a section". */
@Composable
fun RescanChooser(onWhole: () -> Unit, onSection: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismiss) {
        Text("RESCAN", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
        NeonButton("Whole track", icon = Icons.Default.GraphicEq, filled = true, modifier = Modifier.fillMaxWidth()) { onWhole(); onDismiss() }
        NeonButton("Pick a section on the waveform", icon = Icons.Default.ContentCut, color = C.Hot, modifier = Modifier.fillMaxWidth()) { onSection(); onDismiss() }
        Text("Tip: pick a part where the drums are steady (a drop or groove), not a breakdown or intro.", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
    }
}

/** Waveform with a draggable window; analyzes just that part for BPM + key. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionPicker(vm: AppViewModel, t: TrackEntity, ui: PlayerUi, camelot: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dur = t.durationMs.coerceAtLeast(1)
    var lenSec by remember { mutableIntStateOf(30) }
    var center by remember { mutableFloatStateOf(0.45f) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AnalysisEngine.SectionResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val lenFrac = (lenSec * 1000f / dur).coerceAtMost(1f)
    val start = (center - lenFrac / 2).coerceIn(0f, 1f - lenFrac)
    val startMs = (start * dur).toLong()
    val lenMs = (lenFrac * dur).toLong()
    val progress = if (ui.currentId == t.id && ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else -1f

    Column(Modifier.fillMaxWidth().background(C.Panel, cut(12.dp)).border(1.dp, C.Hot, cut(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("TAP OR DRAG TO PICK A SECTION", fontFamily = F.Display, fontSize = 10.sp, letterSpacing = 1.5.sp, color = C.Hot)
        Canvas(
            Modifier.fillMaxWidth().height(90.dp)
                .pointerInput(lenFrac) { detectTapGestures { center = (it.x / size.width).coerceIn(0f, 1f); result = null } }
                .pointerInput(lenFrac) { detectHorizontalDragGestures { change, _ -> center = (change.position.x / size.width).coerceIn(0f, 1f); result = null } },
        ) {
            val data = t.waveform
            val n = data?.size ?: 0
            val bw = if (n > 0) size.width / n else 0f
            for (i in 0 until n) {
                val v = (data!![i].toInt() and 0xFF) / 255f
                val h = (v * size.height).coerceAtLeast(2f)
                val f = i.toFloat() / n
                val inSel = f >= start && f <= start + lenFrac
                drawRect(if (inSel) C.Hot else C.ElectricDim.copy(alpha = 0.6f), Offset(i * bw, (size.height - h) / 2), Size((bw * 0.7f).coerceAtLeast(1f), h))
            }
            drawRect(C.Hot.copy(alpha = 0.12f), Offset(start * size.width, 0f), Size(lenFrac * size.width, size.height))
            drawRect(C.Hot, Offset(start * size.width, 0f), Size(lenFrac * size.width, size.height), style = Stroke(3f))
            if (progress >= 0) drawLine(Color.White, Offset(progress * size.width, 0f), Offset(progress * size.width, size.height), 3f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${fmtTime(startMs)} – ${fmtTime(startMs + lenMs)}", fontFamily = F.Mono, color = C.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            listOf(15, 30, 60).forEach { s -> Box(Modifier.padding(start = 6.dp)) { SmallChip("${s}s", if (s == lenSec) C.Hot else C.TextDim) { lenSec = s; result = null } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("Preview", icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f)) { vm.player.playAt(t, startMs) }
            NeonButton(if (busy) "Scanning…" else "Scan section", color = C.Hot, filled = true, enabled = !busy, modifier = Modifier.weight(1f)) {
                busy = true; error = null; result = null
                scope.launch {
                    vm.analyzeSection(t, startMs, lenMs).onSuccess { result = it }.onFailure { error = it.message ?: "Scan failed" }
                    busy = false
                }
            }
        }
        if (busy) ProgressLine(null, color = C.Hot)
        error?.let { Text(it, color = C.Warn, fontFamily = F.Body) }
        result?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${EdmClassifier.fmtBpm(r.bpm)} BPM", fontFamily = F.Mono, fontSize = 28.sp, color = C.Text, modifier = Modifier.weight(1f))
                KeyBadge(r.key, camelot, big = true)
            }
            r.genreHint?.let { Text("Heard ${EdmClassifier.fmtBpm(it)}, adjusted to fit ${t.genre}", fontFamily = F.Body, fontSize = 13.sp, color = C.TextDim) }
            val others = r.candidates.filter { abs(it.first - r.bpm) / r.bpm > 0.015 }
            if (others.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Or:", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                others.forEach { (b, _) -> SmallChip(EdmClassifier.fmtBpm(b), C.Warn) { result = r.copy(bpm = b, genreHint = null) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("Use BPM", filled = true, modifier = Modifier.weight(1f)) { vm.applySection(t, r.bpm, null); onClose() }
                NeonButton("BPM + key", modifier = Modifier.weight(1f)) { vm.applySection(t, r.bpm, r.key); onClose() }
            }
            if (r.key >= 0) Text("Key here: ${MusicKey.longName(r.key)} (${(r.keyConfidence * 100).roundToInt()}% sure)", fontFamily = F.Body, fontSize = 12.sp, color = C.TextDim, textAlign = TextAlign.Start)
        }
        Text("CANCEL", fontFamily = F.Display, fontSize = 11.sp, color = C.TextDim, modifier = Modifier.align(Alignment.End).clickable(onClick = onClose).padding(6.dp))
    }
}
