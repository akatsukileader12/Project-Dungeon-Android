package com.example.dungeon

/**
 * Shared input state, written by whatever the platform module uses to
 * capture input (Android's on-screen joystick/buttons, desktop's mouse
 * click-to-move) and read once per frame by [DungeonGame].
 *
 * All fields are `@Volatile` because Android's touch callbacks run on the
 * UI thread while [DungeonGame.simpleUpdate] runs on the GL thread -- these
 * are simple independent primitives (no cross-field invariants), so plain
 * volatile fields are enough; no locking needed.
 */
class PlayerInput {
    /** Joystick horizontal axis, -1 (left) .. 1 (right). Zero when not touched. */
    @Volatile var moveX = 0f

    /** Joystick vertical axis, -1 (pulled toward the player) .. 1 (pushed away). */
    @Volatile var moveY = 0f

    /**
     * Set true for one frame by the UI when the primary-attack button is tapped;
     * [DungeonGame] consumes and clears it. Meaning depends on the active
     * [PlayerClass]: sword swing (Warrior), fireball (Mage), or arrow (Archer).
     */
    @Volatile var attackQueued = false

    /**
     * Set true for one frame by the UI when the mobility button is tapped;
     * [DungeonGame] consumes and clears it. Meaning depends on the active
     * [PlayerClass]: dash (Warrior/Archer) or blink (Mage).
     */
    @Volatile var abilityQueued = false

    /**
     * True for as long as the defensive button is held down. Only consumed as
     * a continuous hold by hold-to-block classes (Warrior/Mage); for
     * tap-to-dodge classes (Archer) [DungeonGame] instead reacts to the
     * rising edge of this flag as a one-shot dodge trigger.
     */
    @Volatile var defenseHeld = false
}
