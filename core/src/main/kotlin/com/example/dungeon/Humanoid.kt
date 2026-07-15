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
import com.jme3.scene.shape.Cylinder
import com.jme3.scene.shape.Sphere

/**
 * Procedural low-poly hero — built entirely from primitive shapes.
 *
 * Improvements over v1:
 *  - Spine node lets the torso lean forward while running and flinch backward
 *    on a hit independently of the leg swing.
 *  - Neck node drives a subtle head bob and look-up/look-down during actions.
 *  - Hip sway (Z-axis root oscillation) gives a natural weight-shift during walk.
 *  - Shoulder counter-rotation: shoulders swing opposite to hips for a real
 *    walk cycle where arms cross the body.
 *  - Run lean: at high speed the spine tips forward for a sprinting silhouette.
 *  - Per-class attack poses now involve the whole upper body:
 *      Warrior  — wide horizontal slash arc + torso twist + spine snap on impact.
 *      Mage     — both arms raise into a casting Y-shape, spine opens up.
 *      Archer   — left arm extends outward, spine turns sideways, right arm draws.
 *  - Dash/blink animations per class:
 *      Warrior  — body planks forward (extreme forward lean).
 *      Mage     — spine spins 360° during the blink (one full twist).
 *      Archer   — body ducks into a low rolling crouch.
 *  - Victory pose (gameOver param): arms raise in a V on win, slump on lose.
 */
class Humanoid(assetManager: AssetManager, private val playerClass: PlayerClass) {

    // ── Proportions (world units; feet at local y = 0) ──────────────────────
    private val legLength   = 0.46f
    private val hipY        = legLength
    private val torsoHeight = 0.50f
    private val shoulderY   = hipY + torsoHeight
    private val headRadius  = 0.19f
    private val armLength   = 0.42f

    val root = Node("PlayerModel")

    // Pivots whose rotations drive animation.
    private val spineNode  = Node("Spine").apply { setLocalTranslation(0f, hipY + torsoHeight * 0.4f, 0f) }
    private val neckNode   = Node("Neck").apply  { setLocalTranslation(0f, torsoHeight * 0.6f, 0f) }
    private val shoulderL  = Node("ShoulderL").apply { setLocalTranslation(-0.26f, torsoHeight * 0.55f, 0f) }
    private val shoulderR  = Node("ShoulderR").apply { setLocalTranslation( 0.26f, torsoHeight * 0.55f, 0f) }
    private val hipL       = Node("HipL").apply { setLocalTranslation(-0.13f, hipY, 0f) }
    private val hipR       = Node("HipR").apply { setLocalTranslation( 0.13f, hipY, 0f) }
    private val offhandPivot = Node("OffhandPivot")

    private lateinit var offhandMat:      Material
    private lateinit var idleOffhandColor: ColorRGBA
    private lateinit var blockOffhandColor: ColorRGBA
    private lateinit var torsoMat:        Material
    private lateinit var idleTorsoColor:  ColorRGBA
    private val hurtFlashColor = ColorRGBA(0.95f, 0.15f, 0.15f, 1f)

    init {
        fun mat(diffuse: ColorRGBA, ambient: ColorRGBA, shininess: Float = 12f) =
            Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse",  diffuse)
                setColor("Ambient",  ambient)
                setColor("Specular", ColorRGBA(0.6f, 0.6f, 0.6f, 1f))
                setFloat("Shininess", shininess)
            }

        val skinMat = mat(ColorRGBA(0.85f, 0.67f, 0.53f, 1f), ColorRGBA(0.25f, 0.19f, 0.15f, 1f))

        val (torsoColor, torsoAmbient, pantsColor, pantsAmbient, accentColor, accentAmbient) = when (playerClass) {
            PlayerClass.WARRIOR -> Palette(
                ColorRGBA(0.18f, 0.42f, 0.26f, 1f), ColorRGBA(0.05f, 0.12f, 0.08f, 1f),
                ColorRGBA(0.32f, 0.26f, 0.20f, 1f), ColorRGBA(0.08f, 0.07f, 0.06f, 1f),
                ColorRGBA(0.72f, 0.74f, 0.78f, 1f), ColorRGBA(0.20f, 0.20f, 0.22f, 1f),
            )
            PlayerClass.MAGE -> Palette(
                ColorRGBA(0.30f, 0.16f, 0.48f, 1f), ColorRGBA(0.09f, 0.05f, 0.15f, 1f),
                ColorRGBA(0.20f, 0.12f, 0.30f, 1f), ColorRGBA(0.06f, 0.04f, 0.10f, 1f),
                ColorRGBA(0.95f, 0.55f, 0.15f, 1f), ColorRGBA(0.35f, 0.18f, 0.02f, 1f),
            )
            PlayerClass.ARCHER -> Palette(
                ColorRGBA(0.30f, 0.34f, 0.20f, 1f), ColorRGBA(0.09f, 0.11f, 0.06f, 1f),
                ColorRGBA(0.26f, 0.20f, 0.14f, 1f), ColorRGBA(0.07f, 0.06f, 0.04f, 1f),
                ColorRGBA(0.42f, 0.30f, 0.16f, 1f), ColorRGBA(0.12f, 0.08f, 0.04f, 1f),
            )
        }

        idleTorsoColor = torsoColor.clone()
        torsoMat  = mat(torsoColor,  torsoAmbient)
        val pantsMat   = mat(pantsColor,  pantsAmbient)
        val bootMat    = mat(ColorRGBA(0.16f, 0.11f, 0.08f, 1f), ColorRGBA(0.04f, 0.03f, 0.02f, 1f))
        val accentMat  = mat(accentColor, accentAmbient, if (playerClass == PlayerClass.WARRIOR) 64f else 24f)
        val hiltMat    = mat(ColorRGBA(0.30f, 0.20f, 0.10f, 1f), ColorRGBA(0.08f, 0.05f, 0.03f, 1f))

        idleOffhandColor = when (playerClass) {
            PlayerClass.WARRIOR -> ColorRGBA(0.32f, 0.24f, 0.14f, 1f)
            PlayerClass.MAGE    -> ColorRGBA(0.35f, 0.18f, 0.55f, 1f)
            PlayerClass.ARCHER  -> ColorRGBA(0.30f, 0.22f, 0.12f, 1f)
        }
        blockOffhandColor = when (playerClass) {
            PlayerClass.ARCHER -> idleOffhandColor.clone()
            else -> ColorRGBA(0.25f, 0.55f, 0.85f, 1f)
        }
        offhandMat = mat(idleOffhandColor, ColorRGBA(0.08f, 0.10f, 0.12f, 1f))

        // ── Geometry ─────────────────────────────────────────────────────────

        // Torso (attached to spine node so spine lean carries it)
        val torso = Geometry("Torso", Box(0.17f, torsoHeight / 2f, 0.11f)).apply {
            material = torsoMat
            setLocalTranslation(0f, 0f, 0f)
        }

        // Head (attached to neck node)
        val head = Geometry("Head", Sphere(12, 12, headRadius)).apply {
            material = skinMat
            setLocalTranslation(0f, headRadius + 0.06f, 0f)
        }

        // Legs
        limb("LegL", 0.115f, legLength / 2f, 0.115f, pantsMat, hipL, legLength)
        limb("LegR", 0.115f, legLength / 2f, 0.115f, pantsMat, hipR, legLength)
        val bootL = Geometry("BootL", Box(0.13f, 0.07f, 0.15f)).apply {
            material = bootMat; setLocalTranslation(0f, -legLength + 0.07f, 0.02f)
        }
        val bootR = Geometry("BootR", Box(0.13f, 0.07f, 0.15f)).apply {
            material = bootMat; setLocalTranslation(0f, -legLength + 0.07f, 0.02f)
        }
        hipL.attachChild(bootL)
        hipR.attachChild(bootR)

        // Arms
        limb("ArmL", 0.09f, armLength / 2f, 0.09f, torsoMat, shoulderL, armLength)
        limb("ArmR", 0.09f, armLength / 2f, 0.09f, torsoMat, shoulderR, armLength)

        // Main-hand weapon in the right hand.
        val hand = Node("Hand").apply { setLocalTranslation(0f, -armLength, 0f) }
        shoulderR.attachChild(hand)

        when (playerClass) {
            PlayerClass.WARRIOR -> {
                // Sword: long blade + crossguard + pommel
                val blade = Geometry("Blade", Box(0.028f, 0.34f, 0.045f)).apply {
                    material = accentMat; setLocalTranslation(0f, -0.30f, 0f)
                }
                val guard = Geometry("Guard", Box(0.09f, 0.03f, 0.04f)).apply {
                    material = hiltMat; setLocalTranslation(0f, 0.04f, 0f)
                }
                val pommel = Geometry("Pommel", Sphere(6, 6, 0.04f)).apply {
                    material = hiltMat; setLocalTranslation(0f, 0.10f, 0f)
                }
                hand.attachChild(blade); hand.attachChild(guard); hand.attachChild(pommel)
            }
            PlayerClass.MAGE -> {
                // Staff: long wooden shaft + glowing orb
                val shaft = Geometry("Shaft", Cylinder(8, 8, 0.025f, 0.65f, true)).apply {
                    material = hiltMat; rotate(FastMath.HALF_PI, 0f, 0f)
                    setLocalTranslation(0f, -0.02f, 0f)
                }
                val orb = Geometry("Orb", Sphere(12, 12, 0.082f)).apply {
                    material = accentMat; setLocalTranslation(0f, 0.32f, 0f)
                }
                // Small ring around orb
                val ring = Geometry("Ring", Cylinder(6, 12, 0.10f, 0.02f, true)).apply {
                    material = accentMat
                    setLocalTranslation(0f, 0.32f, 0f)
                }
                hand.attachChild(shaft); hand.attachChild(orb); hand.attachChild(ring)
            }
            PlayerClass.ARCHER -> {
                // Bow: grip + two curved limbs
                val grip = Geometry("Grip", Box(0.025f, 0.10f, 0.025f)).apply {
                    material = hiltMat; setLocalTranslation(0f, -0.02f, 0f)
                }
                val limbT = Geometry("LimbTop", Cylinder(6, 6, 0.018f, 0.30f, true)).apply {
                    material = accentMat
                    rotate(0f, 0f, FastMath.QUARTER_PI * 0.7f)
                    setLocalTranslation(0.05f, 0.24f, 0f)
                }
                val limbB = Geometry("LimbBot", Cylinder(6, 6, 0.018f, 0.30f, true)).apply {
                    material = accentMat
                    rotate(0f, 0f, -FastMath.QUARTER_PI * 0.7f)
                    setLocalTranslation(0.05f, -0.28f, 0f)
                }
                hand.attachChild(grip); hand.attachChild(limbT); hand.attachChild(limbB)
            }
        }

        // Off-hand item on the left arm.
        offhandPivot.setLocalTranslation(0f, -armLength * 0.6f, 0f)
        when (playerClass) {
            PlayerClass.WARRIOR -> {
                val shield = Geometry("Shield", Box(0.05f, 0.22f, 0.16f)).apply {
                    material = offhandMat; setLocalTranslation(-0.10f, 0f, 0f)
                }
                offhandPivot.attachChild(shield)
            }
            PlayerClass.MAGE -> {
                val ward = Geometry("Ward", Sphere(10, 10, 0.11f)).apply {
                    material = offhandMat; setLocalTranslation(-0.05f, 0f, 0f)
                }
                offhandPivot.attachChild(ward)
            }
            PlayerClass.ARCHER -> {
                val quiver = Geometry("Quiver", Cylinder(6, 6, 0.07f, 0.30f, true)).apply {
                    material = accentMat
                    rotate(FastMath.QUARTER_PI * 0.5f, 0f, 0f)
                    setLocalTranslation(0f, hipY + 0.25f, -0.16f)
                }
                root.attachChild(quiver)
            }
        }
        shoulderL.attachChild(offhandPivot)

        // ── Scene graph ───────────────────────────────────────────────────────
        // root → hips (legs stay grounded and don't lean with spine)
        //      → spineNode → torso, shoulderL/R (upper body leans forward/back)
        //                  → neckNode → head
        root.attachChild(hipL)
        root.attachChild(hipR)

        spineNode.attachChild(torso)
        spineNode.attachChild(shoulderL)
        spineNode.attachChild(shoulderR)
        spineNode.attachChild(neckNode)
        neckNode.attachChild(head)

        root.attachChild(spineNode)
    }

    private data class Palette(
        val torsoColor: ColorRGBA, val torsoAmbient: ColorRGBA,
        val pantsColor: ColorRGBA, val pantsAmbient: ColorRGBA,
        val accentColor: ColorRGBA, val accentAmbient: ColorRGBA,
    )

    private fun limb(name: String, hw: Float, hh: Float, hd: Float, mat: Material, pivot: Node, length: Float): Geometry {
        val geom = Geometry(name, Box(hw, hh, hd)).apply {
            material = mat; setLocalTranslation(0f, -length / 2f, 0f)
        }
        pivot.attachChild(geom)
        return geom
    }

    // ── Animation state ──────────────────────────────────────────────────────

    private var walkClock       = 0f
    private var idleClock       = 0f
    private var hurtFlashAmount = 0f
    private var blockAmount     = 0f
    // Smoothed spine lean (avoids pop when transitioning run ↔ idle)
    private var currentSpineLean = 0f
    // Smoothed hip sway angle
    private var currentHipSway   = 0f

    /**
     * Called once per frame from DungeonGame.simpleUpdate.
     *
     * @param moving       true while the player is translating
     * @param speedScale   0 .. ~1.5 (how fast vs normal walk; drives stride rate and lean)
     * @param attack01     0..1 progress through an attack, or null
     * @param dash01       0..1 progress through dash/blink, or null
     * @param blocking     true while defensive button is held (hold classes only)
     * @param hurtPulse    set true for one frame when a boss hit lands
     */
    fun update(
        tpf: Float,
        moving: Boolean,
        speedScale: Float,
        attack01: Float?,
        dash01: Float?,
        blocking: Boolean,
        hurtPulse: Boolean = false,
    ) {
        // ── Timers ────────────────────────────────────────────────────────────
        if (hurtPulse) hurtFlashAmount = 1f
        hurtFlashAmount = (hurtFlashAmount - tpf * 2.8f).coerceAtLeast(0f)

        if (moving) {
            walkClock += tpf * (6.0f + 5.0f * speedScale.coerceAtMost(1f))
            idleClock  = 0f
        } else {
            // Gentle decay back to neutral — avoids a sudden snap.
            walkClock  = walkClock * (1f - tpf * 4f)
            idleClock += tpf
        }

        val strideAmp = if (moving) (0.50f + 0.18f * speedScale.coerceAtMost(1f)) else 0f
        val stride    = FastMath.sin(walkClock) * strideAmp

        // ── Legs ──────────────────────────────────────────────────────────────
        // Opposite-phase swing on each hip.
        hipL.localRotation = Quaternion().fromAngleAxis( stride, Vector3f.UNIT_X)
        hipR.localRotation = Quaternion().fromAngleAxis(-stride, Vector3f.UNIT_X)

        // ── Hip sway (Z-axis body rock side-to-side) ──────────────────────────
        val targetSway = if (moving) FastMath.cos(walkClock) * 0.06f else 0f
        currentHipSway = FastMath.interpolateLinear(tpf * 8f, currentHipSway, targetSway)

        // ── Spine lean (forward when running, back on flinch) ─────────────────
        val runLean       = if (moving) -0.22f * speedScale.coerceAtMost(1f) else 0f
        val flinchLean    = hurtFlashAmount * 0.40f
        val targetSpineLean = runLean + flinchLean

        // Extra lean while dashing — Warrior lunges, Mage tilts, Archer ducks.
        val dashLean = if (dash01 != null) when (playerClass) {
            PlayerClass.WARRIOR -> -0.55f * FastMath.sin(dash01 * FastMath.PI)
            PlayerClass.MAGE    -> 0f
            PlayerClass.ARCHER  -> 0.35f * FastMath.sin(dash01 * FastMath.PI)  // crouch forward
        } else 0f

        currentSpineLean = FastMath.interpolateLinear(tpf * 10f, currentSpineLean, targetSpineLean + dashLean)

        // ── Mage dash: spin the spine around Y during blink ───────────────────
        val spinY = if (dash01 != null && playerClass == PlayerClass.MAGE) {
            dash01 * FastMath.TWO_PI * 1.5f
        } else 0f

        spineNode.localRotation = Quaternion()
            .fromAngles(currentSpineLean, spinY, currentHipSway)

        // ── Shoulders (counter-rotate to hips for natural arm swing) ──────────
        val shoulderCounterRoll = if (moving) FastMath.cos(walkClock) * 0.07f else 0f

        // ── Arms ──────────────────────────────────────────────────────────────
        when {
            attack01 != null -> applyAttackPose(attack01)
            blocking         -> applyBlockPose(tpf)
            else -> {
                blockAmount = FastMath.interpolateLinear(tpf * 5f, blockAmount, 0f)
                // Walk arm swing: right arm back when right leg forward (natural gait).
                val rSwing = -stride * 0.90f
                val lSwing =  stride * 0.90f
                shoulderR.localRotation = Quaternion().fromAngles(rSwing, 0f, shoulderCounterRoll)
                shoulderL.localRotation = Quaternion().fromAngles(lSwing, 0f, -shoulderCounterRoll)
                offhandPivot.localRotation = Quaternion().fromAngleAxis(
                    -blockAmount * 1.3f, Vector3f.UNIT_X)
                offhandMat.setColor("Diffuse",
                    idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))
            }
        }

        // ── Hurt flash ────────────────────────────────────────────────────────
        torsoMat.setColor("Diffuse",
            idleTorsoColor.clone().interpolateLocal(hurtFlashColor, hurtFlashAmount))

        // ── Idle breathing bob ────────────────────────────────────────────────
        val idleBob = if (!moving && attack01 == null && dash01 == null)
            FastMath.sin(idleClock * 2.2f) * 0.012f else 0f

        // ── Dash squash-and-stretch ───────────────────────────────────────────
        val dashScale = if (dash01 != null)
            1f + FastMath.sin(dash01 * FastMath.PI) * 0.24f else 1f

        root.setLocalTranslation(0f, idleBob, 0f)
        root.setLocalScale(1f, 1f / dashScale, dashScale)

        // ── Head bob (slight up-down + look-ahead tilt while running) ─────────
        val headBob   = if (moving) FastMath.sin(walkClock * 2f) * 0.025f else 0f
        val headLook  = if (moving) -0.12f * speedScale.coerceAtMost(1f) else 0f
        neckNode.localRotation = Quaternion().fromAngles(headLook + headBob, 0f, 0f)
    }

    // ── Attack poses ──────────────────────────────────────────────────────────

    private fun applyAttackPose(t: Float) {
        when (playerClass) {
            PlayerClass.WARRIOR -> applyWarriorAttack(t)
            PlayerClass.MAGE    -> applyMageAttack(t)
            PlayerClass.ARCHER  -> applyArcherAttack(t)
        }
    }

    /**
     * Warrior: wide horizontal slash arc.
     *  Phase 1 (0→0.35): wind-up — arm pulls back-right, torso coils right.
     *  Phase 2 (0.35→1): release — arm sweeps left in a wide arc, torso unwinds.
     */
    private fun applyWarriorAttack(t: Float) {
        val windupFrac = (t / 0.35f).coerceIn(0f, 1f)
        val releaseFrac = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)

        // Vertical arc (X axis): wind up high, slash down.
        val pitchR = if (t < 0.35f)
            FastMath.interpolateLinear(windupFrac, 0f, -1.6f)
        else
            FastMath.interpolateLinear(releaseFrac, -1.6f, 0.8f)

        // Horizontal sweep (Z axis): pull right, sweep through to left.
        val rollR = if (t < 0.35f)
            FastMath.interpolateLinear(windupFrac, 0f, -0.8f)
        else
            FastMath.interpolateLinear(releaseFrac, -0.8f, 0.9f)

        // Torso twists with the slash (Y axis on spine).
        val torsoTwist = if (t < 0.35f)
            FastMath.interpolateLinear(windupFrac, 0f, -0.5f)
        else
            FastMath.interpolateLinear(releaseFrac, -0.5f, 0.4f)

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, rollR)
        // Left arm stays back and slightly raised for balance.
        shoulderL.localRotation = Quaternion().fromAngles(0.3f, 0f, -0.2f)

        // Spine adds the torso twist.
        val baseLean = currentSpineLean
        spineNode.localRotation = Quaternion().fromAngles(baseLean, torsoTwist, currentHipSway)

        blockAmount = FastMath.interpolateLinear(0.1f, blockAmount, 0f)
        offhandPivot.localRotation = Quaternion().fromAngleAxis(-blockAmount * 1.3f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))
    }

    /**
     * Mage: staff thrust — both arms raise in a Y-shape as the bolt charges,
     * then snap forward for the release.
     *  Phase 1 (0→0.45): charge — arms rise above head, spine opens (leans back).
     *  Phase 2 (0.45→1): thrust — arms push forward, spine snaps forward.
     */
    private fun applyMageAttack(t: Float) {
        val chargeFrac  = (t / 0.45f).coerceIn(0f, 1f)
        val thrustFrac  = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)

        // Main hand (staff): raise overhead, then thrust forward.
        val pitchR = if (t < 0.45f)
            FastMath.interpolateLinear(chargeFrac, 0f, -2.2f)
        else
            FastMath.interpolateLinear(thrustFrac, -2.2f, -0.4f)

        // Off-hand: mirror raise.
        val pitchL = if (t < 0.45f)
            FastMath.interpolateLinear(chargeFrac, 0f, -1.8f)
        else
            FastMath.interpolateLinear(thrustFrac, -1.8f, -0.2f)

        // Spine: open back on charge, snap forward on thrust.
        val spineExtra = if (t < 0.45f)
            FastMath.interpolateLinear(chargeFrac, 0f, 0.25f)
        else
            FastMath.interpolateLinear(thrustFrac, 0.25f, -0.35f)

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, 0.15f)
        shoulderL.localRotation = Quaternion().fromAngles(pitchL, 0f, -0.15f)
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean + spineExtra, 0f, currentHipSway)

        // Keep off-hand item raised in sync.
        blockAmount = FastMath.interpolateLinear(0.1f, blockAmount, chargeFrac * 0.7f)
        offhandPivot.localRotation = Quaternion().fromAngleAxis(-pitchL * 0.6f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, chargeFrac * 0.8f))
    }

    /**
     * Archer: draw, aim, release.
     *  Phase 1 (0→0.55): draw — right arm pulls back, left arm extends forward.
     *                     Spine turns sideways (archer's stance).
     *  Phase 2 (0.55→1): release — right arm snaps forward, left arm recoils.
     */
    private fun applyArcherAttack(t: Float) {
        val drawFrac    = (t / 0.55f).coerceIn(0f, 1f)
        val releaseFrac = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)

        // Right arm (bow draw hand): pulls back during draw, snaps forward on release.
        val pitchR = if (t < 0.55f)
            FastMath.interpolateLinear(drawFrac, 0f, 1.3f)    // pull back
        else
            FastMath.interpolateLinear(releaseFrac, 1.3f, -0.4f)  // snap forward

        // Left arm (bow hand): extends outward to hold the bow.
        val pitchL = if (t < 0.55f)
            FastMath.interpolateLinear(drawFrac, 0f, -1.4f)
        else
            FastMath.interpolateLinear(releaseFrac, -1.4f, -0.6f)

        // Outward spread of left arm (Z axis).
        val rollL = if (t < 0.55f)
            FastMath.interpolateLinear(drawFrac, 0f, 0.4f)
        else
            FastMath.interpolateLinear(releaseFrac, 0.4f, 0.2f)

        // Spine turns sideways for a proper archery stance.
        val spineTurn = if (t < 0.55f)
            FastMath.interpolateLinear(drawFrac, 0f, 0.6f)
        else
            FastMath.interpolateLinear(releaseFrac, 0.6f, 0.2f)

        shoulderR.localRotation = Quaternion().fromAngles(pitchR, 0f, -0.3f * drawFrac)
        shoulderL.localRotation = Quaternion().fromAngles(pitchL, 0f, rollL)
        spineNode.localRotation = Quaternion().fromAngles(currentSpineLean, spineTurn, currentHipSway)

        blockAmount = 0f
        offhandPivot.localRotation = Quaternion().fromAngleAxis(-pitchL * 0.5f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse", idleOffhandColor)
    }

    // ── Block pose ────────────────────────────────────────────────────────────

    private fun applyBlockPose(tpf: Float) {
        blockAmount = FastMath.interpolateLinear(tpf * 7f, blockAmount, 1f)

        val shieldRaise = -blockAmount * 1.4f

        when (playerClass) {
            PlayerClass.WARRIOR -> {
                // Shield arm swings up in front; sword arm pulls slightly back.
                shoulderL.localRotation = Quaternion().fromAngles(shieldRaise, 0f, -0.2f)
                shoulderR.localRotation = Quaternion().fromAngles(-0.3f, 0f, 0.1f)
                // Lean slightly forward into the block.
                spineNode.localRotation = Quaternion().fromAngles(
                    currentSpineLean - blockAmount * 0.2f, 0f, currentHipSway)
            }
            PlayerClass.MAGE -> {
                // Both arms raise to project the ward.
                shoulderL.localRotation = Quaternion().fromAngles(shieldRaise, 0f, -0.3f)
                shoulderR.localRotation = Quaternion().fromAngles(shieldRaise * 0.8f, 0f, 0.2f)
                spineNode.localRotation = Quaternion().fromAngles(
                    currentSpineLean + blockAmount * 0.1f, 0f, currentHipSway)
            }
            PlayerClass.ARCHER -> {
                // Archer has no block — leave neutral.
                shoulderR.localRotation = Quaternion().fromAngles(0f, 0f, 0f)
                shoulderL.localRotation = Quaternion().fromAngles(0f, 0f, 0f)
            }
        }

        offhandPivot.localRotation = Quaternion().fromAngleAxis(shieldRaise, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse",
            idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))
    }
}
