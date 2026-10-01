package com.pencilcam.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ============================================================================
 * MainActivity: the one and only screen.
 *   - Asks for camera permission
 *   - Starts the camera and feeds it to the SketchRenderer
 *   - Builds the "Adjust" panel of sliders
 *   - Saves photos to Pictures/PencilCam in your gallery
 * ============================================================================
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SketchRenderer
    private lateinit var settings: SketchSettings
    private var useFrontCamera = false

    private val prefs by lazy { getSharedPreferences("pencilcam", MODE_PRIVATE) }

    // Pops up Android's "Allow camera?" dialog and handles the answer
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "PencilCam needs the camera to draw. Allow it in Settings > Apps > PencilCam.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SketchSettings.load(prefs)

        // ---- GPU drawing surface
        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(3)
        glView.preserveEGLContextOnPause = true
        renderer = SketchRenderer(glView)
        renderer.settings = settings.copy()
        renderer.onNeedCamera = { startCamera() }
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // ---- Keep the button bar above Android's gesture/navigation bar
        val bottomArea = findViewById<View>(R.id.bottomArea)
        ViewCompat.setOnApplyWindowInsetsListener(bottomArea) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        // ---- Buttons
        val panel = findViewById<View>(R.id.panel)
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.btnShutter).setOnClickListener { takePhoto() }
        findViewById<MaterialButton>(R.id.btnSwitch).setOnClickListener {
            useFrontCamera = !useFrontCamera
            settings.mirror = useFrontCamera          // selfie camera looks natural mirrored
            pushSettings(rebuild = true)
            startCamera()
        }

        buildPanel()

        // ---- Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause() { glView.onPause(); super.onPause() }

    // ========================================================================
    // Camera
    // ========================================================================
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // Ask for about 720p: plenty for the drawing and keeps it smooth
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
            val mainExec = ContextCompat.getMainExecutor(this)
            preview.setSurfaceProvider(mainExec) { request -> renderer.provideSurface(request, mainExec) }

            val lens = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, lens, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera couldn't start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ========================================================================
    // Photos
    // ========================================================================
    private fun takePhoto() {
        renderer.requestPhoto { bmp ->
            if (bmp == null) { Toast.makeText(this, "Photo failed. Try again.", Toast.LENGTH_SHORT).show(); return@requestPhoto }
            Thread { // save off the main thread so the preview never stutters
                val ok = savePhoto(bmp)
                runOnUiThread {
                    Toast.makeText(this, if (ok) "Saved to Pictures/PencilCam" else "Couldn't save the photo.", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    private fun savePhoto(bmp: Bitmap): Boolean {
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
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null); false
        }
    }

    // ========================================================================
    // The "Adjust" panel, built in code so adding a slider is one line
    // ========================================================================
    private lateinit var panelContent: LinearLayout
    private val refreshers = mutableListOf<() -> Unit>()   // re-sync controls after Reset

    private fun buildPanel() {
        panelContent = findViewById(R.id.panelContent)
        panelContent.removeAllViews(); refreshers.clear()

        heading("Style")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Pencil", "Ink pen", "Colored pencil").forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply { id = 1000 + i; text = label; setTextColor(Color.WHITE) })
        }
        group.setOnCheckedChangeListener { _, id -> settings.style = id - 1000; pushSettings() }
        panelContent.addView(group)
        refreshers += { group.check(1000 + settings.style) }

        heading("Ink color")
        val inks = listOf(0xFF3D4049, 0xFF10121A, 0xFF1F3F9E, 0xFF6B4A2B, 0xFFB3261E, 0xFF1E6B45).map { it.toInt() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dots = inks.map { c ->
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                setOnClickListener { settings.ink = c; pushSettings(); refreshers.forEach { it() } }
                row.addView(this)
            }
        }
        panelContent.addView(row)
        refreshers += {
            dots.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(inks[i])
                    setStroke(dp(3), if (inks[i] == settings.ink) Color.parseColor("#F2C230") else Color.parseColor("#55FFFFFF"))
                }
            }
        }

        heading("Outlines")
        slider("Darkness", 0f, 1f, 0.01f, { settings.strength }, { settings.strength = it }) { pct(it) }
        slider("Detail", 0f, 1f, 0.01f, { settings.detail }, { settings.detail = it }) { pct(it) }
        slider("Line softness", 0.6f, 2.8f, 0.05f, { settings.width }, { settings.width = it }) { "%.2f".format(it) }

        heading("Shading")
        slider("Crosshatching", 0f, 1f, 0.01f, { settings.hatch }, { settings.hatch = it }) { pct(it) }
        slider("Stroke spacing", 3f, 14f, 0.5f, { settings.spacing }, { settings.spacing = it }) { "%.1f px".format(it) }

        heading("Hand-drawn wobble")
        slider("Wobble amount", 0f, 1f, 0.01f, { settings.boil }, { settings.boil = it }) { pct(it) }
        slider("Redraws per second", 4f, 30f, 1f, { settings.boilFps }, { settings.boilFps = it }) { "${it.roundToInt()} per sec" }
        check("Hold the video between redraws", { settings.holdFrames }, { settings.holdFrames = it })

        heading("Paper")
        slider("Paper grain", 0f, 1f, 0.01f, { settings.grain }, { settings.grain = it }) { pct(it) }

        heading("Testing")
        check("Mirror image", { settings.mirror }, { settings.mirror = it })
        panelContent.addView(MaterialButton(this).apply {
            text = "Rotate preview 90°"
            setOnClickListener { settings.rotationFix = (settings.rotationFix + 1) % 4; pushSettings() }
        })
        panelContent.addView(MaterialButton(this).apply {
            text = "Reset to defaults"
            setOnClickListener {
                val keepMirror = settings.mirror; val keepRot = settings.rotationFix
                settings = SketchSettings(mirror = keepMirror, rotationFix = keepRot)
                pushSettings(rebuild = true)
            }
        })

        refreshers.forEach { it() }
    }

    /** Send the current settings to the GPU thread and save them. */
    private fun pushSettings(rebuild: Boolean = false) {
        renderer.settings = settings.copy()
        settings.save(prefs)
        if (rebuild) refreshers.forEach { it() }
    }

    private fun heading(text: String) {
        panelContent.addView(TextView(this).apply {
            this.text = text; setTextColor(Color.parseColor("#F2C230")); textSize = 15f
            setPadding(0, dp(14), 0, dp(4))
        })
    }

    private fun slider(label: String, min: Float, max: Float, step: Float,
                       get: () -> Float, set: (Float) -> Unit, fmt: (Float) -> String) {
        val title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(this).apply { this.max = steps }
        fun value(p: Int) = min + p * step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = value(p); title.text = "$label: ${fmt(v)}"
                if (fromUser) { set(v); pushSettings() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        panelContent.addView(title); panelContent.addView(bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshers += {
            val p = ((get() - min) / step).roundToInt().coerceIn(0, steps)
            bar.progress = p; title.text = "$label: ${fmt(value(p))}"
        }
    }

    private fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val box = CheckBox(this).apply { text = label; setTextColor(Color.WHITE) }
        box.setOnCheckedChangeListener { _, on -> if (on != get()) { set(on); pushSettings() } }
        panelContent.addView(box)
        refreshers += { box.isChecked = get() }
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
