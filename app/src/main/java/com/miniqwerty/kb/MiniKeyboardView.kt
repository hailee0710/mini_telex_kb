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
}

// ─────────────────────────────────────────────────────────────────────────────
// Key Definitions
// ─────────────────────────────────────────────────────────────────────────────

private enum class KeyType {
    CHARACTER, BACKSPACE, SHIFT, NUMERIC, ABC, SPACE, RETURN, SYMBOLS,
    CLIPBOARD, CLIPBOARD_ITEM, CLIPBOARD_CLOSE
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
                primaryTextPaint.color = 0xFFFFFFFF.toInt()
                toneTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                capsLockTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                secondaryTextPaint.color = 0xFF6E6E73.toInt()
                functionTextPaint.color = 0xFFFFFFFF.toInt()
                functionBoldTextPaint.color = 0xFFFFFFFF.toInt()
                clipboardItemTextPaint.color = 0xFFFFFFFF.toInt()
                handlePaint.color = 0x59FFFFFF.toInt()
            }
            PALETTE_DARK -> {
                bgPaint.color = DARK_BG_COLOR
                keyBgPaint.color = 0xFF484C4F.toInt()
                keyBgModifierPaint.color = 0xFF373C40.toInt()
                keyBgPressedPaint.color = 0xFF5A5F64.toInt()
                primaryTextPaint.color = 0xFFFFFFFF.toInt()
                toneTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                capsLockTextPaint.color = 0xFFFF8A50.toInt() // orange accent
                secondaryTextPaint.color = 0xFF8F8F8F.toInt()
                functionTextPaint.color = 0xFFFFFFFF.toInt()
                functionBoldTextPaint.color = 0xFFFFFFFF.toInt()
                clipboardItemTextPaint.color = 0xFFFFFFFF.toInt()
                handlePaint.color = 0x59FFFFFF.toInt()
            }
            else -> { // PALETTE_LIGHT
                bgPaint.color = LIGHT_BG_COLOR
                keyBgPaint.color = 0xFFEEEEEE.toInt()
                keyBgModifierPaint.color = 0xFFDDE0E4.toInt()
                keyBgPressedPaint.color = 0xFFCFD3D7.toInt()
                primaryTextPaint.color = 0xFF2A2A2A.toInt()
                toneTextPaint.color = 0xFFE65100.toInt() // orange accent
                capsLockTextPaint.color = 0xFFE65100.toInt() // orange accent
                secondaryTextPaint.color = 0xFF8A8A8A.toInt()
                functionTextPaint.color = 0xFF4A4A4A.toInt()
                functionBoldTextPaint.color = 0xFF4A4A4A.toInt()
                clipboardItemTextPaint.color = 0xFF2A2A2A.toInt()
                handlePaint.color = 0x66808080.toInt()
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
        // Clipboard is now a long-press on ⏎, so no dedicated button crowds
        // the space bar.
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

    // Clipboard list scroll state (drag vertically on an item row to scroll).
    private var clipboardScrollPx: Float = 0f
    private var clipboardScrollActive: Boolean = false
    private var clipboardScrollStartY: Float = 0f
    private var clipboardScrollStartPx: Float = 0f

    // Height of one clipboard item slot. Computed per layout — NOT keyHeight,
    // which is stale when the layer switches because the view size does not
    // change (clipboard layer is the same total height as the letters layer).
    private var clipboardSlotH: Float = 0f

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
        val height = ((HANDLE_HEIGHT_DP + rowHeightDp * effectiveRows(rows)) * density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h

        val density = resources.displayMetrics.density
        handleHeightPx = HANDLE_HEIGHT_DP * density
        // Clipboard layer: compact item slots in the main keyboard's height.
        val rows = if (currentLayer == KeyboardLayer.CLIPBOARD) CLIPBOARD_SLOTS.toFloat()
                   else effectiveRows(keys.size)
        keyHeight = (h - handleHeightPx) / rows

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
        // Clip rows are short — scale text relative to the row, not the key.
        clipboardItemTextPaint.textSize = keyHeight * 0.34f

        layoutKeys()
        clipboardScrollPx = clipboardScrollPx.coerceIn(0f, clipboardMaxScrollPx)

        // Cursor mode granularity: a quarter of a letter-column width per
        // character — smaller step per char makes the cursor move faster
        // for the same horizontal finger travel.
        val aKey = letterKeys[1][0]
        cursorPxPerChar = (aKey.right - aKey.left) * 0.25f
    }

    /** Total row-height units: every row is 1 unit, the last row is 75%. */
    private fun effectiveRows(rowCount: Int): Float = (rowCount - 1) + ROW3_HEIGHT_RATIO

    /** Assign pixel bounds to every key based on column spans. */
    private fun layoutKeys() {
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            layoutClipboardKeys()
            return
        }
        // Keys sit inset from the screen edges, like the reference look.
        val marginX = KEY_MARGIN_DP * resources.displayMetrics.density
        val availWidth = viewWidth - 2 * marginX

        for ((rowIdx, row) in keys.withIndex()) {
            // The last row (control row) is 75% of the standard row height.
            val rowH = if (rowIdx == keys.lastIndex) keyHeight * ROW3_HEIGHT_RATIO else keyHeight
            val y = handleHeightPx + rowIdx * keyHeight

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
     * Clipboard layer layout: item rows stack full-width from the top,
     * shifted up by [clipboardScrollPx]. The close button is a small FAB
     * near the bottom-right corner, floating above the list.
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

        // Slot height from the layer's own geometry, not keyHeight (which is
        // stale until a size change — see clipboardSlotH declaration).
        clipboardSlotH = (viewHeight - handleHeightPx) / CLIPBOARD_SLOTS
        clipboardItemTextPaint.textSize = clipboardSlotH * 0.34f

        var y = handleHeightPx - clipboardScrollPx
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
        // resizes and keyHeight is recomputed in onSizeChanged.
        requestLayout()
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

        for (row in keys) {
            for (key in row) {
                drawKey(canvas, key, key == downKey)
            }
        }

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
        // characters and the space bar use the standard key fill. No border.
        val bg = when {
            pressed -> keyBgPressedPaint
            key.keyType == KeyType.SPACE ||
                key.keyType == KeyType.CHARACTER ||
                key.keyType == KeyType.CLIPBOARD_ITEM -> keyBgPaint
            else -> keyBgModifierPaint
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bg)

        val cx = key.left + (key.right - key.left) / 2f
        val cy = key.top + (key.bottom - key.top) / 2f

        when (key.keyType) {
            KeyType.CHARACTER      -> drawCharacterKey(canvas, key, cx, cy)
            KeyType.CLIPBOARD_ITEM -> drawClipboardItemKey(canvas, key)
            KeyType.CLIPBOARD      -> drawClipboardKey(canvas, cx, cy)
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
            color = 0xFF212121.toInt()  // dark label reads on both accent oranges
            textSize = r * 0.55f
        }
        canvas.drawText("ABC", cx, cy + r * 0.2f, labelPaint)
    }

    /** Clipboard button glyph: small monochrome outlined clipboard icon,
     *  matching the other function-key labels (⇧ ⏎) instead of the color emoji. */
    private fun drawClipboardKey(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = resources.displayMetrics.density * 1.1f
        val strokePaint = Paint(functionTextPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val hw = keyHeight * 0.11f  // half width
        val hh = keyHeight * 0.14f  // half height
        val radius = keyHeight * 0.03f
        // Board
        canvas.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), radius, radius, strokePaint)
        // Top tab
        val tabW = hw * 0.45f
        canvas.drawRect(RectF(cx - tabW, cy - hh - stroke, cx + tabW, cy - hh + stroke), strokePaint)
        // Divider line under the tab
        canvas.drawLine(cx - tabW, cy - hh * 0.4f, cx + tabW, cy - hh * 0.4f, strokePaint)
    }

    /** Clipboard history rows: single truncated line, left-aligned like a list,
     *  with a dismiss (✕) button on the right. */
    private fun drawClipboardItemKey(canvas: Canvas, key: KeyDef) {
        val density = resources.displayMetrics.density
        val pad = 12f * density
        val baseline = key.top + clipboardSlotH * 0.65f
        canvas.drawText(key.primary, key.left + pad, baseline, clipboardItemTextPaint)
        // Dismiss button — own size, rows are shorter than normal keys now.
        val dismissPaint = Paint(clipboardItemTextPaint).apply {
            textAlign = Paint.Align.CENTER
        }
        val zone = CLIP_DISMISS_ZONE_DP * density
        canvas.drawText("✕", key.right - zone / 2f, baseline, dismissPaint)
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

        // Pressed-state bg for shift while latched OR locked on.
        if (key.keyType == KeyType.SHIFT && (shiftActive || capsLockActive)) {
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

    /** Apply shift/caps-lock case: uppercase the first char of [s]. Applies to
     *  both the primary label and the secondary (double-tap) label. */
    private fun resolveCase(s: String): String {
        if ((!shiftActive && !capsLockActive) || s.isEmpty()) return s
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
                commitGesture(if (idx >= 0) event.getX(idx) else event.x)
                val newIdx = event.actionIndex
                beginGesture(event.getPointerId(newIdx), event.getX(newIdx), event.getY(newIdx))
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
                commitGesture(if (idx >= 0) event.getX(idx) else event.x)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Only the tracked pointer's release matters. An unrelated
                // finger lifting was already finalized when it was displaced.
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val idx = event.findPointerIndex(activePointerId)
                    commitGesture(if (idx >= 0) event.getX(idx) else event.x)
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
                        } else if (downKey?.keyType == KeyType.RETURN) {
                            // Long-press return opens the clipboard layer —
                            // the former clipboard button lived beside space
                            // where it was too easy to hit by mistake.
                            onKeyActionListener?.onClipboard()
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
        // The clipboard close FAB floats above the list — hit it first.
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            val ck = clipboardCloseKey
            val r = (ck.right - ck.left) / 2f
            val cx = (ck.left + ck.right) / 2f
            val cy = (ck.top + ck.bottom) / 2f
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) return ck
        }
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
            KeyType.CLIPBOARD -> listener.onClipboard()
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
        /** Keyboard background fills — shared with the IME so the system
         *  navigation bar can be tinted to match (MiniKeyboardIME). */
        const val DARK_BG_COLOR = 0xFF292E32.toInt()
        const val LIGHT_BG_COLOR = 0xFFD5D9DE.toInt()
        /** Pure black for OLED screens — pixels stay off when idle. */
        const val OLED_BG_COLOR = 0xFF000000.toInt()

        /** Color palettes the keyboard actually draws. Distinct from the
         *  Prefs.THEME_* values: THEME_SYSTEM resolves to LIGHT or DARK here. */
        private const val PALETTE_LIGHT = 0
        private const val PALETTE_DARK = 1
        private const val PALETTE_OLED = 2

        /** Resolve the palette from the theme pref (system by default, following
         *  the current uiMode). Shared with the IME for the nav-bar tint. */
        fun resolvePaletteFor(context: Context): Int =
            when (context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_SYSTEM)) {
                Prefs.THEME_LIGHT -> PALETTE_LIGHT
                Prefs.THEME_DARK  -> PALETTE_DARK
                Prefs.THEME_BLACK -> PALETTE_OLED
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

        /** Width of the per-item dismiss (✕) button zone. */
        private const val CLIP_DISMISS_ZONE_DP = 40f

        /** Radius of the floating close FAB on the clipboard layer. */
        private const val CLIP_FAB_RADIUS_DP = 18f

        /** Distance of the close FAB from the layer's bottom/right edges. */
        private const val CLIP_FAB_MARGIN_DP = 10f
    }
}
