package com.example.dungeon

import com.jme3.anim.AnimComposer
import com.jme3.asset.AssetManager
import com.jme3.math.FastMath
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import com.jme3.scene.Node
import com.jme3.scene.Spatial

// ── Tuning knobs ──────────────────────────────────────────────────────────────

private const val DRAGON_SCALE     = 0.01f   // adjust if the model appears too large/small
private const val BOSS_MAX_HP      = 8

private const val DETECT_RANGE     = 14f     // world units; boss starts chasing within this
private const val ATTACK_RANGE     = 3.2f    // world units; triggers windup when this close

private const val MOVE_SPEED_WALK  = 2.4f    // world-units/sec in the walk phase
private const val MOVE_SPEED_RUN   = 4.8f    // world-units/sec in the run phase
private const val LUNGE_SPEED      = 10f     // world-units/sec during the attack lunge

private const val WINDUP_DURATION  = 0.85f   // seconds of telegraph before attack
private const val ATTACK_DURATION  = 0.40f   // seconds of lunge
private const val STAGGER_DURATION = 0.80f   // seconds of stagger after being hit

// ── DragonBoss ────────────────────────────────────────────────────────────────

/**
 * Self-contained dragon boss: loads the glTF model, owns its scene node, and
 * drives a simple four-state AI that chases and attacks the player.
 *
 * Call [update] every frame while the game is playing; it returns `true` on the
 * single frame in which the boss's attack should deal damage to the player.
 *
 * Call [takeDamage] when the player's sword swing connects.
 */
class DragonBoss(assetManager: AssetManager) {

    // ── State ──────────────────────────────────────────────────────────────────

    enum class State { IDLE, CHASE, WINDUP, ATTACK, STAGGER, DEAD }

    var hp: Int = BOSS_MAX_HP
        private set
    val maxHp: Int = BOSS_MAX_HP
    var state: State = State.IDLE
        private set

    // ── Scene ──────────────────────────────────────────────────────────────────

    /**
     * Outer node that the caller (DungeonGame) attaches to rootNode and moves.
     * Keep origin at the dragon's feet so y=0 keeps it on the ground.
     */
    val node = Node("DragonBossNode")

    private val composer: AnimComposer?
    private var currentAnimName = ""
    private var stateTimer = 0f

    // The lunge direction is captured at the moment the attack begins so the
    // dragon commits to it even if the player side-steps.
    private var lungeDirection = Vector3f(0f, 0f, -1f)

    // Whether we have already delivered hit-damage in the current ATTACK phase.
    private var hitDelivered = false

    init {
        // Load the glTF model.  assetManager resolves paths relative to the
        // assets folder, so "Models/Dragon/dragon.glb" matches
        // app/src/main/assets/Models/Dragon/dragon.glb.
        val model: Spatial = assetManager.loadModel("Models/Dragon/dragon.glb")

        // Scale the model down to dungeon scale.  The outer node stays at 1:1
        // so that world-space distance checks (ATTACK_RANGE etc.) work directly.
        model.setLocalScale(DRAGON_SCALE)

        // Blender → glTF exports typically face +Z; jME's "forward" is -Z.
        // Rotate 180° around Y so the dragon faces in the direction it moves.
        model.rotate(0f, FastMath.PI, 0f)

        node.attachChild(model)

        // Find the AnimComposer wherever it is in the model hierarchy and play
        // the idle clip immediately so the dragon is animated from the first frame.
        composer = findAnimComposer(model)
        playAnim("Idle")
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Advance the AI for one frame.
     *
     * @param tpf       Time-per-frame in seconds.
     * @param playerPos Player's world-space position.
     * @return `true` on the one frame in which the boss attack connects
     *         (caller should decrement player HP once).
     */
    fun update(tpf: Float, playerPos: Vector3f): Boolean {
        if (state == State.DEAD) return false

        stateTimer = (stateTimer - tpf).coerceAtLeast(0f)
        val bossPos  = node.localTranslation
        val toPlayer = Vector3f(playerPos.x - bossPos.x, 0f, playerPos.z - bossPos.z)
        val dist     = toPlayer.length()

        return when (state) {
            State.IDLE     -> tickIdle(dist)
            State.CHASE    -> tickChase(tpf, toPlayer, dist)
            State.WINDUP   -> tickWindup(toPlayer, dist)
            State.ATTACK   -> tickAttack(tpf, playerPos, dist)
            State.STAGGER  -> tickStagger()
            State.DEAD     -> false
        }
    }

    /**
     * Apply one hit of sword damage to the boss.
     * Interrupts any non-dead state and triggers a brief stagger.
     */
    fun takeDamage(amount: Int = 1) {
        if (state == State.DEAD) return
        hp = (hp - amount).coerceAtLeast(0)
        if (hp == 0) {
            transitionTo(State.DEAD)
        } else {
            transitionTo(State.STAGGER)
            stateTimer = STAGGER_DURATION
        }
    }

    // ── AI ticks ───────────────────────────────────────────────────────────────

    private fun tickIdle(dist: Float): Boolean {
        if (dist <= DETECT_RANGE) transitionTo(State.CHASE)
        return false
    }

    private fun tickChase(tpf: Float, toPlayer: Vector3f, dist: Float): Boolean {
        if (dist <= ATTACK_RANGE) {
            transitionTo(State.WINDUP)
            stateTimer = WINDUP_DURATION
            return false
        }

        // Switch between walk (close) and run (far) animation.
        val targetAnim = if (dist > DETECT_RANGE * 0.6f) "Run" else "Walk"
        if (currentAnimName != targetAnim) playAnim(targetAnim)

        val speed = if (dist > DETECT_RANGE * 0.6f) MOVE_SPEED_RUN else MOVE_SPEED_WALK
        if (dist > 0.1f) {
            val step = toPlayer.normalize().mult(speed * tpf)
            node.move(step)
            faceDirection(toPlayer)
        }
        return false
    }

    private fun tickWindup(toPlayer: Vector3f, dist: Float): Boolean {
        // Stand still facing the player; launch when the timer expires.
        faceDirection(toPlayer)
        if (stateTimer <= 0f) {
            // Capture lunge direction at commit time.
            lungeDirection = if (dist > 0.01f) toPlayer.normalize() else lungeDirection
            hitDelivered = false
            transitionTo(State.ATTACK)
            stateTimer = ATTACK_DURATION
        }
        return false
    }

    private fun tickAttack(tpf: Float, playerPos: Vector3f, dist: Float): Boolean {
        // Lunge forward at high speed for the duration.
        val step = lungeDirection.mult(LUNGE_SPEED * tpf)
        node.move(step)

        var hitPlayer = false
        // Deliver damage once, on whichever frame the boss is close enough.
        if (!hitDelivered && dist <= ATTACK_RANGE * 1.3f) {
            hitDelivered = true
            hitPlayer    = true
        }

        if (stateTimer <= 0f) {
            transitionTo(State.STAGGER)
            stateTimer = STAGGER_DURATION * 0.5f   // shorter stagger after a full attack
        }
        return hitPlayer
    }

    private fun tickStagger(): Boolean {
        if (stateTimer <= 0f) transitionTo(State.CHASE)
        return false
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun transitionTo(next: State) {
        state = next
        when (next) {
            State.IDLE    -> playAnim("Idle")
            State.CHASE   -> playAnim("Walk")
            State.WINDUP  -> playAnim("Fly")   // "Fly" reads well as a threat pose
            State.ATTACK  -> playAnim("Run")   // fast lunge uses run anim
            State.STAGGER -> playAnim("Idle")
            State.DEAD    -> playAnim("Idle")
        }
    }

    private fun faceDirection(dir: Vector3f) {
        if (dir.lengthSquared() < 0.0001f) return
        val flat = Vector3f(dir.x, 0f, dir.z).normalizeLocal()
        if (flat.lengthSquared() < 0.0001f) return
        node.localRotation = Quaternion().lookAt(flat, Vector3f.UNIT_Y)
    }

    private fun playAnim(name: String) {
        val c = composer ?: return
        if (currentAnimName == name) return
        try {
            c.setCurrentAction(name)
            currentAnimName = name
        } catch (e: Exception) {
            // Clip not present in this model version — ignore gracefully.
        }
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    companion object {
        /** Recursive search for the first AnimComposer anywhere in the model tree. */
        fun findAnimComposer(spatial: Spatial): AnimComposer? {
            spatial.getControl(AnimComposer::class.java)?.let { return it }
            if (spatial is Node) {
                for (child in spatial.children) {
                    findAnimComposer(child)?.let { return it }
                }
            }
            return null
        }
    }
}
