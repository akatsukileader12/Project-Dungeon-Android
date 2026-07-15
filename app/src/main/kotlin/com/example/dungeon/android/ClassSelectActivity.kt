package com.example.dungeon.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.dungeon.PlayerClass

/**
 * The very first screen the player sees: pick a hero class before the dungeon
 * loads. Kept as a plain Android view (no jME/GL surface here) so it's cheap
 * to show instantly.
 *
 * Cards are placed in a HorizontalScrollView so Mage and Archer are always
 * reachable regardless of screen width, and the whole column sits inside a
 * ScrollView so nothing is clipped on short landscape screens.
 */
class ClassSelectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(255, 8, 5, 12))

        // Outer vertical scroll so nothing is cut off on short screens.
        val scrollV = ScrollView(this)
        scrollV.isFillViewport = true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }

        col.addView(TextView(this).apply {
            text = "CHOOSE YOUR HERO"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(255, 210, 165, 50))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        col.addView(TextView(this).apply {
            text = "Each class fights the dragon differently"
            textSize = 13f
            setTextColor(Color.argb(160, 200, 180, 140))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6); it.bottomMargin = dp(24) })

        // Horizontal scroll so all 3 cards are reachable on any screen width.
        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }

        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(0), dp(8), dp(0))
        }

        PlayerClass.entries.forEach { cls ->
            cardsRow.addView(buildClassCard(cls), LinearLayout.LayoutParams(
                dp(178), ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(10); it.marginEnd = dp(10) })
        }

        hScroll.addView(cardsRow, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        col.addView(hScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scrollV.addView(col, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(scrollV, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        setContentView(root)
    }

    private fun buildClassCard(cls: PlayerClass): LinearLayout {
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

        val stats = when (cls) {
            PlayerClass.WARRIOR -> "Melee · Shield · Charge"
            PlayerClass.MAGE    -> "Ranged · Ward · Blink"
            PlayerClass.ARCHER  -> "Ranged · Dodge · Sprint"
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(160, 28, 22, 18))
            setPadding(dp(14), dp(18), dp(14), dp(18))
            isClickable = true
            isFocusable = true
        }

        // Class icon
        card.addView(TextView(this).apply {
            text = icon
            textSize = 32f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Class name
        card.addView(TextView(this).apply {
            text = cls.displayName
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) })

        // Tagline
        card.addView(TextView(this).apply {
            text = cls.tagline
            textSize = 11f
            setTextColor(Color.argb(200, 220, 210, 200))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(8) })

        // Stats line
        card.addView(TextView(this).apply {
            text = stats
            textSize = 10f
            setTextColor(Color.argb(140, 200, 190, 160))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(4) })

        // Select button
        card.addView(TextView(this).apply {
            text = "▶  Select"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)))
            gravity = Gravity.CENTER
            setPadding(dp(0), dp(10), dp(0), dp(10))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(14) })

        card.setOnClickListener { selectClass(cls) }
        return card
    }

    private fun selectClass(cls: PlayerClass) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PLAYER_CLASS, cls.name)
        })
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
