package com.jarvis.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The keyboard surface. Owns the geometry, renderer and touch handler and glues them to Android's
 * View system; it does not know what a key *means* — that is reported to [KeyListener] (the service).
 * It also owns the suggestion strip (drawing + taps) and the turning of the tool dial.
 */
class RadialKeyboardView(context: Context) : View(context), KeyboardTouchHandler.Listener {

    interface KeyListener {
        fun onKeyCommit(key: RadialKey)
        /** Return true when the long press was handled (suppresses the normal tap on release). */
        fun onKeyLongPress(key: RadialKey): Boolean
        /** One of the three strip cells (0 = left, 1 = centre, 2 = right) was tapped. */
        fun onSuggestionPicked(index: Int)
    }

    var keyListener: KeyListener? = null
    val state = KeyboardState()

    private val density = resources.displayMetrics.density
    private val settings = KeyboardSettings(context)
    private val geometry = KeyboardGeometry()
    private val renderer = RadialKeyboardRenderer(context)
    private val touch = KeyboardTouchHandler({ allKeys }, this, 14f * density, 9f * density)

    private var fanKeys: List<RadialKey> = emptyList()
    private var dialKeys: List<RadialKey> = emptyList()
    private var allKeys: List<RadialKey> = emptyList()

    private var bottomInset = 0
    private var haptics = true
    private var ambientEnabled = true
    private var suggestionsEnabled = true

    // Suggestion strip
    private val labels = arrayOfNulls<String>(3)
    private var stripPointer = -1
    private var stripPressed = -1

    // Dial ring
    private var ringAngle = 0f
    private var ringAnim: ValueAnimator? = null

    // Animation state
    private var ticker: ValueAnimator? = null
    private var transitionAnim: ValueAnimator? = null
    private var transition = 1f

    // Opening animation: 0 = keys collapsed at the bottom centre, 1 = settled
    private var intro = 1f
    private var introPending = false
    private var introAnim: ValueAnimator? = null
    private var lastTickMs = 0L
    private var lastAmbientDrawMs = 0L
    private var scanAngle = Float.NaN

    private val rippleX = FloatArray(RIPPLES)
    private val rippleY = FloatArray(RIPPLES)
    private val rippleStart = LongArray(RIPPLES) { -1L }
    private var rippleNext = 0

    private val stripHeightPx: Float get() = if (suggestionsEnabled) STRIP_DP * density else 0f

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            val b = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (b != bottomInset) {
                bottomInset = b
                requestLayout()
            }
            insets
        }
        applySettings()
    }

    // ------------------------------------------------------------------ public API

    /** Re-reads theme / glow / ambient / haptics / suggestions. Called when the keyboard is shown. */
    fun applySettings() {
        renderer.theme = ThemeManager.byId(settings.themeId)
        renderer.glowScale = 0.15f + 0.95f * settings.glowIntensity / 100f
        ambientEnabled = settings.ambientAnimation
        haptics = settings.haptics
        touch.longPressMs = settings.longPressMs.toLong()
        val wasStrip = suggestionsEnabled
        suggestionsEnabled = settings.showSuggestions
        if (wasStrip != suggestionsEnabled) {
            if (!suggestionsEnabled) labels.fill(null)
            requestLayout()
        } else if (width > 0 && height > 0) {
            renderer.buildBackground(width, height, geometry)
        }
        invalidate()
    }

    /** Pushes the logical keyboard state in. Layer/language changes rebuild the fan; shift only repaints. */
    fun setKeyboardState(layer: Layer, shift: ShiftState, language: Language) {
        val structural = layer != state.layer || language !== state.language
        state.layer = layer
        state.shift = shift
        state.language = language
        if (structural && width > 0) {
            touch.cancelAll()
            rebuildFan()
            startTransition()
        }
        invalidate()
    }

    /**
     * Plays the opening animation: the keys rise from the bottom centre and spread out into the fan, staggered row
     * by row, while the tool dial spins one full turn. Call when the keyboard is shown.
     */
    fun playIntro() {
        if (!settings.introAnimation) {
            introAnim?.cancel()
            intro = 1f
            introPending = false
            invalidate()
            return
        }
        introAnim?.cancel()
        intro = 0f                // hide the settled keyboard right away so it never flashes before the animation
        introPending = true
        invalidate()
    }

    private fun startIntro() {
        introPending = false
        introAnim?.cancel()
        ringAnim?.cancel()
        setRingAngle(0f)
        intro = 0f
        introAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                intro = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    intro = 1f
                    invalidate()
                }
            })
            start()
        }
        // The dial makes exactly one full clockwise turn and lands back on its resting slots
        ringAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = INTRO_MS
            startDelay = 60L
            interpolator = PathInterpolator(0.2f, 0.0f, 0.1f, 1.0f)
            addUpdateListener { setRingAngle(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true          // the user grabbed the dial: keep it where their finger took it
                }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) setRingAngle(0f)
                }
            })
            start()
        }
    }

    /** Ease-out with a small overshoot so keys settle into place instead of just stopping. */
    private fun easeOutBack(x: Float): Float {
        val c1 = 1.2f
        val c3 = c1 + 1f
        val m = x - 1f
        return 1f + c3 * m * m * m + c1 * m * m
    }

    /** Shows up to three suggestions in the strip (left, centre, right); null leaves a cell empty. */
    fun setSuggestions(left: String?, center: String?, right: String?) {
        if (labels[0] == left && labels[1] == center && labels[2] == right) return
        labels[0] = left
        labels[1] = center
        labels[2] = right
        invalidate()
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val screenH = resources.displayMetrics.heightPixels
        // Fan height follows the width, but never more than half the screen or 300dp (tablets); the panel is centred.
        val fanH = min(min(w * KeyboardGeometry.ASPECT, screenH * 0.52f), 300f * density)
        setMeasuredDimension(w, (fanH + stripHeightPx).toInt() + bottomInset)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        geometry.resize(w.toFloat(), max(1f, (h - bottomInset).toFloat()), density, stripHeightPx)
        dialKeys = geometry.buildDialKeys()
        for (k in dialKeys) k.rotation = ringAngle
        rebuildFan()
        renderer.buildBackground(w, h, geometry)
    }

    private fun rebuildFan() {
        fanKeys = geometry.buildFanKeys(KeyboardLayouts.fanRows(state.language, state.layer))
        allKeys = dialKeys + fanKeys              // dial first: it is drawn on top, so it wins touches
        val span = max(1f, geometry.fanEnd - geometry.fanStart)
        for (k in fanKeys) {
            var row = 0
            for (i in 0 until KeyboardGeometry.ROWS) if (abs(geometry.rowInner[i] - k.rInner) < 0.5f) row = i
            k.introDelay = (KeyboardGeometry.ROWS - 1 - row) * 0.09f + abs(k.midAngle - 270f) / span * 0.14f
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        if (introPending && width > 0) startIntro()
        val now = SystemClock.uptimeMillis()
        val t = if (ambientEnabled) (now % 1_000_000L) / 1000f else 0f

        renderer.drawBackground(canvas)
        renderer.drawStrip(canvas, geometry, labels, stripPressed)

        // Fan keys, with a short fade + swivel when the layer or language changes
        val tr = transition
        val fan = fanKeys
        val saved = if (tr < 1f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * tr).toInt()) else -1
        if (tr < 1f) {
            val sc = 0.97f + 0.03f * tr
            canvas.scale(sc, sc, width / 2f, geometry.stripH + (geometry.viewH - geometry.stripH) / 2f)
        }
        if (intro < 1f) {
            // Keys start collapsed (small, tilted) at the bottom centre and fly out to their own cell
            val ox = width / 2f
            val oy = geometry.viewH + 10f * density
            for (i in fan.indices) {
                val k = fan[i]
                val p = ((intro - k.introDelay) / INTRO_KEY_SPAN).coerceIn(0f, 1f)
                if (p <= 0f) continue
                val e = easeOutBack(p)
                val kx = RadialShapes.px(k.cx, k.midRadius, k.midAngle)
                val ky = RadialShapes.py(k.cy, k.midRadius, k.midAngle)
                val s = 0.15f + 0.85f * e
                val a = (255 * min(1f, p * 3f)).toInt()
                // An offscreen layer is only needed while the key is still fading in
                val layer = if (a < 255) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), a) else canvas.save()
                canvas.translate((ox - kx) * (1f - e), (oy - ky) * (1f - e))
                canvas.rotate((1f - e) * (k.midAngle - 270f) * 0.6f, kx, ky)
                canvas.scale(s, s, kx, ky)
                renderer.drawKey(canvas, k, state)
                canvas.restoreToCount(layer)
            }
        } else {
            for (i in fan.indices) renderer.drawKey(canvas, fan[i], state)
        }
        if (saved >= 0) canvas.restoreToCount(saved)

        renderer.drawDial(canvas, geometry, dialKeys, state, ringAngle, t)

        if (ambientEnabled && !scanAngle.isNaN()) renderer.drawScan(canvas, geometry, scanAngle)

        for (i in 0 until RIPPLES) {
            val start = rippleStart[i]
            if (start < 0L) continue
            val p = (now - start) / RIPPLE_MS.toFloat()
            if (p >= 1f) rippleStart[i] = -1L else renderer.drawRipple(canvas, rippleX[i], rippleY[i], p)
        }
    }

    // ------------------------------------------------------------------ animation

    private fun startTicker() {
        if (ticker != null) return
        lastTickMs = SystemClock.uptimeMillis()
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { onTick() }
            start()
        }
    }

    private fun stopTicker() {
        ticker?.let {
            it.removeAllUpdateListeners()
            it.cancel()
        }
        ticker = null
    }

    private fun onTick() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTickMs).coerceIn(0L, 100L) / 1000f
        lastTickMs = now
        var redraw = false

        // Press glow: fast attack, slower neon fade-out
        val keys = allKeys
        for (i in keys.indices) {
            val k = keys[i]
            val target = if (k.pressCount > 0) 1f else 0f
            if (k.glow != target) {
                val rate = if (target > k.glow) 30f else 9f
                k.glow += (target - k.glow) * (1f - exp(-rate * dt))
                if (abs(k.glow - target) < 0.01f) k.glow = target
                redraw = true
            }
        }

        // Scanning light: a 1.5 s sweep every 6.5 s; keys brighten as the beam passes
        if (ambientEnabled) {
            val phase = now % SCAN_PERIOD_MS
            val wasScanning = !scanAngle.isNaN()
            scanAngle = if (phase < SCAN_SWEEP_MS) {
                val f = phase / SCAN_SWEEP_MS.toFloat()
                val pad = (geometry.fanEnd - geometry.fanStart) / 5f
                val from = geometry.fanStart - pad
                from + (geometry.fanEnd - geometry.fanStart + 2f * pad) * f
            } else {
                Float.NaN
            }
            val scanning = !scanAngle.isNaN()
            val fan = fanKeys
            val fall = max(2f, (geometry.fanEnd - geometry.fanStart) / 6f)
            for (i in fan.indices) {
                val k = fan[i]
                k.ambient = if (scanning) max(0f, 1f - abs(k.midAngle - scanAngle) / fall) else 0f
            }
            if (scanning || wasScanning) redraw = true
        }

        for (i in 0 until RIPPLES) if (rippleStart[i] >= 0L) redraw = true

        if (redraw) {
            lastAmbientDrawMs = now
            invalidate()
        } else if (ambientEnabled && now - lastAmbientDrawMs >= AMBIENT_FRAME_MS) {
            lastAmbientDrawMs = now          // hub rotation: ~25 fps is plenty
            invalidate()
        }
    }

    private fun startTransition() {
        transitionAnim?.cancel()
        transition = 0f
        transitionAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                transition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun addRipple(x: Float, y: Float) {
        rippleX[rippleNext] = x
        rippleY[rippleNext] = y
        rippleStart[rippleNext] = SystemClock.uptimeMillis()
        rippleNext = (rippleNext + 1) % RIPPLES
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTicker()
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        transitionAnim?.cancel()
        introAnim?.cancel()
        ringAnim?.cancel()
        intro = 1f
        introPending = false
        touch.cancelAll()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startTicker() else {
            stopTicker()
            touch.cancelAll()
        }
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (suggestionsEnabled && handleStripTouch(event)) return true
        return touch.onTouchEvent(event)
    }

    /** Taps on the suggestion strip are handled here; returns true when the event was consumed by the strip. */
    private fun handleStripTouch(event: MotionEvent): Boolean {
        val stripH = geometry.stripH
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                if (stripPointer < 0 && event.getY(i) < stripH) {
                    stripPointer = event.getPointerId(i)
                    stripPressed = stripCell(event.getX(i))
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (stripPointer >= 0) {
                    val idx = event.findPointerIndex(stripPointer)
                    if (idx >= 0) {
                        val cell = if (event.getY(idx) < stripH) stripCell(event.getX(idx)) else -1
                        if (cell != stripPressed) {
                            stripPressed = cell
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (stripPointer >= 0 && event.getPointerId(event.actionIndex) == stripPointer) {
                    val idx = event.actionIndex
                    val cell = if (event.getY(idx) < stripH) stripCell(event.getX(idx)) else -1
                    val fire = cell >= 0 && cell == stripPressed && labels[cell] != null
                    stripPointer = -1
                    stripPressed = -1
                    invalidate()
                    if (fire) {
                        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        keyListener?.onSuggestionPicked(cell)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (stripPointer >= 0) {
                    stripPointer = -1
                    stripPressed = -1
                    invalidate()
                }
            }
        }
        return false
    }

    private fun stripCell(x: Float): Int = (x / (width / 3f)).toInt().coerceIn(0, 2)

    override fun onKeyDown(key: RadialKey, x: Float, y: Float) {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        addRipple(x, y)
        if (ticker == null) key.glow = 1f      // animators disabled: still show the press
        invalidate()
    }

    override fun onKeyUp(key: RadialKey) {
        if (ticker == null) key.glow = 0f
        invalidate()
    }

    override fun onKeyCommit(key: RadialKey) {
        keyListener?.onKeyCommit(key)
    }

    override fun onKeyLongPress(key: RadialKey): Boolean {
        val handled = keyListener?.onKeyLongPress(key) ?: false
        if (handled && haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return handled
    }

    // ------------------------------------------------------------------ dial turning

    private fun setRingAngle(a: Float) {
        ringAngle = a
        val slots = dialKeys
        for (i in slots.indices) slots[i].rotation = a
        invalidate()
    }

    override fun onDialDrag(deltaDegrees: Float) {
        ringAnim?.cancel()
        val old = ringAngle
        setRingAngle(old + deltaDegrees)
        // A detent "tick" every time a slot boundary passes, like a real rotary dial
        if (haptics && floor(old / KeyboardGeometry.SLOT_DEGREES) != floor(ringAngle / KeyboardGeometry.SLOT_DEGREES)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onDialRelease(velocityDegPerSec: Float) {
        val slot = KeyboardGeometry.SLOT_DEGREES
        val fling = (velocityDegPerSec * 0.12f).coerceIn(-150f, 150f)
        val from = ringAngle
        val target = Math.round((from + fling) / slot) * slot
        if (abs(target - from) < 0.5f) {
            setRingAngle(target)
            return
        }
        ringAnim?.cancel()
        ringAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = (140f + abs(target - from) * 2.2f).toLong().coerceAtMost(420L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { setRingAngle(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setRingAngle(((ringAngle % 360f) + 360f) % 360f)   // same picture, smaller number
                }
            })
            start()
        }
    }

    private companion object {
        const val RIPPLES = 4
        const val RIPPLE_MS = 340L
        const val SCAN_PERIOD_MS = 6500L
        const val SCAN_SWEEP_MS = 1500L
        const val AMBIENT_FRAME_MS = 40L
        const val STRIP_DP = 42f
        const val INTRO_MS = 950L
        const val INTRO_KEY_SPAN = 0.52f       // share of the intro each key spends moving (rest is stagger)
    }
}
