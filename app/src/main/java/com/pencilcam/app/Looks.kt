package com.pencilcam.app

import kotlin.math.roundToInt

/**
 * ============================================================================
 * Looks: the catalog of every drawing style and every finish.
 *
 * A LOOK is the base style (pencil, watercolor, oil painting...). You pick
 * exactly one. Pencil, Ink pen, and Colored pencil are driven by the
 * general Lines, Shade, and Paper tabs. Every other look has up to three
 * sliders of its own, listed here, which show up under the style list in
 * the Style tab.
 *
 * A FINISH sits on top of whatever look you picked (vignette, matte,
 * glossy, polaroid). Each one has an on/off switch and two sliders, and
 * you can stack as many as you like.
 *
 * The order of the lists below matters: a look's position is its style
 * number, which the shader uses to pick what to draw (u_style in
 * Shaders.kt) and which gets saved in the settings. New looks always go on
 * the END so saved settings keep pointing at the right one.
 * ============================================================================
 */
object Looks {

    /**
     * One slider: its label, range, step, starting value, and how to show
     * the number next to the label.
     */
    class Param(val label: String, val min: Float, val max: Float, val step: Float, val def: Float,
                val fmt: (Float) -> String)

    // Ways to show a slider's value
    private val pct: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    private val whole: (Float) -> String = { "${it.roundToInt()}" }
    private val oneDp: (Float) -> String = { "%.1f".format(it) }
    private val degrees: (Float) -> String = { "${(it * 360).roundToInt()}°" }

    /** How many sliders each look gets. Looks with fewer just leave the rest unused. */
    const val PARAMS = 3

    /** Every look, in style number order (0, 1, 2, ...). */
    val names = listOf(
        "Pencil", "Ink pen", "Colored pencil",                          // 0 to 2
        "Comic", "Halftone", "Watercolor", "Chalkboard",                // 3 to 6
        "Oil painting", "Neon edges", "Crayon edges",                   // 7 to 9
        "Old west", "Inverted", "Predator vision"                       // 10 to 12
    )
    val count get() = names.size

    /** Each look's own sliders. The three pencil looks use the general tabs instead. */
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
            Param("Edge pooling", 0f, 1f, 0.01f, 0.5f, pct)),
        listOf(                                                         // Chalkboard
            Param("Chalk shading", 0f, 1f, 0.01f, 0.7f, pct),
            Param("Board darkness", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Chalk dust", 0f, 1f, 0.01f, 0.4f, pct)),
        listOf(                                                         // Oil painting
            Param("Brush size", 2f, 8f, 0.1f, 4f, oneDp),
            Param("Color richness", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Canvas texture", 0f, 1f, 0.01f, 0.5f, pct)),
        listOf(                                                         // Neon edges
            Param("Glow", 0f, 1f, 0.01f, 0.6f, pct),
            Param("Hue", 0f, 1f, 0.01f, 0.55f, degrees),
            Param("Rainbow", 0f, 1f, 0.01f, 0.5f, pct)),
        listOf(                                                         // Crayon edges
            Param("Stroke thickness", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Color fill", 0f, 1f, 0.01f, 0.4f, pct),
            Param("Wax texture", 0f, 1f, 0.01f, 0.5f, pct)),
        listOf(                                                         // Old west
            Param("Sepia", 0f, 1f, 0.01f, 0.8f, pct),
            Param("Scratches and dust", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Plate wear", 0f, 1f, 0.01f, 0.5f, pct)),
        listOf(                                                         // Inverted
            Param("Contrast", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Color", 0f, 1f, 0.01f, 0.7f, pct),
            Param("Outlines", 0f, 1f, 0.01f, 0.3f, pct)),
        listOf(                                                         // Predator vision
            Param("Edge heat", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Contrast", 0f, 1f, 0.01f, 0.5f, pct),
            Param("Scan lines", 0f, 1f, 0.01f, 0.3f, pct))
    )

    /** A short line under the style list explaining where that look's controls are. */
    fun hint(style: Int): String = when (style) {
        0, 1, 2 -> "This look uses the Lines, Shade, and Paper tabs."
        3 -> "Dot spacing for the shadows is on the Shade tab."
        12 -> "Fake thermal: bright and busy spots read hot. No real heat sensor involved."
        else -> "The Lines tab's Detail and Line softness also change how edges are found."
    }

    /** Starting values for every look's sliders, packed into one list (look 0's three, look 1's three, ...). */
    fun defaultLookParams(): FloatArray {
        val a = FloatArray(count * PARAMS)
        params.forEachIndexed { s, list -> list.forEachIndexed { i, p -> a[s * PARAMS + i] = p.def } }
        return a
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

    /** Starting values for the finish sliders: finish 0's two, finish 1's two, and so on. */
    fun defaultFinishParams(): FloatArray {
        val a = FloatArray(finishCount * 2)
        finishParams.forEachIndexed { f, list -> list.forEachIndexed { i, p -> a[f * 2 + i] = p.def } }
        return a
    }
}
