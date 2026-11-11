package com.cratez.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.core.music.MusicKey
import java.io.File

fun cut(d: Dp = 10.dp) = CutCornerShape(topStart = 0.dp, topEnd = d, bottomEnd = 0.dp, bottomStart = d)

/** Angular panel with a thin neon edge. */
@Composable
fun Panel(modifier: Modifier = Modifier, edge: Color = C.Line, cutSize: Dp = 12.dp, content: @Composable () -> Unit) {
    Box(modifier.clip(cut(cutSize)).background(C.Panel).border(1.dp, edge, cut(cutSize))) { content() }
}

@Composable
fun NeonButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = C.Electric,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = cut(9.dp)
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .shadow(if (filled) 14.dp else 0.dp, shape, ambientColor = color, spotColor = color)
            .clip(shape)
            .background(if (filled) color else color.copy(alpha = 0.08f))
            .border(1.dp, color, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (filled) Color.Black else color, modifier = Modifier.size(18.dp)); if (text.isNotEmpty()) Box(Modifier.width(8.dp)) }
        if (text.isNotEmpty()) Text(text.uppercase(), fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp,
            color = if (filled) Color.Black else color, maxLines = 1)
    }
}

@Composable
fun Tag(text: String, color: Color = C.Electric, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val shape = cut(5.dp)
    Text(
        text.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontFamily = F.Display, fontWeight = FontWeight.Medium, fontSize = 9.sp, letterSpacing = 1.sp, color = color,
        modifier = modifier.clip(shape).background(color.copy(alpha = 0.1f)).border(1.dp, color.copy(alpha = 0.6f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
fun KeyBadge(key: Int, camelot: Boolean, modifier: Modifier = Modifier, big: Boolean = false) {
    if (key < 0) { Text("--", fontFamily = F.Mono, color = C.TextDim, fontSize = if (big) 26.sp else 14.sp, modifier = modifier); return }
    val c = Color(MusicKey.wheelColor(key))
    val shape = cut(if (big) 8.dp else 4.dp)
    Text(
        if (camelot) MusicKey.camelot(key) else MusicKey.shortName(key),
        fontFamily = F.Mono, fontSize = if (big) 26.sp else 13.sp, color = Color.Black, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(shape).background(c).padding(horizontal = if (big) 12.dp else 5.dp, vertical = if (big) 4.dp else 1.dp),
    )
}

@Composable
fun EnergyBar(energy: Int, modifier: Modifier = Modifier, segH: Dp = 10.dp, segW: Dp = 3.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..10) {
            val on = i <= energy
            val col = when { i >= 9 -> C.Hot; i >= 7 -> C.Warn; else -> C.Electric }
            Box(Modifier.width(segW).height(segH * (0.4f + 0.06f * i)).background(if (on) col else C.Line))
        }
    }
}

@Composable
fun Art(path: String?, modifier: Modifier = Modifier, cutSize: Dp = 8.dp) {
    Box(modifier.clip(cut(cutSize)).background(Brush.linearGradient(listOf(C.PanelHi, C.ElectricDim.copy(alpha = 0.4f)))), contentAlignment = Alignment.Center) {
        if (path != null) AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.MusicNote, null, tint = C.Electric.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Waveform(data: ByteArray?, progress: Float, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null) {
    Canvas(modifier.let { m ->
        if (onSeek == null) m else m.then(Modifier.pointerSeek(onSeek))
    }) {
        val n = data?.size ?: 0
        if (n == 0) { drawLine(C.Line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f); return@Canvas }
        val bw = size.width / n
        for (i in 0 until n) {
            val v = (data!![i].toInt() and 0xFF) / 255f
            val hgt = (v * size.height).coerceAtLeast(2f)
            val played = i.toFloat() / n <= progress
            drawRect(if (played) C.Electric else C.ElectricDim.copy(alpha = 0.7f), Offset(i * bw, (size.height - hgt) / 2), Size((bw * 0.7f).coerceAtLeast(1f), hgt))
        }
        if (progress > 0f) drawLine(Color.White, Offset(size.width * progress, 0f), Offset(size.width * progress, size.height), 3f)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = C.Electric) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color))
        Box(Modifier.width(8.dp))
        Text(text.uppercase(), fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp, color = color)
        Box(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), Color.Transparent))))
    }
}

/** The CRATEZ wordmark with an occasional chromatic glitch. */
@Composable
fun Logo(modifier: Modifier = Modifier, size: Int = 26) {
    val tr = rememberInfiniteTransition(label = "glitch")
    val g by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "g")
    val jitter = if (g in 0.92f..0.95f || g in 0.97f..0.98f) 3f else 0f
    Box(modifier) {
        Text("CRATEZ", fontFamily = F.Display, fontWeight = FontWeight.Black, fontSize = size.sp, letterSpacing = 6.sp, color = C.Hot.copy(alpha = 0.7f), modifier = Modifier.offset(x = (-jitter).dp))
        Text("CRATEZ", fontFamily = F.Display, fontWeight = FontWeight.Black, fontSize = size.sp, letterSpacing = 6.sp, color = C.Electric.copy(alpha = 0.8f), modifier = Modifier.offset(x = jitter.dp))
        Text("CRATEZ", fontFamily = F.Display, fontWeight = FontWeight.Black, fontSize = size.sp, letterSpacing = 6.sp, color = C.Text)
    }
}

/** Subtle scanlines + top glow over the whole app. */
fun Modifier.cyberBackdrop(): Modifier = this.drawBehind {
    drawRect(C.Bg)
    drawRect(Brush.radialGradient(listOf(C.Electric.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.width * 0.9f))
    val step = 46f
    var x = 0f
    while (x < size.width) { drawLine(C.Electric.copy(alpha = 0.035f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f
    while (y < size.height) { drawLine(C.Electric.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

@Composable
fun StatTile(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Panel(modifier) {
        androidx.compose.foundation.layout.Column(Modifier.padding(10.dp)) {
            Text(label.uppercase(), fontFamily = F.Display, fontSize = 9.sp, letterSpacing = 1.5.sp, color = C.TextDim)
            Box(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier, color: Color = C.Electric) {
    val tr = rememberInfiniteTransition(label = "pl")
    val x by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    Box(modifier.fillMaxWidth().height(3.dp).background(C.Line)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(color))
        else Canvas(Modifier.fillMaxSize()) {
            val w = size.width * 0.3f
            drawRect(color, Offset(size.width * x * 1.3f - w, 0f), Size(w, size.height))
        }
    }
}

fun Modifier.pointerSeek(onSeek: (Float) -> Unit): Modifier = this
    .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
    .pointerInput("drag") { detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } }
