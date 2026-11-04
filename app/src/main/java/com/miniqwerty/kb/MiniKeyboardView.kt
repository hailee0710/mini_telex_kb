package com.miniqwerty.kb

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Listener dispatched by [MiniKeyboardView] when the user triggers a key action.
 */
interface OnKeyActionListener {
    fun onCharacter(char: Char)
    /** Replace the last raw character in the composing buffer (double-tap). */
    fun onReplaceCharacter(char: Char)
    /** Commit a character directly, bypassing the Telex buffer (numeric layer). */
    fun onDirectCharacter(char: Char)
    /** Replace the last directly-committed character (numeric-layer double-tap). */
    fun onReplaceDirectCharacter(char: Char)
    fun onBackspace()
    fun onShift()
    fun onNumeric()
    /** Long-press on the 123 key: leave the field and open the app's settings. */
    fun onOpenSettings()
    fun onSpace()
    fun onReturn()
    /** Move the cursor by [delta] characters (negative = left). Space-bar cursor mode. */
    fun onCursorMove(delta: Int)
    /** Open (or toggle) the clipboard layer. */
    fun onClipboard()
    /** Paste the clipboard history item at [index]. */
    fun onClipboardItem(index: Int)
    /** Remove the clipboard history item at [index]. */
    fun onClipboardDismiss(index: Int)
    /** Paste the clipboard history item at [index] from the suggestion strip. */
    fun onSuggestionItem(index: Int)
}

// ─────────────────────────────────────────────────────────────────────────────
// Key Definitions
// ─────────────────────────────────────────────────────────────────────────────

private enum class KeyType {
    CHARACTER, BACKSPACE, SHIFT, NUMERIC, ABC, SPACE, RETURN, SYMBOLS,
    CLIPBOARD_ITEM, CLIPBOARD_CLOSE
}

/** Which keyboard layer is currently displayed. */
private enum class KeyboardLayer { LETTERS, NUMERIC, SYMBOLS, CLIPBOARD }

private data class KeyDef(
    val primary: String,
    val secondary: String?,
    /** Telex tone key (s f r x j) — drawn in the orange accent. */
    val isTone: Boolean = false,
    val widthUnits: Float = 1f,
    val keyType: KeyType = KeyType.CHARACTER,
    /** Position in the clipboard history (CLIPBOARD_ITEM keys only). */
    val index: Int = -1,
    /** A quick double-tap repeats the primary instead of emitting the
     *  secondary — for symbols typed in runs (emoji: ")))", "!!!", "--").
     *  The secondary stays reachable by long-press or downward flick. */
    val repeatable: Boolean = false,
) {
    /** Pixel bounds set during layout. */
    var left: Float = 0f
    var top: Float = 0f
    var right: Float = 0f
    var bottom: Float = 0f
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom View
// ─────────────────────────────────────────────────────────────────────────────

class MiniKeyboardView(context: Context) : View(context) {

    var onKeyActionListener: OnKeyActionListener? = null

    /** Whether the Shift key is latched (uppercase next char). */
    var shiftActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Whether caps lock is on (double-tap on Shift) — every primary letter
     *  stays uppercase until toggled off. */
    var capsLockActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Auto-capitalize indicator: the next letter will be uppercase because a
     *  sentence just ended — driven by the IME, not by a tap on Shift. Purely
     *  visual: unlike [shiftActive] it never uppercases the committed
     *  character (the IME applies that case itself), so it can be raised and
     *  cleared freely without fighting the user's manual shift latch. */
    var autoShiftActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // ── Persisted preferences ────────────────────────────────────────────
    private val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    // ── Behavior toggles (re-read in refreshTheme) ───────────────────────
    private var hapticEnabled: Boolean = prefs.getBoolean(Prefs.KEY_HAPTIC_ENABLED, true)
    private var doubleTapMs: Long = prefs.getLong(Prefs.KEY_DOUBLE_TAP_MS, Prefs.DOUBLE_TAP_DEFAULT_MS)

    // ── Theme state ───────────────────────────────────────────────────────
    /** Active color palette, resolved from the theme pref (system follows
     *  uiMode). One of the companion PALETTE_* constants. */
    private var themePalette: Int = resolvePalette()

    // ── Dimensions (set during onSizeChanged) ─────────────────────────────
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var keyHeight: Float = 0f
    private var handleHeightPx: Float = 0f

    // ── Keyboard height (drag-adjustable, persisted) ──────────────────────
    private var rowHeightDp: Float =
        prefs.getFloat(Prefs.KEY_ROW_HEIGHT_DP, Prefs.ROW_HEIGHT_DEFAULT_DP)
            .coerceIn(Prefs.ROW_HEIGHT_MIN_DP, Prefs.ROW_HEIGHT_MAX_DP)

    private var dragActive: Boolean = false
    private var dragStartY: Float = 0f
    private var dragStartRowDp: Float = 0f

    // ── Layer state ───────────────────────────────────────────────────────
    private var currentLayer: KeyboardLayer = KeyboardLayer.LETTERS

    // ── Touch-tracking state ──────────────────────────────────────────────
    /** The pointer this keyboard is currently tracking. Multi-touch aware:
     *  fast typing overlaps taps, so each new pointer finalizes the previous
     *  gesture and becomes the tracked one. */
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var downKey: KeyDef? = null
    /** Key pressed at ACTION_DOWN — swipe-to-secondary stays bound to it even
     *  when [downKey] is re-targeted to a neighbor during the move. */
    private var originalDownKey: KeyDef? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isSwipeDetected: Boolean = false
    private var longPressTriggered: Boolean = false
    /** True when the current gesture moved beyond the touch slop — keeps a
     *  drifting finger from triggering a double-tap secondary. */
    private var tapMoved: Boolean = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    /** A downward flick must clear this much travel (and exit the key) before
     *  it counts as a swipe — a thumb roll on a normal tap must not. */
    private val swipeSlop: Int = touchSlop * 2

    // Space-bar cursor mode: after long-pressing space, horizontal drag moves
    // the text cursor. cursorPxPerChar maps finger dx to character steps.
    private var spaceCursorMode: Boolean = false
    private var lastCursorChars: Int = 0
    private var cursorPxPerChar: Float = 40f

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // Double-tap state: second quick tap on the same key emits its secondary.
    private var lastTapKey: KeyDef? = null
    private var lastTapTime: Long = 0L
    /** Shift state at the first tap — the primary commit consumes the latch,
     *  so the second (double-tap) tap must carry the same case forward. */
    private var lastTapShiftActive: Boolean = false

    // Backspace repeat state.
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeatRunnable: Runnable? = null
    private var backspaceRepeatActive: Boolean = false

    // ── Paints ────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyBgModifierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyBgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Key outline, stroked on the fill's edge. Only the light palette gives it
     *  a visible color: on e-ink the fill steps alone are too close in grey to
     *  separate neighbouring keys, while a 1 dp line comes out crisp. The dark
     *  palettes keep it transparent (a no-op stroke) and stay flat. */
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val toneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val functionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val functionBoldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    /** Shift key glyph while caps lock is on — same bold size as the function
     *  labels, drawn in the orange accent. */
    private val capsLockTextPaint = Paint(functionBoldTextPaint)
    private val clipboardItemTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    /** Suggestion-strip chip label paint — sized to the strip, centered. */
    private val suggestionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ── Corner radius (recomputed from key height in onSizeChanged) ──────
    private var cornerRadius = 6f

    init {
        applyTheme()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Theme
    // ─────────────────────────────────────────────────────────────────────

    /** Resolve the color palette from the saved theme preference (system by
     *  default), following the system night mode. */
    private fun resolvePalette(): Int = resolvePaletteFor(context)

    /** Re-read the theme preference and recolor. Called on init, when the keyboard
     *  window is shown, and on configuration change. Also re-reads the behavior
     *  toggles so settings changes land on the open keyboard. */
    fun refreshTheme() {
        hapticEnabled = prefs.getBoolean(Prefs.KEY_HAPTIC_ENABLED, true)
        doubleTapMs = prefs.getLong(Prefs.KEY_DOUBLE_TAP_MS, Prefs.DOUBLE_TAP_DEFAULT_MS)
        val palette = resolvePalette()
        if (themePalette == palette) return
        themePalette = palette
        applyTheme()
        invalidate()
    }

    private fun applyTheme() {
        when (themePalette) {
            PALETTE_OLED -> {
                // True black background — OLED pixels stay off. Keys sit a step
                // above black so the label reads without a bright screen.
                bgPaint.color = OLED_BG_COLOR
                keyBgPaint.color = 0xFF1B1B1D.toInt()
                keyBgModifierPaint.color = 0xFF111113.toInt()
                keyBgPressedPaint.color = 0xFF2A2A2C.toInt()
                keyBorderPaint.color = Color.TRANSPARENT
                primaryTextPaint.color = 0xFFFFFFFF.toInt()
                toneTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                capsLockTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                secondaryTextPaint.color = 0xFF6E6E73.toInt()
                functionTextPaint.color = 0xFFFFFFFF.toInt()
                functionBoldTextPaint.color = 0xFFFFFFFF.toInt()
                clipboardItemTextPaint.color = 0xFFFFFFFF.toInt()
                suggestionTextPaint.color = 0xFFFFFFFF.toInt()
                handlePaint.color = 0x59FFFFFF.toInt()
            }
            PALETTE_DARK -> {
                bgPaint.color = DARK_BG_COLOR
                keyBgPaint.color = 0xFF484C4F.toInt()
                keyBgModifierPaint.color = 0xFF373C40.toInt()
                keyBgPressedPaint.color = 0xFF5A5F64.toInt()
                keyBorderPaint.color = Color.TRANSPARENT
                primaryTextPaint.color = 0xFFFFFFFF.toInt()
                toneTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                capsLockTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                secondaryTextPaint.color = 0xFF8F8F8F.toInt()
                functionTextPaint.color = 0xFFFFFFFF.toInt()
                functionBoldTextPaint.color = 0xFFFFFFFF.toInt()
                clipboardItemTextPaint.color = 0xFFFFFFFF.toInt()
                suggestionTextPaint.color = 0xFFFFFFFF.toInt()
                handlePaint.color = 0x59FFFFFF.toInt()
            }
            PALETTE_LIGHT -> {
                // Plain light look for an LCD/OLED phone: soft grey page,
                // near-white keys, no outline (the palette is flat by design).
                bgPaint.color = LIGHT_BG_COLOR
                keyBgPaint.color = 0xFFEEEEEE.toInt()
                keyBgModifierPaint.color = 0xFFDDE0E4.toInt()
                keyBgPressedPaint.color = 0xFFCFD3D7.toInt()
                keyBorderPaint.color = Color.TRANSPARENT
                primaryTextPaint.color = 0xFF2A2A2A.toInt()
                toneTextPaint.color = 0xFFE65100.toInt() // orange accent
                capsLockTextPaint.color = 0xFFE65100.toInt() // orange accent
                secondaryTextPaint.color = 0xFF8A8A8A.toInt()
                functionTextPaint.color = 0xFF4A4A4A.toInt()
                functionBoldTextPaint.color = 0xFF4A4A4A.toInt()
                clipboardItemTextPaint.color = 0xFF2A2A2A.toInt()
                suggestionTextPaint.color = 0xFF2A2A2A.toInt()
                handlePaint.color = 0x66808080.toInt()
            }
            else -> { // PALETTE_EINK
                // White page for e-paper: background and character keys are both
                // pure white (white refreshes fastest and needs no dithering),
                // so the key outline does all the separating — and it is black
                // at full strength, because a grey line fades on the panel.
                // No accent color: an orange turns into a flat mid grey on a
                // grayscale panel, so the tone keys are plain black and bold
                // (toneTextPaint already is).
                bgPaint.color = EINK_BG_COLOR
                keyBgPaint.color = EINK_BG_COLOR
                keyBgModifierPaint.color = 0xFFEDEDED.toInt()
                keyBgPressedPaint.color = 0xFFC4C4C4.toInt()
                keyBorderPaint.color = 0xFF000000.toInt()
                primaryTextPaint.color = 0xFF000000.toInt()
                toneTextPaint.color = 0xFF000000.toInt()
                capsLockTextPaint.color = 0xFF000000.toInt()
                secondaryTextPaint.color = 0xFF4D4D4D.toInt()
                functionTextPaint.color = 0xFF000000.toInt()
                functionBoldTextPaint.color = 0xFF000000.toInt()
                clipboardItemTextPaint.color = 0xFF000000.toInt()
                suggestionTextPaint.color = 0xFF000000.toInt()
                handlePaint.color = 0xFF999999.toInt()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Layout definitions
    // ─────────────────────────────────────────────────────────────────────

    // Letters layer. QWERTY-familiar layout from tools/layout_analyzer.py:
    // the familiarity objective (effort + λ·displacement from each letter's
    // QWERTY home, λ=0.5) keeps every key at or next to its QWERTY position,
    // with the 9 rarest letters as double-tap secondaries on the key nearest
    // their QWERTY home. Tone keys X, S, F, R, J sit where Vietnamese Telex
    // typists expect them. A, E, O, D sit on keys without secondaries so the
    // Telex same-key digraphs aa/ee/oo/dd stay typeable via quick double-press.
    private val letterKeys: List<List<KeyDef>> = listOf(
        // Row 1 — letters only, "?" with "!" below it at the right end; the
        // ,(.) key lives on the space row below
        listOf(
            KeyDef("X", "Q", isTone = true),
            KeyDef("W", null),
            KeyDef("E", null),
            KeyDef("R", null, isTone = true),
            KeyDef("T", null),
            KeyDef("H", "Y"),
            KeyDef("U", null),
            KeyDef("I", "P"),
            KeyDef("O", null),
            KeyDef("?", "!"),
        ),
        // Row 2 — the eight letters, then backspace out at the far right.
        // layoutKeys() gives it the stagger and the flushed corner position.
        listOf(
            KeyDef("A", null),
            KeyDef("S", "Z", isTone = true),
            KeyDef("D", null),
            KeyDef("F", "C", isTone = true),
            KeyDef("G", "V"),
            KeyDef("N", "B"),
            KeyDef("J", "K", isTone = true),
            KeyDef("M", "L"),
            KeyDef("⌫", null, widthUnits = ROW2_BACKSPACE_UNITS, keyType = KeyType.BACKSPACE),
        ),
        // Row 3 — control row with variable-width spans, "(.) keyed to the
        // right of space. 10 total units, aligned to row 1's 10-column grid.
        // The clipboard button lives at the left of the strip above row 1, so
        // no dedicated key crowds the space bar.
        listOf(
            KeyDef("⇧", null, widthUnits = 1.5f, keyType = KeyType.SHIFT),
            KeyDef("123", null, widthUnits = 1f, keyType = KeyType.NUMERIC),
            KeyDef(" ", null, widthUnits = 5f, keyType = KeyType.SPACE),
            KeyDef(",", "."),
            KeyDef("⏎", null, widthUnits = 1.5f, keyType = KeyType.RETURN),
        ),
    )

    // Numeric layer. Row 1 is digits only; row 2 holds the frequent symbols,
    // with the rarer ones reachable by double-tap (secondaries).
    private val numericKeys: List<List<KeyDef>> = listOf(
        // Row 1 — digits only; symbols live on row 2
        listOf(
            KeyDef("1", null),
            KeyDef("2", null),
            KeyDef("3", null),
            KeyDef("4", null),
            KeyDef("5", null),
            KeyDef("6", null),
            KeyDef("7", null),
            KeyDef("8", null),
            KeyDef("9", null),
            KeyDef("0", null),
        ),
        // Row 2 — frequent symbols, backspace at the end; double-tap gives
        // the remaining symbols ( ( under ), [ under ], & under :). Symbols
        // typed in runs (emoji) are repeatable: a quick double-tap repeats
        // the primary instead of swapping to the secondary.
        listOf(
            KeyDef("@", "~"),
            KeyDef("!", "#", repeatable = true),
            KeyDef("%", "$"),
            KeyDef(":", "&"),
            KeyDef(")", "(", repeatable = true),
            KeyDef("-", "_", repeatable = true),
            KeyDef("?", "+", repeatable = true),
            KeyDef("=", ";"),
            KeyDef("/", "'"),
            KeyDef("]", "["),
            KeyDef("⌫", null, keyType = KeyType.BACKSPACE),
        ),
        // Row 3 — control row, "=\<" to the left of the space, "," below "."
        // to its right. 11 total units so the dot matches the symbol-key
        // width in row 2. "=\<" opens the rare-symbol layer.
        listOf(
            KeyDef("ABC", null, widthUnits = 1.5f, keyType = KeyType.ABC),
            KeyDef("=\\<", null, widthUnits = 1f, keyType = KeyType.SYMBOLS),
            KeyDef(" ", null, widthUnits = 6f, keyType = KeyType.SPACE),
            KeyDef(".", ","),
            KeyDef("⏎", null, widthUnits = 1.5f, keyType = KeyType.RETURN),
        ),
    )

    // Rare-symbol layer (the =\< page): the symbols that never had a key —
    // backquote and friends — plus the former numeric secondaries given a
    // one-tap home here. All are primaries (direct commit, no secondary), so a
    // quick double-tap on any of them repeats it.
    private val symbolKeys: List<List<KeyDef>> = listOf(
        // Row 1 — 10 wide, like the numeric digit row.
        listOf(
            KeyDef("`", null),
            KeyDef("\\", null),
            KeyDef("|", null),
            KeyDef("\"", null),
            KeyDef("<", null),
            KeyDef(">", null),
            KeyDef("{", null),
            KeyDef("}", null),
            KeyDef("^", null),
            KeyDef("*", null),
        ),
        // Row 2 — the old numeric secondaries (now one tap) plus backspace.
        listOf(
            KeyDef("~", null),
            KeyDef("#", null),
            KeyDef("$", null),
            KeyDef("&", null),
            KeyDef("(", null),
            KeyDef("[", null),
            KeyDef("_", null),
            KeyDef("+", null),
            KeyDef(";", null),
            KeyDef("'", null),
            KeyDef("⌫", null, keyType = KeyType.BACKSPACE),
        ),
        // Row 3 — control row, mirrors the numeric page.
        listOf(
            KeyDef("ABC", null, widthUnits = 1.5f, keyType = KeyType.ABC),
            KeyDef("123", null, widthUnits = 1f, keyType = KeyType.NUMERIC),
            KeyDef(" ", null, widthUnits = 6f, keyType = KeyType.SPACE),
            KeyDef(".", ","),
            KeyDef("⏎", null, widthUnits = 1.5f, keyType = KeyType.RETURN),
        ),
    )

    // Clipboard layer rows, rebuilt from the current history (see showClipboardLayer).
    private var clipboardItems: List<String> = emptyList()
    private var clipboardRows: List<List<KeyDef>> = emptyList()

    /** Fixed close button, pinned to the top-right slot corner — no dedicated row. */
    private val clipboardCloseKey = KeyDef("✕", null, keyType = KeyType.CLIPBOARD_CLOSE)

    /** Clipboard button, pinned to the left end of the suggestion strip — the
     *  same row as the chips, which scroll behind it. Opens (and toggles) the
     *  clipboard layer; its pixel bounds are set by [layoutStripButton]. */
    private val stripButtonRect = RectF()

    // Clipboard list scroll state (drag vertically on an item row to scroll).
    private var clipboardScrollPx: Float = 0f
    private var clipboardScrollActive: Boolean = false
    private var clipboardScrollStartY: Float = 0f
    private var clipboardScrollStartPx: Float = 0f

    // Height of one clipboard item slot, in the list region below the handle.
    // Computed per layout (see layoutClipboardKeys) so it always matches the
    // rows that are drawn and hit-tested.
    private var clipboardSlotH: Float = 0f

    /** Top edge of the item list: just below the drag handle, with a small
     *  inset so the first row never butts against it (its top edge would fall
     *  in the handle's resize gesture instead of the row's tap). Set by
     *  [layoutClipboardKeys]; 0 until the layer is first laid out. */
    private var clipboardListTopPx: Float = 0f

    // ── Suggestion strip (clipboard paste chips above row 1) ────────────
    /** Whether the strip should show at all (driven by the IME: clipboard has
     *  items and the field is empty). Never drawn on the clipboard layer. */
    private var suggestionVisible: Boolean = false
    private var suggestionItems: List<String> = emptyList()
    /** Horizontal scroll offset for the chip row. */
    private var suggestionScrollPx: Float = 0f
    /** Chip rects/labels, rebuilt by [layoutSuggestionChips]. */
    private val chipRects = ArrayList<RectF>()
    private val chipLabels = ArrayList<String>()
    /** Right edge of the chip content (before scroll) — used to clamp scroll. */
    private var suggestionContentEndPx: Float = 0f
    // Touch state for the strip, separate from the key gesture state.
    private var suggestionDownIndex = -1
    private var suggestionScrollActive = false
    private var suggestionScrollStartX = 0f
    private var suggestionScrollStartPx = 0f
    /** The strip's clipboard button is pressed (a clean release opens the layer;
     *  sliding off it turns into a chip scroll instead). */
    private var stripButtonDown = false

    /** Pixel height of the strip when visible (else 0). */
    private val stripHeightPx: Float
        get() = if (suggestionVisible && currentLayer != KeyboardLayer.CLIPBOARD)
            SUGGESTION_STRIP_HEIGHT_DP * resources.displayMetrics.density else 0f

    private val keys: List<List<KeyDef>>
        get() = when (currentLayer) {
            KeyboardLayer.LETTERS   -> letterKeys
            KeyboardLayer.NUMERIC   -> numericKeys
            KeyboardLayer.SYMBOLS   -> symbolKeys
            KeyboardLayer.CLIPBOARD -> clipboardRows
        }

    // ─────────────────────────────────────────────────────────────────────
    // Measurement & Layout
    // ─────────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val density = resources.displayMetrics.density
        // The clipboard layer keeps the main keyboard height (letterKeys.size
        // rows) and fits its 5 compact item slots inside it. Row 3 is 75% of
        // the standard row height, so the total is (rows - 1) + 0.75 rows.
        val rows = if (currentLayer == KeyboardLayer.CLIPBOARD) letterKeys.size else keys.size
        // The suggestion strip adds its own row of height above the keys. The
        // band is reserved on the clipboard layer too (where it draws nothing),
        // so switching layers does not resize the window.
        val stripH = if (suggestionVisible) SUGGESTION_STRIP_HEIGHT_DP * density else 0f
        val height = (((HANDLE_HEIGHT_DP + rowHeightDp * effectiveRows(rows)) * density) + stripH).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h

        handleHeightPx = HANDLE_HEIGHT_DP * resources.displayMetrics.density
        layoutStripButton()
        applyMetrics()

        layoutKeys()
        clipboardScrollPx = clipboardScrollPx.coerceIn(0f, clipboardMaxScrollPx)
        layoutSuggestionChips()
        suggestionScrollPx = suggestionScrollPx.coerceIn(0f, suggestionMaxScrollPx)

        // Cursor mode granularity: a quarter of a letter-column width per
        // character — smaller step per char makes the cursor move faster
        // for the same horizontal finger travel.
        val aKey = letterKeys[1][0]
        cursorPxPerChar = (aKey.right - aKey.left) * 0.25f
    }

    /**
     * Recompute the metrics derived from the layer that is now showing
     * ([keyHeight], the paint sizes, the corner radius). Called from
     * [onSizeChanged] AND on every layer switch: entering and leaving the
     * clipboard layer no longer changes the window size (its strip band is
     * reserved), so a size change alone is not enough to keep these in sync —
     * a stale clipboard slot height would otherwise squash the letters layer.
     */
    private fun applyMetrics() {
        val density = resources.displayMetrics.density
        val clipboard = currentLayer == KeyboardLayer.CLIPBOARD
        // Clipboard layer: compact item slots in the main keyboard's height.
        // The list starts a little below the handle (see clipboardListTopPx),
        // so that inset comes off the usable height here too.
        val rows = if (clipboard) CLIPBOARD_SLOTS.toFloat() else effectiveRows(keys.size)
        val inset = if (clipboard) CLIP_LIST_TOP_PAD_DP * density else stripHeightPx
        keyHeight = (viewHeight - handleHeightPx - inset) / rows

        // Size text paints proportionally
        primaryTextPaint.textSize = keyHeight * 0.34f
        toneTextPaint.textSize = keyHeight * 0.34f
        // Secondary (double-tap) labels match the primary size.
        secondaryTextPaint.textSize = keyHeight * 0.34f
        functionTextPaint.textSize = keyHeight * 0.26f
        functionBoldTextPaint.textSize = keyHeight * 0.30f
        capsLockTextPaint.textSize = keyHeight * 0.30f

        // Large rounded corners, scaled to the key height (the reference look:
        // flat keys with a generous radius).
        cornerRadius = keyHeight * 0.10f
        // Hairline outline; scaled with density, not key height — e-ink panels
        // render a 1 dp line at native resolution, thicker reads as a smudge.
        keyBorderPaint.strokeWidth = KEY_BORDER_WIDTH_DP * density
        // Clip rows are short — scale text relative to the row, not the key.
        clipboardItemTextPaint.textSize = keyHeight * 0.34f
    }

    /** Total row-height units: every row is 1 unit, the last row is 75%. */
    private fun effectiveRows(rowCount: Int): Float = (rowCount - 1) + ROW3_HEIGHT_RATIO

    /**
     * Bounds of the strip's clipboard button: a square chip at the left end of
     * the strip, exactly as tall as the chips beside it. Collapses to nothing
     * (top below bottom) while the strip is hidden, so nothing can hit it.
     */
    private fun layoutStripButton() {
        val density = resources.displayMetrics.density
        val padY = SUGGESTION_CHIP_PAD_Y_DP * density
        val marginX = KEY_MARGIN_DP * density
        val size = (stripHeightPx - 2 * padY).coerceAtLeast(0f)
        stripButtonRect.set(
            marginX,
            handleHeightPx + padY,
            marginX + size,
            handleHeightPx + padY + size
        )
    }

    /** True when (x, y) falls on the strip's clipboard button. */
    private fun isStripButtonAt(x: Float, y: Float): Boolean =
        stripHeightPx > 0f && stripButtonRect.contains(x, y)

    /** Where chip content starts: past the fixed button and its gap. */
    private val stripContentStartX: Float
        get() = stripButtonRect.right + SUGGESTION_CHIP_GAP_DP * resources.displayMetrics.density

    /** Assign pixel bounds to every key based on column spans. */
    private fun layoutKeys() {
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            layoutClipboardKeys()
            return
        }
        // Keys sit inset from the screen edges, like the reference look.
        val marginX = KEY_MARGIN_DP * resources.displayMetrics.density
        val availWidth = viewWidth - 2 * marginX
        // The suggestion strip occupies its own band above row 1.
        val stripOffset = stripHeightPx

        for ((rowIdx, row) in keys.withIndex()) {
            // The last row (control row) is 75% of the standard row height.
            val rowH = if (rowIdx == keys.lastIndex) keyHeight * ROW3_HEIGHT_RATIO else keyHeight
            val y = handleHeightPx + stripOffset + rowIdx * keyHeight

            if (currentLayer == KeyboardLayer.LETTERS && rowIdx == 1) {
                // Letters middle row is a corner-key layout, not a centering
                // one: the eight letters pin to row 1's grid with a quarter-
                // unit stagger (so they keep row 1's exact width), and the
                // backspace is pulled out to the far right — flushed to row
                // 1's edge with a clear gap after M, so a thumb aiming at M
                // can't drift onto it.
                val unitWidth = availWidth / 10f
                var x = marginX + ROW2_STAGGER_UNITS * unitWidth
                for (key in row) {
                    val w = key.widthUnits * unitWidth
                    if (key.keyType == KeyType.BACKSPACE) {
                        x = marginX + availWidth - w
                    }
                    key.left = x
                    key.top = y
                    key.right = x + w
                    key.bottom = y + rowH
                    x += w
                }
            } else {
                // Every other row tiles the full width, sized by width-units.
                val unitWidth = availWidth / row.sumOf { it.widthUnits.toDouble() }.toFloat()
                var x = marginX
                for (key in row) {
                    val w = key.widthUnits * unitWidth
                    key.left = x
                    key.top = y
                    key.right = x + w
                    key.bottom = y + rowH
                    x += w
                }
            }
        }
    }

    /**
     * Clipboard layer layout: item rows stack full-width from just below the
     * drag handle, shifted up by [clipboardScrollPx]. The close button is a
     * small FAB near the bottom-right corner, floating above the list.
     */
    private fun layoutClipboardKeys() {
        val density = resources.displayMetrics.density
        val r = CLIP_FAB_RADIUS_DP * density
        val cx = viewWidth - (CLIP_FAB_MARGIN_DP + r) * density
        val cy = viewHeight - (CLIP_FAB_MARGIN_DP + r) * density
        clipboardCloseKey.left = cx - r
        clipboardCloseKey.top = cy - r
        clipboardCloseKey.right = cx + r
        clipboardCloseKey.bottom = cy + r

        // The handle strip owns the top of the layer (drag to resize), and the
        // list starts a small inset below it, so no row is drawn flush against
        // — or partly inside — the resize gesture.
        clipboardListTopPx = handleHeightPx + CLIP_LIST_TOP_PAD_DP * density
        // Slot height from the layer's own geometry (not keyHeight, which also
        // covers the paint sizing but is not the row pitch here).
        clipboardSlotH = (viewHeight - clipboardListTopPx) / CLIPBOARD_SLOTS
        clipboardItemTextPaint.textSize = clipboardSlotH * 0.34f

        var y = clipboardListTopPx - clipboardScrollPx
        val colWidth = viewWidth / 2f
        for (row in clipboardRows) {
            for ((col, key) in row.withIndex()) {
                key.left = col * colWidth
                key.right = (col + 1) * colWidth
                key.top = y
                key.bottom = y + clipboardSlotH
            }
            y += clipboardSlotH
        }
    }

    /** Called when a new input session starts — always return to letters. */
    fun resetLayer() {
        setLayer(KeyboardLayer.LETTERS)
    }

    /** Switch the displayed layer and re-layout. */
    private fun setLayer(layer: KeyboardLayer) {
        if (currentLayer == layer) return
        currentLayer = layer
        if (layer != KeyboardLayer.LETTERS) shiftActive = false
        lastTapKey = null
        // Row count can differ between layers — remeasure so the IME window
        // resizes and keyHeight is recomputed in onSizeChanged. The metrics are
        // refreshed here too, since equal-height layers never fire that.
        requestLayout()
        applyMetrics()
        layoutKeys()
        invalidate()
    }

    /**
     * Show the clipboard layer with the given history items, one full-width
     * row per item. Tapping the clipboard button while the layer is open
     * toggles it closed, back to letters.
     */
    fun showClipboardLayer(items: List<String>) {
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            setLayer(KeyboardLayer.LETTERS)
            return
        }
        clipboardItems = items
        clipboardScrollPx = 0f  // newest item is first — start at the top
        rebuildClipboardRows()
        currentLayer = KeyboardLayer.CLIPBOARD
        shiftActive = false
        lastTapKey = null
        requestLayout()
        applyMetrics()
        layoutKeys()
        invalidate()
    }

    /** Refresh the item rows when the clipboard changes while the layer is open. */
    fun updateClipboardItems(items: List<String>) {
        if (currentLayer != KeyboardLayer.CLIPBOARD) return
        clipboardItems = items
        rebuildClipboardRows()
        clipboardScrollPx = clipboardScrollPx.coerceIn(0f, clipboardMaxScrollPx)
        requestLayout()
        applyMetrics()
        layoutKeys()
        invalidate()
    }

    private fun rebuildClipboardRows() {
        // Two items per row, newest first. Display label only — pasting goes
        // through the index, so the full text stays in the IME's history.
        clipboardRows = clipboardItems
            .mapIndexed { index, text -> index to text }
            .chunked(2)
            .map { pair ->
                pair.map { (index, text) ->
                    val label = text.replace('\n', ' ').let {
                        if (it.length > CLIP_LABEL_MAX_CHARS) it.take(CLIP_LABEL_MAX_CHARS) + "…" else it
                    }
                    KeyDef(label, null, index = index, keyType = KeyType.CLIPBOARD_ITEM)
                }
            }
    }

    /**
     * Show/hide the suggestion strip above row 1 and feed it the latest
     * clipboard items. [visible] is decided by the IME (clipboard non-empty
     * and the field is empty). Changing visibility resizes the view, since
     * the strip occupies its own row of height.
     */
    fun updateSuggestions(items: List<String>, visible: Boolean) {
        val visibilityChanged = visible != suggestionVisible
        val itemsChanged = items != suggestionItems
        suggestionItems = items
        suggestionVisible = visible
        // New content (or appearing) starts scrolled to the newest chip at the
        // left; a content-unchanged refresh keeps the user's scroll position.
        if (visibilityChanged) {
            suggestionScrollPx = 0f
            requestLayout()
        } else if (itemsChanged) {
            suggestionScrollPx = 0f
        }
        // The strip's button is sized from the band height, so it has to be
        // re-laid-out before the chips (which start after it).
        layoutStripButton()
        layoutSuggestionChips()
        invalidate()
    }

    /** Lay out one chip per clipboard item in the strip band. Chip positions
     *  already include [suggestionScrollPx], so scrolling just re-lays-out
     *  (the same pattern as the clipboard list's vertical scroll). */
    private fun layoutSuggestionChips() {
        chipRects.clear()
        chipLabels.clear()
        suggestionContentEndPx = 0f
        val stripH = stripHeightPx
        if (stripH <= 0f) return
        val density = resources.displayMetrics.density
        val padX = SUGGESTION_CHIP_PAD_X_DP * density
        val padY = SUGGESTION_CHIP_PAD_Y_DP * density
        val gap = SUGGESTION_CHIP_GAP_DP * density
        val marginX = KEY_MARGIN_DP * density
        val top = handleHeightPx + padY
        val bottom = handleHeightPx + stripH - padY

        suggestionTextPaint.textSize = stripH * SUGGESTION_TEXT_SCALE
        val xzone = SUGGESTION_DISMISS_DP * density

        var x = stripContentStartX - suggestionScrollPx
        for (item in suggestionItems) {
            val label = item.replace('\n', ' ').let {
                if (it.length > SUGGESTION_CHIP_MAX_CHARS)
                    it.take(SUGGESTION_CHIP_MAX_CHARS) + "…" else it
            }
            // Text region plus the ✕ dismiss zone at the right edge.
            val w = suggestionTextPaint.measureText(label) + padX * 2f + xzone
            chipRects.add(RectF(x, top, x + w, bottom))
            chipLabels.add(label)
            x += w + gap
        }
        // Right edge of the content (chip end plus trailing margin), used to
        // clamp the horizontal scroll. Scroll-independent — chips are laid out
        // at (stripContentStartX - scrollPx), so add scrollPx back.
        suggestionContentEndPx = (x - gap) + marginX + suggestionScrollPx
    }

    /** Max horizontal scroll so the last chip can reach the right edge. */
    private val suggestionMaxScrollPx: Float
        get() {
            if (suggestionContentEndPx <= 0f) return 0f
            val marginX = KEY_MARGIN_DP * resources.displayMetrics.density
            return (suggestionContentEndPx - (viewWidth - marginX)).coerceAtLeast(0f)
        }

    /** Index of the chip containing [x], or -1 when between chips. */
    private fun chipIndexAt(x: Float): Int {
        for (i in chipRects.indices) {
            if (x >= chipRects[i].left && x <= chipRects[i].right) return i
        }
        return -1
    }

    /** True when the touch x falls in the per-item dismiss (✕) zone. */
    private fun isInDismissZone(key: KeyDef, x: Float): Boolean {
        val zone = resources.displayMetrics.density * CLIP_DISMISS_ZONE_DP
        return x >= key.right - zone
    }

    /** Max scroll offset so the last item can reach the last slot. */
    private val clipboardMaxScrollPx: Float
        get() {
            val itemRows = clipboardItems.size.coerceAtLeast(1)
            val regionH = clipboardSlotH * CLIPBOARD_SLOTS
            return (itemRows * clipboardSlotH - regionH).coerceAtLeast(0f)
        }

    // ─────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Opaque background so the IME window is not transparent.
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), bgPaint)

        if (stripHeightPx > 0f) {
            drawSuggestionStrip(canvas)
        }

        // Item rows scroll under the drag handle: clip them to the list region
        // so a half-scrolled row never paints over the handle (or the FAB).
        val clipboard = currentLayer == KeyboardLayer.CLIPBOARD
        if (clipboard) {
            canvas.save()
            canvas.clipRect(
                0f, clipboardListTopPx, viewWidth.toFloat(), viewHeight.toFloat()
            )
        }
        for (row in keys) {
            for (key in row) {
                drawKey(canvas, key, key == downKey)
            }
        }
        if (clipboard) canvas.restore()

        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            // Close FAB floats above the list, and the empty state is a plain
            // centered hint — the layer itself is otherwise blank.
            drawClipboardFab(canvas)
            if (clipboardItems.isEmpty()) {
                canvas.drawText(
                    "Clipboard empty",
                    viewWidth / 2f,
                    viewHeight / 2f,
                    functionTextPaint
                )
            }
        }

        // Drag handle pill at the top center
        val density = resources.displayMetrics.density
        val pillW = HANDLE_PILL_WIDTH_DP * density
        val pillH = HANDLE_PILL_HEIGHT_DP * density
        val pillL = (viewWidth - pillW) / 2f
        val pillT = (handleHeightPx - pillH) / 2f
        canvas.drawRoundRect(
            RectF(pillL, pillT, pillL + pillW, pillT + pillH),
            pillH / 2f, pillH / 2f,
            handlePaint
        )
    }

    /**
     * The strip's clipboard button: a chip-shaped key with the outlined
     * clipboard glyph, pinned to the left end of the strip so it never scrolls
     * away with the chips. Opens (or closes) the clipboard layer on tap.
     */
    private fun drawStripButton(canvas: Canvas) {
        val rect = stripButtonRect
        val pressed = stripButtonDown
        canvas.drawRoundRect(
            rect, cornerRadius, cornerRadius,
            if (pressed) keyBgPressedPaint else keyBgModifierPaint
        )
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyBorderPaint)

        val density = resources.displayMetrics.density
        val band = rect.height()
        val stroke = 1.1f * density
        val strokePaint = Paint(functionTextPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val cx = rect.centerX()
        val cy = rect.centerY()
        val hw = band * 0.20f  // half width
        val hh = band * 0.24f  // half height
        val radius = hw * 0.3f
        // Board
        canvas.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), radius, radius, strokePaint)
        // Top tab, straddling the board's top edge
        val tabW = hw * 0.45f
        canvas.drawRect(RectF(cx - tabW, cy - hh - stroke, cx + tabW, cy - hh + stroke), strokePaint)
        // Divider line under the tab
        canvas.drawLine(cx - tabW, cy - hh * 0.4f, cx + tabW, cy - hh * 0.4f, strokePaint)
    }

    private fun drawKey(canvas: Canvas, key: KeyDef, pressed: Boolean) {
        // Inset every key so the background shows through as a gap (larger
        // vertically than horizontally, mirroring the reference spacing).
        val padX = KEY_PAD_X_DP * resources.displayMetrics.density
        val padY = KEY_PAD_Y_DP * resources.displayMetrics.density
        val l = key.left + padX
        val t = key.top + padY
        val r = key.right - padX
        val b = key.bottom - padY
        val rect = RectF(l, t, r, b)

        // Modifier keys (shift, backspace, layer/return) get a darker fill;
        // characters and the space bar use the standard key fill. The outline
        // is transparent except in the light palette.
        val bg = when {
            pressed -> keyBgPressedPaint
            key.keyType == KeyType.SPACE ||
                key.keyType == KeyType.CHARACTER ||
                key.keyType == KeyType.CLIPBOARD_ITEM -> keyBgPaint
            else -> keyBgModifierPaint
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bg)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyBorderPaint)

        val cx = key.left + (key.right - key.left) / 2f
        val cy = key.top + (key.bottom - key.top) / 2f

        when (key.keyType) {
            KeyType.CHARACTER      -> drawCharacterKey(canvas, key, cx, cy)
            KeyType.CLIPBOARD_ITEM -> drawClipboardItemKey(canvas, key)
            else                   -> drawFunctionKey(canvas, key, cx, cy)
        }
    }

    /** Floating close button: small circular FAB near the bottom-right,
     *  accent-colored for contrast, labeled ABC — it returns to the letters
     *  layer. Floats above the item rows with a drop shadow. */
    private fun drawClipboardFab(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val key = clipboardCloseKey
        val r = (key.right - key.left) / 2f
        val cx = (key.left + key.right) / 2f
        val cy = (key.top + key.bottom) / 2f

        val fabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Accent orange (same as the vowel accent) stands out from the
            // grey key background.
            color = toneTextPaint.color
            setShadowLayer(3f * density, 0f, 1.5f * density, 0x80000000.toInt())
        }
        canvas.drawCircle(cx, cy, r, fabPaint)

        val labelPaint = Paint(functionTextPaint).apply {
            // Dark label reads on the orange accent; the E-ink palette paints
            // the accent black, so the label flips to white there.
            color = if (themePalette == PALETTE_EINK) 0xFFFFFFFF.toInt()
                    else 0xFF212121.toInt()
            textSize = r * 0.55f
        }
        canvas.drawText("ABC", cx, cy + r * 0.2f, labelPaint)
    }

    /** Clipboard history rows: single truncated line, left-aligned like a list,
     *  with a dismiss (✕) button on the right. */
    private fun drawClipboardItemKey(canvas: Canvas, key: KeyDef) {
        val density = resources.displayMetrics.density
        val pad = 12f * density
        // Baseline from the row's own bounds (a scrolled row keeps its height).
        val baseline = key.top + (key.bottom - key.top) * 0.65f
        canvas.drawText(key.primary, key.left + pad, baseline, clipboardItemTextPaint)
        // Dismiss button — own size, rows are shorter than normal keys now.
        val dismissPaint = Paint(clipboardItemTextPaint).apply {
            textAlign = Paint.Align.CENTER
        }
        val zone = CLIP_DISMISS_ZONE_DP * density
        canvas.drawText("✕", key.right - zone / 2f, baseline, dismissPaint)
    }

    /** Suggestion strip: a fixed clipboard button at the left, then one
     *  rounded chip per clipboard item, scrollable horizontally. Each chip
     *  carries its label and a ✕ dismiss zone at its right edge (mirrors the
     *  clipboard layer's per-item ✕). */
    private fun drawSuggestionStrip(canvas: Canvas) {
        // The button is pinned: draw it first, then let the chips scroll
        // underneath it (the clip keeps them from painting over it).
        drawStripButton(canvas)
        canvas.save()
        canvas.clipRect(
            stripButtonRect.right, handleHeightPx, viewWidth.toFloat(),
            handleHeightPx + stripHeightPx
        )

        suggestionTextPaint.textAlign = Paint.Align.CENTER
        val fm = suggestionTextPaint.fontMetrics
        val xzone = SUGGESTION_DISMISS_DP * resources.displayMetrics.density
        for (i in chipRects.indices) {
            val rect = chipRects[i]
            // Skip chips scrolled out of the viewport.
            if (rect.right < 0f || rect.left > viewWidth) continue
            val pressed = i == suggestionDownIndex && !suggestionScrollActive
            canvas.drawRoundRect(
                rect, cornerRadius, cornerRadius,
                if (pressed) keyBgPressedPaint else keyBgModifierPaint
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyBorderPaint)
            // Vertically center the label inside the chip.
            val baseline = rect.top + (rect.height() - (fm.descent - fm.ascent)) / 2f - fm.ascent
            // Label centered over the text region (left of the ✕ zone).
            val labelRight = rect.right - xzone
            canvas.drawText(
                chipLabels[i],
                (rect.left + labelRight) / 2f,
                baseline,
                suggestionTextPaint
            )
            // Dismiss ✕ pinned to the right edge, smaller than the label.
            val baseSize = suggestionTextPaint.textSize
            suggestionTextPaint.textSize = stripHeightPx * SUGGESTION_DISMISS_TEXT_SCALE
            canvas.drawText("✕", rect.right - xzone / 2f, baseline, suggestionTextPaint)
            suggestionTextPaint.textSize = baseSize
        }
        canvas.restore()
    }

    private fun drawCharacterKey(canvas: Canvas, key: KeyDef, cx: Float, cy: Float) {
        val primaryPaint = if (key.isTone) toneTextPaint else primaryTextPaint
        // Corner labels sit deeper inside the key than the drawn border, so
        // wide glyphs never poke out past the left/right edges.
        val padX = (KEY_PAD_X_DP + KEY_CORNER_PAD_DP) * resources.displayMetrics.density

        // Primary pinned to the top-left corner on every character key, with
        // or without a secondary. Left-aligned from the key edge so wide
        // glyphs never spill past the border. Labels are lowercase by default
        // and follow the shift latch; the KeyDef literals themselves stay
        // uppercase.
        primaryPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            resolveCase(key.primary.lowercase()),
            key.left + padX,
            key.top + keyHeight * 0.38f,
            primaryPaint
        )

        // Secondary (double-tap) pinned to the bottom-right corner,
        // right-aligned from the key edge. Baseline raised a bit so
        // descender glyphs (g y p q ,) clear the key bottom — only if present.
        // The label follows the shift/caps state like the primary.
        if (key.secondary != null) {
            secondaryTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                resolveCase(key.secondary.lowercase()),
                key.right - padX,
                key.bottom - keyHeight * 0.22f,
                secondaryTextPaint
            )
        }
    }

    private fun drawFunctionKey(canvas: Canvas, key: KeyDef, cx: Float, cy: Float) {
        val text = when (key.keyType) {
            KeyType.SHIFT     -> if (capsLockActive) "⇪" else "⇧"
            KeyType.BACKSPACE -> "⌫"
            KeyType.NUMERIC   -> "123"
            KeyType.ABC       -> "ABC"
            KeyType.SPACE     -> ""
            KeyType.RETURN    -> "⏎"
            else              -> key.primary
        }

        // Pressed-state bg for shift while latched, auto-capped, OR locked on.
        if (key.keyType == KeyType.SHIFT && (shiftActive || capsLockActive || autoShiftActive)) {
            val padX = KEY_PAD_X_DP * resources.displayMetrics.density
            val padY = KEY_PAD_Y_DP * resources.displayMetrics.density
            canvas.drawRoundRect(
                RectF(key.left + padX, key.top + padY, key.right - padX, key.bottom - padY),
                cornerRadius, cornerRadius,
                keyBgPressedPaint
            )
        }

        if (text.isNotEmpty()) {
            // Caps lock draws the ⇪ glyph in the orange accent — visually
            // distinct from the one-character latch.
            val paint = when {
                key.keyType == KeyType.SHIFT && capsLockActive -> capsLockTextPaint
                key.keyType == KeyType.SHIFT || key.keyType == KeyType.RETURN -> functionBoldTextPaint
                else -> functionTextPaint
            }
            // Per-key height keeps labels centered on the shorter row 3.
            val rowH = key.bottom - key.top
            canvas.drawText(text, cx, cy + rowH * 0.1f, paint)
        }

        // Space bar is deliberately unlabeled — a wide blank key.
    }

    /** Apply shift/caps-lock/auto-cap case: uppercase the first char of [s].
     *  Applies to both the primary label and the secondary (double-tap)
     *  label. */
    private fun resolveCase(s: String): String {
        if ((!shiftActive && !capsLockActive && !autoShiftActive) || s.isEmpty()) return s
        val first = s[0]
        return if (first.isLowerCase()) first.uppercaseChar() + s.substring(1)
        else s
    }

    // ─────────────────────────────────────────────────────────────────────
    // Touch Handling
    // ─────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < handleHeightPx) {
                    // Drag the handle strip to resize the keyboard.
                    dragActive = true
                    activePointerId = event.getPointerId(0)
                    dragStartY = event.y
                    dragStartRowDp = rowHeightDp
                    invalidate()
                    return true
                }

                if (stripHeightPx > 0f && event.y < handleHeightPx + stripHeightPx) {
                    // Tap/drag on the suggestion strip — the clipboard button
                    // or a chip, never a key.
                    beginSuggestionGesture(event.getPointerId(0), event.x, event.y)
                    invalidate()
                    return true
                }

                beginGesture(event.getPointerId(0), event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landed. Fast typing overlaps taps, so the
                // framework reports the first finger's lift as ACTION_POINTER_UP
                // instead of ACTION_UP — finalize the first gesture now (before
                // that lift is lost) and rebind tracking to the new pointer.
                if (dragActive) return true
                val idx = event.findPointerIndex(activePointerId)
                if (isStripGestureActive()) {
                    commitSuggestionGesture(if (idx >= 0) event.getX(idx) else event.x)
                } else {
                    commitGesture(if (idx >= 0) event.getX(idx) else event.x)
                }
                val newIdx = event.actionIndex
                val nx = event.getX(newIdx)
                val ny = event.getY(newIdx)
                if (stripHeightPx > 0f && ny >= handleHeightPx && ny < handleHeightPx + stripHeightPx) {
                    beginSuggestionGesture(event.getPointerId(newIdx), nx, ny)
                } else {
                    beginGesture(event.getPointerId(newIdx), nx, ny)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Track only the active pointer — a second finger (fast typing)
                // gets its own gesture via ACTION_POINTER_DOWN.
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val ex = event.getX(idx)
                val ey = event.getY(idx)

                if (dragActive) {
                    val density = resources.displayMetrics.density
                    // Use the visible row count — the clipboard layer always
                    // shows its 5 compact slots even when the list is longer.
                    val rows = if (currentLayer == KeyboardLayer.CLIPBOARD) CLIPBOARD_SLOTS.toFloat()
                               else effectiveRows(keys.size)
                    val rowDelta = (dragStartY - ey) / density / rows
                    rowHeightDp = (dragStartRowDp + rowDelta)
                        .coerceIn(Prefs.ROW_HEIGHT_MIN_DP, Prefs.ROW_HEIGHT_MAX_DP)
                    requestLayout()
                    return true
                }

                if (spaceCursorMode) {
                    // Cursor mode: horizontal finger position maps to a cursor
                    // offset from the touch-down point, one step per
                    // cursorPxPerChar. Emit only the delta since last step.
                    val dx = ex - downX
                    val chars = Math.round(dx / cursorPxPerChar)
                    if (chars != lastCursorChars) {
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        onKeyActionListener?.onCursorMove(chars - lastCursorChars)
                        lastCursorChars = chars
                    }
                    invalidate()
                    return true
                }

                if (stripButtonDown) {
                    // Sliding off the button turns the press into a chip
                    // scroll (a clean release still opens the layer).
                    val dx = ex - downX
                    val dy = ey - downY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        stripButtonDown = false
                        suggestionScrollActive = true
                        suggestionScrollStartX = ex
                        suggestionScrollStartPx = suggestionScrollPx
                    }
                    invalidate()
                    return true
                }

                if (suggestionDownIndex >= 0 || suggestionScrollActive) {
                    // Drag horizontally on the strip to scroll the chip row.
                    // A clean tap (no drift beyond the slop) still pastes on
                    // release; crossing the slop turns the gesture into a scroll.
                    val dx = ex - downX
                    if (!suggestionScrollActive && abs(dx) > touchSlop) {
                        suggestionScrollActive = true
                        suggestionScrollStartX = ex
                        suggestionScrollStartPx = suggestionScrollPx
                    }
                    if (suggestionScrollActive) {
                        suggestionScrollPx =
                            (suggestionScrollStartPx - (ex - suggestionScrollStartX))
                                .coerceIn(0f, suggestionMaxScrollPx)
                        layoutSuggestionChips()
                        invalidate()
                    }
                    return true
                }

                if (downKey?.keyType == KeyType.CLIPBOARD_ITEM) {
                    // Drag vertically on an item row to scroll the list.
                    val dy = ey - downY
                    if (!clipboardScrollActive && abs(dy) > touchSlop) {
                        clipboardScrollActive = true
                        clipboardScrollStartY = ey
                        clipboardScrollStartPx = clipboardScrollPx
                    }
                    if (clipboardScrollActive) {
                        clipboardScrollPx = (clipboardScrollStartPx - (ey - clipboardScrollStartY))
                            .coerceIn(0f, clipboardMaxScrollPx)
                        layoutKeys()
                        invalidate()
                        return true
                    }
                }

                if (downKey == null || longPressTriggered) return true

                val dx = ex - downX
                val dy = ey - downY

                // Swipe-to-secondary: a downward flick that clearly leaves the
                // original key, vertical travel dominating horizontal. Bound to
                // the key pressed at ACTION_DOWN so re-targeting can't hijack it.
                if (!isSwipeDetected) {
                    val orig = originalDownKey
                    if (orig?.secondary != null &&
                        dy > swipeSlop && dy > abs(dx) && ey > orig.bottom
                    ) {
                        isSwipeDetected = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    }
                }

                if (!isSwipeDetected) {
                    // Re-target to the key under the finger once drift exceeds
                    // the slop, so the highlight and the released key follow it.
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        tapMoved = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                        if (downKey?.keyType == KeyType.BACKSPACE) {
                            backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                            backspaceRepeatActive = false
                        }
                        val target = findKeyAt(ex, ey)
                        if (target != null && target !== downKey) {
                            downKey = target
                            if (target.keyType == KeyType.BACKSPACE) {
                                scheduleBackspaceRepeat()
                            }
                        }
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragActive) {
                    dragActive = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    prefs.edit().putFloat(Prefs.KEY_ROW_HEIGHT_DP, rowHeightDp).apply()
                    invalidate()
                    return true
                }

                val idx = event.findPointerIndex(activePointerId)
                if (isStripGestureActive()) {
                    commitSuggestionGesture(if (idx >= 0) event.getX(idx) else event.x)
                } else {
                    commitGesture(if (idx >= 0) event.getX(idx) else event.x)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Only the tracked pointer's release matters. An unrelated
                // finger lifting was already finalized when it was displaced.
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (isStripGestureActive()) {
                        commitSuggestionGesture(if (idx >= 0) event.getX(idx) else event.x)
                    } else {
                        commitGesture(if (idx >= 0) event.getX(idx) else event.x)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragActive = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                backspaceRepeatActive = false
                spaceCursorMode = false
                clipboardScrollActive = false
                suggestionScrollActive = false
                suggestionDownIndex = -1
                stripButtonDown = false
                lastCursorChars = 0
                tapMoved = false
                originalDownKey = null
                downKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Start tracking a new gesture for [pointerId] at (x, y): capture the key,
     * fire the press haptic immediately, and arm long-press / backspace repeat.
     */
    private fun beginGesture(pointerId: Int, x: Float, y: Float) {
        activePointerId = pointerId
        originalDownKey = findKeyAt(x, y)
        downKey = originalDownKey
        downX = x
        downY = y
        isSwipeDetected = false
        longPressTriggered = false
        spaceCursorMode = false
        tapMoved = false
        lastCursorChars = 0
        // Immediate press feedback — the key still commits on release.
        if (downKey != null) haptic(HapticFeedbackConstants.KEYBOARD_TAP)

        when (downKey?.keyType) {
            KeyType.BACKSPACE -> {
                // Hold-to-repeat instead of long-press secondary.
                scheduleBackspaceRepeat()
            }
            KeyType.CLIPBOARD_ITEM -> {
                // No long-press: item rows are for tap-to-paste and
                // drag-to-scroll; a long-press pasting mid-scroll would
                // be accidental.
            }
            KeyType.RETURN -> {
                // No long-press either: Enter commits on release, so holding
                // the key does not fire a newline early. The clipboard layer
                // has its own button at the top-left of the handle strip.
            }
            null -> { /* no key under the touch — nothing to arm */ }
            else -> {
                longPressRunnable = Runnable {
                    if (downKey != null && !isSwipeDetected && !longPressTriggered) {
                        longPressTriggered = true
                        haptic(HapticFeedbackConstants.LONG_PRESS)
                        lastTapKey = null
                        if (downKey?.keyType == KeyType.SPACE) {
                            // Long-press space enters cursor mode; the key
                            // stays pressed and drags move the cursor.
                            spaceCursorMode = true
                            lastCursorChars = 0
                        } else if (downKey?.keyType == KeyType.NUMERIC) {
                            // Long-press 123 opens the app's settings instead
                            // of switching layers — a short tap still does.
                            onKeyActionListener?.onOpenSettings()
                            downKey = null
                        } else {
                            commitSecondary(downKey!!, replace = false, shifted = shiftActive)
                            downKey = null
                        }
                        invalidate()
                    }
                }
                longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
            }
        }
    }

    /**
     * Finalize the current gesture: remove pending callbacks and commit the
     * held key (tap, swipe, backspace, or clipboard item). Resets all gesture
     * state. Called on release, and when a second finger displaces this one
     * during fast typing, so the first tap is not lost.
     */
    private fun commitGesture(releaseX: Float) {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }

        val key = downKey
        if (key != null && !longPressTriggered) {
            when {
                key.keyType == KeyType.BACKSPACE -> {
                    // Released before the repeat kicked in → single delete.
                    if (!backspaceRepeatActive) {
                        onKeyActionListener?.onBackspace()
                    }
                }
                isSwipeDetected -> {
                    lastTapKey = null
                    commitSecondary(originalDownKey ?: key, replace = false, shifted = shiftActive)
                }
                key.keyType == KeyType.CLIPBOARD_ITEM -> {
                    if (clipboardScrollActive) {
                        // Scroll gesture, not a tap — keep the position.
                    } else {
                        if (isInDismissZone(key, releaseX)) {
                            onKeyActionListener?.onClipboardDismiss(key.index)
                        } else {
                            onKeyActionListener?.onClipboardItem(key.index)
                        }
                    }
                }
                else -> handleQuickTap(key)
            }
        }

        backspaceRepeatActive = false
        spaceCursorMode = false
        clipboardScrollActive = false
        lastCursorChars = 0
        tapMoved = false
        originalDownKey = null
        downKey = null
    }

    /** True while a strip gesture (clipboard button press or chip tap/scroll)
     *  owns the touch. */
    private fun isStripGestureActive(): Boolean =
        stripButtonDown || suggestionDownIndex >= 0 || suggestionScrollActive

    /** Start tracking a gesture on the suggestion strip at ([x], [y]). A press
     *  on the clipboard button or on a chip fires the immediate haptic; either
     *  commits on a clean release (see [commitSuggestionGesture]). */
    private fun beginSuggestionGesture(pointerId: Int, x: Float, y: Float) {
        activePointerId = pointerId
        stripButtonDown = isStripButtonAt(x, y)
        suggestionDownIndex = if (stripButtonDown) -1 else chipIndexAt(x)
        suggestionScrollActive = false
        downX = x
        downY = y
        // No key gesture is in flight — clear anything a prior gesture armed.
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        backspaceRepeatActive = false
        spaceCursorMode = false
        isSwipeDetected = false
        tapMoved = false
        originalDownKey = null
        downKey = null
        if (stripButtonDown || suggestionDownIndex >= 0) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Finalize a suggestion-strip gesture. A clean tap (no drag beyond the
     *  slop, which would have turned into a scroll) opens the clipboard layer
     *  on the button, or pastes the pressed chip — unless the release lands on
     *  its ✕ zone, which dismisses the entry (mirrors the clipboard layer, and
     *  the index matches its history order). */
    private fun commitSuggestionGesture(releaseX: Float) {
        val onButton = stripButtonDown
        val index = suggestionDownIndex
        val scrolled = suggestionScrollActive
        stripButtonDown = false
        suggestionDownIndex = -1
        suggestionScrollActive = false
        if (onButton) {
            // Still on the button? A slide that left it already cleared the
            // flag in ACTION_MOVE (it becomes a scroll instead).
            if (!scrolled) onKeyActionListener?.onClipboard()
            return
        }
        if (scrolled || index < 0 || index >= chipRects.size) return
        val listener = onKeyActionListener ?: return
        val xzone = SUGGESTION_DISMISS_DP * resources.displayMetrics.density
        val inDismiss = releaseX >= chipRects[index].right - xzone
        if (inDismiss) {
            listener.onClipboardDismiss(index)
        } else {
            listener.onSuggestionItem(index)
        }
    }

    /** Vibration on key press, gated by the user toggle. */
    private fun haptic(feedback: Int) {
        if (hapticEnabled) performHapticFeedback(feedback)
    }

    /**
     * Quick tap. First tap emits the top (primary) character; a second tap on
     * the same key within [doubleTapMs] replaces it with the bottom
     * (secondary) character.
     */
    private fun handleQuickTap(key: KeyDef) {
        val now = SystemClock.uptimeMillis()
        // Double-tap only when the second tap is clean — a finger that drifted
        // beyond the touch slop is a new gesture, not a deliberate double-tap.
        // Repeatable keys (")", "!", "?") never double-tap: a quick second tap
        // repeats the primary (":))" needs no pause) and the secondary stays
        // reachable by long-press or downward flick.
        val isDoubleTap = (currentLayer == KeyboardLayer.LETTERS ||
            currentLayer == KeyboardLayer.NUMERIC || currentLayer == KeyboardLayer.SYMBOLS) &&
            (key.secondary != null || key.keyType == KeyType.SHIFT) &&
            !key.repeatable &&
            key === lastTapKey && !tapMoved && now - lastTapTime <= doubleTapMs

        if (isDoubleTap) {
            if (key.keyType == KeyType.SHIFT) {
                // Double-tap Shift locks caps on/off (sticky), clearing the
                // one-character latch.
                capsLockActive = !capsLockActive
                shiftActive = false
                onKeyActionListener?.onShift()
            } else {
                commitSecondary(key, replace = true, shifted = lastTapShiftActive)
            }
            lastTapKey = null
        } else {
            // Capture the shift state before commitPrimary — it releases the
            // latch on a character, and the second (double-tap) tap needs the
            // same case carried forward.
            lastTapShiftActive = shiftActive
            commitPrimary(key)
            lastTapKey = key
            lastTapTime = now
        }
    }

    /** Repeatedly fire [OnKeyActionListener.onBackspace] while held down. */
    private fun scheduleBackspaceRepeat() {
        backspaceRepeatActive = false
        val runnable = object : Runnable {
            override fun run() {
                if (downKey?.keyType != KeyType.BACKSPACE) return
                backspaceRepeatActive = true
                onKeyActionListener?.onBackspace()
                repeatHandler.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        backspaceRepeatRunnable = runnable
        repeatHandler.postDelayed(runnable, BACKSPACE_INITIAL_DELAY_MS)
    }

    // ── Key lookup ────────────────────────────────────────────────────────

    /**
     * Find the key for a touch point using nearest-center hit testing. Every
     * key has an effective circular sweet spot — half its bounding-box diagonal
     * times [HIT_RADIUS_FACTOR] — which overlaps the gaps between neighbors, so
     * taps landing on or near key borders still register (no dead zones).
     * The sweet-spot center is offset from the visual center toward where
     * thumbs land (see [SWEET_SPOT_HEIGHT_FRACTION], [SWEET_SPOT_EDGE_PULL]);
     * among candidate keys the nearest center wins, biasing boundary taps
     * toward the key the finger most likely meant.
     */
    private fun findKeyAt(x: Float, y: Float): KeyDef? {
        // The clipboard layer is a list, not a key grid — it has its own,
        // rectangle-based lookup.
        if (currentLayer == KeyboardLayer.CLIPBOARD) return findClipboardItemAt(x, y)
        // Outside the key area: the drag-handle strip above, or below the window.
        if (y < handleHeightPx || y >= viewHeight) return null

        var best: KeyDef? = null
        var bestDist = Float.MAX_VALUE
        for (row in keys) {
            for (key in row) {
                // Off-view keys (scrolled clipboard items) never hit.
                if (key.bottom <= handleHeightPx || key.top >= viewHeight) continue
                // Sweet-spot center, biased toward where thumbs actually land:
                // shifted up (the contact pad lands below the aim point) and,
                // for row-edge keys, pulled toward the screen center.
                val kw = key.right - key.left
                val kh = key.bottom - key.top
                var cx = key.left + kw * 0.5f
                if (key === row.first()) cx += kw * SWEET_SPOT_EDGE_PULL
                if (key === row.last()) cx -= kw * SWEET_SPOT_EDGE_PULL
                val cy = key.top + kh * SWEET_SPOT_HEIGHT_FRACTION
                val dx = x - cx
                val dy = y - cy
                val rx = kw / 2f
                val ry = kh / 2f
                val radius = sqrt(rx * rx + ry * ry) * HIT_RADIUS_FACTOR
                val dist = dx * dx + dy * dy
                if (dist <= radius * radius && dist < bestDist) {
                    best = key
                    bestDist = dist
                }
            }
        }
        return best
    }

    /**
     * Clipboard-layer hit test. The rows are not keyboard keys: one is half the
     * screen wide and a fifth of the keyboard tall, so the circular sweet spot
     * used for letters (radius = half the bounding-box diagonal, ~ a third of
     * the screen width) would swallow taps far away from the drawn row — an
     * empty strip below the list still pasted the last item. Match the drawn
     * rectangles instead, clipped to the visible list region, so a tap only
     * does something when it lands on a row that is actually on screen.
     */
    private fun findClipboardItemAt(x: Float, y: Float): KeyDef? {
        // The close FAB floats above the list — hit it first.
        val ck = clipboardCloseKey
        val r = (ck.right - ck.left) / 2f
        val cx = (ck.left + ck.right) / 2f
        val cy = (ck.top + ck.bottom) / 2f
        val dx = x - cx
        val dy = y - cy
        if (dx * dx + dy * dy <= r * r) return ck
        // The drag-handle strip above and anything past the window belong to
        // no row.
        if (y < clipboardListTopPx || y >= viewHeight) return null
        for (row in clipboardRows) {
            for (key in row) {
                // Rows scrolled out of the list never hit.
                if (key.bottom <= clipboardListTopPx || key.top >= viewHeight) continue
                if (x >= key.left && x < key.right && y >= key.top && y < key.bottom) {
                    return key
                }
            }
        }
        return null
    }

    // ── Commit helpers ────────────────────────────────────────────────────

    private fun commitPrimary(key: KeyDef) {
        val listener = onKeyActionListener ?: return
        when (key.keyType) {
            KeyType.CHARACTER -> {
                val ch = if (shiftActive || capsLockActive) {
                    key.primary[0].uppercaseChar()
                } else {
                    key.primary[0].lowercaseChar()
                }
                // Auto-release the one-character latch; caps lock persists.
                if (shiftActive) shiftActive = false

                if (currentLayer != KeyboardLayer.LETTERS) {
                    // Numeric and rare-symbol keys commit directly, no Telex
                    // processing.
                    listener.onDirectCharacter(ch)
                } else {
                    listener.onCharacter(ch)
                }
            }
            KeyType.BACKSPACE -> listener.onBackspace()
            KeyType.SHIFT     -> {
                // Single tap: toggle the one-character latch; while caps lock
                // is on, a single tap exits it. Double-tap locks caps on/off
                // (see handleQuickTap).
                if (capsLockActive) {
                    capsLockActive = false
                } else {
                    shiftActive = !shiftActive
                }
                listener.onShift()
            }
            KeyType.NUMERIC   -> setLayer(KeyboardLayer.NUMERIC)
            KeyType.ABC       -> setLayer(KeyboardLayer.LETTERS)
            KeyType.SYMBOLS   -> setLayer(KeyboardLayer.SYMBOLS)
            KeyType.SPACE     -> listener.onSpace()
            KeyType.RETURN    -> listener.onReturn()
            KeyType.CLIPBOARD_ITEM -> listener.onClipboardItem(key.index)
            KeyType.CLIPBOARD_CLOSE -> setLayer(KeyboardLayer.LETTERS)
        }
    }

    private fun commitSecondary(key: KeyDef, replace: Boolean, shifted: Boolean) {
        val listener = onKeyActionListener ?: return
        if (key.secondary != null) {
            // Secondaries follow the shift/caps state like the primary. The
            // double-tap path passes the shift captured at the first tap —
            // the primary commit consumed the latch before the second tap
            // landed. Either way the latch is released here.
            val ch = if (shifted || capsLockActive) {
                key.secondary[0].uppercaseChar()
            } else {
                key.secondary[0].lowercaseChar()
            }
            if (shiftActive) shiftActive = false
            if (replace) {
                if (currentLayer != KeyboardLayer.LETTERS) {
                    // Double-tap: the digit/symbol was already committed
                    // directly, so the editor replaces it (no Telex buffer
                    // involved).
                    listener.onReplaceDirectCharacter(ch)
                } else {
                    listener.onReplaceCharacter(ch)
                }
            } else if (currentLayer != KeyboardLayer.LETTERS) {
                // Numeric / rare-symbol layer keys commit directly.
                listener.onDirectCharacter(ch)
            } else {
                listener.onCharacter(ch)
            }
        } else {
            // Fallback: if no secondary defined, treat as primary
            commitPrimary(key)
        }
    }

    companion object {
        /** Keyboard background fills — shared with the IME so the system
         *  navigation bar can be tinted to match (MiniKeyboardIME). */
        const val DARK_BG_COLOR = 0xFF292E32.toInt()
        const val LIGHT_BG_COLOR = 0xFFD5D9DE.toInt()
        /** Pure black for OLED screens — pixels stay off when idle. */
        const val OLED_BG_COLOR = 0xFF000000.toInt()
        /** Pure white page for e-paper screens (see PALETTE_EINK). */
        const val EINK_BG_COLOR = 0xFFFFFFFF.toInt()

        /** Color palettes the keyboard actually draws. Distinct from the
         *  Prefs.THEME_* values: THEME_SYSTEM resolves to LIGHT or DARK here. */
        private const val PALETTE_LIGHT = 0
        private const val PALETTE_DARK = 1
        private const val PALETTE_OLED = 2
        private const val PALETTE_EINK = 3

        /** Resolve the palette from the theme pref (system by default, following
         *  the current uiMode). Shared with the IME for the nav-bar tint. */
        fun resolvePaletteFor(context: Context): Int =
            when (context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_SYSTEM)) {
                Prefs.THEME_LIGHT -> PALETTE_LIGHT
                Prefs.THEME_DARK  -> PALETTE_DARK
                Prefs.THEME_BLACK -> PALETTE_OLED
                Prefs.THEME_EINK  -> PALETTE_EINK
                else              -> if ((context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
                    PALETTE_DARK else PALETTE_LIGHT
            }

        /** Background fill for the given palette — also tints the system
         *  navigation bar so the strip below the keyboard matches. */
        fun backgroundColor(palette: Int): Int =
            when (palette) {
                PALETTE_DARK  -> DARK_BG_COLOR
                PALETTE_OLED  -> OLED_BG_COLOR
                PALETTE_EINK  -> EINK_BG_COLOR
                else          -> LIGHT_BG_COLOR
            }

        /** Hit-target radius factor: half the key's bounding-box diagonal times
         *  this fills the gaps between adjacent keys, so taps never fall dead.
         *  >1 also absorbs the sweet-spot offsets defined below. */
        private const val HIT_RADIUS_FACTOR = 1.08f
        /** Sweet-spot vertical position as a fraction of key height. 0.5 =
         *  exact center; <0.5 shifts the target UP, because a thumb's contact
         *  pad usually lands below the key it aims at. */
        private const val SWEET_SPOT_HEIGHT_FRACTION = 0.38f
        /** Inward nudge for the leftmost/rightmost key of a row, as a fraction
         *  of key width — thumbs reach edge keys from the screen center. */
        private const val SWEET_SPOT_EDGE_PULL = 0.08f

        private const val LONG_PRESS_MS = 350L
        private const val BACKSPACE_INITIAL_DELAY_MS = 400L
        private const val BACKSPACE_REPEAT_MS = 60L

        private const val HANDLE_HEIGHT_DP = 14f
        private const val HANDLE_PILL_WIDTH_DP = 40f
        private const val HANDLE_PILL_HEIGHT_DP = 4f

        /** Key inset per side (dp): the background gap between keys. Wider
         *  vertically than horizontally, mirroring the reference spacing. */
        private const val KEY_PAD_X_DP = 1.5f
        private const val KEY_PAD_Y_DP = 2.5f
        /** Outline width in dp for the light palette's key border. */
        private const val KEY_BORDER_WIDTH_DP = 1f

        /** Extra inset for corner labels so glyphs clear the key edges (dp). */
        private const val KEY_CORNER_PAD_DP = 4f

        /** Side margin between the keyboard rows and the window edges (dp). */
        private const val KEY_MARGIN_DP = 7f

        /** Row 3 (control row) is 82.5% of the standard row height (75% plus
         *  a 10% bump). */
        private const val ROW3_HEIGHT_RATIO = 0.825f

        /** Letters-layer middle row: the eight letter keys are offset a
         *  quarter unit right of row 1's grid for a staggered typing feel. */
        private const val ROW2_STAGGER_UNITS = 0.25f

        /** Width of the letters-layer backspace (in row-1 units). Slightly
         *  narrower than the old 1.5 so it can sit flushed to the far right
         *  while leaving a clear gap after M. */
        private const val ROW2_BACKSPACE_UNITS = 1.25f

        /** Max chars of a clipboard item shown on the list row (display only;
         *  keeps the label clear of the dismiss button). */
        private const val CLIP_LABEL_MAX_CHARS = 30

        /** Item slots visible on the clipboard layer without scrolling — 5
         *  compact rows squeezed into the main keyboard's height; the full
         *  history (30) scrolls through them. */
        private const val CLIPBOARD_SLOTS = 5

        /** Gap between the drag handle and the first clipboard row (dp) — keeps
         *  the top row out of the handle's resize gesture and off the very top
         *  edge of the layer. */
        private const val CLIP_LIST_TOP_PAD_DP = 6f

        /** Width of the per-item dismiss (✕) button zone. */
        private const val CLIP_DISMISS_ZONE_DP = 40f

        /** Radius of the floating close FAB on the clipboard layer. */
        private const val CLIP_FAB_RADIUS_DP = 18f

        /** Distance of the close FAB from the layer's bottom/right edges. */
        private const val CLIP_FAB_MARGIN_DP = 10f

        /** Suggestion strip (clipboard paste chips above row 1): its own row
         *  of height above the keys, only when visible. */
        private const val SUGGESTION_STRIP_HEIGHT_DP = 40f
        /** Horizontal padding inside a chip (dp). */
        private const val SUGGESTION_CHIP_PAD_X_DP = 10f
        /** Vertical padding inside a chip (dp). */
        private const val SUGGESTION_CHIP_PAD_Y_DP = 6f
        /** Gap between adjacent chips (dp). */
        private const val SUGGESTION_CHIP_GAP_DP = 6f
        /** Max chars of a clip shown on a chip label (display only). */
        private const val SUGGESTION_CHIP_MAX_CHARS = 20
        /** Chip label font scale relative to the strip height — kept small,
         *  Gboard-like. */
        private const val SUGGESTION_TEXT_SCALE = 0.38f
        /** Per-chip dismiss (✕) zone width at the chip's right edge (dp). */
        private const val SUGGESTION_DISMISS_DP = 32f
        /** ✕ glyph font scale relative to the strip height (smaller than the
         *  label). */
        private const val SUGGESTION_DISMISS_TEXT_SCALE = 0.28f
    }
}
