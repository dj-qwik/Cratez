package com.cratez.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Save
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import com.cratez.app.ui.AppViewModel
import com.cratez.app.ui.components.Art
import com.cratez.app.ui.components.KeyBadge
import com.cratez.app.ui.components.NeonButton
import com.cratez.app.ui.components.Tag
import com.cratez.app.ui.components.Waveform
import com.cratez.app.ui.components.cut
import com.cratez.app.ui.components.cyberBackdrop
import com.cratez.app.ui.theme.C
import com.cratez.app.ui.theme.F
import com.cratez.app.viz.Viz
import com.cratez.app.viz.VizController
import com.cratez.app.viz.VizParams
import com.cratez.app.viz.VizRenderer
import com.cratez.core.genre.EdmClassifier
import kotlinx.coroutines.delay

private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
fun NowPlayingScreen(vm: AppViewModel, nav: Nav) {
    val ui by vm.player.ui.collectAsState()
    val all by vm.tracks.collectAsState()
    val settings by vm.settings.collectAsState()
    val t = all.firstOrNull { it.id == ui.currentId }
    Box(Modifier.fillMaxSize().cyberBackdrop().clickable(remember { MutableInteractionSource() }, null) {}) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.Electric) }
                Text("NOW PLAYING", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = C.TextDim, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { t?.let { nav.push(Overlay.AddToList(listOf(it.id))) } }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", tint = C.Electric) }
                IconButton(onClick = { t?.let { nav.push(Overlay.Detail(it.id)) } }) { Icon(Icons.Default.Tune, "Track info", tint = C.Electric) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Art(t?.artPath, Modifier.fillMaxWidth(0.86f).aspectRatio(1f).shadow(40.dp, cut(20.dp), ambientColor = C.Electric, spotColor = C.Electric), cutSize = 20.dp)
            }
            Text(t?.displayTitle ?: "Nothing playing", fontFamily = F.Body, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = C.Text, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
            Text(t?.displayArtist ?: "", fontFamily = F.Body, fontSize = 18.sp, color = C.Electric, maxLines = 1)
            if (t != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.genre.isNotBlank()) Tag(t.genre)
                if (t.bpm > 0) Text("${EdmClassifier.fmtBpm(t.bpm)} BPM", fontFamily = F.Mono, color = C.Text, fontSize = 14.sp)
                KeyBadge(t.musicalKey, settings.camelotKeys)
            }
            val prog = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f
            Waveform(t?.waveform, prog, Modifier.fillMaxWidth().height(70.dp).padding(top = 14.dp)) { f -> vm.player.seekTo((f * ui.durationMs).toLong()) }
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(ui.positionMs), fontFamily = F.Mono, color = C.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(fmt(ui.durationMs), fontFamily = F.Mono, color = C.TextDim, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.player.shuffle() }) { Icon(Icons.Default.Shuffle, "Shuffle", tint = if (ui.shuffle) C.Hot else C.TextDim) }
                IconButton(onClick = { vm.player.prev() }) { Icon(Icons.Default.SkipPrevious, "Previous", tint = C.Text, modifier = Modifier.size(34.dp)) }
                Box(Modifier.size(72.dp).shadow(20.dp, cut(14.dp), ambientColor = C.Electric, spotColor = C.Electric).background(C.Electric, cut(14.dp)).clickable { vm.player.toggle() },
                    contentAlignment = Alignment.Center) {
                    Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, "Next", tint = C.Text, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = { vm.player.cycleRepeat() }) {
                    Icon(if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", tint = if (ui.repeat != Player.REPEAT_MODE_OFF) C.Hot else C.TextDim)
                }
            }
            NeonButton("Visualize", icon = Icons.Default.AutoAwesome, color = C.Hot, filled = true, modifier = Modifier.fillMaxWidth()) { nav.push(Overlay.Viz) }
        }
    }
}

// ======================= Visualizer =======================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VizScreen(vm: AppViewModel, nav: Nav) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui by vm.player.ui.collectAsState()
    val all by vm.tracks.collectAsState()
    val t = all.firstOrNull { it.id == ui.currentId }
    val params by VizController.params.collectAsState()
    val presetIdx by VizController.presetIndex.collectAsState()
    val showInfo by VizController.showInfo.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    val custom by VizController.custom.collectAsState()
    val allPresets = Viz.PRESETS + custom
    val showReadout by VizController.stickReadout.collectAsState()
    var saveOpen by remember { mutableStateOf(false) }
    var stickText by remember { mutableStateOf("") }
    var lastStick by remember { mutableLongStateOf(0L) }
    val stickRadius = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(lastStick) { delay(900); if (System.currentTimeMillis() - lastStick >= 850) stickText = "" }
    fun describeSticks(): String {
        val parts = mutableListOf<String>()
        val c = VizController
        if (abs(c.lx) > 0.05f || abs(c.ly) > 0.05f) {
            parts += "SPEED ×" + "%.1f".format(Math.pow(2.0, c.lx * 1.6))
            parts += "INTENSITY " + (if (c.ly >= 0) "+" else "") + (c.ly * 100).roundToInt() + "%"
        }
        if (abs(c.rx) > 0.05f || abs(c.ry) > 0.05f) {
            parts += "SPIN " + (if (c.rx >= 0) "⟳ " else "⟲ ") + (abs(c.rx) * 100).roundToInt() + "%"
            parts += (if (c.ry >= 0) "ZOOM IN " else "ZOOM OUT ") + (abs(c.ry) * 100).roundToInt() + "%"
        }
        return parts.joinToString("   ")
    }

    // Fullscreen + free rotation while the visualizer is open.
    DisposableEffect(Unit) {
        val act = ctx as? Activity
        val win = act?.window
        val ctl = win?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        val prevOrientation = act?.requestedOrientation
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        view.keepScreenOn = true
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            act?.requestedOrientation = prevOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
        }
    }
    DisposableEffect(lifecycle, glView) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) glView?.onPause()
            if (e == Lifecycle.Event.ON_RESUME) glView?.onResume()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(lastTouch, panelOpen) {
        controlsVisible = true
        if (!panelOpen) { delay(4000); controlsVisible = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                GLSurfaceView(c).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(VizRenderer())
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glView = this
                }
            },
            onRelease = { it.onPause() },
            modifier = Modifier.fillMaxSize(),
        )
        // Touch layer. Invisible thumbsticks: a thumb dragged on the left half = ENERGY stick
        // (sideways: speed, up/down: intensity); right half = CAMERA stick (sideways: spin, up/down: zoom).
        // Wherever the thumb lands becomes the stick's centre. Taps still work: tap = controls,
        // double-tap = next preset, long-press = mashup.
        Box(Modifier.fillMaxSize().pointerInput(stickRadius) {
            awaitPointerEventScope {
                val origins = HashMap<PointerId, Pair<Offset, Boolean>>()
                val active = HashSet<PointerId>()
                val slop = viewConfiguration.touchSlop
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    for (c in ev.changes) {
                        if (c.changedToDown()) origins[c.id] = c.position to (c.position.x < size.width / 2f)
                        val o = origins[c.id] ?: continue
                        if (c.pressed) {
                            val d = c.position - o.first
                            if (c.id !in active && d.getDistance() > slop) active += c.id
                            if (c.id in active) {
                                c.consume()
                                var x = d.x / stickRadius; var y = -d.y / stickRadius
                                val m = sqrt(x * x + y * y)
                                if (m > 1f) { x /= m; y /= m }
                                if (m < 0.08f) { x = 0f; y = 0f }
                                if (o.second) { VizController.lx = x; VizController.ly = y } else { VizController.rx = x; VizController.ry = y }
                                lastStick = System.currentTimeMillis(); stickText = describeSticks()
                            }
                        } else {
                            if (c.id in active) {
                                c.consume()
                                if (o.second) VizController.releaseLeft() else VizController.releaseRight()
                                lastStick = System.currentTimeMillis()
                            }
                            active -= c.id; origins.remove(c.id)
                        }
                    }
                }
            }
        }.combinedClickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { if (controlsVisible && !panelOpen) controlsVisible = false else lastTouch = System.currentTimeMillis() },
            onDoubleClick = { VizController.next(); lastTouch = System.currentTimeMillis() },
            onLongClick = { VizController.mashup(); lastTouch = System.currentTimeMillis() },
        ))

        if (showReadout && stickText.isNotEmpty()) {
            Text(stickText, fontFamily = F.Mono, fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 64.dp)
                    .background(Color(0x55000000), cut(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (showInfo && t != null && !controlsVisible) {
            Column(Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) {
                Text(t.displayTitle, fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White.copy(alpha = 0.85f))
                Text(t.displayArtist, fontFamily = F.Body, fontSize = 14.sp, color = C.Electric.copy(alpha = 0.85f))
            }
        }

        AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                // Top bar
                Row(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                    IconButton(onClick = { VizController.prev(); lastTouch = System.currentTimeMillis() }) { Icon(Icons.Default.ChevronLeft, "Previous preset", tint = C.Electric) }
                    Text(if (presetIdx in allPresets.indices) (if (VizController.isCustom(presetIdx)) "★ " else "") + allPresets[presetIdx].name.uppercase() else "CUSTOM MIX", fontFamily = F.Display, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, letterSpacing = 1.sp, color = C.Electric, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { VizController.next(); lastTouch = System.currentTimeMillis() }) { Icon(Icons.Default.ChevronRight, "Next preset", tint = C.Electric) }
                    IconButton(onClick = { VizController.mashup(); lastTouch = System.currentTimeMillis() }) { Icon(Icons.Default.Casino, "Mashup", tint = C.Hot) }
                    IconButton(onClick = { saveOpen = true; lastTouch = System.currentTimeMillis() }) { Icon(Icons.Default.Save, "Save preset", tint = Color.White) }
                    IconButton(onClick = { panelOpen = !panelOpen; lastTouch = System.currentTimeMillis() }) { Icon(Icons.Default.Tune, "Controls", tint = if (panelOpen) C.Hot else Color.White) }
                }
                // Bottom transport
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t?.displayTitle ?: "Play something to feed the visuals", maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = F.Body, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(t?.displayArtist ?: "", maxLines = 1, fontFamily = F.Body, color = C.Electric, fontSize = 13.sp)
                    }
                    IconButton(onClick = { vm.player.prev() }) { Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White) }
                    IconButton(onClick = { vm.player.toggle() }) { Icon(if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = C.Electric, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, "Next", tint = Color.White) }
                }
                if (panelOpen) VizPanel(params, Modifier.align(Alignment.CenterEnd).padding(top = 60.dp, bottom = 60.dp), { saveOpen = true }) { lastTouch = System.currentTimeMillis() }
                if (!panelOpen) Text("Thumbs: left = speed/intensity · right = spin/zoom\nTap: controls · Double-tap: next · Hold: mashup", textAlign = TextAlign.Center, fontFamily = F.Mono, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
            }
        }
        if (saveOpen) {
            var name by remember { mutableStateOf(if (VizController.isCustom(presetIdx)) allPresets[presetIdx].name else "") }
            Dialog({ saveOpen = false }) {
                Text("SAVE PRESET", fontFamily = F.Display, fontWeight = FontWeight.Bold, color = C.Electric)
                Text("Saves exactly what's on screen now, including your thumbstick moves.", fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
                Field(name, { name = it }, "Preset name")
                NeonButton("Save", filled = true, modifier = Modifier.fillMaxWidth()) {
                    VizController.saveCustom(name); saveOpen = false; lastTouch = System.currentTimeMillis()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VizPanel(p: VizParams, modifier: Modifier, onSave: () -> Unit, touched: () -> Unit) {
    val custom by VizController.custom.collectAsState()
    val stickKeep by VizController.stickKeep.collectAsState()
    val stickReadout by VizController.stickReadout.collectAsState()
    val auto by VizController.autoCycle.collectAsState()
    val cycle by VizController.cycleSec.collectAsState()
    val onDrop by VizController.cycleOnDrop.collectAsState()
    val cycleMode by VizController.cycleMode.collectAsState()
    val showInfo by VizController.showInfo.collectAsState()
    val presetIdx by VizController.presetIndex.collectAsState()
    fun set(f: (VizParams) -> VizParams) { VizController.update(f); touched() }
    Column(
        modifier.widthIn(max = 380.dp).fillMaxWidth(0.92f).fillMaxHeight().background(Color(0xE6060A10), cut(14.dp)).border(1.dp, C.Electric, cut(14.dp))
            .clickable(remember { MutableInteractionSource() }, null) { touched() }
            .verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Big, obvious randomize — the fastest way to new looks.
        NeonButton("🎲  Randomize everything", color = C.Hot, filled = true, modifier = Modifier.fillMaxWidth()) { VizController.mashup(); touched() }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("🎨 Colors", false) { VizController.randomizeColors(); touched() }
            Chip("🌀 Motion", false) { VizController.randomizeMotion(); touched() }
            Chip("〰 Waves", false) { VizController.randomizeWaves(); touched() }
        }
        Text("Like what you see? Tap 💾 to keep it.", fontFamily = F.Body, color = C.TextDim, fontSize = 12.sp)

        PanelHead("Presets (${Viz.PRESETS.size + custom.size})")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            itemsIndexed(Viz.PRESETS + custom) { i, pr -> Chip(if (VizController.isCustom(i)) "★ ${pr.name}" else pr.name, i == presetIdx) { VizController.select(i); touched() } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("💾 Save as preset", false) { onSave(); touched() }
            Chip("🎲 Random", false) { VizController.random(); touched() }
            Chip("⚡ Mashup", false) { VizController.mashup(); touched() }
            Chip("↺ Reset", false) { VizController.reset(); touched() }
            if (VizController.isCustom(presetIdx)) Chip("🗑 Delete this preset", false) { VizController.deleteCustom(presetIdx); touched() }
        }

        PanelHead("Thumbsticks")
        Text("Drag a thumb anywhere on screen. LEFT side: sideways = speed, up/down = intensity (warp, audio punch, trails). RIGHT side: sideways = spin, up/down = zoom.",
            fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
        ToggleRow("Changes stay when I let go", stickKeep) { VizController.setStickKeep(it); touched() }
        ToggleRow("Show stick readout", stickReadout) { VizController.setStickReadout(it); touched() }
        ToggleRow("Auto-cycle presets", auto) { VizController.setAutoCycle(it); touched() }
        if (auto) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(10, 20, 30, 60, 120).forEach { s -> Chip("${s}s", cycle == s) { VizController.setCycleSec(s); touched() } } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Presets", "Random looks", "Both").forEachIndexed { i, l -> Chip(l, cycleMode == i) { VizController.setCycleMode(i); touched() } }
            }
            ToggleRow("Also switch on big drops", onDrop) { VizController.setCycleOnDrop(it); touched() }
        }

        PanelHead("Motion")
        Choice("Warp", Viz.WARP_MODES, p.warpMode) { v -> set { it.copy(warpMode = v) } }
        SliderRow("Speed", p.speed, 0.1f..3f) { v -> set { it.copy(speed = v) } }
        SliderRow("Zoom", p.zoom, 0.94f..1.10f) { v -> set { it.copy(zoom = v) } }
        SliderRow("Rotation", p.rot, -0.03f..0.03f) { v -> set { it.copy(rot = v) } }
        SliderRow("Warp amount", p.warp, -2f..2.5f) { v -> set { it.copy(warp = v) } }
        SliderRow("Drift", p.drift, 0f..2f) { v -> set { it.copy(drift = v) } }
        SliderRow("Trails", p.decay, 0.80f..0.995f) { v -> set { it.copy(decay = v) } }

        PanelHead("Audio")
        SliderRow("Reactivity", p.react, 0f..2.5f) { v -> set { it.copy(react = v) } }
        SliderRow("Bass zoom punch", p.zoomPulse, 0f..3f) { v -> set { it.copy(zoomPulse = v) } }
        Choice("Wave", Viz.WAVE_MODES, p.waveMode) { v -> set { it.copy(waveMode = v) } }
        SliderRow("Wave size", p.waveSize, 0.2f..2.5f) { v -> set { it.copy(waveSize = v) } }
        SliderRow("Line thickness", p.thickness, 1f..10f) { v -> set { it.copy(thickness = v) } }
        Choice("Shapes", listOf("Off", "Triangle", "Square", "Pentagon", "Hexagon", "Octagon"), when (p.shapeSides) { 3 -> 1; 4 -> 2; 5 -> 3; 6 -> 4; 8 -> 5; else -> 0 }) { v ->
            set { it.copy(shapeSides = listOf(0, 3, 4, 5, 6, 8)[v]) }
        }
        ToggleRow("Particles", p.particles) { v -> set { it.copy(particles = v) } }

        PanelHead("Color & FX")
        Choice("Palette", Viz.PALETTES.map { it.name }, p.palette) { v -> set { it.copy(palette = v) } }
        SliderRow("Color cycle", p.hueSpeed, 0f..3f) { v -> set { it.copy(hueSpeed = v) } }
        Choice("Kaleidoscope", listOf("Off", "3", "4", "5", "6", "8", "10", "12", "16"), listOf(0, 3, 4, 5, 6, 8, 10, 12, 16).indexOf(p.kaleido).coerceAtLeast(0)) { v ->
            set { it.copy(kaleido = listOf(0, 3, 4, 5, 6, 8, 10, 12, 16)[v]) }
        }
        Choice("Mirror", Viz.MIRRORS, p.mirror) { v -> set { it.copy(mirror = v) } }
        SliderRow("Glow", p.glow, 0f..1.5f) { v -> set { it.copy(glow = v) } }
        SliderRow("Chromatic split", p.chroma, 0f..1.5f) { v -> set { it.copy(chroma = v) } }
        SliderRow("Plasma seed", p.seed, 0f..1.5f) { v -> set { it.copy(seed = v) } }
        ToggleRow("Scanlines", p.scanlines) { v -> set { it.copy(scanlines = v) } }
        ToggleRow("Beat flash", p.beatFlash) { v -> set { it.copy(beatFlash = v) } }
        ToggleRow("Invert on beat (intense)", p.invertBeat) { v -> set { it.copy(invertBeat = v) } }
        ToggleRow("Show track title", showInfo) { VizController.setShowInfo(it); touched() }
        Box(Modifier.height(10.dp))
    }
}

@Composable private fun PanelHead(t: String) = Text(t.uppercase(), fontFamily = F.Display, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp, color = C.Hot, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, fontFamily = F.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (selected) Color.Black else C.Text, maxLines = 1,
        modifier = Modifier.background(if (selected) C.Electric else C.PanelHi, cut(5.dp)).border(1.dp, if (selected) C.Electric else C.Line, cut(5.dp))
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Text(label, fontFamily = F.Body, color = C.TextDim, fontSize = 13.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.forEachIndexed { i, o -> Chip(o, i == selected) { onPick(i) } }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = F.Body, color = C.Text, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.weight(1f).height(30.dp),
            colors = SliderDefaults.colors(thumbColor = C.Electric, activeTrackColor = C.Electric, inactiveTrackColor = C.Line))
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = F.Body, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = C.Electric, uncheckedTrackColor = C.PanelHi, uncheckedBorderColor = C.Line))
    }
}
