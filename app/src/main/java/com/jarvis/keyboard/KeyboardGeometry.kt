package com.jarvis.keyboard

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Polar helpers and the reusable curved-key path builder (arcTo + lineTo + close). */
object RadialShapes {

    private val oval = RectF()

    fun px(cx: Float, r: Float, deg: Float): Float = cx + r * cos(Math.toRadians(deg.toDouble())).toFloat()

    fun py(cy: Float, r: Float, deg: Float): Float = cy + r * sin(Math.toRadians(deg.toDouble())).toFloat()

    private fun deg(rad: Float): Float = Math.toDegrees(rad.toDouble()).toFloat()

    /**
     * Builds an annular sector into [path]. [inset] shrinks every edge so neighbouring keys keep a constant
     * visual gap (angular padding is derived per arc so the gap does not widen with radius).
     * [chamfer] cuts the four corners diagonally for the sci-fi look.
     */
    fun sector(
        path: Path, cx: Float, cy: Float, r0: Float, r1: Float,
        start: Float, sweep: Float, inset: Float = 0f, chamfer: Float = 0f
    ) {
        path.reset()
        val ri = r0 + inset
        val ro = r1 - inset
        if (ro - ri < 1f || sweep <= 0f) return

        val padI = deg(inset / ri)
        val padO = deg(inset / ro)
        val a0i = start + padI
        val a1i = start + sweep - padI
        val a0o = start + padO
        val a1o = start + sweep - padO
        if (a1i <= a0i || a1o <= a0o) return

        val arcWidth = ri * Math.toRadians((a1i - a0i).toDouble()).toFloat()
        val ch = minOf(chamfer, (ro - ri) * 0.3f, arcWidth * 0.3f)
        val chI = deg(ch / ri)
        val chO = deg(ch / ro)

        // Outer arc, left -> right
        path.moveTo(px(cx, ro, a0o + chO), py(cy, ro, a0o + chO))
        oval.set(cx - ro, cy - ro, cx + ro, cy + ro)
        path.arcTo(oval, a0o + chO, (a1o - a0o) - 2f * chO)
        // Right side with chamfers
        path.lineTo(px(cx, ro - ch, a1o), py(cy, ro - ch, a1o))
        path.lineTo(px(cx, ri + ch, a1i), py(cy, ri + ch, a1i))
        path.lineTo(px(cx, ri, a1i - chI), py(cy, ri, a1i - chI))
        // Inner arc, right -> left
        oval.set(cx - ri, cy - ri, cx + ri, cy + ri)
        path.arcTo(oval, a1i - chI, -((a1i - a0i) - 2f * chI))
        // Left side with chamfers
        path.lineTo(px(cx, ri + ch, a0i), py(cy, ri + ch, a0i))
        path.lineTo(px(cx, ro - ch, a0o), py(cy, ro - ch, a0o))
        path.close()
    }
}

/**
 * Pure geometry: turns (width, height, density) into the suggestion strip, the four typing rows and the
 * rotating tool dial, and builds the [RadialKey] cells. No drawing and no input logic lives here.
 *
 * Layout: a suggestion strip on top, then four concentric, gently curved rows that run edge to edge so the keys
 * fill the whole rectangle. The rows are arcs of one large circle whose centre sits far below the keyboard; every
 * row ends at the same left/right x. The bottom-left corner is taken by a quarter-disc dial drawn on top of the
 * keys (a notch): a ring of tool slots that can be turned by dragging, so only a few tools are in view at once.
 */
class KeyboardGeometry {

    var viewW = 0f; private set
    var viewH = 0f; private set          // keyboard height (strip + rows), without the system-bar inset
    var density = 1f; private set
    var stripH = 0f; private set         // suggestion strip height (0 when suggestions are off)

    var panelLeft = 0f; private set
    var panelW = 0f; private set

    // Row arcs: all rows share one centre. Angles in degrees, 270 = straight up.
    var fanCx = 0f; private set
    var fanCy = 0f; private set
    val rowInner = FloatArray(ROWS)
    val rowOuter = FloatArray(ROWS)
    val rowStart = FloatArray(ROWS)      // left end of each row (rows next to the dial start further right)
    val rowEnd = FloatArray(ROWS)
    var fanStart = 270f; private set     // leftmost / rightmost angle over all rows (scan-light range)
    var fanEnd = 270f; private set
    var rowThickness = 0f; private set

    var gap = 0f; private set

    // Dial (left corner). Everything is measured from (leftDialX, dialY).
    var dialR = 0f; private set          // outer radius of the tool ring
    var ringInner = 0f; private set      // inner radius of the tool ring
    var tickInner = 0f; private set      // tick band between ring and hub
    var tickOuter = 0f; private set
    var hubR = 0f; private set
    var leftDialX = 0f; private set
    var dialY = 0f; private set

    private val chamfer: Float get() = 6f * density

    fun resize(w: Float, h: Float, density: Float, stripH: Float) {
        this.viewW = w
        this.viewH = h
        this.density = density
        this.stripH = stripH
        val d = density

        // Keep a fixed aspect for the key panel so it looks the same in portrait and landscape.
        val fanH = h - stripH
        panelW = min(w, fanH / ASPECT)
        panelLeft = (w - panelW) / 2f

        val topPad = stripH + 5f * d
        val botPad = 6f * d
        val side = 2f * d
        gap = 3.5f * d
        val xHalf = panelW / 2f - side

        // Outer arc: a chord of half-width xHalf with sagitta (edge drop) sag.
        val sag = SAGITTA_FRACTION * panelW
        val r0 = (xHalf * xHalf + sag * sag) / (2f * sag)
        fanCx = panelLeft + panelW / 2f
        fanCy = topPad + r0

        // Row thickness: the lowest key corner (inner corner of the bottom row, at its ends) must sit at the bottom pad.
        val target = h - botPad
        var lo = 10f * d
        var hi = h / 4f
        for (i in 0 until 40) {
            val mid = (lo + hi) / 2f
            val outer3 = r0 - (ROWS - 1) * (mid + gap)
            val inner3 = outer3 - mid
            val a3 = asin((xHalf / outer3).coerceAtMost(0.999f))
            val lowest = fanCy - inner3 * cos(a3)
            if (lowest > target) hi = mid else lo = mid
        }
        rowThickness = ((lo + hi) / 2f).coerceAtLeast(20f * d)
        for (i in 0 until ROWS) {
            rowOuter[i] = r0 - i * (rowThickness + gap)
            rowInner[i] = rowOuter[i] - rowThickness
        }

        // Dial: sized from the row height so its slots are comfortable touch targets.
        leftDialX = panelLeft + 5f * d
        dialY = h
        dialR = (DIAL_FACTOR * rowThickness).coerceIn(0.05f * panelW, 0.34f * panelW)
        ringInner = dialR * 0.58f
        tickOuter = dialR * 0.53f
        tickInner = dialR * 0.44f
        hubR = dialR * 0.36f

        // Each row runs edge to edge (outer corners at +-xHalf); rows beside the dial start where they clear it.
        var minStart = 360f
        var maxEnd = 0f
        for (i in 0 until ROWS) {
            val maxA = Math.toDegrees(asin((xHalf / rowOuter[i]).coerceAtMost(0.999f)).toDouble()).toFloat()
            rowEnd[i] = 270f + maxA
            var start = 270f - maxA
            var guard = 0
            // The two rows beside the dial (third row with SHIFT, bottom row) must both clear it. Letting the third
            // row run underneath the dial hid half of the SHIFT key and made the whole corner look broken.
            while (i >= ROWS - 2 && guard < 6000 && !clearsDial(i, start)) {
                start += 0.1f
                guard++
            }
            rowStart[i] = start
            minStart = min(minStart, start)
            if (rowEnd[i] > maxEnd) maxEnd = rowEnd[i]
        }
        fanStart = minStart
        fanEnd = maxEnd
    }

    /** True when the left end of [row], cut at [angle], is clear of the dial circle (plus the key gap). */
    private fun clearsDial(row: Int, angle: Float): Boolean {
        val need = dialR + gap * 1.5f
        for (k in 0..8) {
            val r = rowInner[row] + (rowOuter[row] - rowInner[row]) * k / 8f
            val kx = RadialShapes.px(fanCx, r, angle)
            val ky = RadialShapes.py(fanCy, r, angle)
            if (hypot(kx - leftDialX, ky - dialY) < need) return false
        }
        return true
    }

    /** Builds the cells of the four rows from their definitions (outermost row first). */
    fun buildFanKeys(rows: List<List<KeyDef>>): List<RadialKey> {
        val keys = ArrayList<RadialKey>(40)
        for (ri in rows.indices) {
            if (ri >= ROWS) break
            val row = rows[ri]
            var total = 0f
            for (def in row) total += def.weight
            val span = rowEnd[ri] - rowStart[ri]
            var angle = rowStart[ri]
            for (def in row) {
                val sweep = span * def.weight / total
                val key = RadialKey(def, Zone.FAN)
                key.setCell(fanCx, fanCy, rowInner[ri], rowOuter[ri], angle, sweep)
                finish(key, rotateLabel = true)
                keys.add(key)
                angle += sweep
            }
        }
        return keys
    }

    /**
     * Builds the tool ring: one slot per tool, all the same size, covering a full turn. Slot i starts at
     * 270 + i*sweep degrees when the ring is at rest, so the first three are the ones visible in the corner.
     */
    fun buildDialKeys(): List<RadialKey> {
        val tools = KeyboardLayouts.dialTools
        val sweep = 360f / tools.size
        val keys = ArrayList<RadialKey>(tools.size)
        for (i in tools.indices) {
            val key = RadialKey(tools[i], Zone.DIAL)
            key.setCell(leftDialX, dialY, ringInner, dialR, 270f + i * sweep, sweep)
            finish(key, rotateLabel = false)
            keys.add(key)
        }
        return keys
    }

    /** Builds the draw path and label anchors for a key whose cell has been set. */
    private fun finish(key: RadialKey, rotateLabel: Boolean) {
        RadialShapes.sector(
            key.path, key.cx, key.cy, key.rInner, key.rOuter,
            key.startAngle, key.sweep, gap / 2f, chamfer
        )
        val a = key.midAngle
        var lx = RadialShapes.px(key.cx, key.midRadius, a)
        var ly = RadialShapes.py(key.cy, key.midRadius, a)
        if (key.zone == Zone.FAN) {
            // Keys that run partly under the dial (e.g. shift) keep their face clear of the dial's edge.
            val dx = lx - leftDialX
            val dy = ly - dialY
            val dist = hypot(dx, dy)
            val need = dialR + gap + key.thickness * 0.40f
            if (dist in 1f..need) {
                lx = leftDialX + dx * need / dist
                ly = dialY + dy * need / dist
            }
        }
        key.labelX = lx
        key.labelY = ly
        key.labelRotation = if (rotateLabel) a - 270f else 0f
        val hintA = a + key.sweep * 0.30f
        val hintR = key.rOuter - key.thickness * 0.20f
        key.hintX = RadialShapes.px(key.cx, hintR, hintA)
        key.hintY = RadialShapes.py(key.cy, hintR, hintA)
    }

    companion object {
        const val ROWS = 4
        const val ASPECT = 0.72f              // key-panel height / width (the strip is extra)
        const val SAGITTA_FRACTION = 0.045f   // how far the row ends drop below the centre, as a share of the width
        const val DIAL_FACTOR = 1.35f         // dial radius as a multiple of the row thickness
        /** Angular size of one dial slot: the ring is split evenly between all tools. */
        val SLOT_DEGREES: Float get() = 360f / KeyboardLayouts.dialTools.size
    }
}
