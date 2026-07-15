package com.example.dungeon.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

enum class ActionIcon { SWORD, SHIELD, DASH, STAFF, BOW, WARD, BLINK, DODGE }

/**
 * A round HUD button with a hand-drawn icon glyph (no image assets), a
 * colored glow themed per-action, and a press animation.
 *
 * One-shot buttons ([SWORD]/[DASH]/[STAFF]/[BOW]/[BLINK]/[DODGE]) fire
 * [onDown] once per tap. Hold buttons ([SHIELD]/[WARD]) bracket the press
 * with [onDown]/[onUp] so the caller can drive a continuous "held" state.
 */
class ActionButton(
    context: Context,
    private val icon: ActionIcon,
    private val accent: Int,
) : View(context) {

    var onDown: () -> Unit = {}
    var onUp: () -> Unit = {}

    private var pressed = false
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private var fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = (Math.min(w, h) / 2f) * 0.90f
        iconPaint.strokeWidth = radius * 0.14f
        ringPaint.color = accent
        rebuildFill()
    }

    private fun rebuildFill() {
        val core = if (pressed) lighten(accent, 0.35f) else accent
        fillPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(lighten(core, 0.25f), withAlpha(core, 210), withAlpha(core, 60)),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        ringPaint.alpha = if (pressed) 255 else 200
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = if (pressed) radius * 0.92f else radius
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        drawIcon(canvas, r)
    }

    private fun drawIcon(canvas: Canvas, r: Float) {
        val s = r * 0.62f // icon half-extent
        when (icon) {
            ActionIcon.SWORD -> {
                // Diagonal blade from lower-left to upper-right, with a crossguard and hilt.
                val path = Path().apply {
                    moveTo(cx - s * 0.75f, cy + s * 0.85f)
                    lineTo(cx + s * 0.75f, cy - s * 0.85f)
                }
                canvas.drawPath(path, iconPaint)
                // Crossguard, perpendicular near the base of the blade.
                val gx = cx - s * 0.32f
                val gy = cy + s * 0.42f
                canvas.drawLine(gx - s * 0.32f, gy + s * 0.20f, gx + s * 0.32f, gy - s * 0.20f, iconPaint)
                // Pommel dot.
                canvas.drawCircle(cx - s * 0.68f, cy + s * 0.78f, r * 0.08f, iconFillPaint)
            }
            ActionIcon.SHIELD -> {
                val path = Path().apply {
                    moveTo(cx, cy - s)
                    lineTo(cx + s * 0.85f, cy - s * 0.55f)
                    lineTo(cx + s * 0.85f, cy + s * 0.15f)
                    cubicTo(cx + s * 0.85f, cy + s * 0.75f, cx + s * 0.35f, cy + s * 1.05f, cx, cy + s * 1.15f)
                    cubicTo(cx - s * 0.35f, cy + s * 1.05f, cx - s * 0.85f, cy + s * 0.75f, cx - s * 0.85f, cy + s * 0.15f)
                    lineTo(cx - s * 0.85f, cy - s * 0.55f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx, cy - s * 0.7f, cx, cy + s * 0.55f, iconPaint)
            }
            ActionIcon.DASH -> {
                // Two forward chevrons -- reads as "speed".
                for (offset in floatArrayOf(-s * 0.42f, s * 0.28f)) {
                    val path = Path().apply {
                        moveTo(cx - s * 0.55f + offset, cy - s * 0.7f)
                        lineTo(cx + s * 0.15f + offset, cy)
                        lineTo(cx - s * 0.55f + offset, cy + s * 0.7f)
                    }
                    canvas.drawPath(path, iconPaint)
                }
            }
            ActionIcon.STAFF -> {
                // A vertical staff shaft with a glowing orb at the top -- Mage's fireball.
                canvas.drawLine(cx - s * 0.15f, cy + s, cx + s * 0.45f, cy - s * 0.75f, iconPaint)
                canvas.drawCircle(cx + s * 0.55f, cy - s * 0.85f, r * 0.22f, iconFillPaint)
            }
            ActionIcon.BOW -> {
                // A curved bow limb with a drawn string -- Archer's arrow shot.
                val path = Path().apply {
                    moveTo(cx + s * 0.35f, cy - s * 0.95f)
                    cubicTo(cx - s * 0.75f, cy - s * 0.5f, cx - s * 0.75f, cy + s * 0.5f, cx + s * 0.35f, cy + s * 0.95f)
                }
                canvas.drawPath(path, iconPaint)
                canvas.drawLine(cx + s * 0.35f, cy - s * 0.95f, cx - s * 0.15f, cy, iconPaint)
                canvas.drawLine(cx - s * 0.15f, cy, cx + s * 0.35f, cy + s * 0.95f, iconPaint)
                canvas.drawLine(cx - s * 0.15f, cy, cx + s * 0.85f, cy, iconPaint)
            }
            ActionIcon.WARD -> {
                // A soft hexagonal barrier -- Mage's magic ward (block).
                val path = Path().apply {
                    moveTo(cx, cy - s)
                    lineTo(cx + s * 0.87f, cy - s * 0.5f)
                    lineTo(cx + s * 0.87f, cy + s * 0.5f)
                    lineTo(cx, cy + s)
                    lineTo(cx - s * 0.87f, cy + s * 0.5f)
                    lineTo(cx - s * 0.87f, cy - s * 0.5f)
                    close()
                }
                canvas.drawPath(path, iconPaint)
                canvas.drawCircle(cx, cy, r * 0.16f, iconFillPaint)
            }
            ActionIcon.BLINK -> {
                // A dotted arc + a small diamond "landing" mark -- teleport hop.
                canvas.drawArc(cx - s, cy - s, cx + s, cy + s, -60f, 120f, false, iconPaint)
                val d = r * 0.14f
                canvas.save()
                canvas.translate(cx + s * 0.7f, cy)
                canvas.rotate(45f)
                canvas.drawRect(-d, -d, d, d, iconFillPaint)
                canvas.restore()
            }
            ActionIcon.DODGE -> {
                // A curved motion-swirl -- rolling out of the way.
                canvas.drawArc(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f, 20f, 280f, false, iconPaint)
                val path = Path().apply {
                    moveTo(cx + s * 0.55f, cy - s * 0.55f)
                    lineTo(cx + s * 0.85f, cy - s * 0.55f)
                    lineTo(cx + s * 0.7f, cy - s * 0.22f)
                    close()
                }
                canvas.drawPath(path, iconFillPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                rebuildFill()
                invalidate()
                onDown()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                rebuildFill()
                invalidate()
                onUp()
                return true
            }
        }
        return false
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
