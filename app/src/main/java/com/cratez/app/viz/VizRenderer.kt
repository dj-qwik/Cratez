package com.cratez.app.viz

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.util.Log
import com.cratez.app.player.AudioFrame
import com.cratez.app.player.AudioTap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MilkDrop-style renderer: each frame the previous frame is warped (zoom / rotate / per-pixel
 * distortion) and faded, new audio-reactive waves and shapes are drawn on top, then a composite
 * pass adds kaleidoscope, mirroring, palettes, glow, chromatic aberration and scanlines.
 */
class VizRenderer : GLSurfaceView.Renderer {
    private val audio = AudioFrame()
    private var w = 1; private var h = 1
    private var fw = 1; private var fh = 1
    private val tex = IntArray(2); private val fbo = IntArray(2)
    private var cur = 0
    private var warpProg = 0; private var compProg = 0; private var lineProg = 0
    private lateinit var quad: FloatBuffer
    private val verts = FloatArray(6 * 24000)
    private var vn = 0
    private lateinit var vbuf: FloatBuffer
    private var time = 0.0
    private var last = 0L
    private var hue = 0f
    private val rnd = Random(7)

    // Smoothed numeric params for preset morphing.
    private var sZoom = 1.02f; private var sRot = 0f; private var sWarp = 1f; private var sDecay = 0.94f
    private var sGlow = 0.3f; private var sChroma = 0.2f; private var sSeed = 0.2f; private var sWave = 1f

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var hue: Float)
    private val particles = ArrayList<Particle>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        warpProg = program(VS_QUAD, FS_WARP)
        compProg = program(VS_QUAD, FS_COMP)
        lineProg = program(VS_LINE, FS_LINE)
        quad = fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        vbuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        glGenTextures(2, tex, 0); glGenFramebuffers(2, fbo, 0)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        val scale = min(1f, 900f / max(width, height))
        fw = max(2, (width * scale).toInt()); fh = max(2, (height * scale).toInt())
        for (i in 0..1) {
            glBindTexture(GL_TEXTURE_2D, tex[i])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fw, fh, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0)
            glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9).toFloat().coerceIn(0.001f, 0.1f)
        last = now
        // Thumbsticks glide toward their targets (and back to centre when released).
        val ks = 1f - Math.pow(0.0005, dt.toDouble()).toFloat()
        VizController.slx += (VizController.lx - VizController.slx) * ks
        VizController.sly += (VizController.ly - VizController.sly) * ks
        VizController.srx += (VizController.rx - VizController.srx) * ks
        VizController.sry += (VizController.ry - VizController.sry) * ks
        val p = VizController.params.value
        val stickP = VizController.applySticks(p, VizController.slx, VizController.sly, VizController.srx, VizController.sry)
        time += dt * stickP.speed
        val t = time.toFloat()
        AudioTap.analyze(audio)
        val react = stickP.react
        val bass = 1f + (audio.bassAtt - 1f) * react
        val mid = 1f + (audio.midAtt - 1f) * react
        val treb = 1f + (audio.trebAtt - 1f) * react
        val beat = audio.beat * react
        VizController.tick(audio.beat > 0.9f && audio.bassAtt > 2f)

        val k = 1f - Math.pow(0.02, dt.toDouble()).toFloat()  // ~1.2 s morph
        sZoom += (p.zoom - sZoom) * k; sRot += (p.rot - sRot) * k; sWarp += (p.warp - sWarp) * k; sDecay += (p.decay - sDecay) * k
        // Sticks act on top of the (morphing) preset values, so they respond instantly.
        val eff = VizController.applySticks(p.copy(zoom = sZoom, rot = sRot, warp = sWarp, decay = sDecay, speed = p.speed, react = p.react),
            VizController.slx, VizController.sly, VizController.srx, VizController.sry)
        sGlow += (p.glow - sGlow) * k; sChroma += (p.chroma - sChroma) * k; sSeed += (p.seed - sSeed) * k; sWave += (p.waveSize - sWave) * k
        hue += dt * p.hueSpeed * 0.1f * (0.6f + 0.4f * mid)

        val src = cur; val dst = 1 - cur
        val fps = 60f * dt
        // ---- 1. Warp previous frame into dst ----
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[dst])
        glViewport(0, 0, fw, fh)
        glDisable(GL_BLEND)
        glUseProgram(warpProg)
        val zoom = 1f + (eff.zoom - 1f) * fps + (bass - 1f) * 0.012f * p.zoomPulse
        u1(warpProg, "uTime", t); u1(warpProg, "uBass", bass); u1(warpProg, "uMid", mid); u1(warpProg, "uTreb", treb); u1(warpProg, "uBeat", beat)
        u1(warpProg, "uZoom", zoom); u1(warpProg, "uRot", eff.rot * fps * (0.7f + 0.3f * mid)); u1(warpProg, "uWarp", eff.warp)
        u1(warpProg, "uDecay", Math.pow(eff.decay.toDouble(), fps.toDouble()).toFloat()); u1(warpProg, "uAspect", fw.toFloat() / fh)
        u1(warpProg, "uMode", p.warpMode.toFloat()); u1(warpProg, "uSeed", sSeed); u1(warpProg, "uHueStep", p.hueSpeed * 0.01f * fps)
        u1(warpProg, "uDrift", p.drift)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[src]); glUniform1i(glGetUniformLocation(warpProg, "uPrev"), 0)
        drawQuad(warpProg)

        // ---- 2. Waves, shapes, particles ----
        vn = 0
        buildGeometry(p, t, bass, beat, dt)
        if (vn > 0) {
            glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE)
            glUseProgram(lineProg)
            vbuf.clear(); vbuf.put(verts, 0, vn * 6); vbuf.position(0)
            val aPos = glGetAttribLocation(lineProg, "aPos"); val aCol = glGetAttribLocation(lineProg, "aCol")
            glEnableVertexAttribArray(aPos); glVertexAttribPointer(aPos, 2, GL_FLOAT, false, 24, vbuf)
            vbuf.position(2)
            glEnableVertexAttribArray(aCol); glVertexAttribPointer(aCol, 4, GL_FLOAT, false, 24, vbuf)
            glDrawArrays(GL_TRIANGLES, 0, vn)
            glDisableVertexAttribArray(aPos); glDisableVertexAttribArray(aCol)
            glDisable(GL_BLEND)
        }

        // ---- 3. Composite to screen ----
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, w, h)
        glUseProgram(compProg)
        val pal = Viz.PALETTES[p.palette.coerceIn(0, Viz.PALETTES.lastIndex)]
        glUniform3fv(glGetUniformLocation(compProg, "uPalA"), 1, pal.a, 0)
        glUniform3fv(glGetUniformLocation(compProg, "uPalB"), 1, pal.b, 0)
        glUniform3fv(glGetUniformLocation(compProg, "uPalC"), 1, pal.c, 0)
        glUniform3fv(glGetUniformLocation(compProg, "uPalD"), 1, pal.d, 0)
        u1(compProg, "uPalOn", if (p.palette == 0) 0f else 1f); u1(compProg, "uPalShift", hue)
        u1(compProg, "uKaleido", p.kaleido.toFloat()); u1(compProg, "uMirror", p.mirror.toFloat())
        u1(compProg, "uChroma", sChroma * (1f + beat)); u1(compProg, "uGlow", sGlow); u1(compProg, "uScan", if (p.scanlines) 1f else 0f)
        u1(compProg, "uFlash", if (p.beatFlash) beat else 0f); u1(compProg, "uInvert", if (p.invertBeat) audio.beat else 0f)
        u1(compProg, "uAspect", w.toFloat() / h); u1(compProg, "uTime", t); u1(compProg, "uRes", h.toFloat())
        u2(compProg, "uTexel", 1f / fw, 1f / fh)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[dst]); glUniform1i(glGetUniformLocation(compProg, "uTex"), 0)
        drawQuad(compProg)
        cur = dst
    }

    // ---------------- Geometry ----------------
    private fun palColor(p: VizParams, x: Float, out: FloatArray) {
        val pal = Viz.PALETTES[p.palette.coerceIn(0, Viz.PALETTES.lastIndex)]
        if (p.palette == 0) { hsv(x, out); return }
        for (i in 0..2) out[i] = (pal.a[i] + pal.b[i] * cos(2 * PI * (pal.c[i] * x + pal.d[i]))).toFloat().coerceIn(0f, 1f)
        // Keep waves bright.
        val m = max(out[0], max(out[1], out[2])).coerceAtLeast(0.2f)
        for (i in 0..2) out[i] = out[i] / m
    }

    private fun hsv(hh: Float, out: FloatArray) {
        val x = ((hh % 1f) + 1f) % 1f * 6f
        val c = 1f; val xx = 1f - abs(x % 2f - 1f)
        val (r, g, b) = when (x.toInt()) { 0 -> Triple(c, xx, 0f); 1 -> Triple(xx, c, 0f); 2 -> Triple(0f, c, xx); 3 -> Triple(0f, xx, c); 4 -> Triple(xx, 0f, c); else -> Triple(c, 0f, xx) }
        out[0] = r; out[1] = g; out[2] = b
    }

    private val col = FloatArray(3)
    private val ax = FloatArray(1024); private val ay = FloatArray(1024)

    private fun vtx(x: Float, y: Float, r: Float, g: Float, b: Float, a: Float) {
        if (vn >= verts.size / 6) return
        val o = vn * 6
        verts[o] = x; verts[o + 1] = y; verts[o + 2] = r; verts[o + 3] = g; verts[o + 4] = b; verts[o + 5] = a
        vn++
    }

    /** Thick polyline from points in NDC; thickness in pixels. */
    private fun polyline(n: Int, closed: Boolean, thick: Float, c: FloatArray, alpha: Float) {
        val hx = thick / fw; val hy = thick / fh
        val segs = if (closed) n else n - 1
        for (i in 0 until segs) {
            val x0 = ax[i]; val y0 = ay[i]; val x1 = ax[(i + 1) % n]; val y1 = ay[(i + 1) % n]
            val dxp = (x1 - x0) * fw; val dyp = (y1 - y0) * fh
            val len = sqrt(dxp * dxp + dyp * dyp).coerceAtLeast(1e-4f)
            val nx = -dyp / len * hx; val ny = dxp / len * hy
            vtx(x0 + nx, y0 + ny, c[0], c[1], c[2], alpha); vtx(x0 - nx, y0 - ny, c[0], c[1], c[2], alpha); vtx(x1 + nx, y1 + ny, c[0], c[1], c[2], alpha)
            vtx(x1 + nx, y1 + ny, c[0], c[1], c[2], alpha); vtx(x0 - nx, y0 - ny, c[0], c[1], c[2], alpha); vtx(x1 - nx, y1 - ny, c[0], c[1], c[2], alpha)
        }
    }

    private fun quadAt(x: Float, y: Float, sx: Float, sy: Float, c: FloatArray, a: Float) {
        vtx(x - sx, y - sy, c[0], c[1], c[2], a); vtx(x + sx, y - sy, c[0], c[1], c[2], a); vtx(x - sx, y + sy, c[0], c[1], c[2], a)
        vtx(x - sx, y + sy, c[0], c[1], c[2], a); vtx(x + sx, y - sy, c[0], c[1], c[2], a); vtx(x + sx, y + sy, c[0], c[1], c[2], a)
    }

    private fun buildGeometry(p: VizParams, t: Float, bass: Float, beat: Float, dt: Float) {
        val asp = fw.toFloat() / fh
        val sx = if (asp > 1) 1f / asp else 1f
        val sy = if (asp > 1) 1f else asp
        val size = sWave
        val thick = p.thickness
        val wv = audio.wave; val sp = audio.spectrum
        palColor(p, hue, col)
        when (p.waveMode) {
            0 -> { // ring
                val n = 128
                for (i in 0 until n) {
                    val a = (i.toFloat() / n) * 2f * PI.toFloat() + t * 0.2f
                    val r = (0.32f + wv[i * 2] * 0.12f * size) * (0.9f + 0.1f * bass)
                    ax[i] = cos(a) * r * sx; ay[i] = sin(a) * r * sy
                }
                polyline(n, true, thick, col, 0.9f)
            }
            1 -> { // scope
                val n = 200
                for (i in 0 until n) { ax[i] = -0.95f + 1.9f * i / (n - 1); ay[i] = wv[(i * 256) / n] * 0.35f * size }
                polyline(n, false, thick, col, 0.9f)
                palColor(p, hue + 0.5f, col)
                for (i in 0 until n) ay[i] = -ay[i] * 0.6f
                polyline(n, false, thick * 0.6f, col, 0.5f)
            }
            2 -> { // radial spectrum
                for (i in 0 until 64) {
                    val a = i / 64f * 2f * PI.toFloat() + t * 0.15f
                    val r0 = 0.16f * (0.9f + 0.1f * bass); val r1 = r0 + sp[i] * 0.55f * size
                    palColor(p, hue + i / 64f, col)
                    ax[0] = cos(a) * r0 * sx; ay[0] = sin(a) * r0 * sy; ax[1] = cos(a) * r1 * sx; ay[1] = sin(a) * r1 * sy
                    polyline(2, false, thick * 1.4f, col, 0.85f)
                }
            }
            3 -> { // bars top + bottom
                val bw = 1.9f / 64
                for (i in 0 until 64) {
                    val x = -0.95f + bw * (i + 0.5f)
                    val hgt = sp[i] * 0.7f * size
                    palColor(p, hue + i / 64f * 0.5f, col)
                    quadAt(x, -1f + hgt / 2, bw * 0.38f, hgt / 2, col, 0.8f)
                    quadAt(x, 1f - hgt / 2, bw * 0.38f, hgt / 2, col, 0.4f)
                }
            }
            4 -> { // lissajous
                val n = 220
                for (i in 0 until n) { ax[i] = wv[i] * 0.55f * size * sx; ay[i] = wv[(i + 37) % 256] * 0.55f * size * sy }
                polyline(n, false, thick, col, 0.75f)
            }
            5 -> {} // starburst = particles only
            6 -> {}
            7 -> { // twin rings
                for (ring in 0..1) {
                    val n = 128
                    val dir = if (ring == 0) 1f else -1f
                    for (i in 0 until n) {
                        val a = (i.toFloat() / n) * 2f * PI.toFloat() + t * 0.3f * dir
                        val s = sp[(i % 64)]
                        val r = (if (ring == 0) 0.22f else 0.42f) + (if (ring == 0) wv[i * 2] * 0.06f else s * 0.15f) * size
                        ax[i] = cos(a) * r * sx; ay[i] = sin(a) * r * sy
                    }
                    palColor(p, hue + ring * 0.33f, col)
                    polyline(n, true, thick, col, 0.85f)
                }
            }
        }

        // Rotating shapes
        if (p.shapeSides >= 3) {
            for (s in 0..1) {
                val n = p.shapeSides
                val r = (if (s == 0) 0.2f else 0.36f) * (0.85f + 0.25f * (bass - 1f).coerceIn(-0.5f, 2f)) * size
                val rot = t * (if (s == 0) 0.5f else -0.33f)
                for (i in 0 until n) { val a = rot + i * 2f * PI.toFloat() / n; ax[i] = cos(a) * r * sx; ay[i] = sin(a) * r * sy }
                palColor(p, hue + 0.25f + s * 0.3f, col)
                polyline(n, true, thick * 0.8f, col, 0.6f)
            }
        }

        // Particles
        if (p.particles || p.waveMode == 5) {
            if (beat > 0.8f || rnd.nextFloat() < audio.level * 0.5f) {
                val count = if (beat > 0.8f) 60 else 3
                for (i in 0 until count) {
                    if (particles.size > 900) break
                    val a = rnd.nextFloat() * 2f * PI.toFloat(); val sp0 = 0.2f + rnd.nextFloat() * 0.9f * (0.5f + bass * 0.5f)
                    particles += Particle(0f, 0f, cos(a) * sp0 * sx, sin(a) * sp0 * sy, 1f, hue + rnd.nextFloat() * 0.3f)
                }
            }
            val it = particles.iterator()
            while (it.hasNext()) {
                val q = it.next()
                q.x += q.vx * dt; q.y += q.vy * dt; q.vx *= 1.01f; q.vy *= 1.01f; q.life -= dt * 0.7f
                if (q.life <= 0 || abs(q.x) > 1.2f || abs(q.y) > 1.2f) { it.remove(); continue }
                palColor(p, q.hue, col)
                val s = (2f + 3f * q.life) * thick / 3f
                quadAt(q.x, q.y, s / fw, s / fh, col, q.life)
            }
        } else particles.clear()
    }

    // ---------------- GL utils ----------------
    private fun drawQuad(prog: Int) {
        val a = glGetAttribLocation(prog, "aPos")
        quad.position(0)
        glEnableVertexAttribArray(a); glVertexAttribPointer(a, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(a)
    }

    private fun u1(p: Int, n: String, v: Float) = glUniform1f(glGetUniformLocation(p, n), v)
    private fun u2(p: Int, n: String, a: Float, b: Float) = glUniform2f(glGetUniformLocation(p, n), a, b)
    private fun fb(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src); glCompileShader(s)
        val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("CratezViz", "Shader error: " + glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("CratezViz", "Link error: " + glGetProgramInfoLog(p))
        return p
    }

    companion object {
        const val VS_QUAD = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        const val VS_LINE = """
attribute vec2 aPos;
attribute vec4 aCol;
varying vec4 vCol;
void main() { vCol = aCol; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        const val FS_LINE = """
precision mediump float;
varying vec4 vCol;
void main() { gl_FragColor = vCol; }
"""
        const val FS_WARP = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uPrev;
uniform float uTime, uBass, uMid, uTreb, uBeat, uZoom, uRot, uWarp, uDecay, uAspect, uMode, uSeed, uHueStep, uDrift;

vec3 hue(float h) { return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); }
vec3 hueRotate(vec3 c, float a) {
    const vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

void main() {
    float t = uTime;
    vec2 p = vUv - 0.5;
    p.x *= uAspect;
    p -= 0.04 * uDrift * vec2(sin(t * 0.31), cos(t * 0.23));
    float r = length(p);
    float a = atan(p.y, p.x);
    float rot = uRot;
    float w = uWarp;
    if (uMode < 0.5) {
        // tunnel: zoom only
    } else if (uMode < 1.5) {
        rot += 0.035 * w * r * (0.6 + 0.4 * uMid);
    } else if (uMode < 2.5) {
        p += w * 0.012 * vec2(sin(p.y * 7.0 + t * 1.3 + uMid), cos(p.x * 6.0 - t * 1.1));
    } else if (uMode < 3.5) {
        p *= 1.0 - w * 0.02 * sin(r * 28.0 - t * 5.0) * (0.4 + uBass * 0.6);
    } else if (uMode < 4.5) {
        p = vec2(abs(p.x), p.y);
        p *= 1.0 - 0.01 * w;
    } else if (uMode < 5.5) {
        a += w * 0.05 / (r + 0.08);
        p = r * vec2(cos(a), sin(a));
    } else if (uMode < 6.5) {
        p.y -= 0.006 * w * (1.0 + uBass * 0.5);
        p.x += 0.004 * abs(w) * sin(p.y * 18.0 + t * 2.0);
    } else if (uMode < 7.5) {
        p *= 1.0 - w * 0.04 * (0.35 - r) * (1.0 + uBeat);
    } else {
        p += w * 0.01 * vec2(sin(p.x * 13.0 + t), sin(p.y * 11.0 - t * 1.7)) * (r * 3.0 + 0.2);
    }
    float c = cos(rot), s = sin(rot);
    p = mat2(c, -s, s, c) * p / uZoom;
    p += 0.04 * uDrift * vec2(sin(t * 0.31), cos(t * 0.23));
    p.x /= uAspect;
    vec2 uv = p + 0.5;
    vec3 col = texture2D(uPrev, uv).rgb;
    col = hueRotate(col, uHueStep);
    col = max(col * uDecay - 0.003, 0.0);
    // Seed: a faint plasma so the scene never goes fully dark.
    vec2 q = (vUv - 0.5) * vec2(uAspect, 1.0);
    float pl = sin(q.x * 10.0 + t) + sin(q.y * 12.0 - t * 1.3) + sin((q.x + q.y) * 8.0 + t * 0.7) + sin(length(q) * 14.0 - t * 2.0);
    col += uSeed * hue(pl * 0.12 + t * 0.03) * (0.01 + 0.05 * uBass + 0.06 * uBeat) * smoothstep(1.2, -0.2, pl);
    gl_FragColor = vec4(col, 1.0);
}
"""
        const val FS_COMP = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uPalA, uPalB, uPalC, uPalD;
uniform float uPalOn, uPalShift, uKaleido, uMirror, uChroma, uGlow, uScan, uFlash, uInvert, uAspect, uTime, uRes;
uniform vec2 uTexel;

vec2 kaleido(vec2 uv, float n) {
    vec2 p = uv - 0.5;
    p.x *= uAspect;
    float r = length(p);
    float a = atan(p.y, p.x) + uTime * 0.05;
    float seg = 6.2831853 / n;
    a = mod(a, seg);
    a = abs(a - seg * 0.5);
    p = r * vec2(cos(a), sin(a));
    p.x /= uAspect;
    return p + 0.5;
}

vec3 tap(vec2 uv) {
    vec2 d = (uv - 0.5) * uChroma * 0.03;
    return vec3(texture2D(uTex, uv + d).r, texture2D(uTex, uv).g, texture2D(uTex, uv - d).b);
}

void main() {
    vec2 uv = vUv;
    if (uKaleido >= 2.0) uv = kaleido(uv, uKaleido);
    if (uMirror > 0.5) uv.x = uv.x > 0.5 ? 1.0 - uv.x : uv.x;
    if (uMirror > 1.5) uv.y = uv.y > 0.5 ? 1.0 - uv.y : uv.y;
    uv = clamp(uv, 0.001, 0.999);
    vec3 col = tap(uv);
    if (uGlow > 0.01) {
        vec3 g = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float ang = float(i) * 0.785398;
            vec2 o = vec2(cos(ang), sin(ang)) * uTexel * 4.0;
            g += texture2D(uTex, uv + o).rgb + texture2D(uTex, uv + o * 2.5).rgb;
        }
        col += g / 16.0 * uGlow * 1.4;
    }
    if (uPalOn > 0.5) {
        float l = dot(col, vec3(0.299, 0.587, 0.114));
        vec3 pc = uPalA + uPalB * cos(6.2831853 * (uPalC * (l * 1.3 + uPalShift) + uPalD));
        col = pc * smoothstep(0.015, 0.45, l) * (0.55 + l * 0.9);
    }
    col += uFlash * 0.18;
    col = mix(col, 1.0 - col, clamp(uInvert, 0.0, 1.0) * 0.85);
    if (uScan > 0.5) col *= 0.82 + 0.18 * sin(gl_FragCoord.y * 3.14159 * 0.5);
    vec2 v = vUv - 0.5;
    col *= 1.0 - dot(v, v) * 0.6;
    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
