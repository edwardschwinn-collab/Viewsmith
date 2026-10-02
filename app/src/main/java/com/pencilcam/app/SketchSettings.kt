package com.pencilcam.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/**
 * ============================================================================
 * SketchSettings
 * Everything that changes how the drawing looks, kept in one place: the
 * chosen look, the general sliders, each look's own sliders, and the
 * finishes. The screen edits this, hands a copy (snapshot) to the
 * renderer, and saves it so the app opens with the same look next time.
 *
 * Saved values live in Android's SharedPreferences under the file name
 * "pencilcam". That name is left over from before the app was called
 * ViewSmith. I kept it on purpose: changing it would make the app forget
 * everyone's saved look on the next update.
 * ============================================================================
 */
data class SketchSettings(
    var style: Int = 0,             // which look, see Looks.names
    var strength: Float = 0.9f,     // outline darkness, 0..1
    var detail: Float = 0.55f,      // 0..1, higher picks up more small edges
    var width: Float = 1.2f,        // line softness (blur width), 0.6..2.8
    var hatch: Float = 0.7f,        // amount of crosshatch shading, 0..1
    var spacing: Float = 6f,        // stroke spacing (and comic dot spacing), 3..14
    var boil: Float = 0.55f,        // hand-drawn wobble amount, 0..1
    var boilFps: Float = 10f,       // wobble redraws per second, 1..30
    var holdFrames: Boolean = false,// freeze the video between redraws (flipbook look)
    var grain: Float = 0.55f,       // paper grain, 0..1
    var ink: Int = 0xFF3D4049.toInt(), // ink color (ARGB)
    var mirror: Boolean = false,    // flip left/right (turns on by itself for the selfie camera)
    var rotationFix: Int = 0,       // extra quarter turns, in case a camera ever shows up sideways
    /** Every look's own settings, 4 per look, in look order. */
    var lookParams: FloatArray = Looks.defaultLookParams(),
    /** Which finishes are switched on (vignette, matte, glossy, polaroid). */
    var finishOn: BooleanArray = BooleanArray(Looks.finishCount),
    /** Every finish's two sliders, in finish order. */
    var finishParams: FloatArray = Looks.defaultFinishParams(),
    /** Neon tube color (ARGB), picked on the Lines tab when Neon edges is the look. */
    var neonColor: Int = Looks.neonColors[0].second,
    /** Which random damage layout Old west uses. "New damage" picks a new number. */
    var damageSeed: Float = 17f,
    /** Polaroid caption: 0 off, 1 today's date, 2 custom text. */
    var captionMode: Int = 0,
    var captionText: String = "",
    /** Mix slider for each look: 1 = all look, 0 = the plain picture. */
    var mix: FloatArray = FloatArray(Looks.count) { 1f }
) {
    /** The Mix value for one look. */
    fun mixFor(style: Int): Float = mix.getOrElse(style) { 1f }
    /**
     * A fully separate copy. Plain copy() would share the lists above, so
     * a slider moving on the main thread could change a value halfway
     * through the GPU thread drawing a frame. The renderer always gets one of these.
     */
    fun snapshot(): SketchSettings = copy(
        lookParams = lookParams.copyOf(), finishOn = finishOn.copyOf(), finishParams = finishParams.copyOf(),
        mix = mix.copyOf())

    /**
     * The whole look written out as JSON text, for Favorites. Everything
     * except Mirror image and the camera rotation fix, which belong to the
     * phone and camera, not to the look.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("style", style); put("strength", strength.toDouble()); put("detail", detail.toDouble())
        put("width", width.toDouble()); put("hatch", hatch.toDouble()); put("spacing", spacing.toDouble())
        put("boil", boil.toDouble()); put("boilFps", boilFps.toDouble()); put("holdFrames", holdFrames)
        put("grain", grain.toDouble()); put("ink", ink); put("neonColor", neonColor)
        put("damageSeed", damageSeed.toDouble()); put("captionMode", captionMode); put("captionText", captionText)
        put("lookParams", JSONArray(lookParams.map { it.toDouble() }))
        put("finishOn", JSONArray(finishOn.toList()))
        put("finishParams", JSONArray(finishParams.map { it.toDouble() }))
        put("mix", JSONArray(mix.map { it.toDouble() }))
    }

    /** One of the current look's own settings (i = 0 to 3). */
    fun lookParam(i: Int): Float = lookParams[style * Looks.PARAMS + i]
    fun setLookParam(i: Int, v: Float) { lookParams[style * Looks.PARAMS + i] = v }

    /** How many finishes are switched on. Shown on the Finish tab's badge. */
    fun finishCount(): Int = finishOn.count { it }

    /** The short label shown over the viewfinder, like "Watercolor + Vignette + Polaroid". */
    fun summary(): String {
        val parts = mutableListOf(Looks.names[style])
        Looks.finishNames.forEachIndexed { i, n -> if (finishOn[i]) parts += n }
        return parts.joinToString(" + ")
    }

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
        val e = p.edit()
            .putInt("style", style).putFloat("strength", strength).putFloat("detail", detail)
            .putFloat("width", width).putFloat("hatch", hatch).putFloat("spacing", spacing)
            .putFloat("boil", boil).putFloat("boilFps", boilFps).putBoolean("holdFrames", holdFrames)
            .putFloat("grain", grain).putInt("ink", ink).putBoolean("mirror", mirror)
            .putInt("rotFix2", rotationFix)
        // "look4_" because each look now has 4 slots. The old "look_" keys
        // (3 per look) are only read once, to carry old values across.
        lookParams.forEachIndexed { i, v -> e.putFloat("look4_$i", v) }
        e.putInt("neonColor", neonColor).putFloat("damageSeed", damageSeed)
            .putInt("captionMode", captionMode).putString("captionText", captionText)
        mix.forEachIndexed { i, v -> e.putFloat("mix_$i", v) }
        finishOn.forEachIndexed { i, on -> e.putBoolean("finOn_$i", on) }
        finishParams.forEachIndexed { i, v -> e.putFloat("finVal_$i", v) }
        e.apply()
    }

    companion object {
        /**
         * Rebuild a look from Favorites JSON. Mirror and rotation fix come
         * from `keep` (the current settings). Anything missing from an older
         * favorite falls back to the defaults, and lists are matched up slot
         * by slot so a favorite saved before new looks were added still loads.
         */
        fun fromJson(o: JSONObject, keep: SketchSettings): SketchSettings {
            val d = SketchSettings()
            fun floats(key: String, def: FloatArray): FloatArray {
                val a = o.optJSONArray(key) ?: return def
                return FloatArray(def.size) { i -> if (i < a.length()) a.optDouble(i, def[i].toDouble()).toFloat() else def[i] }
            }
            val on = o.optJSONArray("finishOn")
            return SketchSettings(
                style = o.optInt("style", d.style).coerceIn(0, Looks.count - 1),
                strength = o.optDouble("strength", d.strength.toDouble()).toFloat(),
                detail = o.optDouble("detail", d.detail.toDouble()).toFloat(),
                width = o.optDouble("width", d.width.toDouble()).toFloat(),
                hatch = o.optDouble("hatch", d.hatch.toDouble()).toFloat(),
                spacing = o.optDouble("spacing", d.spacing.toDouble()).toFloat(),
                boil = o.optDouble("boil", d.boil.toDouble()).toFloat(),
                boilFps = o.optDouble("boilFps", d.boilFps.toDouble()).toFloat().coerceIn(1f, 30f),
                holdFrames = o.optBoolean("holdFrames", d.holdFrames),
                grain = o.optDouble("grain", d.grain.toDouble()).toFloat(),
                ink = o.optInt("ink", d.ink),
                mirror = keep.mirror, rotationFix = keep.rotationFix,
                lookParams = floats("lookParams", d.lookParams).also { a ->
                    for (i in a.indices) a[i] = Looks.clampParam(i / Looks.PARAMS, i % Looks.PARAMS, a[i])
                },
                finishOn = BooleanArray(d.finishOn.size) { i -> on != null && i < on.length() && on.optBoolean(i) },
                finishParams = floats("finishParams", d.finishParams),
                neonColor = o.optInt("neonColor", d.neonColor),
                damageSeed = o.optDouble("damageSeed", d.damageSeed.toDouble()).toFloat(),
                captionMode = o.optInt("captionMode", d.captionMode).coerceIn(0, 2),
                captionText = o.optString("captionText", d.captionText),
                mix = floats("mix", d.mix).also { a -> for (i in a.indices) a[i] = a[i].coerceIn(0f, 1f) }
            )
        }

        /** Any ARGB color split into red, green, blue from 0 to 1. */
        fun rgb(c: Int): FloatArray = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

        /** Load the saved values. Anything never saved falls back to the defaults above. */
        fun load(p: SharedPreferences): SketchSettings {
            val d = SketchSettings()
            return SketchSettings(
                style = p.getInt("style", d.style).coerceIn(0, Looks.count - 1),
                strength = p.getFloat("strength", d.strength),
                detail = p.getFloat("detail", d.detail),
                width = p.getFloat("width", d.width),
                hatch = p.getFloat("hatch", d.hatch),
                spacing = p.getFloat("spacing", d.spacing),
                boil = p.getFloat("boil", d.boil),
                boilFps = p.getFloat("boilFps", d.boilFps).coerceIn(1f, 30f),
                holdFrames = p.getBoolean("holdFrames", d.holdFrames),
                grain = p.getFloat("grain", d.grain),
                ink = p.getInt("ink", d.ink),
                mirror = p.getBoolean("mirror", d.mirror),
                rotationFix = p.getInt("rotFix2", d.rotationFix),
                lookParams = FloatArray(d.lookParams.size) { i ->
                    // Slot i belongs to look s, setting n. If it was saved by an
                    // older version (3 per look), read it from where it used to be.
                    val s = i / Looks.PARAMS; val n = i % Looks.PARAMS
                    val old = if (n < 3) p.getFloat("look_${s * 3 + n}", d.lookParams[i]) else d.lookParams[i]
                    Looks.clampParam(s, n, p.getFloat("look4_$i", old))
                },
                neonColor = p.getInt("neonColor", d.neonColor),
                damageSeed = p.getFloat("damageSeed", d.damageSeed),
                captionMode = p.getInt("captionMode", d.captionMode).coerceIn(0, 2),
                captionText = p.getString("captionText", d.captionText) ?: "",
                mix = FloatArray(d.mix.size) { i -> p.getFloat("mix_$i", 1f).coerceIn(0f, 1f) },
                finishOn = BooleanArray(d.finishOn.size) { i -> p.getBoolean("finOn_$i", false) },
                finishParams = FloatArray(d.finishParams.size) { i -> p.getFloat("finVal_$i", d.finishParams[i]) }
            )
        }
    }
}
