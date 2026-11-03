package com.miniqwerty.kb

/**
 * An edit the engine wants applied before the cursor (Vikey `Engine/Driver.h` → `VKEdit`).
 * Delete [deleteCount] characters, then insert [text]. [languageChanged] means the engine
 * auto-switched the typing language and the UI should follow.
 */
class Edit(val deleteCount: Int, val text: String, val languageChanged: Boolean) {
    override fun toString() = "Edit(-$deleteCount, \"$text\"${if (languageChanged) ", lang" else ""})"
}

/**
 * JNI face of the vendored Vikey engine (`app/src/main/cpp/vikey_jni.cpp`).
 *
 * The engine keeps its word state in C++ statics, so **one [Driver] per process** — see
 * the note on [Driver].
 */
internal object VikeyDriver {
    init { System.loadLibrary("vikey") }

    @JvmStatic external fun create(): Long
    @JvmStatic external fun destroy(h: Long)
    @JvmStatic external fun language(h: Long): Int
    @JvmStatic external fun setLanguage(h: Long, l: Int)
    @JvmStatic external fun configure(
        h: Long,
        autoSwitch: Boolean,
        pauseAfterManual: Double,
        spelling: Boolean,
        restoreIfWrongSpelling: Boolean,
        modernOrthography: Boolean,
        teencode: Boolean,
    )
    @JvmStatic external fun typeChar(h: Long, c: Char, now: Double): String
    @JvmStatic external fun backspace(h: Long, now: Double): String
    @JvmStatic external fun manualSwitch(h: Long, now: Double)
    @JvmStatic external fun resetContext(h: Long)
}

/**
 * One typing session over the engine.
 *
 * Not thread-safe, and only one instance may exist per process: the engine stores the
 * current word, the previous word and the auto-switch undo data in file-scope globals, so
 * a second instance would read and overwrite the first one's state. Create it in
 * `onCreateInputView` or alongside the IME service, never per keystroke, and [close] it
 * when the service goes away.
 */
class Driver {
    private val handle = VikeyDriver.create()
    private var closed = false

    /** Auto switch Vietnamese ⇄ English while typing. */
    var autoSwitch = true
    /** Seconds auto switch stays off after a manual language change (0 = off). */
    var pauseAfterManual = 5.0
    /** Reject shapes that are not valid Vietnamese syllables (the English fallback). */
    var spelling = true
    /** At word end, put back the raw keys when the resolved word is not valid. */
    var restoreIfInvalid = true
    /** Vikey's "modern" orthography: hoà/thuý. Off keeps hòa/thúy (this app's current output). */
    var modernOrthography = false
    /** Teencode (z = d, "gòy"…). Upstream defaults to on with no UI; this app keeps it off. */
    var teencode = false

    var language: Int
        get() = VikeyDriver.language(handle)
        set(value) = VikeyDriver.setLanguage(handle, value)

    /** Push [autoSwitch]/[spelling]/… into the engine. Call after changing any of them. */
    fun applySettings() = VikeyDriver.configure(
        handle, autoSwitch, pauseAfterManual, spelling, restoreIfInvalid, modernOrthography, teencode,
    )

    /** One character typed: letters, digits, punctuation, " " or "\n". */
    fun typeCharacter(text: String, now: Double): Edit {
        if (closed) return Edit(0, text, false)
        if (text.length != 1) { VikeyDriver.resetContext(handle); return Edit(0, text, false) }
        return unpack(VikeyDriver.typeChar(handle, text[0], now))
    }

    fun backspace(now: Double): Edit {
        if (closed) return Edit(0, "", false)
        return unpack(VikeyDriver.backspace(handle, now))
    }

    /** The user switched language by hand (the VI/EN key). */
    fun manualSwitch(now: Double) {
        if (!closed) VikeyDriver.manualSwitch(handle, now)
    }

    /** The cursor moved or the field changed: the next word starts fresh. */
    fun resetContext() {
        if (!closed) VikeyDriver.resetContext(handle)
    }

    fun close() {
        if (closed) return
        closed = true
        VikeyDriver.destroy(handle)
    }

    /** deleteCount, then flags, then the text. */
    private fun unpack(packed: String) = Edit(
        deleteCount = packed[0].code,
        text = packed.substring(2),
        languageChanged = packed[1].code and 1 == 1,
    )

    companion object {
        const val VIETNAMESE = 1
        const val ENGLISH = 0
    }
}
