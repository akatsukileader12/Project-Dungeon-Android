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
import android.widget.ScrollView
import android.widget.TextView
import com.example.dungeon.DungeonGame
import com.example.dungeon.PlayerClass
import com.example.dungeon.android.ui.ActionButton
import com.example.dungeon.android.ui.ActionIcon
import com.example.dungeon.android.ui.JoystickView
import com.jme3.app.AndroidHarness
import java.util.concurrent.Callable

class MainActivity : AndroidHarness() {

    init {
        appClass = "com.example.dungeon.DungeonGame"

        eglBitsPerPixel = 24;  eglAlphaBits   = 0
        eglDepthBits    = 24;  eglSamples     = 0;  eglStencilBits = 0
        frameRate       = -1

        mouseEventsEnabled    = false
        joystickEventsEnabled = false

        finishOnAppStop   = true
        handleExitHook    = true
        exitDialogTitle   = "Exit Project Dungeon?"
        exitDialogMessage = "Press Home to keep it running in the background, or confirm to exit."

        screenFullScreen = true
        screenShowTitle  = false
    }

    private lateinit var playAgainBtn:   TextView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var progressFill:   View
    private lateinit var percentLabel:   TextView
    private lateinit var rootLayout:     FrameLayout
    private var progressAnimator: ValueAnimator? = null

    /** Current class in use — may change mid-game via the settings overlay. */
    private var currentClass: PlayerClass = PlayerClass.WARRIOR

    private val selectedClass: PlayerClass by lazy {
        val name = intent.getStringExtra(EXTRA_PLAYER_CLASS)
        PlayerClass.entries.firstOrNull { it.name == name } ?: PlayerClass.WARRIOR
    }

    // ── Layout ─────────────────────────────────────────────────────────────────

    override fun layoutDisplay() {
        currentClass = selectedClass

        // ── IMPORTANT: get the GL view and wire callbacks BEFORE setContentView.
        //   AndroidHarness starts the GL render thread when the GLSurfaceView is
        //   attached to the window (inside setContentView). Registering callbacks
        //   after setContentView creates a race condition: simpleInitApp can
        //   complete and fire onInitComplete before we register it, causing the
        //   loading screen to stay on screen forever.

        // Step 1: instantiate the app + get the surface view (no GL yet).
        val glView = view

        // Step 2: apply the chosen class to the game object immediately.
        (app as? DungeonGame)?.playerClass = currentClass

        // Step 3: wire callbacks while we still control the GL thread start.
        (app as? DungeonGame)?.let { game ->
            game.onGameOver = { _ ->
                runOnUiThread {
                    playAgainBtn.visibility = View.VISIBLE
                }
            }
            game.onInitComplete = {
                // GL thread fires this when the scene is fully ready.
                runOnUiThread { finishLoading() }
            }
        }

        // Step 4: build the view tree.
        rootLayout = FrameLayout(this)
        rootLayout.addView(glView, matchParent())
        rootLayout.addView(buildControlsLayer(currentClass))

        playAgainBtn = buildPlayAgainButton()
        rootLayout.addView(playAgainBtn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).also { it.topMargin = dp(60) })

        loadingOverlay = buildLoadingOverlay()
        rootLayout.addView(loadingOverlay, matchParent())

        // Step 5: attach to window → GL thread starts here.
        setContentView(rootLayout)

        // Step 6: safety net — if GL init already finished before setContentView
        //   returned (extremely fast device / warm JIT), dismiss now.
        if ((app as? DungeonGame)?.initComplete == true) {
            runOnUiThread { finishLoading() }
        }

        startLoadingAnimation()
    }

    // ── Settings overlay (class switch during battle) ──────────────────────────

    private fun showClassSwitchOverlay() {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(210, 0, 0, 0))
        overlay.isClickable = true   // consume taps behind the panel

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(230, 14, 10, 20))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        panel.addView(TextView(this).apply {
            text     = "⚙  Switch Class"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(255, 210, 165, 50))
            gravity  = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(18) })

        panel.addView(TextView(this).apply {
            text     = "The battle will restart with your new class."
            textSize = 12f
            setTextColor(Color.argb(160, 200, 200, 200))
            gravity  = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(20) })

        PlayerClass.entries.forEach { cls ->
            val accent = when (cls) {
                PlayerClass.WARRIOR -> Color.rgb(0xC9, 0x3A, 0x3A)
                PlayerClass.MAGE    -> Color.rgb(0xB8, 0x4A, 0xE0)
                PlayerClass.ARCHER  -> Color.rgb(0x5C, 0x9A, 0x4A)
            }
            val icon = when (cls) {
                PlayerClass.WARRIOR -> "⚔"
                PlayerClass.MAGE    -> "✦"
                PlayerClass.ARCHER  -> "🏹"
            }
            val btn = TextView(this).apply {
                text     = "$icon  ${cls.displayName}"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (cls == currentClass) Color.WHITE else Color.argb(200, 220, 210, 200))
                setBackgroundColor(
                    if (cls == currentClass)
                        Color.argb(200, Color.red(accent), Color.green(accent), Color.blue(accent))
                    else
                        Color.argb(80, Color.red(accent), Color.green(accent), Color.blue(accent))
                )
                gravity  = Gravity.CENTER
                setPadding(dp(0), dp(14), dp(0), dp(14))
                isClickable = true
                isFocusable = true
            }
            btn.setOnClickListener {
                rootLayout.removeView(overlay)
                if (cls != currentClass) {
                    currentClass = cls
                    // Rebuild the controls layer for the new class icons.
                    rebuildControlsLayer(cls)
                    // Tell the game engine to switch class on the GL thread.
                    app.enqueue(Callable<Void?> {
                        (app as? DungeonGame)?.switchClass(cls)
                        null
                    })
                }
            }
            panel.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) })
        }

        // Cancel
        val cancel = TextView(this).apply {
            text     = "Cancel"
            textSize = 14f
            setTextColor(Color.argb(180, 200, 200, 200))
            setBackgroundColor(Color.argb(60, 200, 200, 200))
            gravity  = Gravity.CENTER
            setPadding(dp(0), dp(12), dp(0), dp(12))
            isClickable = true
            isFocusable = true
        }
        cancel.setOnClickListener { rootLayout.removeView(overlay) }
        panel.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) })

        overlay.addView(panel, FrameLayout.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        overlay.setOnClickListener { rootLayout.removeView(overlay) }

        rootLayout.addView(overlay, matchParent())
    }

    // ── Controls layer ─────────────────────────────────────────────────────────

    private var controlsLayer: FrameLayout? = null

    private fun rebuildControlsLayer(cls: PlayerClass) {
        controlsLayer?.let { rootLayout.removeView(it) }
        val newLayer = buildControlsLayer(cls)
        // Insert at index 1 (above GL view, below everything else).
        rootLayout.addView(newLayer, 1, matchParent())
        controlsLayer = newLayer
    }

    private fun buildControlsLayer(cls: PlayerClass): FrameLayout {
        val layer = FrameLayout(this)
        controlsLayer = layer

        // Joystick — bottom-left
        layer.addView(
            JoystickView(this).apply {
                onMove = { x, y ->
                    (app as? DungeonGame)?.input?.let { it.moveX = x; it.moveY = y }
                }
            },
            FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(28); bottomMargin = dp(28)
            }
        )

        // Primary attack — bottom-right, raised.
        val attackIcon = when (cls) {
            PlayerClass.WARRIOR -> ActionIcon.SWORD
            PlayerClass.MAGE    -> ActionIcon.STAFF
            PlayerClass.ARCHER  -> ActionIcon.BOW
        }
        val attackColor = when (cls) {
            PlayerClass.WARRIOR -> Color.rgb(0xC9, 0x3A, 0x3A)
            PlayerClass.MAGE    -> Color.rgb(0xB8, 0x4A, 0xE0)
            PlayerClass.ARCHER  -> Color.rgb(0x5C, 0x9A, 0x4A)
        }
        layer.addView(
            ActionButton(this, attackIcon, attackColor).apply {
                onDown = { (app as? DungeonGame)?.input?.attackQueued = true }
            },
            FrameLayout.LayoutParams(dp(76), dp(76), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(28); bottomMargin = dp(96)
            }
        )

        // Mobility
        val abilityIcon = if (cls == PlayerClass.MAGE) ActionIcon.BLINK else ActionIcon.DASH
        layer.addView(
            ActionButton(this, abilityIcon, Color.rgb(0xD8, 0xA6, 0x3A)).apply {
                onDown = { (app as? DungeonGame)?.input?.abilityQueued = true }
            },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(112); bottomMargin = dp(24)
            }
        )

        // Defense
        val defenseIcon = when (cls) {
            PlayerClass.WARRIOR -> ActionIcon.SHIELD
            PlayerClass.MAGE    -> ActionIcon.WARD
            PlayerClass.ARCHER  -> ActionIcon.DODGE
        }
        layer.addView(
            ActionButton(this, defenseIcon, Color.rgb(0x3E, 0x7B, 0xC4)).apply {
                onDown = { (app as? DungeonGame)?.input?.defenseHeld = true  }
                onUp   = { (app as? DungeonGame)?.input?.defenseHeld = false }
            },
            FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20); bottomMargin = dp(24)
            }
        )

        // Settings gear — top-right, opens the class-switch overlay.
        layer.addView(
            buildSettingsButton(),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(16); topMargin = dp(16)
            }
        )

        return layer
    }

    private fun buildSettingsButton(): TextView =
        TextView(this).apply {
            text     = "⚙"
            textSize = 22f
            gravity  = Gravity.CENTER
            setTextColor(Color.argb(200, 220, 210, 180))
            setBackgroundColor(Color.argb(100, 20, 16, 10))
            isClickable = true
            isFocusable = true
            setOnClickListener { showClassSwitchOverlay() }
        }

    // ── Loading overlay ────────────────────────────────────────────────────────

    private fun buildLoadingOverlay(): FrameLayout {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(255, 8, 5, 12))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        col.addView(TextView(this).apply {
            text     = "PROJECT DUNGEON"
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(255, 210, 165, 50))
            gravity  = Gravity.CENTER
        })

        col.addView(TextView(this).apply {
            text     = "${selectedClass.displayName}  ·  ${selectedClass.tagline}"
            textSize = 13f
            setTextColor(Color.argb(180, 200, 185, 150))
            gravity  = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(10); it.bottomMargin = dp(36) })

        // Progress bar track
        val track = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(100, 60, 50, 40))
        }
        progressFill = View(this).apply {
            setBackgroundColor(Color.argb(255, 210, 150, 40))
        }
        track.addView(progressFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(track, LinearLayout.LayoutParams(dp(280), dp(6)))

        percentLabel = TextView(this).apply {
            text     = "0%"
            textSize = 12f
            setTextColor(Color.argb(180, 200, 180, 140))
            gravity  = Gravity.CENTER
        }
        col.addView(percentLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(8) })

        overlay.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        return overlay
    }

    private fun startLoadingAnimation() {
        progressAnimator = ValueAnimator.ofFloat(0f, 0.85f).apply {
            duration = 3500
            addUpdateListener { updateProgressBar(it.animatedValue as Float) }
            start()
        }
    }

    private fun finishLoading() {
        progressAnimator?.cancel()
        // Animate bar to 100 % then fade the whole overlay out.
        ValueAnimator.ofFloat(currentProgress(), 1f).apply {
            duration = 300
            addUpdateListener { updateProgressBar(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    loadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(400)
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
        val pct    = (fraction * 100).toInt().coerceIn(0, 100)
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
            text      = "▶  Play Again"
            textSize  = 20f
            typeface  = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(210, 40, 40, 40))
            setPadding(dp(24), dp(14), dp(24), dp(14))
            visibility = View.GONE

            setOnClickListener {
                visibility = View.GONE
                app.enqueue(Callable<Void?> {
                    (app as? DungeonGame)?.restartGame()
                    null
                })
            }
        }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    companion object {
        const val EXTRA_PLAYER_CLASS = "player_class"
    }
}
