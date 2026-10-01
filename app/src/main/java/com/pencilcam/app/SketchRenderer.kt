package com.pencilcam.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/**
 * ============================================================================
 * SketchRenderer
 * Runs on the GPU thread. Every screen refresh it:
 *   1. grabs the newest camera frame,
 *   2. runs the 4 shader passes,
 *   3. draws the result on screen,
 *   4. if a photo was requested, also renders it off-screen and hands back a Bitmap.
 * ============================================================================
 */
class SketchRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {

    // ---- Values the main screen updates (read on the GPU thread) ----
    @Volatile var settings = SketchSettings()
    @Volatile var cameraRotation = 90                  // from CameraX: degrees to turn the image upright
    @Volatile var onNeedCamera: (() -> Unit)? = null    // called if the GPU context was rebuilt
    @Volatile private var photoCallback: ((Bitmap?) -> Unit)? = null

    // ---- Camera input ----
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameAvailable = AtomicBoolean(false)
    private val stMatrix = FloatArray(16)
    private var camW = 0
    private var camH = 0
    private var gotFrame = false

    // ---- Shader programs ----
    private var pCopy = 0; private var pBlurH = 0; private var pBlurV = 0; private var pDraw = 0
    private lateinit var quad: FloatBuffer

    // ---- Off-screen images ("render targets") ----
    private class Target(val fb: Int, val tex: Int, val w: Int, val h: Int)
    private var srcT: Target? = null     // upright camera frame
    private var blurA: Target? = null    // after horizontal blur
    private var blurB: Target? = null    // after vertical blur
    private var procW = 0; private var procH = 0
    private var viewW = 1; private var viewH = 1
    private var srcValid = false

    // ---- Wobble timing ----
    private var seed = 0f
    private var lastBoilNs = 0L

    // ========================================================================
    // Called from the main thread when CameraX wants a place to send frames.
    // We create a fresh SurfaceTexture on the GPU thread and hand it over.
    // ========================================================================
    // Camera requests wait here until the GPU thread is ready to accept them
    private class Pending(val request: SurfaceRequest, val exec: Executor)
    @Volatile private var pending: Pending? = null

    fun provideSurface(request: SurfaceRequest, mainExec: Executor) {
        request.setTransformationInfoListener(mainExec) { info -> cameraRotation = info.rotationDegrees }
        pending?.request?.willNotProvideSurface()   // a newer request replaces any older one
        pending = Pending(request, mainExec)
    }

    /** Runs on the GPU thread (from onDrawFrame), so the GPU is guaranteed ready. */
    private fun connectPendingCamera() {
        val pr = pending ?: return
        pending = null
        val request = pr.request; val mainExec = pr.exec

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        st.setOnFrameAvailableListener { frameAvailable.set(true) }
        val surface = Surface(st)

        oesTex = tex
        surfaceTexture = st
        camW = request.resolution.width
        camH = request.resolution.height
        gotFrame = false
        srcValid = false

        request.provideSurface(surface, mainExec) {
            // Camera is done with this surface (camera switched or app paused): clean up
            surface.release()
            view.queueEvent {
                if (surfaceTexture === st) { surfaceTexture = null; oesTex = 0 }
                st.release()
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            }
        }
    }

    /** Ask for the next drawn frame as a full-resolution Bitmap. */
    fun requestPhoto(cb: (Bitmap?) -> Unit) { photoCallback = cb }

    // ========================================================================
    // GPU lifecycle
    // ========================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)

        pCopy = program(Shaders.COPY_VS, Shaders.COPY_FS)
        pBlurH = program(Shaders.QUAD_VS, Shaders.BLUR_H_FS)
        pBlurV = program(Shaders.QUAD_VS, Shaders.BLUR_V_FS)
        pDraw = program(Shaders.QUAD_VS, Shaders.DRAW_FS)

        // A brand-new GPU context means old textures are gone. Start fresh.
        srcT = null; blurA = null; blurB = null; procW = 0; procH = 0
        if (surfaceTexture != null) {
            surfaceTexture = null; oesTex = 0
            view.post { onNeedCamera?.invoke() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0.118f, 0.125f, 0.141f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        connectPendingCamera()
        val st = surfaceTexture ?: return
        if (frameAvailable.getAndSet(false)) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                gotFrame = true
            } catch (e: Exception) { Log.w(TAG, "updateTexImage failed", e); return }
        }
        if (!gotFrame || oesTex == 0) return

        val s = settings

        // ---- Wobble: only advance on "redraw" steps (animated on twos/threes)
        val now = System.nanoTime()
        var stepped = false
        if (now - lastBoilNs >= (1_000_000_000.0 / s.boilFps).toLong()) {
            lastBoilNs = now; seed = (seed + 1f) % 997f; stepped = true
        }

        // ---- Work out the upright image size
        val rot = ((cameraRotation + s.rotationFix * 90) % 360 + 360) % 360
        val sideways = rot % 180 != 0
        val w = if (sideways) camH else camW
        val h = if (sideways) camW else camH
        if (!ensureTargets(w, h)) return
        val src = srcT!!; val a = blurA!!; val b = blurB!!

        // ---- Pass 0: camera -> upright source image
        if (stepped || !s.holdFrames || !srcValid) {
            bindTarget(src)
            GLES20.glUseProgram(pCopy)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glUniform1i(loc(pCopy, "u_cam"), 0)
            GLES20.glUniformMatrix4fv(loc(pCopy, "u_st"), 1, false, stMatrix, 0)
            GLES20.glUniform1f(loc(pCopy, "u_rot"), rot.toFloat())
            GLES20.glUniform1f(loc(pCopy, "u_mirror"), if (s.mirror) 1f else 0f)
            drawQuad()
            srcValid = true
        }

        // ---- Passes 1 and 2: blurs
        bindTarget(a)
        GLES20.glUseProgram(pBlurH)
        bindTex(0, src.tex); GLES20.glUniform1i(loc(pBlurH, "u_src"), 0)
        GLES20.glUniform2f(loc(pBlurH, "u_texel"), 1f / w, 1f / h)
        GLES20.glUniform1f(loc(pBlurH, "u_s1"), s.width)
        drawQuad()

        bindTarget(b)
        GLES20.glUseProgram(pBlurV)
        bindTex(0, a.tex); GLES20.glUniform1i(loc(pBlurV, "u_tex"), 0)
        GLES20.glUniform2f(loc(pBlurV, "u_texel"), 1f / w, 1f / h)
        GLES20.glUniform1f(loc(pBlurV, "u_s1"), s.width)
        drawQuad()

        // ---- Pass 3: draw to the screen, fitted with bars if needed
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val scale = min(viewW.toFloat() / w, viewH.toFloat() / h)
        val vw = (w * scale).toInt(); val vh = (h * scale).toInt()
        GLES20.glViewport((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
        drawSketch(s, src, b, w, h)

        // ---- Photo: same drawing, rendered at full size off-screen
        val cb = photoCallback
        if (cb != null) {
            photoCallback = null
            val bmp = try { renderPhoto(s, src, b, w, h) } catch (e: Exception) { Log.e(TAG, "photo failed", e); null }
            view.post { cb(bmp) }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================
    private fun drawSketch(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int) {
        GLES20.glUseProgram(pDraw)
        bindTex(0, blur.tex); GLES20.glUniform1i(loc(pDraw, "u_blur"), 0)
        bindTex(1, src.tex); GLES20.glUniform1i(loc(pDraw, "u_src"), 1)
        GLES20.glUniform2f(loc(pDraw, "u_res"), w.toFloat(), h.toFloat())
        GLES20.glUniform1f(loc(pDraw, "u_seed"), seed)
        GLES20.glUniform1f(loc(pDraw, "u_strength"), s.strength)
        GLES20.glUniform1f(loc(pDraw, "u_thr"), s.threshold())
        GLES20.glUniform1f(loc(pDraw, "u_hatch"), s.hatch)
        GLES20.glUniform1f(loc(pDraw, "u_spacing"), s.spacing)
        GLES20.glUniform1f(loc(pDraw, "u_boil"), s.boil)
        GLES20.glUniform1f(loc(pDraw, "u_grain"), s.grain)
        GLES20.glUniform1f(loc(pDraw, "u_style"), s.style.toFloat())
        val ink = s.inkRgb()
        GLES20.glUniform3f(loc(pDraw, "u_ink"), ink[0], ink[1], ink[2])
        drawQuad()
    }

    private fun renderPhoto(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int): Bitmap {
        val t = makeTarget(w, h, halfFloat = false) ?: throw IllegalStateException("no photo target")
        bindTarget(t)
        drawSketch(s, src, blur, w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        deleteTarget(t)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // GPU images are stored upside down compared to photos: flip it
        val flip = Matrix().apply { preScale(1f, -1f) }
        val out = Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
        raw.recycle()
        return out
    }

    private fun ensureTargets(w: Int, h: Int): Boolean {
        if (w == procW && h == procH && srcT != null) return true
        listOf(srcT, blurA, blurB).forEach { it?.let(::deleteTarget) }
        srcT = makeTarget(w, h, halfFloat = false)
        // Half-float keeps the subtle blur differences clean; fall back to 8-bit if unsupported
        blurA = makeTarget(w, h, halfFloat = true) ?: makeTarget(w, h, halfFloat = false)
        blurB = makeTarget(w, h, halfFloat = true) ?: makeTarget(w, h, halfFloat = false)
        procW = w; procH = h; srcValid = false
        return srcT != null && blurA != null && blurB != null
    }

    private fun makeTarget(w: Int, h: Int, halfFloat: Boolean): Target? {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (halfFloat) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!ok) {
            GLES20.glDeleteFramebuffers(1, f, 0); GLES20.glDeleteTextures(1, t, 0)
            return null
        }
        return Target(f[0], t[0], w, h)
    }

    private fun deleteTarget(t: Target) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(t.fb), 0)
        GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0)
    }

    private fun bindTarget(t: Target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fb)
        GLES20.glViewport(0, 0, t.w, t.h)
    }

    private fun bindTex(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    private fun drawQuad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private val locCache = HashMap<String, Int>()
    private fun loc(prog: Int, name: String): Int =
        locCache.getOrPut("$prog/$name") { GLES20.glGetUniformLocation(prog, name) }

    private fun program(vs: String, fs: String): Int {
        locCache.clear()
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glBindAttribLocation(p, 0, "p")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src.trimStart())   // "#extension" must be the very first thing
        GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object { private const val TAG = "PencilCam" }
}
