package com.example.dungeon

import com.jme3.app.SimpleApplication
import com.jme3.bullet.BulletAppState
import com.jme3.bullet.control.RigidBodyControl
import com.jme3.collision.CollisionResults
import com.jme3.font.BitmapText
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

private const val JOYSTICK_DEADZONE  = 0.15f
private const val SHIELD_SLOW_FACTOR = 0.45f  // movement speed multiplier while blocking

private const val ATTACK_DURATION = 0.42f  // seconds, sword swing
private const val ATTACK_COOLDOWN = 0.18f  // seconds after a swing before another can start

private const val DASH_DURATION  = 0.22f   // seconds
private const val DASH_COOLDOWN  = 0.55f   // seconds after a dash before another can start
private const val DASH_SPEED     = 20f     // world-units per second while dashing

// Combat hit detection
private const val SWORD_HIT_RANGE  = 3.5f  // world units: player to boss centre
private const val PLAYER_MAX_HP    = 5

// HUD geometry (pixels)
private const val BOSS_BAR_W   = 240f
private const val BOSS_BAR_H   = 18f
private const val PLAYER_BAR_W = 150f
private const val PLAYER_BAR_H = 18f
private const val BAR_TOP_PAD  = 40f   // distance from top of screen

// ── Game state ─────────────────────────────────────────────────────────────────

private enum class GameState { PLAYING, WIN, LOSE }

// ── Main application ───────────────────────────────────────────────────────────

class DungeonGame : SimpleApplication() {

    // Physics
    private lateinit var bulletState: BulletAppState

    // Scene objects
    private lateinit var playerNode: Node
    private lateinit var humanoid:   Humanoid
    private lateinit var groundGeom: Geometry
    private val wallGeoms = mutableListOf<Geometry>()

    // Boss
    private lateinit var dragonBoss: DragonBoss

    // Click-to-move state (desktop mouse fallback; joystick takes priority)
    private var targetPosition: Vector3f? = null

    // Facing direction, updated whenever the player actually moves.
    private var facing = Vector3f(0f, 0f, -1f)

    // Combat/movement action state
    private var attackTimer    = 0f
    private var attackCooldown = 0f
    private var dashTimer      = 0f
    private var dashCooldown   = 0f
    private var dashDirection  = Vector3f(0f, 0f, -1f)

    // Whether we already dealt sword damage in the current swing (prevents multi-hit).
    private var attackHitDealt = false

    // Player HP and overall game state
    private var playerHp   = PLAYER_MAX_HP
    private var gameState  = GameState.PLAYING

    // HUD references
    private lateinit var bossHpFill:    Geometry
    private lateinit var playerHpFill:  Geometry
    private lateinit var bossHpText:    BitmapText
    private lateinit var playerHpText:  BitmapText
    private lateinit var statusText:    BitmapText

    /**
     * Shared input state written by the platform's UI controls and read each
     * frame on the GL thread.
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
        setupBoss()
        setupHUD()
        setupInput()
    }

    // ── Physics ────────────────────────────────────────────────────────────────

    private fun setupPhysics() {
        bulletState = BulletAppState()
        stateManager.attach(bulletState)
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun setupCamera() {
        flyCam.isEnabled = false
        cam.location = Vector3f(0f, CAM_HEIGHT, CAM_DISTANCE)
        cam.lookAt(Vector3f(0f, 0f, 0f), Vector3f.UNIT_Y)
    }

    // ── Lighting ───────────────────────────────────────────────────────────────

    private fun setupLighting() {
        val sun = DirectionalLight().apply {
            direction = Vector3f(-0.4f, -1f, -0.6f).normalizeLocal()
            color     = ColorRGBA(0.8f, 0.75f, 0.65f, 1f)
        }
        rootNode.addLight(sun)

        val ambient = AmbientLight().apply {
            color = ColorRGBA(0.25f, 0.22f, 0.28f, 1f)
        }
        rootNode.addLight(ambient)

        val torch = PointLight().apply {
            color    = ColorRGBA(1.0f, 0.65f, 0.2f, 1f)
            radius   = 18f
            position = Vector3f(0f, 3f, 0f)
        }
        rootNode.addLight(torch)

        // A second, cooler light near the boss spawn to give the arena more depth.
        val bossTorch = PointLight().apply {
            color    = ColorRGBA(0.4f, 0.55f, 1.0f, 1f)
            radius   = 14f
            position = Vector3f(0f, 3f, -7f)
        }
        rootNode.addLight(bossTorch)
    }

    // ── Ground ─────────────────────────────────────────────────────────────────

    private fun setupGround() {
        val mesh = Quad(40f, 40f)
        groundGeom = Geometry("Ground", mesh).apply {
            val mat = Material(assetManager, "Common/MatDefs/Light/Lighting.j3md").apply {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse", ColorRGBA(0.28f, 0.25f, 0.22f, 1f))
                setColor("Ambient", ColorRGBA(0.15f, 0.13f, 0.12f, 1f))
            }
            material = mat
            rotate(-Math.PI.toFloat() / 2f, 0f, 0f)
            setLocalTranslation(-20f, 0f, 20f)
        }
        rootNode.attachChild(groundGeom)

        val groundPhysics = RigidBodyControl(0f)
        groundGeom.addControl(groundPhysics)
        bulletState.physicsSpace.add(groundPhysics)
    }

    // ── Walls ──────────────────────────────────────────────────────────────────
    //
    //  Expanded room (28 × 28 instead of 20 × 20) so the boss has room to move.

    private fun setupWalls() {
        val wallDefs = listOf(
            // North wall
            floatArrayOf( 0f, 1.5f, -14f,  14f, 1.5f, 0.5f),
            // South wall
            floatArrayOf( 0f, 1.5f,  14f,  14f, 1.5f, 0.5f),
            // West wall
            floatArrayOf(-14f, 1.5f, 0f,  0.5f, 1.5f, 14f),
            // East wall
            floatArrayOf( 14f, 1.5f, 0f,  0.5f, 1.5f, 14f),
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
        playerNode.setLocalTranslation(0f, 0f, 5f)   // start near south side
        rootNode.attachChild(playerNode)
    }

    // ── Boss ───────────────────────────────────────────────────────────────────

    private fun setupBoss() {
        dragonBoss = DragonBoss(assetManager)
        // Spawn the boss on the north side of the arena, facing the player.
        dragonBoss.node.setLocalTranslation(0f, 0f, -7f)
        rootNode.attachChild(dragonBoss.node)
    }

    // ── HUD ────────────────────────────────────────────────────────────────────

    private fun setupHUD() {
        val sw = cam.width.toFloat()
        val sh = cam.height.toFloat()

        fun barBg(color: ColorRGBA, w: Float, h: Float, x: Float, y: Float): Geometry {
            val g = Geometry("HudBg", Quad(w, h))
            val mat = Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md")
            mat.setColor("Color", color)
            g.material = mat
            g.setLocalTranslation(x, y, 0f)
            guiNode.attachChild(g)
            return g
        }

        fun barFill(color: ColorRGBA, w: Float, h: Float, x: Float, y: Float): Geometry {
            val g = Geometry("HudFill", Quad(w, h))
            val mat = Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md")
            mat.setColor("Color", color)
            g.material = mat
            g.setLocalTranslation(x, y, 1f)  // z=1 renders on top of background
            guiNode.attachChild(g)
            return g
        }

        val font = assetManager.loadFont("Interface/Fonts/Default.fnt")

        // ── Boss HP bar (top-center) ──
        val bossBarX = (sw / 2f) - (BOSS_BAR_W / 2f)
        val bossBarY = sh - BAR_TOP_PAD - BOSS_BAR_H

        barBg(ColorRGBA(0.25f, 0.02f, 0.02f, 0.85f), BOSS_BAR_W, BOSS_BAR_H, bossBarX, bossBarY)
        bossHpFill = barFill(ColorRGBA(0.85f, 0.12f, 0.12f, 1f), BOSS_BAR_W, BOSS_BAR_H, bossBarX, bossBarY)

        bossHpText = BitmapText(font, false).apply {
            size = 15f
            text = "Dragon Boss"
            setLocalTranslation(bossBarX, bossBarY + BOSS_BAR_H + 18f, 2f)
        }
        guiNode.attachChild(bossHpText)

        // ── Player HP bar (top-left) ──
        val playerBarX = 14f
        val playerBarY = sh - BAR_TOP_PAD - PLAYER_BAR_H

        barBg(ColorRGBA(0.05f, 0.20f, 0.05f, 0.85f), PLAYER_BAR_W, PLAYER_BAR_H, playerBarX, playerBarY)
        playerHpFill = barFill(ColorRGBA(0.18f, 0.82f, 0.18f, 1f), PLAYER_BAR_W, PLAYER_BAR_H, playerBarX, playerBarY)

        playerHpText = BitmapText(font, false).apply {
            size = 15f
            text = "Hero"
            setLocalTranslation(playerBarX, playerBarY + PLAYER_BAR_H + 18f, 2f)
        }
        guiNode.attachChild(playerHpText)

        // ── Win / Lose overlay (hidden until the game ends) ──
        statusText = BitmapText(font, false).apply {
            size = 52f
            text = ""
            setLocalTranslation(sw / 2f - 160f, sh / 2f + 26f, 3f)
        }
        guiNode.attachChild(statusText)
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
        val cursor = inputManager.cursorPosition
        val near   = cam.getWorldCoordinates(cursor, 0f)
        val far    = cam.getWorldCoordinates(cursor, 1f)
        val dir    = far.subtract(near).normalizeLocal()

        val results = CollisionResults()
        rootNode.collideWith(Ray(near, dir), results)

        for (i in 0 until results.size()) {
            val hit = results.getCollision(i)
            if (hit.geometry.name == "Ground") {
                targetPosition = Vector3f(hit.contactPoint.x, 0f, hit.contactPoint.z)
                break
            }
        }
    }

    // ── Update loop ────────────────────────────────────────────────────────────

    override fun simpleUpdate(tpf: Float) {
        if (gameState != GameState.PLAYING) return

        updateActionTimers(tpf)
        movePlayer(tpf)
        followCamera()
        updateHumanoid(tpf)
        updateBoss(tpf)
        checkPlayerSwordHit()
        updateHUD()
    }

    /** Advances attack/dash timers and consumes one-shot button presses. */
    private fun updateActionTimers(tpf: Float) {
        if (attackTimer > 0f)    attackTimer    = (attackTimer    - tpf).coerceAtLeast(0f)
        if (attackCooldown > 0f) attackCooldown = (attackCooldown - tpf).coerceAtLeast(0f)
        if (dashTimer > 0f)      dashTimer      = (dashTimer      - tpf).coerceAtLeast(0f)
        if (dashCooldown > 0f)   dashCooldown   = (dashCooldown   - tpf).coerceAtLeast(0f)

        if (input.swordQueued) {
            input.swordQueued = false
            if (attackCooldown <= 0f) {
                attackTimer    = ATTACK_DURATION
                attackCooldown = ATTACK_DURATION + ATTACK_COOLDOWN
                attackHitDealt = false  // new swing — allow one fresh hit
            }
        }
        if (input.dashQueued) {
            input.dashQueued = false
            if (dashCooldown <= 0f) {
                dashTimer     = DASH_DURATION
                dashCooldown  = DASH_DURATION + DASH_COOLDOWN
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
                speed   = DASH_SPEED
            }
            joystickMag > JOYSTICK_DEADZONE -> {
                targetPosition = null
                moveDir        = joystickVec.normalize()
                speed          = MOVE_SPEED * joystickMag.coerceAtMost(1f)
            }
            targetPosition != null -> {
                val toTarget = targetPosition!!.subtract(playerNode.localTranslation).also { it.y = 0f }
                if (toTarget.length() < 0.08f) {
                    targetPosition   = null
                    lastMoveDistance = 0f
                    return
                }
                moveDir = toTarget.normalize()
                speed   = MOVE_SPEED
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
        val moving     = lastMoveDistance > 0.0001f
        val speedScale = (lastMoveDistance / (MOVE_SPEED * tpf).coerceAtLeast(0.0001f)).coerceIn(0f, 1.5f)
        val attack01   = if (attackTimer > 0f) 1f - (attackTimer / ATTACK_DURATION) else null
        val dash01     = if (dashTimer   > 0f) 1f - (dashTimer   / DASH_DURATION)   else null
        humanoid.update(tpf, moving, speedScale, attack01, dash01, input.shieldHeld)
    }

    // ── Boss update ────────────────────────────────────────────────────────────

    private fun updateBoss(tpf: Float) {
        val bossHitPlayer = dragonBoss.update(tpf, playerNode.localTranslation)

        if (bossHitPlayer) {
            // Shield blocks boss damage entirely.
            if (!input.shieldHeld) {
                playerHp = (playerHp - 1).coerceAtLeast(0)
                if (playerHp == 0) triggerGameOver(false)
            }
        }

        if (dragonBoss.state == DragonBoss.State.DEAD && gameState == GameState.PLAYING) {
            triggerGameOver(true)
        }
    }

    // ── Sword hit detection ────────────────────────────────────────────────────

    private fun checkPlayerSwordHit() {
        if (attackTimer <= 0f || attackHitDealt) return
        // Active hit window: first 60 % of the swing animation.
        val swingProgress = 1f - (attackTimer / ATTACK_DURATION)
        if (swingProgress > 0.60f) return

        val dist = playerNode.localTranslation.distance(dragonBoss.node.localTranslation)
        if (dist <= SWORD_HIT_RANGE) {
            attackHitDealt = true
            dragonBoss.takeDamage()
        }
    }

    // ── Win / Lose ─────────────────────────────────────────────────────────────

    private fun triggerGameOver(won: Boolean) {
        gameState = if (won) GameState.WIN else GameState.LOSE
        statusText.text = if (won) "YOU WIN!" else "YOU LOSE"
        statusText.color = if (won) ColorRGBA(0.2f, 1f, 0.3f, 1f) else ColorRGBA(1f, 0.2f, 0.2f, 1f)
    }

    // ── HUD update ─────────────────────────────────────────────────────────────

    private fun updateHUD() {
        // Scale the fill bars on X from [0..1] representing current HP ratio.
        // Quad origin is at bottom-left so X-scaling shrinks from the right. ✓
        val bossRatio   = dragonBoss.hp.toFloat() / dragonBoss.maxHp.toFloat()
        val playerRatio = playerHp.toFloat() / PLAYER_MAX_HP.toFloat()

        bossHpFill.setLocalScale(bossRatio.coerceIn(0f, 1f), 1f, 1f)
        playerHpFill.setLocalScale(playerRatio.coerceIn(0f, 1f), 1f, 1f)

        bossHpText.text   = "Dragon Boss  ${dragonBoss.hp}/${dragonBoss.maxHp}"
        playerHpText.text = "Hero  $playerHp/$PLAYER_MAX_HP"
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun followCamera() {
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
