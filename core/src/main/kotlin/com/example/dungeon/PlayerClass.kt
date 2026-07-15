package com.example.dungeon

/**
 * Selectable hero classes. Each one has its own combat identity -- range,
 * damage, mobility ability, and defensive ability -- rather than being a
 * pure cosmetic skin over the same numbers.
 *
 * [defenseIsHold]:
 *  - true  → the defensive button is a hold-to-block (Warrior's shield /
 *            Mage's ward): damage is nullified while held, movement slows.
 *  - false → the defensive button is a tap-to-dodge (Archer's roll): a
 *            short burst of speed plus a brief invulnerability window,
 *            fired once per press regardless of how long it's held.
 *
 * [dashIsBlink]:
 *  - true  → the mobility button instantly relocates the hero forward by
 *            [blinkDistance] (Mage's Arcane Blink) instead of sliding.
 *  - false → the mobility button is a timed speed-burst slide, using
 *            [dashSpeed] / [dashDuration] (Warrior's Charge, Archer's Sprint).
 */
enum class PlayerClass(
    val displayName: String,
    val tagline: String,

    // Primary attack
    val attackRange: Float,
    val attackDamage: Int,
    val attackDuration: Float,
    val attackCooldown: Float,
    val ranged: Boolean,
    val projectileSpeed: Float,

    // Mobility ability
    val dashIsBlink: Boolean,
    val blinkDistance: Float,
    val dashSpeed: Float,
    val dashDuration: Float,
    val dashCooldown: Float,

    // Defensive ability
    val defenseIsHold: Boolean,
    val defenseSlowFactor: Float,
    val dodgeSpeedMultiplier: Float,
    val dodgeInvulnDuration: Float,
) {
    WARRIOR(
        displayName = "Warrior",
        tagline = "Steel and grit. Close the gap and swing hard.",
        attackRange = 4.0f, attackDamage = 1, attackDuration = 0.42f, attackCooldown = 0.18f,
        ranged = false, projectileSpeed = 0f,
        dashIsBlink = false, blinkDistance = 0f,
        dashSpeed = 20f, dashDuration = 0.22f, dashCooldown = 0.55f,
        defenseIsHold = true, defenseSlowFactor = 0.45f,
        dodgeSpeedMultiplier = 1f, dodgeInvulnDuration = 0f,
    ),
    MAGE(
        displayName = "Mage",
        tagline = "Arcane fire from a distance. Never let it get close.",
        attackRange = 11f, attackDamage = 2, attackDuration = 0.55f, attackCooldown = 0.55f,
        ranged = true, projectileSpeed = 13f,
        dashIsBlink = true, blinkDistance = 6f,
        dashSpeed = 0f, dashDuration = 0.16f, dashCooldown = 0.85f,
        defenseIsHold = true, defenseSlowFactor = 0.6f,
        dodgeSpeedMultiplier = 1f, dodgeInvulnDuration = 0f,
    ),
    ARCHER(
        displayName = "Archer",
        tagline = "Fast hands, faster feet. Death by a thousand arrows.",
        attackRange = 10f, attackDamage = 1, attackDuration = 0.24f, attackCooldown = 0.10f,
        ranged = true, projectileSpeed = 20f,
        dashIsBlink = false, blinkDistance = 0f,
        dashSpeed = 24f, dashDuration = 0.18f, dashCooldown = 0.42f,
        defenseIsHold = false, defenseSlowFactor = 1f,
        dodgeSpeedMultiplier = 2.6f, dodgeInvulnDuration = 0.35f,
    ),
}
