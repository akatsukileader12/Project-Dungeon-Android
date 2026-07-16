package com.example.dungeon

import com.jme3.asset.AssetManager
import com.jme3.material.Material
import com.jme3.math.ColorRGBA
import com.jme3.math.FastMath
import com.jme3.math.Quaternion
import com.jme3.math.Vector3f
import com.jme3.renderer.queue.RenderQueue
import com.jme3.scene.Geometry
import com.jme3.scene.Node
import com.jme3.scene.Spatial
import com.jme3.scene.shape.Box

// ── VfxManager ────────────────────────────────────────────────────────────────
//
// Manages three categories of in-world combat visual effects:
//
//  • Slash arcs   – fan of bright flat boxes that flash when a melee swing lands.
//  • Lightning    – jagged chain of box segments from caster to target; flickers.
//  • Arrow trails – thin elongated boxes spawned per arrow in a volley.
//
// Each effect is self-expiring. Call update(tpf) every frame.
// All objects are attached to / detached from [scene] automatically.

class VfxManager(
    private val scene:        Node,
    private val assetManager: AssetManager,
) {

    // ── Effect base class ──────────────────────────────────────────────────────

    private abstract inner class VfxEffect(val maxLife: Float) {
        var life = maxLife
        abstract fun tick(tpf: Float)
        abstract fun detach()
    }

    private val active = mutableListOf<VfxEffect>()

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Three-slash fan at [worldPos] facing [swingDir].
     * [swingNum] 0/1/2 shifts the colour (white→yellow→orange) to mark the combo hit.
     */
    fun spawnSlash(worldPos: Vector3f, swingDir: Vector3f, swingNum: Int = 0) {
        val colors = listOf(
            ColorRGBA(1f, 1f, 0.85f, 1f),
            ColorRGBA(1f, 0.90f, 0.20f, 1f),
            ColorRGBA(1f, 0.55f, 0.10f, 1f),
        )
        val baseColor = colors[swingNum.coerceIn(0, 2)]

        // Build a rotation that aligns the slash's "blade" (local Y axis) with the swing plane.
        val lookDir = if (swingDir.lengthSquared() > 0.001f) swingDir.normalize() else Vector3f(0f, 0f, -1f)
        val baseRot  = Quaternion().lookAt(lookDir, Vector3f.UNIT_Y)

        // Fan: three blades at –30°, 0°, +30° around the forward direction.
        val blades   = mutableListOf<Geometry>()
        val mat      = unlitMat(baseColor)

        listOf(-0.52f, 0f, 0.52f).forEachIndexed { i, fanAngle ->
            val rot  = Quaternion().fromAngleAxis(fanAngle, lookDir).mult(baseRot)
            // Scale middle blade slightly longer.
            val scaleY = if (i == 1) 1.20f else 1.0f
            val blade  = Geometry("Slash$i", Box(0.022f, 0.42f * scaleY, 0.006f)).apply {
                material = mat
                localRotation = rot
                setLocalTranslation(worldPos.add(Vector3f.UNIT_Y.mult(0.7f)))
                queueBucket = RenderQueue.Bucket.Transparent
                scene.attachChild(this)
            }
            blades += blade
        }

        active += object : VfxEffect(0.22f) {
            override fun tick(tpf: Float) {
                val frac = life / maxLife           // 1→0
                val scale = 0.5f + frac * 0.5f     // shrink as it fades
                blades.forEach { b ->
                    b.setLocalScale(scale, scale, scale)
                    // Dim towards transparent by shifting colour toward black.
                    mat.setColor("Color", baseColor.mult(frac + 0.05f))
                }
            }
            override fun detach() = blades.forEach { scene.detachChild(it) }
        }
    }

    /**
     * Jagged lightning bolt from [from] to [to].
     * Flickers for [flickerDuration]s then holds briefly before vanishing.
     */
    fun spawnLightning(from: Vector3f, to: Vector3f, colour: ColorRGBA = LIGHTNING_COLOR) {
        val segments = mutableListOf<Geometry>()
        val mat      = unlitMat(colour)

        val diff   = to.subtract(from)
        val total  = diff.length()
        val n      = 10
        val step   = total / n

        // Build N zigzag segments between from→to.
        var prevPt = from.clone().add(0f, 0.8f, 0f)     // start at chest height
        val endPt  = to.clone().add(0f, 1.5f, 0f)

        for (i in 1..n) {
            val t    = i.toFloat() / n
            val base = from.interpolateLocal(endPt.clone(), t)
                .also { it.y += (from.y + endPt.y) / 2f - it.y }   // keep at mid-height
            // Jitter: perpendicular offset (avoid last point to land exactly on target)
            val jitter = if (i < n) {
                val perp = Vector3f(diff.z, 0f, -diff.x).normalizeLocal()
                perp.mult((FastMath.nextRandomFloat() - 0.5f) * 1.1f)
            } else Vector3f.ZERO

            // Recalculate base properly: linear lerp from start to end
            val lerpPt = from.add(0f, 0.8f, 0f).interpolateLocal(endPt.clone(), t)
            val curPt  = if (i < n) lerpPt.add(jitter) else endPt.add(0f, 1.5f, 0f)

            val segDir = curPt.subtract(prevPt)
            val segLen = segDir.length()
            if (segLen < 0.01f) { prevPt = curPt; continue }

            val midPt  = prevPt.add(curPt).mult(0.5f)
            val rot    = Quaternion().lookAt(segDir.normalize(), Vector3f.UNIT_Y)

            val seg = Geometry("LtSeg$i", Box(0.020f, 0.020f, segLen / 2f)).apply {
                material = mat
                localRotation = rot
                setLocalTranslation(midPt)
                scene.attachChild(this)
            }
            segments += seg
            prevPt = curPt
        }

        // Spawn a bright central flash sphere (Box) at origin.
        val flash = Geometry("LtFlash", Box(0.14f, 0.14f, 0.14f)).apply {
            material = unlitMat(ColorRGBA(1f, 1f, 1f, 1f))
            setLocalTranslation(from.add(0f, 0.9f, 0f))
            scene.attachChild(this)
        }

        active += object : VfxEffect(0.48f) {
            private var flickerClock = 0f
            private var flickerOn    = true

            override fun tick(tpf: Float) {
                flickerClock += tpf
                if (flickerClock > 0.045f) {
                    flickerClock = 0f
                    flickerOn    = !flickerOn
                    val hint = if (flickerOn) Spatial.CullHint.Never else Spatial.CullHint.Always
                    segments.forEach { it.cullHint = hint }
                }
                // Flash fades fast.
                val frac = (life / maxLife).coerceIn(0f, 1f)
                flash.setLocalScale(frac * 0.8f)
            }

            override fun detach() {
                segments.forEach { scene.detachChild(it) }
                scene.detachChild(flash)
            }
        }
    }

    /**
     * Arrow-volley spray: [count] arrows fan out from [origin] toward [facing].
     * Each arrow is a thin elongated box; they fly forward and vanish.
     */
    fun spawnArrowVolley(origin: Vector3f, facing: Vector3f, count: Int = 5) {
        val arrowMat = unlitMat(ColorRGBA(0.80f, 0.62f, 0.28f, 1f))

        // Spread angles: evenly distributed across ±25° fan.
        val halfSpread = 0.44f  // radians
        val spread = if (count > 1) halfSpread * 2f / (count - 1) else 0f
        val perpUp = Vector3f(0f, 1f, 0f)

        val arrows = mutableListOf<Pair<Geometry, Vector3f>>() // geom + velocity

        for (i in 0 until count) {
            val angle = -halfSpread + i * spread
            val rot   = Quaternion().fromAngleAxis(angle, perpUp)
            val dir   = rot.mult(facing.normalize())

            val arrowRot = Quaternion().lookAt(dir, perpUp)
            val geom = Geometry("VfxArrow$i", Box(0.016f, 0.016f, 0.18f)).apply {
                material = arrowMat
                localRotation = arrowRot
                setLocalTranslation(origin.add(0f, 0.8f, 0f).add(dir.mult(0.4f)))
                scene.attachChild(this)
            }
            arrows += geom to dir.mult(28f)   // fast travel speed
        }

        active += object : VfxEffect(0.30f) {
            override fun tick(tpf: Float) {
                val frac = life / maxLife
                arrows.forEach { (g, v) ->
                    g.move(v.mult(tpf))
                    // Shrink laterally as they fly (needle effect).
                    g.setLocalScale(frac, frac, 1f)
                }
            }
            override fun detach() = arrows.forEach { (g, _) -> scene.detachChild(g) }
        }
    }

    /**
     * Big mage energy burst explosion: ring of Box shards that fly outward.
     */
    fun spawnArcaneBlast(center: Vector3f) {
        val shards = mutableListOf<Pair<Geometry, Vector3f>>()
        val count  = 12
        val mat    = unlitMat(MAGE_BLAST_COLOR)

        for (i in 0 until count) {
            val angle = i.toFloat() / count * FastMath.TWO_PI
            val dir   = Vector3f(FastMath.cos(angle), 0.6f, FastMath.sin(angle)).normalizeLocal()
            val geom  = Geometry("Shard$i", Box(0.06f, 0.06f, 0.18f)).apply {
                material = mat
                localRotation = Quaternion().lookAt(dir, Vector3f.UNIT_Y)
                setLocalTranslation(center.add(0f, 1.0f, 0f))
                scene.attachChild(this)
            }
            shards += geom to dir.mult(9f)
        }

        active += object : VfxEffect(0.38f) {
            override fun tick(tpf: Float) {
                val frac = (life / maxLife).coerceIn(0f, 1f)
                shards.forEach { (g, v) ->
                    g.move(v.mult(tpf))
                    g.setLocalScale(frac, frac, frac)
                    mat.setColor("Color", MAGE_BLAST_COLOR.mult(frac + 0.1f))
                }
            }
            override fun detach() = shards.forEach { (g, _) -> scene.detachChild(g) }
        }
    }

    // ── Frame tick ─────────────────────────────────────────────────────────────

    fun update(tpf: Float) {
        val it = active.iterator()
        while (it.hasNext()) {
            val fx = it.next()
            fx.life -= tpf
            if (fx.life <= 0f) {
                fx.detach()
                it.remove()
            } else {
                fx.tick(tpf)
            }
        }
    }

    /** Immediately destroy all active effects (e.g. on restartGame). */
    fun clear() {
        active.forEach { it.detach() }
        active.clear()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun unlitMat(color: ColorRGBA) =
        Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md").apply {
            setColor("Color", color)
        }

    companion object {
        val LIGHTNING_COLOR  = ColorRGBA(0.82f, 0.52f, 1.00f, 1f)
        val MAGE_BLAST_COLOR = ColorRGBA(0.70f, 0.30f, 1.00f, 1f)
    }
}
