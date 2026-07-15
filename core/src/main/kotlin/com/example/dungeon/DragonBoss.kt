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

private const val DRAGON_SCALE    = 0.08f

private const val BOSS_MAX_HP     = 8

// Boss does NOT chase until the player walks within DETECT_RANGE.
// Spawn is at z=-11, player starts at z=5 → initial distance ≈ 16 > 12.
private const val DETECT_RANGE    = 12f
private const val ATTACK_RANGE    = 5.0f   // triggers windup
private const val BOSS_HIT_DIST   = 3.5f   // lunge deals damage within this

private const val MOVE_SPEED_WALK = 2.4f
private const val MOVE_SPEED_RUN  = 4.8f
private const val LUNGE_SPEED     = 8.5f
private const val MAX_LUNGE_DIST  = 4.5f   // hard cap on lunge travel

private const val WINDUP_DURATION  = 0.90f
private const val STAGGER_DURATION = 0.80f
private const val POST_ATK_PAUSE   = 0.65f

// Windup telegraph: the dragon "coils" back and down before it lunges, so a
// player watching the model (not just a timer) gets a readable warning.
private const val WINDUP_COIL_SCALE = 0.90f

// Impact snap: a quick squash-forward punch on the model the instant the
// lunge connects, so a hit reads as an actual impact rather than a silent
// HP-bar tick. This is layered on top of the baked clips since the model
// has no dedicated bite/attack animation.
private const val IMPACT_SNAP_DURATION = 0.22f

private const val ANIM_IDLE = "Idel_New"  // sic — matches baked clip name
private const val ANIM_WALK = "Walk_New"
private const val ANIM_RUN  = "Run_New"
// Fly_New intentionally excluded: root-Y motion floats the dragon off the ground.

// ── DragonBoss ────────────────────────────────────────────────────────────────

class DragonBoss(private val assetManager: AssetManager) {

    enum class State { IDLE, CHASE, WINDUP, ATTACK, STAGGER, DEAD }

    /** Outer node — attach to rootNode; translate this for world movement. */
    val node = Node("DragonBossNode")

    var hp: Int = BOSS_MAX_HP
        private set
    val maxHp: Int = BOSS_MAX_HP

    var state: State = State.IDLE
        private set

    private val composer: AnimComposer?
    private var currentAnim = ""
    private lateinit var modelRef: Spatial

    // Set to true after each init/reset; first update() call fires playAnim so
    // the AnimComposer is guaranteed to be in the scene graph first.
    private var needsAnimInit = true

    private var stateTimer = 0f
    private var lungeDir   = Vector3f(0f, 0f, -1f)
    private var lungeMoved = 0f
    private var hitDone    = false

    // Procedural feedback layered on top of the baked clips (no bite/attack
    // clip exists in the source model — see tuning notes above).
    private var impactSnapTimer = 0f

    /** True for a couple of frames right after the lunge connects — lets the
     *  game layer trigger camera shake / player flinch in the same instant. */
    var justLandedHit = false
        private set

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val model: Spatial = assetManager.loadModel("Models/Dragon/dragon.glb")

        // Scale only the model child, not the node, so world-space distance math
        // (ATTACK_RANGE, BOSS_HIT_DIST, …) works directly in world units.
        model.setLocalScale(DRAGON_SCALE)

        // NOTE: do NOT add an extra 180° Y rotation here. Quaternion.lookAt()
        // (used in face() below) aligns the node's local +Z axis with the
        // target direction, and this model's authored forward is already
        // local +Z. An extra 180° flip here was cancelling that out, which
        // made the dragon always face directly away from whatever it was
        // walking toward (i.e. it appeared to walk backwards, tail-first).
        node.attachChild(model)
        modelRef = model

        composer = findAnimComposer(model)
        // Do NOT call playAnim here — AnimComposer.setCurrentAction() silently
        // no-ops if the spatial hasn't had its first scene-graph update yet.
        // needsAnimInit = true (already set above) defers it to first update().
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun update(tpf: Float, playerPos: Vector3f): Boolean {
        // Defer first animation start until after the node is in the scene.
        if (needsAnimInit) {
            needsAnimInit = false
            playAnim(ANIM_IDLE)
        }

        justLandedHit = false
        impactSnapTimer = (impactSnapTimer - tpf).coerceAtLeast(0f)
        applyProceduralPose()

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

    fun reset() {
        hp         = maxHp
        stateTimer = 0f
        lungeMoved = 0f
        hitDone    = false
        impactSnapTimer = 0f
        justLandedHit = false
        lungeDir.set(0f, 0f, -1f)
        needsAnimInit = true   // re-trigger animation init after scene reset
        modelRef.setLocalScale(DRAGON_SCALE)
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
        face(toPlayer)
        if (stateTimer <= 0f) {
            lungeDir   = if (toPlayer.lengthSquared() > 0.001f) toPlayer.normalize() else lungeDir
            lungeMoved = 0f
            hitDone    = false
            go(State.ATTACK)
        }
        return false
    }

    /** Coil-back scale during windup: shrinks toward the ground as the timer
     *  runs out, like a predator loading a spring before it lunges. */
    private fun windupCoilProgress(): Float =
        if (state == State.WINDUP) 1f - (stateTimer / WINDUP_DURATION) else 0f

    private fun tickAttack(tpf: Float, dist: Float): Boolean {
        // Advance the lunge only while budget remains AND we haven't already hit.
        if (!hitDone && lungeMoved < MAX_LUNGE_DIST) {
            val remaining = MAX_LUNGE_DIST - lungeMoved
            val step      = minOf(LUNGE_SPEED * tpf, remaining)
            node.move(lungeDir.mult(step))
            lungeMoved += step
        }

        var hitPlayer = false
        if (!hitDone && dist <= BOSS_HIT_DIST) {
            hitDone       = true
            hitPlayer     = true
            justLandedHit = true
            impactSnapTimer = IMPACT_SNAP_DURATION
        }

        // Transition to stagger when:
        //  a) lunge budget exhausted, OR
        //  b) hit connected (stop sliding through the player)
        if (lungeMoved >= MAX_LUNGE_DIST || hitDone) {
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
            State.WINDUP  -> playAnim(ANIM_IDLE)
            State.ATTACK  -> playAnim(ANIM_RUN)
            State.STAGGER -> playAnim(ANIM_IDLE)
            State.DEAD    -> playAnim(ANIM_IDLE)
        }
    }

    /** Blends the windup coil and impact-snap punch into the model's scale
     *  every frame. Purely cosmetic — never touches world-space translation
     *  so distance-based combat math (ATTACK_RANGE, BOSS_HIT_DIST) is unaffected. */
    private fun applyProceduralPose() {
        val coil = windupCoilProgress()
        val coilScale = FastMath.interpolateLinear(coil, 1f, WINDUP_COIL_SCALE)

        val snapT = if (IMPACT_SNAP_DURATION > 0f) impactSnapTimer / IMPACT_SNAP_DURATION else 0f
        // Punch forward (stretch Z) and squash (shrink X/Y) for one quick beat.
        val punch = FastMath.sin(snapT * FastMath.PI) * snapT.coerceAtLeast(0f)
        val stretch = 1f + punch * 0.22f
        val squash  = 1f - punch * 0.12f

        modelRef.setLocalScale(
            DRAGON_SCALE * coilScale * squash,
            DRAGON_SCALE * coilScale * squash,
            DRAGON_SCALE * coilScale * stretch,
        )
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
        } catch (_: Exception) {
            // Clip not found in this model build — degrade gracefully.
        }
    }

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
