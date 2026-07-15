package com.example.dungeon

import com.jme3.app.SimpleApplication
import com.jme3.bullet.BulletAppState
import com.jme3.bullet.control.RigidBodyControl
import com.jme3.collision.CollisionResults
import com.jme3.input.MouseInput
import com.jme3.input.controls.ActionListener
import com.jme3.input.controls.MouseButtonTrigger
import com.jme3.light.AmbientLight
import com.jme3.light.DirectionalLight
import com.jme3.light.PointLight
import com.jme3.material.Material
import com.jme3.math.ColorRGBA
import com.jme3.math.FastMath
import com.jme3.math.Quaternion
import com.jme3.math.Ray
import com.jme3.math.Vector3f
import com.jme3.scene.Geometry
import com.jme3.scene.Node
import com.jme3.scene.shape.Box
import com.jme3.scene.shape.Quad

// Platform-specific entry points (desktop/android modules) construct and
// start DungeonGame themselves — this class has no dependency on any
// particular renderer/context, so it works unmodified on both.

// ── Constants ──────────────────────────────────────────────────────────────────

private const val MOVE_SPEED   = 6f    // world-units per second
private const val CAM_HEIGHT   = 18f   // camera Y above the ground
private const val CAM_DISTANCE = 12f   // camera Z behind the look-at point
private const val CAM_PITCH    = -55f  // tilt angle in degrees (negative = look down)

private const val JOYSTICK_DEADZONE  = 0.15f
private const val SHIELD_SLOW_FACTOR = 0.45f  // movement speed multiplier while blocking

private const val ATTACK_DURATION = 0.42f  // seconds, sword swing
private const val ATTACK_COOLDOWN = 0.18f  // seconds after a swing before another can start

private const val DASH_DURATION  = 0.22f   // seconds
private const val DASH_COOLDOWN  = 0.55f   // seconds after a dash before another can start
private const val DASH_SPEED     = 20f     // world-units per second while dashing

// ── Main application ───────────────────────────────────────────────────────────

class DungeonGame : SimpleApplication() {

    // Physics
    private lateinit var bulletState: BulletAppState

    // Scene objects
    private lateinit var playerNode:   Node
    private lateinit var humanoid:     Humanoid
    private lateinit var groundGeom:   Geometry
    private val wallGeoms = mutableListOf<Geometry>()

    // Click-to-move state (desktop mouse fallback; joystick input takes
    // priority over this when the player is actively steering it)
    private var targetPosition: Vector3f? = null

    // Facing direction, updated whenever the player actually moves. Used so
    // dashing/attacking have a direction to work with even the instant the
    // player stops pushing the joystick.
    private var facing = Vector3f(0f, 0f, -1f)

    // Combat/movement action state
    private var attackTimer   = 0f  // counts down from ATTACK_DURATION while a swing is playing
    private var attackCooldown = 0f
    private var dashTimer     = 0f  // counts down from DASH_DURATION while dashing
    private var dashCooldown  = 0f
    private var dashDirection = Vector3f(0f, 0f, -1f)

    /**
     * Shared input state. The Android module's on-screen joystick/buttons
     * write into this every frame from the UI thread; desktop currently
     * only uses mouse click-to-move and leaves it untouched.
     */
    val input = PlayerInput()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun simpleInitApp() {
        setupPhysics()
        setupCamera()
        setupLighting()
        setupGround()
        setupWalls()
        setupPlayer()
        setupInput()
    }

    // ── Physics ────────────────────────────────────────────────────────────────

    private fun setupPhysics() {
        bulletState = BulletAppState()
        stateManager.attach(bulletState)
        // bulletState.debugEnabled = true  // uncomment to see collision shapes
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun setupCamera() {
        // Disable the default free-fly camera; we drive it ourselves in simpleUpdate
        flyCam.isEnabled = false

        // Position will be set each frame relative to the player (see simpleUpdate)
        cam.location  = Vector3f(0f, CAM_HEIGHT, CAM_DISTANCE)
        cam.lookAt(Vector3f(0f, 0f, 0f), Vector3f.UNIT_Y)
    }

    // ── Lighting ───────────────────────────────────────────────────────────────

    private fun setupLighting() {
        // Main directional light (sun/ceiling diffuse)
        val sun = DirectionalLight().apply {
            direction = Vector3f(-0.4f, -1f, -0.6f).normalizeLocal()
            color     = ColorRGBA(0.8f, 0.75f, 0.65f, 1f)
        }
        rootNode.addLight(sun)

        // Low ambient so shadowed corners aren't pitch black
        val ambient = AmbientLight().apply {
            color = ColorRGBA(0.25f, 0.22f, 0.28f, 1f)
        }
        rootNode.addLight(ambient)

        // Torch-style point light near the player spawn
        val torch = PointLight().apply {
            color    = ColorRGBA(1.0f, 0.65f, 0.2f, 1f)
            radius   = 14f
            position = Vector3f(0f, 3f, 0f)
        }
        rootNode.addLight(torch)
    }

    // ── Ground ─────────────────────────────────────────────────────────────────

    private fun setupGround() {
        // A 40×40 flat quad — click target for navigation
        val mesh = Quad(40f, 40f)
        groundGeom = Geometry("Ground", mesh).apply {
            val mat = Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse", ColorRGBA(0.28f, 0.25f, 0.22f, 1f))
                setColor("Ambient", ColorRGBA(0.15f, 0.13f, 0.12f, 1f))
            }
            material = mat
            // Rotate the XY quad to lie flat on the XZ plane
            rotate(-Math.PI.toFloat() / 2f, 0f, 0f)
            setLocalTranslation(-20f, 0f, 20f)
        }
        rootNode.attachChild(groundGeom)

        // Static physics body so bullets/future objects interact with the floor
        val groundPhysics = RigidBodyControl(0f)   // mass 0 = static
        groundGeom.addControl(groundPhysics)
        bulletState.physicsSpace.add(groundPhysics)
    }

    // ── Walls ──────────────────────────────────────────────────────────────────
    //
    //  Simple box-wall dungeon room. Swap these out for imported tile meshes later.

    private fun setupWalls() {
        // Format: centerX, centerY, centerZ, halfW, halfH, halfD
        val wallDefs = listOf(
            // North wall
            floatArrayOf( 0f, 1.5f, -10f,  10f, 1.5f, 0.5f),
            // South wall
            floatArrayOf( 0f, 1.5f,  10f,  10f, 1.5f, 0.5f),
            // West wall
            floatArrayOf(-10f, 1.5f, 0f,  0.5f, 1.5f, 10f),
            // East wall
            floatArrayOf( 10f, 1.5f, 0f,  0.5f, 1.5f, 10f),
        )

        val wallMat = Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
            setBoolean("UseMaterialColors", true)
            setColor("Diffuse", ColorRGBA(0.35f, 0.30f, 0.25f, 1f))
            setColor("Ambient", ColorRGBA(0.10f, 0.09f, 0.08f, 1f))
        }

        for (def in wallDefs) {
            val (cx, cy, cz, hw, hh, hd) = def
            val geom = Geometry("Wall", Box(hw, hh, hd)).apply {
                material = wallMat
                setLocalTranslation(cx, cy, cz)
            }
            rootNode.attachChild(geom)
            wallGeoms += geom

            val physics = RigidBodyControl(0f)
            geom.addControl(physics)
            bulletState.physicsSpace.add(physics)
        }
    }

    // ── Player ─────────────────────────────────────────────────────────────────

    private fun setupPlayer() {
        humanoid = Humanoid(assetManager)

        playerNode = Node("PlayerNode")
        playerNode.attachChild(humanoid.root)
        playerNode.setLocalTranslation(0f, 0f, 0f)   // feet at ground level
        rootNode.attachChild(playerNode)
    }

    // ── Input ──────────────────────────────────────────────────────────────────

    private fun setupInput() {
        inputManager.addMapping("ClickMove", MouseButtonTrigger(MouseInput.BUTTON_LEFT))
        inputManager.addListener(clickListener, "ClickMove")
    }

    private val clickListener = ActionListener { name, isPressed, _ ->
        if (name == "ClickMove" && isPressed) handleClickMove()
    }

    private fun handleClickMove() {
        val cursor  = inputManager.cursorPosition
        val near    = cam.getWorldCoordinates(cursor, 0f)
        val far     = cam.getWorldCoordinates(cursor, 1f)
        val dir     = far.subtract(near).normalizeLocal()

        val results = CollisionResults()
        rootNode.collideWith(Ray(near, dir), results)

        for (i in 0 until results.size()) {
            val hit = results.getCollision(i)
            if (hit.geometry.name == "Ground") {
                // Player's origin sits at its feet, flush with the floor
                targetPosition = Vector3f(hit.contactPoint.x, 0f, hit.contactPoint.z)
                break
            }
        }
    }

    // ── Update loop ────────────────────────────────────────────────────────────

    override fun simpleUpdate(tpf: Float) {
        updateActionTimers(tpf)
        movePlayer(tpf)
        followCamera()
        updateHumanoid(tpf)
    }

    /** Advances attack/dash timers and consumes one-shot button presses queued by the UI. */
    private fun updateActionTimers(tpf: Float) {
        if (attackTimer > 0f) attackTimer = (attackTimer - tpf).coerceAtLeast(0f)
        if (attackCooldown > 0f) attackCooldown = (attackCooldown - tpf).coerceAtLeast(0f)
        if (dashTimer > 0f) dashTimer = (dashTimer - tpf).coerceAtLeast(0f)
        if (dashCooldown > 0f) dashCooldown = (dashCooldown - tpf).coerceAtLeast(0f)

        if (input.swordQueued) {
            input.swordQueued = false
            if (attackCooldown <= 0f) {
                attackTimer = ATTACK_DURATION
                attackCooldown = ATTACK_DURATION + ATTACK_COOLDOWN
            }
        }
        if (input.dashQueued) {
            input.dashQueued = false
            if (dashCooldown <= 0f) {
                dashTimer = DASH_DURATION
                dashCooldown = DASH_DURATION + DASH_COOLDOWN
                dashDirection = facing.clone()
            }
        }
    }

    private fun movePlayer(tpf: Float) {
        val joystickVec = Vector3f(input.moveX, 0f, -input.moveY)
        val joystickMag = joystickVec.length()

        val moveDir: Vector3f
        val speed: Float

        when {
            dashTimer > 0f -> {
                moveDir = dashDirection
                speed = DASH_SPEED
            }
            joystickMag > JOYSTICK_DEADZONE -> {
                targetPosition = null // joystick overrides desktop click-to-move
                moveDir = joystickVec.normalize()
                speed = MOVE_SPEED * joystickMag.coerceAtMost(1f)
            }
            targetPosition != null -> {
                val toTarget = targetPosition!!.subtract(playerNode.localTranslation).also { it.y = 0f }
                if (toTarget.length() < 0.08f) {
                    targetPosition = null
                    lastMoveDistance = 0f
                    return
                }
                moveDir = toTarget.normalize()
                speed = MOVE_SPEED
            }
            else -> {
                lastMoveDistance = 0f
                return
            }
        }

        val slow = if (input.shieldHeld) SHIELD_SLOW_FACTOR else 1f
        val step = moveDir.mult(speed * slow * tpf)
        playerNode.move(step)
        lastMoveDistance = step.length()

        if (dashTimer <= 0f && moveDir.lengthSquared() > 0.0001f) {
            facing = moveDir.clone()
        }
        playerNode.localRotation = Quaternion().lookAt(facing, Vector3f.UNIT_Y)
    }

    private var lastMoveDistance = 0f

    private fun updateHumanoid(tpf: Float) {
        val moving = lastMoveDistance > 0.0001f
        val speedScale = (lastMoveDistance / (MOVE_SPEED * tpf).coerceAtLeast(0.0001f)).coerceIn(0f, 1.5f)
        val attack01 = if (attackTimer > 0f) 1f - (attackTimer / ATTACK_DURATION) else null
        val dash01 = if (dashTimer > 0f) 1f - (dashTimer / DASH_DURATION) else null
        humanoid.update(tpf, moving, speedScale, attack01, dash01, input.shieldHeld)
    }

    private fun followCamera() {
        // Camera orbits above the player at a fixed isometric angle
        val playerPos = playerNode.localTranslation
        cam.location  = Vector3f(playerPos.x, playerPos.y + CAM_HEIGHT, playerPos.z + CAM_DISTANCE)
        cam.lookAt(playerPos, Vector3f.UNIT_Y)
    }
}

// ── Destructuring helper for wall definitions ──────────────────────────────────

private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
private operator fun FloatArray.component5() = this[4]
private operator fun FloatArray.component6() = this[5]
