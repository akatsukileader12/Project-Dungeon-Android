package com.example.dungeon.android

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.example.dungeon.DungeonGame
import com.example.dungeon.android.ui.ActionButton
import com.example.dungeon.android.ui.ActionIcon
import com.example.dungeon.android.ui.JoystickView
import com.jme3.app.AndroidHarness

/**
 * Android entry point.  AndroidHarness loads [DungeonGame] via reflection
 * and drives it with an OpenGL ES context.
 */
class MainActivity : AndroidHarness() {

    init {
        appClass = "com.example.dungeon.DungeonGame"

        eglBitsPerPixel = 24
        eglAlphaBits    = 0
        eglDepthBits    = 24
        eglSamples      = 0
        eglStencilBits  = 0
        frameRate       = -1

        // The on-screen joystick/buttons drive DungeonGame.input directly;
        // jME's built-in mouse/joystick emulation is not needed.
        mouseEventsEnabled    = false
        joystickEventsEnabled = false

        finishOnAppStop   = true
        handleExitHook    = true
        exitDialogTitle   = "Exit Project Dungeon?"
        exitDialogMessage = "Press Home to keep the game running in the " +
                "background, or confirm to exit."

        screenFullScreen = true
        screenShowTitle  = false
    }

    // ── Play Again button, created once and shown/hidden via onGameOver ────────

    private lateinit var playAgainBtn: TextView

    // ── Layout ─────────────────────────────────────────────────────────────────

    /**
     * AndroidHarness calls this from onCreate() after the jME [app] instance
     * has been instantiated (via reflection from [appClass]) but before
     * [com.jme3.app.Application.start] has been invoked.
     *
     * We wrap the GL surface in a FrameLayout so we can layer the HUD
     * controls and the Play Again button on top without touching the engine.
     */
    override fun layoutDisplay() {
        // `view` is the GLSurfaceView set up by AndroidHarness.
        val glView = view

        val overlay = FrameLayout(this)
        overlay.addView(
            glView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Build and overlay the HUD controls.
        overlay.addView(buildControlsLayer())

        // Build the Play Again button (initially invisible).
        playAgainBtn = buildPlayAgainButton()
        overlay.addView(
            playAgainBtn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                val dp = resources.displayMetrics.density
                topMargin = (60 * dp).toInt()
            }
        )

        setContentView(overlay)

        // Wire the restart callback now — app is already instantiated.
        (app as? DungeonGame)?.onGameOver = { _ ->
            runOnUiThread { playAgainBtn.visibility = View.VISIBLE }
        }
    }

    // ── Play Again button ──────────────────────────────────────────────────────

    private fun buildPlayAgainButton(): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text      = "Play Again"
            textSize  = 20f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(210, 40, 40, 40))
            setPadding(
                (24 * dp).toInt(), (14 * dp).toInt(),
                (24 * dp).toInt(), (14 * dp).toInt()
            )
            visibility = View.GONE

            setOnClickListener {
                visibility = View.GONE
                (app as? DungeonGame)?.restartGame()
            }
        }
    }

    // ── Action buttons + joystick ──────────────────────────────────────────────

    private fun buildControlsLayer(): FrameLayout {
        val dp = resources.displayMetrics.density
        fun dpI(v: Float) = (v * dp).toInt()

        val layer = FrameLayout(this)

        // Movement joystick — bottom-left
        val joystick = JoystickView(this).apply {
            onMove = { x, y ->
                (app as? DungeonGame)?.input?.let { it.moveX = x; it.moveY = y }
            }
        }
        layer.addView(
            joystick,
            FrameLayout.LayoutParams(dpI(150f), dpI(150f), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin   = dpI(28f)
                bottomMargin = dpI(28f)
            }
        )

        // Action buttons — bottom-right
        val swordAccent  = Color.rgb(0xC9, 0x3A, 0x3A)
        val shieldAccent = Color.rgb(0x3E, 0x7B, 0xC4)
        val dashAccent   = Color.rgb(0xD8, 0xA6, 0x3A)

        // Sword — taller slot so it doesn't overlap the other two
        layer.addView(
            ActionButton(this, ActionIcon.SWORD, swordAccent).apply {
                onDown = { (app as? DungeonGame)?.input?.swordQueued = true }
            },
            FrameLayout.LayoutParams(dpI(76f), dpI(76f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin  = dpI(28f)
                bottomMargin = dpI(96f)
            }
        )

        // Dash
        layer.addView(
            ActionButton(this, ActionIcon.DASH, dashAccent).apply {
                onDown = { (app as? DungeonGame)?.input?.dashQueued = true }
            },
            FrameLayout.LayoutParams(dpI(64f), dpI(64f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin  = dpI(112f)
                bottomMargin = dpI(24f)
            }
        )

        // Shield (hold)
        layer.addView(
            ActionButton(this, ActionIcon.SHIELD, shieldAccent).apply {
                onDown = { (app as? DungeonGame)?.input?.shieldHeld = true  }
                onUp   = { (app as? DungeonGame)?.input?.shieldHeld = false }
            },
            FrameLayout.LayoutParams(dpI(64f), dpI(64f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin  = dpI(20f)
                bottomMargin = dpI(24f)
            }
        )

        return layer
    }
}
