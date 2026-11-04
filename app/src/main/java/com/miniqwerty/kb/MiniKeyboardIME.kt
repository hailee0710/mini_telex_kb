package com.miniqwerty.kb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Custom Android IME implementing a compact 3-row keyboard layout with an
 * integrated Vietnamese Telex engine.
 *
 * ## Architecture
 * - [MiniKeyboardView] — Custom View canvas handling rendering & touch.
 * - [Driver] — Vikey's engine (`libvikey.so`). It does not compose: every key
 *   returns an [Edit] (delete N characters, insert text) that is applied to
 *   the editor immediately, so the word is committed as it is typed.
 * - [lastContext] — our copy of the text before the caret. The engine counts
 *   its deletions against the text it produced, so the cache is kept in sync
 *   from our own edits rather than read back from the field per keystroke.
 *
 * ## Lifecycle
 * - [onCreateInputView] — inflates the custom View and attaches the listener.
 * - [onStartInputView] — resets the engine for the new field.
 * - [onFinishInputView] — drops the engine context; nothing is left uncommitted.
 */
class MiniKeyboardIME : InputMethodService(), OnKeyActionListener {

    // ── Engine state ─────────────────────────────────────────────────────
    /**
     * The engine keeps its word state in C++ globals, so there is exactly one
     * [Driver] per process (see VikeyDriver.kt). It is deliberately never
     * closed: creating a second instance after the first was destroyed
     * corrupts the shared state.
     */
    private val driver: Driver
        get() = sharedDriver ?: Driver().also { sharedDriver = it }

    /** Our copy of the text before the caret, kept in sync by [afterEdit] —
     *  asking the editor is an IPC. Null until read from the field. */
    private var lastContext: String? = null

    /** Caret position as we last left it; -1 while unknown. */
    private var cursorPos = -1

    /** Positions our own edits produced. An [onUpdateSelection] reporting one
     *  of them is the app catching up with us, not the user moving the caret. */
    private val recentPositions = ArrayList<Int>()

    /** Raw keys typed for the word under the caret. A double-tap replays them
     *  so the engine ends up holding the secondary instead of the primary.
     *  Cleared when the word ends, when the engine context resets, and on
     *  backspace (the engine then removes the last displayed letter, not the
     *  last key, so the keys we recorded no longer describe the word — see
     *  [wordKeysExact]). */
    private val wordKeys = StringBuilder()

    /**
     * Characters the current word occupies on screen. This is the engine's
     * rendering of [wordKeys], not the number of keys typed (a triple letter
     * collapses: `dooor` -> `door`), and it is how much a double-tap has to
     * replace. The trailing run of letters in [lastContext] is *not* a safe
     * proxy: after backspacing over a space the previous word is glued to the
     * caret ("will" + space + ⌫), so that run would swallow it whole.
     */
    private var wordLength = 0

    /** True while [wordKeys] still describes the whole word on screen. A
     *  backspace takes back the last *displayed* letter, so the recorded keys
     *  no longer do and a double-tap falls back to [replaceLastTap] until the
     *  word ends. */
    private var wordKeysExact = true

    /**
     * The inverse of the last edit we sent — delete what it inserted, put the
     * removed text back. A double-tap that cannot replay the word uses it to
     * take the first tap back exactly. Null when the edit cannot be reversed
     * (the text before the caret was never read).
     */
    private var undoLastEdit: Edit? = null

    /** Editor capabilities from the last [onStartInput]. */
    private var editorInfo: EditorInfo? = null

    // ── View ─────────────────────────────────────────────────────────────
    private lateinit var keyboardView: MiniKeyboardView

    // ── Clipboard history ────────────────────────────────────────────────
    /** In-memory copy history, newest first. Session-only by design. */
    private val clipboardHistory = ArrayList<String>()

    /** Texts the user dismissed — kept out even though the system clipboard
     *  still holds them, until a fresh copy of that text re-arms it. */
    private val dismissedClips = LinkedHashSet<String>()

    private lateinit var clipboardManager: ClipboardManager

    private val onPrimaryClipChanged = ClipboardManager.OnPrimaryClipChangedListener {
        // Runs on the main thread; the system filters callbacks by clipboard
        // access (IME visible) on Android 10+. A change event is a fresh copy
        // by the user — it re-arms a previously dismissed text.
        addToHistory(readClipboardText(), freshCopy = true)
        if (::keyboardView.isInitialized) {
            keyboardView.updateClipboardItems(clipboardHistory)
            updateSuggestionStrip()
        }
    }

    // ── Smart Telex ──────────────────────────────────────────────────────
    /** User toggle for English-aware validation. */
    private var smartTelexEnabled = true

    // ── Auto-capitalize ─────────────────────────────────────────────────
    /** User toggle — capitalize the first letter of a new sentence. */
    private var autoCapitalize = true

    /** True when the next alphabetic character starts a sentence and should
     *  be uppercased: armed on a fresh field, or by a space/newline after a
     *  sentence terminator, and consumed by the first letter typed. */
    private var capitalizeNext = false

    /** True while a sentence terminator (. ! ?) has just been committed and
     *  the following character has not yet broken it. Further terminators and
     *  spaces keep it ("What?!", "etc..."); a letter or punctuation glued to
     *  the terminator cancels it — "example.com" is not a sentence end. The
     *  first space promotes it to [capitalizeNext]. */
    private var sentenceEnded = false

    /** User toggle — show the clipboard paste suggestion strip above row 1
     *  (Gboard-style) when the field is empty. */
    private var suggestionStripEnabled = true

    // Applies a theme change the moment the settings screen writes it —
    // same process, so the SharedPreferences listener fires live.
    private val onPrefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_SMART_TELEX_ENABLED -> {
                smartTelexEnabled = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.KEY_SMART_TELEX_ENABLED, true)
                // Apply live: the engine reads these on every key.
                applyEngineSettings()
            }
            Prefs.KEY_AUTO_CAPITALIZE -> {
                autoCapitalize = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.KEY_AUTO_CAPITALIZE, true)
            }
            Prefs.KEY_THEME_MODE -> {
                if (::keyboardView.isInitialized) {
                    keyboardView.refreshTheme()
                }
                applyNavigationBarColor()
            }
            Prefs.KEY_HAPTIC_ENABLED, Prefs.KEY_DOUBLE_TAP_MS -> {
                if (::keyboardView.isInitialized) {
                    keyboardView.refreshTheme()
                }
            }
            Prefs.KEY_SUGGESTION_STRIP -> {
                suggestionStripEnabled = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.KEY_SUGGESTION_STRIP, true)
                // Apply live — a running keyboard hides/shows the strip now.
                updateSuggestionStrip()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // InputMethodService lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(onPrimaryClipChanged)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(onPrefsChanged)
        smartTelexEnabled = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_SMART_TELEX_ENABLED, true)
        autoCapitalize = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_AUTO_CAPITALIZE, true)
        suggestionStripEnabled = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(Prefs.KEY_SUGGESTION_STRIP, true)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(onPrimaryClipChanged)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(onPrefsChanged)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        keyboardView = MiniKeyboardView(this)
        keyboardView.onKeyActionListener = this
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info
        // Re-apply user theme/height preferences (may have changed in settings).
        keyboardView.refreshTheme()
        applyNavigationBarColor()
        // Every new input session starts on the letters layer — the previous
        // layer (numeric/clipboard) is not remembered.
        keyboardView.resetLayer()
        keyboardView.shiftActive = false
        // A new field: the next word starts fresh. The engine's language
        // follows the field start (Vietnamese), never the previous field.
        resetEngine()
        driver.language = Driver.VIETNAMESE
        applyEngineSettings()
        readContext()
        cursorPos = if (info.initialSelStart >= 0) info.initialSelStart else -1
        recentPositions.clear()
        // Re-read the current clip: while the keyboard was hidden the change
        // listener does not fire, so the suggestion strip catches up here.
        val current = readClipboardText()
        if (current != null && current !in dismissedClips) {
            addToHistory(current, freshCopy = false)
        }
        // A fresh field (or one whose text before the caret is only whitespace)
        // starts a sentence — capitalize its first letter.
        sentenceEnded = false
        capitalizeNext = shouldCapitalizeOnStart()
        syncShiftIndicator()
        updateSuggestionStrip()
    }

    // Never enter fullscreen IME mode in landscape — keep the compact
    // keyboard anchored to the bottom of the screen.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Every keystroke is already committed (the engine never composes), so
        // leaving the field has nothing to flush — only the word context to
        // drop, so the next field does not continue this word.
        resetEngine()
        lastContext = null
        cursorPos = -1
        recentPositions.clear()
        capitalizeNext = false
        sentenceEnded = false
    }

    /**
     * Fires when the target editor's selection or composing region changes —
     * including for the selection moving because of our own edits, which the
     * app reports back. The engine counts its deletions against the text it
     * produced, so a caret move it did not cause must reset its word.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        newComposingStart: Int, newComposingEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, newComposingStart, newComposingEnd)
        if (newSelStart == newSelEnd) {
            // Our own edit, reported back late: not a move.
            val reported = recentPositions.indexOf(newSelStart)
            if (reported >= 0) {
                recentPositions.subList(0, reported + 1).clear()
                updateSuggestionStrip()
                return
            }
            if (newSelStart == cursorPos) {
                updateSuggestionStrip()
                return
            }
        }
        // A real move (a tap elsewhere, or a selection): nothing to look back
        // on. Re-read the field — it may have been emptied or changed too.
        resetEngine()
        cursorPos = if (newSelStart == newSelEnd) newSelStart else -1
        recentPositions.clear()
        readContext()
        updateSuggestionStrip()
        // The user repositioned the caret, so the sentence-start assumption no
        // longer holds — don't force a capital.
        capitalizeNext = false
        sentenceEnded = false
        syncShiftIndicator()
    }

    /**
     * Paint the system navigation bar (the strip below the keyboard holding the
     * gesture pill) with the keyboard's own background color. `getWindow()`
     * returns the SoftInputWindow (a Dialog); the second call reaches the
     * underlying PhoneWindow that exposes the nav-bar APIs. On API 29+ the
     * default contrast scrim is disabled so the color shows exactly.
     */
    private fun applyNavigationBarColor() {
        val win = window?.window ?: return
        val bg = MiniKeyboardView.backgroundColor(
            MiniKeyboardView.resolvePaletteFor(this))
        win.setNavigationBarColor(bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.navigationBarDividerColor = bg
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            win.isNavigationBarContrastEnforced = false
        }
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        editorInfo = info
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-apply the dark/light palette when the system theme changes.
        if (::keyboardView.isInitialized) {
            keyboardView.refreshTheme()
        }
        applyNavigationBarColor()
    }

    // ─────────────────────────────────────────────────────────────────────
    // OnKeyActionListener implementation
    // ─────────────────────────────────────────────────────────────────────

    override fun onCharacter(char: Char) {
        val ic = currentInputConnection ?: return

        var c = char
        if (autoCapitalize && capitalizeNext && c.isLetter() && !wordActive) {
            // First letter of a new sentence. Uppercasing is idempotent, so a
            // char already uppercased by the shift latch or caps lock passes
            // through unchanged; the engine propagates the case into its
            // transforms ("Aa" resolves to "Â"), so Vietnamese works too.
            capitalizeNext = false
            c = c.uppercaseChar()
        }
        // A letter glued to what preceded it means the previous char was not a
        // sentence end ("example.com") — cancel it. Punctuation updates the
        // state instead (a terminator arms it, a space promotes it).
        if (c.isLetter()) sentenceEnded = false else markSentenceStart(c)

        type(ic, c)
        syncShiftIndicator()
        updateSuggestionStrip()
    }

    override fun onReplaceCharacter(char: Char) {
        // Double-tap: replace what the first tap inserted. Replaying the word
        // is the only correct way — taking the last character back would delete
        // a letter the first tap only toned ("ba" + s -> "bá", not "bá" - 1).
        val ic = currentInputConnection ?: return
        if (!wordKeysExact || wordKeys.isEmpty() || wordLength == 0) {
            // Nothing replayable: take the first tap's edit back and commit the
            // secondary as plain text (see undoLastEdit).
            replaceLastTap(ic, char)
        } else {
            wordKeys.setLength(wordKeys.length - 1)
            wordKeys.append(char)
            replayWord(ic, wordLength)
        }
        syncShiftIndicator()
        updateSuggestionStrip()
    }

    override fun onDirectCharacter(char: Char) {
        // Numeric / rare-symbol layer: not Telex input. End the current word
        // first — the engine takes a digit glued to a word as part of it and
        // then puts the whole thing back raw ("tooi5" stays "tooi5") instead
        // of showing the resolved word.
        val ic = currentInputConnection ?: return
        resetEngine()
        insertDirect(ic, char.toString())
        markSentenceStart(char)
        syncShiftIndicator()
        updateSuggestionStrip()
    }

    override fun onReplaceDirectCharacter(char: Char) {
        // Numeric-layer double-tap: the first tap already committed the digit
        // directly, so delete it in the editor and insert the symbol.
        val ic = currentInputConnection ?: return
        resetEngine()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(char.toString(), 1)
        ic.endBatchEdit()
        afterEdit(1, char.toString())
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return

        // An active selection must be deleted whole: deleteSurroundingText(1, 0)
        // only removes characters before the selection start, which just
        // collapses the selection to its end. commitText replaces the
        // selection with the given text, so an empty string deletes it.
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 0)
            resetEngine()
            readContext()
        } else {
            val e = driver.backspace(now)
            applyEdit(ic, e)
            // The engine drops the last displayed letter, not the last key, so
            // the keys recorded for the word no longer describe it.
            accountWordEdit(e)
            wordKeys.setLength(0)
            wordKeysExact = false
        }
        // Deleting text breaks the sentence context — don't force the next
        // letter into a capital.
        capitalizeNext = false
        sentenceEnded = false
        syncShiftIndicator()
        // Deleting may have emptied the field — re-evaluate the strip.
        updateSuggestionStrip()
    }

    override fun onShift() {
        // Shift state is managed by the View; we just update for visual feedback.
        // The View applies case to the next character before calling onCharacter.
    }

    override fun onNumeric() {
        // Layer switching is handled by MiniKeyboardView; nothing to do here.
    }

    override fun onOpenSettings() {
        // Long-press on 123: bring the settings screen up over the field. The
        // IME window hides with the app that owned it, and the settings screen
        // is the launcher entry point, so a NEW_TASK launch is what the
        // activity expects from a non-activity context.
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return

        // The engine resolves and commits the pending word itself, then emits
        // the space.
        type(ic, ' ')
        // A space right after a sentence terminator starts the next sentence:
        // arm the capital and light the Shift key. A space in the middle of a
        // sentence leaves the state untouched.
        if (sentenceEnded) {
            sentenceEnded = false
            capitalizeNext = autoCapitalize
        }
        updateSuggestionStrip()
        syncShiftIndicator()
    }

    override fun onReturn() {
        val ic = currentInputConnection ?: return
        // A newline begins a new sentence. Even when the editor dispatches an
        // action (send/search), the next typed letter starts fresh input.
        capitalizeNext = autoCapitalize
        sentenceEnded = false
        syncShiftIndicator()

        // Dispatch the Enter key action as configured by the target editor
        val actionId = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            // The editor acts on the field (send, search) and may clear it —
            // nothing to look back on.
            resetEngine()
            cursorPos = -1
            recentPositions.clear()
            ic.performEditorAction(actionId)
        } else {
            type(ic, '\n')
        }
        updateSuggestionStrip()
    }

    override fun onCursorMove(delta: Int) {
        val ic = currentInputConnection ?: return

        // Probe the cursor position: setSelection takes absolute offsets, so
        // measure how much text lies on each side of the caret.
        val before = ic.getTextBeforeCursor(CURSOR_PROBE_LEN, 0) ?: return
        val after = ic.getTextAfterCursor(CURSOR_PROBE_LEN, 0)
        val cursor = before.length
        val end = cursor + (after?.length ?: 0)
        val target = (cursor + delta).coerceIn(0, end)
        ic.setSelection(target, target)
        // The caret left the word: the engine must not look back at it.
        resetEngine()
        cursorPos = target
        recentPositions.clear()
        readContext()
        capitalizeNext = false
        sentenceEnded = false
        syncShiftIndicator()
        updateSuggestionStrip()
    }

    override fun onClipboard() {
        // Re-read the current clip first: while the keyboard was hidden the
        // change listener does not fire, so catch up here. This is a re-read
        // of an old clip, not a fresh copy — respect dismissals.
        val current = readClipboardText()
        if (current != null && current !in dismissedClips) {
            addToHistory(current, freshCopy = false)
        }
        keyboardView.showClipboardLayer(clipboardHistory)
    }

    override fun onClipboardItem(index: Int) {
        val text = clipboardHistory.getOrNull(index) ?: return
        // Move to top BEFORE any setPrimaryClip below, so the change listener
        // this triggers sees the text already first and dedupes it.
        addToHistory(text, freshCopy = true)

        val ic = currentInputConnection
        if (ic != null) {
            // Paste into the focused field. A live engine word ends here (the
            // pasted text takes over the caret), then the text follows it.
            resetEngine()
            insertDirect(ic, text)
        } else {
            // No focused text field — re-copy the item so it is ready for the
            // next paste.
            clipboardManager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }
        // Stay on the clipboard layer; the pasted item moved to the top.
        keyboardView.updateClipboardItems(clipboardHistory)
        // The pasted text precedes the caret — field no longer empty.
        updateSuggestionStrip()
    }

    override fun onClipboardDismiss(index: Int) {
        if (index !in clipboardHistory.indices) return
        val text = clipboardHistory.removeAt(index)
        // The system clipboard still holds this text — keep it out of the
        // history until the user copies it again.
        dismissedClips.add(text)
        while (dismissedClips.size > MAX_CLIP_ITEMS) {
            dismissedClips.remove(dismissedClips.first())
        }
        keyboardView.updateClipboardItems(clipboardHistory)
        updateSuggestionStrip()
    }

    override fun onSuggestionItem(index: Int) {
        val text = clipboardHistory.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        // Insert directly, no Telex involved (the paste is committed text) —
        // also when the field already holds text, since the strip now shows in
        // both cases.
        resetEngine()
        insertDirect(ic, text)
        // The pasted text now precedes the caret.
        updateSuggestionStrip()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hard-key fallback
    // ─────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {

        if (keyCode == KeyEvent.KEYCODE_DEL) {
            onBackspace()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            onReturn()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            onSpace()
            return true
        }

        // Pass through printable characters to the Telex pipeline
        val unicode = event.unicodeChar
        if (unicode != 0 && !Character.isISOControl(unicode)) {
            onCharacter(unicode.toChar())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Clipboard history helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * First text item of the primary clip, or null for blank / non-text clips
     * (images would otherwise surface as useless `content://` URIs). OEM
     * clipboard guards (e.g. MIUI) can throw SecurityException — degrade to null.
     */
    private fun readClipboardText(): String? {
        return try {
            val clip = clipboardManager.primaryClip ?: return null
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i).text?.toString()
                if (!text.isNullOrBlank()) return text
            }
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Insert [text] at the head of the history; dedupe identical newest entry.
     * [freshCopy] means the text just arrived as a clipboard change event
     * (user copied it) — that re-arms a previously dismissed text; a plain
     * re-read keeps dismissals in force.
     */
    private fun addToHistory(text: String?, freshCopy: Boolean) {
        if (text.isNullOrBlank()) return
        val capped = if (text.length > MAX_CLIP_CHARS) text.take(MAX_CLIP_CHARS) else text
        if (freshCopy) {
            dismissedClips.remove(capped)
        } else if (capped in dismissedClips) {
            return
        }
        if (capped == clipboardHistory.firstOrNull()) return
        clipboardHistory.remove(capped)
        clipboardHistory.add(0, capped)
        while (clipboardHistory.size > MAX_CLIP_ITEMS) {
            clipboardHistory.removeAt(clipboardHistory.size - 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /** Monotonic seconds for the engine's timing rules (double space, auto switch pause). */
    private val now: Double get() = SystemClock.uptimeMillis() / 1000.0

    /** True while the caret sits inside a word (the char before it is a
     *  letter) — the next letter continues that word, so it must not be
     *  auto-capitalized however the sentence state looks. */
    private val wordActive: Boolean get() = lastContext?.lastOrNull()?.isLetter() == true

    /**
     * Passwords are typed exactly as pressed: no Telex, no auto switch. The
     * editor's variation flags say so; everything else goes through the engine.
     */
    private val isPasswordField: Boolean
        get() {
            val inputType = editorInfo?.inputType ?: return false
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> {
                    val variation = inputType and InputType.TYPE_MASK_VARIATION
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                }
                InputType.TYPE_CLASS_NUMBER ->
                    inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

    /** One character typed through the engine. */
    private fun type(ic: InputConnection, char: Char) {
        if (isPasswordField) {
            clearWord()
            insertDirect(ic, char.toString())
            return
        }
        val e = driver.typeCharacter(char.toString(), now)
        applyEdit(ic, e)
        // A letter continues the word; anything else ends it.
        if (char.isLetter()) {
            wordKeys.append(char)
            accountWordEdit(e)
        } else {
            clearWord()
        }
    }

    /**
     * Re-run the engine over [wordKeys] and swap the word displayed across
     * [displayed] characters for the result — how a double-tap replaces a key.
     * The engine's own state has already moved past the primary character, so
     * the word is resolved from scratch; that also drops the engine's
     * previous-word look-back, which only affects auto switch heuristics.
     */
    private fun replayWord(ic: InputConnection, displayed: Int) {
        // Snapshot first: resetEngine() also empties wordKeys, which would
        // otherwise leave the replay with nothing to type and delete the word.
        val keys = wordKeys.toString()
        resetEngine()
        var shadow = ""
        for (c in keys) {
            val e = driver.typeCharacter(c.toString(), now)
            shadow = shadow.dropLast(minOf(e.deleteCount, shadow.length)) + e.text
        }
        applyEdit(ic, Edit(displayed, shadow, false))
        // The engine now holds the replayed word, so resume recording its keys
        // — the next keystroke continues it and the next double-tap replays it.
        wordKeys.append(keys)
        wordKeysExact = true
        wordLength = shadow.length
    }

    /**
     * Keep [wordLength] in step with an engine edit that belongs to the current
     * word: the edit replaces the last [Edit.deleteCount] characters of the
     * word with [Edit.text]. An edit that reaches further back than the word
     * itself means the engine rewrote earlier text (auto-switch look-back), so
     * the recorded keys no longer describe the screen.
     */
    private fun accountWordEdit(e: Edit) {
        if (e.deleteCount > wordLength) {
            wordKeys.setLength(0)
            wordKeysExact = false
            wordLength = 0
        } else {
            wordLength += e.text.length - e.deleteCount
        }
    }

    /** The current word is over: nothing left to replay or measure. */
    private fun clearWord() {
        wordKeys.setLength(0)
        wordKeysExact = true
        wordLength = 0
    }

    /**
     * Apply an [Edit] to the editor, sending only what actually changes:
     * "tooi" + s comes back as delete "oi" + insert "ói", so the leading "o"
     * is kept instead of deleted and retyped.
     *
     * Also remembers the exact inverse in [undoLastEdit]: the edit turns the
     * tail from the first [Edit.deleteCount] chars of [lastContext] into
     * [Edit.text], so reversing it means deleting that text and putting the
     * old tail back.
     */
    private fun applyEdit(ic: InputConnection, e: Edit) {
        var deletes = e.deleteCount
        var insert = e.text
        val before = lastContext
        undoLastEdit = if (deletes == 0) {
            Edit(insert.length, "", false)
        } else if (before != null && before.length >= deletes) {
            Edit(insert.length, before.takeLast(deletes), false)
        } else {
            null
        }
        if (deletes > 0 && before != null && before.length >= deletes) {
            val old = before.takeLast(deletes)
            var same = 0
            while (same < old.length && same < insert.length && old[same] == insert[same]) same++
            deletes -= same
            insert = insert.substring(same)
        }
        ic.beginBatchEdit()
        // A raw-key editor (a terminal emulator: TYPE_NULL, no text buffer)
        // handles deleteSurroundingText by firing KEYCODE_DEL key events,
        // which the app dispatches on its UI thread — so they land after the
        // text we commit from this one. "tiếng" then deletes its own tail and
        // the tone mark vanishes at random. Its commitText path writes to the
        // terminal synchronously, so send the deletions *inside* that same
        // commit as DEL characters: one ordered write, nothing to race.
        val foldDeletes = deletes > 0 && isRawKeyEditor
        if (deletes > 0 && !foldDeletes) ic.deleteSurroundingText(deletes, 0)
        val commit = if (foldDeletes) DEL_CHAR.repeat(deletes) + insert else insert
        if (commit.isNotEmpty()) ic.commitText(commit, 1)
        ic.endBatchEdit()
        afterEdit(deletes, insert)
    }

    /** True for an editor that takes raw key input instead of holding a text
     *  buffer of its own — a terminal emulator. It declares no input class
     *  ([InputType.TYPE_NULL]) and reports no caret position, because the
     *  cursor lives in the pty, not in the view. */
    private val isRawKeyEditor: Boolean
        get() {
            val info = editorInfo ?: return false
            return info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL &&
                info.initialSelStart < 0
        }

    /** Insert text that did not come from the engine — a paste, or a keystroke
     *  in a password field. */
    private fun insertDirect(ic: InputConnection, text: String) {
        ic.commitText(text, 1)
        afterEdit(0, text)
        // Plain insertion at the caret: taking it back means deleting it.
        undoLastEdit = Edit(text.length, "", false)
    }

    /**
     * Double-tap fallback when the word cannot be replayed (a backspace took a
     * letter back, or no Telex word is open): reverse the first tap's edit and
     * commit the secondary as plain text.
     *
     * Asking the engine for a backspace instead is wrong twice over — it
     * removes the last *displayed* letter (so a tap that only added a tone
     * costs a letter: "bán" -> "bá"), and right after a word break it resumes
     * the previous word, so the reply can reach back past the tap and eat the
     * word before it. The engine's word is dropped rather than left describing
     * text that is no longer on screen.
     */
    private fun replaceLastTap(ic: InputConnection, char: Char) {
        val undo = undoLastEdit
        if (undo == null) {
            // Nothing to reverse (the field was never read): the old fallback.
            applyEdit(ic, driver.backspace(now))
            type(ic, char)
            return
        }
        applyEdit(ic, undo)
        resetEngine()
        insertDirect(ic, char.toString())
    }

    /** Keep [lastContext] and [cursorPos] in step with an edit we just made. */
    private fun afterEdit(deleted: Int, inserted: String) {
        val c = lastContext ?: ""
        lastContext = (c.dropLast(minOf(deleted, c.length)) + inserted).takeLast(CONTEXT_CAP)
        if (cursorPos >= 0) {
            cursorPos = maxOf(0, cursorPos - deleted) + inserted.length
            // The app reports our own edits back through onUpdateSelection,
            // often several callbacks later — remember where we put the caret
            // so those reports are not mistaken for the user moving it.
            recentPositions.add(cursorPos)
            if (recentPositions.size > RECENT_POSITIONS) recentPositions.removeAt(0)
        }
    }

    /** Refresh [lastContext] from the field (field start, paste, a caret move). */
    private fun readContext() {
        lastContext = currentInputConnection?.getTextBeforeCursor(CONTEXT_CAP, 0)?.toString()
    }

    /** Drop the engine's word state — new field, caret move, paste, enter. */
    private fun resetEngine() {
        driver.resetContext()
        clearWord()
        undoLastEdit = null
    }

    /**
     * Push the app's settings into the engine. Smart Telex off is plain Telex:
     * every transform applies, no syllable-shape check and no English auto
     * switch, which is how the old engine behaved with the toggle off.
     */
    private fun applyEngineSettings() {
        driver.autoSwitch = smartTelexEnabled
        driver.spelling = smartTelexEnabled
        driver.restoreIfInvalid = smartTelexEnabled
        // "Modern" orthography would give hoà/thuý; this app has always
        // produced hòa/thúy.
        driver.modernOrthography = false
        // Teencode (z = d) is hard-coded on upstream with no way to turn it off.
        driver.teencode = false
        driver.applySettings()
    }

    /**
     * Show/hide the clipboard suggestion strip: visible whenever the toggle is
     * on and the clipboard holds text, on every input state — the strip also
     * carries the clipboard-layer button, so it must stay reachable while
     * typing, not only over an empty field.
     */
    private fun updateSuggestionStrip() {
        if (!::keyboardView.isInitialized) return
        val show = suggestionStripEnabled && clipboardHistory.isNotEmpty()
        keyboardView.updateSuggestions(clipboardHistory, show)
    }

    /**
     * Advance the sentence-start state when [char] is committed directly.
     * A terminator (. ! ?) arms [sentenceEnded] but does NOT cap yet — only a
     * following space (or the newline itself) starts the next sentence, so a
     * letter glued to the period ("example.com/x") stays lowercase. A
     * non-terminator punctuation char glued to the terminator cancels it
     * (comma/colon continue the sentence).
     */
    private fun markSentenceStart(char: Char) {
        when {
            char == '.' || char == '!' || char == '?' ->
                sentenceEnded = autoCapitalize
            char == '\n' -> {
                // The newline is its own separator — arm immediately.
                capitalizeNext = autoCapitalize
                sentenceEnded = false
            }
            char == ' ' -> {
                // A space right after a terminator starts the next sentence.
                if (sentenceEnded) {
                    sentenceEnded = false
                    capitalizeNext = autoCapitalize
                }
            }
            else -> sentenceEnded = false
        }
    }

    /**
     * Mirror the auto-cap state onto the keyboard's [autoShiftActive]
     * indicator so the keys show uppercase while the next letter will be
     * capitalized. Display only — the actual uppercase comes from the
     * [capitalizeNext] branch in [onCharacter], and this flag never touches
     * the View's manual shift latch. A pending word makes the next letter
     * lowercase, so the indicator is cleared whenever the buffer holds text.
     */
    private fun syncShiftIndicator() {
        if (::keyboardView.isInitialized) {
            keyboardView.autoShiftActive = autoCapitalize && capitalizeNext && !wordActive
        }
    }

    /**
     * Whether input starting now begins a sentence — used on [onStartInputView].
     * True when the field holds only whitespace before the caret (a fresh or
     * cleared field); once real text precedes the caret the runtime flag
     * (markSentenceStart) takes over, so mid-paragraph edits are not forced.
     */
    private fun shouldCapitalizeOnStart(): Boolean {
        if (!autoCapitalize) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(64, 0)
        // Unreachable InputConnection at start most often means an empty field —
        // cap, matching the common case.
        if (before == null) return true
        return before.isBlank()
    }

    companion object {
        /** Max chars probed around the caret to locate the cursor position. */
        private const val CURSOR_PROBE_LEN = 5000

        /** Clipboard history size cap (the list scrolls, so it can be long). */
        private const val MAX_CLIP_ITEMS = 30

        /** Per-item length cap — stays well under the ~1MB Binder transaction
         *  limit for commitText/setPrimaryClip. */
        private const val MAX_CLIP_CHARS = 50_000

        /** Label used for ClipData created when re-copying an item. */
        private const val CLIP_LABEL = "clipboard"

        /** How much text before the caret we mirror; only the tail matters. */
        private const val CONTEXT_CAP = 2000

        /** How many of our own caret positions to remember for onUpdateSelection. */
        private const val RECENT_POSITIONS = 12

        /** ASCII DEL, what a terminal erases with. Committed in place of a
         *  deleteSurroundingText call when the editor is a raw-key terminal. */
        private const val DEL_CHAR = "\u007F"

        /**
         * The one engine instance for the process. Vikey keeps the current
         * word, the previous word and its auto-switch undo data in C++ globals,
         * so a second [Driver] in the same process corrupts the first. An
         * IME service recreated in a live process must reuse this instance
         * rather than build a new one.
         */
        private var sharedDriver: Driver? = null
    }
}
