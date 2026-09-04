package com.miniqwerty.kb

/**
 * Shared preference keys and defaults used by both the IME service and the
 * settings screen.
 */
object Prefs {
    const val NAME = "miniqwerty_kb_prefs"

    // Theme mode
    const val KEY_THEME_MODE = "theme_mode"
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    // Keyboard row height, in dp
    const val KEY_ROW_HEIGHT_DP = "row_height_dp"
    const val ROW_HEIGHT_DEFAULT_DP = 46f
    const val ROW_HEIGHT_MIN_DP = 30f
    const val ROW_HEIGHT_MAX_DP = 75f

    // Haptic feedback (vibrate on key press)
    const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    // Smart Telex: English-aware syllable validation + dictionary check at commit
    const val KEY_SMART_TELEX_ENABLED = "smart_telex_enabled"
    // Auto-capitalize the first letter of a new sentence (. ! ? newline / field start)
    const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
    // Quick double-tap window, in ms
    const val KEY_DOUBLE_TAP_MS = "double_tap_ms"
    const val DOUBLE_TAP_DEFAULT_MS = 200L
    const val DOUBLE_TAP_MIN_MS = 100L
    const val DOUBLE_TAP_MAX_MS = 500L
}
