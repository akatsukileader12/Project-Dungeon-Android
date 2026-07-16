package com.example.dungeon

import com.jme3.asset.AssetManager
import com.jme3.material.Material
import com.jme3.math.ColorRGBA
import com.jme3.math.FastMath
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import com.jme3.scene.Geometry
import com.jme3.scene.Node
import com.jme3.scene.shape.Box

/**
 * Blocky Lego-style procedural hero.
 *
 * ALL body parts are Box geometries — no Spheres or Cylinders — giving
 * the game a consistent voxel / Lego aesthetic without any external assets.
 *
 * Animation nodes (same hierarchy as before):
 *   root
 *    ├─ hipL / hipR         (leg swing)
 *    └─ spineNode           (upper-body lean / twist)
 *         ├─ shoulderL / shoulderR   (arm poses)
 *         └─ neckNode       (head bob / tilt)
 *
 * New in this revision:
 *  - Square block head with two recessed "eye" boxes on the face.
 *  - Improved Warrior normal attack: 4-phase power slash (wind-up → rip →
 *    follow-through → recovery) reads unmistakably as a sword swing.
 *  - Improved Archer normal attack: draw → aim-hold → snap release.
 *  - Combo animations: per-class cinematic multi-hit moves.
 *      Warrior  — 3-hit chain: slash R→L, slash L→R, overhead spin slam.
 *      Mage     — chain lightning: arms spread → slam → arcane blast.
 *      Archer   — 5-arrow rapid volley: crouch aim → rapid triple snap.
 *  - update() accepts optional [combo01] (0..1) independently of [attack01].
 */
class Humanoid(assetManager: AssetManager, private val playerClass: PlayerClass) {

    // ── Proportions ──────────────────────────────────────────────────────────
    private val legLength   = 0.46f
    private val hipY        = legLength
    private val torsoH      = 0.54f          // slightly taller torso for blocky look
    private val armLen      = 0.44f
    private val headSide    = 0.23f          // cube head half-extent

    val root = Node("PlayerModel")

    // Animation pivots.
    private val spineNode   = Node("Spine").apply  { setLocalTranslation(0f, hipY + torsoH * 0.38f, 0f) }
    private val neckNode    = Node("Neck").apply   { setLocalTranslation(0f, torsoH * 0.62f, 0f) }
    private val shoulderL   = Node("ShoulderL").apply { setLocalTranslation(-0.28f, torsoH * 0.58f, 0f) }
    private val shoulderR   = Node("ShoulderR").apply { setLocalTranslation( 0.28f, torsoH * 0.58f, 0f) }
    private val hipL        = Node("HipL").apply  { setLocalTranslation(-0.14f, hipY, 0f) }
    private val hipR        = Node("HipR").apply  { setLocalTranslation( 0.14f, hipY, 0f) }
    private val offhandNode = Node("OffhandPivot")

    private lateinit var offhandMat:       Material
    private lateinit var idleOffhandColor: ColorRGBA
    private lateinit var blockOffhandColor: ColorRGBA
    private lateinit var torsoMat:         Material
    private lateinit var idleTorsoColor:   ColorRGBA
    private val hurtColor = ColorRGBA(0.96f, 0.14f, 0.14f, 1f)

    init {
        fun lit(diffuse: ColorRGBA, ambient: ColorRGBA, shin: Float = 14f) =
            Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse",  diffuse)
                setColor("Ambient",  ambient)
                setColor("Specular", ColorRGBA(0.55f, 0.55f, 0.55f, 1f))
                setFloat("Shininess", shin)
            }

        fun unlit(color: ColorRGBA) =
            Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md").apply {
                setColor("Color", color)
            }

        val skinMat = lit(ColorRGBA(0.90f, 0.70f, 0.56f, 1f), ColorRGBA(0.26f, 0.19f, 0.14f, 1f))

        data class Pal(
            val torso: ColorRGBA, val torsoA: ColorRGBA,
            val pants: ColorRGBA, val pantsA: ColorRGBA,
            val accent: ColorRGBA, val accentA: ColorRGBA,
        )
        val pal = when (playerClass) {
            PlayerClass.WARRIOR -> Pal(
                ColorRGBA(0.18f, 0.44f, 0.26f, 1f), ColorRGBA(0.05f, 0.12f, 0.07f, 1f),
                ColorRGBA(0.30f, 0.24f, 0.18f, 1f), ColorRGBA(0.08f, 0.07f, 0.05f, 1f),
                ColorRGBA(0.75f, 0.76f, 0.80f, 1f), ColorRGBA(0.20f, 0.20f, 0.22f, 1f),
            )
            PlayerClass.MAGE -> Pal(
                ColorRGBA(0.30f, 0.16f, 0.50f, 1f), ColorRGBA(0.09f, 0.05f, 0.16f, 1f),
                ColorRGBA(0.20f, 0.12f, 0.30f, 1f), ColorRGBA(0.06f, 0.04f, 0.10f, 1f),
                ColorRGBA(0.95f, 0.55f, 0.15f, 1f), ColorRGBA(0.35f, 0.18f, 0.02f, 1f),
            )
            PlayerClass.ARCHER -> Pal(
                ColorRGBA(0.30f, 0.36f, 0.20f, 1f), ColorRGBA(0.08f, 0.11f, 0.06f, 1f),
                ColorRGBA(0.26f, 0.20f, 0.14f, 1f), ColorRGBA(0.07f, 0.06f, 0.04f, 1f),
                ColorRGBA(0.44f, 0.30f, 0.16f, 1f), ColorRGBA(0.13f, 0.08f, 0.04f, 1f),
            )
        }

        idleTorsoColor = pal.torso.clone()
        torsoMat       = lit(pal.torso,  pal.torsoA)
        val pantsMat   = lit(pal.pants,  pal.pantsA)
        val bootMat    = lit(ColorRGBA(0.16f, 0.11f, 0.08f, 1f), ColorRGBA(0.04f, 0.03f, 0.02f, 1f))
        val accentMat  = lit(pal.accent, pal.accentA, if (playerClass == PlayerClass.WARRIOR) 64f else 24f)
        val hiltMat    = lit(ColorRGBA(0.30f, 0.20f, 0.10f, 1f), ColorRGBA(0.08f, 0.05f, 0.03f, 1f))

        idleOffhandColor = when (playerClass) {
            PlayerClass.WARRIOR -> ColorRGBA(0.32f, 0.24f, 0.14f, 1f)
            PlayerClass.MAGE    -> ColorRGBA(0.35f, 0.18f, 0.55f, 1f)
            PlayerClass.ARCHER  -> ColorRGBA(0.30f, 0.22f, 0.12f, 1f)
        }
        blockOffhandColor = when (playerClass) {
            PlayerClass.ARCHER -> idleOffhandColor.clone()
            else -> ColorRGBA(0.25f, 0.55f, 0.85f, 1f)
        }
        offhandMat = lit(idleOffhandColor, ColorRGBA(0.08f, 0.10f, 0.12f, 1f))

        // ── Geometry ─────────────────────────────────────────────────────────

        // Torso — wide flat block (the iconic Lego brick body)
        val torso = Geometry("Torso", Box(0.20f, torsoH / 2f, 0.13f)).apply {
            material = torsoMat
        }

        // ── Head (square Lego head) ─────────────────────────────────────────
        val head = Geometry("Head", Box(headSide, headSide, headSide)).apply {
            material = skinMat
            setLocalTranslation(0f, headSide + 0.05f, 0f)
        }
        // Eyes — two dark recessed cubes on the front face.
        val eyeColor = when (playerClass) {
            PlayerClass.WARRIOR -> ColorRGBA(0.08f, 0.08f, 0.08f, 1f)
            PlayerClass.MAGE    -> ColorRGBA(0.45f, 0.10f, 0.80f, 1f)   // purple mage eyes
            PlayerClass.ARCHER  -> ColorRGBA(0.08f, 0.08f, 0.08f, 1f)
        }
        val eyeMat = unlit(eyeColor)
        val eyeL = Geometry("EyeL", Box(0.04f, 0.04f, 0.005f)).apply {
            material = eyeMat
            setLocalTranslation(-0.09f, headSide + 0.08f, headSide)
        }
        val eyeR = Geometry("EyeR", Box(0.04f, 0.04f, 0.005f)).apply {
            material = eyeMat
            setLocalTranslation( 0.09f, headSide + 0.08f, headSide)
        }

        // Legs — thick blocks
        blockLimb("LegL", 0.115f, legLength / 2f, 0.115f, pantsMat, hipL, legLength)
        blockLimb("LegR", 0.115f, legLength / 2f, 0.115f, pantsMat, hipR, legLength)

        // Block boots
        listOf(hipL to "BootL", hipR to "BootR").forEach { (hip, name) ->
            Geometry(name, Box(0.135f, 0.07f, 0.16f)).apply {
                material = bootMat
                setLocalTranslation(0f, -legLength + 0.07f, 0.02f)
            }.also { hip.attachChild(it) }
        }

        // Arms — blocky
        blockLimb("ArmL", 0.105f, armLen / 2f, 0.105f, torsoMat, shoulderL, armLen)
        blockLimb("ArmR", 0.105f, armLen / 2f, 0.105f, torsoMat, shoulderR, armLen)

        // Hand node — weapon attaches here.
        val hand = Node("Hand").apply { setLocalTranslation(0f, -armLen, 0f) }
        shoulderR.attachChild(hand)

        when (playerClass) {
            PlayerClass.WARRIOR -> {
                // Sword: wide flat blade + thick guard + pommel nub.
                Geometry("Blade", Box(0.030f, 0.36f, 0.050f)).apply {
                    material = accentMat; setLocalTranslation(0f, -0.32f, 0f)
                }.also { hand.attachChild(it) }
                Geometry("Guard", Box(0.10f, 0.035f, 0.050f)).apply {
                    material = hiltMat; setLocalTranslation(0f, 0.04f, 0f)
                }.also { hand.attachChild(it) }
                Geometry("Pommel", Box(0.04f, 0.04f, 0.04f)).apply {
                    material = hiltMat; setLocalTranslation(0f, 0.12f, 0f)
                }.also { hand.attachChild(it) }
            }
            PlayerClass.MAGE -> {
                // Staff: box shaft + glowing cube orb + ring frame.
                Geometry("Shaft", Box(0.028f, 0.38f, 0.028f)).apply {
                    material = hiltMat; setLocalTranslation(0f, 0.02f, 0f)
                }.also { hand.attachChild(it) }
                Geometry("Orb", Box(0.09f, 0.09f, 0.09f)).apply {
                    material = accentMat; setLocalTranslation(0f, 0.48f, 0f)
                }.also { hand.attachChild(it) }
                // Corner spikes on orb for dramatic look.
                listOf(-0.09f to 0.09f, 0.09f to 0.09f, -0.09f to -0.09f, 0.09f to -0.09f)
                    .forEach { (xz1, xz2) ->
                        Geometry("Spike", Box(0.020f, 0.020f, 0.020f)).apply {
                            material = accentMat
                            setLocalTranslation(xz1, 0.48f, xz2)
                        }.also { hand.attachChild(it) }
                    }
            }
            PlayerClass.ARCHER -> {
                // Bow: centre grip + upper/lower angled limbs.
                Geometry("Grip", Box(0.028f, 0.10f, 0.028f)).apply {
                    material = hiltMat; setLocalTranslation(0f, -0.02f, 0f)
                }.also { hand.attachChild(it) }
                Geometry("LimbTop", Box(0.025f, 0.24f, 0.025f)).apply {
                    material = accentMat
                    rotate(0f, 0f, FastMath.QUARTER_PI * 0.55f)
                    setLocalTranslation(0.05f, 0.22f, 0f)
                }.also { hand.attachChild(it) }
                Geometry("LimbBot", Box(0.025f, 0.24f, 0.025f)).apply {
                    material = accentMat
                    rotate(0f, 0f, -FastMath.QUARTER_PI * 0.55f)
                    setLocalTranslation(0.05f, -0.26f, 0f)
                }.also { hand.attachChild(it) }
            }
        }

        // Off-hand item.
        offhandNode.setLocalTranslation(0f, -armLen * 0.65f, 0f)
        when (playerClass) {
            PlayerClass.WARRIOR -> {
                Geometry("Shield", Box(0.06f, 0.24f, 0.18f)).apply {
                    material = offhandMat; setLocalTranslation(-0.10f, 0f, 0f)
                }.also { offhandNode.attachChild(it) }
                // Shield boss stud (Lego style).
                Geometry("ShieldBoss", Box(0.04f, 0.04f, 0.04f)).apply {
                    material = accentMat; setLocalTranslation(-0.16f, 0f, 0f)
                }.also { offhandNode.attachChild(it) }
            }
            PlayerClass.MAGE -> {
                // Offhand orb (focus stone).
                Geometry("Focus", Box(0.10f, 0.10f, 0.10f)).apply {
                    material = offhandMat; setLocalTranslation(-0.05f, 0f, 0f)
                }.also { offhandNode.attachChild(it) }
            }
            PlayerClass.ARCHER -> {
                // Quiver: box + arrow stubs.
                Geometry("Quiver", Box(0.065f, 0.18f, 0.065f)).apply {
                    material = accentMat
                    setLocalTranslation(0f, hipY + 0.18f, -0.17f)
                }.also { root.attachChild(it) }
                repeat(3) { i ->
                    Geometry("QuiverArrow$i", Box(0.010f, 0.14f, 0.010f)).apply {
                        material = lit(ColorRGBA(0.60f, 0.45f, 0.20f, 1f),
                                       ColorRGBA(0.15f, 0.10f, 0.04f, 1f))
                        setLocalTranslation(
                            (i - 1) * 0.025f, hipY + 0.38f, -0.17f)
                    }.also { root.attachChild(it) }
                }
            }
        }
        shoulderL.attachChild(offhandNode)

        // ── Scene graph ───────────────────────────────────────────────────────
        root.attachChild(hipL)
        root.attachChild(hipR)

        spineNode.attachChild(torso)
        spineNode.attachChild(shoulderL)
        spineNode.attachChild(shoulderR)
        spineNode.attachChild(neckNode)

        neckNode.attachChild(head)
        neckNode.attachChild(eyeL)
        neckNode.attachChild(eyeR)

        root.attachChild(spineNode)
    }

    // ── Geometry helper ───────────────────────────────────────────────────────

    private fun blockLimb(name: String, hw: Float, hh: Float, hd: Float,
                          mat: Material, pivot: Node, length: Float): Geometry {
        val g = Geometry(name, Box(hw, hh, hd)).apply {
            material = mat
            setLocalTranslation(0f, -length / 2f, 0f)
        }
        pivot.attachChild(g)
        return g
    }

    // ── Animation state ───────────────────────────────────────────────────────

    private var walkClock        = 0f
    private var idleClock        = 0f
    private var hurtFlash        = 0f
    private var blockAmount      = 0f
    private var currentSpineLean = 0f
    private var currentHipSway   = 0f

    /**
     * Drive all procedural animation for one frame.
     *
     * @param attack01  0..1 progress through a NORMAL attack, or null.
     * @param dash01    0..1 progress through dash/blink, or null.
     * @param combo01   0..1 progress through a COMBO attack, or null.
     *                  When non-null, overrides [attack01].
     */
    fun update(
        tpf:      Float,
        moving:   Boolean,
        speedScale: Float,
        attack01: Float?,
        dash01:   Float?,
        blocking: Boolean,
        hurtPulse: Boolean = false,
        combo01:  Float? = null,
    ) {
        // Hurt flash.
        if (hurtPulse) hurtFlash = 1f
        hurtFlash = (hurtFlash - tpf * 2.6f).coerceAtLeast(0f)

        // Walk clock.
        if (moving) { walkClock += tpf * (6.0f + 5.0f * speedScale.coerceAtMost(1f)); idleClock = 0f }
        else        { walkClock = walkClock * (1f - tpf * 4f); idleClock += tpf }

        val strideAmp = if (moving) (0.50f + 0.18f * speedScale.coerceAtMost(1f)) else 0f
        val stride    = FastMath.sin(walkClock) * strideAmp

        // Legs.
        hipL.localRotation = Quaternion().fromAngleAxis( stride, Vector3f.UNIT_X)
        hipR.localRotation = Quaternion().fromAngleAxis(-stride, Vector3f.UNIT_X)

        // Hip sway.
        val targetSway = if (moving) FastMath.cos(walkClock) * 0.06f else 0f
        currentHipSway = FastMath.interpolateLinear(tpf * 8f, currentHipSway, targetSway)

        // Spine lean.
        val runLean    = if (moving) -0.22f * speedScale.coerceAtMost(1f) else 0f
        val flinchLean = hurtFlash * 0.40f
        val dashLean   = if (dash01 != null) when (playerClass) {
            PlayerClass.WARRIOR -> -0.55f * FastMath.sin(dash01 * FastMath.PI)
            PlayerClass.MAGE    -> 0f
            PlayerClass.ARCHER  ->  0.35f * FastMath.sin(dash01 * FastMath.PI)
        } else 0f
        currentSpineLean = FastMath.interpolateLinear(tpf * 10f, currentSpineLean,
            runLean + flinchLean + dashLean)

        // Mage blink spin.
        val spinY = if (dash01 != null && playerClass == PlayerClass.MAGE)
            dash01 * FastMath.TWO_PI * 1.5f else 0f
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean, spinY, currentHipSway)

        // Shoulder counter-rotation (natural arm swing).
        val shoulderCounter = if (moving) FastMath.cos(walkClock) * 0.07f else 0f

        // Arms — priority: combo > normal attack > block > walk idle.
        val activeAttack = combo01 ?: attack01
        when {
            combo01 != null  -> applyCombo(combo01)
            attack01 != null -> applyAttackPose(attack01)
            blocking         -> applyBlockPose(tpf)
            else -> {
                blockAmount = FastMath.interpolateLinear(tpf * 5f, blockAmount, 0f)
                val rSwing = -stride * 0.90f
                val lSwing =  stride * 0.90f
                shoulderR.localRotation = Quaternion().fromAngles(rSwing, 0f,  shoulderCounter)
                shoulderL.localRotation = Quaternion().fromAngles(lSwing, 0f, -shoulderCounter)
                offhandNode.localRotation = Quaternion().fromAngleAxis(-blockAmount * 1.3f, Vector3f.UNIT_X)
                offhandMat.setColor("Diffuse",
                    idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))
            }
        }

        // Hurt flash on torso.
        torsoMat.setColor("Diffuse", idleTorsoColor.clone().interpolateLocal(hurtColor, hurtFlash))

        // Idle breathing.
        val idleBob = if (!moving && activeAttack == null && dash01 == null)
            FastMath.sin(idleClock * 2.2f) * 0.012f else 0f

        // Dash squash-and-stretch.
        val dashScale = if (dash01 != null) 1f + FastMath.sin(dash01 * FastMath.PI) * 0.24f else 1f
        root.setLocalTranslation(0f, idleBob, 0f)
        root.setLocalScale(1f, 1f / dashScale, dashScale)

        // Head bob.
        val headBob  = if (moving) FastMath.sin(walkClock * 2f) * 0.025f else 0f
        val headLook = if (moving) -0.12f * speedScale.coerceAtMost(1f) else 0f
        neckNode.localRotation = Quaternion().fromAngles(headLook + headBob, 0f, 0f)
    }

    // ── Normal attack poses ───────────────────────────────────────────────────

    private fun applyAttackPose(t: Float) = when (playerClass) {
        PlayerClass.WARRIOR -> applyWarriorNormal(t)
        PlayerClass.MAGE    -> applyMageNormal(t)
        PlayerClass.ARCHER  -> applyArcherNormal(t)
    }

    /**
     * Warrior normal — 4-phase power slash:
     *  0→0.30  Wind-up: arm yanks WAY back, torso coils right.
     *  0.30→0.65 Power rip: arm sweeps diagonally L, torso unwinds explosively.
     *  0.65→0.80 Follow-through: arm continues past, slight upswing.
     *  0.80→1.0  Recovery: arm returns to neutral.
     */
    private fun applyWarriorNormal(t: Float) {
        val windFrac  = (t / 0.30f).coerceIn(0f, 1f)
        val ripFrac   = ((t - 0.30f) / 0.35f).coerceIn(0f, 1f)
        val ftFrac    = ((t - 0.65f) / 0.15f).coerceIn(0f, 1f)
        val recFrac   = ((t - 0.80f) / 0.20f).coerceIn(0f, 1f)

        val pitchR = when {
            t < 0.30f -> lerp(windFrac, 0f, -1.80f)          // raise high behind
            t < 0.65f -> lerp(ripFrac,  -1.80f, 0.90f)       // diagonal slash down
            t < 0.80f -> lerp(ftFrac,   0.90f,  0.60f)       // follow through
            else      -> lerp(recFrac,  0.60f,  0.00f)       // return
        }
        val rollR = when {
            t < 0.30f -> lerp(windFrac, 0f, -1.00f)          // pull inward/back
            t < 0.65f -> lerp(ripFrac,  -1.00f, 1.10f)       // sweep through wide
            t < 0.80f -> lerp(ftFrac,   1.10f,  0.80f)
            else      -> lerp(recFrac,  0.80f,  0.00f)
        }
        // Torso twists to load and unload.
        val torsoY = when {
            t < 0.30f -> lerp(windFrac, 0f, -0.60f)          // coil right
            t < 0.65f -> lerp(ripFrac,  -0.60f, 0.50f)       // unwind to left
            else      -> lerp(((t - 0.65f) / 0.35f).coerceIn(0f, 1f), 0.50f, 0f)
        }

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, rollR)
        shoulderL.localRotation = Quaternion().fromAngles(0.25f, 0f, -0.20f)   // brace arm
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean, torsoY, currentHipSway)
        blockAmount = lerp(0.1f, blockAmount, 0f)
        offhandNode.localRotation = Quaternion().fromAngleAxis(-blockAmount * 1.3f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))
    }

    /**
     * Mage normal — charge (Y-shape) → thrust → recoil.
     */
    private fun applyMageNormal(t: Float) {
        val chargeFrac = (t / 0.45f).coerceIn(0f, 1f)
        val thrustFrac = ((t - 0.45f) / 0.30f).coerceIn(0f, 1f)
        val recoilFrac = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)

        val pitchR = when {
            t < 0.45f -> lerp(chargeFrac, 0f, -2.20f)        // raise overhead
            t < 0.75f -> lerp(thrustFrac, -2.20f, -0.35f)    // thrust forward
            else      -> lerp(recoilFrac, -0.35f, 0f)
        }
        val pitchL = when {
            t < 0.45f -> lerp(chargeFrac, 0f, -1.80f)
            t < 0.75f -> lerp(thrustFrac, -1.80f, -0.20f)
            else      -> lerp(recoilFrac, -0.20f, 0f)
        }
        val spineExtra = when {
            t < 0.45f -> lerp(chargeFrac,  0f,  0.28f)       // lean back on charge
            t < 0.75f -> lerp(thrustFrac,  0.28f, -0.38f)    // snap forward on release
            else      -> lerp(recoilFrac, -0.38f, 0f)
        }

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, 0.16f)
        shoulderL.localRotation = Quaternion().fromAngles(pitchL, 0f, -0.16f)
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean + spineExtra, 0f, currentHipSway)
        blockAmount = lerp(0.1f, blockAmount, chargeFrac * 0.7f)
        offhandNode.localRotation = Quaternion().fromAngleAxis(-pitchL * 0.6f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, chargeFrac * 0.8f))
    }

    /**
     * Archer normal — draw → aim hold → snap release.
     *  0→0.45  Draw: right arm pulls back, left extends, body turns sideways.
     *  0.45→0.62 Aim hold: brief pause at full tension (slight tremor).
     *  0.62→0.80 Snap: right arm snaps forward, left recoils, body jerks.
     *  0.80→1.0  Recovery.
     */
    private fun applyArcherNormal(t: Float) {
        val drawFrac    = (t / 0.45f).coerceIn(0f, 1f)
        val snapFrac    = ((t - 0.62f) / 0.18f).coerceIn(0f, 1f)
        val recFrac     = ((t - 0.80f) / 0.20f).coerceIn(0f, 1f)

        // Tremor during aim hold (0.45→0.62).
        val inHold  = t in 0.45f..0.62f
        val tremor  = if (inHold) FastMath.sin(t * 120f) * 0.04f else 0f

        val pitchR = when {
            t < 0.45f -> lerp(drawFrac,  0f, 1.40f)          // pull string back
            t < 0.62f -> 1.40f + tremor                       // hold with tremor
            t < 0.80f -> lerp(snapFrac,  1.40f, -0.35f)      // snap release
            else      -> lerp(recFrac,  -0.35f, 0f)
        }
        val pitchL = when {
            t < 0.45f -> lerp(drawFrac,  0f, -1.50f)         // extend bow arm
            t < 0.62f -> -1.50f + tremor
            t < 0.80f -> lerp(snapFrac,  -1.50f, -0.60f)     // recoil
            else      -> lerp(recFrac,  -0.60f, 0f)
        }
        val rollL = when {
            t < 0.45f -> lerp(drawFrac, 0f, 0.42f)
            t < 0.62f -> 0.42f
            else      -> lerp(((t - 0.62f) / 0.38f).coerceIn(0f, 1f), 0.42f, 0f)
        }
        val spineTurn = when {
            t < 0.45f -> lerp(drawFrac, 0f, 0.62f)
            t < 0.62f -> 0.62f
            else      -> lerp(((t - 0.62f) / 0.38f).coerceIn(0f, 1f), 0.62f, 0.15f)
        }

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, -0.30f * drawFrac)
        shoulderL.localRotation = Quaternion().fromAngles(pitchL, 0f, rollL)
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean, spineTurn, currentHipSway)
        blockAmount = 0f
        offhandNode.localRotation = Quaternion().fromAngleAxis(-pitchL * 0.5f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse", idleOffhandColor)
    }

    // ── Combo poses ───────────────────────────────────────────────────────────

    private fun applyCombo(t: Float) = when (playerClass) {
        PlayerClass.WARRIOR -> applyWarriorCombo(t)
        PlayerClass.MAGE    -> applyMageCombo(t)
        PlayerClass.ARCHER  -> applyArcherCombo(t)
    }

    /**
     * Warrior 3-hit chain:
     *   0→0.28  Hit 1: R→L diagonal slash (mirrored normal rip).
     *   0.28→0.55 Hit 2: L→R fast return slash.
     *   0.55→0.80 Wind up spin: arms raise overhead, body begins rotating.
     *   0.80→1.0  Overhead slam: arms crash down, torso hunches forward hard.
     */
    private fun applyWarriorCombo(t: Float) {
        val hit1F  = (t / 0.28f).coerceIn(0f, 1f)
        val hit2F  = ((t - 0.28f) / 0.27f).coerceIn(0f, 1f)
        val windF  = ((t - 0.55f) / 0.25f).coerceIn(0f, 1f)
        val slamF  = ((t - 0.80f) / 0.20f).coerceIn(0f, 1f)

        val pitchR = when {
            t < 0.28f -> lerp(hit1F, 0f, 0.80f)              // swing forward-right
            t < 0.55f -> lerp(hit2F, 0.80f, -0.40f)          // sweep back left
            t < 0.80f -> lerp(windF, -0.40f, -2.40f)         // raise high overhead
            else      -> lerp(slamF, -2.40f, 1.20f)          // SLAM down
        }
        val rollR = when {
            t < 0.28f -> lerp(hit1F, -0.90f, 1.00f)          // wide arc
            t < 0.55f -> lerp(hit2F, 1.00f, -0.80f)          // return arc
            t < 0.80f -> lerp(windF, -0.80f, 0f)             // neutral overhead
            else      -> lerp(slamF, 0f, 0f)                  // straight slam
        }
        val torsoY = when {
            t < 0.28f -> lerp(hit1F, -0.50f, 0.45f)
            t < 0.55f -> lerp(hit2F, 0.45f, -0.50f)
            t < 0.80f -> lerp(windF, -0.50f, 0f)
            else      -> lerp(slamF, 0f, 0.20f)               // slight forward lean
        }
        val torsoX = when {
            t < 0.80f -> currentSpineLean
            else      -> lerp(slamF, currentSpineLean, currentSpineLean - 0.50f) // hunch forward
        }
        // Full body spin effect during wind-up: use root Y (the whole player rotates).
        val bodySpinY = when {
            t < 0.55f -> 0f
            t < 0.80f -> lerp(windF, 0f, FastMath.TWO_PI)    // 360° spin
            else      -> FastMath.TWO_PI                       // hold final rotation
        }
        root.rotate(0f, bodySpinY - root.localRotation.toAngles(null)[1].let { 0f }, 0f)

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, rollR)
        shoulderL.localRotation = Quaternion().fromAngles(-pitchR * 0.3f, 0f, 0.20f)
        spineNode.localRotation = Quaternion().fromAngles(torsoX, torsoY, currentHipSway)
        blockAmount = 0f
        offhandNode.localRotation = Quaternion().fromAngleAxis(-blockAmount * 1.3f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse", idleOffhandColor)
    }

    /**
     * Mage chain lightning:
     *   0→0.40  Arms spread WIDE to sides (T-pose, lightning channels).
     *   0.40→0.65 Arms slam forward and together, blast releases.
     *   0.65→0.80 Recoil — spine snaps BACK dramatically.
     *   0.80→1.0  Recovery.
     */
    private fun applyMageCombo(t: Float) {
        val spreadF  = (t / 0.40f).coerceIn(0f, 1f)
        val slamF    = ((t - 0.40f) / 0.25f).coerceIn(0f, 1f)
        val recoilF  = ((t - 0.65f) / 0.15f).coerceIn(0f, 1f)
        val recF     = ((t - 0.80f) / 0.20f).coerceIn(0f, 1f)

        // Right arm: spreads out to side, then slams forward.
        val pitchR = when {
            t < 0.40f -> lerp(spreadF, 0f, -0.30f)           // slight upward
            t < 0.65f -> lerp(slamF, -0.30f, -0.55f)         // thrust forward
            else      -> lerp(((t - 0.65f) / 0.35f).coerceIn(0f, 1f), -0.55f, 0f)
        }
        val rollR = when {
            t < 0.40f -> lerp(spreadF, 0f, -1.60f)           // spread to right side
            t < 0.65f -> lerp(slamF, -1.60f, 0.15f)          // sweep to center
            else      -> lerp(((t - 0.65f) / 0.35f).coerceIn(0f, 1f), 0.15f, 0f)
        }
        val pitchL = when {
            t < 0.40f -> lerp(spreadF, 0f, -0.30f)
            t < 0.65f -> lerp(slamF, -0.30f, -0.55f)
            else      -> lerp(((t - 0.65f) / 0.35f).coerceIn(0f, 1f), -0.55f, 0f)
        }
        val rollL = when {
            t < 0.40f -> lerp(spreadF, 0f, 1.60f)            // spread to left side
            t < 0.65f -> lerp(slamF, 1.60f, -0.15f)          // sweep to center
            else      -> lerp(((t - 0.65f) / 0.35f).coerceIn(0f, 1f), -0.15f, 0f)
        }
        val spineExtra = when {
            t < 0.40f -> lerp(spreadF,  0f,   0.15f)         // slight lean back
            t < 0.65f -> lerp(slamF,   0.15f, -0.50f)        // lunge forward
            t < 0.80f -> lerp(recoilF, -0.50f, 0.70f)        // SNAP back
            else      -> lerp(recF,    0.70f,  0f)
        }

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, rollR)
        shoulderL.localRotation = Quaternion().fromAngles(pitchL, 0f, rollL)
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean + spineExtra, 0f, currentHipSway)
        blockAmount = lerp(0.1f, blockAmount, spreadF * 0.9f)
        offhandNode.localRotation = Quaternion().fromAngleAxis(rollL * 0.5f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, spreadF * 0.9f))
    }

    /**
     * Archer 5-arrow rapid volley:
     *   0→0.18  Initial draw (same as normal draw).
     *   0.18→1.0  5 rapid draw→snap cycles.
     *             Each cycle: 0.16s draw, snap at midpoint.
     */
    private fun applyArcherCombo(t: Float) {
        val initF = (t / 0.18f).coerceIn(0f, 1f)

        // After initial draw, do rapid snap cycles.
        val cycleLen = 0.164f           // each arrow takes this long
        val cycleT   = if (t >= 0.18f) ((t - 0.18f) % cycleLen) / cycleLen else null

        val pitchR = when {
            t < 0.18f -> lerp(initF, 0f, 1.40f)
            else      -> {
                val c = cycleT!!
                // Snap: snap forward at 0.5, re-draw by 1.0.
                if (c < 0.5f) lerp(c / 0.5f, 1.40f, -0.30f) else lerp((c - 0.5f) / 0.5f, -0.30f, 1.40f)
            }
        }
        val pitchL = when {
            t < 0.18f -> lerp(initF, 0f, -1.50f)
            else      -> -1.50f                                // stays extended
        }
        val spineTurn = when {
            t < 0.18f -> lerp(initF, 0f, 0.62f)
            else      -> 0.62f
        }
        // Rapid body jolt on each snap.
        val jolt = if (t >= 0.18f && cycleT != null && cycleT < 0.20f)
            -0.12f * FastMath.sin(cycleT / 0.20f * FastMath.PI) else 0f

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, -0.30f * initF)
        shoulderL.localRotation = Quaternion().fromAngles(pitchL, 0f, 0.42f)
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean + jolt, spineTurn, currentHipSway)
        blockAmount = 0f
        offhandNode.localRotation = Quaternion().fromAngleAxis(-pitchL * 0.5f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse", idleOffhandColor)
    }

    // ── Block pose ────────────────────────────────────────────────────────────

    private fun applyBlockPose(tpf: Float) {
        blockAmount = FastMath.interpolateLinear(tpf * 7f, blockAmount, 1f)
        val raise   = -blockAmount * 1.4f

        when (playerClass) {
            PlayerClass.WARRIOR -> {
                shoulderL.localRotation = Quaternion().fromAngles(raise, 0f, -0.2f)
                shoulderR.localRotation = Quaternion().fromAngles(-0.3f, 0f, 0.1f)
                spineNode.localRotation = Quaternion().fromAngles(
                    currentSpineLean - blockAmount * 0.2f, 0f, currentHipSway)
            }
            PlayerClass.MAGE -> {
                shoulderL.localRotation = Quaternion().fromAngles(raise, 0f, -0.3f)
                shoulderR.localRotation = Quaternion().fromAngles(raise * 0.8f, 0f, 0.2f)
                spineNode.localRotation = Quaternion().fromAngles(
                    currentSpineLean + blockAmount * 0.1f, 0f, currentHipSway)
            }
            PlayerClass.ARCHER -> {
                shoulderR.localRotation = Quaternion().fromAngles(0f, 0f, 0f)
                shoulderL.localRotation = Quaternion().fromAngles(0f, 0f, 0f)
            }
        }
        offhandNode.localRotation = Quaternion().fromAngleAxis(raise, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))
    }

    // ── Math helper ───────────────────────────────────────────────────────────

    private fun lerp(t: Float, a: Float, b: Float) = FastMath.interpolateLinear(t, a, b)
}
