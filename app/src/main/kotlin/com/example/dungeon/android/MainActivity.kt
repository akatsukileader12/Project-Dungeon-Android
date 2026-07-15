package com.example.dungeon.android

import android.graphics.Color
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
 * Android entry point.  AndroidHarness loads [DungeonGame] via reflection and
 * drives it with an OpenGL ES context.
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
        // we do not need AndroidHarness's built-in mouse emulation.
        mouseEventsEnabled   = false
        joystickEventsEnabled = false

        finishOnAppStop  = true
        handleExitHook   = true
        exitDialogTitle   = "Exit Project Dungeon?"
        exitDialogMessage = "Press Home to keep the game running in the background, or confirm to exit."

        screenFullScreen = true
        screenShowTitle  = false
    }

    /**
     * Wrap the GL surface in a FrameLayout so we can layer the HUD controls
     * and the Play Again button on top without touching the engine view.
     */
    override fun layoutDisplay() {
        val gameView = this.view
        (gameView.parent as? ViewGroup)?.removeView(gameView)

        val overlay = FrameLayout(this)
        overlay.addView(
            gameView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        (app as? DungeonGame)?.let { game ->
            overlay.addView(buildControlsLayer(game))
            overlay.addView(buildRestartButton(game))
        }

        setContentView(overlay)
    }

    // ── Action buttons + joystick ──────────────────────────────────────────────

    private fun buildControlsLayer(game: DungeonGame): FrameLayout {
        val dp = resources.displayMetrics.density
        fun dp(v: Float) = (v * dp).toInt()

        val layer = FrameLayout(this)

        // Movement joystick — bottom-left
        val joystick = JoystickView(this).apply {
            onMove = { x, y -> game.input.moveX = x; game.input.moveY = y }
        }
        layer.addView(
            joystick,
            FrameLayout.LayoutParams(dp(150f), dp(150f), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin   = dp(28f)
                bottomMargin = dp(28f)
            }
        )

        // Action buttons — bottom-right
        val swordAccent  = Color.rgb(0xC9, 0x3A, 0x3A)
        val shieldAccent = Color.rgb(0x3E, 0x7B, 0xC4)
        val dashAccent   = Color.rgb(0xD8, 0xA6, 0x3A)

        layer.addView(
            ActionButton(this, ActionIcon.SWORD, swordAccent).apply {
                onDown = { game.input.swordQueued = true }
            },
            FrameLayout.LayoutParams(dp(76f), dp(76f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin  = dp(28f)
                bottomMargin = dp(96f)
            }
        )
        layer.addView(
            ActionButton(this, ActionIcon.DASH, dashAccent).apply {
                onDown = { game.input.dashQueued = true }
            },
            FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin  = dp(112f)
                bottomMargin = dp(24f)
            }
        )
        layer.addView(
            ActionButton(this, ActionIcon.SHIELD, shieldAccent).apply {
                onDown = { game.input.shieldHeld = true }
                onUp   = { game.input.shieldHeld = false }
            },
            FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin  = dp(20f)
                bottomMargin = dp(24f)
            }
        )

        return layer
    }

    // ── Play Again button ──────────────────────────────────────────────────────

    private fun buildRestartButton(game: DungeonGame): TextView {
        val dp = resources.displayMetrics.density

        val btn = TextView(this).apply {
            text      = "Play Again"
            textSize  = 20f
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(210, 40, 40, 40))
            setPadding(
                (24 * dp).toInt(), (14 * dp).toInt(),
                (24 * dp).toInt(), (14 * dp).toInt()
            )
            visibility = View.GONE   // hidden until the game ends
        }

        // Show the button as soon as the engine reports game-over.
        // onGameOver is called on the GL thread, so post to the UI thread.
        game.onGameOver = { _ ->
            runOnUiThread { btn.visibility = View.VISIBLE }
        }

        btn.setOnClickListener {
            btn.visibility = View.GONE
            // restartGame() is thread-safe: it only writes volatile fields and
            // jME scene operations are serialized in simpleUpdate on the GL thread.
            game.restartGame()
        }

        return FrameLayout(this).apply {
            addView(
                btn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply { topMargin = (60 * dp).toInt() }
            )
        }.also { container ->
            // The container fills the screen so the button centres correctly.
            container.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Intercept touch only when visible to avoid blocking game input.
            container.isClickable = false
            container.isFocusable = false
        }
    }
}
