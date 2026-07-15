package com.example.dungeon

import com.jme3.anim.AnimComposer
import com.jme3.asset.AssetManager
import com.jme3.math.FastMath
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import com.jme3.scene.Node
import com.jme3.scene.Spatial

// ── Tuning ────────────────────────────────────────────────────────────────────
//
//  Model space height ≈ 30.4 units → scale 0.08 gives ~2.4 world-unit boss.
//  Actual clip names extracted from the .glb JSON chunk:
//  "Walk_New", "Run_New", "Idel_New" (sic), "Fly_New"
//  (Fly_New has root-Y motion so we avoid it for ground states.)

private const val DRAGON_SCALE     = 0.08f

private const val BOSS_MAX_HP      = 8

private const val DETECT_RANGE     = 14f   // boss starts chasing within this distance
private const val ATTACK_RANGE     = 5.0f  // triggers windup when closer than this
private const val BOSS_HIT_DIST    = 3.5f  // boss must be this close to actually deal damage

private const val MOVE_SPEED_WALK  = 2.4f  // world-units/sec (slow chase)
private const val MOVE_SPEED_RUN   = 5.0f  // world-units/sec (fast chase)
private const val LUNGE_SPEED      = 9.0f  // world-units/sec during attack
private const val MAX_LUNGE_DIST   = 4.5f  // boss stops lunging after this many world-units

private const val WINDUP_DURATION  = 0.90f // seconds of telegraph before lunge
private const val STAGGER_DURATION = 0.80f // seconds of stagger when hit
private const val POST_ATK_PAUSE   = 0.70f // stagger duration after a completed lunge

private const val ANIM_IDLE  = "Idel_New"  // sic — matches the baked clip name
private const val ANIM_WALK  = "Walk_New"
private const val ANIM_RUN   = "Run_New"
// Fly_New is intentionally unused: it contains root-Y motion that
// makes the dragon float off the ground.

// ── DragonBoss ────────────────────────────────────────────────────────────────

/**
 * Dragon boss with a four-state ground AI: IDLE → CHASE → WINDUP → ATTACK → STAGGER.
 *
 * Call [update] every frame; it returns `true` on the single frame the lunge
 * connects so the caller can decrement player HP once.
 *
 * Call [takeDamage] when the player's sword swing registers a hit.
 * Call [reset] to bring the boss back to full health at its spawn position.
 */
class DragonBoss(private val assetManager: AssetManager) {

    enum class State { IDLE, CHASE, WINDUP, ATTACK, STAGGER, DEAD }

    // ── Scene ──────────────────────────────────────────────────────────────────

    /** Outer node — attach to rootNode; translate this for world movement. */
    val node = Node("DragonBossNode")

    // ── Stats ──────────────────────────────────────────────────────────────────

    var hp: Int = BOSS_MAX_HP
        private set
    val maxHp: Int = BOSS_MAX_HP

    var state: State = State.IDLE
        private set

    // ── Animation ─────────────────────────────────────────────────────────────

    private val composer: AnimComposer?
    private var currentAnim = ""

    // ── AI state ──────────────────────────────────────────────────────────────

    private var stateTimer = 0f
    private var lungeDir   = Vector3f(0f, 0f, -1f)
    private var lungeMoved = 0f   // world-units covered in the current lunge
    private var hitDone    = false // one hit per lunge

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val model: Spatial = assetManager.loadModel("Models/Dragon/dragon.glb")

        // Scale to dungeon size. Only scale the model — not the node — so
        // world-space distance math (ATTACK_RANGE etc.) works directly.
        model.setLocalScale(DRAGON_SCALE)

        // Blender → glTF: model faces +Z; jME forward is -Z → rotate 180° around Y.
        model.rotate(0f, FastMath.PI, 0f)

        node.attachChild(model)

        composer = findAnimComposer(model)
        playAnim(ANIM_IDLE)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Advance AI one frame.  Returns `true` on the frame the boss attack lands.
     */
    fun update(tpf: Float, playerPos: Vector3f): Boolean {
        if (state == State.DEAD) return false

        stateTimer = (stateTimer - tpf).coerceAtLeast(0f)
        val bossPos  = node.localTranslation
        val toPlayer = Vector3f(playerPos.x - bossPos.x, 0f, playerPos.z - bossPos.z)
        val dist     = toPlayer.length()

        return when (state) {
            State.IDLE    -> tickIdle(dist)
            State.CHASE   -> tickChase(tpf, toPlayer, dist)
            State.WINDUP  -> tickWindup(tpf, toPlayer)
            State.ATTACK  -> tickAttack(tpf, dist)
            State.STAGGER -> tickStagger()
            State.DEAD    -> false
        }
    }

    /** Apply one sword hit.  Interrupts the current action and staggers. */
    fun takeDamage(amount: Int = 1) {
        if (state == State.DEAD) return
        hp = (hp - amount).coerceAtLeast(0)
        if (hp == 0) {
            go(State.DEAD)
        } else {
            go(State.STAGGER)
            stateTimer = STAGGER_DURATION
        }
    }

    /** Reset to full health so the game can be replayed without reloading. */
    fun reset() {
        hp         = maxHp
        stateTimer = 0f
        lungeMoved = 0f
        hitDone    = false
        lungeDir.set(0f, 0f, -1f)
        go(State.IDLE)
    }

    // ── State ticks ────────────────────────────────────────────────────────────

    private fun tickIdle(dist: Float): Boolean {
        if (dist <= DETECT_RANGE) go(State.CHASE)
        return false
    }

    private fun tickChase(tpf: Float, toPlayer: Vector3f, dist: Float): Boolean {
        if (dist <= ATTACK_RANGE) {
            go(State.WINDUP)
            stateTimer = WINDUP_DURATION
            return false
        }

        val fast   = dist > DETECT_RANGE * 0.55f
        val speed  = if (fast) MOVE_SPEED_RUN else MOVE_SPEED_WALK
        val target = if (fast) ANIM_RUN else ANIM_WALK
        if (currentAnim != target) playAnim(target)

        if (dist > 0.1f) {
            node.move(toPlayer.normalize().mult(speed * tpf))
            face(toPlayer)
        }
        return false
    }

    private fun tickWindup(tpf: Float, toPlayer: Vector3f): Boolean {
        face(toPlayer) // keep facing the player during telegraph
        if (stateTimer <= 0f) {
            lungeDir   = if (toPlayer.lengthSquared() > 0.001f) toPlayer.normalize() else lungeDir
            lungeMoved = 0f
            hitDone    = false
            go(State.ATTACK)
        }
        return false
    }

    private fun tickAttack(tpf: Float, dist: Float): Boolean {
        // Lunge forward up to MAX_LUNGE_DIST then stop.
        val maxStep  = (MAX_LUNGE_DIST - lungeMoved).coerceAtLeast(0f)
        val wantStep = LUNGE_SPEED * tpf
        val step     = minOf(wantStep, maxStep)
        if (step > 0f) {
            node.move(lungeDir.mult(step))
            lungeMoved += step
        }

        var hitPlayer = false
        if (!hitDone && dist <= BOSS_HIT_DIST) {
            hitDone   = true
            hitPlayer = true
        }

        if (lungeMoved >= MAX_LUNGE_DIST) {
            go(State.STAGGER)
            stateTimer = POST_ATK_PAUSE
        }
        return hitPlayer
    }

    private fun tickStagger(): Boolean {
        if (stateTimer <= 0f) go(State.CHASE)
        return false
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun go(next: State) {
        state = next
        when (next) {
            State.IDLE    -> playAnim(ANIM_IDLE)
            State.CHASE   -> playAnim(ANIM_WALK)
            State.WINDUP  -> playAnim(ANIM_IDLE)  // stand still during telegraph
            State.ATTACK  -> playAnim(ANIM_RUN)
            State.STAGGER -> playAnim(ANIM_IDLE)
            State.DEAD    -> playAnim(ANIM_IDLE)
        }
    }

    private fun face(dir: Vector3f) {
        val flat = Vector3f(dir.x, 0f, dir.z)
        if (flat.lengthSquared() < 0.0001f) return
        flat.normalizeLocal()
        node.localRotation = Quaternion().lookAt(flat, Vector3f.UNIT_Y)
    }

    private fun playAnim(name: String) {
        val c = composer ?: return
        if (currentAnim == name) return
        try {
            c.setCurrentAction(name)
            currentAnim = name
        } catch (e: Exception) {
            // Animation clip not found in this model build — degrade gracefully.
        }
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    companion object {
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
