package com.example.dungeon

import com.jme3.app.SimpleApplication
import com.jme3.collision.CollisionResults
import com.jme3.font.BitmapFont
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

// ── Constants ──────────────────────────────────────────────────────────────────

private const val MOVE_SPEED        = 6f
private const val CAM_HEIGHT        = 22f
private const val CAM_DISTANCE      = 14f

private const val JOYSTICK_DEADZONE  = 0.15f
private const val SHIELD_SLOW_FACTOR = 0.45f

private const val ATTACK_DURATION  = 0.42f
private const val ATTACK_COOLDOWN  = 0.18f

private const val DASH_DURATION    = 0.22f
private const val DASH_COOLDOWN    = 0.55f
private const val DASH_SPEED       = 20f

private const val SWORD_HIT_RANGE  = 4.0f
private const val PLAYER_MAX_HP    = 5

// Arena half-extents — player is clamped inside this box.
private const val ARENA_HALF       = 13.5f

// HUD
private const val BOSS_BAR_W   = 240f
private const val BOSS_BAR_H   = 18f
private const val PLAYER_BAR_W = 150f
private const val PLAYER_BAR_H = 18f
private const val BAR_TOP_PAD  = 44f

// ── Game state ─────────────────────────────────────────────────────────────────

enum class GameState { PLAYING, WIN, LOSE }

// ── Floating damage label ──────────────────────────────────────────────────────

private data class FloatNum(
    val text:    BitmapText,
    val color:   ColorRGBA,
    var screenX: Float,
    var screenY: Float,
    var life:    Float,
    val maxLife: Float = 1.2f
)

// ── DungeonGame ────────────────────────────────────────────────────────────────

class DungeonGame : SimpleApplication() {

    // Scene
    private lateinit var playerNode: Node
    private lateinit var humanoid:   Humanoid

    // Boss
    private lateinit var dragonBoss: DragonBoss
    private val bossSpawnPos = Vector3f(0f, 0f, -7f)

    // Player movement
    private var targetPosition:    Vector3f? = null
    private var facing           = Vector3f(0f, 0f, -1f)
    private var lastMoveDistance = 0f

    // Timers
    private var attackTimer    = 0f
    private var attackCooldown = 0f
    private var dashTimer      = 0f
    private var dashCooldown   = 0f
    private var dashDirection  = Vector3f(0f, 0f, -1f)
    private var attackHitDealt = false

    // HP / state
    private var playerHp = PLAYER_MAX_HP
    var gameState: GameState = GameState.PLAYING
        private set

    /** Fired on the GL thread when the game ends; wire up from MainActivity. */
    var onGameOver: ((won: Boolean) -> Unit)? = null

    // HUD
    private lateinit var hudFont:      BitmapFont
    private lateinit var bossHpFill:   Geometry
    private lateinit var playerHpFill: Geometry
    private lateinit var bossHpText:   BitmapText
    private lateinit var playerHpText: BitmapText
    private lateinit var statusText:   BitmapText
    private lateinit var restartHint:  BitmapText
    private var lastPlayerHp = -1
    private var lastBossHp   = -1

    // Floating numbers
    private val floatNums = mutableListOf<FloatNum>()

    /** Written by the Android UI / desktop input layer. */
    val input = PlayerInput()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun simpleInitApp() {
        setupCamera()
        setupLighting()
        setupGround()
        setupWalls()
        setupPlayer()
        setupBoss()
        setupHUD()
        setupMouseInput()   // best-effort; silently skipped on Android
    }

    // ── Setup ──────────────────────────────────────────────────────────────────

    private fun setupCamera() {
        flyCam.isEnabled = false
        cam.location = Vector3f(0f, CAM_HEIGHT, CAM_DISTANCE)
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y)
    }

    private fun setupLighting() {
        rootNode.addLight(DirectionalLight().apply {
            direction = Vector3f(-0.4f, -1f, -0.6f).normalizeLocal()
            color     = ColorRGBA(0.85f, 0.80f, 0.70f, 1f)
        })
        rootNode.addLight(AmbientLight().apply {
            color = ColorRGBA(0.28f, 0.25f, 0.30f, 1f)
        })
        rootNode.addLight(PointLight().apply {
            color    = ColorRGBA(1.0f, 0.65f, 0.20f, 1f)
            radius   = 18f
            position = Vector3f(0f, 3f, 5f)
        })
        rootNode.addLight(PointLight().apply {
            color    = ColorRGBA(0.35f, 0.50f, 1.0f, 1f)
            radius   = 16f
            position = Vector3f(0f, 4f, -7f)
        })
    }

    private fun setupGround() {
        val g = Geometry("Ground", Quad(40f, 40f)).apply {
            material = mat("Common/MatDefs/Light/Lighting.j3md") {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse", ColorRGBA(0.28f, 0.25f, 0.22f, 1f))
                setColor("Ambient", ColorRGBA(0.15f, 0.13f, 0.12f, 1f))
            }
            rotate(-FastMath.HALF_PI, 0f, 0f)
            setLocalTranslation(-20f, 0f, 20f)
        }
        rootNode.attachChild(g)
    }

    private fun setupWalls() {
        val wallMat = mat("Common/MatDefs/Light/Lighting.j3md") {
            setBoolean("UseMaterialColors", true)
            setColor("Diffuse", ColorRGBA(0.35f, 0.30f, 0.25f, 1f))
            setColor("Ambient", ColorRGBA(0.10f, 0.09f, 0.08f, 1f))
        }
        // [centerX, centerY, centerZ, halfW, halfH, halfD]
        listOf(
            floatArrayOf( 0f,  1.5f, -14f,  14f, 1.5f, 0.5f),
            floatArrayOf( 0f,  1.5f,  14f,  14f, 1.5f, 0.5f),
            floatArrayOf(-14f, 1.5f,   0f, 0.5f, 1.5f,  14f),
            floatArrayOf( 14f, 1.5f,   0f, 0.5f, 1.5f,  14f),
        ).forEach { (cx, cy, cz, hw, hh, hd) ->
            rootNode.attachChild(Geometry("Wall", Box(hw, hh, hd)).apply {
                material = wallMat
                setLocalTranslation(cx, cy, cz)
            })
        }
    }

    private fun setupPlayer() {
        humanoid   = Humanoid(assetManager)
        playerNode = Node("PlayerNode").apply { setLocalTranslation(0f, 0f, 5f) }
        playerNode.attachChild(humanoid.root)
        rootNode.attachChild(playerNode)
    }

    private fun setupBoss() {
        dragonBoss = DragonBoss(assetManager)
        dragonBoss.node.setLocalTranslation(bossSpawnPos.clone())
        rootNode.attachChild(dragonBoss.node)
    }

    private fun setupHUD() {
        val sw = cam.width.toFloat()
        val sh = cam.height.toFloat()
        hudFont = assetManager.loadFont("Interface/Fonts/Default.fnt")

        // Boss HP bar — top-center
        val bx = sw / 2f - BOSS_BAR_W / 2f
        val by = sh - BAR_TOP_PAD - BOSS_BAR_H
        guiNode.attachChild(hpBarBg(BOSS_BAR_W, BOSS_BAR_H, bx, by,
            ColorRGBA(0.28f, 0.02f, 0.02f, 0.85f)))
        bossHpFill = hpBarFill(BOSS_BAR_W, BOSS_BAR_H, bx, by,
            ColorRGBA(0.85f, 0.12f, 0.12f, 1f))
        bossHpText = hudText("Dragon Boss", 15f, bx, by + BOSS_BAR_H + 18f)

        // Player HP bar — top-left
        val px = 14f
        val py = sh - BAR_TOP_PAD - PLAYER_BAR_H
        guiNode.attachChild(hpBarBg(PLAYER_BAR_W, PLAYER_BAR_H, px, py,
            ColorRGBA(0.05f, 0.22f, 0.05f, 0.85f)))
        playerHpFill = hpBarFill(PLAYER_BAR_W, PLAYER_BAR_H, px, py,
            ColorRGBA(0.18f, 0.82f, 0.18f, 1f))
        playerHpText = hudText("Hero", 15f, px, py + PLAYER_BAR_H + 18f)

        // End-game overlay
        statusText  = hudText("", 52f, sw / 2f - 170f, sh / 2f + 30f)
        restartHint = hudText("", 22f, sw / 2f - 130f, sh / 2f - 20f)
    }

    /**
     * Register a desktop click-to-move mapping.
     * Wrapped in try/catch because on Android the mouse input backend is absent
     * and InputManager may throw when a MouseButtonTrigger is registered.
     */
    private fun setupMouseInput() {
        try {
            inputManager.addMapping("Tap", MouseButtonTrigger(MouseInput.BUTTON_LEFT))
            inputManager.addListener(tapListener, "Tap")
        } catch (_: Exception) { /* Android — no mouse, no-op */ }
    }

    // ── Input ──────────────────────────────────────────────────────────────────

    private val tapListener = ActionListener { _, isPressed, _ ->
        if (!isPressed) return@ActionListener
        when (gameState) {
            GameState.PLAYING          -> handleClickMove()
            GameState.WIN, GameState.LOSE -> restartGame()
        }
    }

    private fun handleClickMove() {
        val cursor  = inputManager.cursorPosition
        val near    = cam.getWorldCoordinates(cursor, 0f)
        val far     = cam.getWorldCoordinates(cursor, 1f)
        val results = CollisionResults()
        rootNode.collideWith(Ray(near, far.subtract(near).normalizeLocal()), results)
        for (i in 0 until results.size()) {
            val hit = results.getCollision(i)
            if (hit.geometry.name == "Ground") {
                targetPosition = Vector3f(hit.contactPoint.x, 0f, hit.contactPoint.z)
                break
            }
        }
    }

    // ── Public restart ─────────────────────────────────────────────────────────

    fun restartGame() {
        playerHp           = PLAYER_MAX_HP
        playerNode.setLocalTranslation(0f, 0f, 5f)
        facing             = Vector3f(0f, 0f, -1f)
        attackTimer        = 0f;  attackCooldown = 0f
        dashTimer          = 0f;  dashCooldown   = 0f
        attackHitDealt     = false
        targetPosition     = null;  lastMoveDistance = 0f
        input.moveX        = 0f;  input.moveY    = 0f
        input.swordQueued  = false; input.dashQueued  = false
        input.shieldHeld   = false

        dragonBoss.node.setLocalTranslation(bossSpawnPos.clone())
        dragonBoss.node.localRotation = Quaternion.IDENTITY
        dragonBoss.reset()

        lastPlayerHp = -1; lastBossHp = -1
        statusText.text  = ""
        restartHint.text = ""
        floatNums.forEach { guiNode.detachChild(it.text) }
        floatNums.clear()

        gameState = GameState.PLAYING
    }

    // ── Update ─────────────────────────────────────────────────────────────────

    override fun simpleUpdate(tpf: Float) {
        if (gameState != GameState.PLAYING) return
        updateTimers(tpf)
        movePlayer(tpf)
        followCamera()
        updateHumanoid(tpf)
        updateBoss(tpf)
        checkSwordHit()
        updateHUD()
        tickFloatNums(tpf)
    }

    private fun updateTimers(tpf: Float) {
        attackTimer    = (attackTimer    - tpf).coerceAtLeast(0f)
        attackCooldown = (attackCooldown - tpf).coerceAtLeast(0f)
        dashTimer      = (dashTimer      - tpf).coerceAtLeast(0f)
        dashCooldown   = (dashCooldown   - tpf).coerceAtLeast(0f)

        if (input.swordQueued) {
            input.swordQueued = false
            if (attackCooldown <= 0f) {
                attackTimer    = ATTACK_DURATION
                attackCooldown = ATTACK_DURATION + ATTACK_COOLDOWN
                attackHitDealt = false
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
        val jVec = Vector3f(input.moveX, 0f, -input.moveY)
        val jMag = jVec.length()

        val moveDir: Vector3f
        val speed: Float

        when {
            dashTimer > 0f -> { moveDir = dashDirection; speed = DASH_SPEED }
            jMag > JOYSTICK_DEADZONE -> {
                targetPosition = null
                moveDir = jVec.normalize()
                speed   = MOVE_SPEED * jMag.coerceAtMost(1f)
            }
            targetPosition != null -> {
                val toT = targetPosition!!.subtract(playerNode.localTranslation)
                    .also { it.y = 0f }
                if (toT.length() < 0.08f) {
                    targetPosition = null; lastMoveDistance = 0f; return
                }
                moveDir = toT.normalize()
                speed   = MOVE_SPEED
            }
            else -> { lastMoveDistance = 0f; return }
        }

        val slow = if (input.shieldHeld) SHIELD_SLOW_FACTOR else 1f
        val step = moveDir.mult(speed * slow * tpf)
        playerNode.move(step)
        lastMoveDistance = step.length()

        // Clamp inside arena
        val p = playerNode.localTranslation
        playerNode.setLocalTranslation(
            p.x.coerceIn(-ARENA_HALF, ARENA_HALF),
            0f,
            p.z.coerceIn(-ARENA_HALF, ARENA_HALF)
        )

        if (dashTimer <= 0f && moveDir.lengthSquared() > 0.0001f) facing = moveDir.clone()
        playerNode.localRotation = Quaternion().lookAt(facing, Vector3f.UNIT_Y)
    }

    private fun updateHumanoid(tpf: Float) {
        val moving     = lastMoveDistance > 0.0001f
        val speedScale = (lastMoveDistance /
                (MOVE_SPEED * tpf).coerceAtLeast(0.0001f)).coerceIn(0f, 1.5f)
        val attack01 = if (attackTimer > 0f) 1f - attackTimer / ATTACK_DURATION else null
        val dash01   = if (dashTimer   > 0f) 1f - dashTimer   / DASH_DURATION   else null
        humanoid.update(tpf, moving, speedScale, attack01, dash01, input.shieldHeld)
    }

    // ── Boss ───────────────────────────────────────────────────────────────────

    private fun updateBoss(tpf: Float) {
        val bossHit = dragonBoss.update(tpf, playerNode.localTranslation)

        // Pin Y to ground regardless of root-bone drift
        val p = dragonBoss.node.localTranslation
        if (p.y != 0f) dragonBoss.node.setLocalTranslation(p.x, 0f, p.z)

        if (bossHit && !input.shieldHeld) {
            val wasAlive = playerHp > 0
            playerHp = (playerHp - 1).coerceAtLeast(0)
            when {
                wasAlive && playerHp == 0 -> endGame(false)
                wasAlive -> spawnDmgNum(
                    playerNode.localTranslation.add(0f, 1.5f, 0f),
                    "-1", ColorRGBA(1f, 0.25f, 0.25f, 1f)
                )
            }
        }

        if (dragonBoss.state == DragonBoss.State.DEAD && gameState == GameState.PLAYING) {
            endGame(true)
        }
    }

    // ── Sword hit ──────────────────────────────────────────────────────────────

    private fun checkSwordHit() {
        if (attackTimer <= 0f || attackHitDealt) return
        if ((1f - attackTimer / ATTACK_DURATION) > 0.60f) return   // past active frames

        val dist = playerNode.localTranslation.distance(dragonBoss.node.localTranslation)
        if (dist <= SWORD_HIT_RANGE) {
            attackHitDealt = true
            dragonBoss.takeDamage()
            spawnDmgNum(dragonBoss.node.localTranslation.add(0f, 2.5f, 0f),
                "-1", ColorRGBA(1f, 0.85f, 0.10f, 1f))
        }
    }

    // ── Win / Lose ─────────────────────────────────────────────────────────────

    private fun endGame(won: Boolean) {
        gameState         = if (won) GameState.WIN else GameState.LOSE
        statusText.text   = if (won) "YOU WIN!" else "YOU LOSE"
        statusText.color  = if (won) ColorRGBA(0.2f, 1f, 0.3f, 1f) else ColorRGBA(1f, 0.2f, 0.2f, 1f)
        restartHint.text  = "Tap Play Again to restart"
        restartHint.color = ColorRGBA(0.9f, 0.9f, 0.9f, 1f)
        onGameOver?.invoke(won)
    }

    // ── HUD ────────────────────────────────────────────────────────────────────

    private fun updateHUD() {
        bossHpFill.setLocalScale(
            (dragonBoss.hp.toFloat() / dragonBoss.maxHp).coerceIn(0f, 1f), 1f, 1f)
        playerHpFill.setLocalScale(
            (playerHp.toFloat() / PLAYER_MAX_HP).coerceIn(0f, 1f), 1f, 1f)

        if (dragonBoss.hp != lastBossHp) {
            lastBossHp      = dragonBoss.hp
            bossHpText.text = "Dragon Boss  ${dragonBoss.hp} / ${dragonBoss.maxHp}"
        }
        if (playerHp != lastPlayerHp) {
            lastPlayerHp      = playerHp
            playerHpText.text = "Hero  $playerHp / $PLAYER_MAX_HP"
        }
    }

    // ── Floating damage numbers ────────────────────────────────────────────────

    private fun spawnDmgNum(worldPos: Vector3f, label: String, color: ColorRGBA) {
        val sc = cam.getScreenCoordinates(worldPos)
        val c  = color.clone()
        val t  = BitmapText(hudFont, false).apply {
            size = 24f
            text = label
            this.color = c
            setLocalTranslation(sc.x - 10f, sc.y, 5f)
        }
        guiNode.attachChild(t)
        floatNums += FloatNum(t, c, sc.x - 10f, sc.y, 1.2f)
    }

    private fun tickFloatNums(tpf: Float) {
        val it = floatNums.iterator()
        while (it.hasNext()) {
            val fn = it.next()
            fn.life -= tpf
            if (fn.life <= 0f) { guiNode.detachChild(fn.text); it.remove(); continue }
            val progress = 1f - fn.life / fn.maxLife
            fn.text.setLocalTranslation(fn.screenX, fn.screenY + progress * 55f, 5f)
            fn.color.a = fn.life / fn.maxLife
            fn.text.color = fn.color
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun followCamera() {
        val p = playerNode.localTranslation
        cam.location = Vector3f(p.x, p.y + CAM_HEIGHT, p.z + CAM_DISTANCE)
        cam.lookAt(p, Vector3f.UNIT_Y)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun mat(def: String, block: Material.() -> Unit) =
        Material(assetManager, def).apply(block)

    private fun hpBarBg(w: Float, h: Float, x: Float, y: Float, c: ColorRGBA): Geometry =
        Geometry("HudBg", Quad(w, h)).apply {
            material = mat("Common/MatDefs/Misc/Unshaded.j3md") { setColor("Color", c) }
            setLocalTranslation(x, y, 0f)
        }

    private fun hpBarFill(w: Float, h: Float, x: Float, y: Float, c: ColorRGBA): Geometry =
        Geometry("HudFill", Quad(w, h)).apply {
            material = mat("Common/MatDefs/Misc/Unshaded.j3md") { setColor("Color", c) }
            setLocalTranslation(x, y, 1f)
            guiNode.attachChild(this)
        }

    private fun hudText(txt: String, size: Float, x: Float, y: Float): BitmapText =
        BitmapText(hudFont, false).apply {
            this.size = size
            text = txt
            setLocalTranslation(x, y, 2f)
            guiNode.attachChild(this)
        }
}

// FloatArray destructuring up to 6 elements (used in setupWalls)
private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
private operator fun FloatArray.component5() = this[4]
private operator fun FloatArray.component6() = this[5]
