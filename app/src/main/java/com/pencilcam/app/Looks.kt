package com.pencilcam.app

import kotlin.math.roundToInt

/**
 * ============================================================================
 * Looks: the catalog of every drawing style and every finish.
 *
 * A LOOK is the base style (pencil, watercolor, oil painting...). You pick
 * exactly one. Pencil, Ink pen, and Colored pencil are driven by the
 * general Lines, Shade, and Paper tabs. Every other look has up to four
 * settings of its own, listed here, which show up under the style list in
 * the Style tab. A setting is either a slider or a pick-one choice (like
 * Print / Film), which shows as a row of radio buttons.
 *
 * A FINISH sits on top of whatever look you picked (vignette, matte,
 * glossy, polaroid). Each one has an on/off switch and two sliders, and
 * you can stack as many as you like.
 *
 * The order of the lists below matters: a look's position is its style
 * number, which the shader uses to pick what to draw (u_style in
 * Shaders.kt) and which gets saved in the settings. New looks always go on
 * the END so saved settings keep pointing at the right one. Same goes for
 * a look's settings: their position is which slot (u_p.x, .y, .z, .w) the
 * shader reads.
 * ============================================================================
 */
object Looks {

    /**
     * One setting: its label, range, step, starting value, and how to show
     * the number next to the label. If choices is set, it's a pick-one
     * setting instead: the value is the chosen item's number (0, 1, 2...).
     */
    class Param(val label: String, val min: Float, val max: Float, val step: Float, val def: Float,
                val fmt: (Float) -> String, val choices: List<String>? = null)

    // Ways to show a slider's value
    private val pct: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    private val whole: (Float) -> String = { "${it.roundToInt()}" }
    private val oneDp: (Float) -> String = { "%.1f".format(it) }

    /** A pick-one setting. Starts on choice number def. */
    private fun choice(label: String, def: Int, vararg names: String) =
        Param(label, 0f, (names.size - 1).toFloat(), 1f, def.toFloat(), { names[it.roundToInt()] }, names.toList())

    /** How many settings each look gets. Looks with fewer just leave the rest unused. */
    const val PARAMS = 4

    // Style numbers the screen needs to know about
    const val OIL = 7; const val NEON = 8; const val OLD_WEST = 10

    /** Every look, in style number order (0, 1, 2, ...). */
    val names = listOf(
        "Pencil", "Ink pen", "Colored pencil",                          // 0 to 2
        "Comic", "Halftone", "Watercolor", "Chalkboard",                // 3 to 6
        "Oil painting", "Neon edges", "Crayon edges",                   // 7 to 9
        "Old west", "Inverted", "Predator vision"                       // 10 to 12
    )
    val count get() = names.size

    /** Each look's own settings. The three pencil looks use the general tabs instead. */
    val params: List<List<Param>> = listOf(
        emptyList(),                                                    // Pencil
        emptyList(),                                                    // Ink pen
        emptyList(),                                                    // Colored pencil
        listOf(                                                         // Comic
            Param("Color levels", 2f, 6f, 1f, 4f, whole),
            Param("Shadow dots", 0f, 1f, 0.01f, 0.7f, pct),
            Param("Outline weight", 0f, 1f, 0.01f, 0.6f, pct)),
        listOf(                                                         // Halftone
            Param("Dot size", 4f, 20f, 0.5f, 8.5f, oneDp),
            Param("Color", 0f, 1f, 0.01f, 1f, pct),
            Param("Outlines", 0f, 1f, 0.01f, 0.8f, pct)),
        listOf(                                                         // Watercolor
            Param("Bleed", 0f, 1f, 0.01f, 0.55f, pct),
            Param("Color strength", 0f, 1f, 0.01f, 0.75f, pct),
            Param("Edge pooling", 0f, 1f, 0.01f, 0.6f, pct)),
        listOf(                                                         // Chalkboard
            Param("Chalk shading", 0f, 1f, 0.01f, 0.7f, pct),
            Param("Board darkness", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Chalk dust", 0f, 1f, 0.01f, 0.4f, pct)),
        listOf(                                                         // Oil painting
            Param("Brush size", 2f, 10f, 0.1f, 5f, oneDp),
            Param("Impasto depth", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Canvas", 0f, 1f, 0.01f, 0.4f, pct)),
        listOf(                                                         // Neon edges
            Param("Glow", 0f, 1f, 0.01f, 0.6f, pct),
            choice("Mode", 0, "Solid", "Rainbow", "Two-tone", "Scene color"),
            Param("Rainbow speed", 0f, 1f, 0.01f, 0.4f, pct)),
        listOf(                                                         // Crayon edges
            Param("Stroke thickness", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Color fill", 0f, 1f, 0.01f, 0.4f, pct),
            Param("Wax texture", 0f, 1f, 0.01f, 0.5f, pct)),
        listOf(                                                         // Old west
            Param("Sepia", 0f, 1f, 0.01f, 0.8f, pct),
            Param("Scratches", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Wear", 0f, 1f, 0.01f, 0.4f, pct),
            choice("Damage", 0, "Print", "Film")),
        listOf(                                                         // Inverted
            Param("Contrast", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Color", 0f, 1f, 0.01f, 0.7f, pct),
            Param("Outlines", 0f, 1f, 0.01f, 0.3f, pct)),
        listOf(                                                         // Predator vision
            Param("Edge heat", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Contrast", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Scan lines", 0f, 1f, 0.01f, 0.3f, pct),
            choice("Palette", 0, "Ironbow", "White-hot", "Black-hot", "Rainbow"))
    )

    /** A short line under the look's settings saying where its other controls are. */
    fun hint(style: Int): String = when (style) {
        0, 1, 2 -> "This look uses the Lines, Shade, and Paper tabs."
        3 -> "Dot spacing for the shadows is on the Shade tab."
        NEON -> "Pick the neon color on the Lines tab. Rainbow ignores it; Two-tone uses it for the glow."
        OLD_WEST -> "Wear goes from light fading up to folds, cracks, and dog-eared corners. " +
            "Print keeps the damage still; Film makes vertical scratches flicker like an old reel."
        12 -> "Fake thermal: bright and busy spots read hot. No real heat sensor involved."
        else -> "The Lines tab's Detail and Line softness also change how edges are found."
    }

    /** Neon tube colors offered on the Lines tab when Neon edges is the look. */
    val neonColors = listOf(
        "Hot pink" to 0xFFFF2D95.toInt(), "Electric blue" to 0xFF2E7DFF.toInt(), "Lime" to 0xFFA6FF00.toInt(),
        "Purple" to 0xFFB026FF.toInt(), "Orange" to 0xFFFF7A00.toInt(), "White" to 0xFFFFFFFF.toInt())

    /** Starting values for every look's settings, packed into one list (look 0's four, look 1's four, ...). */
    fun defaultLookParams(): FloatArray {
        val a = FloatArray(count * PARAMS)
        params.forEachIndexed { s, list -> list.forEachIndexed { i, p -> a[s * PARAMS + i] = p.def } }
        return a
    }

    /** Keep a loaded value inside its setting's range (and on a whole number for choices). */
    fun clampParam(style: Int, i: Int, v: Float): Float {
        val p = params.getOrNull(style)?.getOrNull(i) ?: return v
        val c = v.coerceIn(p.min, p.max)
        return if (p.choices != null) c.roundToInt().toFloat() else c
    }

    // ---- Finishes ----------------------------------------------------------
    const val VIGNETTE = 0; const val MATTE = 1; const val GLOSSY = 2; const val POLAROID = 3
    val finishNames = listOf("Vignette", "Matte", "Glossy", "Polaroid")
    val finishCount get() = finishNames.size

    /** Each finish has exactly two sliders. */
    val finishParams: List<List<Param>> = listOf(
        listOf(Param("Amount", 0f, 1f, 0.01f, 0.5f, pct), Param("Size", 0f, 1f, 0.01f, 0.5f, pct)),          // Vignette
        listOf(Param("Fade", 0f, 1f, 0.01f, 0.5f, pct), Param("Soften", 0f, 1f, 0.01f, 0.3f, pct)),          // Matte
        listOf(Param("Shine", 0f, 1f, 0.01f, 0.5f, pct), Param("Punch", 0f, 1f, 0.01f, 0.4f, pct)),          // Glossy
        listOf(Param("Frame", 0f, 1f, 0.01f, 0.5f, pct), Param("Faded tones", 0f, 1f, 0.01f, 0.5f, pct))     // Polaroid
    )

    /** The polaroid caption choices. */
    val captionModes = listOf("Off", "Today's date", "Custom")

    /** Starting values for the finish sliders: finish 0's two, finish 1's two, and so on. */
    fun defaultFinishParams(): FloatArray {
        val a = FloatArray(finishCount * 2)
        finishParams.forEachIndexed { f, list -> list.forEachIndexed { i, p -> a[f * 2 + i] = p.def } }
        return a
    }
}
