package com.example.dungeon.android

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.dungeon.DungeonGame
import com.example.dungeon.android.ui.ActionButton
import com.example.dungeon.android.ui.ActionIcon
import com.example.dungeon.android.ui.JoystickView
import com.jme3.app.AndroidHarness

/**
 * Android entry point. AndroidHarness loads [com.example.dungeon.DungeonGame]
 * (the same engine-agnostic class the desktop build uses) via reflection and
 * drives it with an OpenGL ES context instead of LWJGL.
 */
class MainActivity : AndroidHarness() {

    init {
        appClass = "com.example.dungeon.DungeonGame"

        // Rendering surface configuration
        eglBitsPerPixel = 24
        eglAlphaBits = 0
        eglDepthBits = 24
        eglSamples = 0
        eglStencilBits = 0
        frameRate = -1 // uncapped

        // The on-screen joystick/buttons drive movement and actions directly
        // through DungeonGame.input (see below), so we don't need
        // AndroidHarness's built-in tap-to-move mouse emulation.
        mouseEventsEnabled = false
        joystickEventsEnabled = false

        // Exit behavior
        finishOnAppStop = true
        handleExitHook = true
        exitDialogTitle = "Exit Project Dungeon?"
        exitDialogMessage = "Press Home to keep the game running in the background, or confirm to exit."

        screenFullScreen = true
        screenShowTitle = false
    }

    /**
     * AndroidHarness's default implementation just does
     * `setContentView(view)`. We override it to wrap that same GL surface in
     * a [FrameLayout] with the joystick/action buttons layered on top, so
     * the game view itself is untouched -- only what's drawn over it changes.
     */
    override fun layoutDisplay() {
        val gameView = this.view
        (gameView.parent as? ViewGroup)?.removeView(gameView)

        val overlay = FrameLayout(this)
        overlay.addView(gameView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        (app as? DungeonGame)?.let { game -> overlay.addView(buildControlsLayer(game)) }

        setContentView(overlay)
    }

    private fun buildControlsLayer(game: DungeonGame): FrameLayout {
        val dp = resources.displayMetrics.density
        fun dp(v: Float) = (v * dp).toInt()

        val layer = FrameLayout(this)

        // Movement joystick, bottom-left.
        val joystick = JoystickView(this).apply {
            onMove = { x, y -> game.input.moveX = x; game.input.moveY = y }
        }
        layer.addView(
            joystick,
            FrameLayout.LayoutParams(dp(150f), dp(150f), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(28f)
                bottomMargin = dp(28f)
            }
        )

        // Action buttons, bottom-right, arranged in a small arc: shield
        // (guard, bottom-left of the cluster), dash (mid), sword (primary,
        // largest and highest -- the thumb's natural resting spot).
        val swordAccent = Color.rgb(0xC9, 0x3A, 0x3A)
        val shieldAccent = Color.rgb(0x3E, 0x7B, 0xC4)
        val dashAccent = Color.rgb(0xD8, 0xA6, 0x3A)

        val swordBtn = ActionButton(this, ActionIcon.SWORD, swordAccent).apply {
            onDown = { game.input.swordQueued = true }
        }
        layer.addView(
            swordBtn,
            FrameLayout.LayoutParams(dp(76f), dp(76f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(28f)
                bottomMargin = dp(96f)
            }
        )

        val dashBtn = ActionButton(this, ActionIcon.DASH, dashAccent).apply {
            onDown = { game.input.dashQueued = true }
        }
        layer.addView(
            dashBtn,
            FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(112f)
                bottomMargin = dp(24f)
            }
        )

        val shieldBtn = ActionButton(this, ActionIcon.SHIELD, shieldAccent).apply {
            onDown = { game.input.shieldHeld = true }
            onUp = { game.input.shieldHeld = false }
        }
        layer.addView(
            shieldBtn,
            FrameLayout.LayoutParams(dp(64f), dp(64f), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20f)
                bottomMargin = dp(24f)
            }
        )

        return layer
    }
}
