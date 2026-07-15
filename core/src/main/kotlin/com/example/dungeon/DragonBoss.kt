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

    // Set to true after each init/reset; first update() call fires playAnim so
    // the AnimComposer is guaranteed to be in the scene graph first.
    private var needsAnimInit = true

    private var stateTimer = 0f
    private var lungeDir   = Vector3f(0f, 0f, -1f)
    private var lungeMoved = 0f
    private var hitDone    = false

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val model: Spatial = assetManager.loadModel("Models/Dragon/dragon.glb")

        // Scale only the model child, not the node, so world-space distance math
        // (ATTACK_RANGE, BOSS_HIT_DIST, …) works directly in world units.
        model.setLocalScale(DRAGON_SCALE)

        // Blender → glTF: model face is +Z; jME forward is -Z → rotate 180° Y.
        model.rotate(0f, FastMath.PI, 0f)

        node.attachChild(model)

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
        lungeDir.set(0f, 0f, -1f)
        needsAnimInit = true   // re-trigger animation init after scene reset
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
            hitDone   = true
            hitPlayer = true
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
