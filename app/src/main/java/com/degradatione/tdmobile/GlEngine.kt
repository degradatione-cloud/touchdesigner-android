package com.degradatione.tdmobile

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL-движок: каждый узел — проход фрагментного шейдера в собственный FBO,
 * выход показывается на экране. Feedback держит кадр между кадрами.
 */
class GlEngine : GLSurfaceView.Renderer {

    @Volatile var patch: Patch? = null
    @Volatile var playing = true
    @Volatile var speed = 1f

    @Volatile var fps: Float = 0f
        private set
    @Volatile var errorLog: String? = null
        private set

    var snapshotCallback: ((Bitmap?) -> Unit)? = null

    private var time = 0f
    private var lastNs = System.nanoTime()
    private var frames = 0
    private var fpsMark = System.nanoTime()

    private var width = 0
    private var height = 0

    private val programs = HashMap<NodeKind, Int>()
    private val uniforms = HashMap<Int, HashMap<String, Int>>()
    private val fbos = HashMap<Int, Fbo>()
    private val fbPing = HashMap<Int, Pair<Fbo, Fbo>>()
    private val fbState = HashMap<Int, Boolean>() // true = рисуем в первый

    private data class Fbo(val fbo: Int, val tex: Int)

    private var quad: FloatBuffer? = null

    private val vertexShader = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        val buf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        quad = buf
        programs.clear()
        uniforms.clear()
        fbos.clear()
        fbPing.clear()
        fbState.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewportW = w
        viewportH = h
        // рендерим в разумном разрешении: не выше 1080 по длинной стороне
        val k = minOf(1f, 1080f / maxOf(w, h))
        width = (w * k).toInt().coerceAtLeast(2)
        height = (h * k).toInt().coerceAtLeast(2)
        releaseTargets()
        GLES20.glViewport(0, 0, w, h)
    }

    private fun releaseTargets() {
        for (f in fbos.values) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(f.fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(f.tex), 0)
        }
        fbos.clear()
        for ((a, b) in fbPing.values) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(a.fbo, b.fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(a.tex, b.tex), 0)
        }
        fbPing.clear()
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now
        if (playing) time += dt * speed

        frames++
        if (now - fpsMark > 500_000_000L) {
            fps = frames / ((now - fpsMark) / 1e9f)
            frames = 0
            fpsMark = now
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportW, viewportH)
        GLES20.glClearColor(0.04f, 0.04f, 0.06f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val p = patch
        if (p != null && width > 0 && height > 0) {
            val order = p.evalOrder()
            for (n in order) {
                if (n.kind == NodeKind.OUT) continue
                drawToFbo(n, p)
            }
            p.outputNode()?.let { out -> drawToScreen(out, p) }
        }

        snapshotCallback?.let { cb ->
            snapshotCallback = null
            cb(readScreen())
        }
    }

    private var viewportW = 0
    private var viewportH = 0

    fun setViewport(w: Int, h: Int) {
        viewportW = w
        viewportH = h
    }

    // ── проходы ────────────────────────────────────────────────────────────
    private fun drawToFbo(n: Node, p: Patch) {
        val target = if (n.kind == NodeKind.FEEDBACK) {
            val pair = fbPing.getOrPut(n.id) { newFbo() to newFbo() }
            val useFirst = fbState.getOrPut(n.id) { true }
            if (useFirst) pair.first else pair.second
        } else {
            fbos.getOrPut(n.id) { newFbo() }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.fbo)
        GLES20.glViewport(0, 0, width, height)
        drawQuad(n, p, screen = false)
        if (n.kind == NodeKind.FEEDBACK) {
            fbState[n.id] = !(fbState[n.id] ?: true)
        }
    }

    private fun drawToScreen(n: Node, p: Patch) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportW, viewportH)
        drawQuad(n, p, screen = true)
    }

    private fun drawQuad(n: Node, p: Patch, screen: Boolean) {
        val prog = program(n.kind) ?: return
        GLES20.glUseProgram(prog)
        var unit = 0
        for (i in 0 until n.kind.inputs) {
            val srcId = n.inputs[i]
            val tex = srcId?.let { textureOf(it) } ?: 0
            if (tex != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glUniform1i(loc(prog, "uTex$i"), unit)
                unit++
            }
        }
        if (n.kind == NodeKind.FEEDBACK) {
            val pair = fbPing[n.id]
            if (pair != null) {
                val prev = if (fbState[n.id] == true) pair.second else pair.first
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prev.tex)
                GLES20.glUniform1i(loc(prog, "uPrev"), unit)
            }
        }
        GLES20.glUniform1f(loc(prog, "uTime"), time)
        GLES20.glUniform2f(loc(prog, "uRes"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(loc(prog, "uP1"), n.p1)
        GLES20.glUniform1f(loc(prog, "uP2"), n.p2)

        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        quad?.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun loc(prog: Int, name: String): Int {
        val map = uniforms.getOrPut(prog) { HashMap() }
        return map.getOrPut(name) { GLES20.glGetUniformLocation(prog, name) }
    }

    private fun textureOf(nodeId: Int): Int {
        fbos[nodeId]?.let { return it.tex }
        fbPing[nodeId]?.let { (a, b) ->
            // после рисования актуальный кадр лежит в том, что был только что закрыт
            return if (fbState[nodeId] == true) b.tex else a.tex
        }
        return 0
    }

    private fun newFbo(): Fbo {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0
        )
        return Fbo(fbo[0], tex[0])
    }

    fun readScreen(): Bitmap? {
        if (viewportW <= 0 || viewportH <= 0) return null
        val buf = ByteBuffer.allocateDirect(viewportW * viewportH * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glReadPixels(0, 0, viewportW, viewportH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val bmp = Bitmap.createBitmap(viewportW, viewportH, Bitmap.Config.ARGB_8888)
        buf.rewind()
        val pixels = IntArray(viewportW * viewportH)
        for (y in 0 until viewportH) {
            for (x in 0 until viewportW) {
                val i = (y * viewportW + x) * 4
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                val a = buf.get(i + 3).toInt() and 0xFF
                pixels[(viewportH - 1 - y) * viewportW + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, viewportW, 0, 0, viewportW, viewportH)
        return bmp
    }

    fun requestSnapshot(cb: (Bitmap?) -> Unit) {
        snapshotCallback = cb
    }

    // ── шейдеры ────────────────────────────────────────────────────────────
    private fun program(kind: NodeKind): Int? {
        programs[kind]?.let { return it }
        val frag = FRAGMENTS[kind] ?: return null
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, frag)
        if (vs == 0 || fs == 0) return null
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            errorLog = "link ${kind.name}: " + GLES20.glGetProgramInfoLog(p)
            return null
        }
        programs[kind] = p
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            errorLog = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    companion object {
        private const val HSV_FN = """
            vec3 rgb2hsv(vec3 c) {
                vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
                vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
                vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
                float d = q.x - min(q.w, q.y);
                float e = 1.0e-10;
                return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
            }
            vec3 hsv2rgb(vec3 c) {
                vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
                vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(vec3(1.0), clamp(p - K.xxx, 0.0, 1.0), c.y);
            }
        """

        private val FRAGMENTS: Map<NodeKind, String> = mapOf(
            NodeKind.GRADIENT to """
                precision mediump float;
                varying vec2 vUv;
                uniform float uTime, uP1, uP2;
                $HSV_FN
                void main() {
                    float a = vUv.y * 0.8 + 0.18 * sin(vUv.x * 6.0 + uTime * (0.2 + uP2 * 2.0));
                    float h = fract(uP1 + a * 0.6);
                    vec3 col = hsv2rgb(vec3(h, 0.8, 0.95));
                    col *= 0.85 + 0.15 * sin(vUv.x * 12.0 + uTime * 1.5);
                    gl_FragColor = vec4(col, 1.0);
                }
            """,

            NodeKind.NOISE to """
                precision mediump float;
                varying vec2 vUv;
                uniform float uTime, uP1, uP2;
                float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
                float vnoise(vec2 p) {
                    vec2 i = floor(p), f = fract(p);
                    f = f * f * (3.0 - 2.0 * f);
                    float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                    float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
                }
                float fbm(vec2 p) {
                    float s = 0.0, a = 0.5;
                    for (int i = 0; i < 5; i++) { s += a * vnoise(p); p *= 2.03; a *= 0.5; }
                    return s;
                }
                void main() {
                    vec2 uv = vUv * (uP1 * 2.0) + vec2(uTime * uP2 * 0.2, uTime * uP2 * 0.13);
                    float n = fbm(uv);
                    vec3 col = vec3(n * 0.9, n * 0.75 + 0.1, n * 0.6 + 0.25);
                    gl_FragColor = vec4(col, 1.0);
                }
            """,

            NodeKind.BLUR to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                uniform vec2 uRes;
                uniform float uP1;
                void main() {
                    vec2 px = uP1 / max(uRes, vec2(1.0));
                    vec4 sum = vec4(0.0);
                    for (int y = -2; y <= 2; y++) {
                        for (int x = -2; x <= 2; x++) {
                            sum += texture2D(uTex0, vUv + vec2(float(x), float(y)) * px);
                        }
                    }
                    gl_FragColor = sum / 25.0;
                }
            """,

            NodeKind.KALEIDO to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                uniform float uP1, uP2;
                void main() {
                    vec2 uv = vUv - 0.5;
                    float r = length(uv) * 2.0;
                    float a = atan(uv.y, uv.x) + uP2 * 3.14159265;
                    float seg = max(uP1, 2.0);
                    float k = 6.2831853 / seg;
                    a = mod(a, k);
                    a = abs(a - k * 0.5);
                    vec2 p = vec2(cos(a), sin(a)) * r * 0.5 + 0.5;
                    gl_FragColor = texture2D(uTex0, fract(p)) * (1.0 - r * 0.35);
                }
            """,

            NodeKind.TWIST to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                uniform float uTime, uP1, uP2;
                void main() {
                    vec2 uv = vUv - 0.5;
                    float r = length(uv);
                    float ang = uP1 * r + uTime * uP2;
                    float c = cos(ang), s = sin(ang);
                    vec2 p = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c) + 0.5;
                    gl_FragColor = texture2D(uTex0, clamp(p, 0.001, 0.999));
                }
            """,

            NodeKind.MIRROR to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                uniform float uP1, uP2;
                void main() {
                    float t = max(1.0, floor(uP1));
                    vec2 uv = fract(vUv * t + uP2);
                    uv = abs(uv * 2.0 - 1.0);
                    gl_FragColor = texture2D(uTex0, uv);
                }
            """,

            NodeKind.COLORIZE to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                uniform float uP1, uP2;
                $HSV_FN
                void main() {
                    vec4 src = texture2D(uTex0, vUv);
                    vec3 hsv = rgb2hsv(src.rgb);
                    hsv.x = fract(hsv.x + uP1);
                    hsv.y = clamp(hsv.y * uP2, 0.0, 1.0);
                    gl_FragColor = vec4(hsv2rgb(hsv), src.a);
                }
            """,

            NodeKind.FEEDBACK to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                uniform sampler2D uPrev;
                uniform float uP1, uP2;
                void main() {
                    vec2 uv = (vUv - 0.5) * (1.0 - uP2 * 0.02) + 0.5;
                    vec4 cur = texture2D(uTex0, uv);
                    vec4 prev = texture2D(uPrev, uv);
                    gl_FragColor = max(cur, prev * uP1);
                }
            """,

            NodeKind.BLEND to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0, uTex1;
                uniform float uP1, uP2;
                void main() {
                    vec4 a = texture2D(uTex0, vUv);
                    vec4 b = texture2D(uTex1, vUv);
                    vec3 r;
                    if (uP2 < 0.5) { r = a.rgb + b.rgb; }
                    else if (uP2 < 1.5) { r = a.rgb * b.rgb; }
                    else { r = 1.0 - (1.0 - a.rgb) * (1.0 - b.rgb); }
                    gl_FragColor = vec4(mix(a.rgb, clamp(r, 0.0, 1.0), clamp(uP1, 0.0, 1.0)), 1.0);
                }
            """,

            NodeKind.OUT to """
                precision mediump float;
                varying vec2 vUv;
                uniform sampler2D uTex0;
                void main() {
                    gl_FragColor = texture2D(uTex0, vUv);
                }
            """,
        )
    }
}