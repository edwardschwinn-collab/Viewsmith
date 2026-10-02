package com.pencilcam.app

import android.content.SharedPreferences
import kotlin.math.pow

/**
 * ============================================================================
 * SketchSettings
 * Every slider and switch that changes how the drawing looks, kept in one
 * place. The screen edits a copy of this, hands it to the renderer, and
 * saves it so the app opens with the same look next time.
 *
 * Saved values live in Android's SharedPreferences under the file name
 * "pencilcam". That name is left over from before the app was called
 * ViewSmith. I kept it on purpose: changing it would make the app forget
 * everyone's saved look on the next update.
 * ============================================================================
 */
data class SketchSettings(
    var style: Int = 0,             // 0 pencil, 1 ink pen, 2 colored pencil, 3 comic,
                                    // 4 halftone, 5 watercolor, 6 chalkboard
    var strength: Float = 0.9f,     // outline darkness, 0..1
    var detail: Float = 0.55f,      // 0..1, higher picks up more small edges
    var width: Float = 1.2f,        // line softness (blur width), 0.6..2.8
    var hatch: Float = 0.7f,        // amount of shading (crosshatching or dots), 0..1
    var spacing: Float = 6f,        // stroke spacing (and dot size), 3..14
    var boil: Float = 0.55f,        // hand-drawn wobble amount, 0..1
    var boilFps: Float = 10f,       // wobble redraws per second, 4..30
    var holdFrames: Boolean = false,// freeze the video between redraws (flipbook look)
    var grain: Float = 0.55f,       // paper grain, 0..1
    var ink: Int = 0xFF3D4049.toInt(), // ink color (ARGB)
    var mirror: Boolean = false,    // flip left/right (turns on by itself for the selfie camera)
    var rotationFix: Int = 0        // extra quarter turns, in case a camera ever shows up sideways
) {
    /**
     * Turns the friendly "Detail" slider into the edge threshold the shader
     * wants. Low detail = high threshold = only strong edges get outlined.
     * The curve (power 1.6) spreads the useful range across the slider.
     */
    fun threshold(): Float = 0.045f * (1f - detail).pow(1.6f) + 0.002f

    /** Ink color split into red, green, blue from 0 to 1, the way the shader wants it. */
    fun inkRgb(): FloatArray = floatArrayOf(
        ((ink shr 16) and 0xFF) / 255f,
        ((ink shr 8) and 0xFF) / 255f,
        (ink and 0xFF) / 255f
    )

    /** Save every value so the app remembers the look between launches. */
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
        /** How many styles there are. The Style tab lists this many choices. */
        const val STYLE_COUNT = 7

        /** Load the saved values. Anything never saved falls back to the defaults above. */
        fun load(p: SharedPreferences): SketchSettings {
            val d = SketchSettings()
            return SketchSettings(
                style = p.getInt("style", d.style).coerceIn(0, STYLE_COUNT - 1),
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
