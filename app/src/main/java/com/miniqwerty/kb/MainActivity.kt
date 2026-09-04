package com.miniqwerty.kb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Settings screen for the keyboard: theme mode, haptics, smart Telex,
 * and the double-tap window.
 * Also the launcher entry point for the app.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // ── Theme mode ────────────────────────────────────────────────────
        val themeGroup = findViewById<RadioGroup>(R.id.theme_group)
        when (prefs.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_SYSTEM)) {
            Prefs.THEME_LIGHT -> themeGroup.check(R.id.theme_light)
            Prefs.THEME_DARK  -> themeGroup.check(R.id.theme_dark)
            Prefs.THEME_BLACK -> themeGroup.check(R.id.theme_black)
            else              -> themeGroup.check(R.id.theme_system)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_light -> Prefs.THEME_LIGHT
                R.id.theme_dark  -> Prefs.THEME_DARK
                R.id.theme_black -> Prefs.THEME_BLACK
                else             -> Prefs.THEME_SYSTEM
            }
            prefs.edit().putInt(Prefs.KEY_THEME_MODE, mode).apply()
        }

        // ── Haptics ───────────────────────────────────────────────────────
        val hapticSwitch = findViewById<Switch>(R.id.haptic_switch)
        hapticSwitch.isChecked = prefs.getBoolean(Prefs.KEY_HAPTIC_ENABLED, true)
        hapticSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_HAPTIC_ENABLED, checked).apply()
        }

        // ── Smart Telex ───────────────────────────────────────────────────
        val smartSwitch = findViewById<Switch>(R.id.smart_switch)
        smartSwitch.isChecked = prefs.getBoolean(Prefs.KEY_SMART_TELEX_ENABLED, true)
        smartSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_SMART_TELEX_ENABLED, checked).apply()
        }

        // ── Double-tap window ─────────────────────────────────────────────
        val doubleTapValue = findViewById<TextView>(R.id.double_tap_value)
        val doubleTapSeek = findViewById<SeekBar>(R.id.double_tap_seek)
        fun msToProgress(ms: Long): Int =
            ((ms - Prefs.DOUBLE_TAP_MIN_MS) / 10).toInt()
        fun progressToMs(progress: Int): Long =
            Prefs.DOUBLE_TAP_MIN_MS + progress * 10L

        val currentMs = prefs.getLong(Prefs.KEY_DOUBLE_TAP_MS, Prefs.DOUBLE_TAP_DEFAULT_MS)
            .coerceIn(Prefs.DOUBLE_TAP_MIN_MS, Prefs.DOUBLE_TAP_MAX_MS)
        doubleTapSeek.progress = msToProgress(currentMs)
        doubleTapValue.text = getString(R.string.double_tap_label, currentMs)
        // Label updates live; the value is persisted when the drag ends to
        // avoid writing on every tick.
        doubleTapSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                doubleTapValue.text = getString(R.string.double_tap_label, progressToMs(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                prefs.edit().putLong(Prefs.KEY_DOUBLE_TAP_MS, progressToMs(seekBar.progress)).apply()
            }
        })

        // ── Auto-capitalize ───────────────────────────────────────────────
        val autoCapSwitch = findViewById<Switch>(R.id.auto_cap_switch)
        autoCapSwitch.isChecked = prefs.getBoolean(Prefs.KEY_AUTO_CAPITALIZE, true)
        autoCapSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_AUTO_CAPITALIZE, checked).apply()
        }

        // ── Clipboard suggestions ─────────────────────────────────────────
        val suggestionSwitch = findViewById<Switch>(R.id.suggestion_switch)
        suggestionSwitch.isChecked = prefs.getBoolean(Prefs.KEY_SUGGESTION_STRIP, true)
        suggestionSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_SUGGESTION_STRIP, checked).apply()
        }

        // ── Enable keyboard shortcut ──────────────────────────────────────
        findViewById<Button>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }
}
