package com.jarvis.keyboard

import android.graphics.Path
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * One key = one annular sector (ring slice). The "cell" (cx, cy, rInner..rOuter, startAngle..+sweep)
 * is the full hit area and tiles with its neighbours; [path] is the same cell inset by half the key gap
 * with chamfered corners, used for drawing. Angles are degrees, clockwise from 3 o'clock (Canvas convention).
 *
 * Dial slots live on a ring that can be turned: [rotation] is added to [startAngle] for hit testing, and the
 * renderer rotates the canvas by the same amount, so [path] and the label anchors stay in ring coordinates.
 */
class RadialKey(val def: KeyDef, val zone: Zone) {

    var cx = 0f
    var cy = 0f
    var rInner = 0f
    var rOuter = 0f
    var startAngle = 0f
    var sweep = 0f

    /** Current turn of the dial ring in degrees (always 0 for fan keys). */
    var rotation = 0f

    val path = Path()

    val labelUpper: String = def.label.uppercase(Locale.ROOT)

    // Precomputed label placement in ring/cell coordinates (filled by KeyboardGeometry)
    var labelX = 0f
    var labelY = 0f
    var labelRotation = 0f
    var hintX = 0f
    var hintY = 0f

    // Dynamic state, owned by the view
    var pressCount = 0
    var glow = 0f
    var ambient = 0f

    /** Start offset (0..~0.5) of this key inside the opening animation; the bottom row goes first. */
    var introDelay = 0f

    val midAngle: Float get() = startAngle + sweep / 2f
    val midRadius: Float get() = (rInner + rOuter) / 2f
    val thickness: Float get() = rOuter - rInner

    /** Start angle after the ring's current turn. */
    val effectiveStart: Float get() = startAngle + rotation

    fun setCell(cx: Float, cy: Float, rInner: Float, rOuter: Float, startAngle: Float, sweep: Float) {
        this.cx = cx
        this.cy = cy
        this.rInner = rInner
        this.rOuter = rOuter
        this.startAngle = startAngle
        this.sweep = sweep
    }

    /** The dial is a quarter-disc window in the screen corner: only points right of and above its centre count. */
    private fun outsideWindow(dx: Float, dy: Float): Boolean = zone == Zone.DIAL && (dx < 0f || dy > 0f)

    /** Exact hit test against the curved cell. */
    fun contains(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        if (outsideWindow(dx, dy)) return false
        val r2 = dx * dx + dy * dy
        if (r2 < rInner * rInner || r2 > rOuter * rOuter) return false
        return relativeAngle(dx, dy) <= sweep
    }

    /** Distance in pixels from a point to the cell (0 when inside); used for the touch-slop fallback. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        if (outsideWindow(dx, dy)) return Float.MAX_VALUE
        val r = hypot(dx, dy)
        val dr = when {
            r < rInner -> rInner - r
            r > rOuter -> r - rOuter
            else -> 0f
        }
        val rel = relativeAngle(dx, dy)
        val dAngle = if (rel <= sweep) 0f else min(rel - sweep, 360f - rel)
        val arc = Math.toRadians(dAngle.toDouble()).toFloat() * r
        return hypot(dr, arc)
    }

    private fun relativeAngle(dx: Float, dy: Float): Float {
        val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        var rel = (a - effectiveStart) % 360f
        if (rel < 0f) rel += 360f
        return rel
    }
}
