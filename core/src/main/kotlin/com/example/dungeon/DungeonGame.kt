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
private const val JOYSTICK_DEADZONE = 0.15f
private const val PLAYER_MAX_HP     = 5
private const val ARENA_HALF        = 13.5f

// Ranged — fire at this fraction of the attack animation.
private const val RANGED_RELEASE_FRACTION = 0.50f
private const val PROJECTILE_HIT_DIST    = 1.10f
private const val PROJECTILE_MAX_LIFE    = 2.50f

// Camera shake on a landed boss hit.
private const val HIT_SHAKE_DURATION = 0.28f
private const val HIT_SHAKE_MAG      = 0.35f

// Combo: tap attack again while the current attack animation is still running.
// Each class gets its own combo animation duration.
private const val WARRIOR_COMBO_DURATION = 1.20f   // 3-hit chain
private const val MAGE_COMBO_DURATION    = 1.10f   // chain lightning
private const val ARCHER_COMBO_DURATION  = 1.00f   // 5-arrow volley
private const val COMBO_COOLDOWN_EXTRA   = 0.40f   // extra cooldown after combo

// HUD
private const val BOSS_BAR_W   = 240f
private const val BOSS_BAR_H   = 18f
private const val PLAYER_BAR_W = 150f
private const val PLAYER_BAR_H = 18f
private const val BAR_TOP_PAD  = 44f

// ── Enums / data ───────────────────────────────────────────────────────────────

enum class GameState { PLAYING, WIN, LOSE }

private data class FloatNum(
    val text:    BitmapText,
    val color:   ColorRGBA,
    var screenX: Float,
    var screenY: Float,
    var life:    Float,
    val maxLife: Float = 1.2f,
)

private class Projectile(
    val geom:     Geometry,
    val velocity: Vector3f,
    val damage:   Int,
    var life:     Float,
)

// ── DungeonGame ────────────────────────────────────────────────────────────────

class DungeonGame : SimpleApplication() {

    /** Set by MainActivity before the GL thread starts. */
    var playerClass: PlayerClass = PlayerClass.WARRIOR

    // Scene
    private lateinit var playerNode: Node
    private lateinit var humanoid:   Humanoid
    private lateinit var vfxManager: VfxManager

    // Boss
    private lateinit var dragonBoss: DragonBoss
    private val bossSpawnPos = Vector3f(0f, 0f, -11f)

    // Movement
    private var targetPosition:  Vector3f? = null
    private var facing           = Vector3f(0f, 0f, -1f)
    private var lastMoveDistance = 0f

    // ── Normal attack state ───────────────────────────────────────────────────
    private var attackTimer    = 0f
    private var attackCooldown = 0f
    private var attackHitDealt = false
    private var rangedReleased = false

    // ── Combo state ───────────────────────────────────────────────────────────
    /** True when the player tapped attack while a normal attack was still active. */
    private var comboQueued    = false
    /** > 0 while the combo animation is playing. */
    private var comboTimer     = 0f
    private var comboCooldown  = 0f
    private val comboDuration
        get() = when (playerClass) {
            PlayerClass.WARRIOR -> WARRIOR_COMBO_DURATION
            PlayerClass.MAGE    -> MAGE_COMBO_DURATION
            PlayerClass.ARCHER  -> ARCHER_COMBO_DURATION
        }
    // Per-class hit flags — reset when combo starts so each VFX/damage fires once.
    private var ch1 = false; private var ch2 = false; private var ch3 = false
    private var ch4 = false; private var ch5 = false

    // Warrior 3-hit VFX trigger fractions.
    private val WARRIOR_SLASH_TIMES = floatArrayOf(0.27f, 0.54f, 0.88f)
    // Mage chain lightning VFX fractions.
    private val MAGE_BOLT_TIMES     = floatArrayOf(0.39f, 0.60f, 0.74f)
    // Archer volley VFX fractions.
    private val ARCHER_ARROW_TIMES  = floatArrayOf(0.20f, 0.36f, 0.52f, 0.68f, 0.84f)

    // ── Dash / dodge ──────────────────────────────────────────────────────────
    private var dashTimer        = 0f
    private var dashCooldown     = 0f
    private var dashDirection    = Vector3f(0f, 0f, -1f)
    private var dodgeInvulnTimer = 0f
    private var prevDefenseHeld  = false

    // Camera shake.
    private var shakeTimer = 0f

    // In-flight projectiles.
    private val projectiles = mutableListOf<Projectile>()
    private lateinit var fireballMat: Material
    private lateinit var arrowMat:    Material

    // HP / state.
    private var playerHp = PLAYER_MAX_HP
    var gameState: GameState = GameState.PLAYING
        private set

    /**
     * True after simpleInitApp completes. MainActivity checks this after
     * registering [onInitComplete] to close the race-condition window.
     */
    @Volatile var initComplete = false

    var onGameOver:     ((won: Boolean) -> Unit)? = null
    var onInitComplete: (() -> Unit)?              = null

    fun switchClass(newClass: PlayerClass) {
        playerClass = newClass
        playerNode.detachChild(humanoid.root)
        humanoid = Humanoid(assetManager, playerClass)
        playerNode.attachChild(humanoid.root)
        buildProjectileMaterials()
        restartGame()
    }

    // HUD
    private lateinit var hudFont:      BitmapFont
    private lateinit var bossHpFill:   Geometry
    private lateinit var playerHpFill: Geometry
    private lateinit var bossHpText:   BitmapText
    private lateinit var playerHpText: BitmapText
    private lateinit var statusText:   BitmapText
    private lateinit var restartHint:  BitmapText
    private lateinit var classLabel:   BitmapText
    private var lastPlayerHp = -1
    private var lastBossHp   = -1

    private val floatNums = mutableListOf<FloatNum>()

    val input = PlayerInput()

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun simpleInitApp() {
        setDisplayStatView(false)
        setDisplayFps(false)

        setupCamera()
        setupLighting()
        setupGround()
        setupWalls()
        setupPlayer()
        setupBoss()
        setupHUD()
        setupMouseInput()
        buildProjectileMaterials()

        vfxManager = VfxManager(rootNode, assetManager)

        initComplete = true
        onInitComplete?.invoke()
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
            color     = ColorRGBA(1.0f, 0.92f, 0.78f, 1f)
        })
        rootNode.addLight(AmbientLight().apply {
            color = ColorRGBA(0.28f, 0.22f, 0.30f, 1f)
        })
        rootNode.addLight(PointLight().apply {
            position = Vector3f(0f, 3f, 0f)
            color    = ColorRGBA(1.0f, 0.55f, 0.15f, 1f)
            radius   = 22f
        })
        rootNode.addLight(DirectionalLight().apply {
            direction = Vector3f(0.3f, -0.5f, 0.7f).normalizeLocal()
            color     = ColorRGBA(0.20f, 0.25f, 0.45f, 1f)
        })
    }

    private fun setupGround() {
        rootNode.attachChild(Geometry("Ground", Box(ARENA_HALF, 0.1f, ARENA_HALF)).apply {
            material = mat("Common/MatDefs/Light/Lighting.j3md") {
                setBoolean("UseMaterialColors", true)
                setColor("Diffuse",  ColorRGBA(0.18f, 0.14f, 0.12f, 1f))
                setColor("Ambient",  ColorRGBA(0.08f, 0.06f, 0.05f, 1f))
                setColor("Specular", ColorRGBA(0.05f, 0.05f, 0.05f, 1f))
                setFloat("Shininess", 4f)
            }
            setLocalTranslation(0f, -0.1f, 0f)
        })
    }

    private fun setupWalls() {
        val wallMat = mat("Common/MatDefs/Light/Lighting.j3md") {
            setBoolean("UseMaterialColors", true)
            setColor("Diffuse",  ColorRGBA(0.22f, 0.18f, 0.15f, 1f))
            setColor("Ambient",  ColorRGBA(0.06f, 0.05f, 0.04f, 1f))
            setColor("Specular", ColorRGBA(0.04f, 0.04f, 0.04f, 1f))
            setFloat("Shininess", 6f)
        }
        val walls = arrayOf(
            floatArrayOf(ARENA_HALF, 2f, 0.4f,  0f,          2f, -ARENA_HALF),
            floatArrayOf(ARENA_HALF, 2f, 0.4f,  0f,          2f,  ARENA_HALF),
            floatArrayOf(0.4f, 2f, ARENA_HALF, -ARENA_HALF,  2f,  0f),
            floatArrayOf(0.4f, 2f, ARENA_HALF,  ARENA_HALF,  2f,  0f),
        )
        for ((hw, hh, hd, tx, ty, tz) in walls) {
            rootNode.attachChild(Geometry("Wall", Box(hw, hh, hd)).apply {
                material = wallMat
                setLocalTranslation(tx, ty, tz)
            })
        }
    }

    private fun setupPlayer() {
        playerNode = Node("Player")
        playerNode.setLocalTranslation(0f, 0f, 5f)
        humanoid = Humanoid(assetManager, playerClass)
        playerNode.attachChild(humanoid.root)
        rootNode.attachChild(playerNode)
    }

    private fun setupBoss() {
        try {
            dragonBoss = DragonBoss(assetManager)
        } catch (e: Exception) {
            dragonBoss = DragonBoss.createFallback(assetManager)
        }
        dragonBoss.node.setLocalTranslation(bossSpawnPos.clone())
        rootNode.attachChild(dragonBoss.node)
    }

    private fun setupHUD() {
        val sw = cam.width.toFloat()
        val sh = cam.height.toFloat()
        hudFont = assetManager.loadFont("Interface/Fonts/Default.fnt")

        val bx = sw / 2f - BOSS_BAR_W / 2f
        val by = sh - BAR_TOP_PAD - BOSS_BAR_H
        guiNode.attachChild(hpBarBg(BOSS_BAR_W, BOSS_BAR_H, bx, by,
            ColorRGBA(0.28f, 0.02f, 0.02f, 0.85f)))
        bossHpFill = hpBarFill(BOSS_BAR_W, BOSS_BAR_H, bx, by, ColorRGBA(0.85f, 0.12f, 0.12f, 1f))
        bossHpText = hudText("Dragon Boss", 15f, bx, by + BOSS_BAR_H + 18f)

        val px = 14f
        val py = sh - BAR_TOP_PAD - PLAYER_BAR_H
        guiNode.attachChild(hpBarBg(PLAYER_BAR_W, PLAYER_BAR_H, px, py,
            ColorRGBA(0.05f, 0.22f, 0.05f, 0.85f)))
        playerHpFill = hpBarFill(PLAYER_BAR_W, PLAYER_BAR_H, px, py, ColorRGBA(0.18f, 0.82f, 0.18f, 1f))
        playerHpText = hudText("Hero", 15f, px, py + PLAYER_BAR_H + 18f)

        classLabel       = hudText(playerClass.displayName, 14f, sw - 100f, sh - 14f)
        classLabel.color = ColorRGBA(0.75f, 0.75f, 0.85f, 0.85f)

        statusText  = hudText("", 52f, sw / 2f - 170f, sh / 2f + 30f)
        restartHint = hudText("", 22f, sw / 2f - 130f, sh / 2f - 20f)
    }

    private fun buildProjectileMaterials() {
        fireballMat = mat("Common/MatDefs/Misc/Unshaded.j3md") {
            setColor("Color", ColorRGBA(1f, 0.45f, 0.05f, 1f))
        }
        arrowMat = mat("Common/MatDefs/Light/Lighting.j3md") {
            setBoolean("UseMaterialColors", true)
            setColor("Diffuse",  ColorRGBA(0.60f, 0.45f, 0.20f, 1f))
            setColor("Ambient",  ColorRGBA(0.20f, 0.14f, 0.06f, 1f))
            setColor("Specular", ColorRGBA(0.80f, 0.80f, 0.60f, 1f))
            setFloat("Shininess", 32f)
        }
    }

    private fun setupMouseInput() {
        try {
            inputManager.addMapping("Tap", MouseButtonTrigger(MouseInput.BUTTON_LEFT))
            inputManager.addListener(tapListener, "Tap")
        } catch (_: Exception) {}
    }

    // ── Input ──────────────────────────────────────────────────────────────────

    private val tapListener = ActionListener { _, isPressed, _ ->
        if (!isPressed) return@ActionListener
        when (gameState) {
            GameState.PLAYING            -> handleClickMove()
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

    // ── Restart (GL thread) ───────────────────────────────────────────────────

    fun restartGame() {
        playerHp = PLAYER_MAX_HP
        playerNode.setLocalTranslation(0f, 0f, 5f)
        facing           = Vector3f(0f, 0f, -1f)
        attackTimer      = 0f;  attackCooldown  = 0f
        dashTimer        = 0f;  dashCooldown    = 0f
        attackHitDealt   = false;  rangedReleased = false
        comboQueued      = false;  comboTimer = 0f;  comboCooldown = 0f
        ch1 = false; ch2 = false; ch3 = false; ch4 = false; ch5 = false
        dodgeInvulnTimer = 0f;  prevDefenseHeld = false
        shakeTimer       = 0f
        targetPosition   = null;  lastMoveDistance = 0f
        input.moveX = 0f;  input.moveY = 0f
        input.attackQueued = false;  input.abilityQueued = false
        input.defenseHeld  = false

        projectiles.forEach { rootNode.detachChild(it.geom) }
        projectiles.clear()

        if (::vfxManager.isInitialized) vfxManager.clear()

        dragonBoss.node.setLocalTranslation(bossSpawnPos.clone())
        dragonBoss.node.localRotation = Quaternion.IDENTITY
        dragonBoss.reset()

        lastPlayerHp = -1;  lastBossHp = -1
        classLabel.text  = playerClass.displayName
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
        followCamera(tpf)
        updateHumanoid(tpf)
        updateProjectiles(tpf)
        val bossHit = updateBoss(tpf)
        checkPlayerAttack()
        checkComboAttack()
        vfxManager.update(tpf)
        updateHUD()
        tickFloatNums(tpf)
        if (bossHit) triggerHitFeedback()
    }

    // ── Timers + input ─────────────────────────────────────────────────────────

    private fun updateTimers(tpf: Float) {
        val cls = playerClass
        attackTimer      = (attackTimer      - tpf).coerceAtLeast(0f)
        attackCooldown   = (attackCooldown   - tpf).coerceAtLeast(0f)
        comboTimer       = (comboTimer       - tpf).coerceAtLeast(0f)
        comboCooldown    = (comboCooldown    - tpf).coerceAtLeast(0f)
        dashTimer        = (dashTimer        - tpf).coerceAtLeast(0f)
        dashCooldown     = (dashCooldown     - tpf).coerceAtLeast(0f)
        dodgeInvulnTimer = (dodgeInvulnTimer - tpf).coerceAtLeast(0f)
        shakeTimer       = (shakeTimer       - tpf).coerceAtLeast(0f)

        if (input.attackQueued) {
            input.attackQueued = false
            when {
                // Tap during an active normal attack → queue combo.
                attackTimer > 0f && !comboQueued -> comboQueued = true

                // Tap while combo is running → ignored (no triple-combo).
                comboTimer > 0f -> { /* discard */ }

                // Cooldown expired → start a fresh normal attack.
                attackCooldown <= 0f && comboCooldown <= 0f -> {
                    attackTimer    = cls.attackDuration
                    attackCooldown = cls.attackDuration + cls.attackCooldown
                    attackHitDealt = false
                    rangedReleased = false
                    comboQueued    = false
                }
            }
        }

        // When normal attack animation ends and combo was queued, fire combo.
        if (attackTimer <= 0f && comboQueued && comboCooldown <= 0f && comboTimer <= 0f) {
            comboQueued   = false
            comboTimer    = comboDuration
            comboCooldown = comboDuration + COMBO_COOLDOWN_EXTRA
            ch1 = false; ch2 = false; ch3 = false; ch4 = false; ch5 = false
        }

        if (input.abilityQueued) {
            input.abilityQueued = false
            if (dashCooldown <= 0f) {
                if (cls.dashIsBlink) blinkPlayer(cls.blinkDistance)
                dashTimer     = cls.dashDuration
                dashCooldown  = cls.dashDuration + cls.dashCooldown
                dashDirection = facing.clone()
            }
        }

        // Archer dodge-roll on rising edge of defense button.
        if (cls == PlayerClass.ARCHER) {
            val held = input.defenseHeld
            if (held && !prevDefenseHeld && dashCooldown <= 0f) {
                dashTimer        = cls.dodgeInvulnDuration
                dashCooldown     = cls.dodgeInvulnDuration + cls.dashCooldown
                dodgeInvulnTimer = cls.dodgeInvulnDuration
                dashDirection    = facing.clone()
            }
            prevDefenseHeld = held
        }
    }

    // ── Movement ───────────────────────────────────────────────────────────────

    private fun movePlayer(tpf: Float) {
        val cls = playerClass

        if (dashTimer > 0f && !cls.dashIsBlink) {
            val move = dashDirection.mult(cls.dashSpeed * tpf)
            val p    = playerNode.localTranslation
            playerNode.setLocalTranslation(
                (p.x + move.x).coerceIn(-ARENA_HALF, ARENA_HALF),
                0f,
                (p.z + move.z).coerceIn(-ARENA_HALF, ARENA_HALF),
            )
            lastMoveDistance = move.length()
            return
        }

        val mx = input.moveX
        val my = input.moveY
        val raw = Vector3f(mx, 0f, -my)
        val len = raw.length()

        if (len < JOYSTICK_DEADZONE) {
            targetPosition?.let { target ->
                val diff = target.subtract(playerNode.localTranslation)
                val dist = diff.length()
                if (dist < 0.2f) { targetPosition = null; lastMoveDistance = 0f; return }
                val slow = if (cls.defenseIsHold && input.defenseHeld) cls.defenseSlowFactor else 1f
                val step = diff.normalize().mult(MOVE_SPEED * slow * tpf)
                val p    = playerNode.localTranslation
                playerNode.setLocalTranslation(
                    (p.x + step.x).coerceIn(-ARENA_HALF, ARENA_HALF), 0f,
                    (p.z + step.z).coerceIn(-ARENA_HALF, ARENA_HALF),
                )
                lastMoveDistance = step.length()
                if (dist > 0.01f) facing = diff.normalize()
            } ?: run { lastMoveDistance = 0f }
            return
        }

        val dir   = raw.normalize()
        val slow  = if (cls.defenseIsHold && input.defenseHeld) cls.defenseSlowFactor else 1f
        val speed = MOVE_SPEED * slow * len.coerceIn(0f, 1f)
        val move  = dir.mult(speed * tpf)
        val p     = playerNode.localTranslation
        playerNode.setLocalTranslation(
            (p.x + move.x).coerceIn(-ARENA_HALF, ARENA_HALF), 0f,
            (p.z + move.z).coerceIn(-ARENA_HALF, ARENA_HALF),
        )
        lastMoveDistance = move.length()
        if (dashTimer <= 0f && dir.lengthSquared() > 0.0001f) facing = dir.clone()
        playerNode.localRotation = Quaternion().lookAt(facing, Vector3f.UNIT_Y)
    }

    private fun blinkPlayer(distance: Float) {
        val dest = playerNode.localTranslation.add(facing.mult(distance))
        playerNode.setLocalTranslation(
            dest.x.coerceIn(-ARENA_HALF, ARENA_HALF), 0f,
            dest.z.coerceIn(-ARENA_HALF, ARENA_HALF),
        )
    }

    // ── Humanoid update ────────────────────────────────────────────────────────

    private var hurtPulseThisFrame = false

    private fun updateHumanoid(tpf: Float) {
        val cls      = playerClass
        val moving   = lastMoveDistance > 0.0001f
        val speed    = (lastMoveDistance / (MOVE_SPEED * tpf).coerceAtLeast(0.0001f)).coerceIn(0f, 1.5f)
        val attack01 = if (attackTimer > 0f) 1f - attackTimer / cls.attackDuration else null
        val dash01   = if (dashTimer   > 0f) 1f - dashTimer   / cls.dashDuration   else null
        val combo01  = if (comboTimer  > 0f) 1f - comboTimer  / comboDuration       else null
        val blocking = cls.defenseIsHold && input.defenseHeld
        humanoid.update(tpf, moving, speed, attack01, dash01, blocking, hurtPulseThisFrame, combo01)
        hurtPulseThisFrame = false
    }

    // ── Boss ───────────────────────────────────────────────────────────────────

    private fun updateBoss(tpf: Float): Boolean {
        val hit = dragonBoss.update(tpf, playerNode.localTranslation)

        val p = dragonBoss.node.localTranslation
        if (p.y != 0f) dragonBoss.node.setLocalTranslation(p.x, 0f, p.z)

        val blocked = (playerClass.defenseIsHold && input.defenseHeld) || dodgeInvulnTimer > 0f
        if (hit && !blocked) {
            val wasAlive = playerHp > 0
            playerHp = (playerHp - 1).coerceAtLeast(0)
            when {
                wasAlive && playerHp == 0 -> endGame(false)
                wasAlive -> spawnDmgNum(playerNode.localTranslation.add(0f, 1.5f, 0f),
                    "-1", ColorRGBA(1f, 0.25f, 0.25f, 1f))
            }
            if (wasAlive) return true
        }
        if (dragonBoss.state == DragonBoss.State.DEAD && gameState == GameState.PLAYING)
            endGame(true)
        return false
    }

    private fun triggerHitFeedback() {
        hurtPulseThisFrame = true
        shakeTimer = HIT_SHAKE_DURATION
    }

    // ── Normal player attack ───────────────────────────────────────────────────

    private fun checkPlayerAttack() {
        if (attackTimer <= 0f) return
        val cls      = playerClass
        val progress = 1f - attackTimer / cls.attackDuration

        if (cls.ranged) {
            if (!rangedReleased && progress >= RANGED_RELEASE_FRACTION) {
                rangedReleased = true
                fireProjectile(cls)
            }
            return
        }

        if (attackHitDealt || progress > 0.60f) return
        val dist = playerNode.localTranslation.distance(dragonBoss.node.localTranslation)
        if (dist <= cls.attackRange) {
            attackHitDealt = true
            dragonBoss.takeDamage(cls.attackDamage)
            // VFX: single slash on normal melee swing.
            vfxManager.spawnSlash(playerNode.localTranslation.add(facing.mult(1.2f)), facing, 0)
            spawnDmgNum(dragonBoss.node.localTranslation.add(0f, 2.5f, 0f),
                "-${cls.attackDamage}", ColorRGBA(1f, 0.85f, 0.10f, 1f))
        }
    }

    // ── Combo attack ───────────────────────────────────────────────────────────

    /**
     * Per-frame combo progress check. VFX and damage are triggered at specific
     * t-fractions of the combo animation so they sync with the humanoid poses.
     */
    private fun checkComboAttack() {
        if (comboTimer <= 0f) return
        val t = 1f - comboTimer / comboDuration   // 0..1 combo progress

        when (playerClass) {
            PlayerClass.WARRIOR -> checkWarriorCombo(t)
            PlayerClass.MAGE    -> checkMageCombo(t)
            PlayerClass.ARCHER  -> checkArcherCombo(t)
        }
    }

    private fun checkWarriorCombo(t: Float) {
        val dist = playerNode.localTranslation.distance(dragonBoss.node.localTranslation)
        val inRange = dist <= playerClass.attackRange * 1.3f   // slightly extended range for combo

        if (!ch1 && t >= WARRIOR_SLASH_TIMES[0]) {
            ch1 = true
            val swingDir = facing.clone()
            vfxManager.spawnSlash(playerNode.localTranslation.add(facing.mult(1.4f)), swingDir, 0)
            if (inRange) { dragonBoss.takeDamage(1); spawnDmgNum(bossPos().add(0f, 2.5f, 0f), "-1", COLOR_DMG) }
        }
        if (!ch2 && t >= WARRIOR_SLASH_TIMES[1]) {
            ch2 = true
            val returnDir = Vector3f(-facing.x, 0f, -facing.z)
            vfxManager.spawnSlash(playerNode.localTranslation.add(facing.mult(1.4f)), returnDir, 1)
            if (inRange) { dragonBoss.takeDamage(1); spawnDmgNum(bossPos().add(0.4f, 2.8f, 0f), "-1", COLOR_DMG) }
        }
        if (!ch3 && t >= WARRIOR_SLASH_TIMES[2]) {
            ch3 = true
            // Overhead slam — biggest slash, orange tint.
            vfxManager.spawnSlash(playerNode.localTranslation.add(facing.mult(1.6f)), facing, 2)
            // Second slash fan overhead for dramatic slam.
            val upDir = Vector3f(0f, 1f, -0.5f).normalizeLocal()
            vfxManager.spawnSlash(playerNode.localTranslation.add(0f, 1.2f, 0f), upDir, 2)
            if (inRange) { dragonBoss.takeDamage(1); spawnDmgNum(bossPos().add(-0.4f, 3.0f, 0f), "-1", COLOR_DMG) }
        }
    }

    private fun checkMageCombo(t: Float) {
        if (!ch1 && t >= MAGE_BOLT_TIMES[0]) {
            ch1 = true
            // First bolt — arm spread peak.
            val from = playerNode.localTranslation
            val to   = dragonBoss.node.localTranslation
            vfxManager.spawnLightning(from, to, VfxManager.LIGHTNING_COLOR)
        }
        if (!ch2 && t >= MAGE_BOLT_TIMES[1]) {
            ch2 = true
            // Main blast — arms slam together; biggest damage + explosion.
            val from = playerNode.localTranslation
            val to   = dragonBoss.node.localTranslation
            vfxManager.spawnLightning(from, to, ColorRGBA(1f, 0.9f, 1f, 1f))   // brighter white bolt
            vfxManager.spawnArcaneBlast(dragonBoss.node.localTranslation)
            dragonBoss.takeDamage(2)
            spawnDmgNum(bossPos().add(0f, 3.0f, 0f), "-2", ColorRGBA(0.85f, 0.40f, 1f, 1f))
        }
        if (!ch3 && t >= MAGE_BOLT_TIMES[2]) {
            ch3 = true
            // Aftershock bolt.
            val from = playerNode.localTranslation.add(facing.mult(0.5f))
            val to   = dragonBoss.node.localTranslation
            vfxManager.spawnLightning(from, to, VfxManager.LIGHTNING_COLOR)
            dragonBoss.takeDamage(1)
            spawnDmgNum(bossPos().add(0.5f, 2.6f, 0f), "-1", ColorRGBA(0.85f, 0.40f, 1f, 1f))
        }
    }

    private fun checkArcherCombo(t: Float) {
        // 5 arrows at evenly-spaced fractions.
        val times = ARCHER_ARROW_TIMES
        val flags = booleanArrayOf(ch1, ch2, ch3, ch4, ch5)
        var anyFired = false

        for (i in times.indices) {
            if (!flags[i] && t >= times[i]) {
                when (i) {
                    0 -> ch1 = true;  1 -> ch2 = true;  2 -> ch3 = true
                    3 -> ch4 = true;  4 -> ch5 = true
                }
                // Spawn a visible arrow-spray VFX centred on the facing direction.
                val origin = playerNode.localTranslation
                vfxManager.spawnArrowVolley(origin, facing, 1)   // single arrow per snap
                // Fire an actual projectile for this arrow.
                fireComboArrow(i)
                anyFired = true
            }
        }

        // Additionally fire the full 5-arrow spread VFX once at the midpoint.
        if (!ch3 && t >= 0.50f) {
            ch3 = true
            vfxManager.spawnArrowVolley(playerNode.localTranslation, facing, 3)
        }
    }

    private fun fireComboArrow(index: Int) {
        val spread = floatArrayOf(-0.30f, -0.15f, 0f, 0.15f, 0.30f)
        val angle  = spread.getOrElse(index) { 0f }
        val rot    = Quaternion().fromAngleAxis(angle, Vector3f.UNIT_Y)
        val dir    = rot.mult(facing.normalize())
        val origin = playerNode.localTranslation.add(0f, 0.9f, 0f).add(dir.mult(0.5f))
        val geom   = Geometry("ComboArrow$index", Box(0.04f, 0.04f, 0.22f)).apply {
            material = arrowMat
            setLocalTranslation(origin)
            localRotation = Quaternion().lookAt(dir, Vector3f.UNIT_Y)
        }
        rootNode.attachChild(geom)
        projectiles += Projectile(geom, dir.mult(playerClass.projectileSpeed * 1.2f), 1, PROJECTILE_MAX_LIFE)
    }

    // ── Projectiles ────────────────────────────────────────────────────────────

    private fun fireProjectile(cls: PlayerClass) {
        val origin = playerNode.localTranslation.add(0f, 0.9f, 0f).add(facing.mult(0.5f))
        val mesh   = if (cls == PlayerClass.MAGE) Box(0.13f, 0.13f, 0.13f) else Box(0.05f, 0.05f, 0.24f)
        val geom   = Geometry("Projectile", mesh).apply {
            material = if (cls == PlayerClass.MAGE) fireballMat else arrowMat
            setLocalTranslation(origin)
            localRotation = Quaternion().lookAt(facing, Vector3f.UNIT_Y)
        }
        rootNode.attachChild(geom)
        projectiles += Projectile(geom, facing.mult(cls.projectileSpeed), cls.attackDamage, PROJECTILE_MAX_LIFE)
    }

    private fun updateProjectiles(tpf: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val proj = it.next()
            proj.geom.move(proj.velocity.mult(tpf))
            proj.life -= tpf

            val dist = proj.geom.localTranslation.distance(dragonBoss.node.localTranslation)
            val hit  = dist <= PROJECTILE_HIT_DIST && dragonBoss.state != DragonBoss.State.DEAD

            if (hit) {
                dragonBoss.takeDamage(proj.damage)
                spawnDmgNum(bossPos().add(0f, 2.5f, 0f),
                    "-${proj.damage}", ColorRGBA(1f, 0.85f, 0.10f, 1f))
            }

            if (hit || proj.life <= 0f) {
                rootNode.detachChild(proj.geom)
                it.remove()
            }
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
            size = 24f; text = label; this.color = c
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
            val p = 1f - fn.life / fn.maxLife
            fn.text.setLocalTranslation(fn.screenX, fn.screenY + p * 55f, 5f)
            fn.color.a = fn.life / fn.maxLife
            fn.text.color = fn.color
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun followCamera(tpf: Float) {
        val p = playerNode.localTranslation
        var cam = Vector3f(p.x, p.y + CAM_HEIGHT, p.z + CAM_DISTANCE)
        if (shakeTimer > 0f) {
            val mag = HIT_SHAKE_MAG * (shakeTimer / HIT_SHAKE_DURATION)
            cam = cam.add(
                (FastMath.nextRandomFloat() - 0.5f) * mag,
                (FastMath.nextRandomFloat() - 0.5f) * mag,
                (FastMath.nextRandomFloat() - 0.5f) * mag,
            )
        }
        this.cam.location = cam
        this.cam.lookAt(p, Vector3f.UNIT_Y)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun bossPos() = dragonBoss.node.localTranslation.clone()

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
            this.size = size; text = txt
            setLocalTranslation(x, y, 2f)
            guiNode.attachChild(this)
        }

    companion object {
        private val COLOR_DMG = ColorRGBA(1f, 0.85f, 0.10f, 1f)
    }
}

// FloatArray destructuring for wall setup.
private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
private operator fun FloatArray.component5() = this[4]
private operator fun FloatArray.component6() = this[5]
