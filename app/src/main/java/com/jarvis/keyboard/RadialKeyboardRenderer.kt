package com.jarvis.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

internal fun mixColor(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    val i = 1f - k
    return Color.argb(
        (Color.alpha(a) * i + Color.alpha(b) * k).toInt(),
        (Color.red(a) * i + Color.red(b) * k).toInt(),
        (Color.green(a) * i + Color.green(b) * k).toInt(),
        (Color.blue(a) * i + Color.blue(b) * k).toInt()
    )
}

/**
 * All drawing. The static scenery (carbon fibre, illumination, HUD ruler, strip frame) is rendered once
 * into a bitmap; per frame only the suggestions, keys, dial, scan beam and ripples are drawn. Glow is faked with
 * stacked strokes of decreasing width, which is hardware-accelerated on every API level (unlike
 * BlurMaskFilter / setShadowLayer on paths).
 */
class RadialKeyboardRenderer(context: Context) {

    private val d = context.resources.displayMetrics.density

    var theme: JarvisTheme = ThemeManager.themes[0]
    var glowScale: Float = 0.7f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val scratch = Path()
    private val oval = RectF()
    private var background: Bitmap? = null
    private val icons = HashMap<KeyType, Path>()

    init {
        buildIcons()
    }

    // ------------------------------------------------------------------ static background

    fun buildBackground(w: Int, h: Int, g: KeyboardGeometry) {
        background?.recycle()
        background = null
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val accent = theme.accent

        c.drawColor(Color.rgb(2, 3, 6))

        // Carbon-fibre weave
        fill.shader = BitmapShader(carbonTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, wf, hf, fill)
        fill.shader = null

        // Subtle blue illumination behind the fan
        fill.shader = RadialGradient(
            g.fanCx, g.stripH + (g.viewH - g.stripH) * 0.55f, g.panelW * 0.75f,
            intArrayOf(withAlpha(theme.bgGlow, 70), withAlpha(theme.bgGlow, 0)), null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, fill)
        // Vignette
        fill.shader = RadialGradient(
            wf / 2f, hf * 0.45f, wf * 0.85f,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0)), null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, fill)
        fill.shader = null

        // Suggestion strip frame: dark glass, neon underline, two dividers
        if (g.stripH > 0f) {
            val sh = g.stripH
            fill.color = Color.argb(205, 2, 5, 10)
            c.drawRect(0f, 0f, wf, sh, fill)
            stroke.color = withAlpha(accent, 45)
            stroke.strokeWidth = 5f * d
            c.drawLine(0f, sh - 1f * d, wf, sh - 1f * d, stroke)
            stroke.color = withAlpha(accent, 170)
            stroke.strokeWidth = 1.2f * d
            c.drawLine(0f, sh - 1f * d, wf, sh - 1f * d, stroke)
            stroke.color = withAlpha(accent, 85)
            stroke.strokeWidth = 1f * d
            for (i in 1..2) {
                val x = wf * i / 3f
                c.drawLine(x, sh * 0.24f, x, sh * 0.76f, stroke)
            }
        }

        background = bmp
    }

    fun drawBackground(c: Canvas) {
        background?.let { c.drawBitmap(it, 0f, 0f, null) }
    }

    private fun carbonTile(): Bitmap {
        val s = 16
        val px = IntArray(s * s)
        for (y in 0 until s) {
            for (x in 0 until s) {
                val vertical = ((x / 8) + (y / 8)) % 2 == 0
                val across = if (vertical) x % 8 else y % 8     // position across the thread
                val along = if (vertical) y % 8 else x % 8      // position along the thread
                val t = abs(across - 3.5f) / 3.5f
                val base = 9f + (1f - t * t) * 22f
                val sheen = sin(along * PI / 8.0).toFloat() * 4f
                val l = (base + sheen).toInt().coerceIn(0, 255)
                px[y * s + x] = Color.rgb(l, l, (l * 1.1f).toInt().coerceAtMost(255))
            }
        }
        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ suggestion strip

    /** Draws up to three suggestions (left, centre, right). The centre one is the best match. */
    fun drawStrip(c: Canvas, g: KeyboardGeometry, labels: Array<String?>, pressed: Int) {
        if (g.stripH <= 0f) return
        val cellW = g.viewW / 3f
        val avail = cellW - 22f * d
        for (i in 0 until 3) {
            val x0 = i * cellW
            if (i == pressed && labels[i] != null) {
                fill.color = withAlpha(theme.accent, 55)
                c.drawRect(x0 + 1f * d, 0f, x0 + cellW - 1f * d, g.stripH - 2f * d, fill)
            }
            val label = labels[i] ?: continue
            val strong = i == 1
            val base = (if (strong) 17f else 15f) * d
            text.textSize = base
            var s = label
            var w = text.measureText(s)
            if (w > avail) {
                text.textSize = max(base * avail / w, 11f * d)
                w = text.measureText(s)
                if (w > avail) {
                    var n = s.length
                    val ell = text.measureText("…")
                    while (n > 1 && text.measureText(s, 0, n) + ell > avail) n--
                    s = s.substring(0, n) + "…"
                }
            }
            text.color = if (strong) mixColor(theme.text, Color.WHITE, 0.35f) else withAlpha(theme.text, 190)
            val y = (g.stripH - 2f * d) / 2f - (text.ascent() + text.descent()) / 2f
            c.drawText(s, x0 + cellW / 2f, y, text)
        }
    }

    // ------------------------------------------------------------------ fan keys

    fun drawKey(c: Canvas, k: RadialKey, st: KeyboardState) {
        drawBody(c, k, st)
        drawFace(c, k, st, k.labelX, k.labelY, k.labelRotation)
    }

    private fun hotOf(k: RadialKey, st: KeyboardState): Float {
        val active = when (k.def.type) {
            KeyType.SHIFT -> st.shift != ShiftState.OFF
            KeyType.MODE_NUM -> st.layer == Layer.NUMBERS
            KeyType.MODE_SYM -> st.layer == Layer.SYMBOLS
            else -> false
        }
        return max(k.glow, if (active) 0.6f else 0f)
    }

    /** Body + neon edge. Drawn in cell coordinates, so dial slots are drawn under a rotated canvas. */
    private fun drawBody(c: Canvas, k: RadialKey, st: KeyboardState) {
        val hot = hotOf(k, st)
        val boost = max(hot, k.ambient * 0.7f)
        val ga = glowScale

        fill.color = mixColor(theme.keyFill, theme.keyFillHot, hot)
        c.drawPath(k.path, fill)
        if (hot > 0.01f) {
            fill.color = withAlpha(theme.accent, (hot * 70f).toInt())
            c.drawPath(k.path, fill)
        }

        // Neon edge: wide faint halo, mid halo, crisp core
        val edge = mixColor(theme.accent, Color.WHITE, hot * 0.6f)
        if (boost > 0.02f || ga > 0.5f) {
            stroke.color = withAlpha(edge, ((18f + 90f * boost) * ga).toInt())
            stroke.strokeWidth = 6f * d
            c.drawPath(k.path, stroke)
        }
        stroke.color = withAlpha(edge, ((40f + 110f * boost) * ga).toInt())
        stroke.strokeWidth = 3f * d
        c.drawPath(k.path, stroke)
        stroke.color = withAlpha(edge, (150f + 105f * boost).toInt())
        stroke.strokeWidth = 1.2f * d
        c.drawPath(k.path, stroke)
    }

    /** Label or icon, centred on (x, y), rotated by [rot] degrees. */
    private fun drawFace(c: Canvas, k: RadialKey, st: KeyboardState, x: Float, y: Float, rot: Float) {
        val hot = hotOf(k, st)
        val th = k.thickness
        val ink = mixColor(theme.text, Color.WHITE, hot * 0.5f)
        when (k.def.type) {
            KeyType.CHAR -> {
                val s = if (st.layer == Layer.LETTERS && st.shift != ShiftState.OFF) k.labelUpper else k.def.label
                drawText(c, s, x, y, th * 0.38f, rot, ink)
                val hint = k.def.hint
                if (hint != null) {
                    drawText(c, hint, k.hintX, k.hintY, th * 0.19f, rot, withAlpha(theme.hint, 200))
                }
            }
            KeyType.SPACE -> Unit      // intentionally blank: a clean bar
            KeyType.MODE_NUM ->
                drawText(c, if (st.layer == Layer.LETTERS) "123" else "ABC", x, y, th * 0.32f, 0f, ink)
            KeyType.MODE_SYM ->
                drawText(c, if (st.layer == Layer.SYMBOLS) "123" else "#+=", x, y, th * 0.34f, rot, ink)
            else -> drawIcon(c, k.def.type, x, y, th * 0.27f, rot, ink, st.shift == ShiftState.LOCK)
        }
    }

    private fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, rot: Float, color: Int) {
        text.textSize = size
        text.color = color
        val dy = -(text.ascent() + text.descent()) / 2f
        if (rot != 0f) {
            c.save()
            c.rotate(rot, x, y)
            c.drawText(s, x, y + dy, text)
            c.restore()
        } else {
            c.drawText(s, x, y + dy, text)
        }
    }

    private fun drawIcon(c: Canvas, type: KeyType, x: Float, y: Float, half: Float, rot: Float, color: Int, locked: Boolean) {
        val path = icons[type] ?: return
        icon.color = color
        icon.strokeWidth = 1.7f * d / half
        icon.style = if (type == KeyType.SHIFT && locked) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.save()
        c.translate(x, y)
        if (rot != 0f) c.rotate(rot)
        c.scale(half, half)
        c.drawPath(path, icon)
        c.restore()
    }

    private fun buildIcons() {
        icons[KeyType.SHIFT] = Path().apply {
            moveTo(0f, -0.95f); lineTo(0.9f, 0.05f); lineTo(0.42f, 0.05f); lineTo(0.42f, 0.85f)
            lineTo(-0.42f, 0.85f); lineTo(-0.42f, 0.05f); lineTo(-0.9f, 0.05f); close()
        }
        icons[KeyType.BACKSPACE] = Path().apply {
            moveTo(-0.95f, 0f); lineTo(-0.4f, -0.75f); lineTo(0.95f, -0.75f); lineTo(0.95f, 0.75f)
            lineTo(-0.4f, 0.75f); close()
            moveTo(-0.05f, -0.3f); lineTo(0.55f, 0.3f)
            moveTo(0.55f, -0.3f); lineTo(-0.05f, 0.3f)
        }
        icons[KeyType.ENTER] = Path().apply {
            moveTo(0.85f, -0.75f); lineTo(0.85f, 0.15f); lineTo(-0.75f, 0.15f)
            moveTo(-0.3f, -0.35f); lineTo(-0.8f, 0.15f); lineTo(-0.3f, 0.65f)
        }
        icons[KeyType.LANG] = Path().apply {
            addCircle(0f, 0f, 0.9f, Path.Direction.CW)
            addOval(RectF(-0.42f, -0.9f, 0.42f, 0.9f), Path.Direction.CW)
            moveTo(-0.9f, 0f); lineTo(0.9f, 0f)
            moveTo(-0.76f, -0.45f); lineTo(0.76f, -0.45f)
            moveTo(-0.76f, 0.45f); lineTo(0.76f, 0.45f)
        }
        icons[KeyType.SETTINGS] = Path().apply {
            addCircle(0f, 0f, 0.34f, Path.Direction.CW)
            addCircle(0f, 0f, 0.66f, Path.Direction.CW)
            for (n in 0 until 8) {
                val a = n * PI / 4.0
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                moveTo(ca * 0.66f, sa * 0.66f)
                lineTo(ca * 0.95f, sa * 0.95f)
            }
        }
        icons[KeyType.CURSOR_LEFT] = Path().apply {
            moveTo(0.35f, -0.8f); lineTo(-0.45f, 0f); lineTo(0.35f, 0.8f)
        }
        icons[KeyType.CURSOR_RIGHT] = Path().apply {
            moveTo(-0.35f, -0.8f); lineTo(0.45f, 0f); lineTo(-0.35f, 0.8f)
        }
        icons[KeyType.SELECT_ALL] = Path().apply {
            moveTo(-0.85f, -0.35f); lineTo(-0.85f, -0.85f); lineTo(-0.35f, -0.85f)
            moveTo(0.35f, -0.85f); lineTo(0.85f, -0.85f); lineTo(0.85f, -0.35f)
            moveTo(0.85f, 0.35f); lineTo(0.85f, 0.85f); lineTo(0.35f, 0.85f)
            moveTo(-0.35f, 0.85f); lineTo(-0.85f, 0.85f); lineTo(-0.85f, 0.35f)
            addRect(-0.3f, -0.3f, 0.3f, 0.3f, Path.Direction.CW)
        }
        icons[KeyType.COPY] = Path().apply {
            addRect(-0.85f, -0.4f, 0.35f, 0.9f, Path.Direction.CW)
            moveTo(-0.4f, -0.4f); lineTo(-0.4f, -0.9f); lineTo(0.85f, -0.9f); lineTo(0.85f, 0.45f); lineTo(0.35f, 0.45f)
        }
        icons[KeyType.CUT] = Path().apply {
            addCircle(-0.45f, 0.6f, 0.28f, Path.Direction.CW)
            addCircle(0.45f, 0.6f, 0.28f, Path.Direction.CW)
            moveTo(-0.38f, 0.35f); lineTo(0.5f, -0.9f)
            moveTo(0.38f, 0.35f); lineTo(-0.5f, -0.9f)
        }
        icons[KeyType.PASTE] = Path().apply {
            addRect(-0.7f, -0.6f, 0.7f, 0.9f, Path.Direction.CW)
            addRect(-0.3f, -0.95f, 0.3f, -0.4f, Path.Direction.CW)
            moveTo(-0.35f, 0.05f); lineTo(0.35f, 0.05f)
            moveTo(-0.35f, 0.5f); lineTo(0.35f, 0.5f)
        }
        icons[KeyType.HIDE] = Path().apply {
            moveTo(-0.7f, -0.3f); lineTo(0f, 0.4f); lineTo(0.7f, -0.3f)
            moveTo(-0.7f, 0.85f); lineTo(0.7f, 0.85f)
        }
        icons[KeyType.DEL_WORD] = Path().apply {
            moveTo(-0.95f, 0f); lineTo(-0.4f, -0.75f); lineTo(0.95f, -0.75f); lineTo(0.95f, 0.75f)
            lineTo(-0.4f, 0.75f); close()
            moveTo(0.2f, -0.3f); lineTo(-0.1f, 0f); lineTo(0.2f, 0.3f)
            moveTo(0.65f, -0.3f); lineTo(0.35f, 0f); lineTo(0.65f, 0.3f)
        }
    }

    // ------------------------------------------------------------------ rotating dial

    /**
     * The tool dial: a quarter-disc window onto a ring of tool slots. [ringAngle] is how far the ring has been
     * turned; the tick band turns with it. Everything is clipped to the window so slots slide in and out
     * from under the screen corner. [t] is seconds (0 freezes the hub animation).
     */
    fun drawDial(c: Canvas, g: KeyboardGeometry, slots: List<RadialKey>, st: KeyboardState, ringAngle: Float, t: Float) {
        val cx = g.leftDialX
        val cy = g.dialY
        c.save()
        c.clipRect(cx, cy - g.dialR - 4f * d, cx + g.dialR + 4f * d, cy)

        // Opaque backing: the dial is a notch cut into the key area, so any key underneath disappears behind it.
        val edgeR = g.dialR + g.gap * 0.5f
        fill.color = Color.rgb(2, 4, 8)
        c.drawCircle(cx, cy, edgeR, fill)
        stroke.color = withAlpha(theme.accent, 120)
        stroke.strokeWidth = 1.2f * d
        oval.set(cx - edgeR, cy - edgeR, cx + edgeR, cy + edgeR)
        c.drawArc(oval, 270f, 90f, false, stroke)

        drawTicks(c, g, ringAngle)

        for (i in slots.indices) {
            val k = slots[i]
            val rel = ((k.effectiveStart - 250f) % 360f + 360f) % 360f
            if (rel >= 130f && rel <= 330f) continue                      // slot is fully outside the window
            c.save()
            c.rotate(k.rotation, k.cx, k.cy)
            drawBody(c, k, st)
            c.restore()
            val a = Math.toRadians(k.rotation.toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val dx = k.labelX - k.cx
            val dy = k.labelY - k.cy
            drawFace(c, k, st, k.cx + dx * ca - dy * sa, k.cy + dx * sa + dy * ca, 0f)
        }

        drawHub(c, cx, cy, g.hubR, st.language.code, t)
        c.restore()
    }

    private fun drawTicks(c: Canvas, g: KeyboardGeometry, ringAngle: Float) {
        val cx = g.leftDialX
        val cy = g.dialY
        val accent = theme.accent
        stroke.strokeWidth = 1.1f * d
        for (n in 0 until 72) {
            val ang = 270f + n * 5f + ringAngle
            val rel = ((ang - 250f) % 360f + 360f) % 360f
            if (rel > 140f) continue
            val major = n % 6 == 0
            val len = (g.tickOuter - g.tickInner) * (if (major) 1f else 0.5f)
            stroke.color = withAlpha(accent, if (major) 210 else 110)
            c.drawLine(
                RadialShapes.px(cx, g.tickOuter, ang), RadialShapes.py(cy, g.tickOuter, ang),
                RadialShapes.px(cx, g.tickOuter - len, ang), RadialShapes.py(cy, g.tickOuter - len, ang), stroke
            )
        }
        // Fixed hairline rings frame the tick band
        stroke.color = withAlpha(accent, 70)
        stroke.strokeWidth = 1f * d
        for (r in floatArrayOf(g.tickOuter + 2f * d, g.tickInner - 2f * d)) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(oval, 270f, 90f, false, stroke)
        }
    }

    private fun drawHub(c: Canvas, cx: Float, cy: Float, r: Float, label: String, t: Float) {
        val accent = theme.accent
        val ga = glowScale
        fill.color = theme.keyFill
        c.drawCircle(cx, cy, r, fill)

        stroke.color = withAlpha(accent, (60f * ga).toInt())
        stroke.strokeWidth = 4f * d
        c.drawCircle(cx, cy, r, stroke)
        stroke.color = withAlpha(accent, 190)
        stroke.strokeWidth = 1.3f * d
        c.drawCircle(cx, cy, r, stroke)

        val spin = t * 45f
        oval.set(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        stroke.strokeWidth = 2.2f * d
        stroke.color = withAlpha(accent, 220)
        for (n in 0 until 3) c.drawArc(oval, spin + n * 120f, 56f, false, stroke)

        oval.set(cx - r * 0.56f, cy - r * 0.56f, cx + r * 0.56f, cy + r * 0.56f)
        stroke.strokeWidth = 1.2f * d
        stroke.color = withAlpha(accent, 150)
        for (n in 0 until 4) c.drawArc(oval, -spin * 1.6f + n * 90f, 34f, false, stroke)

        val pulse = 0.5f + 0.5f * sin(t * 2.2f)
        fill.color = withAlpha(accent, (40f + 70f * pulse).toInt())
        c.drawCircle(cx, cy, r * 0.3f, fill)

        drawText(
            c, label, RadialShapes.px(cx, r * 0.55f, 315f), RadialShapes.py(cy, r * 0.55f, 315f),
            r * 0.30f, 0f, withAlpha(theme.text, 230)
        )
    }

    // ------------------------------------------------------------------ scan beam, ripples

    /** Light beam sweeping across the rows; [angle] in canvas degrees. */
    fun drawScan(c: Canvas, g: KeyboardGeometry, angle: Float) {
        val r0 = g.rowInner[GEOM_LAST] - 4f * d
        val r1 = g.rowOuter[0] + 4f * d
        val ga = glowScale
        val wide = (g.fanEnd - g.fanStart) / 5f
        RadialShapes.sector(scratch, g.fanCx, g.fanCy, r0, r1, angle - wide / 2f, wide)
        fill.color = withAlpha(theme.accent, (16f * ga).toInt())
        c.drawPath(scratch, fill)
        RadialShapes.sector(scratch, g.fanCx, g.fanCy, r0, r1, angle - wide * 0.06f, wide * 0.12f)
        fill.color = withAlpha(Color.WHITE, (70f * ga).toInt())
        c.drawPath(scratch, fill)
    }

    fun drawRipple(c: Canvas, x: Float, y: Float, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        stroke.color = withAlpha(theme.accent, ((1f - p) * 170f * glowScale).toInt())
        stroke.strokeWidth = 1.6f * d
        c.drawCircle(x, y, (6f + 40f * p) * d, stroke)
    }

    private companion object {
        const val GEOM_LAST = KeyboardGeometry.ROWS - 1
    }
}
