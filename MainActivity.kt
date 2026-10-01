package com.pencilcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ============================================================================
 * MainActivity (v0.2)
 * The screen is built in code so it can re-arrange when you turn the phone:
 *   PORTRAIT  : drawing on top (4:3), black-glass control deck below
 *   LANDSCAPE : drawing fills the screen (16:9), dial floats over it
 * The app itself stays locked to portrait (like most camera apps) and uses
 * the accelerometer to know how you're holding it, so the camera never
 * restarts mid-shot and icons simply rotate to stay upright.
 * ============================================================================
 */
class MainActivity : AppCompatActivity() {

    // ---- Modes (match the dial)
    private var mode = ModeDial.PHOTO

    // ---- Views
    private lateinit var root: FrameLayout
    private lateinit var videoBox: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var deck: FrameLayout
    private lateinit var dial: ModeDial
    private lateinit var flipBtn: GlossButton
    private lateinit var modeLabel: TextView
    private lateinit var tabsCol: LinearLayout
    private lateinit var panel: ScrollView
    private lateinit var panelContent: LinearLayout
    private val tabs = LinkedHashMap<String, GlossTab>()
    private var openTab: String? = null

    // ---- State
    private lateinit var settings: SketchSettings
    private val prefs by lazy { getSharedPreferences("pencilcam", MODE_PRIVATE) }
    private var useFront = false
    private var deviceRot = 0              // 0, 90, 180, 270: how the phone is being held
    private var pendingRot = 0
    private var landscapeHeld = false
    private var loadedBitmap: Bitmap? = null
    private var recorder: VideoRecorder? = null
    private var recording = false
    private var recStart = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var lastW = 0; private var lastH = 0

    // ---- Permission + picker launchers
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("PencilCam needs the camera. Allow it in Settings > Apps > PencilCam.")
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast("No microphone: videos will record without sound.")
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri) else if (loadedBitmap == null) updateModeLabel()
    }

    // ========================================================================
    // Setup
    // ========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen camera look: hide status and navigation bars (swipe to peek)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        settings = SketchSettings.load(prefs)
        buildScreen()
        setMode(ModeDial.PHOTO, fromDial = false)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val snapped = when { o >= 315 || o < 45 -> 0; o < 135 -> 90; o < 225 -> 180; else -> 270 }
                if (snapped != pendingRot) {
                    pendingRot = snapped
                    handler.removeCallbacks(applyRotation)
                    handler.postDelayed(applyRotation, 350)   // wait until it's steady
                }
            }
        }
    }
    private val applyRotation = Runnable {
        if (recording) return@Runnable                       // keep everything fixed while filming
        deviceRot = pendingRot
        rotateIcons()
        val land = deviceRot == 90 || deviceRot == 270
        if (land != landscapeHeld) {
            landscapeHeld = land
            applyLayout(force = true)
            if (!(mode == ModeDial.FILE && loadedBitmap != null)) startCamera()   // switch 4:3 <-> 16:9
        }
    }

    override fun onResume() {
        super.onResume(); glView.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }
    override fun onPause() {
        orientationListener.disable()
        if (recording) stopRecording(fromPause = true)
        glView.onPause(); super.onPause()
    }

    // ========================================================================
    // Building the screen
    // ========================================================================
    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        // --- Drawing area (GPU view + side tabs + slide-out panel)
        videoBox = FrameLayout(this)
        root.addView(videoBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(RecordableConfigChooser())
            preserveEGLContextOnPause = true
        }
        renderer = SketchRenderer(glView)
        renderer.settings = settings.copy()
        renderer.onNeedCamera = { startCamera() }
        renderer.onRecordError = { msg -> toast(msg); stopRecording(failed = true) }
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        videoBox.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        tabsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (name in listOf("Style", "Lines", "Shade", "Paper", "Motion")) {
            val t = GlossTab(this, name).apply { setOnClickListener { toggleTab(name) }; contentDescription = "$name settings" }
            tabs[name] = t
            tabsCol.addView(t, LinearLayout.LayoutParams(dp(58), dp(58)).apply { topMargin = dp(5); bottomMargin = dp(5) })
        }
        videoBox.addView(tabsCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })

        panel = ScrollView(this).apply {
            background = GlossPanelDrawable(dp(18).toFloat()); visibility = View.GONE; isVerticalScrollBarEnabled = false
        }
        panelContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14)) }
        panel.addView(panelContent)
        videoBox.addView(panel, FrameLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(76) })

        // --- Control deck (dial, flip button, mode label)
        deck = FrameLayout(this)
        root.addView(deck, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230), Gravity.BOTTOM))

        dial = ModeDial(this).apply {
            onModeChanged = { m -> setMode(m, fromDial = true) }
            onShutter = { shutter() }
            onLongPress = { if (mode == ModeDial.FILE) openPicker() }
            contentDescription = "Shutter. Drag the ring to change mode."
        }
        deck.addView(dial, FrameLayout.LayoutParams(dp(164), dp(164), Gravity.CENTER))

        flipBtn = GlossButton(this).apply {
            contentDescription = "Switch camera"
            translationX = dp(122).toFloat()
            setOnClickListener { flipCamera() }
        }
        deck.addView(flipBtn, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))

        modeLabel = TextView(this).apply {
            setTextColor(Gloss.TEXT); textSize = 14f; gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        deck.addView(modeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { bottomMargin = dp(10) })

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyLayout(force = false) }
    }

    /** Arrange the drawing area and deck for portrait or landscape holding. */
    private fun applyLayout(force: Boolean) {
        val W = root.width; val H = root.height
        if (W == 0 || H == 0) return
        if (!force && W == lastW && H == lastH) return
        lastW = W; lastH = H

        val vb = videoBox.layoutParams as FrameLayout.LayoutParams
        val dk = deck.layoutParams as FrameLayout.LayoutParams
        if (!landscapeHeld) {
            // Drawing on top at 4:3 (portrait 3 wide x 4 tall), deck fills the rest
            val videoH = min(W * 4 / 3, H - dp(200))
            vb.width = W; vb.height = videoH; vb.gravity = Gravity.TOP
            dk.height = H - videoH
            deck.background = deckBackground(H - videoH)
        } else {
            // Full-screen drawing, deck floats over the bottom
            vb.width = W; vb.height = H; vb.gravity = Gravity.TOP
            dk.height = dp(230)
            deck.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }
        videoBox.layoutParams = vb; deck.layoutParams = dk
        (panel.layoutParams as FrameLayout.LayoutParams).height = (vb.height - dp(40)).coerceAtLeast(dp(200))
        panel.requestLayout()
    }

    /** Black-glass deck with a silver hairline at the top edge. */
    private fun deckBackground(h: Int): android.graphics.drawable.Drawable {
        val sd = ShapeDrawable(RectShape())
        sd.paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#9AA1AA"), Color.parseColor("#1E2125"), Color.parseColor("#0C0D0F"), Color.parseColor("#050506")),
            floatArrayOf(0f, 0.012f, 0.35f, 1f), Shader.TileMode.CLAMP)
        return sd
    }

    /** Turn icons so they read upright however the phone is held. */
    private fun rotateIcons() {
        val target = -deviceRot.toFloat()
        fun spin(v: View) {
            var d = (target - v.rotation) % 360f
            if (d > 180) d -= 360; if (d < -180) d += 360
            v.animate().rotation(v.rotation + d).setDuration(220).start()
        }
        spin(flipBtn); tabs.values.forEach { spin(it) }
        dial.iconRotation = target
    }

    // ========================================================================
    // Modes and the shutter
    // ========================================================================
    private fun setMode(m: Int, fromDial: Boolean) {
        if (recording) { dial.setMode(mode); return }
        val old = mode
        mode = m
        if (!fromDial) dial.setMode(m)
        renderer.animate = m != ModeDial.PHOTO                 // Photo: the drawing holds still
        tabs["Motion"]?.visibility = if (m == ModeDial.PHOTO) View.GONE else View.VISIBLE
        if (m == ModeDial.PHOTO && openTab == "Motion") closePanel()
        flipBtn.visibility = if (m == ModeDial.FILE) View.INVISIBLE else View.VISIBLE

        if (old == ModeDial.FILE && m != ModeDial.FILE && loadedBitmap != null) {
            loadedBitmap = null; dial.fileLoaded = false; renderer.clearBitmap()
        }
        when (m) {
            ModeDial.VIDEO -> { ensureMic(); startCamera() }
            ModeDial.PHOTO -> startCamera()
            ModeDial.FILE -> if (loadedBitmap == null && old != ModeDial.FILE) openPicker()
        }
        updateModeLabel()
        glView.requestRender()
    }

    private fun shutter() {
        when (mode) {
            ModeDial.PHOTO -> takePhoto()
            ModeDial.VIDEO -> if (recording) stopRecording() else startRecording()
            ModeDial.FILE -> if (loadedBitmap == null) openPicker() else saveDrawing()
        }
    }

    private fun updateModeLabel() {
        if (recording) return
        modeLabel.setTextColor(Gloss.TEXT)
        modeLabel.text = when (mode) {
            ModeDial.PHOTO -> "Photo"
            ModeDial.VIDEO -> "Video"
            else -> if (loadedBitmap == null) "File: tap to pick a photo" else "File: tap to save, hold to pick another"
        }
    }

    /** Degrees clockwise to turn saved output so it matches how the phone was held. */
    private fun outRotation(): Int = if (settings.mirror) (360 - deviceRot) % 360 else deviceRot

    // ========================================================================
    // Camera
    // ========================================================================
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            if (mode == ModeDial.FILE && loadedBitmap != null) { provider.unbindAll(); return@addListener }   // save battery

            // Portrait = 4:3, landscape = 16:9. Photo mode gets more pixels; video stays light.
            val ratio = if (landscapeHeld) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            val long = if (mode == ModeDial.PHOTO) 1920 else 1280
            val short = if (ratio == AspectRatio.RATIO_16_9) long * 9 / 16 else long * 3 / 4
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .setResolutionStrategy(ResolutionStrategy(Size(long, short), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            val exec = ContextCompat.getMainExecutor(this)
            preview.setSurfaceProvider(exec) { req -> renderer.provideSurface(req, exec) }
            val lens = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try { provider.unbindAll(); provider.bindToLifecycle(this, lens, preview) }
            catch (e: Exception) { toast("Camera couldn't start: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        if (recording) return
        useFront = !useFront
        settings.mirror = useFront
        pushSettings()
        startCamera()
    }

    private fun ensureMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ========================================================================
    // Photo mode + File mode saving
    // ========================================================================
    private fun takePhoto() {
        flash()
        renderer.requestPhoto(outRotation()) { bmp -> saveBitmap(bmp) }
    }

    private fun saveDrawing() {
        flash()
        renderer.requestPhoto(0) { bmp -> saveBitmap(bmp) }
    }

    private fun flash() {
        glView.animate().alpha(0.25f).setDuration(60).withEndAction { glView.animate().alpha(1f).setDuration(160).start() }.start()
    }

    private fun saveBitmap(bmp: Bitmap?) {
        if (bmp == null) { toast("Couldn't capture. Try again."); return }
        Thread {
            val ok = writeJpeg(bmp)
            runOnUiThread { toast(if (ok) "Saved to Pictures/PencilCam" else "Couldn't save the photo.") }
        }.start()
    }

    private fun writeJpeg(bmp: Bitmap): Boolean {
        val name = "PencilCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PencilCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null); true
        } catch (e: Exception) { contentResolver.delete(uri, null, null); false }
    }

    // ========================================================================
    // File mode: pick a photo from the gallery
    // ========================================================================
    private fun openPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun loadImage(uri: Uri) {
        modeLabel.text = "Loading..."
        Thread {
            val bmp = try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE        // GPU upload needs a normal bitmap
                    val s = info.size
                    val scale = min(1f, 2048f / max(s.width, s.height))      // keep under the GPU's comfort zone
                    dec.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
                }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp == null) { toast("Couldn't open that photo."); updateModeLabel(); return@runOnUiThread }
                if (mode != ModeDial.FILE) return@runOnUiThread
                loadedBitmap = bmp
                dial.fileLoaded = true
                renderer.showBitmap(bmp)
                startCamera()                                   // unbinds the camera while a photo is shown
                updateModeLabel()
            }
        }.start()
    }

    // ========================================================================
    // Video recording
    // ========================================================================
    private fun startRecording() {
        val size = renderer.procSize
        if (size.width == 0) { toast("Camera isn't ready yet."); return }
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = VideoRecorder(this)
        // Encoders like even sizes
        val w = size.width and 1.inv(); val h = size.height and 1.inv()
        if (!rec.prepare(w, h, outRotation(), audio)) { toast("Couldn't start recording."); return }
        try { rec.start() } catch (e: Exception) { rec.abandon(); toast("Couldn't start recording."); return }
        recorder = rec; recording = true
        dial.recording = true; dial.locked = true
        flipBtn.isEnabled = false; flipBtn.alpha = 0.4f
        recStart = SystemClock.elapsedRealtime()
        handler.post(recTimer)
        renderer.startRecording(rec.surface!!)
    }

    private fun stopRecording(failed: Boolean = false, fromPause: Boolean = false) {
        val rec = recorder ?: return
        recorder = null; recording = false
        handler.removeCallbacks(recTimer)
        dial.recording = false; dial.locked = false
        flipBtn.isEnabled = true; flipBtn.alpha = 1f
        updateModeLabel()
        if (fromPause) { renderer.stopRecording { }; rec.stop(); return }
        renderer.stopRecording {
            val ok = !failed && rec.stop()
            if (failed) rec.abandon()
            toast(if (ok) "Saved to Movies/PencilCam" else "Video wasn't saved.")
        }
    }

    private val recTimer = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            modeLabel.setTextColor(Gloss.REC_RED)
            modeLabel.text = "\u25CF REC  %02d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 500)
        }
    }

    // ========================================================================
    // Side tabs and slider groups
    // ========================================================================
    private fun toggleTab(name: String) {
        if (openTab == name) { closePanel(); return }
        openTab = name
        tabs.forEach { (n, t) -> t.selectedTab = n == name }
        buildGroup(name)
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f; panel.translationX = dp(40).toFloat()
            panel.animate().alpha(1f).translationX(0f).setDuration(180).start()
        }
        panel.scrollTo(0, 0)
    }

    private fun closePanel() {
        openTab = null
        tabs.values.forEach { it.selectedTab = false }
        panel.animate().alpha(0f).translationX(dp(40).toFloat()).setDuration(150)
            .withEndAction { panel.visibility = View.GONE }.start()
    }

    private val refreshers = mutableListOf<() -> Unit>()

    private fun buildGroup(name: String) {
        panelContent.removeAllViews(); refreshers.clear()
        when (name) {
            "Style" -> {
                heading("Drawing style")
                val group = RadioGroup(this)
                listOf("Pencil", "Ink pen", "Colored pencil").forEachIndexed { i, label ->
                    group.addView(RadioButton(this).apply {
                        id = 1000 + i; text = label; setTextColor(Gloss.TEXT)
                        buttonTintList = ColorStateList.valueOf(Gloss.SILVER_1)
                    })
                }
                group.setOnCheckedChangeListener { _, id -> if (id >= 1000) { settings.style = id - 1000; pushSettings() } }
                panelContent.addView(group)
                refreshers += { group.check(1000 + settings.style) }
                note("More looks (comic, halftone, watercolor) land here as they're built.")

                heading("Setup")
                check("Mirror image", { settings.mirror }, { settings.mirror = it })
                button("Rotate camera 90\u00B0") { settings.rotationFix = (settings.rotationFix + 1) % 4; pushSettings() }
                button("Reset all sliders") {
                    settings = SketchSettings(mirror = settings.mirror, rotationFix = settings.rotationFix)
                    pushSettings(); refreshers.forEach { it() }
                }
            }
            "Lines" -> {
                heading("Ink color")
                val inks = listOf(0xFF3D4049, 0xFF10121A, 0xFF1F3F9E, 0xFF6B4A2B, 0xFFB3261E, 0xFF1E6B45).map { it.toInt() }
                val row = LinearLayout(this)
                val dots = inks.map { c ->
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
                        setOnClickListener { settings.ink = c; pushSettings(); refreshers.forEach { it() } }
                        row.addView(this)
                    }
                }
                panelContent.addView(row)
                refreshers += {
                    dots.forEachIndexed { i, v ->
                        v.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(inks[i])
                            setStroke(dp(3), if (inks[i] == settings.ink) Gloss.SILVER_HI else Color.argb(90, 255, 255, 255))
                        }
                    }
                }
                heading("Outlines")
                slider("Darkness", 0f, 1f, 0.01f, { settings.strength }, { settings.strength = it }) { pct(it) }
                slider("Detail", 0f, 1f, 0.01f, { settings.detail }, { settings.detail = it }) { pct(it) }
                slider("Line softness", 0.6f, 2.8f, 0.05f, { settings.width }, { settings.width = it }) { "%.2f".format(it) }
            }
            "Shade" -> {
                heading("Shading")
                slider("Crosshatching", 0f, 1f, 0.01f, { settings.hatch }, { settings.hatch = it }) { pct(it) }
                slider("Stroke spacing", 3f, 14f, 0.5f, { settings.spacing }, { settings.spacing = it }) { "%.1f".format(it) }
            }
            "Paper" -> {
                heading("Paper")
                slider("Paper grain", 0f, 1f, 0.01f, { settings.grain }, { settings.grain = it }) { pct(it) }
            }
            "Motion" -> {
                heading("Hand-drawn wobble")
                slider("Wobble amount", 0f, 1f, 0.01f, { settings.boil }, { settings.boil = it }) { pct(it) }
                slider("Redraws per second", 4f, 30f, 1f, { settings.boilFps }, { settings.boilFps = it }) { "${it.roundToInt()}" }
                check("Hold the video between redraws", { settings.holdFrames }, { settings.holdFrames = it })
            }
        }
        refreshers.forEach { it() }
    }

    private fun pushSettings() {
        renderer.settings = settings.copy()
        settings.save(prefs)
        glView.requestRender()
    }

    // ---- Little builders for panel rows
    private fun heading(text: String) {
        panelContent.addView(TextView(this).apply {
            this.text = text; setTextColor(Gloss.SILVER_HI); textSize = 15f
            setPadding(0, dp(12), 0, dp(4)); paint.isFakeBoldText = true
        })
    }
    private fun note(text: String) {
        panelContent.addView(TextView(this).apply { this.text = text; setTextColor(Gloss.TEXT_DIM); textSize = 12f; setPadding(0, dp(4), 0, 0) })
    }
    private fun button(label: String, action: () -> Unit) {
        panelContent.addView(TextView(this).apply {
            text = label; setTextColor(Color.parseColor("#15171A")); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Gloss.SILVER_HI, Gloss.SILVER_1, Gloss.SILVER_2)).apply { cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    private fun slider(label: String, min: Float, max: Float, step: Float,
                       get: () -> Float, set: (Float) -> Unit, fmt: (Float) -> String) {
        val title = TextView(this).apply { setTextColor(Gloss.TEXT); textSize = 13f }
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(this).apply {
            this.max = steps
            progressTintList = ColorStateList.valueOf(Gloss.SILVER_1)
            progressBackgroundTintList = ColorStateList.valueOf(Gloss.SILVER_3)
            thumbTintList = ColorStateList.valueOf(Gloss.SILVER_HI)
        }
        fun value(p: Int) = min + p * step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = value(p); title.text = "$label: ${fmt(v)}"
                if (fromUser) { set(v); pushSettings() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        panelContent.addView(title)
        panelContent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshers += {
            val p = ((get() - min) / step).roundToInt().coerceIn(0, steps)
            bar.progress = p; title.text = "$label: ${fmt(value(p))}"
        }
    }
    private fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val box = CheckBox(this).apply { text = label; setTextColor(Gloss.TEXT); buttonTintList = ColorStateList.valueOf(Gloss.SILVER_1) }
        box.setOnCheckedChangeListener { _, on -> if (on != get()) { set(on); pushSettings() } }
        panelContent.addView(box)
        refreshers += { box.isChecked = get() }
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
