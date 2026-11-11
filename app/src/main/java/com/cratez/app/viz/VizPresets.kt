package com.cratez.app.viz

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** Every knob of the visualizer. All of them are live-tweakable from the overlay. */
data class VizParams(
    val warpMode: Int = 0,
    val waveMode: Int = 0,
    val palette: Int = 0,
    val kaleido: Int = 0,
    val mirror: Int = 0,
    val zoom: Float = 1.02f,
    val rot: Float = 0.004f,
    val warp: Float = 1f,
    val decay: Float = 0.94f,
    val hueSpeed: Float = 0.5f,
    val speed: Float = 1f,
    val react: Float = 1f,
    val waveSize: Float = 1f,
    val thickness: Float = 3f,
    val glow: Float = 0.3f,
    val chroma: Float = 0.2f,
    val seed: Float = 0.2f,
    val shapeSides: Int = 0,
    val zoomPulse: Float = 1f,
    val drift: Float = 0.5f,
    val scanlines: Boolean = false,
    val beatFlash: Boolean = true,
    val invertBeat: Boolean = false,
    val particles: Boolean = false,
)

data class VizPreset(val name: String, val p: VizParams)

data class Palette(val name: String, val a: FloatArray, val b: FloatArray, val c: FloatArray, val d: FloatArray)

object Viz {
    val WARP_MODES = listOf("Tunnel", "Spiral", "Liquid", "Ripple", "Fold", "Vortex", "Rise / Rain", "Bulge", "Swirl Noise")
    val WAVE_MODES = listOf("Ring", "Scope", "Radial Spectrum", "Spectrum Bars", "Lissajous", "Starburst", "None", "Twin Rings")
    val MIRRORS = listOf("Off", "Mirror", "Quad")

    private fun v(vararg f: Float) = f
    val PALETTES = listOf(
        Palette("Natural", v(0f, 0f, 0f), v(0f, 0f, 0f), v(0f, 0f, 0f), v(0f, 0f, 0f)),
        Palette("Rainbow", v(.5f, .5f, .5f), v(.5f, .5f, .5f), v(1f, 1f, 1f), v(0f, .33f, .67f)),
        Palette("Cyber Neon", v(.5f, .5f, .75f), v(.5f, .5f, .25f), v(1f, 1f, 1f), v(.85f, .35f, .55f)),
        Palette("Sunset", v(.5f, .5f, .5f), v(.5f, .5f, .5f), v(1f, 1f, 1f), v(0f, .1f, .2f)),
        Palette("Acid", v(.5f, .5f, .5f), v(.5f, .5f, .5f), v(1f, 1f, .5f), v(.8f, .9f, .3f)),
        Palette("Fire", v(.5f, .5f, .5f), v(.5f, .5f, .5f), v(1f, .7f, .4f), v(0f, .15f, .2f)),
        Palette("Vaporwave", v(.5f, .5f, .5f), v(.5f, .5f, .5f), v(2f, 1f, 0f), v(.5f, .2f, .25f)),
        Palette("Electric Blue", v(.08f, .42f, .75f), v(.08f, .42f, .25f), v(1f, 1f, 1f), v(0f, 0f, 0f)),
        Palette("Off-World", v(.5f, .45f, .4f), v(.5f, .35f, .3f), v(1f, 1f, 1f), v(0f, .15f, .55f)),
        Palette("Toxic", v(.8f, .5f, .4f), v(.2f, .4f, .2f), v(2f, 1f, 1f), v(0f, .25f, .25f)),
        Palette("Ice", v(.55f, .75f, .9f), v(.35f, .25f, .15f), v(1f, 1f, 1f), v(.5f, .6f, .7f)),
        Palette("Blood Moon", v(.45f, .08f, .1f), v(.45f, .08f, .1f), v(1f, 1f, 1f), v(0f, 0f, 0f)),
    )

    val PRESETS = listOf(
        VizPreset("Neon Tunnel", VizParams(warpMode = 0, waveMode = 0, palette = 2, zoom = 1.03f, rot = 0.006f, decay = 0.95f, seed = 0.1f)),
        VizPreset("Liquid Chrome", VizParams(warpMode = 2, waveMode = 1, palette = 10, zoom = 1.005f, rot = 0f, warp = 1.4f, decay = 0.96f)),
        VizPreset("Acid Spiral", VizParams(warpMode = 1, waveMode = 2, palette = 4, zoom = 1.02f, rot = 0.01f, warp = 1.2f, decay = 0.94f)),
        VizPreset("Kaleido Bloom", VizParams(warpMode = 3, waveMode = 0, palette = 1, kaleido = 8, zoom = 1.01f, decay = 0.93f)),
        VizPreset("Off-World Rain", VizParams(warpMode = 6, waveMode = 3, palette = 8, warp = -1.2f, zoom = 1.0f, rot = 0f, decay = 0.95f, scanlines = true)),
        VizPreset("Wormhole", VizParams(warpMode = 5, waveMode = 4, palette = 6, zoom = 1.04f, decay = 0.95f)),
        VizPreset("Hyperdrive", VizParams(warpMode = 0, waveMode = 5, palette = 7, zoom = 1.07f, rot = 0f, decay = 0.9f, seed = 0f, particles = true)),
        VizPreset("Fractal Mirror", VizParams(warpMode = 4, waveMode = 0, palette = 1, kaleido = 6, zoom = 1.02f, rot = 0.008f)),
        VizPreset("Plasma Core", VizParams(warpMode = 7, waveMode = 6, palette = 5, seed = 0.9f, decay = 0.9f, warp = 1.5f, shapeSides = 6)),
        VizPreset("Glitch City", VizParams(warpMode = 2, waveMode = 1, palette = 2, chroma = 0.9f, scanlines = true, warp = 0.6f, decay = 0.9f)),
        VizPreset("Bass Quake", VizParams(warpMode = 3, waveMode = 2, palette = 11, warp = 2f, zoomPulse = 2f, decay = 0.93f)),
        VizPreset("Laser Grid", VizParams(warpMode = 0, waveMode = 3, palette = 9, mirror = 2, zoom = 0.99f, decay = 0.9f)),
        VizPreset("Deep Space", VizParams(warpMode = 1, waveMode = 5, palette = 10, zoom = 1.01f, rot = 0.002f, decay = 0.97f, warp = 0.4f, particles = true)),
        VizPreset("Mandala", VizParams(warpMode = 1, waveMode = 7, palette = 1, kaleido = 12, decay = 0.94f)),
        VizPreset("Electric Feel", VizParams(warpMode = 6, waveMode = 4, palette = 5, warp = 1.2f, decay = 0.95f)),
        VizPreset("Synth Sun", VizParams(warpMode = 7, waveMode = 3, palette = 6, mirror = 1, decay = 0.92f, shapeSides = 3)),
        VizPreset("Cyber Lotus", VizParams(warpMode = 8, waveMode = 0, palette = 2, kaleido = 5, decay = 0.95f, shapeSides = 5)),
        VizPreset("Zero Gravity", VizParams(warpMode = 8, waveMode = 4, palette = 7, zoom = 1.0f, rot = 0.003f, drift = 1.5f, decay = 0.97f)),
        VizPreset("Chrome Vortex", VizParams(warpMode = 5, waveMode = 7, palette = 10, kaleido = 4)),
        VizPreset("Strobe Temple", VizParams(warpMode = 0, waveMode = 2, palette = 3, kaleido = 3, beatFlash = true, glow = 0.7f, shapeSides = 4)),
        VizPreset("Hologram", VizParams(warpMode = 4, waveMode = 1, palette = 7, mirror = 1, chroma = 0.6f, scanlines = true, decay = 0.92f)),
        VizPreset("Supernova", VizParams(warpMode = 0, waveMode = 5, palette = 3, zoom = 0.97f, decay = 0.93f, particles = true, glow = 0.8f)),
    )

    fun mashup(r: Random = Random.Default) = VizParams(
        warpMode = r.nextInt(WARP_MODES.size), waveMode = r.nextInt(WAVE_MODES.size), palette = r.nextInt(PALETTES.size),
        kaleido = listOf(0, 0, 0, 3, 4, 5, 6, 8, 12).random(r), mirror = listOf(0, 0, 0, 1, 2).random(r),
        zoom = 0.97f + r.nextFloat() * 0.09f, rot = (r.nextFloat() - 0.5f) * 0.02f, warp = 0.3f + r.nextFloat() * 1.7f,
        decay = 0.88f + r.nextFloat() * 0.09f, hueSpeed = r.nextFloat() * 1.5f, waveSize = 0.6f + r.nextFloat() * 1f,
        thickness = 2f + r.nextFloat() * 4f, glow = r.nextFloat() * 0.8f, chroma = r.nextFloat() * 0.7f, seed = r.nextFloat() * 0.6f,
        shapeSides = listOf(0, 0, 3, 4, 5, 6, 8).random(r), zoomPulse = 0.5f + r.nextFloat() * 1.5f, drift = r.nextFloat() * 1.5f,
        scanlines = r.nextInt(4) == 0, particles = r.nextInt(3) == 0,
    )
}

/** Holds what the visualizer is showing; shared by the GL renderer and the Compose controls. */
object VizController {
    private var prefs: SharedPreferences? = null
    private val _params = MutableStateFlow(Viz.PRESETS[0].p)
    val params: StateFlow<VizParams> = _params
    private val _presetIndex = MutableStateFlow(0)
    /** Index into [all]; -1 = unsaved mashup / edits. */
    val presetIndex: StateFlow<Int> = _presetIndex
    private val _custom = MutableStateFlow<List<VizPreset>>(emptyList())
    val custom: StateFlow<List<VizPreset>> = _custom
    private val _autoCycle = MutableStateFlow(false)
    val autoCycle: StateFlow<Boolean> = _autoCycle
    private val _cycleSec = MutableStateFlow(30)
    val cycleSec: StateFlow<Int> = _cycleSec
    private val _cycleOnDrop = MutableStateFlow(true)
    val cycleOnDrop: StateFlow<Boolean> = _cycleOnDrop
    private val _showInfo = MutableStateFlow(true)
    val showInfo: StateFlow<Boolean> = _showInfo
    private val _stickKeep = MutableStateFlow(false)
    /** false = sticks bounce back when released, true = changes stay. */
    val stickKeep: StateFlow<Boolean> = _stickKeep
    private val _stickReadout = MutableStateFlow(true)
    val stickReadout: StateFlow<Boolean> = _stickReadout
    @Volatile var lastChange = System.currentTimeMillis()

    // ---- Thumbsticks: targets set by touch (-1..1, up = +y), smoothed values owned by the renderer ----
    @Volatile var lx = 0f; @Volatile var ly = 0f; @Volatile var rx = 0f; @Volatile var ry = 0f
    @Volatile var slx = 0f; @Volatile var sly = 0f; @Volatile var srx = 0f; @Volatile var sry = 0f

    fun all(): List<VizPreset> = Viz.PRESETS + _custom.value
    fun isCustom(i: Int) = i >= Viz.PRESETS.size

    fun init(ctx: Context) {
        if (prefs != null) return
        val sp = ctx.getSharedPreferences("viz", Context.MODE_PRIVATE)
        prefs = sp
        _custom.value = runCatching {
            val arr = JSONArray(sp.getString("custom", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> VizPreset(o.getString("name"), fromJson(o.getJSONObject("p"))) } }
        }.getOrDefault(emptyList())
        _stickKeep.value = sp.getBoolean("stickKeep", false)
        _stickReadout.value = sp.getBoolean("stickReadout", true)
        val last = sp.getInt("last", 0)
        if (last in all().indices) select(last)
    }

    /**
     * Thumbstick mapping. Left stick = ENERGY (x: speed, y: intensity = warp + audio punch + trails).
     * Right stick = CAMERA (x: spin, y: zoom / fly forward).
     */
    fun applySticks(p: VizParams, lx: Float, ly: Float, rx: Float, ry: Float) = p.copy(
        speed = (p.speed * 2f.pow(lx * 1.6f)).coerceIn(0.05f, 6f),
        warp = (p.warp * (1f + ly * 1.2f) + ly * 0.5f).coerceIn(-3f, 4f),
        react = (p.react * (1f + ly * 0.7f)).coerceIn(0f, 3f),
        decay = (p.decay + ly * 0.035f).coerceIn(0.8f, 0.995f),
        zoom = (p.zoom + ry * 0.05f).coerceIn(0.9f, 1.15f),
        rot = (p.rot + rx * 0.025f).coerceIn(-0.06f, 0.06f),
    )

    fun releaseLeft() {
        if (_stickKeep.value) { _params.value = applySticks(_params.value, slx, sly, 0f, 0f); slx = 0f; sly = 0f; _presetIndex.value = -1 }
        lx = 0f; ly = 0f
    }
    fun releaseRight() {
        if (_stickKeep.value) { _params.value = applySticks(_params.value, 0f, 0f, srx, sry); srx = 0f; sry = 0f; _presetIndex.value = -1 }
        rx = 0f; ry = 0f
    }

    fun select(i: Int) {
        val list = all()
        val idx = ((i % list.size) + list.size) % list.size
        _presetIndex.value = idx; _params.value = list[idx].p; lastChange = System.currentTimeMillis()
        prefs?.edit()?.putInt("last", idx)?.apply()
    }
    fun next() = select(_presetIndex.value + 1)
    fun prev() = select(if (_presetIndex.value < 0) 0 else _presetIndex.value - 1)
    fun random() = select(Random.nextInt(all().size))
    fun mashup() { _presetIndex.value = -1; _params.value = Viz.mashup(); lastChange = System.currentTimeMillis() }
    fun reset() { val i = _presetIndex.value; if (i >= 0) _params.value = all()[i].p }
    fun update(f: (VizParams) -> VizParams) { _params.value = f(_params.value) }
    fun setAutoCycle(v: Boolean) { _autoCycle.value = v; lastChange = System.currentTimeMillis() }
    fun setCycleSec(v: Int) { _cycleSec.value = v }
    fun setCycleOnDrop(v: Boolean) { _cycleOnDrop.value = v }
    fun setShowInfo(v: Boolean) { _showInfo.value = v }
    fun setStickKeep(v: Boolean) { _stickKeep.value = v; prefs?.edit()?.putBoolean("stickKeep", v)?.apply() }
    fun setStickReadout(v: Boolean) { _stickReadout.value = v; prefs?.edit()?.putBoolean("stickReadout", v)?.apply() }

    /** Saves what's on screen right now (including any thumbstick moves) as a custom preset. */
    fun saveCustom(name: String) {
        val n = name.trim().take(28).ifBlank { "My preset ${_custom.value.size + 1}" }
        val p = applySticks(_params.value, slx, sly, srx, sry)
        val list = _custom.value.filter { it.name != n } + VizPreset(n, p)
        _custom.value = list
        persist()
        select(Viz.PRESETS.size + list.indexOfFirst { it.name == n })
    }

    fun deleteCustom(i: Int) {
        if (!isCustom(i)) return
        val ci = i - Viz.PRESETS.size
        _custom.value = _custom.value.filterIndexed { idx, _ -> idx != ci }
        persist()
        select(0)
    }

    private fun persist() {
        val arr = JSONArray()
        _custom.value.forEach { arr.put(JSONObject().put("name", it.name).put("p", toJson(it.p))) }
        prefs?.edit()?.putString("custom", arr.toString())?.apply()
    }

    private fun toJson(p: VizParams) = JSONObject().apply {
        put("warpMode", p.warpMode); put("waveMode", p.waveMode); put("palette", p.palette); put("kaleido", p.kaleido); put("mirror", p.mirror)
        put("zoom", p.zoom.toDouble()); put("rot", p.rot.toDouble()); put("warp", p.warp.toDouble()); put("decay", p.decay.toDouble())
        put("hueSpeed", p.hueSpeed.toDouble()); put("speed", p.speed.toDouble()); put("react", p.react.toDouble()); put("waveSize", p.waveSize.toDouble())
        put("thickness", p.thickness.toDouble()); put("glow", p.glow.toDouble()); put("chroma", p.chroma.toDouble()); put("seed", p.seed.toDouble())
        put("shapeSides", p.shapeSides); put("zoomPulse", p.zoomPulse.toDouble()); put("drift", p.drift.toDouble())
        put("scanlines", p.scanlines); put("beatFlash", p.beatFlash); put("invertBeat", p.invertBeat); put("particles", p.particles)
    }

    private fun fromJson(o: JSONObject): VizParams {
        val d = VizParams()
        fun f(k: String, def: Float) = o.optDouble(k, def.toDouble()).toFloat()
        return VizParams(
            warpMode = o.optInt("warpMode", d.warpMode), waveMode = o.optInt("waveMode", d.waveMode), palette = o.optInt("palette", d.palette),
            kaleido = o.optInt("kaleido", d.kaleido), mirror = o.optInt("mirror", d.mirror), zoom = f("zoom", d.zoom), rot = f("rot", d.rot),
            warp = f("warp", d.warp), decay = f("decay", d.decay), hueSpeed = f("hueSpeed", d.hueSpeed), speed = f("speed", d.speed),
            react = f("react", d.react), waveSize = f("waveSize", d.waveSize), thickness = f("thickness", d.thickness), glow = f("glow", d.glow),
            chroma = f("chroma", d.chroma), seed = f("seed", d.seed), shapeSides = o.optInt("shapeSides", d.shapeSides),
            zoomPulse = f("zoomPulse", d.zoomPulse), drift = f("drift", d.drift), scanlines = o.optBoolean("scanlines", d.scanlines),
            beatFlash = o.optBoolean("beatFlash", d.beatFlash), invertBeat = o.optBoolean("invertBeat", d.invertBeat), particles = o.optBoolean("particles", d.particles),
        )
    }

    /** Called by the renderer every frame. */
    fun tick(bigDrop: Boolean) {
        if (!_autoCycle.value) return
        val since = System.currentTimeMillis() - lastChange
        if (since > _cycleSec.value * 1000L || (_cycleOnDrop.value && bigDrop && since > 8000)) {
            if (Random.nextInt(5) == 0) mashup() else random()
        }
    }
}
