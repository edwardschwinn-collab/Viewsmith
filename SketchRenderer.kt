package com.pencilcam.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.util.Size
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
 * SketchRenderer (GPU thread)
 * Sources:  live camera  OR  a gallery photo (File mode)
 * Outputs:  the screen, a saved photo (on request), and the video encoder
 *           (while recording).
 *
 * Sizing note: every slider (stroke spacing, wobble, blur) is defined in
 * "720-pixel units". Bigger images get a scale factor k so the drawing looks
 * the same whether the source is 720p video or a 12 MP photo.
 * ============================================================================
 */
class SketchRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {

    // ---- Set by the main screen
    @Volatile var settings = SketchSettings()
    @Volatile var animate = true                       // false in Photo mode: drawing holds still
    @Volatile var onNeedCamera: (() -> Unit)? = null
    @Volatile var onRecordError: ((String) -> Unit)? = null
    /** Size of the image currently being drawn (the screen asks this before recording). */
    @Volatile var procSize = Size(0, 0); private set

    // ---- Camera input
    private class Pending(val request: SurfaceRequest, val exec: Executor)
    @Volatile private var pending: Pending? = null
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameAvailable = AtomicBoolean(false)
    private val stMatrix = FloatArray(16)
    private var camW = 0; private var camH = 0
    private var gotFrame = false

    // ---- Gallery photo input (File mode)
    @Volatile private var pendingBitmap: Bitmap? = null
    @Volatile private var clearBitmapReq = false
    private var lastBitmap: Bitmap? = null
    private var bmpTex = 0; private var bmpW = 0; private var bmpH = 0
    private var useBitmap = false

    // ---- Photo / save request
    private class PhotoReq(val rotation: Int, val cb: (Bitmap?) -> Unit)
    @Volatile private var photoReq: PhotoReq? = null

    // ---- Recording
    @Volatile private var pendingEncSurface: Surface? = null
    @Volatile private var stopEncReq: (() -> Unit)? = null
    private var encSurface: EGLSurface? = null
    private var lastEncNs = 0L

    // ---- Programs, geometry, targets
    private var pCopy = 0; private var pCopy2D = 0; private var pBlurH = 0; private var pBlurV = 0; private var pDraw = 0
    private lateinit var quad: FloatBuffer
    private class Target(val fb: Int, val tex: Int, val w: Int, val h: Int)
    private var srcT: Target? = null; private var blurA: Target? = null; private var blurB: Target? = null
    private var procW = 0; private var procH = 0
    private var viewW = 1; private var viewH = 1
    private var srcValid = false

    // ---- Wobble timing
    private var seed = 0f
    private var lastBoilNs = 0L

    // ========================================================================
    // Requests from the main thread (all just set a flag + ask for a redraw)
    // ========================================================================
    fun provideSurface(request: SurfaceRequest, mainExec: Executor) {
        pending?.request?.willNotProvideSurface()
        pending = Pending(request, mainExec)
        view.requestRender()
    }
    fun showBitmap(b: Bitmap) { pendingBitmap = b; view.requestRender() }
    fun clearBitmap() { clearBitmapReq = true; view.requestRender() }
    fun requestPhoto(rotation: Int, cb: (Bitmap?) -> Unit) { photoReq = PhotoReq(rotation, cb); view.requestRender() }
    fun startRecording(s: Surface) { pendingEncSurface = s; view.requestRender() }
    fun stopRecording(done: () -> Unit) { stopEncReq = done; view.requestRender() }

    // ========================================================================
    // GPU lifecycle
    // ========================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        locCache.clear()
        pCopy = program(Shaders.COPY_VS, Shaders.COPY_FS)
        pCopy2D = program(Shaders.QUAD_VS, Shaders.COPY2D_FS)
        pBlurH = program(Shaders.QUAD_VS, Shaders.BLUR_H_FS)
        pBlurV = program(Shaders.QUAD_VS, Shaders.BLUR_V_FS)
        pDraw = program(Shaders.QUAD_VS, Shaders.DRAW_FS)

        // Fresh GPU context: old textures are gone
        srcT = null; blurA = null; blurB = null; procW = 0; procH = 0; srcValid = false
        encSurface = null
        if (useBitmap && lastBitmap != null) { pendingBitmap = lastBitmap; useBitmap = false; bmpTex = 0 }
        if (surfaceTexture != null) { surfaceTexture = null; oesTex = 0; view.post { onNeedCamera?.invoke() } }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { viewW = width; viewH = height }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        handleBitmapRequests()
        connectPendingCamera()
        handleStopRecording()

        // ---- Pull the newest camera frame
        surfaceTexture?.let { st ->
            if (frameAvailable.getAndSet(false)) {
                try { st.updateTexImage(); st.getTransformMatrix(stMatrix); gotFrame = true }
                catch (e: Exception) { Log.w(TAG, "updateTexImage failed", e) }
            }
        }
        val ready = if (useBitmap) bmpTex != 0 else (gotFrame && oesTex != 0 && surfaceTexture != null)
        if (!ready) return

        val s = settings

        // ---- Wobble steps ("animated on twos")
        val now = System.nanoTime()
        var stepped = false
        if (animate && now - lastBoilNs >= (1_000_000_000.0 / s.boilFps).toLong()) {
            lastBoilNs = now; seed = (seed + 1f) % 997f; stepped = true
        }

        // ---- Upright image size
        val w: Int; val h: Int
        if (useBitmap) { w = bmpW; h = bmpH }
        else {
            // The camera's SurfaceTexture already turns frames upright for a portrait phone,
            // so the upright image is the sensor size with width/height swapped.
            var nw = min(camW, camH); var nh = maxOf(camW, camH)
            if (s.rotationFix % 2 == 1) { val t = nw; nw = nh; nh = t }
            w = nw; h = nh
        }
        if (!ensureTargets(w, h)) return
        procSize = Size(w, h)
        val k = min(w, h) / 720f                       // slider units -> pixels
        val src = srcT!!; val a = blurA!!; val b = blurB!!

        // ---- Pass 0: source -> upright RGBA image
        val updateSource = !srcValid || if (useBitmap) false else (!animate || stepped || !s.holdFrames)
        if (updateSource) {
            bindTarget(src)
            if (useBitmap) {
                GLES20.glUseProgram(pCopy2D)
                bindTex(0, bmpTex); GLES20.glUniform1i(loc(pCopy2D, "u_img"), 0)
            } else {
                GLES20.glUseProgram(pCopy)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
                GLES20.glUniform1i(loc(pCopy, "u_cam"), 0)
                GLES20.glUniformMatrix4fv(loc(pCopy, "u_st"), 1, false, stMatrix, 0)
                GLES20.glUniform1f(loc(pCopy, "u_rot"), (s.rotationFix * 90).toFloat())
                GLES20.glUniform1f(loc(pCopy, "u_mirror"), if (s.mirror) 1f else 0f)
            }
            drawQuad(pCopy)
            srcValid = true
        }

        // ---- Passes 1 & 2: blurs (in 720-units via k)
        bindTarget(a); GLES20.glUseProgram(pBlurH)
        bindTex(0, src.tex); GLES20.glUniform1i(loc(pBlurH, "u_src"), 0)
        GLES20.glUniform2f(loc(pBlurH, "u_texel"), k / w, k / h)
        GLES20.glUniform1f(loc(pBlurH, "u_s1"), s.width)
        drawQuad(pBlurH)

        bindTarget(b); GLES20.glUseProgram(pBlurV)
        bindTex(0, a.tex); GLES20.glUniform1i(loc(pBlurV, "u_tex"), 0)
        GLES20.glUniform2f(loc(pBlurV, "u_texel"), k / w, k / h)
        GLES20.glUniform1f(loc(pBlurV, "u_s1"), s.width)
        drawQuad(pBlurV)

        // ---- Pass 3 to the screen, fitted inside the view
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val scale = min(viewW.toFloat() / w, viewH.toFloat() / h)
        val vw = (w * scale).toInt(); val vh = (h * scale).toInt()
        GLES20.glViewport((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
        drawSketch(s, src, b, w, h, k)

        // ---- Video encoder
        handleRecording(s, src, b, w, h, k, now)

        // ---- Photo / save
        photoReq?.let { req ->
            photoReq = null
            val bmp = try { renderPhoto(s, src, b, w, h, k, req.rotation) } catch (e: Exception) { Log.e(TAG, "photo failed", e); null }
            view.post { req.cb(bmp) }
        }

        // A still photo with wobble on needs a steady drumbeat to keep redrawing
        if (useBitmap && animate) view.postDelayed({ view.requestRender() }, 30)
    }

    // ========================================================================
    // Sources
    // ========================================================================
    private fun connectPendingCamera() {
        val pr = pending ?: return
        pending = null
        val request = pr.request
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        st.setOnFrameAvailableListener { frameAvailable.set(true); view.requestRender() }
        val surface = Surface(st)
        oesTex = tex; surfaceTexture = st
        camW = request.resolution.width; camH = request.resolution.height
        gotFrame = false; srcValid = false
        request.provideSurface(surface, pr.exec) {
            surface.release()
            view.queueEvent {
                if (surfaceTexture === st) { surfaceTexture = null; oesTex = 0 }
                st.release()
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            }
        }
    }

    private fun handleBitmapRequests() {
        if (clearBitmapReq) {
            clearBitmapReq = false; pendingBitmap = null
            if (bmpTex != 0) GLES20.glDeleteTextures(1, intArrayOf(bmpTex), 0)
            bmpTex = 0; useBitmap = false; lastBitmap = null; srcValid = false
        }
        val bmp = pendingBitmap ?: return
        pendingBitmap = null
        if (bmpTex != 0) GLES20.glDeleteTextures(1, intArrayOf(bmpTex), 0)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); bmpTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bmpTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmpW = bmp.width; bmpH = bmp.height
        lastBitmap = bmp; useBitmap = true; srcValid = false
    }

    // ========================================================================
    // Recording: draw the same sketch into the encoder's surface
    // ========================================================================
    private fun handleRecording(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float, now: Long) {
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()

        pendingEncSurface?.let { surf ->
            pendingEncSurface = null
            val cfgId = IntArray(1)
            EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, cfgId, 0)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val num = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_CONFIG_ID, cfgId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
            val es = if (num[0] > 0) EGL14.eglCreateWindowSurface(dpy, configs[0], surf, intArrayOf(EGL14.EGL_NONE), 0) else null
            if (es == null || es == EGL14.EGL_NO_SURFACE) {
                view.post { onRecordError?.invoke("This phone's GPU couldn't connect to the video recorder.") }
            } else { encSurface = es; lastEncNs = 0L }
        }

        val es = encSurface ?: return
        if (now - lastEncNs < 30_000_000L) return       // cap at ~30 fps
        lastEncNs = now
        val drawS = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(dpy, es, es, ctx)) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        drawSketch(s, src, blur, w, h, k)
        EGLExt.eglPresentationTimeANDROID(dpy, es, now)
        EGL14.eglSwapBuffers(dpy, es)
        EGL14.eglMakeCurrent(dpy, drawS, readS, ctx)
    }

    private fun handleStopRecording() {
        val done = stopEncReq ?: return
        stopEncReq = null
        encSurface?.let { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) }
        encSurface = null
        view.post { done() }
    }

    // ========================================================================
    // Drawing helpers
    // ========================================================================
    private fun drawSketch(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float) {
        GLES20.glUseProgram(pDraw)
        bindTex(0, blur.tex); GLES20.glUniform1i(loc(pDraw, "u_blur"), 0)
        bindTex(1, src.tex); GLES20.glUniform1i(loc(pDraw, "u_src"), 1)
        GLES20.glUniform2f(loc(pDraw, "u_res"), w / k, h / k)       // "virtual 720" resolution
        GLES20.glUniform1f(loc(pDraw, "u_seed"), seed)
        GLES20.glUniform1f(loc(pDraw, "u_strength"), s.strength)
        GLES20.glUniform1f(loc(pDraw, "u_thr"), s.threshold())
        GLES20.glUniform1f(loc(pDraw, "u_hatch"), s.hatch)
        GLES20.glUniform1f(loc(pDraw, "u_spacing"), s.spacing)
        GLES20.glUniform1f(loc(pDraw, "u_boil"), if (animate) s.boil else 0f)
        GLES20.glUniform1f(loc(pDraw, "u_grain"), s.grain)
        GLES20.glUniform1f(loc(pDraw, "u_style"), s.style.toFloat())
        val ink = s.inkRgb()
        GLES20.glUniform3f(loc(pDraw, "u_ink"), ink[0], ink[1], ink[2])
        drawQuad(pDraw)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun renderPhoto(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float, rotation: Int): Bitmap {
        val t = makeTarget(w, h, halfFloat = false) ?: throw IllegalStateException("no photo target")
        bindTarget(t)
        drawSketch(s, src, blur, w, h, k)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        deleteTarget(t)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buf.rewind()
        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buf)
        // GPU rows are upside down; then turn to match how the phone was held
        val m = Matrix().apply { preScale(1f, -1f); postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
        raw.recycle()
        return out
    }

    private fun ensureTargets(w: Int, h: Int): Boolean {
        if (w == procW && h == procH && srcT != null) return true
        listOf(srcT, blurA, blurB).forEach { it?.let(::deleteTarget) }
        srcT = makeTarget(w, h, halfFloat = false)
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
        if (halfFloat) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!ok) { GLES20.glDeleteFramebuffers(1, f, 0); GLES20.glDeleteTextures(1, t, 0); return null }
        return Target(f[0], t[0], w, h)
    }

    private fun deleteTarget(t: Target) {
        GLES20.glDeleteFramebuffers(1, intArrayOf(t.fb), 0)
        GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0)
    }
    private fun bindTarget(t: Target) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fb); GLES20.glViewport(0, 0, t.w, t.h) }
    private fun bindTex(unit: Int, tex: Int) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex) }

    @Suppress("UNUSED_PARAMETER")
    private fun drawQuad(prog: Int) {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private val locCache = HashMap<String, Int>()
    private fun loc(prog: Int, name: String): Int = locCache.getOrPut("$prog/$name") { GLES20.glGetUniformLocation(prog, name) }

    private fun program(vs: String, fs: String): Int {
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
        GLES20.glShaderSource(s, src.trimStart())
        GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object { private const val TAG = "PencilCam" }
}
