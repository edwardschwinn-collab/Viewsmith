package com.pencilcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ScrollView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ============================================================================
 * Gloss: the black glass and polished silver look used on every control.
 *
 * The whole app is lit by one light, straight overhead. That means every
 * highlight sits at the TOP of a shape, every shadow falls BELOW it, and
 * silver runs bright at the top to dark at the bottom. When a control turns
 * with the phone, only its icon or label turns. The glass and its shine
 * stay where they are, because the light didn't move. Stick to that and
 * everything looks like it belongs together.
 * ============================================================================
 */
object Gloss {
    // Silver, from brightest (facing the light) to darkest (facing away)
    val SILVER_HI = Color.parseColor("#F6F8FA")
    val SILVER_1 = Color.parseColor("#D3D8DE")
    val SILVER_2 = Color.parseColor("#9AA1AA")
    val SILVER_3 = Color.parseColor("#5C626A")
    val SILVER_LO = Color.parseColor("#2E3237")
    // Black glass
    val GLASS_TOP = Color.parseColor("#2C3036")
    val GLASS_BOTTOM = Color.parseColor("#060708")
    // Text
    val TEXT = Color.parseColor("#D8DCE1")
    val TEXT_DIM = Color.parseColor("#8C939C")
    val REC_RED = Color.parseColor("#E5322B")

    /** Vertical silver shader for a shape spanning top..bottom. */
    fun silver(top: Float, bottom: Float): Shader = LinearGradient(
        0f, top, 0f, bottom,
        intArrayOf(SILVER_HI, SILVER_1, SILVER_2, SILVER_3, SILVER_1),
        floatArrayOf(0f, 0.25f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP
    )

    /** Glass highlight: bright at the top fading to nothing. */
    fun highlight(top: Float, bottom: Float, alpha: Int = 120): Shader = LinearGradient(
        0f, top, 0f, bottom,
        Color.argb(alpha, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
    )
}

/**
 * Rounded black-glass panel with a silver rim (behind the sliders and Settings).
 * opacity: 0f = glass fully clear (you see the drawing behind it),
 *          1f = the original solid black glass.
 * The silver rim never fades below 40% so you can always see the panel's edge.
 */
class GlossPanelDrawable(private val radius: Float) : Drawable() {
    var opacity = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidateSelf() }   // redraw right away when the slider moves

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = RectF(bounds); b.inset(1f, 1f)
        // Glass body: dark at the top, darker at the bottom, see-through by the opacity setting
        fill.shader = LinearGradient(0f, b.top, 0f, b.bottom,
            Color.argb((235 * opacity).toInt(), 34, 37, 42), Color.argb((245 * opacity).toInt(), 6, 7, 8), Shader.TileMode.CLAMP)
        c.drawRoundRect(b, radius, radius, fill)
        // Top sheen: light from above hits the upper edge of the glass
        shine.shader = Gloss.highlight(b.top, b.top + radius * 2.2f, (60 * opacity).toInt())
        c.drawRoundRect(RectF(b.left + 2, b.top + 2, b.right - 2, b.top + radius * 2.2f), radius, radius, shine)
        // Silver rim (Paint.alpha also fades a shader)
        rim.shader = Gloss.silver(b.top, b.bottom)
        rim.alpha = (255 * maxOf(opacity, 0.4f)).toInt()
        c.drawRoundRect(b, radius, radius, rim)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A ScrollView that grows to fit its contents but never past maxHeight.
 * Short groups (Paper) get a short panel, long groups scroll.
 */
class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeight = Int.MAX_VALUE
        set(v) { field = v; requestLayout() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
    }
}

/**
 * Square glossy tab button with a short label. Silver face when selected.
 * badge > 0 adds a silver glow around the edge and a small silver disc in
 * the top-right corner with the number on it (the Finish tab uses this to
 * show how many finishes are stacked on).
 */
class GlossTab(context: Context, var label: String) : View(context) {
    var selectedTab = false
        set(v) { field = v; invalidate() }
    /** Turns only the LABEL to match how the phone is held. The glass and its
     *  shine stay put, so the light still comes from above. */
    var iconRotation = 0f
        set(v) { field = v; invalidate() }
    /** Count shown on the badge. 0 = no badge, no glow. */
    var badge = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        // Let the glow and badge spill a little outside the button's box
        clipToOutline = false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) * 0.24f
        val b = RectF(2f, 2f, w - 2f, h - 4f)
        if (badge > 0) {
            // Silver glow: a few soft rings fading outward, like light bouncing off polished metal
            p.shader = null; p.style = Paint.Style.STROKE
            for (i in 3 downTo 1) {
                p.strokeWidth = i * 2.2f
                p.color = Color.argb(70 / i, 230, 236, 242)
                c.drawRoundRect(b, r, r, p)
            }
            p.style = Paint.Style.FILL
        }
        // Soft shadow BELOW (light is above)
        p.shader = null; p.color = Color.argb(110, 0, 0, 0)
        c.drawRoundRect(RectF(b.left, b.top + 3f, b.right, b.bottom + 3f), r, r, p)
        // Face
        p.shader = if (selectedTab) Gloss.silver(b.top, b.bottom)
        else LinearGradient(0f, b.top, 0f, b.bottom, Gloss.GLASS_TOP, Gloss.GLASS_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRoundRect(b, r, r, p)
        // Glass highlight on the upper half
        p.shader = Gloss.highlight(b.top, b.centerY(), if (selectedTab) 150 else 90)
        c.drawRoundRect(RectF(b.left + 3, b.top + 3, b.right - 3, b.centerY()), r, r, p)
        // Silver rim (a bit thicker and brighter while the badge is showing)
        p.shader = Gloss.silver(b.top, b.bottom); p.style = Paint.Style.STROKE; p.strokeWidth = if (badge > 0) 3.5f else 2.5f
        c.drawRoundRect(b, r, r, p); p.style = Paint.Style.FILL
        // Label
        text.textSize = h * 0.2f
        text.color = if (selectedTab) Color.parseColor("#15171A") else Gloss.TEXT
        text.isFakeBoldText = false
        c.save(); c.rotate(iconRotation, w / 2f, b.centerY())
        c.drawText(label, w / 2f, b.centerY() + text.textSize * 0.35f, text)
        c.restore()

        if (badge > 0) {
            // Badge: a small silver disc tucked into the top-right corner.
            // Shadow below it, silver bright at the top, a glint on top.
            val br = min(w, h) * 0.17f
            val bx = b.right - br * 0.55f; val by = b.top + br * 0.55f
            p.shader = null; p.color = Color.argb(140, 0, 0, 0)
            c.drawCircle(bx, by + 2f, br, p)
            p.shader = Gloss.silver(by - br, by + br); c.drawCircle(bx, by, br, p)
            p.shader = Gloss.highlight(by - br, by, 170)
            c.drawOval(RectF(bx - br * 0.7f, by - br * 0.92f, bx + br * 0.7f, by - br * 0.1f), p)
            p.shader = null
            // The number turns with the phone; the disc and its shine don't
            text.textSize = br * 1.25f; text.color = Color.parseColor("#15171A"); text.isFakeBoldText = true
            c.save(); c.rotate(iconRotation, bx, by)
            c.drawText(badge.toString(), bx, by + text.textSize * 0.36f, text)
            c.restore()
        }
    }
}

/**
 * Small round glossy button. icon = FLIP (two chasing arrows) or COG (settings gear).
 * iconRotation turns only the icon; the silver bezel and its shine stay lit from above.
 */
class GlossButton(context: Context, private val icon: Int = FLIP) : View(context) {
    companion object { const val FLIP = 0; const val COG = 1 }

    var iconRotation = 0f
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f - 4f
        p.shader = null; p.color = Color.argb(120, 0, 0, 0); c.drawCircle(cx, cy + 3f, R, p)      // shadow below
        p.shader = Gloss.silver(cy - R, cy + R); c.drawCircle(cx, cy, R, p)                          // silver bezel
        val r2 = R * 0.82f
        p.shader = RadialGradient(cx, cy + r2 * 0.3f, r2 * 1.2f, Gloss.GLASS_TOP, Gloss.GLASS_BOTTOM, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r2, p)                                                                   // black glass
        p.shader = Gloss.highlight(cy - r2, cy, 130)
        c.drawOval(RectF(cx - r2 * 0.7f, cy - r2 * 0.92f, cx + r2 * 0.7f, cy - r2 * 0.1f), p)       // top shine

        c.save(); c.rotate(iconRotation, cx, cy)                                                      // only the icon turns
        if (icon == COG) drawCog(c, cx, cy, r2, R) else drawFlip(c, cx, cy, r2, R)
        c.restore()
    }

    /** Two curved arrows chasing each other (switch camera). */
    private fun drawFlip(c: Canvas, cx: Float, cy: Float, r2: Float, R: Float) {
        val s = r2 * 0.5f
        line.color = Gloss.TEXT; line.strokeWidth = R * 0.09f
        val box = RectF(cx - s, cy - s, cx + s, cy + s)
        c.drawArc(box, 200f, 140f, false, line)
        c.drawArc(box, 20f, 140f, false, line)
        val a = Path()
        a.moveTo(cx + s * 0.95f, cy - s * 0.55f); a.lineTo(cx + s * 0.98f, cy + s * 0.05f); a.lineTo(cx + s * 0.45f, cy - s * 0.15f)
        a.moveTo(cx - s * 0.95f, cy + s * 0.55f); a.lineTo(cx - s * 0.98f, cy - s * 0.05f); a.lineTo(cx - s * 0.45f, cy + s * 0.15f)
        c.drawPath(a, line)
    }

    /** Settings gear: solid hub, 8 square teeth, black-glass hole in the middle. */
    private fun drawCog(c: Canvas, cx: Float, cy: Float, r2: Float, R: Float) {
        val s = r2 * 0.6f
        solid.shader = null; solid.color = Gloss.TEXT
        c.drawCircle(cx, cy, s * 0.66f, solid)                                   // hub
        line.color = Gloss.TEXT; line.strokeWidth = s * 0.34f; line.strokeCap = Paint.Cap.BUTT
        for (i in 0 until 8) {                                                   // teeth every 45 degrees
            val a = Math.toRadians(i * 45.0 + 22.5)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + ca * s * 0.55f, cy + sa * s * 0.55f, cx + ca * s * 0.98f, cy + sa * s * 0.98f, line)
        }
        line.strokeCap = Paint.Cap.ROUND
        solid.color = Gloss.GLASS_BOTTOM
        c.drawCircle(cx, cy, s * 0.28f, solid)                                   // center hole
    }
}
