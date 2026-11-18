package com.jarvis.keyboard

import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Multi-touch aware hit testing and gesture timing. It knows nothing about drawing or text input:
 * it reports down / up / commit / long-press and dial rotation through [Listener].
 *
 *  - Letters and most keys commit on finger-up (sliding onto another fan key retargets the press).
 *  - Backspace acts on finger-down and auto-repeats while held.
 *  - A touch that starts on the tool dial is a tap on a tool, or - once the finger moves - a drag that turns the
 *    ring. Holding a cursor tool without moving repeats it.
 *  - A long press on a key whose [Listener.onKeyLongPress] returns true suppresses the normal commit.
 */
class KeyboardTouchHandler(
    private val keys: () -> List<RadialKey>,
    private val listener: Listener,
    private val slopPx: Float,
    private val rotateSlopPx: Float
) {

    interface Listener {
        fun onKeyDown(key: RadialKey, x: Float, y: Float)
        fun onKeyUp(key: RadialKey)
        fun onKeyCommit(key: RadialKey)
        fun onKeyLongPress(key: RadialKey): Boolean
        /** The dial ring was turned by [deltaDegrees] (clockwise positive) following the finger. */
        fun onDialDrag(deltaDegrees: Float)
        /** The finger left the dial after turning it; [velocityDegPerSec] is the fling speed. */
        fun onDialRelease(velocityDegPerSec: Float)
    }

    var longPressMs: Long = 350L

    private val handler = Handler(Looper.getMainLooper())
    private val trackers = SparseArray<Tracker>()

    private inner class Tracker : Runnable {
        var key: RadialKey? = null
        var longFired = false
        var repeating = false

        // Dial gesture state
        var onDial = false
        var rotating = false
        var cx = 0f
        var cy = 0f
        var downX = 0f
        var downY = 0f
        var lastAngle = 0f
        var lastTime = 0L
        var velocity = 0f

        override fun run() {
            val k = key ?: return
            if (isRepeatable(k)) {
                repeating = true
                listener.onKeyCommit(k)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            } else if (!longFired && listener.onKeyLongPress(k)) {
                longFired = true
            }
        }
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                down(ev.getPointerId(i), ev.getX(i), ev.getY(i), ev.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) move(ev.getPointerId(i), ev.getX(i), ev.getY(i), ev.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                up(ev.getPointerId(ev.actionIndex), commit = true, time = ev.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    fun cancelAll() {
        for (i in 0 until trackers.size()) {
            val t = trackers.valueAt(i)
            val wasRotating = t.rotating
            release(t)
            if (wasRotating) listener.onDialRelease(0f)
        }
        trackers.clear()
    }

    /**
     * The dial sits on top of the keys: its slots win, and the rest of its disc (hub, tick band) swallows the
     * touch. Otherwise exact cell first, then the nearest fan key within the slop radius (covers gaps and edges).
     */
    fun findKey(x: Float, y: Float): RadialKey? {
        val list = keys()
        var insideDial = false
        for (i in list.indices) {
            val k = list[i]
            if (k.zone != Zone.DIAL) continue
            if (k.contains(x, y)) return k
            val dx = x - k.cx
            val dy = y - k.cy
            if (dx >= 0f && dy <= 0f && dx * dx + dy * dy <= k.rOuter * k.rOuter) insideDial = true
        }
        if (insideDial) return null
        var best: RadialKey? = null
        var bestDist = slopPx
        for (i in list.indices) {
            val k = list[i]
            if (k.zone == Zone.DIAL) continue
            if (k.contains(x, y)) return k
            val d = k.distanceTo(x, y)
            if (d < bestDist) {
                bestDist = d
                best = k
            }
        }
        return best
    }

    private fun down(id: Int, x: Float, y: Float, time: Long) {
        val key = findKey(x, y) ?: return
        val t = Tracker()
        trackers.put(id, t)
        press(t, key, x, y, time)
    }

    private fun move(id: Int, x: Float, y: Float, time: Long) {
        val t = trackers.get(id) ?: return
        if (t.onDial) {
            moveDial(t, x, y, time)
            return
        }
        val current = t.key ?: return
        if (t.longFired || t.repeating) return
        if (current.contains(x, y)) return
        val next = findKey(x, y) ?: return
        if (next === current || next.zone == Zone.DIAL) return    // drifting onto the dial never triggers a tool
        release(t)
        press(t, next, x, y, time)
    }

    private fun moveDial(t: Tracker, x: Float, y: Float, time: Long) {
        if (t.longFired || t.repeating) return
        if (!t.rotating) {
            if (hypot(x - t.downX, y - t.downY) < rotateSlopPx) return
            t.rotating = true
            release(t)                         // no longer a tap: drop the press, cancel hold timers
        }
        val angle = angleOf(x - t.cx, y - t.cy)
        var delta = angle - t.lastAngle
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        val dt = (time - t.lastTime).coerceAtLeast(1L)
        t.velocity = t.velocity * 0.6f + (delta * 1000f / dt) * 0.4f
        t.lastAngle = angle
        t.lastTime = time
        listener.onDialDrag(delta)
    }

    private fun up(id: Int, commit: Boolean, time: Long) {
        val t = trackers.get(id) ?: return
        val k = t.key
        val wasRotating = t.rotating
        val doCommit = commit && k != null && !t.longFired && !t.repeating && !wasRotating && !actsOnDown(k)
        release(t)
        trackers.remove(id)
        if (wasRotating) {
            listener.onDialRelease(if (time - t.lastTime > STALE_VELOCITY_MS) 0f else t.velocity)
        } else if (doCommit) {
            k?.let { listener.onKeyCommit(it) }
        }
    }

    private fun press(t: Tracker, key: RadialKey, x: Float, y: Float, time: Long) {
        t.key = key
        t.longFired = false
        t.repeating = false
        t.onDial = key.zone == Zone.DIAL
        t.rotating = false
        if (t.onDial) {
            t.cx = key.cx
            t.cy = key.cy
            t.downX = x
            t.downY = y
            t.lastAngle = angleOf(x - key.cx, y - key.cy)
            t.lastTime = time
            t.velocity = 0f
        }
        key.pressCount++
        listener.onKeyDown(key, x, y)
        when {
            actsOnDown(key) -> {
                listener.onKeyCommit(key)             // act immediately, then repeat while held
                handler.postDelayed(t, REPEAT_DELAY_MS)
            }
            isRepeatable(key) -> handler.postDelayed(t, REPEAT_DELAY_MS)   // dial cursor tools: hold to repeat
            else -> handler.postDelayed(t, longPressMs)
        }
    }

    private fun release(t: Tracker) {
        handler.removeCallbacks(t)
        val k = t.key ?: return
        if (k.pressCount > 0) k.pressCount--
        t.key = null
        listener.onKeyUp(k)
    }

    private fun angleOf(dx: Float, dy: Float): Float =
        Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

    private fun isRepeatable(k: RadialKey): Boolean =
        k.def.type == KeyType.BACKSPACE || k.def.type == KeyType.CURSOR_LEFT || k.def.type == KeyType.CURSOR_RIGHT

    /** Fan keys that repeat (backspace) fire on touch-down; dial tools wait to see whether it is a drag. */
    private fun actsOnDown(k: RadialKey): Boolean = isRepeatable(k) && k.zone == Zone.FAN

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        const val STALE_VELOCITY_MS = 90L
    }
}
