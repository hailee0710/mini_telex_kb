package com.miniqwerty.kb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * Custom Android IME implementing a compact 3-row keyboard layout with an
 * integrated Vietnamese Telex processing engine.
 *
 * ## Architecture
 * - [MiniKeyboardView] — Custom View canvas handling rendering & touch.
 * - [TelexProcessor]  — Stateless engine that transforms raw keystrokes
 *   into composed Vietnamese text.
 * - Composing buffer — Accumulates raw characters in the current word.
 *   On space/return, the resolved text is committed and the buffer is cleared.
 *   On backspace, the last raw character is removed and the display is updated.
 *
 * ## Lifecycle
 * - [onCreateInputView] — inflates the custom View and attaches the listener.
 * - [onStartInputView] — resets composing state for a new input session.
 * - [onFinishInputView] — ensures any pending composing text is committed.
 */
class MiniKeyboardIME : InputMethodService(), OnKeyActionListener {

    // ── Composing state ──────────────────────────────────────────────────
    /** Raw character buffer for the current Telex word. */
    private val rawBuffer = StringBuilder()

    /** True when the last composing display showed the raw buffer verbatim
     *  (an English word that fell back to literal). Backspace then keeps the
     *  literal prefix instead of re-applying the tone the popped character
     *  had visually undone — "lantern" deletes back through "lanter", never
     *  jumping to "lảnte". */
    private var composingShowsRaw = false

    /** The most recent character committed directly to the editor — a
     *  punctuation/whitespace char (commits immediately via [onCharacter]),
     *  or a numeric-layer char. Double-tap replace targets this when the raw
     *  buffer is empty: the secondary replaces it in the editor, the same way
     *  the numeric layer replaces a directly-committed digit. Null while a
     *  Telex word owns the tail of the text. */
    private var lastDirectChar: Char? = null

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
        }
    }

    // ── Smart Telex ──────────────────────────────────────────────────────
    /** User toggle for English-aware validation. */
    private var smartTelexEnabled = true

    // ── Auto-capitalize ─────────────────────────────────────────────────
    /** User toggle — capitalize the first letter of a new sentence. */
    private var autoCapitalize = true

    /** True when the next alphabetic character starts a sentence and should
     *  be uppercased: set after a terminator (. ! ? newline), on a fresh
     *  field, and consumed by the first letter typed. */
    private var capitalizeNext = false

    /** Known Vietnamese words, loaded once from assets; null while loading
     *  or on load failure (commit-time dictionary check is then skipped). */
    private var wordDict: Set<String>? = null

    private var wordDictLoaded = false

    // Applies a theme change the moment the settings screen writes it —
    // same process, so the SharedPreferences listener fires live.
    private val onPrefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_SMART_TELEX_ENABLED -> {
                smartTelexEnabled = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.KEY_SMART_TELEX_ENABLED, true)
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
        loadWordDict()
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
        // Reset composing state when switching input targets
        commitPending()
        rawBuffer.clear()
        keyboardView.shiftActive = false
        // A fresh field (or one whose text before the caret is only whitespace)
        // starts a sentence — capitalize its first letter.
        capitalizeNext = shouldCapitalizeOnStart()
        updateComposingText()
    }

    // Never enter fullscreen IME mode in landscape — keep the compact
    // keyboard anchored to the bottom of the screen.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // The user has left the text field — commit the pending word so typed
        // text is never lost. Explicit lifecycle end, so commit unconditionally
        // (see commitBuffer()); do not gate on the composing-region probe,
        // which some editors report as cleared once focus moves.
        val ic = currentInputConnection
        if (ic != null) {
            commitBuffer(ic)
        }
        rawBuffer.clear()
        lastDirectChar = null
        capitalizeNext = false
    }

    /**
     * Fires when the target editor's selection or composing region changes.
     * A user tap that moves the caret elsewhere while a word is pending must
     * not leave the composing text detached from the caret — finalize the word
     * where it stands. Our own composing updates always leave the caret at the
     * composing end, so they never take this branch.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        newComposingStart: Int, newComposingEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, newComposingStart, newComposingEnd)
        val ic = currentInputConnection ?: return
        if (rawBuffer.isEmpty()) return

        val caretAtComposingEnd = newComposingStart >= 0 &&
            newSelStart == newComposingEnd && newSelEnd == newComposingEnd
        if (!caretAtComposingEnd) {
            // The caret moved away from the composing word (user tapped). The
            // word must stay where it is, so finalize it in place instead of
            // commitText at the caret, which would duplicate it elsewhere.
            // finishComposingText (unlike commitComposingText, which moves the
            // caret to the composing start) keeps the tapped caret untouched.
            if (newComposingStart >= 0) {
                @Suppress("DEPRECATION")
                ic.finishComposingText()
            }
            rawBuffer.clear()
            lastDirectChar = null
            // The caret left the composing word — the user repositioned it, so
            // the sentence-start assumption no longer holds. Don't force a cap.
            capitalizeNext = false
        }
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

        if (TelexProcessor.shouldCommit(char)) {
            // Commit current word and reset. Explicit user action — commit
            // unconditionally (see commitBuffer()).
            commitBuffer(ic)
            ic.commitText(char.toString(), 1)
            lastDirectChar = char
            markSentenceStart(char)
            updateComposingText()
            return
        }

        // Append to raw buffer and resolve — the word now owns the tail of
        // the text, so no directly-committed char is replaceable.
        lastDirectChar = null
        if (autoCapitalize && capitalizeNext && rawBuffer.isEmpty() && char.isLetter()) {
            // First letter of a new sentence. Uppercasing is idempotent, so a
            // char already uppercased by the shift latch or caps lock passes
            // through unchanged; Telex propagates the case into its transforms
            // ("Aa" resolves to "Â"), so Vietnamese works too.
            capitalizeNext = false
            rawBuffer.append(char.uppercaseChar())
        } else {
            rawBuffer.append(char)
        }
        // Same-tone-twice toggle ("charr" → "char") is a deliberate "this word
        // is English, done" gesture: commit it immediately so a following
        // letter starts a fresh word instead of re-interpreting the doubled
        // tone key as a doubled consonant ("charr" then "z" → "charrz").
        if (smartTelexEnabled && TelexProcessor.hasToneToggle(rawBuffer.toString())) {
            commitBuffer(ic)
        } else {
            updateComposingText()
        }
    }

    override fun onReplaceCharacter(char: Char) {
        // Double-tap: swap the last raw character for the key's secondary
        // before re-resolving the buffer.
        if (rawBuffer.isNotEmpty()) {
            rawBuffer.deleteCharAt(rawBuffer.lastIndex)
            onCharacter(char)
            return
        }
        // No pending word: the first tap's primary was a punctuation char
        // that committed directly ("x," — comma is in the shouldCommit set),
        // so it lives in the editor, not the buffer. Delete it there and
        // insert the secondary ("x."), mirroring the numeric layer.
        val ic = currentInputConnection ?: return
        if (lastDirectChar != null) {
            lastDirectChar = null
            ic.deleteSurroundingText(1, 0)
            ic.commitText(char.toString(), 1)
            markSentenceStart(char)
        } else {
            onCharacter(char)
        }
    }

    override fun onDirectCharacter(char: Char) {
        // Numeric layer: commit the pending word, then insert the character
        // without passing it through the Telex buffer. Explicit user action —
        // commit unconditionally (see commitBuffer()).
        val ic = currentInputConnection ?: return
        commitBuffer(ic)
        ic.commitText(char.toString(), 1)
        lastDirectChar = char
        markSentenceStart(char)
    }

    override fun onReplaceDirectCharacter(char: Char) {
        // Numeric-layer double-tap: the first tap already committed the digit
        // directly, so delete it in the editor and insert the symbol.
        val ic = currentInputConnection ?: return
        commitBuffer(ic)
        lastDirectChar = null
        ic.deleteSurroundingText(1, 0)
        ic.commitText(char.toString(), 1)
        lastDirectChar = char
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return

        if (rawBuffer.isNotEmpty()) {
            // Remove last character from raw buffer
            rawBuffer.deleteCharAt(rawBuffer.lastIndex)
            if (composingShowsRaw) {
                // The buffer was displayed literally (English fallback) —
                // keep the raw prefix literal so the tone the popped char
                // visually undid does not snap back.
                if (rawBuffer.isEmpty()) {
                    ic.setComposingText("", 0)
                    composingShowsRaw = false
                } else {
                    ic.setComposingText(rawBuffer.toString(), 1)
                }
            } else {
                updateComposingText()
            }
        } else {
            // No composing text — delegate to the target app. An active text
            // selection must be deleted whole: deleteSurroundingText(1, 0)
            // only removes characters before the selection start, which just
            // collapses the selection to its end. commitText replaces the
            // selection with the given text, so an empty string deletes it.
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 0)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
            // Deleting text that was already committed breaks the sentence
            // context — don't force the next letter into a capital.
            capitalizeNext = false
        }
    }

    override fun onShift() {
        // Shift state is managed by the View; we just update for visual feedback.
        // The View applies case to the next character before calling onCharacter.
    }

    override fun onNumeric() {
        // Layer switching is handled by MiniKeyboardView; nothing to do here.
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return

        if (rawBuffer.isNotEmpty()) {
            // Resolve and commit the word, then append the space
            val resolved = TelexProcessor.resolve(
                rawBuffer.toString(), smart = smartTelexEnabled, dict = wordDict)
            ic.commitText(resolved + " ", 1)
            rawBuffer.clear()
        } else {
            ic.commitText(" ", 1)
        }
        // The space is a direct commit, but it has no secondary key — a
        // later double-tap must never delete it. Drop the replace target.
        lastDirectChar = null
        updateComposingText()
    }

    override fun onReturn() {
        val ic = currentInputConnection ?: return
        commitBuffer(ic)
        // A newline begins a new sentence. Even when the editor dispatches an
        // action (send/search), the next typed letter starts fresh input.
        capitalizeNext = autoCapitalize

        // Dispatch the Enter key action as configured by the target editor
        val actionId = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            ic.commitText("\n", 1)
        }
        updateComposingText()
    }

    override fun onCursorMove(delta: Int) {
        val ic = currentInputConnection ?: return

        // A pending Telex word would break the buffer's assumption about the
        // surrounding text once the cursor moves — commit it first. Explicit
        // user action, so commit unconditionally (see commitBuffer()).
        if (rawBuffer.isNotEmpty()) {
            commitBuffer(ic)
        }

        // Probe the cursor position: setSelection takes absolute offsets, so
        // measure how much text lies on each side of the caret.
        val before = ic.getTextBeforeCursor(CURSOR_PROBE_LEN, 0) ?: return
        val after = ic.getTextAfterCursor(CURSOR_PROBE_LEN, 0)
        val cursor = before.length
        val end = cursor + (after?.length ?: 0)
        val target = (cursor + delta).coerceIn(0, end)
        ic.setSelection(target, target)
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
            // Paste into the focused field. A pending Telex word is committed
            // first (explicit user action — see commitBuffer()), then the
            // pasted text follows it.
            commitBuffer(ic)
            ic.commitText(text, 1)
            updateComposingText()
        } else {
            // No focused text field — re-copy the item so it is ready for the
            // next paste.
            clipboardManager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }
        // Stay on the clipboard layer; the pasted item moved to the top.
        keyboardView.updateClipboardItems(clipboardHistory)
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

    /**
     * Loads the Vietnamese word list from assets on a background thread.
     * Failure (missing asset, IO error) leaves [wordDict] null — smart mode
     * then falls back to shape validation only.
     */
    private fun loadWordDict() {
        if (wordDictLoaded) return
        wordDictLoaded = true
        Thread {
            try {
                val words = HashSet<String>(50_000)
                assets.open("vi_words.txt").bufferedReader().useLines { lines ->
                    lines.forEach { words.add(it) }
                }
                wordDict = words
            } catch (e: Exception) {
                wordDict = null
            }
        }.start()
    }

    /**
     * Resolves the raw buffer through [TelexProcessor] and updates the
     * composing region in the target editor via [InputConnection.setComposingText].
     */
    private fun updateComposingText() {
        val ic = currentInputConnection ?: return
        if (rawBuffer.isEmpty()) {
            // Clear any stale composing span
            ic.setComposingText("", 0)
            composingShowsRaw = false
            return
        }
        // Live composing display: shape validation only, no dictionary —
        // the commit-time check corrects the final word (Gboard-style).
        // Exception: a mid-word tone key ("masy", "mast") can make an English
        // word pass the shape check (base → báe as ba+e), so those words get
        // the dictionary fallback live too. Purely trailing tones ("giengs" →
        // giễng) keep the pretty composing form until commit.
        val buffer = rawBuffer.toString()
        val dict = if (TelexProcessor.hasEmbeddedTone(buffer)) wordDict else null
        val resolved = TelexProcessor.resolve(buffer, smart = smartTelexEnabled, dict = dict)
        composingShowsRaw = resolved == buffer
        ic.setComposingText(resolved, 1)
    }

    /**
     * Commits the resolved raw buffer unconditionally and clears state.
     *
     * Used for explicit user actions (enter, cursor move). Unlike
     * [commitPending], it does not gate on the editor's composing-region
     * probe: editors that fail to report partial offsets would otherwise
     * have the visible composing word silently wiped.
     */
    private fun commitBuffer(ic: InputConnection) {
        val resolved = TelexProcessor.resolve(
            rawBuffer.toString(), smart = smartTelexEnabled, dict = wordDict)
        // Clear before touching the editor so an onUpdateSelection delivered
        // synchronously by commitText cannot re-enter this buffer.
        rawBuffer.clear()
        // The committed word now owns the tail of the text — no direct char
        // from before it is replaceable by a double-tap.
        lastDirectChar = null
        if (resolved.isNotEmpty()) {
            ic.commitText(resolved, 1)
        }
        ic.setComposingText("", 0)
    }

    /**
     * Commits any pending composing text to the target editor and resets state.
     *
     * The commit is conditional on the editor still holding a composing region:
     * if it is gone (e.g. a chat app cleared the field while we were composing),
     * the buffer is stale and re-inserting it would resurrect deleted text.
     */
    private fun commitPending() {
        val ic = currentInputConnection ?: return
        val resolved = TelexProcessor.resolve(
            rawBuffer.toString(), smart = smartTelexEnabled, dict = wordDict)
        if (resolved.isNotEmpty() && editorHasComposingRegion()) {
            ic.commitText(resolved, 1)
        }
        lastDirectChar = null
        ic.setComposingText("", 0)
    }

    /**
     * True if the editor reports an active composing region
     * (partialStartOffset < partialEndOffset). Editors without composing
     * support return null extracted text — treat as "commit" (legacy behavior,
     * their text was already inserted by setComposingText falling back to
     * commitText).
     */
    private fun editorHasComposingRegion(): Boolean {
        val ic = currentInputConnection ?: return true
        val req = ExtractedTextRequest().apply { hintMaxChars = 0; hintMaxLines = 1 }
        val et = ic.getExtractedText(req, 0) ?: return true
        return et.partialStartOffset >= 0 && et.partialEndOffset > et.partialStartOffset
    }

    /**
     * Raise [capitalizeNext] when [char] ends a sentence. Sentence terminators
     * are . ! ? and the newline; comma, colon, ellipsis, spaces and tabs merely
     * continue the current sentence.
     */
    private fun markSentenceStart(char: Char) {
        if (char == '.' || char == '!' || char == '?' || char == '\n') {
            capitalizeNext = autoCapitalize
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
    }
}
