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
 */
class Humanoid(assetManager: AssetManager) {

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
    private val shieldPivot = Node("ShieldPivot")

    private lateinit var shieldMat: Material
    private val idleShieldColor = ColorRGBA(0.32f, 0.24f, 0.14f, 1f)
    private val blockShieldColor = ColorRGBA(0.25f, 0.55f, 0.85f, 1f)

    init {
        fun mat(diffuse: ColorRGBA, ambient: ColorRGBA, shininess: Float = 12f) =
            Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse", diffuse)
                setColor("Ambient", ambient)
                setColor("Specular", ColorRGBA(0.6f, 0.6f, 0.6f, 1f))
                setFloat("Shininess", shininess)
            }

        val skinMat  = mat(ColorRGBA(0.85f, 0.67f, 0.53f, 1f), ColorRGBA(0.25f, 0.19f, 0.15f, 1f))
        val tunicMat = mat(ColorRGBA(0.18f, 0.42f, 0.26f, 1f), ColorRGBA(0.05f, 0.12f, 0.08f, 1f))
        val pantsMat = mat(ColorRGBA(0.32f, 0.26f, 0.20f, 1f), ColorRGBA(0.08f, 0.07f, 0.06f, 1f))
        val bootMat  = mat(ColorRGBA(0.16f, 0.11f, 0.08f, 1f), ColorRGBA(0.04f, 0.03f, 0.02f, 1f))
        val bladeMat = mat(ColorRGBA(0.72f, 0.74f, 0.78f, 1f), ColorRGBA(0.20f, 0.20f, 0.22f, 1f), 64f)
        val hiltMat  = mat(ColorRGBA(0.30f, 0.20f, 0.10f, 1f), ColorRGBA(0.08f, 0.05f, 0.03f, 1f))
        shieldMat    = mat(idleShieldColor, ColorRGBA(0.08f, 0.10f, 0.12f, 1f))

        // Torso
        val torso = Geometry("Torso", Box(0.17f, torsoHeight / 2f, 0.11f)).apply {
            material = tunicMat
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
        limb("ArmL", 0.09f, armLength / 2f, 0.09f, tunicMat, shoulderL, armLength)
        limb("ArmR", 0.09f, armLength / 2f, 0.09f, tunicMat, shoulderR, armLength)

        // Sword, held in the right hand (end of the right arm)
        val hand = Node("Hand").apply { setLocalTranslation(0f, -armLength, 0f) }
        shoulderR.attachChild(hand)
        val bladeGeom = Geometry("SwordBlade", Box(0.03f, 0.32f, 0.05f)).apply {
            material = bladeMat
            setLocalTranslation(0f, -0.30f, 0f)
        }
        val hiltGeom = Geometry("SwordHilt", Box(0.05f, 0.06f, 0.05f)).apply {
            material = hiltMat
            setLocalTranslation(0f, 0.02f, 0f)
        }
        hand.attachChild(bladeGeom)
        hand.attachChild(hiltGeom)

        // Shield, on the left arm -- pivots independently so it can swing up
        // into a "block" pose without the whole arm following.
        shieldPivot.setLocalTranslation(0f, -armLength * 0.6f, 0f)
        val shieldGeom = Geometry("Shield", Box(0.05f, 0.22f, 0.16f)).apply {
            material = shieldMat
            setLocalTranslation(-0.10f, 0f, 0f)
        }
        shieldPivot.attachChild(shieldGeom)
        shoulderL.attachChild(shieldPivot)

        root.attachChild(torso)
        root.attachChild(head)
        root.attachChild(hipL)
        root.attachChild(hipR)
        root.attachChild(shoulderL)
        root.attachChild(shoulderR)
    }

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

    /**
     * Drives the walk/idle/attack/block/dash poses. Called once per frame
     * from [DungeonGame.simpleUpdate].
     *
     * @param moving      true while the player is actively translating
     * @param speedScale  0..~1.5, how fast relative to normal walk speed (drives stride rate)
     * @param attack01    0..1 progress through a sword swing, or null when not attacking
     * @param dash01      0..1 progress through a dash, or null when not dashing
     * @param blocking    true while the shield button is held
     */
    fun update(tpf: Float, moving: Boolean, speedScale: Float, attack01: Float?, dash01: Float?, blocking: Boolean) {
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

        // Right arm: walk swing, overridden by the attack swing while one is active.
        val rightArmSwing = when {
            attack01 != null -> {
                // Wind up, then slam down through the target -- a simple two-lobe arc.
                val t = attack01
                if (t < 0.35f) FastMath.interpolateLinear(t / 0.35f, 0f, -1.9f)
                else FastMath.interpolateLinear((t - 0.35f) / 0.65f, -1.9f, 1.1f)
            }
            else -> stride * 0.8f
        }
        shoulderR.localRotation = Quaternion().fromAngleAxis(rightArmSwing, Vector3f.UNIT_X)
        shoulderL.localRotation = Quaternion().fromAngleAxis(leftArmSwing, Vector3f.UNIT_X)

        // Shield: lerp its pivot up into a raised "block" pose, and tint it
        // to a cool color while active so it reads clearly at a glance.
        blockAmount = FastMath.interpolateLinear(0.15f, blockAmount, if (blocking) 1f else 0f)
        shieldPivot.localRotation = Quaternion().fromAngleAxis(-blockAmount * 1.3f, Vector3f.UNIT_X)
        shieldMat.setColor("Diffuse", idleShieldColor.clone().interpolateLocal(blockShieldColor, blockAmount))

        // Idle breathing bob (subtle, only when standing still and not mid-action).
        val idleBob = if (!moving && attack01 == null && dash01 == null) FastMath.sin(idleClock * 2.2f) * 0.015f else 0f
        root.setLocalTranslation(0f, idleBob, 0f)

        // Dash: quick squash-and-stretch for a snappy "burst" feel.
        val dashScale = if (dash01 != null) {
            1f + FastMath.sin(dash01 * FastMath.PI) * 0.22f
        } else 1f
        root.setLocalScale(1f, 1f / dashScale, dashScale)
    }

    private var blockAmount = 0f
}
