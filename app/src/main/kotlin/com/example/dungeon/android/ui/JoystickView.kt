package com.example.dungeon.android.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A round, glowing on-screen movement joystick. Drawn entirely with Canvas
 * (no image assets) so it stays crisp on any screen density.
 *
 * Reports a normalized (-1..1, -1..1) vector via [onMove] on every drag, and
 * animates the knob back to center (calling [onMove] with 0,0) on release.
 */
class JoystickView(context: Context) : View(context) {

    /** x = left(-1)/right(1), y = pulled-toward-player(-1)/pushed-away(1). */
    var onMove: (x: Float, y: Float) -> Unit = { _, _ -> }

    private val accent = 0xFFE0A54A.toInt()      // warm torchlight gold, matches the dungeon lighting
    private val baseFill = 0x552A2622
    private val knobFill = 0xCC3A322A.toInt()

    private var basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = accent
        alpha = 180
    }
    private var knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = accent
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f

    private var knobX = 0f
    private var knobY = 0f
    private var activePointerId = -1
    private var snapBackAnimator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f * 0.92f
        knobRadius = baseRadius * 0.42f
        knobX = centerX
        knobY = centerY

        basePaint.shader = RadialGradient(
            centerX, centerY, baseRadius,
            intArrayOf(0x66443322, baseFill, 0x33000000),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        knobPaint.shader = RadialGradient(
            centerX, centerY, knobRadius,
            intArrayOf(0xFF6B5940.toInt(), knobFill),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activePointerId == -1) {
                    val idx = event.actionIndex
                    activePointerId = event.getPointerId(idx)
                    snapBackAnimator?.cancel()
                    updateKnob(event.getX(idx), event.getY(idx))
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx != -1) updateKnob(event.getX(idx), event.getY(idx))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    event.getPointerId(idx) == activePointerId
                ) {
                    activePointerId = -1
                    animateBackToCenter()
                }
                return true
            }
        }
        return false
    }

    private fun updateKnob(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val clamped = min(dist, baseRadius)
        val scale = if (dist > 0.001f) clamped / dist else 0f
        knobX = centerX + dx * scale
        knobY = centerY + dy * scale
        invalidate()

        val normX = ((knobX - centerX) / baseRadius).coerceIn(-1f, 1f)
        // Screen Y grows downward; "pushed away/up" should be positive.
        val normY = (-(knobY - centerY) / baseRadius).coerceIn(-1f, 1f)
        onMove(normX, normY)
    }

    private fun animateBackToCenter() {
        val fromX = knobX
        val fromY = knobY
        snapBackAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 120
            addUpdateListener {
                val t = it.animatedValue as Float
                knobX = centerX + (fromX - centerX) * t
                knobY = centerY + (fromY - centerY) * t
                invalidate()
                onMove(
                    ((knobX - centerX) / baseRadius).coerceIn(-1f, 1f),
                    (-(knobY - centerY) / baseRadius).coerceIn(-1f, 1f)
                )
            }
            start()
        }
    }
}
