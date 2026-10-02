package com.pencilcam.app

import android.Manifest
import android.animation.ValueAnimator
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
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
 * MainActivity: the one and only screen in ViewSmith.
 *
 * ViewSmith takes the live camera, a photo, or a video and redraws it as a
 * hand-drawn picture: pencil, ink pen, colored pencil, comic, halftone,
 * watercolor, or chalkboard. Lines can wobble a little from frame to frame
 * the way hand-drawn animation does.
 *
 * The dial at the bottom picks the mode:
 *   PHOTO : live camera, tap to save a still drawing
 *   VIDEO : live camera, tap to start and stop recording the drawing
 *   FILE  : pick a photo or video from the gallery, tap to save it drawn
 *
 * How the screen is laid out:
 *   Holding it upright  : drawing on top (4:3), black glass control deck below
 *   Holding it sideways : drawing fills the screen (16:9), dial floats over it
 *
 * The screen itself is locked to portrait, like most camera apps. Instead
 * of letting Android rotate everything (which would restart the camera
 * mid-shot), I read the phone's tilt sensor and turn the icons, menus, and
 * settings cog myself so they always read right side up. Because of that,
 * every position below is worked out in the phone's PORTRAIT pixel grid,
 * even when you're holding it sideways.
 *
 * The screen is built in code rather than in an XML layout file, since it
 * rearranges itself so much.
 * ============================================================================
 */
class MainActivity : AppCompatActivity() {

    // ---- Which mode the dial is on
    private var mode = ModeDial.PHOTO

    // ---- Views
    private lateinit var root: FrameLayout
    private lateinit var videoBox: FrameLayout           // the drawing area: GPU view, tabs, panel
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var deck: FrameLayout               // control deck: dial, flip button, mode label
    private lateinit var dial: ModeDial
    private lateinit var flipBtn: GlossButton
    private lateinit var modeLabel: TextView
    private lateinit var tabsCol: LinearLayout
    private lateinit var panelHost: FrameLayout          // invisible box beside the tabs that holds the panel
    private lateinit var panel: MaxHeightScrollView      // the slide-out slider panel
    private lateinit var panelContent: LinearLayout
    private lateinit var panelBg: GlossPanelDrawable
    private lateinit var cogBtn: GlossButton             // settings gear, always at your bottom-left
    private lateinit var settingsPanel: MaxHeightScrollView
    private lateinit var settingsContent: LinearLayout
    private lateinit var settingsBg: GlossPanelDrawable
    private var settingsOpen = false
    private var cogCorner = -1                           // which corner the cog is parked in (same as deviceRot)
    private val tabs = LinkedHashMap<String, GlossTab>()
    private var openTab: String? = null

    // ---- State
    private lateinit var settings: SketchSettings
    // The settings file keeps its old name so nobody's saved look is lost (see SketchSettings)
    private val prefs by lazy { getSharedPreferences("pencilcam", MODE_PRIVATE) }
    private var useFront = false
    private var deviceRot = 0              // 0, 90, 180, 270: how the phone is being held
    private var pendingRot = 0
    private var landscapeHeld = false
    private var recorder: VideoRecorder? = null
    private var recording = false
    private var recStart = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var lastW = 0; private var lastH = 0

    // ---- File mode: what's loaded (a photo OR a video, never both)
    private var loadedBitmap: Bitmap? = null
    private var videoUri: Uri? = null
    private var videoInfo: VideoInfo? = null
    private var videoSurface: Surface? = null            // the renderer's input for the video decoder
    private var player: VideoPlayer? = null              // loops the video on screen
    private var exporter: VideoExporter? = null          // saving a sketched copy, when running
    private val exporting get() = exporter != null
    private fun fileLoaded() = loadedBitmap != null || videoUri != null

    // ---- App settings (the cog menu), saved in the same prefs file
    private var uiOpacity = 0.35f       // menu background, 0 = clear, 1 = solid
    private var haptics = true          // dial clicks
    private var keepAwake = true        // screen stays on while the app is open
    private var rememberLook = true     // reload the sliders and style on launch

    // ---- Permission requests and the gallery picker.
    // Android shows its own pop-up for these and calls back with the answer.
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("ViewSmith needs the camera. Allow it in Settings > Apps > ViewSmith.")
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast("No microphone: videos will record without sound.")
    }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPicked(uri) else if (!fileLoaded()) updateModeLabel()
    }

    // ========================================================================
    // Start up
    // ========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen: hide the status bar and navigation bar (swipe in from an edge to see them)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        loadUiPrefs()
        val saved = SketchSettings.load(prefs)
        // With "Remember my look" off, start from the defaults, but keep the camera rotation fix
        settings = if (rememberLook) saved else SketchSettings(rotationFix = saved.rotationFix)
        buildScreen()
        applyUiPrefs()
        setMode(ModeDial.PHOTO, fromDial = false)

        // The back gesture closes an open menu first. With nothing open, it works normally.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    settingsOpen -> closeSettings()
                    openTab != null -> closePanel()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    /**
     * Watches the tilt sensor. It reports 0 to 359 degrees; I snap that to
     * the nearest quarter turn, then wait 350 ms for it to settle so the
     * screen doesn't flicker while the phone is on its way around.
     */
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val snapped = when { o >= 315 || o < 45 -> 0; o < 135 -> 90; o < 225 -> 180; else -> 270 }
                if (snapped != pendingRot) {
                    pendingRot = snapped
                    handler.removeCallbacks(applyRotation)
                    handler.postDelayed(applyRotation, 350)
                }
            }
        }
    }
    private val applyRotation = Runnable {
        if (recording || exporting) return@Runnable          // keep everything still while filming or saving
        deviceRot = pendingRot
        val land = deviceRot == 90 || deviceRot == 270
        if (land != landscapeHeld) {
            landscapeHeld = land
            applyLayout(force = true)
            if (!(mode == ModeDial.FILE && fileLoaded())) startCamera()   // switch between 4:3 and 16:9
        }
        rotateIcons()
        placePanels()
        placeCog(animated = true)
        // Open menus jump to their new angle, so fade them back in instead of letting them pop
        for (v in listOf(panel, settingsPanel)) if (v.visibility == View.VISIBLE) {
            v.alpha = 0f; v.animate().alpha(1f).setDuration(200).start()
        }
    }

    override fun onResume() {
        super.onResume(); glView.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (mode == ModeDial.FILE && videoSurface != null) startPlayer()
    }
    override fun onPause() {
        orientationListener.disable()
        if (recording) stopRecording(fromPause = true)
        exporter?.cancel()                                   // leaving the app stops a video save
        stopPlayer()
        glView.onPause(); super.onPause()
    }

    // ========================================================================
    // Building the screen
    // ========================================================================
    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        // --- Drawing area (GPU view, side tabs, slide-out panel)
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
        renderer.onNeedVideo = { reopenVideo() }
        renderer.onRecordError = { msg -> toast(msg); stopRecording(failed = true) }
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY     // only redraw when something changes
        videoBox.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        tabsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (name in listOf("Style", "Lines", "Shade", "Paper", "Motion")) {
            val t = GlossTab(this, name).apply { setOnClickListener { toggleTab(name) }; contentDescription = "$name settings" }
            tabs[name] = t
            tabsCol.addView(t, LinearLayout.LayoutParams(dp(58), dp(58)).apply { topMargin = dp(5); bottomMargin = dp(5) })
        }
        videoBox.addView(tabsCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })

        // The slider panel sits inside panelHost, an invisible box right beside
        // the tabs. The panel is centered in that box and turned to face you,
        // so it reads upright whichever way the phone is held. Android turns
        // touches along with it, so the sliders still drag the right way.
        panelHost = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        videoBox.addView(panelHost, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
        panelBg = GlossPanelDrawable(dp(18).toFloat())
        panel = MaxHeightScrollView(this).apply {
            background = panelBg; visibility = View.GONE; isVerticalScrollBarEnabled = false
        }
        panelContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(14)) }
        panel.addView(panelContent)
        panelHost.addView(panel, FrameLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> hugTabs() }   // stay snug to the tabs when its size changes

        // --- Control deck (dial, flip button, mode label)
        deck = FrameLayout(this)
        root.addView(deck, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230), Gravity.BOTTOM))

        dial = ModeDial(this).apply {
            onModeChanged = { m -> setMode(m, fromDial = true) }
            onShutter = { shutter() }
            onLongPress = { if (mode == ModeDial.FILE && !exporting) openPicker() }
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

        // --- Settings cog: always in the bottom-left corner as YOU see the screen
        cogBtn = GlossButton(this, GlossButton.COG).apply {
            contentDescription = "Settings"
            setOnClickListener { toggleSettings() }
        }
        root.addView(cogBtn, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.LEFT))
        placeCog(animated = false)

        // --- Settings panel: centered on the screen, turned to face you
        settingsBg = GlossPanelDrawable(dp(20).toFloat())
        settingsPanel = MaxHeightScrollView(this).apply {
            background = settingsBg; visibility = View.GONE; isVerticalScrollBarEnabled = false
        }
        settingsContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(16)) }
        settingsPanel.addView(settingsContent)
        root.addView(settingsPanel, FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyLayout(force = false) }
    }

    /** Lay out the drawing area and the deck for holding the phone upright or sideways. */
    private fun applyLayout(force: Boolean) {
        val W = root.width; val H = root.height
        if (W == 0 || H == 0) return
        if (!force && W == lastW && H == lastH) return
        lastW = W; lastH = H

        val vb = videoBox.layoutParams as FrameLayout.LayoutParams
        val dk = deck.layoutParams as FrameLayout.LayoutParams
        if (!landscapeHeld) {
            // Drawing on top at 4:3 (3 wide by 4 tall), the deck takes what's left below
            val videoH = min(W * 4 / 3, H - dp(200))
            vb.width = W; vb.height = videoH; vb.gravity = Gravity.TOP
            dk.height = H - videoH
            deck.background = deckBackground(H - videoH)
        } else {
            // Drawing fills the screen, the deck floats over the bottom on a soft dark fade
            vb.width = W; vb.height = H; vb.gravity = Gravity.TOP
            dk.height = dp(230)
            deck.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }
        videoBox.layoutParams = vb; deck.layoutParams = dk
        placePanels()
    }

    /**
     * Size, place, and turn both menus for how the phone is held.
     * Remember, everything here is in the PORTRAIT pixel grid. Held sideways,
     * the panel's "width as you see it" actually runs up and down the
     * portrait screen. The tabs always hug the portrait right edge, which is
     * your bottom edge (left side up) or top edge (right side up).
     */
    private fun placePanels() {
        val W = root.width; val H = root.height
        if (W == 0 || H == 0) return
        val vbH = (videoBox.layoutParams as FrameLayout.LayoutParams).height.let { if (it > 0) it else H }
        val turn = -deviceRot.toFloat()

        val host = panelHost.layoutParams as FrameLayout.LayoutParams
        val pl = panel.layoutParams as FrameLayout.LayoutParams
        if (!landscapeHeld) {
            // Upright: a 290dp column just left of the tabs
            host.leftMargin = W - dp(76) - dp(290); host.topMargin = dp(20)
            host.width = dp(290); host.height = (vbH - dp(40)).coerceAtLeast(dp(200))
            pl.width = dp(290)
            panel.maxHeight = host.height
        } else {
            // Sideways: a band beside the tabs, centered along them.
            // span = the panel's width as you see it, kept clear of the dial.
            val span = min(dp(380), H - dp(470)).coerceAtLeast(dp(240))
            host.leftMargin = dp(16); host.topMargin = (vbH - span) / 2
            host.width = W - dp(76) - dp(16); host.height = span
            pl.width = span
            panel.maxHeight = host.width          // its height as you see it = the short side of the screen
        }
        panelHost.layoutParams = host; panel.layoutParams = pl
        panel.rotation = turn

        // Settings: centered on the whole screen, sized to fit whichever way it's held
        val userW = if (landscapeHeld) H else W
        val userH = if (landscapeHeld) W else H
        val sl = settingsPanel.layoutParams as FrameLayout.LayoutParams
        sl.width = min(dp(330), userW - dp(40))
        settingsPanel.layoutParams = sl
        settingsPanel.maxHeight = userH - dp(60)
        settingsPanel.rotation = turn
        hugTabs()
    }

    /** Held sideways, slide the panel over so its edge sits right against the tabs. */
    private fun hugTabs() {
        panel.translationX = if (landscapeHeld && panel.height > 0) (panelHost.width - panel.height) / 2f else 0f
    }

    /** Park the cog in whichever corner is bottom-left from where you're looking. */
    private fun placeCog(animated: Boolean) {
        if (deviceRot == cogCorner) return
        val first = cogCorner == -1
        cogCorner = deviceRot
        val move = {
            val lp = cogBtn.layoutParams as FrameLayout.LayoutParams
            // deviceRot 90 = left side up, so your bottom-left is the phone's bottom-right, and so on around
            lp.gravity = when (cogCorner) {
                90 -> Gravity.BOTTOM or Gravity.RIGHT
                180 -> Gravity.TOP or Gravity.RIGHT
                270 -> Gravity.TOP or Gravity.LEFT
                else -> Gravity.BOTTOM or Gravity.LEFT
            }
            val m = dp(22); lp.setMargins(m, m, m, m)   // clear of the screen's rounded corners
            cogBtn.layoutParams = lp
            cogBtn.iconRotation = -cogCorner.toFloat()
        }
        if (!animated || first) { move(); return }
        // Fade out, hop to the new corner, fade back in
        cogBtn.animate().cancel()
        cogBtn.animate().alpha(0f).setDuration(110).withEndAction {
            move(); cogBtn.animate().alpha(1f).setDuration(170).start()
        }.start()
    }

    /** Black glass deck with a thin silver line along the top edge. */
    private fun deckBackground(h: Int): android.graphics.drawable.Drawable {
        val sd = ShapeDrawable(RectShape())
        sd.paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#9AA1AA"), Color.parseColor("#1E2125"), Color.parseColor("#0C0D0F"), Color.parseColor("#050506")),
            floatArrayOf(0f, 0.012f, 0.35f, 1f), Shader.TileMode.CLAMP)
        return sd
    }

    /** Turn the icons so they read upright however the phone is held. */
    private fun rotateIcons() {
        val target = -deviceRot.toFloat()
        // Spin each icon the short way round. Only the icon turns; the glass and shine stay lit from above.
        fun spin(get: () -> Float, set: (Float) -> Unit) {
            val from = get()
            var d = (target - from) % 360f
            if (d > 180) d -= 360; if (d < -180) d += 360
            ValueAnimator.ofFloat(from, from + d).apply {
                duration = 220
                addUpdateListener { set(it.animatedValue as Float) }
                start()
            }
        }
        spin({ flipBtn.iconRotation }, { flipBtn.iconRotation = it })
        tabs.values.forEach { t -> spin({ t.iconRotation }, { t.iconRotation = it }) }
        dial.iconRotation = target
    }

    // ========================================================================
    // Modes and the shutter
    // ========================================================================
    private fun setMode(m: Int, fromDial: Boolean) {
        if (recording || exporting) { dial.setMode(mode); return }    // no switching mid-recording or mid-save
        val old = mode
        mode = m
        if (!fromDial) dial.setMode(m)
        renderer.animate = m != ModeDial.PHOTO                 // in Photo mode the drawing holds still
        tabs["Motion"]?.visibility = if (m == ModeDial.PHOTO) View.GONE else View.VISIBLE
        if (m == ModeDial.PHOTO && openTab == "Motion") closePanel()
        flipBtn.visibility = if (m == ModeDial.FILE) View.INVISIBLE else View.VISIBLE

        if (old == ModeDial.FILE && m != ModeDial.FILE) clearFile()
        when (m) {
            ModeDial.VIDEO -> { ensureMic(); startCamera() }
            ModeDial.PHOTO -> startCamera()
            ModeDial.FILE -> if (!fileLoaded() && old != ModeDial.FILE) openPicker()
        }
        updateModeLabel()
        glView.requestRender()
    }

    /** The center of the dial was tapped. What it does depends on the mode. */
    private fun shutter() {
        when (mode) {
            ModeDial.PHOTO -> takePhoto()
            ModeDial.VIDEO -> if (recording) stopRecording() else startRecording()
            ModeDial.FILE -> when {
                exporting -> exporter?.cancel()                 // tap again to stop a save
                videoUri != null -> exportVideo()
                loadedBitmap != null -> saveDrawing()
                else -> openPicker()
            }
        }
    }

    private fun updateModeLabel() {
        if (recording || exporting) return
        modeLabel.setTextColor(Gloss.TEXT)
        modeLabel.text = when (mode) {
            ModeDial.PHOTO -> "Photo"
            ModeDial.VIDEO -> "Video"
            else -> when {
                videoUri != null -> "File: tap to save the video, hold to pick another"
                loadedBitmap != null -> "File: tap to save, hold to pick another"
                else -> "File: tap to pick a photo or video"
            }
        }
    }

    /** Degrees clockwise to turn a saved photo or video so it matches how the phone was held. */
    private fun outRotation(): Int = if (settings.mirror) (360 - deviceRot) % 360 else deviceRot

    // ========================================================================
    // Camera (CameraX)
    // ========================================================================
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // With a photo or video loaded in File mode the camera isn't needed, so turn it off to save battery
            if (mode == ModeDial.FILE && fileLoaded()) { provider.unbindAll(); return@addListener }

            // Upright = 4:3, sideways = 16:9. Photo mode gets more pixels; video stays lighter so it keeps up.
            val ratio = if (landscapeHeld) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            val long = if (mode == ModeDial.PHOTO) 1920 else 1280
            val short = if (ratio == AspectRatio.RATIO_16_9) long * 9 / 16 else long * 3 / 4
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                .setResolutionStrategy(ResolutionStrategy(Size(long, short), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            val exec = ContextCompat.getMainExecutor(this)
            // CameraX asks for somewhere to send frames; the renderer hands it a Surface
            preview.setSurfaceProvider(exec) { req -> renderer.provideSurface(req, exec) }
            val lens = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try { provider.unbindAll(); provider.bindToLifecycle(this, lens, preview) }
            catch (e: Exception) { toast("Camera couldn't start: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        if (recording) return
        useFront = !useFront
        settings.mirror = useFront              // selfies look right mirrored, like a mirror
        pushSettings()
        startCamera()
    }

    private fun ensureMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    // ========================================================================
    // Saving stills (Photo mode, and a photo in File mode)
    // ========================================================================
    private fun takePhoto() {
        flash()
        renderer.requestPhoto(outRotation()) { bmp -> saveBitmap(bmp) }
    }

    private fun saveDrawing() {
        flash()
        renderer.requestPhoto(0) { bmp -> saveBitmap(bmp) }   // gallery photos are already upright
    }

    /** A quick dim and back, so you can see the shot was taken. */
    private fun flash() {
        glView.animate().alpha(0.25f).setDuration(60).withEndAction { glView.animate().alpha(1f).setDuration(160).start() }.start()
    }

    private fun saveBitmap(bmp: Bitmap?) {
        if (bmp == null) { toast("Couldn't capture. Try again."); return }
        Thread {
            val ok = writeJpeg(bmp)
            runOnUiThread { toast(if (ok) "Saved to Pictures/ViewSmith" else "Couldn't save the photo.") }
        }.start()
    }

    /** Write a JPEG into Pictures/ViewSmith. IS_PENDING hides it from the gallery until it's fully written. */
    private fun writeJpeg(bmp: Bitmap): Boolean {
        val name = "ViewSmith_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ViewSmith")
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
    // File mode: pick a photo or video from the gallery
    // ========================================================================
    private fun openPicker() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    /** Something was picked. Check whether it's a video or a photo. */
    private fun loadPicked(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video/")) loadVideo(uri) else loadImage(uri)
    }

    /** Drop whatever File mode had loaded and stop the video, if one was playing. */
    private fun clearFile() {
        stopPlayer()
        if (videoUri != null) {
            renderer.clearVideo()
            videoUri = null; videoInfo = null; videoSurface = null
        }
        if (loadedBitmap != null) { loadedBitmap = null; renderer.clearBitmap() }
        dial.fileLoaded = false
    }

    private fun loadImage(uri: Uri) {
        modeLabel.text = "Loading..."
        Thread {
            val bmp = try {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE        // the GPU upload needs a plain bitmap in memory
                    val s = info.size
                    val scale = min(1f, 2048f / max(s.width, s.height))      // big photos get scaled to 2048 on the long side
                    dec.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
                }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp == null) { toast("Couldn't open that photo."); updateModeLabel(); return@runOnUiThread }
                if (mode != ModeDial.FILE) return@runOnUiThread
                // Switching from a video to a photo: shut the video down first
                if (videoUri != null) {
                    stopPlayer(); renderer.clearVideo()
                    videoUri = null; videoInfo = null; videoSurface = null
                }
                loadedBitmap = bmp
                dial.fileLoaded = true
                renderer.showBitmap(bmp)
                startCamera()                                   // turns the camera off while a file is showing
                updateModeLabel()
            }
        }.start()
    }

    private fun loadVideo(uri: Uri) {
        modeLabel.text = "Loading..."
        Thread {
            val info = VideoInfo.read(this, uri)
            runOnUiThread {
                if (info == null) { toast("Couldn't open that video."); updateModeLabel(); return@runOnUiThread }
                if (mode != ModeDial.FILE) return@runOnUiThread
                stopPlayer()
                if (loadedBitmap != null) { loadedBitmap = null; renderer.clearBitmap() }
                videoUri = uri; videoInfo = info; videoSurface = null
                dial.fileLoaded = true
                openVideoInRenderer(uri, info)
                startCamera()                                   // turns the camera off while a file is showing
                updateModeLabel()
            }
        }.start()
    }

    /** Ask the renderer for a Surface to decode into, then start playback once it's ready. */
    private fun openVideoInRenderer(uri: Uri, info: VideoInfo) {
        renderer.openVideo(info.width, info.height, info.rotation) { surface ->
            if (videoUri != uri) return@openVideo           // something else got picked in the meantime
            videoSurface = surface
            if (mode == ModeDial.FILE) startPlayer()
        }
    }

    /** The GPU was reset and the video's Surface went with it. Set it back up. */
    private fun reopenVideo() {
        val u = videoUri ?: return; val i = videoInfo ?: return
        stopPlayer(); videoSurface = null
        openVideoInRenderer(u, i)
    }

    private fun startPlayer() {
        val u = videoUri ?: return; val s = videoSurface ?: return
        if (exporting) return
        stopPlayer()
        player = VideoPlayer(this, u, s).also { it.start() }
    }

    private fun stopPlayer() {
        player?.stop(); player = null
    }

    /** Save a sketched copy of the loaded video. The dial turns red; tap it again to cancel. */
    private fun exportVideo() {
        val u = videoUri; val info = videoInfo; val surf = videoSurface
        if (u == null || info == null || surf == null) { toast("The video isn't ready yet."); return }
        if (openTab != null) closePanel()
        stopPlayer()                                         // the exporter takes over the decoder Surface
        val size = SketchRenderer.videoProcSize(info.width, info.height, info.rotation)
        val ex = VideoExporter(this, u, surf, renderer, size.width, size.height, info.durationUs,
            onProgress = { pct -> if (exporting) showSaving(pct) },
            onDone = { _, msg ->
                exporter = null
                dial.recording = false; dial.locked = false
                toast(msg)
                updateModeLabel()
                if (mode == ModeDial.FILE) startPlayer()     // back to looping the original
            })
        exporter = ex
        dial.recording = true; dial.locked = true
        showSaving(0)
        ex.start()
    }

    private fun showSaving(pct: Int) {
        modeLabel.setTextColor(Gloss.REC_RED)
        modeLabel.text = "Saving video $pct%   tap to cancel"
    }

    // ========================================================================
    // Video mode: recording the live drawing
    // ========================================================================
    private fun startRecording() {
        val size = renderer.procSize
        if (size.width == 0) { toast("Camera isn't ready yet."); return }
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val rec = VideoRecorder(this)
        // Video encoders want even numbers for width and height
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
        // Let the renderer finish its last frame before closing the file
        renderer.stopRecording {
            val ok = !failed && rec.stop()
            if (failed) rec.abandon()
            toast(if (ok) "Saved to Movies/ViewSmith" else "Video wasn't saved.")
        }
    }

    /** Updates the red REC timer twice a second while recording. */
    private val recTimer = object : Runnable {
        override fun run() {
            if (!recording) return
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            modeLabel.setTextColor(Gloss.REC_RED)
            modeLabel.text = "● REC  %02d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 500)
        }
    }

    // ========================================================================
    // Side tabs and their slide-out panel
    // ========================================================================
    private fun toggleTab(name: String) {
        if (openTab == name) { closePanel(); return }
        if (settingsOpen) closeSettings()
        openTab = name
        tabs.forEach { (n, t) -> t.selectedTab = n == name }
        buildGroup(name)
        popIn(panel)
        panel.scrollTo(0, 0)
    }

    private fun closePanel() {
        openTab = null
        tabs.values.forEach { it.selectedTab = false }
        popOut(panel) { openTab == null }
    }

    // Menus grow in and shrink out from their center, which looks right at any angle
    private fun popIn(v: View) {
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; v.alpha = 0f; v.scaleX = 0.92f; v.scaleY = 0.92f }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
    }
    private fun popOut(v: View, stillClosed: () -> Boolean) {
        v.animate().cancel()
        v.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(150)
            .withEndAction { if (stillClosed()) v.visibility = View.GONE }.start()
    }

    // ========================================================================
    // Settings (the cog)
    // ========================================================================
    private val settingsRefreshers = mutableListOf<() -> Unit>()

    private fun toggleSettings() { if (settingsOpen) closeSettings() else openSettings() }

    private fun openSettings() {
        if (openTab != null) closePanel()
        settingsOpen = true
        buildSettings()
        popIn(settingsPanel)
        settingsPanel.scrollTo(0, 0)
    }

    private fun closeSettings() {
        settingsOpen = false
        popOut(settingsPanel) { !settingsOpen }
    }

    private fun buildSettings() {
        settingsContent.removeAllViews(); settingsRefreshers.clear()
        target = settingsContent; targetRefreshers = settingsRefreshers

        heading("Settings")
        slider("Menu background", 0f, 100f, 1f, { uiOpacity * 100f }, { setMenuOpacity(it / 100f) }) { "${it.roundToInt()}%" }
        note("0% is clear glass so you can watch the drawing while you adjust. 100% is solid.")

        heading("Behavior")
        check("Haptic clicks on the dial", { haptics }, { haptics = it; applyUiPrefs(); saveUiPrefs() })
        check("Keep screen awake", { keepAwake }, { keepAwake = it; applyUiPrefs(); saveUiPrefs() })
        check("Remember my look between launches", { rememberLook }, { rememberLook = it; saveUiPrefs() })

        button("Close") { closeSettings() }
        button("Exit ViewSmith", danger = true) {
            // onPause still runs on the way out, so a video being recorded still gets saved
            finishAndRemoveTask()
        }
        note("ViewSmith v${appVersion()}")
        settingsRefreshers.forEach { it() }
    }

    /** The version number from app/build.gradle.kts, so there's only one place to change it. */
    private fun appVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun setMenuOpacity(v: Float) {
        uiOpacity = v.coerceIn(0f, 1f)
        panelBg.opacity = uiOpacity; settingsBg.opacity = uiOpacity
        saveUiPrefs()
    }

    private fun loadUiPrefs() {
        uiOpacity = prefs.getFloat("uiOpacity", 0.35f)
        haptics = prefs.getBoolean("haptics", true)
        keepAwake = prefs.getBoolean("keepAwake", true)
        rememberLook = prefs.getBoolean("rememberLook", true)
    }

    private fun saveUiPrefs() {
        prefs.edit().putFloat("uiOpacity", uiOpacity).putBoolean("haptics", haptics)
            .putBoolean("keepAwake", keepAwake).putBoolean("rememberLook", rememberLook).apply()
    }

    /** Put the app settings into effect on the live screen. */
    private fun applyUiPrefs() {
        panelBg.opacity = uiOpacity; settingsBg.opacity = uiOpacity
        dial.isHapticFeedbackEnabled = haptics          // the dial's clicks follow this switch
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ========================================================================
    // What's in each side tab
    // ========================================================================
    private val refreshers = mutableListOf<() -> Unit>()

    // The row builders below (heading, slider, check...) add to whichever menu
    // is being built right now, and register a "refresher" that sets the
    // control to the current value.
    private lateinit var target: LinearLayout
    private lateinit var targetRefreshers: MutableList<() -> Unit>

    private val styleNames = listOf("Pencil", "Ink pen", "Colored pencil", "Comic", "Halftone", "Watercolor", "Chalkboard")

    private fun buildGroup(name: String) {
        panelContent.removeAllViews(); refreshers.clear()
        target = panelContent; targetRefreshers = refreshers
        when (name) {
            "Style" -> {
                heading("Drawing style")
                val group = RadioGroup(this)
                styleNames.forEachIndexed { i, label ->
                    group.addView(RadioButton(this).apply {
                        id = 1000 + i; text = label; setTextColor(Gloss.TEXT); shade()
                        buttonTintList = ColorStateList.valueOf(Gloss.SILVER_1)
                    })
                }
                group.setOnCheckedChangeListener { _, id -> if (id >= 1000) { settings.style = id - 1000; pushSettings() } }
                panelContent.addView(group)
                refreshers += { group.check(1000 + settings.style) }
                note("Comic and Halftone use Stroke spacing (Shade tab) for their dot size.")

                heading("Setup")
                check("Mirror image", { settings.mirror }, { settings.mirror = it })
                button("Rotate camera 90°") { settings.rotationFix = (settings.rotationFix + 1) % 4; pushSettings() }
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
                slider("Shading", 0f, 1f, 0.01f, { settings.hatch }, { settings.hatch = it }) { pct(it) }
                slider("Stroke / dot spacing", 3f, 14f, 0.5f, { settings.spacing }, { settings.spacing = it }) { "%.1f".format(it) }
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

    /** Send the current look to the renderer, save it, and redraw. */
    private fun pushSettings() {
        renderer.settings = settings.copy()
        settings.save(prefs)
        glView.requestRender()
    }

    // ---- Small builders for menu rows
    /** Dark halo behind light text, so it stays readable over white paper when the menu is see-through. */
    private fun TextView.shade() = setShadowLayer(6f, 0f, 1.5f, Color.argb(235, 0, 0, 0))

    private fun heading(text: String) {
        target.addView(TextView(this).apply {
            this.text = text; setTextColor(Gloss.SILVER_HI); textSize = 15f
            setPadding(0, dp(12), 0, dp(4)); paint.isFakeBoldText = true; shade()
        })
    }
    private fun note(text: String) {
        target.addView(TextView(this).apply {
            this.text = text; setTextColor(Gloss.TEXT_DIM); textSize = 12f; setPadding(0, dp(4), 0, 0); shade()
        })
    }
    /** Silver push button. danger = red bold text, for Exit. */
    private fun button(label: String, danger: Boolean = false, action: () -> Unit) {
        target.addView(TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (danger) Color.parseColor("#A3170F") else Color.parseColor("#15171A"))
            if (danger) paint.isFakeBoldText = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Gloss.SILVER_HI, Gloss.SILVER_1, Gloss.SILVER_2)).apply { cornerRadius = dp(10).toFloat() }
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    /**
     * A labeled slider. Android's SeekBar only does whole steps from 0 up,
     * so the value is mapped: value = min + steps * step.
     */
    private fun slider(label: String, min: Float, max: Float, step: Float,
                       get: () -> Float, set: (Float) -> Unit, fmt: (Float) -> String) {
        val title = TextView(this).apply { setTextColor(Gloss.TEXT); textSize = 13f; shade() }
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
        target.addView(title)
        target.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        targetRefreshers += {
            val p = ((get() - min) / step).roundToInt().coerceIn(0, steps)
            bar.progress = p; title.text = "$label: ${fmt(value(p))}"
        }
    }
    private fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val box = CheckBox(this).apply { text = label; setTextColor(Gloss.TEXT); buttonTintList = ColorStateList.valueOf(Gloss.SILVER_1); shade() }
        box.setOnCheckedChangeListener { _, on -> if (on != get()) { set(on); pushSettings() } }
        target.addView(box)
        targetRefreshers += { box.isChecked = get() }
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
