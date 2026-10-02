package com.pencilcam.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ============================================================================
 * ModeDial: the shutter.
 *   - Outer silver ring: DRAG it around like a real camera dial. It clicks
 *     (haptic tick) into PHOTO / VIDEO / FILE. The mode lined up with the
 *     notch at the top is the active one.
 *   - Center black-glass button: TAP to shoot / record / pick-or-save.
 *     LONG-PRESS in File mode picks a different photo.
 * Lighting is fixed from above: the ring's tick marks rotate, but the
 * shine never moves, just like a real polished dial.
 * ============================================================================
 */
class ModeDial(context: Context) : View(context) {

    companion object { const val PHOTO = 0; const val VIDEO = 1; const val FILE = 2 }
    private val labels = arrayOf("PHOTO", "VIDEO", "FILE")

    // ---- Callbacks to the screen
    var onModeChanged: ((Int) -> Unit)? = null
    var onShutter: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    // ---- State the screen sets
    var recording = false; set(v) { field = v; invalidate() }
    var fileLoaded = false; set(v) { field = v; invalidate() }
    var locked = false                                  // no mode changes while recording
    var iconRotation = 0f; set(v) { field = v; invalidate() }

    private var mode = PHOTO
    private var shownMode = PHOTO
    private var ringAngle = 0f                          // degrees; mode m sits on top when ringAngle = -m*120
    private var dragging = false
    private var pressing = false
    private var longFired = false
    private var lastTouchAngle = 0f
    private var snapAnim: ValueAnimator? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val longPress = Runnable {
        if (pressing) { longFired = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onLongPress?.invoke() }
    }

    fun setMode(m: Int) { mode = m; shownMode = m; ringAngle = -m * 120f; invalidate() }

    // ------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val R = min(width, height) / 2f - 6f
        val rRingOut = R * 0.93f
        val rRingIn = R * 0.64f
        val rBtn = R * 0.58f

        // 1. Shadow below the whole dial (light from above)
        p.shader = RadialGradient(cx, cy + R * 0.08f, R * 1.05f, Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + R * 0.06f, R * 1.05f, p)

        // 2. Silver bezel
        p.shader = Gloss.silver(cy - R, cy + R); c.drawCircle(cx, cy, R, p)

        // 3. Knurled ring: base silver (fixed light), slightly darker than bezel
        p.shader = LinearGradient(0f, cy - rRingOut, 0f, cy + rRingOut,
            intArrayOf(Color.parseColor("#E3E7EC"), Color.parseColor("#A2A9B2"), Color.parseColor("#4F555D"), Color.parseColor("#9EA5AE")),
            floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rRingOut, p)

        // 4. Tick marks + engraved labels ROTATE with the ring
        c.save(); c.rotate(ringAngle, cx, cy)
        for (i in 0 until 72) {
            val a = i * 5f
            if ((0..2).any { m -> angleDist(a, m * 120f) < 16f }) continue   // keep space around labels
            val rad = Math.toRadians((a - 90).toDouble())
            val x1 = cx + cos(rad).toFloat() * (rRingIn + R * 0.05f); val y1 = cy + sin(rad).toFloat() * (rRingIn + R * 0.05f)
            val x2 = cx + cos(rad).toFloat() * (rRingOut - R * 0.04f); val y2 = cy + sin(rad).toFloat() * (rRingOut - R * 0.04f)
            line.strokeWidth = R * 0.018f
            line.color = Color.argb(110, 255, 255, 255); c.drawLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1, line)  // lit edge
            line.color = Color.parseColor("#353A40"); c.drawLine(x1, y1, x2, y2, line)                      // groove
        }
        txt.textSize = R * 0.105f
        val labelR = (rRingIn + rRingOut) / 2f
        for (m in 0..2) {
            c.save(); c.rotate(m * 120f, cx, cy)
            txt.color = Color.argb(140, 255, 255, 255); c.drawText(labels[m], cx, cy - labelR + txt.textSize * 0.38f + 1.2f, txt)
            txt.color = if (m == shownMode) Color.parseColor("#14161A") else Color.parseColor("#3A3F46")
            c.drawText(labels[m], cx, cy - labelR + txt.textSize * 0.38f, txt)
            c.restore()
        }
        c.restore()

        // 5. Recessed inner edge of the ring (dark on top, light on bottom = sunk in)
        p.shader = LinearGradient(0f, cy - rRingIn, 0f, cy + rRingIn, Color.parseColor("#2A2E33"), Color.parseColor("#C9CED5"), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rRingIn, p)

        // 6. Fixed notch at the top of the bezel showing the active mode
        p.shader = null; p.color = Color.parseColor("#16181B")
        val notch = Path().apply {
            moveTo(cx, cy - rRingOut + R * 0.01f)
            lineTo(cx - R * 0.055f, cy - R * 0.995f); lineTo(cx + R * 0.055f, cy - R * 0.995f); close()
        }
        c.drawPath(notch, p)

        // 7. Center button: black glass (or red while recording)
        val top = if (recording) Color.parseColor("#D2433A") else Gloss.GLASS_TOP
        val bot = if (recording) Color.parseColor("#4A0B08") else Gloss.GLASS_BOTTOM
        p.shader = RadialGradient(cx, cy + rBtn * 0.35f, rBtn * 1.25f, top, bot, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rBtn, p)
        if (pressing) { p.shader = null; p.color = Color.argb(90, 0, 0, 0); c.drawCircle(cx, cy, rBtn, p) }
        // silver rim
        p.shader = Gloss.silver(cy - rBtn, cy + rBtn); p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.025f
        c.drawCircle(cx, cy, rBtn, p); p.style = Paint.Style.FILL
        // top gloss
        p.shader = Gloss.highlight(cy - rBtn, cy, if (pressing) 70 else 135)
        c.drawOval(RectF(cx - rBtn * 0.72f, cy - rBtn * 0.93f, cx + rBtn * 0.72f, cy - rBtn * 0.08f), p)

        // 8. Icon (rotates to stay upright for the person holding the phone)
        c.save(); c.rotate(iconRotation, cx, cy)
        drawIcon(c, cx, cy, rBtn * 0.5f, R)
        c.restore()
    }

    private fun drawIcon(c: Canvas, cx: Float, cy: Float, s: Float, R: Float) {
        icon.color = Gloss.TEXT; icon.strokeWidth = R * 0.035f; icon.style = Paint.Style.STROKE
        when {
            recording -> {   // stop square
                icon.style = Paint.Style.FILL; icon.color = Color.WHITE
                c.drawRoundRect(RectF(cx - s * 0.45f, cy - s * 0.45f, cx + s * 0.45f, cy + s * 0.45f), s * 0.1f, s * 0.1f, icon)
            }
            shownMode == PHOTO -> {   // camera
                c.drawRoundRect(RectF(cx - s, cy - s * 0.55f, cx + s, cy + s * 0.75f), s * 0.2f, s * 0.2f, icon)
                c.drawRoundRect(RectF(cx - s * 0.38f, cy - s * 0.82f, cx + s * 0.38f, cy - s * 0.55f), s * 0.08f, s * 0.08f, icon)
                c.drawCircle(cx, cy + s * 0.1f, s * 0.4f, icon)
            }
            shownMode == VIDEO -> {   // movie camera
                c.drawRoundRect(RectF(cx - s, cy - s * 0.55f, cx + s * 0.35f, cy + s * 0.55f), s * 0.18f, s * 0.18f, icon)
                val t = Path().apply { moveTo(cx + s * 0.45f, cy); lineTo(cx + s, cy - s * 0.42f); lineTo(cx + s, cy + s * 0.42f); close() }
                c.drawPath(t, icon)
                icon.style = Paint.Style.FILL; icon.color = Gloss.REC_RED
                c.drawCircle(cx - s * 0.55f, cy - s * 0.18f, s * 0.12f, icon)
            }
            fileLoaded -> {   // save: arrow into a tray
                c.drawLine(cx, cy - s * 0.75f, cx, cy + s * 0.25f, icon)
                c.drawLine(cx - s * 0.38f, cy - s * 0.12f, cx, cy + s * 0.25f, icon)
                c.drawLine(cx + s * 0.38f, cy - s * 0.12f, cx, cy + s * 0.25f, icon)
                val tray = Path().apply { moveTo(cx - s * 0.85f, cy + s * 0.15f); lineTo(cx - s * 0.85f, cy + s * 0.72f); lineTo(cx + s * 0.85f, cy + s * 0.72f); lineTo(cx + s * 0.85f, cy + s * 0.15f) }
                c.drawPath(tray, icon)
            }
            else -> {   // folder
                val f = Path().apply {
                    moveTo(cx - s, cy - s * 0.6f); lineTo(cx - s * 0.3f, cy - s * 0.6f); lineTo(cx - s * 0.12f, cy - s * 0.38f)
                    lineTo(cx + s, cy - s * 0.38f); lineTo(cx + s, cy + s * 0.65f); lineTo(cx - s, cy + s * 0.65f); close()
                }
                c.drawPath(f, icon)
            }
        }
    }

    // ------------------------------------------------------------------------
    // Touch: drag the ring, tap or hold the center
    // ------------------------------------------------------------------------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f; val cy = height / 2f
        val R = min(width, height) / 2f - 6f
        val rBtn = R * 0.58f
        val dx = e.x - cx; val dy = e.y - cy; val r = hypot(dx, dy)
        val ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (r <= rBtn) {
                    pressing = true; longFired = false
                    postDelayed(longPress, 550)
                } else if (r <= R * 1.05f && !locked) {
                    dragging = true; lastTouchAngle = ang; snapAnim?.cancel()
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else return false
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    var d = ang - lastTouchAngle
                    if (d > 180) d -= 360; if (d < -180) d += 360
                    lastTouchAngle = ang; ringAngle += d
                    val m = modeFor(ringAngle)
                    if (m != shownMode) { shownMode = m; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    invalidate()
                } else if (pressing && r > rBtn * 1.25f) {
                    pressing = false; removeCallbacks(longPress); invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) { dragging = false; snapTo(modeFor(ringAngle)) }
                else if (pressing) {
                    pressing = false; removeCallbacks(longPress)
                    if (!longFired) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onShutter?.invoke() }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; snapTo(modeFor(ringAngle)) }
                pressing = false; removeCallbacks(longPress); invalidate()
            }
        }
        return true
    }

    private fun modeFor(a: Float): Int = ((((-a / 120f).roundToInt()) % 3) + 3) % 3

    private fun snapTo(m: Int) {
        val base = -m * 120f
        val k = ((ringAngle - base) / 360f).roundToInt()
        val target = base + k * 360f
        snapAnim = ValueAnimator.ofFloat(ringAngle, target).apply {
            duration = 160; interpolator = DecelerateInterpolator()
            addUpdateListener { ringAngle = it.animatedValue as Float; invalidate() }
            start()
        }
        shownMode = m
        if (m != mode) { mode = m; onModeChanged?.invoke(m) }
    }

    private fun angleDist(a: Float, b: Float): Float { val d = abs(((a - b) % 360 + 360) % 360); return min(d, 360 - d) }
}
