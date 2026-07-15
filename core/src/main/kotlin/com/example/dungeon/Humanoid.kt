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
 * A small procedural low-poly hero, built entirely from primitive shapes
 * (no imported model files) so the whole thing is version-control friendly
 * and needs no asset pipeline.
 *
 * Body parts hang from pivot [Node]s positioned at the joint (shoulder/hip),
 * with the actual geometry offset below the pivot -- rotating the pivot
 * swings the limb like a real joint instead of spinning around its own
 * center.
 *
 * Visuals and attack poses are driven by [playerClass] so each hero reads as
 * a distinct fighter, not a recolored sword-and-shield warrior:
 *  - [PlayerClass.WARRIOR] -- tunic, sword + shield.
 *  - [PlayerClass.MAGE]    -- robe, staff + floating arcane ward orb.
 *  - [PlayerClass.ARCHER]  -- leathers, bow + quiver, no off-hand item.
 */
class Humanoid(assetManager: AssetManager, private val playerClass: PlayerClass) {

    // ── Proportions (world units; feet sit at local y = 0) ──────────────────
    private val legLength   = 0.46f
    private val hipY        = legLength
    private val torsoHeight = 0.5f
    private val shoulderY   = hipY + torsoHeight
    private val headRadius  = 0.19f

    val root = Node("PlayerModel")

    private val shoulderL = Node("ShoulderL").apply { setLocalTranslation(-0.26f, shoulderY, 0f) }
    private val shoulderR = Node("ShoulderR").apply { setLocalTranslation(0.26f, shoulderY, 0f) }
    private val hipL = Node("HipL").apply { setLocalTranslation(-0.13f, hipY, 0f) }
    private val hipR = Node("HipR").apply { setLocalTranslation(0.13f, hipY, 0f) }
    private val offhandPivot = Node("OffhandPivot")

    private lateinit var offhandMat: Material
    private lateinit var idleOffhandColor: ColorRGBA
    private lateinit var blockOffhandColor: ColorRGBA
    private lateinit var torsoMat: Material
    private lateinit var idleTorsoColor: ColorRGBA
    private val hurtFlashColor = ColorRGBA(0.95f, 0.15f, 0.15f, 1f)

    init {
        fun mat(diffuse: ColorRGBA, ambient: ColorRGBA, shininess: Float = 12f) =
            Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse", diffuse)
                setColor("Ambient", ambient)
                setColor("Specular", ColorRGBA(0.6f, 0.6f, 0.6f, 1f))
                setFloat("Shininess", shininess)
            }

        val skinMat = mat(ColorRGBA(0.85f, 0.67f, 0.53f, 1f), ColorRGBA(0.25f, 0.19f, 0.15f, 1f))

        // Class-specific palette: tunic/robe/leathers, pants, and accent (blade/staff/bow) colors.
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
        torsoMat = mat(torsoColor, torsoAmbient)
        val pantsMat = mat(pantsColor, pantsAmbient)
        val bootMat  = mat(ColorRGBA(0.16f, 0.11f, 0.08f, 1f), ColorRGBA(0.04f, 0.03f, 0.02f, 1f))
        val accentMat = mat(accentColor, accentAmbient, if (playerClass == PlayerClass.WARRIOR) 64f else 24f)
        val hiltMat  = mat(ColorRGBA(0.30f, 0.20f, 0.10f, 1f), ColorRGBA(0.08f, 0.05f, 0.03f, 1f))

        idleOffhandColor = when (playerClass) {
            PlayerClass.WARRIOR -> ColorRGBA(0.32f, 0.24f, 0.14f, 1f)
            PlayerClass.MAGE    -> ColorRGBA(0.35f, 0.18f, 0.55f, 1f)
            PlayerClass.ARCHER  -> ColorRGBA(0.30f, 0.22f, 0.12f, 1f)
        }
        blockOffhandColor = when (playerClass) {
            PlayerClass.ARCHER -> idleOffhandColor.clone() // Archer never blocks; color unused.
            else -> ColorRGBA(0.25f, 0.55f, 0.85f, 1f)
        }
        offhandMat = mat(idleOffhandColor, ColorRGBA(0.08f, 0.10f, 0.12f, 1f))

        // Torso
        val torso = Geometry("Torso", Box(0.17f, torsoHeight / 2f, 0.11f)).apply {
            material = torsoMat
            setLocalTranslation(0f, hipY + torsoHeight / 2f, 0f)
        }

        // Head
        val head = Geometry("Head", Sphere(12, 12, headRadius)).apply {
            material = skinMat
            setLocalTranslation(0f, shoulderY + headRadius + 0.06f, 0f)
        }

        // Legs (hang below the hip pivots)
        limb("LegL", 0.115f, legLength / 2f, 0.115f, pantsMat, hipL, legLength)
        limb("LegR", 0.115f, legLength / 2f, 0.115f, pantsMat, hipR, legLength)
        val bootL = Geometry("BootL", Box(0.13f, 0.07f, 0.15f)).apply {
            material = bootMat
            setLocalTranslation(0f, -legLength + 0.07f, 0.02f)
        }
        val bootR = Geometry("BootR", Box(0.13f, 0.07f, 0.15f)).apply {
            material = bootMat
            setLocalTranslation(0f, -legLength + 0.07f, 0.02f)
        }
        hipL.attachChild(bootL)
        hipR.attachChild(bootR)

        // Arms
        val armLength = 0.42f
        limb("ArmL", 0.09f, armLength / 2f, 0.09f, torsoMat, shoulderL, armLength)
        limb("ArmR", 0.09f, armLength / 2f, 0.09f, torsoMat, shoulderR, armLength)

        // Main-hand weapon, held in the right hand (end of the right arm).
        val hand = Node("Hand").apply { setLocalTranslation(0f, -armLength, 0f) }
        shoulderR.attachChild(hand)
        when (playerClass) {
            PlayerClass.WARRIOR -> {
                val bladeGeom = Geometry("SwordBlade", Box(0.03f, 0.32f, 0.05f)).apply {
                    material = accentMat
                    setLocalTranslation(0f, -0.30f, 0f)
                }
                val hiltGeom = Geometry("SwordHilt", Box(0.05f, 0.06f, 0.05f)).apply {
                    material = hiltMat
                    setLocalTranslation(0f, 0.02f, 0f)
                }
                hand.attachChild(bladeGeom)
                hand.attachChild(hiltGeom)
            }
            PlayerClass.MAGE -> {
                val shaft = Geometry("StaffShaft", Cylinder(8, 8, 0.025f, 0.62f, true)).apply {
                    material = hiltMat
                    rotate(FastMath.HALF_PI, 0f, 0f)
                    setLocalTranslation(0f, -0.02f, 0f)
                }
                val orb = Geometry("StaffOrb", Sphere(10, 10, 0.075f)).apply {
                    material = accentMat
                    setLocalTranslation(0f, 0.30f, 0f)
                }
                hand.attachChild(shaft)
                hand.attachChild(orb)
            }
            PlayerClass.ARCHER -> {
                // A simple curved bow: two angled limbs meeting at a grip.
                val grip = Geometry("BowGrip", Box(0.025f, 0.10f, 0.025f)).apply {
                    material = hiltMat
                    setLocalTranslation(0f, -0.02f, 0f)
                }
                val limbTop = Geometry("BowLimbTop", Cylinder(6, 6, 0.018f, 0.30f, true)).apply {
                    material = accentMat
                    rotate(0f, 0f, FastMath.QUARTER_PI * 0.7f)
                    setLocalTranslation(0.05f, 0.24f, 0f)
                }
                val limbBottom = Geometry("BowLimbBottom", Cylinder(6, 6, 0.018f, 0.30f, true)).apply {
                    material = accentMat
                    rotate(0f, 0f, -FastMath.QUARTER_PI * 0.7f)
                    setLocalTranslation(0.05f, -0.28f, 0f)
                }
                hand.attachChild(grip)
                hand.attachChild(limbTop)
                hand.attachChild(limbBottom)
            }
        }

        // Off-hand item, on the left arm -- pivots independently so it can
        // swing up into a "block"/"cast" pose without the whole arm following.
        offhandPivot.setLocalTranslation(0f, -armLength * 0.6f, 0f)
        when (playerClass) {
            PlayerClass.WARRIOR -> {
                val shieldGeom = Geometry("Shield", Box(0.05f, 0.22f, 0.16f)).apply {
                    material = offhandMat
                    setLocalTranslation(-0.10f, 0f, 0f)
                }
                offhandPivot.attachChild(shieldGeom)
            }
            PlayerClass.MAGE -> {
                val wardGeom = Geometry("Ward", Sphere(10, 10, 0.11f)).apply {
                    material = offhandMat
                    setLocalTranslation(-0.05f, 0f, 0f)
                }
                offhandPivot.attachChild(wardGeom)
            }
            PlayerClass.ARCHER -> {
                // Quiver rides on the back-facing side of the hip instead of the hand.
                val quiver = Geometry("Quiver", Cylinder(6, 6, 0.07f, 0.30f, true)).apply {
                    material = accentMat
                    rotate(FastMath.QUARTER_PI * 0.5f, 0f, 0f)
                    setLocalTranslation(0f, hipY + 0.25f, -0.16f)
                }
                root.attachChild(quiver)
            }
        }
        shoulderL.attachChild(offhandPivot)

        root.attachChild(torso)
        root.attachChild(head)
        root.attachChild(hipL)
        root.attachChild(hipR)
        root.attachChild(shoulderL)
        root.attachChild(shoulderR)
    }

    private data class Palette(
        val torsoColor: ColorRGBA, val torsoAmbient: ColorRGBA,
        val pantsColor: ColorRGBA, val pantsAmbient: ColorRGBA,
        val accentColor: ColorRGBA, val accentAmbient: ColorRGBA,
    )

    private fun limb(name: String, hw: Float, hh: Float, hd: Float, mat: Material, pivot: Node, length: Float): Geometry {
        val geom = Geometry(name, Box(hw, hh, hd)).apply {
            material = mat
            setLocalTranslation(0f, -length / 2f, 0f)
        }
        pivot.attachChild(geom)
        return geom
    }

    // ── Animation ─────────────────────────────────────────────────────────

    private var walkClock = 0f
    private var idleClock = 0f
    private var hurtFlashAmount = 0f

    /**
     * Drives the walk/idle/attack/block/dash/hurt poses. Called once per
     * frame from [DungeonGame.simpleUpdate].
     *
     * @param moving      true while the player is actively translating
     * @param speedScale  0..~1.5, how fast relative to normal walk speed (drives stride rate)
     * @param attack01    0..1 progress through an attack, or null when not attacking
     * @param dash01      0..1 progress through a dash/blink, or null when not dashing
     * @param blocking    true while the defensive button is held (hold-to-block classes only)
     * @param hurtPulse   set to true for one frame when the boss just landed a hit -- triggers
     *                    a flinch pose and a brief red damage flash
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
        if (hurtPulse) hurtFlashAmount = 1f
        hurtFlashAmount = (hurtFlashAmount - tpf * 2.6f).coerceAtLeast(0f)

        // Walk cycle: opposite-phase arm/leg swing, amplitude scaled by speed.
        if (moving) {
            walkClock += tpf * (6f + 4f * speedScale)
            idleClock = 0f
        } else {
            walkClock = FastMath.interpolateLinear(0.08f, walkClock, 0f)
            idleClock += tpf
        }
        val stride = FastMath.sin(walkClock) * (if (moving) 0.55f else 0f)
        hipL.localRotation = Quaternion().fromAngleAxis(stride, Vector3f.UNIT_X)
        hipR.localRotation = Quaternion().fromAngleAxis(-stride, Vector3f.UNIT_X)

        // Left arm swings with the walk cycle unless it's busy blocking.
        val leftArmSwing = if (blocking) 0f else -stride * 0.8f

        // Right arm: walk swing, overridden by the class's attack pose while one is active.
        val rightArmSwing = when {
            attack01 != null -> attackPoseAngle(attack01)
            else -> stride * 0.8f
        }
        shoulderR.localRotation = Quaternion().fromAngleAxis(rightArmSwing, Vector3f.UNIT_X)
        shoulderL.localRotation = Quaternion().fromAngleAxis(leftArmSwing, Vector3f.UNIT_X)

        // Off-hand item: lerp its pivot up into a raised "block"/"cast" pose, and tint it
        // to a cool color while active so it reads clearly at a glance.
        blockAmount = FastMath.interpolateLinear(0.15f, blockAmount, if (blocking) 1f else 0f)
        offhandPivot.localRotation = Quaternion().fromAngleAxis(-blockAmount * 1.3f, Vector3f.UNIT_X)
        offhandMat.setColor("Diffuse", idleOffhandColor.clone().interpolateLocal(blockOffhandColor, blockAmount))

        // Hurt flash: torso flashes red and the whole body flinches backward.
        torsoMat.setColor("Diffuse", idleTorsoColor.clone().interpolateLocal(hurtFlashColor, hurtFlashAmount))
        val flinchLean = hurtFlashAmount * 0.35f

        // Idle breathing bob (subtle, only when standing still and not mid-action).
        val idleBob = if (!moving && attack01 == null && dash01 == null) FastMath.sin(idleClock * 2.2f) * 0.015f else 0f
        root.setLocalTranslation(0f, idleBob, 0f)
        root.localRotation = Quaternion().fromAngleAxis(flinchLean, Vector3f.UNIT_X)

        // Dash/blink: quick squash-and-stretch for a snappy "burst" feel.
        val dashScale = if (dash01 != null) {
            1f + FastMath.sin(dash01 * FastMath.PI) * 0.22f
        } else 1f
        root.setLocalScale(1f, 1f / dashScale, dashScale)
    }

    /** Per-class attack arm arc, so a melee slash, a staff cast, and a bow
     *  draw-and-release all read as distinct actions. */
    private fun attackPoseAngle(t: Float): Float = when (playerClass) {
        PlayerClass.WARRIOR ->
            // Wind up, then slam down through the target -- a two-lobe swing arc.
            if (t < 0.35f) FastMath.interpolateLinear(t / 0.35f, 0f, -1.9f)
            else FastMath.interpolateLinear((t - 0.35f) / 0.65f, -1.9f, 1.1f)
        PlayerClass.MAGE ->
            // Raise the staff, then thrust it forward as the bolt is released.
            if (t < 0.45f) FastMath.interpolateLinear(t / 0.45f, 0f, -1.3f)
            else FastMath.interpolateLinear((t - 0.45f) / 0.55f, -1.3f, -0.2f)
        PlayerClass.ARCHER ->
            // Draw back, hold briefly, then snap forward on release.
            if (t < 0.55f) FastMath.interpolateLinear(t / 0.55f, 0f, 1.4f)
            else FastMath.interpolateLinear((t - 0.55f) / 0.45f, 1.4f, -0.3f)
    }

    private var blockAmount = 0f
}
