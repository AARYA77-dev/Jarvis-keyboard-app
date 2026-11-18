package com.jarvis.keyboard

import android.content.Context
import android.content.SharedPreferences

/** Thin SharedPreferences wrapper. Activity and IME service run in the same process and share it. */
class KeyboardSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var themeId: String
        get() = prefs.getString(KEY_THEME, ThemeManager.DEFAULT_ID) ?: ThemeManager.DEFAULT_ID
        set(value) { prefs.edit().putString(KEY_THEME, value).apply() }

    var languageId: String
        get() = prefs.getString(KEY_LANGUAGE, "en") ?: "en"
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) { prefs.edit().putBoolean(KEY_HAPTICS, value).apply() }

    /** Hub rotation + scanning light. Off = the view only redraws on touch (best for battery). */
    var ambientAnimation: Boolean
        get() = prefs.getBoolean(KEY_AMBIENT, true)
        set(value) { prefs.edit().putBoolean(KEY_AMBIENT, value).apply() }

    /** Opening animation: keys rise from the bottom and fan out, the dial spins once. */
    var introAnimation: Boolean
        get() = prefs.getBoolean(KEY_INTRO, true)
        set(value) { prefs.edit().putBoolean(KEY_INTRO, value).apply() }

    /** Greeting shown in the suggestion strip when the keyboard opens on an empty field, until the user types. */
    var greetingEnabled: Boolean
        get() = prefs.getBoolean(KEY_GREET_ON, true)
        set(value) { prefs.edit().putBoolean(KEY_GREET_ON, value).apply() }

    /** What the greeting says, e.g. "Hi Aarya". */
    var greetingText: String
        get() = prefs.getString(KEY_GREET_TEXT, DEFAULT_GREETING) ?: DEFAULT_GREETING
        set(value) { prefs.edit().putString(KEY_GREET_TEXT, value).apply() }

    /** true = greet only the very first time the keyboard opens; false = on every fresh open. */
    var greetingOnlyOnce: Boolean
        get() = prefs.getBoolean(KEY_GREET_ONCE, false)
        set(value) { prefs.edit().putBoolean(KEY_GREET_ONCE, value).apply() }

    /** Set once the greeting has been shown (used by [greetingOnlyOnce]). */
    var greetingSeen: Boolean
        get() = prefs.getBoolean(KEY_GREET_SEEN, false)
        set(value) { prefs.edit().putBoolean(KEY_GREET_SEEN, value).apply() }

    var autoCapitalize: Boolean
        get() = prefs.getBoolean(KEY_AUTOCAP, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTOCAP, value).apply() }

    /** Suggestion strip above the keys. */
    var showSuggestions: Boolean
        get() = prefs.getBoolean(KEY_SUGGEST, true)
        set(value) { prefs.edit().putBoolean(KEY_SUGGEST, value).apply() }

    /** Learn words and word pairs from typing (stored only on this device). */
    var learnWords: Boolean
        get() = prefs.getBoolean(KEY_LEARN, true)
        set(value) { prefs.edit().putBoolean(KEY_LEARN, value).apply() }

    /** Bumped by the settings screen; the service clears its in-memory learned words when it changes. */
    var learnResetToken: Int
        get() = prefs.getInt(KEY_LEARN_RESET, 0)
        set(value) { prefs.edit().putInt(KEY_LEARN_RESET, value).apply() }

    /** 0..100 */
    var glowIntensity: Int
        get() = prefs.getInt(KEY_GLOW, 70)
        set(value) { prefs.edit().putInt(KEY_GLOW, value.coerceIn(0, 100)).apply() }

    var longPressMs: Int
        get() = prefs.getInt(KEY_LONG_PRESS, 350)
        set(value) { prefs.edit().putInt(KEY_LONG_PRESS, value.coerceIn(150, 800)).apply() }

    companion object {
        const val USER_WORDS_FILE = "user_words.txt"
        const val DEFAULT_GREETING = "Hi there"
        const val GREETING_MAX_CHARS = 30

        private const val PREFS = "jarvis_keyboard"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_AMBIENT = "ambient"
        private const val KEY_INTRO = "intro_animation"
        private const val KEY_GREET_ON = "greeting_on"
        private const val KEY_GREET_TEXT = "greeting_text"
        private const val KEY_GREET_ONCE = "greeting_once"
        private const val KEY_GREET_SEEN = "greeting_seen"
        private const val KEY_AUTOCAP = "autocap"
        private const val KEY_SUGGEST = "suggestions"
        private const val KEY_LEARN = "learn_words"
        private const val KEY_LEARN_RESET = "learn_reset"
        private const val KEY_GLOW = "glow"
        private const val KEY_LONG_PRESS = "long_press_ms"
    }
}
