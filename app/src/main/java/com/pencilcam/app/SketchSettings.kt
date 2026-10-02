package com.pencilcam.app

import android.content.SharedPreferences
import kotlin.math.pow

/**
 * ============================================================================
 * SketchSettings
 * Every slider value in one place. These match the browser lab exactly,
 * so the JSON numbers you dialed in there carry straight over.
 * ============================================================================
 */
data class SketchSettings(
    var style: Int = 0,             // 0 = pencil, 1 = ink pen, 2 = colored pencil
    var strength: Float = 0.9f,     // outline darkness, 0..1
    var detail: Float = 0.55f,      // 0..1, higher = more small edges
    var width: Float = 1.2f,        // line softness (blur sigma), 0.6..2.8
    var hatch: Float = 0.7f,        // crosshatching amount, 0..1
    var spacing: Float = 6f,        // stroke spacing in pixels, 3..14
    var boil: Float = 0.55f,        // hand-drawn wobble amount, 0..1
    var boilFps: Float = 10f,       // redraws per second, 4..30
    var holdFrames: Boolean = false,// freeze video between redraws (flipbook look)
    var grain: Float = 0.55f,       // paper grain, 0..1
    var ink: Int = 0xFF3D4049.toInt(), // ink color (ARGB)
    var mirror: Boolean = false,    // flip left/right (auto-on for selfie camera)
    var rotationFix: Int = 0        // setup helper: extra 90-degree turns if a camera ever shows sideways
) {
    /** Converts the friendly "detail" slider into the shader's edge threshold (same math as the lab). */
    fun threshold(): Float = 0.045f * (1f - detail).pow(1.6f) + 0.002f

    /** Ink color as 0..1 RGB floats for the shader. */
    fun inkRgb(): FloatArray = floatArrayOf(
        ((ink shr 16) and 0xFF) / 255f,
        ((ink shr 8) and 0xFF) / 255f,
        (ink and 0xFF) / 255f
    )

    /** Save every value so the app remembers your look between launches. */
    fun save(p: SharedPreferences) {
        p.edit()
            .putInt("style", style).putFloat("strength", strength).putFloat("detail", detail)
            .putFloat("width", width).putFloat("hatch", hatch).putFloat("spacing", spacing)
            .putFloat("boil", boil).putFloat("boilFps", boilFps).putBoolean("holdFrames", holdFrames)
            .putFloat("grain", grain).putInt("ink", ink).putBoolean("mirror", mirror)
            .putInt("rotFix2", rotationFix)
            .apply()
    }

    companion object {
        /** Load saved values, falling back to the defaults above. */
        fun load(p: SharedPreferences): SketchSettings {
            val d = SketchSettings()
            return SketchSettings(
                style = p.getInt("style", d.style),
                strength = p.getFloat("strength", d.strength),
                detail = p.getFloat("detail", d.detail),
                width = p.getFloat("width", d.width),
                hatch = p.getFloat("hatch", d.hatch),
                spacing = p.getFloat("spacing", d.spacing),
                boil = p.getFloat("boil", d.boil),
                boilFps = p.getFloat("boilFps", d.boilFps),
                holdFrames = p.getBoolean("holdFrames", d.holdFrames),
                grain = p.getFloat("grain", d.grain),
                ink = p.getInt("ink", d.ink),
                mirror = p.getBoolean("mirror", d.mirror),
                rotationFix = p.getInt("rotFix2", d.rotationFix)
            )
        }
    }
}
