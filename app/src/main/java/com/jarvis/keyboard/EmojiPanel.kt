package com.jarvis.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import androidx.emoji2.emojipicker.EmojiPickerView

/**
 * The emoji page of the keyboard: Google's AndroidX [EmojiPickerView] (categories, skin tones, recents, current
 * Unicode emoji) with a slim bar underneath holding "ABC" (back to the letters) and a repeating backspace.
 * It sits on top of the keyboard view and takes exactly its place, so the keyboard does not change size.
 */
class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onBack: () -> Unit
) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val abc: TextView
    private val del: TextView

    private val repeatDelete = object : Runnable {
        override fun run() {
            onBackspace()
            main.postDelayed(this, REPEAT_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(2, 4, 8))

        // The picker is styled with AppCompat attributes, which an input method's own theme does not have.
        val picker = EmojiPickerView(ContextThemeWrapper(context, R.style.EmojiPickerTheme)).apply {
            emojiGridColumns = 8
            setOnEmojiPickedListener { item -> onEmoji(item.emoji) }
        }
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        abc = barButton("ABC")
        abc.setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onBack()
        }
        del = barButton("⌫")
        del.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onBackspace()
                    main.postDelayed(repeatDelete, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeatDelete)
                }
            }
            true
        }
        bar.addView(abc, LayoutParams(dp(96), dp(44)))
        bar.addView(View(context), LayoutParams(0, 1, 1f))
        bar.addView(del, LayoutParams(dp(96), dp(44)))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Same navigation-bar clearance as the keyboard view
        setOnApplyWindowInsetsListener { _, insets ->
            val b = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (paddingBottom != b) setPadding(0, 0, 0, b)
            insets
        }
        applyTheme(ThemeManager.byId(KeyboardSettings(context).themeId))
    }

    /** Colours the bar buttons with the current keyboard theme. */
    fun applyTheme(theme: JarvisTheme) {
        for (b in listOf(abc, del)) {
            b.setTextColor(theme.accent)
            b.background = GradientDrawable().apply {
                setColor(theme.keyFill)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), theme.accent)
            }
        }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(repeatDelete)
        super.onDetachedFromWindow()
    }

    private fun barButton(label: String) = TextView(context).apply {
        text = label
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isClickable = true
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 55L
    }
}
