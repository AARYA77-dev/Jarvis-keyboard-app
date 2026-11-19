package com.jarvis.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Setup + settings screen, built from plain framework views (no AppCompat / Compose dependency).
 * Step 1 enables the IME, step 2 makes it the active keyboard, then the settings below tune it.
 */
class MainActivity : Activity() {

    private lateinit var settings: KeyboardSettings
    private lateinit var enableStatus: TextView
    private lateinit var selectStatus: TextView
    private val themeButtons = ArrayList<Pair<JarvisTheme, Button>>()

    private val accent get() = ThemeManager.byId(settings.themeId).accent
    private val dim = Color.rgb(130, 160, 175)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        col.addView(label("J.A.R.V.I.S", 28f, accent, bold = true))
        col.addView(label("RADIAL KEYBOARD", 13f, dim, spacing = 0.3f))

        col.addView(section("1  ENABLE"))
        enableStatus = label("", 13f, dim)
        col.addView(enableStatus)
        col.addView(button("Open keyboard settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })

        col.addView(section("2  SELECT"))
        selectStatus = label("", 13f, dim)
        col.addView(selectStatus)
        col.addView(button("Choose JARVIS as active keyboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        col.addView(section("TEST"))
        col.addView(EditText(this).apply {
            hint = "Tap here and type..."
            setHintTextColor(dim)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = Gravity.TOP
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outline(Color.TRANSPARENT, dp(8))
        }, lp(top = 8))

        col.addView(section("THEME"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (theme in ThemeManager.themes) {
            val b = Button(this).apply {
                text = theme.name.substringBefore(' ')
                isAllCaps = false
                setTextColor(theme.accent)
                setOnClickListener {
                    settings.themeId = theme.id
                    refreshThemeButtons()
                }
            }
            themeButtons.add(theme to b)
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(row)
        refreshThemeButtons()

        col.addView(section("BEHAVIOUR"))
        col.addView(toggle("Haptic feedback", settings.haptics) { settings.haptics = it })
        col.addView(toggle("Ambient animation (scan light, rotating hubs)", settings.ambientAnimation) {
            settings.ambientAnimation = it
        })
        col.addView(toggle("Opening animation (keys fan out, dial spins)", settings.introAnimation) {
            settings.introAnimation = it
        })
        col.addView(toggle("Auto-capitalise sentences", settings.autoCapitalize) { settings.autoCapitalize = it })
        col.addView(toggle("Show suggestions", settings.showSuggestions) { settings.showSuggestions = it })
        col.addView(toggle("Greeting when the keyboard opens", settings.greetingEnabled) {
            settings.greetingEnabled = it
        })
        col.addView(label("Greeting text (shown in the suggestion bar until you start typing)", 13f, dim), lp(top = 4))
        col.addView(EditText(this).apply {
            setText(settings.greetingText)
            hint = "Hi Aarya"
            setHintTextColor(dim)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(KeyboardSettings.GREETING_MAX_CHARS))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = outline(Color.TRANSPARENT, dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { settings.greetingText = s?.toString() ?: "" }
            })
        }, lp(top = 4))
        col.addView(toggle("Greet only the first time the keyboard ever opens", settings.greetingOnlyOnce) {
            settings.greetingOnlyOnce = it
            if (it) settings.greetingSeen = false        // turning it on starts fresh: next open greets once
        })
        col.addView(toggle("Learn words from my typing (stays on this phone)", settings.learnWords) {
            settings.learnWords = it
        })
        col.addView(button("Clear learned words") { clearLearnedWords() }, lp(top = 8))

        col.addView(label("Neon glow", 13f, dim), lp(top = 14))
        col.addView(SeekBar(this).apply {
            max = 100
            progress = settings.glowIntensity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) settings.glowIntensity = value
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })

        col.addView(label("Long-press delay", 13f, dim), lp(top = 14))
        col.addView(SeekBar(this).apply {
            max = 650                                   // 150..800 ms
            progress = settings.longPressMs - 150
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) settings.longPressMs = value + 150
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })

        col.addView(section("HOW TO USE"))
        col.addView(label(
            "Keys: type as usual. Shift and backspace sit at the ends of the third row, enter at the end of the bottom row.\n\n" +
                "Left dial: tap a tool (language, 123, #+=), or hold and drag the dial to rotate it and bring more tools into view " +
                "(emoji picker, cursor arrows, select all, copy, cut, paste, delete word, hide keyboard, settings). " +
                "Hold the globe for the keyboard picker. Double-tap or hold SHIFT (left end of the third row) for caps lock.\n\n" +
                "Suggestions: tap one to insert it. Hold a letter for its number or punctuation twin.",
            13f, dim
        ))

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(4, 6, 10))
            fitsSystemWindows = true
            addView(col)
        }
    }

    private fun clearLearnedWords() {
        File(filesDir, KeyboardSettings.USER_WORDS_FILE).delete()
        settings.learnResetToken = settings.learnResetToken + 1     // tells the running keyboard to forget too
        Toast.makeText(this, "Learned words cleared", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val enabled = isEnabled()
        enableStatus.text = if (enabled) "Enabled" else "Not enabled yet"
        enableStatus.setTextColor(if (enabled) accent else dim)
        val selected = isSelected()
        selectStatus.text = if (selected) "Active keyboard" else "Not the active keyboard"
        selectStatus.setTextColor(if (selected) accent else dim)
        refreshThemeButtons()
    }

    private fun refreshThemeButtons() {
        for ((theme, b) in themeButtons) {
            val on = theme.id == settings.themeId
            b.background = outline(if (on) Color.argb(60, Color.red(theme.accent), Color.green(theme.accent), Color.blue(theme.accent)) else Color.TRANSPARENT, dp(8), theme.accent)
        }
    }

    private fun isEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }

    // ------------------------------------------------------------------ small view helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            letterSpacing = spacing
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun section(s: String) = label(s, 12f, accent, bold = true, spacing = 0.2f).also {
        it.setPadding(0, dp(26), 0, dp(6))
    }

    private fun outline(fill: Int, radius: Int, color: Int = accent) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), color)
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(accent)
        background = outline(Color.TRANSPARENT, dp(8))
        setOnClickListener { onClick() }
    }

    private fun toggle(s: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = s
        setTextColor(Color.WHITE)
        isChecked = initial
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }
}
