package com.example.dungeon

import com.jme3.anim.AnimComposer
import com.jme3.asset.AssetManager
import com.jme3.material.Material
import com.jme3.math.ColorRGBA
import com.jme3.math.FastMath
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import com.jme3.scene.Geometry
import com.jme3.scene.Node
import com.jme3.scene.Spatial
import com.jme3.scene.shape.Box
import com.jme3.scene.shape.Sphere

// ── Tuning ────────────────────────────────────────────────────────────────────

private const val DRAGON_SCALE    = 0.08f
private const val BOSS_MAX_HP     = 8
private const val DETECT_RANGE    = 12f
private const val ATTACK_RANGE    = 5.0f
private const val BOSS_HIT_DIST   = 3.5f

private const val MOVE_SPEED_WALK = 2.4f
private const val MOVE_SPEED_RUN  = 4.8f
private const val LUNGE_SPEED     = 8.5f
private const val MAX_LUNGE_DIST  = 4.5f

private const val WINDUP_DURATION  = 0.90f
private const val STAGGER_DURATION = 0.80f
private const val POST_ATK_PAUSE   = 0.65f

private const val WINDUP_COIL_SCALE   = 0.90f
private const val IMPACT_SNAP_DURATION = 0.22f

private const val ANIM_IDLE = "Idel_New"
private const val ANIM_WALK = "Walk_New"
private const val ANIM_RUN  = "Run_New"

// ── DragonBoss ────────────────────────────────────────────────────────────────

class DragonBoss private constructor(
    private val assetManager: AssetManager,
    isFallback: Boolean,
) {

    enum class State { IDLE, CHASE, WINDUP, ATTACK, STAGGER, DEAD }

    val node = Node("DragonBossNode")

    var hp: Int = BOSS_MAX_HP
        private set
    val maxHp: Int = BOSS_MAX_HP

    var state: State = State.IDLE
        private set

    private val composer: AnimComposer?
    private var currentAnim = ""
    private lateinit var modelRef: Spatial

    private var needsAnimInit = true
    private var stateTimer    = 0f
    private var lungeDir      = Vector3f(0f, 0f, -1f)
    private var lungeMoved    = 0f
    private var hitDone       = false

    private var impactSnapTimer = 0f

    var justLandedHit = false
        private set

    // Primary constructor: load the real GLB model.
    constructor(assetManager: AssetManager) : this(assetManager, false) {}

    init {
        if (isFallback) {
            // Procedural placeholder — a red glowing sphere — used when the
            // dragon.glb fails to load (missing asset, corrupt file, etc.).
            val mat = Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md").apply {
                setColor("Color", ColorRGBA(0.8f, 0.1f, 0.05f, 1f))
            }
            val body = Geometry("BossBody", Sphere(16, 16, 1.5f)).apply { material = mat }
            val eye1 = Geometry("Eye1", Sphere(8, 8, 0.22f)).apply {
                material = Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md").apply {
                    setColor("Color", ColorRGBA(1f, 0.8f, 0f, 1f))
                }
                setLocalTranslation(0.6f, 0.4f, 1.2f)
            }
            val eye2 = Geometry("Eye2", Sphere(8, 8, 0.22f)).apply {
                material = Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md").apply {
                    setColor("Color", ColorRGBA(1f, 0.8f, 0f, 1f))
                }
                setLocalTranslation(-0.6f, 0.4f, 1.2f)
            }
            // Horns
            listOf(-0.5f to 0.5f, 0.5f to 0.5f).forEach { (xOff, _) ->
                val horn = Geometry("Horn", Box(0.1f, 0.4f, 0.1f)).apply {
                    material = Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md").apply {
                        setColor("Color", ColorRGBA(0.3f, 0.05f, 0.02f, 1f))
                    }
                    setLocalTranslation(xOff, 1.6f, 0f)
                }
                node.attachChild(horn)
            }
            node.attachChild(body)
            node.attachChild(eye1)
            node.attachChild(eye2)
            modelRef = body
            composer = null
        } else {
            // Real GLB model.
            val model: Spatial = assetManager.loadModel("Models/Dragon/dragon.glb")
            model.setLocalScale(DRAGON_SCALE)
            node.attachChild(model)
            modelRef = model
            composer = findAnimComposer(model)
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun update(tpf: Float, playerPos: Vector3f): Boolean {
        if (needsAnimInit) {
            needsAnimInit = false
            playAnim(ANIM_IDLE)
        }

        justLandedHit   = false
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
        if (hp == 0) go(State.DEAD)
        else { go(State.STAGGER); stateTimer = STAGGER_DURATION }
    }

    fun reset() {
        hp              = maxHp
        stateTimer      = 0f
        lungeMoved      = 0f
        hitDone         = false
        impactSnapTimer = 0f
        justLandedHit   = false
        lungeDir.set(0f, 0f, -1f)
        needsAnimInit   = true
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
        val fast  = dist > DETECT_RANGE * 0.55f
        val speed = if (fast) MOVE_SPEED_RUN else MOVE_SPEED_WALK
        val anim  = if (fast) ANIM_RUN else ANIM_WALK
        if (currentAnim != anim) playAnim(anim)

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
            // Give the lunge enough time to travel MAX_LUNGE_DIST + a grace buffer.
            stateTimer = MAX_LUNGE_DIST / LUNGE_SPEED + 0.20f
        }
        return false
    }

    private fun tickAttack(tpf: Float, dist: Float): Boolean {
        val step = lungeDir.mult(LUNGE_SPEED * tpf)
        node.move(step)
        lungeMoved += step.length()

        if (!hitDone && dist <= BOSS_HIT_DIST) {
            hitDone       = true
            justLandedHit = true
            impactSnapTimer = IMPACT_SNAP_DURATION
        }

        if (lungeMoved >= MAX_LUNGE_DIST || stateTimer <= 0f) {
            go(State.CHASE)
            stateTimer = POST_ATK_PAUSE
        }
        return justLandedHit
    }

    private fun tickStagger(): Boolean {
        if (stateTimer <= 0f) go(State.CHASE)
        return false
    }

    // ── Procedural pose ────────────────────────────────────────────────────────

    private fun windupCoilProgress(): Float =
        if (state == State.WINDUP) 1f - (stateTimer / WINDUP_DURATION) else 0f

    private fun applyProceduralPose() {
        val coil      = windupCoilProgress()
        val coilScale = FastMath.interpolateLinear(coil, 1f, WINDUP_COIL_SCALE)

        val snapT   = if (IMPACT_SNAP_DURATION > 0f) impactSnapTimer / IMPACT_SNAP_DURATION else 0f
        val punch   = FastMath.sin(snapT * FastMath.PI) * snapT.coerceAtLeast(0f)
        val stretch = 1f + punch * 0.22f
        val squash  = 1f - punch * 0.12f

        modelRef.setLocalScale(
            DRAGON_SCALE * coilScale * squash,
            DRAGON_SCALE * coilScale * squash,
            DRAGON_SCALE * coilScale * stretch,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun go(s: State) {
        state = s
        when (s) {
            State.IDLE    -> playAnim(ANIM_IDLE)
            State.CHASE   -> playAnim(ANIM_WALK)
            State.WINDUP  -> playAnim(ANIM_IDLE)
            State.ATTACK  -> playAnim(ANIM_RUN)
            State.STAGGER -> playAnim(ANIM_IDLE)
            State.DEAD    -> { /* freeze */ }
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
            // Clip not found — degrade gracefully.
        }
    }

    companion object {
        /** Returns a DragonBoss backed by a procedural placeholder (no GLB needed). */
        fun createFallback(assetManager: AssetManager): DragonBoss =
            DragonBoss(assetManager, isFallback = true)

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
