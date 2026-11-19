# JARVIS – Radial Android Keyboard

A real Android system keyboard (InputMethodService) drawn entirely with Canvas: a suggestion strip, four
gently curved key rows that run edge to edge, and one rotating tool dial in the bottom-left corner. Emoji picker: AndroidX `emoji2-emojipicker` (opened from the dial).

## Build and install
1. Android Studio (current stable) → **Open** this folder → let Gradle sync (JDK 17; Studio's bundled JDK works).
   If Studio offers to upgrade AGP/Gradle, accepting is fine.
2. Run on a device, or **Build > Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`.
3. Open **JARVIS Keyboard** → *1 Enable* (toggle it on) → *2 Select* (pick it in the picker).

Installing over an older debug build keeps your settings. CLI users: `gradle wrapper --gradle-version 8.9` once,
then `./gradlew assembleDebug`.

## Using it
- **Keys:** four rows, edge to edge: Q-P / A-L / shift Z-M backspace / `,` space `.` enter. Hold a letter for its number or
  punctuation twin. The dial is a notch in the bottom-left corner; shift's key tucks behind its edge.
- **Suggestion strip:** three cells, best match in the centre. Tap one to insert it (a space is added; typing `.` or `,`
  right after replaces that space). What you typed appears in quotes on the left when it differs from the best match.
  With nothing typed it predicts the next word. It is hidden in password, e-mail and URL fields.
- **Tool dial (bottom-left):** at rest you see Language, 123 and `#+=`. **Hold and drag the dial to rotate it** (it snaps
  and ticks every slot) to reach: cursor left/right (hold to repeat), select all, copy, cut, paste, delete word, hide
  keyboard, settings. Hold the globe for the system keyboard picker; double-tap or hold Shift for caps lock.

## Suggestions
Bundled frequency dictionaries + word-pair statistics for EN / DE / FR / ES (`app/src/main/assets/dict/`), matched
accent-insensitively (typing `cafe` offers `café`, `fur` offers `für`), plus typo correction. The keyboard also learns the
words and word pairs you type (after you use one twice) and stores them only on the phone, in `files/user_words.txt`.
It never learns in password / e-mail / URL fields or when an app asks for no personalised learning (e.g. incognito).
Settings has *Show suggestions*, *Learn words* and *Clear learned words*. Backups are disabled so this file never leaves
the device. If you publish the app, check the licence of the word-list data you bundle.

## Architecture (`app/src/main/java/com/jarvis/keyboard/`)
| File | Responsibility |
|---|---|
| `KeyboardGeometry.kt` | Pure maths: strip, row arcs (edge-to-edge, one shared centre), dial ring from (w, h, density); `RadialShapes.sector()` path builder |
| `RadialKey.kt` | One annular-sector key: exact curved hit test, slop distance, ring rotation, glow state |
| `KeyboardLayouts.kt` | Languages, layers, key definitions, the 12 dial tools |
| `RadialKeyboardRenderer.kt` | All drawing: cached carbon-fibre background, neon strokes, icons, strip, dial, hub, scan beam |
| `KeyboardTouchHandler.kt` | Multi-touch hit testing, long-press, auto-repeat, dial-drag gesture + fling |
| `RadialKeyboardView.kt` | View glue: measure, animation ticker, suggestion-strip taps, dial snapping, insets, haptics |
| `KeyboardService.kt` | InputMethodService: shift/layer/language state, InputConnection calls, suggestions flow |
| `suggest/` | `SuggestionEngine`, `WordDictionary`, `UserModel`, `TextContext`, `TextNorm` (pure Kotlin, no Android) |
| `KeyboardSettings.kt` / `ThemeManager.kt` | SharedPreferences + 4 colour themes |
| `MainActivity.kt` | Setup + settings screen (plain framework views) |

App icon: adaptive icon (`res/mipmap-anydpi-v26`, layers in `res/drawable-nodpi`) with a themed monochrome layer for
Android 13+, plus PNG fallbacks in `res/mipmap-*dpi` for Android 7.x.

## Verification status
- All Kotlin sources compile cleanly (Kotlin 2.0.21 against the API 35 `android.jar`); resources and manifest
  references were checked; the suggestion engine was run on the JVM against the real dictionaries (typos, accents,
  next-word, learning); the layout geometry was verified numerically for phone, small-phone, landscape and tablet sizes.
- **Not yet run through Gradle or on a device**: AGP build, touch feel, dial-drag feel and real-device rendering are untested.
  Tunables in `KeyboardGeometry`: `SAGITTA_FRACTION` (row curvature), `ASPECT` (height), `DIAL_FACTOR` (dial size), gap, chamfer; the drag threshold and fling in `RadialKeyboardView` / `KeyboardTouchHandler`.

## Known limitations
- No autocorrect-on-space or swipe typing yet; no accented-letter long-press (accents come through suggestions).
- Landscape shows the same panel centred rather than stretched.
