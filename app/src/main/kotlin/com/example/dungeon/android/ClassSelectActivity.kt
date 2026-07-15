package com.example.dungeon.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.dungeon.PlayerClass

/**
 * The very first screen the player sees: pick a hero class before the dungeon
 * loads. Kept as a plain Android view (no jME/GL surface here) so it's cheap
 * to show instantly, with no GL init cost, while the choice made here decides
 * which class [MainActivity] hands to [com.example.dungeon.DungeonGame].
 */
class ClassSelectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(255, 8, 5, 12))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        col.addView(TextView(this).apply {
            text = "CHOOSE YOUR HERO"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(255, 210, 165, 50))
            gravity = Gravity.CENTER
        })

        col.addView(TextView(this).apply {
            text = "Each class fights the dragon differently"
            textSize = 14f
            setTextColor(Color.argb(160, 200, 180, 140))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(8); it.bottomMargin = dp(32) })

        val cardsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        PlayerClass.entries.forEach { cls ->
            cardsRow.addView(buildClassCard(cls), LinearLayout.LayoutParams(
                dp(190), ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(12); it.marginEnd = dp(12) })
        }
        col.addView(cardsRow)

        root.addView(col, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
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

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(140, 30, 24, 20))
            setPadding(dp(18), dp(22), dp(18), dp(22))
            isClickable = true
            isFocusable = true
        }

        card.addView(TextView(this).apply {
            text = cls.displayName
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
        })

        card.addView(TextView(this).apply {
            text = cls.tagline
            textSize = 12f
            setTextColor(Color.argb(200, 220, 210, 200))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(10) })

        card.addView(TextView(this).apply {
            text = "Select"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent)))
            gravity = Gravity.CENTER
            setPadding(dp(0), dp(10), dp(0), dp(10))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(16) })

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
