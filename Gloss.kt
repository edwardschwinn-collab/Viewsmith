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
import kotlin.math.min

/**
 * ============================================================================
 * Gloss: the black-glass and polished-silver look.
 * RULE: one light source, straight above. So every highlight sits at the TOP
 * of a shape, every shadow falls BELOW it, and silver gradients run
 * bright-at-top to dark-at-bottom. Keep to that and everything matches.
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

/** Rounded black-glass panel with a silver rim (used behind the sliders). */
class GlossPanelDrawable(private val radius: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = RectF(bounds); b.inset(1f, 1f)
        fill.shader = LinearGradient(0f, b.top, 0f, b.bottom,
            Color.argb(235, 34, 37, 42), Color.argb(245, 6, 7, 8), Shader.TileMode.CLAMP)
        c.drawRoundRect(b, radius, radius, fill)
        // Top sheen: light from above hits the upper edge of the glass
        shine.shader = Gloss.highlight(b.top, b.top + radius * 2.2f, 60)
        c.drawRoundRect(RectF(b.left + 2, b.top + 2, b.right - 2, b.top + radius * 2.2f), radius, radius, shine)
        rim.shader = Gloss.silver(b.top, b.bottom)
        c.drawRoundRect(b, radius, radius, rim)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Square glossy tab button with a short label. Silver face when selected. */
class GlossTab(context: Context, var label: String) : View(context) {
    var selectedTab = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) * 0.24f
        val b = RectF(2f, 2f, w - 2f, h - 4f)
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
        // Silver rim
        p.shader = Gloss.silver(b.top, b.bottom); p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
        c.drawRoundRect(b, r, r, p); p.style = Paint.Style.FILL
        // Label
        text.textSize = h * 0.2f
        text.color = if (selectedTab) Color.parseColor("#15171A") else Gloss.TEXT
        c.drawText(label, w / 2f, b.centerY() + text.textSize * 0.35f, text)
    }
}

/** Small round glossy button with a "flip camera" icon. */
class GlossButton(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val R = min(width, height) / 2f - 4f
        p.shader = null; p.color = Color.argb(120, 0, 0, 0); c.drawCircle(cx, cy + 3f, R, p)      // shadow below
        p.shader = Gloss.silver(cy - R, cy + R); c.drawCircle(cx, cy, R, p)                          // silver bezel
        val r2 = R * 0.82f
        p.shader = RadialGradient(cx, cy + r2 * 0.3f, r2 * 1.2f, Gloss.GLASS_TOP, Gloss.GLASS_BOTTOM, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r2, p)                                                                   // black glass
        p.shader = Gloss.highlight(cy - r2, cy, 130)
        c.drawOval(RectF(cx - r2 * 0.7f, cy - r2 * 0.92f, cx + r2 * 0.7f, cy - r2 * 0.1f), p)       // top shine
        // Icon: two curved arrows chasing each other (flip)
        val s = r2 * 0.5f
        icon.color = Gloss.TEXT; icon.strokeWidth = R * 0.09f
        val box = RectF(cx - s, cy - s, cx + s, cy + s)
        c.drawArc(box, 200f, 140f, false, icon)
        c.drawArc(box, 20f, 140f, false, icon)
        val a = Path()
        a.moveTo(cx + s * 0.95f, cy - s * 0.55f); a.lineTo(cx + s * 0.98f, cy + s * 0.05f); a.lineTo(cx + s * 0.45f, cy - s * 0.15f)
        a.moveTo(cx - s * 0.95f, cy + s * 0.55f); a.lineTo(cx - s * 0.98f, cy - s * 0.05f); a.lineTo(cx - s * 0.45f, cy + s * 0.15f)
        c.drawPath(a, icon)
    }
}
