package com.example.dungeon.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.dungeon.DungeonGame
import com.example.dungeon.android.ui.ActionButton
import com.example.dungeon.android.ui.ActionIcon
import com.example.dungeon.android.ui.JoystickView
import com.jme3.app.AndroidHarness
import java.util.concurrent.Callable

class MainActivity : AndroidHarness() {

    init {
        appClass = "com.example.dungeon.DungeonGame"

        eglBitsPerPixel = 24;  eglAlphaBits = 0
        eglDepthBits    = 24;  eglSamples   = 0;  eglStencilBits = 0
        frameRate       = -1

        mouseEventsEnabled    = false
        joystickEventsEnabled = false

        finishOnAppStop   = true
        handleExitHook    = true
        exitDialogTitle   = "Exit Project Dungeon?"
        exitDialogMessage = "Press Home to keep the game running in the background, or confirm to exit."

        screenFullScreen = true
        screenShowTitle  = false
    }

    private lateinit var playAgainBtn:    TextView
    private lateinit var loadingOverlay:  FrameLayout
    private lateinit var progressFill:    View
    private lateinit var percentLabel:    TextView
    private var progressAnimator: ValueAnimator? = null

    // ── Layout ─────────────────────────────────────────────────────────────────

    override fun layoutDisplay() {
        val glView = view

        val root = FrameLayout(this)
        root.addView(glView, matchParent())

        // HUD controls sit above the GL surface
        root.addView(buildControlsLayer())

        // Play Again button (initially hidden; shown via onGameOver)
        playAgainBtn = buildPlayAgainButton()
        root.addView(playAgainBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).also { it.topMargin = dp(60) })

        // Loading overlay sits on top of everything
        loadingOverlay = buildLoadingOverlay()
        root.addView(loadingOverlay, matchParent())

        setContentView(root)

        // Wire jME callbacks now — app is already instantiated at this point.
        (app as? DungeonGame)?.let { game ->
            game.onGameOver = { _ ->
                runOnUiThread { playAgainBtn.visibility = View.VISIBLE }
            }
            game.onInitComplete = {
                // Animate to 100 % then fade out the overlay on the UI thread.
                runOnUiThread { finishLoading() }
            }
        }

        // Start the simulated loading progress animation immediately.
        startLoadingAnimation()
    }

    // ── Loading overlay ────────────────────────────────────────────────────────

    private fun buildLoadingOverlay(): FrameLayout {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(255, 8, 5, 12))

        // Centre column
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // Title
        col.addView(TextView(this).apply {
            text      = "DUNGEON"
            textSize  = 44f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(255, 210, 165, 50))
            gravity   = Gravity.CENTER
        })

        // Subtitle
        col.addView(TextView(this).apply {
            text     = "Prepare for battle"
            textSize = 14f
            setTextColor(Color.argb(160, 200, 180, 140))
            gravity  = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(8) })

        // Spacer
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(40)))

        // Progress bar track
        val track = FrameLayout(this)
        track.setBackgroundColor(Color.argb(60, 210, 165, 50))

        progressFill = View(this).apply { setBackgroundColor(Color.argb(220, 210, 165, 50)) }
        track.addView(progressFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))

        col.addView(track, LinearLayout.LayoutParams(dp(280), dp(6)))

        // Percentage label
        percentLabel = TextView(this).apply {
            text      = "0%"
            textSize  = 13f
            setTextColor(Color.argb(180, 210, 165, 50))
            gravity   = Gravity.CENTER
        }
        col.addView(percentLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(10) })

        overlay.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        return overlay
    }

    /**
     * Animate the progress bar from 0 → 88 % while assets load.
     * The bar decelerates near 88 % so it never appears "stuck at 100" before
     * the real init-complete signal arrives.
     */
    private fun startLoadingAnimation() {
        val trackWidth = dp(280)
        progressAnimator = ValueAnimator.ofFloat(0f, 0.88f).apply {
            duration = 3500L
            // Decelerate interpolator: fast at first, slow near the end
            interpolator = android.view.animation.DecelerateInterpolator(2.5f)
            addUpdateListener { va ->
                val pct = va.animatedValue as Float
                updateProgressBar(pct)
            }
            start()
        }
    }

    /**
     * Called when jME fires onInitComplete.
     * Snaps the bar to 100 % and then fades out the overlay.
     */
    private fun finishLoading() {
        progressAnimator?.cancel()
        // Quick fill to 100 %
        ValueAnimator.ofFloat(currentProgress(), 1f).apply {
            duration = 300L
            addUpdateListener { va -> updateProgressBar(va.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    // Fade out the whole overlay
                    loadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(400L)
                        .withEndAction { loadingOverlay.visibility = View.GONE }
                        .start()
                }
            })
            start()
        }
    }

    private fun currentProgress(): Float {
        val trackW = progressFill.parent?.let { (it as? FrameLayout)?.width } ?: dp(280)
        return if (trackW > 0) progressFill.layoutParams.width.toFloat() / trackW else 0f
    }

    private fun updateProgressBar(fraction: Float) {
        val pct = (fraction * 100).toInt().coerceIn(0, 100)
        percentLabel.text = "$pct%"
        val trackW = (progressFill.parent as? FrameLayout)?.width?.takeIf { it > 0 } ?: dp(280)
        progressFill.layoutParams = FrameLayout.LayoutParams(
            (trackW * fraction).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        progressFill.requestLayout()
    }

    // ── Play Again button ──────────────────────────────────────────────────────

    private fun buildPlayAgainButton(): TextView =
        TextView(this).apply {
            text      = "Play Again"
            textSize  = 20f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(210, 40, 40, 40))
            setPadding(dp(24), dp(14), dp(24), dp(14))
            visibility = View.GONE

            setOnClickListener {
                visibility = View.GONE
                // restartGame() modifies the jME scene graph and MUST run on the
                // GL thread. app.enqueue() schedules it for the next GL frame.
                app.enqueue(Callable<Void?> {
                    (app as? DungeonGame)?.restartGame()
                    null
                })
            }
        }

    // ── HUD controls ──────────────────────────────────────────────────────────

    private fun buildControlsLayer(): FrameLayout {
        val layer = FrameLayout(this)

        // Joystick — bottom-left
        layer.addView(
            JoystickView(this).apply {
                onMove = { x, y ->
                    (app as? DungeonGame)?.input?.let { it.moveX = x; it.moveY = y }
                }
            },
            FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(28);  bottomMargin = dp(28)
            }
        )

        // Sword — bottom-right, raised
        layer.addView(
            ActionButton(this, ActionIcon.SWORD, Color.rgb(0xC9, 0x3A, 0x3A)).apply {
                onDown = { (app as? DungeonGame)?.input?.swordQueued = true }
            },
            FrameLayout.LayoutParams(dp(76), dp(76), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(28);  bottomMargin = dp(96)
            }
        )

        // Dash — bottom-right, left of shield
        layer.addView(
            ActionButton(this, ActionIcon.DASH, Color.rgb(0xD8, 0xA6, 0x3A)).apply {
                onDown = { (app as? DungeonGame)?.input?.dashQueued = true }
            },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(112);  bottomMargin = dp(24)
            }
        )

        // Shield — bottom-right corner (hold)
        layer.addView(
            ActionButton(this, ActionIcon.SHIELD, Color.rgb(0x3E, 0x7B, 0xC4)).apply {
                onDown = { (app as? DungeonGame)?.input?.shieldHeld = true  }
                onUp   = { (app as? DungeonGame)?.input?.shieldHeld = false }
            },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20);  bottomMargin = dp(24)
            }
        )

        return layer
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
}
