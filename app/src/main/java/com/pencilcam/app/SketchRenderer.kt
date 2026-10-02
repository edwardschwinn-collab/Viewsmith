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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ============================================================================
 * SketchRenderer
 * This is the part that actually draws. It runs on its own thread (the GL
 * thread) that GLSurfaceView sets up, and it is the only code allowed to
 * talk to the graphics chip.
 *
 * Where pictures come from (one at a time):
 *   - the live camera
 *   - a gallery photo (File mode)
 *   - a video file (File mode), either playing on screen or being saved
 *
 * Where the drawing goes:
 *   - the screen, every frame
 *   - a saved photo, when asked
 *   - the camera video recorder, while recording in Video mode
 *   - the video file encoder, while saving a sketched copy of a video
 *
 * Other threads never touch the GPU directly. They drop a request into a
 * variable here and ask for a redraw. The next time onDrawFrame runs on the
 * GL thread, it picks the request up and handles it.
 *
 * Sizing: all the slider values (stroke spacing, wobble, blur) are in
 * "720 units". Bigger pictures get a scale factor k so the drawing looks the
 * same on a 720p video, a 4K video, or a 12 MP photo.
 * ============================================================================
 */
class SketchRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {

    // ---- Set by the main screen
    @Volatile var settings = SketchSettings()
    @Volatile var animate = true                       // false in Photo mode: the drawing holds still
    @Volatile var onNeedCamera: (() -> Unit)? = null   // GPU was reset, please reconnect the camera
    @Volatile var onNeedVideo: (() -> Unit)? = null    // GPU was reset, please reopen the video
    @Volatile var onRecordError: ((String) -> Unit)? = null
    /** Size of the picture being drawn right now. The screen reads this before recording. */
    @Volatile var procSize = Size(0, 0); private set
    /**
     * How big to draw the camera picture, as its short side in pixels
     * (720, 1080, 1440, 2160). 0 = just use whatever size the camera sends.
     * Video mode sets this to the recording size. The drawing is made up of
     * strokes the GPU draws fresh every frame, so drawing bigger than the
     * camera sends still gives crisp lines.
     */
    @Volatile var cameraShort = 0
    /** Short side for video files playing on screen in File mode (saving uses its own size). */
    @Volatile var videoPreviewShort = 1080
    /** Which picture edge is the viewer's bottom (0, 90, 180, 270), for the polaroid border and glossy shine. */
    @Volatile var frameRot = 0

    // ---- Camera input
    // CameraX asks for a Surface to send frames into. I make one from a
    // SurfaceTexture, which turns each frame into an OES texture on the GPU.
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

    // ---- Video file input (File mode)
    // Works like the camera: the video decoder sends frames into a Surface
    // that I make from a SurfaceTexture. The video's rotation setting is
    // applied here (in Pass 0) so the drawing is always upright.
    private class VideoReq(val w: Int, val h: Int, val rotation: Int, val cb: (Surface) -> Unit)
    @Volatile private var pendingVideo: VideoReq? = null
    @Volatile private var clearVideoReq = false
    private var vidTex = 0
    private var vidST: SurfaceTexture? = null
    private var vidSurface: Surface? = null
    private val vidFrameAvailable = AtomicBoolean(false)
    private val vidMatrix = FloatArray(16)
    private var vidW = 0; private var vidH = 0; private var vidRot = 0
    private var vidGotFrame = false
    private var useVideo = false

    // ---- Saving a video file through the sketch ("export")
    // The exporter feeds decoded frames in one at a time. For each one I draw
    // the sketch into the encoder's Surface, stamp it with the frame's own
    // time, and tell the exporter it's done so it can send the next frame.
    private class ExportReq(val surface: Surface, val w: Int, val h: Int, val short: Int,
                            val onFrame: (Long) -> Unit, val onReady: (Boolean) -> Unit)
    @Volatile private var pendingExport: ExportReq? = null
    @Volatile private var stopExportReq: (() -> Unit)? = null
    /** The exporter sets this to the time stamp (ns) of the frame it just sent, so I know which frame to save. */
    @Volatile var exportExpectNs = -1L
    private var exportEgl: EGLSurface? = null
    private var exportW = 0; private var exportH = 0; private var exportShort = 1080
    private var exportOnFrame: ((Long) -> Unit)? = null
    private var exportClockNs = 0L

    // ---- Photo / save request
    private class PhotoReq(val rotation: Int, val cb: (Bitmap?) -> Unit)
    @Volatile private var photoReq: PhotoReq? = null

    // ---- Camera recording (Video mode)
    // turn = true when the encoder only takes wide frames, so the drawing
    // goes in sideways (see DRAW_VS in Shaders.kt)
    private class EncReq(val surface: Surface, val w: Int, val h: Int, val turn: Boolean)
    @Volatile private var pendingEncSurface: EncReq? = null
    @Volatile private var stopEncReq: (() -> Unit)? = null
    private var encSurface: EGLSurface? = null
    private var encW = 0; private var encH = 0; private var encTurn = false
    private var lastEncNs = 0L

    // ---- Keeping up: are camera frames being drawn as fast as they arrive?
    // The camera thread counts frames arriving; the GL thread counts frames
    // it actually drew. If the GPU falls behind, frames get skipped and the
    // drawn count drops below the arrived count. Measured over ~1.5 seconds.
    private val camArrived = java.util.concurrent.atomic.AtomicLong(0)
    private var camDrawn = 0L
    private var perfStartNs = 0L; private var perfArrived0 = 0L; private var perfDrawn0 = 0L
    private var perfSizeW = 0; private var perfSizeH = 0
    /** The latest measurement, or null if there isn't a fresh one yet. */
    @Volatile var perf: Perf? = null; private set
    /** arrivedFps = camera frames per second, drawnFps = frames the GPU kept up with, at size w x h. */
    class Perf(val arrivedFps: Float, val drawnFps: Float, val w: Int, val h: Int)

    // ---- Shader programs, the full screen rectangle, and the off-screen images
    private var pCopy = 0; private var pCopy2D = 0; private var pBlurH = 0; private var pBlurV = 0; private var pDraw = 0
    private lateinit var quad: FloatBuffer
    /** An off-screen image the GPU can draw into (framebuffer + texture). */
    private class Target(val fb: Int, val tex: Int, val w: Int, val h: Int)
    private var srcT: Target? = null; private var blurA: Target? = null; private var blurB: Target? = null
    private var procW = 0; private var procH = 0
    private var viewW = 1; private var viewH = 1
    private var srcValid = false

    // ---- Wobble timing
    private var seed = 0f
    private var lastBoilNs = 0L

    // ========================================================================
    // Requests from other threads. Each one just leaves a note and asks for
    // a redraw; onDrawFrame does the real work on the GL thread.
    // ========================================================================
    fun provideSurface(request: SurfaceRequest, mainExec: Executor) {
        pending?.request?.willNotProvideSurface()
        pending = Pending(request, mainExec)
        view.requestRender()
    }
    fun showBitmap(b: Bitmap) { pendingBitmap = b; view.requestRender() }
    fun clearBitmap() { clearBitmapReq = true; view.requestRender() }

    /**
     * Get ready to show a video file. w and h are the video's stored size,
     * rotation is how far (clockwise) a player should turn it. cb gets the
     * Surface the decoder should send frames into (called on the main thread).
     */
    fun openVideo(w: Int, h: Int, rotation: Int, cb: (Surface) -> Unit) {
        pendingVideo = VideoReq(w, h, rotation, cb); view.requestRender()
    }
    /** Done with the video. Stop the decoder BEFORE calling this. */
    fun clearVideo() { clearVideoReq = true; view.requestRender() }

    /**
     * Start saving a video file. surface = the encoder's input, w x h = the
     * encoder's size, short = the short side to draw the clip at while saving.
     */
    fun startExport(surface: Surface, w: Int, h: Int, short: Int, onFrame: (Long) -> Unit, onReady: (Boolean) -> Unit) {
        pendingExport = ExportReq(surface, w, h, short, onFrame, onReady); view.requestRender()
    }
    fun stopExport(done: () -> Unit) { stopExportReq = done; view.requestRender() }

    fun requestPhoto(rotation: Int, cb: (Bitmap?) -> Unit) { photoReq = PhotoReq(rotation, cb); view.requestRender() }
    /** Start drawing into the camera recorder. w x h is the recorder's frame size; turn = feed it sideways. */
    fun startRecording(s: Surface, w: Int, h: Int, turn: Boolean) { pendingEncSurface = EncReq(s, w, h, turn); view.requestRender() }
    fun stopRecording(done: () -> Unit) { stopEncReq = done; view.requestRender() }

    // ========================================================================
    // GPU lifecycle
    // ========================================================================
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Two triangles covering the screen, corners from -1 to +1
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        locCache.clear()
        pCopy = program(Shaders.COPY_VS, Shaders.COPY_FS)
        pCopy2D = program(Shaders.QUAD_VS, Shaders.COPY2D_FS)
        pBlurH = program(Shaders.QUAD_VS, Shaders.BLUR_H_FS)
        pBlurV = program(Shaders.QUAD_VS, Shaders.BLUR_V_FS)
        pDraw = program(Shaders.DRAW_VS, Shaders.DRAW_FS)

        // This runs on first start, and again if Android ever throws away the
        // GPU context. Either way, any old textures are gone, so start clean
        // and ask the screen to reconnect whatever was showing.
        srcT = null; blurA = null; blurB = null; procW = 0; procH = 0; srcValid = false
        encSurface = null; exportEgl = null
        if (useBitmap && lastBitmap != null) { pendingBitmap = lastBitmap; useBitmap = false; bmpTex = 0 }
        if (surfaceTexture != null) { surfaceTexture = null; oesTex = 0; view.post { onNeedCamera?.invoke() } }
        if (vidST != null) { vidST = null; vidSurface = null; vidTex = 0; useVideo = false; view.post { onNeedVideo?.invoke() } }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { viewW = width; viewH = height }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Pick up any notes left by other threads
        handleBitmapRequests()
        handleVideoRequests()
        connectPendingCamera()
        handleStopRecording()
        handleExportRequests()

        // ---- Pull in the newest camera frame, if one came in
        surfaceTexture?.let { st ->
            if (frameAvailable.getAndSet(false)) {
                try { st.updateTexImage(); st.getTransformMatrix(stMatrix); gotFrame = true; camDrawn++ }
                catch (e: Exception) { Log.w(TAG, "camera updateTexImage failed", e) }
            }
        }
        // ---- Same for the video file. Its time stamp tells the exporter which frame this is.
        var newVideoFrame = false
        var videoNs = 0L
        vidST?.let { st ->
            if (vidFrameAvailable.getAndSet(false)) {
                try {
                    st.updateTexImage(); st.getTransformMatrix(vidMatrix)
                    videoNs = st.timestamp; vidGotFrame = true; newVideoFrame = true
                } catch (e: Exception) { Log.w(TAG, "video updateTexImage failed", e) }
            }
        }

        // Which picture is showing: a photo beats a video beats the camera
        val ready = when {
            useBitmap -> bmpTex != 0
            useVideo -> vidGotFrame && vidST != null
            else -> gotFrame && oesTex != 0 && surfaceTexture != null
        }
        if (!ready) return

        val s = settings

        // ---- The clock. Normally real time. While saving a video it is the
        // video's own time, so the wobble lands at the same moments in the
        // saved file no matter how fast or slow the phone renders it.
        val exporting = exportEgl != null && useVideo
        val isExportFrame = exporting && newVideoFrame && exportExpectNs >= 0 && abs(videoNs - exportExpectNs) < 1_000_000L
        val now = when {
            isExportFrame -> { exportClockNs = videoNs; videoNs }
            exporting -> exportClockNs
            else -> System.nanoTime()
        }

        // ---- Wobble steps. The seed only changes a few times a second
        // ("Redraws per second"), like hand-drawn animation shot on twos.
        val period = (1_000_000_000.0 / s.boilFps).toLong()
        if (now < lastBoilNs) lastBoilNs = now - period      // clock jumped back (video looped or a save started)
        var stepped = false
        if (animate && now - lastBoilNs >= period) {
            lastBoilNs = now; seed = (seed + 1f) % 997f; stepped = true
        }

        // ---- Upright picture size
        val w: Int; val h: Int
        when {
            useBitmap -> { w = bmpW; h = bmpH }
            useVideo -> {
                val sz = videoProcSize(vidW, vidH, vidRot, if (exporting) exportShort else videoPreviewShort)
                w = sz.width; h = sz.height
            }
            else -> {
                // The camera's SurfaceTexture already turns frames upright for a
                // portrait phone, so upright is the sensor size with width and height swapped.
                var nw = min(camW, camH); var nh = max(camW, camH)
                if (s.rotationFix % 2 == 1) { val t = nw; nw = nh; nh = t }
                // Video mode: scale to the recording size (same shape)
                val cs = cameraShort
                if (cs > 0) {
                    val sc = cs.toFloat() / min(nw, nh)
                    nw = ((nw * sc).roundToInt() and 1.inv()).coerceAtLeast(2)
                    nh = ((nh * sc).roundToInt() and 1.inv()).coerceAtLeast(2)
                }
                w = nw; h = nh
            }
        }
        if (!ensureTargets(w, h)) return
        procSize = Size(w, h)
        if (!useBitmap && !useVideo) measurePerf(w, h)
        val k = min(w, h) / 720f                          // 720 units -> real pixels
        val src = srcT!!; val a = blurA!!; val b = blurB!!

        // ---- Pass 0: incoming picture -> upright color image (srcT).
        // With "Hold the video between redraws" on, the picture only updates
        // when the wobble steps, which gives the flipbook look.
        val updateSource = !srcValid || if (useBitmap) false else (!animate || stepped || !s.holdFrames)
        if (updateSource) {
            bindTarget(src)
            when {
                useBitmap -> {
                    GLES20.glUseProgram(pCopy2D)
                    bindTex(0, bmpTex); GLES20.glUniform1i(loc(pCopy2D, "u_img"), 0)
                }
                useVideo -> copyExternal(vidTex, vidMatrix, vidRot.toFloat(), false)
                else -> copyExternal(oesTex, stMatrix, (s.rotationFix * 90).toFloat(), s.mirror)
            }
            drawQuad()
            srcValid = true
        }

        // ---- Passes 1 and 2: the two blurs (texel step scaled by k, so widths are in 720 units)
        bindTarget(a); GLES20.glUseProgram(pBlurH)
        bindTex(0, src.tex); GLES20.glUniform1i(loc(pBlurH, "u_src"), 0)
        GLES20.glUniform2f(loc(pBlurH, "u_texel"), k / w, k / h)
        GLES20.glUniform1f(loc(pBlurH, "u_s1"), s.width)
        drawQuad()

        bindTarget(b); GLES20.glUseProgram(pBlurV)
        bindTex(0, a.tex); GLES20.glUniform1i(loc(pBlurV, "u_tex"), 0)
        GLES20.glUniform2f(loc(pBlurV, "u_texel"), k / w, k / h)
        GLES20.glUniform1f(loc(pBlurV, "u_s1"), s.width)
        drawQuad()

        // ---- Pass 3 to the screen, fitted inside the view with black bars if needed
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val scale = min(viewW.toFloat() / w, viewH.toFloat() / h)
        val vw = (w * scale).toInt(); val vh = (h * scale).toInt()
        GLES20.glViewport((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
        drawSketch(s, src, b, w, h, k)

        // ---- Camera recording (Video mode)
        handleRecording(s, src, b, w, h, k, now)

        // ---- Saving a video file: send this frame to the encoder, then tell the exporter
        if (isExportFrame) {
            drawToExport(s, src, b, w, h, k, videoNs)
            exportOnFrame?.invoke(videoNs)
        }

        // ---- Photo / save
        photoReq?.let { req ->
            photoReq = null
            val bmp = try { renderPhoto(s, src, b, w, h, k, req.rotation) } catch (e: Exception) { Log.e(TAG, "photo failed", e); null }
            view.post { req.cb(bmp) }
        }

        // A still photo has no new frames coming in, so with wobble on I keep
        // asking for redraws about 30 times a second to keep it moving.
        if (useBitmap && animate) view.postDelayed({ view.requestRender() }, 30)
    }

    /**
     * Every 1.5 seconds, work out how many camera frames came in per second
     * and how many actually got drawn. Starts over whenever the drawing size
     * changes, since a new size means new performance.
     */
    private fun measurePerf(w: Int, h: Int) {
        val now = System.nanoTime()
        if (w != perfSizeW || h != perfSizeH || perfStartNs == 0L) {
            perfSizeW = w; perfSizeH = h; perf = null
            perfStartNs = now; perfArrived0 = camArrived.get(); perfDrawn0 = camDrawn
            return
        }
        val dt = (now - perfStartNs) / 1e9f
        if (dt < 1.5f) return
        val arrived = camArrived.get()
        perf = Perf((arrived - perfArrived0) / dt, (camDrawn - perfDrawn0) / dt, w, h)
        perfStartNs = now; perfArrived0 = arrived; perfDrawn0 = camDrawn
    }

    /** Pass 0 for an OES source (camera or video): rotate, mirror, copy. */
    private fun copyExternal(tex: Int, matrix: FloatArray, rotation: Float, mirror: Boolean) {
        GLES20.glUseProgram(pCopy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(loc(pCopy, "u_cam"), 0)
        GLES20.glUniformMatrix4fv(loc(pCopy, "u_st"), 1, false, matrix, 0)
        GLES20.glUniform1f(loc(pCopy, "u_rot"), rotation)
        GLES20.glUniform1f(loc(pCopy, "u_mirror"), if (mirror) 1f else 0f)
    }

    // ========================================================================
    // Sources
    // ========================================================================

    /** Make a new OES texture plus SurfaceTexture. Camera and video both use one. */
    private fun makeOes(onFrame: () -> Unit): Pair<Int, SurfaceTexture> {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(tex)
        st.setOnFrameAvailableListener { onFrame(); view.requestRender() }
        return tex to st
    }

    private fun connectPendingCamera() {
        val pr = pending ?: return
        pending = null
        val request = pr.request
        val (tex, st) = makeOes { frameAvailable.set(true); camArrived.incrementAndGet() }
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        oesTex = tex; surfaceTexture = st
        camW = request.resolution.width; camH = request.resolution.height
        gotFrame = false; srcValid = false
        // When CameraX is done with this Surface (camera switched or closed), clean it up
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

    private fun handleVideoRequests() {
        if (clearVideoReq) { clearVideoReq = false; releaseVideo() }
        val r = pendingVideo ?: return
        pendingVideo = null
        releaseVideo()
        val (tex, st) = makeOes { vidFrameAvailable.set(true) }
        val surface = Surface(st)
        vidTex = tex; vidST = st; vidSurface = surface
        vidW = r.w; vidH = r.h; vidRot = ((r.rotation % 360) + 360) % 360
        vidGotFrame = false; vidFrameAvailable.set(false)
        useVideo = true; srcValid = false
        view.post { r.cb(surface) }
    }

    private fun releaseVideo() {
        vidSurface?.release(); vidSurface = null
        vidST?.release(); vidST = null
        if (vidTex != 0) GLES20.glDeleteTextures(1, intArrayOf(vidTex), 0)
        vidTex = 0; useVideo = false; vidGotFrame = false; srcValid = false
    }

    // ========================================================================
    // Encoder surfaces. To draw into a video encoder, I wrap its input
    // Surface in an EGL "window surface" made with the same GPU setup as the
    // screen (that's what RecordableConfigChooser picks), then switch drawing
    // over to it, draw, stamp the time, and switch back.
    // ========================================================================
    private fun makeEncoderEgl(surf: Surface): EGLSurface? {
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val cfgId = IntArray(1)
        EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, cfgId, 0)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_CONFIG_ID, cfgId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
        if (num[0] == 0) return null
        val es = try { EGL14.eglCreateWindowSurface(dpy, configs[0], surf, intArrayOf(EGL14.EGL_NONE), 0) }
                 catch (e: Exception) { Log.e(TAG, "encoder surface failed", e); null }
        return if (es == null || es == EGL14.EGL_NO_SURFACE) null else es
    }

    /**
     * Draw the sketch into an encoder surface at size ow x oh, stamped with
     * time tNs. The sketch is worked out per spot from 0 to 1 across the
     * picture, so it fills any output size; ow x oh doesn't have to match
     * the drawing size. turn = draw it a quarter turn sideways.
     */
    private fun drawInto(es: EGLSurface, ow: Int, oh: Int, tNs: Long, turn: Boolean,
                         s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float) {
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val drawS = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val readS = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(dpy, es, es, ctx)) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, ow, oh)
        drawSketch(s, src, blur, w, h, k, turn)
        EGLExt.eglPresentationTimeANDROID(dpy, es, tNs)
        EGL14.eglSwapBuffers(dpy, es)
        EGL14.eglMakeCurrent(dpy, drawS, readS, ctx)
    }

    // ---- Camera recording (Video mode)
    private fun handleRecording(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float, now: Long) {
        pendingEncSurface?.let { req ->
            pendingEncSurface = null
            val es = makeEncoderEgl(req.surface)
            if (es == null) view.post { onRecordError?.invoke("This phone's GPU couldn't connect to the video recorder.") }
            else { encSurface = es; encW = req.w; encH = req.h; encTurn = req.turn; lastEncNs = 0L }
        }
        val es = encSurface ?: return
        if (now - lastEncNs < 30_000_000L) return         // about 30 frames a second is plenty
        lastEncNs = now
        drawInto(es, encW, encH, now, encTurn, s, src, blur, w, h, k)
    }

    private fun handleStopRecording() {
        val done = stopEncReq ?: return
        stopEncReq = null
        encSurface?.let { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) }
        encSurface = null
        view.post { done() }
    }

    // ---- Saving a video file
    private fun handleExportRequests() {
        stopExportReq?.let { done ->
            stopExportReq = null
            exportEgl?.let { EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), it) }
            exportEgl = null; exportOnFrame = null; exportExpectNs = -1L
            done()
        }
        val r = pendingExport ?: return
        pendingExport = null
        val es = makeEncoderEgl(r.surface)
        if (es == null) { r.onReady(false); return }
        exportEgl = es; exportW = r.w; exportH = r.h; exportShort = r.short; exportOnFrame = r.onFrame
        exportClockNs = 0L; lastBoilNs = 0L; exportExpectNs = -1L
        // A frame left over from the on-screen playback must not end up in the saved file
        if (vidFrameAvailable.getAndSet(false)) try { vidST?.updateTexImage() } catch (_: Exception) {}
        r.onReady(true)
    }

    private fun drawToExport(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float, tNs: Long) {
        val es = exportEgl ?: return
        drawInto(es, exportW, exportH, tNs, false, s, src, blur, w, h, k)
    }

    // ========================================================================
    // Drawing helpers
    // ========================================================================

    /** Pass 3: run the drawing shader with every slider value, the look's own sliders, and the finishes. */
    private fun drawSketch(s: SketchSettings, src: Target, blur: Target, w: Int, h: Int, k: Float, turn: Boolean = false) {
        GLES20.glUseProgram(pDraw)
        GLES20.glUniform1f(loc(pDraw, "u_turn"), if (turn) 1f else 0f)
        GLES20.glUniform3f(loc(pDraw, "u_p"), s.lookParam(0), s.lookParam(1), s.lookParam(2))
        val on = s.finishOn; val fp = s.finishParams
        fun b(i: Int) = if (on[i]) 1f else 0f
        GLES20.glUniform4f(loc(pDraw, "u_finOn"), b(0), b(1), b(2), b(3))
        GLES20.glUniform4f(loc(pDraw, "u_finA"), fp[0], fp[2], fp[4], fp[6])     // each finish's first slider
        GLES20.glUniform4f(loc(pDraw, "u_finB"), fp[1], fp[3], fp[5], fp[7])     // and its second
        GLES20.glUniform1f(loc(pDraw, "u_frameRot"), frameRot.toFloat())
        bindTex(0, blur.tex); GLES20.glUniform1i(loc(pDraw, "u_blur"), 0)
        bindTex(1, src.tex); GLES20.glUniform1i(loc(pDraw, "u_src"), 1)
        GLES20.glUniform2f(loc(pDraw, "u_res"), w / k, h / k)       // picture size in 720 units
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
        drawQuad()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Draw the sketch at full size into an off-screen image and read it back as a Bitmap. */
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
        // The GPU reads rows bottom to top, so flip it, then turn it to match how the phone was held
        val m = Matrix().apply { preScale(1f, -1f); postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
        raw.recycle()
        return out
    }

    /** Make (or remake) the three off-screen images whenever the picture size changes. */
    private fun ensureTargets(w: Int, h: Int): Boolean {
        if (w == procW && h == procH && srcT != null) return true
        listOf(srcT, blurA, blurB).forEach { it?.let(::deleteTarget) }
        srcT = makeTarget(w, h, halfFloat = false)
        // The blurs keep more precision (16-bit float) when the GPU allows it, so faint edges survive
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

    /** Draw the full screen rectangle with whatever program is active. */
    private fun drawQuad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // Looking up a uniform by name is slow-ish, so remember the answers
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

    companion object {
        private const val TAG = "ViewSmith"

        /**
         * The upright size a video file is drawn at. A clip shot holding the
         * phone upright is stored sideways with rotation 90 or 270, so width
         * and height swap. Then scale so the short side is `short` pixels
         * (but never bigger than the clip itself), keeping sizes even
         * because video encoders want that.
         */
        fun videoProcSize(w: Int, h: Int, rotation: Int, short: Int): Size {
            val turned = ((rotation % 360) + 360) % 180 == 90
            val uw = if (turned) h else w; val uh = if (turned) w else h
            val sc = min(1f, short.toFloat() / min(uw, uh))
            val ow = ((uw * sc).roundToInt() and 1.inv()).coerceAtLeast(2)
            val oh = ((uh * sc).roundToInt() and 1.inv()).coerceAtLeast(2)
            return Size(ow, oh)
        }
    }
}
