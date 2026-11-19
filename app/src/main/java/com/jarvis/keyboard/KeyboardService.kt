package com.jarvis.keyboard

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.jarvis.keyboard.suggest.SuggestionEngine
import com.jarvis.keyboard.suggest.TextContext
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The actual system keyboard. It owns the *logical* state (layer, shift, language), turns key events from
 * [RadialKeyboardView] into InputConnection calls, and feeds the suggestion strip. No geometry or drawing
 * code lives here.
 */
class KeyboardService : InputMethodService(), RadialKeyboardView.KeyListener {

    /** What a strip cell shows ([label]) and what tapping it types ([text]). */
    private class Slot(val text: String, val label: String)

    private lateinit var settings: KeyboardSettings
    private val engine = SuggestionEngine()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var view: RadialKeyboardView? = null

    private var layer = Layer.LETTERS
    private var shift = ShiftState.OFF
    private var languageIndex = 0
    private var lastShiftTapMs = 0L

    private var suggestionsAllowed = true
    private var learningAllowed = true
    private var slots: Array<Slot?> = arrayOfNulls(3)

    /** True while the greeting owns the strip: from opening on an empty field until the user types something. */
    private var greetingActive = false

    private var emojiPanel: EmojiPanel? = null
    private var swapSpace = false          // a suggestion just added a trailing space that punctuation may replace
    private var learnResetSeen = 0

    private val refreshRunnable = Runnable { refreshSuggestions() }
    private val saveRunnable = Runnable { saveUserWords() }

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        languageIndex = KeyboardLayouts.languageIndex(settings.languageId)
        learnResetSeen = settings.learnResetToken
        loadUserWords()
        loadLanguage(KeyboardLayouts.languages[languageIndex].id)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        saveUserWords()
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = RadialKeyboardView(this)
        v.keyListener = this
        view = v
        val panel = EmojiPanel(this, ::commitEmoji, ::backspace, ::hideEmoji)
        panel.visibility = View.GONE
        emojiPanel = panel
        // The emoji panel is stacked on the keyboard and takes exactly its size (the keyboard stays measured while hidden)
        val root = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
                v.measure(exactWidth, heightMeasureSpec)
                val keyboardHeight = v.measuredHeight
                panel.measure(exactWidth, MeasureSpec.makeMeasureSpec(keyboardHeight, MeasureSpec.EXACTLY))
                setMeasuredDimension(
                    resolveSize(width, widthMeasureSpec),
                    keyboardHeight
                )
            }
        }
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pushState()
        return root
    }

    private fun showEmoji() {
        val p = emojiPanel ?: return
        p.applyTheme(ThemeManager.byId(settings.themeId))
        view?.visibility = View.INVISIBLE
        p.visibility = View.VISIBLE
    }

    private fun hideEmoji() {
        emojiPanel?.visibility = View.GONE
        view?.visibility = View.VISIBLE
    }

    private fun commitEmoji(emoji: String) {
        val ic = currentInputConnection ?: return
        greetingActive = false
        swapSpace = false
        ic.commitText(emoji, 1)
        updateAutoShift()
        scheduleRefresh()
    }

    /** Never take over the screen in landscape; the radial panel scales down instead. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        view?.applySettings()
        if (!restarting) view?.playIntro()
        languageIndex = KeyboardLayouts.languageIndex(settings.languageId)
        loadLanguage(KeyboardLayouts.languages[languageIndex].id)
        if (settings.learnResetToken != learnResetSeen) {          // "Clear learned words" was pressed in settings
            learnResetSeen = settings.learnResetToken
            engine.user.clear()
        }
        configureForEditor(info)
        if (!restarting) {
            hideEmoji()
            greetingActive = shouldGreet()
        }
        if (!restarting) {
            val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            layer = when (cls) {
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Layer.NUMBERS
                else -> Layer.LETTERS
            }
            shift = ShiftState.OFF
        }
        swapSpace = false
        pushState()
        updateAutoShift()
        scheduleRefresh()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        main.removeCallbacks(refreshRunnable)
        saveUserWords()
        view?.setSuggestions(null, null, null)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoShift()
        scheduleRefresh()
    }

    // ------------------------------------------------------------------ key events

    override fun onKeyCommit(key: RadialKey) {
        when (key.def.type) {
            KeyType.CHAR -> typeText(key.def.output)
            KeyType.SPACE -> typeText(" ")
            KeyType.BACKSPACE -> backspace()
            KeyType.ENTER -> enter()
            KeyType.SHIFT -> tapShift()
            KeyType.MODE_NUM -> {
                layer = if (layer == Layer.LETTERS) Layer.NUMBERS else Layer.LETTERS
                afterLayerChange()
            }
            KeyType.MODE_SYM -> {
                layer = if (layer == Layer.SYMBOLS) Layer.NUMBERS else Layer.SYMBOLS
                afterLayerChange()
            }
            KeyType.LANG -> nextLanguage()
            KeyType.SETTINGS -> openSettings()
            KeyType.CURSOR_LEFT -> moveCursor(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyType.CURSOR_RIGHT -> moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT)
            KeyType.SELECT_ALL -> editAction(android.R.id.selectAll)
            KeyType.COPY -> editAction(android.R.id.copy)
            KeyType.CUT -> editAction(android.R.id.cut)
            KeyType.PASTE -> editAction(android.R.id.paste)
            KeyType.DEL_WORD -> deleteWord()
            KeyType.HIDE -> requestHideSelf(0)
            KeyType.EMOJI -> showEmoji()
        }
    }

    override fun onKeyLongPress(key: RadialKey): Boolean {
        when (key.def.type) {
            KeyType.CHAR -> {
                val hint = key.def.hint ?: return false      // letters: long-press types the number/punctuation twin
                swapSpace = false
                currentInputConnection?.commitText(hint, 1)
                if (shift == ShiftState.ON) {
                    shift = ShiftState.OFF
                    pushState()
                }
                scheduleRefresh()
                return true
            }
            KeyType.SHIFT -> {
                shift = ShiftState.LOCK
                pushState()
                return true
            }
            KeyType.SPACE, KeyType.LANG -> {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                return true
            }
            else -> return false
        }
    }

    // ------------------------------------------------------------------ input actions

    private fun typeText(raw: String) {
        val ic = currentInputConnection ?: return
        greetingActive = false
        val isPunctuation = raw.length == 1 && raw[0] in SWAP_PUNCTUATION
        if (swapSpace && isPunctuation && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.deleteSurroundingText(1, 0)                    // "word ." -> "word."
        }
        swapSpace = false
        val separator = raw.length == 1 && !raw[0].isLetterOrDigit() && raw[0] != '\'' && raw[0] != '’'
        if (separator && learningAllowed && layer == Layer.LETTERS) learnTrailingWord(ic)

        val text = if (layer == Layer.LETTERS && shift != ShiftState.OFF) raw.uppercase(Locale.ROOT) else raw
        ic.commitText(text, 1)
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
            pushState()
        }
        updateAutoShift()
        scheduleRefresh()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        swapSpace = false
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)                              // delete the selection
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)         // works in WebViews and terminals too
        }
        updateAutoShift()
        scheduleRefresh()
    }

    /** Dial tool: delete back to the start of the previous word (or the selection, if there is one). */
    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        swapSpace = false
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            val count = before.length - i
            if (count > 0) ic.deleteSurroundingText(count, 0)
        }
        updateAutoShift()
        scheduleRefresh()
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        swapSpace = false
        val ei = currentInputEditorInfo
        if (ei != null) {
            val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
            val noAction = (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)                // Send / Search / Go / Done ...
                scheduleRefresh()
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        scheduleRefresh()
    }

    private fun moveCursor(keyCode: Int) {
        swapSpace = false
        sendDownUpKeyEvents(keyCode)
    }

    private fun editAction(id: Int) {
        swapSpace = false
        currentInputConnection?.performContextMenuAction(id)
    }

    private fun tapShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.LOCK -> ShiftState.OFF
            shift == ShiftState.ON && now - lastShiftTapMs < DOUBLE_TAP_MS -> ShiftState.LOCK
            shift == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastShiftTapMs = now
        pushState()
        scheduleRefresh()
    }

    private fun afterLayerChange() {
        if (layer != Layer.LETTERS && shift != ShiftState.LOCK) shift = ShiftState.OFF
        pushState()
        updateAutoShift()
        scheduleRefresh()
    }

    private fun nextLanguage() {
        languageIndex = (languageIndex + 1) % KeyboardLayouts.languages.size
        val lang = KeyboardLayouts.languages[languageIndex]
        settings.languageId = lang.id
        loadLanguage(lang.id)
        pushState()
        scheduleRefresh()
    }

    /** Sentence / word auto-capitalisation, driven by the editor's own caps mode. */
    private fun updateAutoShift() {
        if (!settings.autoCapitalize || layer != Layer.LETTERS || shift == ShiftState.LOCK) return
        val ei = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = if (ei.inputType != InputType.TYPE_NULL) ic.getCursorCapsMode(ei.inputType) else 0
        val wanted = if (caps != 0) ShiftState.ON else ShiftState.OFF
        if (wanted != shift) {
            shift = wanted
            pushState()
        }
    }

    private fun pushState() {
        view?.setKeyboardState(layer, shift, KeyboardLayouts.languages[languageIndex])
    }

    private fun openSettings() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ------------------------------------------------------------------ suggestions

    /** Decides, per text field, whether to suggest and whether to learn (never in passwords, e-mail, URLs...). */
    private fun configureForEditor(info: EditorInfo?) {
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val noSuggestions = (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val sensitive = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
        suggestionsAllowed = cls == InputType.TYPE_CLASS_TEXT && !noSuggestions && !sensitive
        val incognito = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        learningAllowed = suggestionsAllowed && !incognito && settings.learnWords
    }

    /** The greeting appears when enabled, suggestions are allowed here and the field is empty (and, if asked, only once). */
    private fun shouldGreet(): Boolean {
        if (!settings.greetingEnabled || !settings.showSuggestions || !suggestionsAllowed) return false
        if (settings.greetingText.isBlank()) return false
        if (settings.greetingOnlyOnce && settings.greetingSeen) return false
        val before = currentInputConnection?.getTextBeforeCursor(1, 0) ?: return false
        if (before.isNotEmpty()) return false
        if (settings.greetingOnlyOnce) settings.greetingSeen = true
        return true
    }

    private fun apostropheInWord(): Boolean = KeyboardLayouts.languages[languageIndex].id == "en"

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshRunnable)
        main.post(refreshRunnable)
    }

    private fun refreshSuggestions() {
        val v = view ?: return
        if (greetingActive) {
            val ic = currentInputConnection ?: return
            if (ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                slots = arrayOfNulls(3)
                v.setSuggestions(null, settings.greetingText.trim(), null)
                return
            }
            greetingActive = false                       // text appeared: greeting is gone for good in this field
        }
        val lang = KeyboardLayouts.languages[languageIndex]
        if (!settings.showSuggestions || !suggestionsAllowed || layer != Layer.LETTERS ||
            !engine.isReady || engine.language != lang.id
        ) {
            slots = arrayOfNulls(3)
            v.setSuggestions(null, null, null)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
        val ctx = TextContext.parse(before, apostropheInWord())
        val list = engine.suggest(ctx.prev, ctx.fragment, ctx.sentenceStart, shift != ShiftState.OFF)
        slots = arrange(ctx.fragment, list)
        v.setSuggestions(slots[0]?.label, slots[1]?.label, slots[2]?.label)
    }

    /**
     * Strip layout, like Gboard: the best match sits in the centre. When what you typed differs from the best
     * match it is offered in quotes on the left, so you can always keep your own spelling.
     */
    private fun arrange(fragment: String, list: List<String>): Array<Slot?> {
        val out = arrayOfNulls<Slot>(3)
        if (list.isEmpty()) {
            if (fragment.length >= 2) out[1] = Slot(fragment, "\"" + fragment + "\"")
            return out
        }
        val best = list[0]
        if (fragment.length >= 2 && !best.equals(fragment, ignoreCase = true)) {
            out[0] = Slot(fragment, "\"" + fragment + "\"")
            out[1] = Slot(best, best)
            list.getOrNull(1)?.let { out[2] = Slot(it, it) }
        } else {
            out[1] = Slot(best, best)
            list.getOrNull(1)?.let { out[0] = Slot(it, it) }
            list.getOrNull(2)?.let { out[2] = Slot(it, it) }
        }
        return out
    }

    override fun onSuggestionPicked(index: Int) {
        val slot = slots.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        greetingActive = false
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
        val ctx = TextContext.parse(before, apostropheInWord())
        ic.beginBatchEdit()
        if (ctx.fragment.isNotEmpty()) ic.deleteSurroundingText(ctx.fragment.length, 0)
        ic.commitText(slot.text + " ", 1)
        ic.endBatchEdit()
        if (learningAllowed) {
            engine.learn(slot.text, ctx.prev)
            scheduleSave()
        }
        swapSpace = true
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
            pushState()
        }
        updateAutoShift()
        scheduleRefresh()                                     // now predict the next word
    }

    private fun learnTrailingWord(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
        val ctx = TextContext.parse(before, apostropheInWord())
        if (ctx.fragment.isNotEmpty()) {
            engine.learn(ctx.fragment, ctx.prev)
            scheduleSave()
        }
    }

    // ------------------------------------------------------------------ dictionaries + learned words

    private fun loadLanguage(id: String) {
        if (engine.isReady && engine.language == id) return
        io.execute {
            try {
                val dict = assets.open("dict/$id.txt").bufferedReader(Charsets.UTF_8)
                val bigrams = try {
                    assets.open("dict/${id}_bi.txt").bufferedReader(Charsets.UTF_8)
                } catch (e: IOException) {
                    null
                }
                engine.load(id, dict, bigrams)
                main.post { scheduleRefresh() }
            } catch (e: IOException) {
                // Dictionary missing: the strip just stays empty.
            }
        }
    }

    private fun loadUserWords() {
        val f = File(filesDir, KeyboardSettings.USER_WORDS_FILE)
        if (!f.exists()) return
        try {
            f.bufferedReader(Charsets.UTF_8).use { engine.user.read(it) }
        } catch (e: IOException) {
            // Unreadable file: start fresh.
        }
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun saveUserWords() {
        if (!engine.user.dirty) return
        val sw = StringWriter()
        engine.user.write(sw)                                 // snapshot on the main thread...
        val data = sw.toString()
        val f = File(filesDir, KeyboardSettings.USER_WORDS_FILE)
        try {
            io.execute {                                      // ...write on the I/O thread
                try {
                    f.writeText(data, Charsets.UTF_8)
                } catch (e: IOException) {
                    // Best effort.
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Service is shutting down.
        }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 400L
        const val CONTEXT_CHARS = 64
        const val SAVE_DELAY_MS = 20_000L
        const val SWAP_PUNCTUATION = ".,!?;:"
    }
}
