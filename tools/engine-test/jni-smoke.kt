// End-to-end smoke test of the JNI bridge on the JVM: builds VikeyDriver.kt + the host
// build of libvikey.so and drives it the way MiniKeyboardIME does — the same applyEdit
// (delete, then insert, sending only what changed), the same backspace, the same
// "numeric layer resets the word first" rule. Catches packing/link mistakes without a
// device, and pins the engine behaviour the IME relies on.
import com.miniqwerty.kb.Driver
import com.miniqwerty.kb.Edit

private var failures = 0

/** Stands in for the target editor + the IME's lastContext cache. */
private class Field {
    val text = StringBuilder()
    var lastContext: String? = null

    fun readContext() {
        lastContext = text.toString()
    }

    /** Mirrors MiniKeyboardIME.applyEdit: send only what changes. */
    fun apply(e: Edit) {
        var deletes = e.deleteCount
        var insert = e.text
        val before = lastContext
        if (deletes > 0 && before != null && before.length >= deletes) {
            val old = before.takeLast(deletes)
            var same = 0
            while (same < old.length && same < insert.length && old[same] == insert[same]) same++
            deletes -= same
            insert = insert.substring(same)
        }
        if (deletes > 0) text.setLength(maxOf(0, text.length - deletes))
        text.append(insert)
        lastContext = text.toString().takeLast(2000)
    }

    fun insertDirect(s: String) {
        text.append(s)
        lastContext = text.toString().takeLast(2000)
    }
}

private class Sim {
    val driver = Driver().apply {
        autoSwitch = true
        spelling = true
        restoreIfInvalid = true
        modernOrthography = false
        teencode = false
        applySettings()
    }
    val field = Field()
    val wordKeys = StringBuilder()

    fun type(s: String) {
        for (c in s) typed(c)
    }

    fun typed(char: Char) {
        field.apply(driver.typeCharacter(char.toString(), 0.0))
        if (char.isLetter()) wordKeys.append(char) else wordKeys.setLength(0)
    }

    /** MiniKeyboardIME.onCharacter, including auto-capitalize. */
    fun character(char: Char, capitalize: Boolean = false) {
        val c = if (capitalize && char.isLetter()) char.uppercaseChar() else char
        type(c.toString())
    }

    fun backspace() {
        field.apply(driver.backspace(0.0))
        wordKeys.setLength(0)
    }

    /** MiniKeyboardIME.onReplaceCharacter: replay the word with the secondary. */
    fun replaceWith(char: Char) {
        val displayed = letterRunLength(field.lastContext ?: "")
        if (wordKeys.isEmpty() || displayed == 0) {
            field.apply(driver.backspace(0.0))
            character(char)
            return
        }
        wordKeys.setLength(wordKeys.length - 1)
        wordKeys.append(char)
        driver.resetContext()
        var shadow = ""
        for (c in wordKeys) {
            val e = driver.typeCharacter(c.toString(), 0.0)
            shadow = shadow.dropLast(minOf(e.deleteCount, shadow.length)) + e.text
        }
        field.apply(Edit(displayed, shadow, false))
    }

    fun letterRunLength(s: String): Int {
        var i = s.length
        while (i > 0 && s[i - 1].isLetter()) i--
        return s.length - i
    }

    /** MiniKeyboardIME.onDirectCharacter: end the word, insert without the engine. */
    fun direct(char: Char) {
        driver.resetContext()
        field.insertDirect(char.toString())
    }

    fun reset() {
        wordKeys.setLength(0)
        driver.resetContext()
        driver.language = Driver.VIETNAMESE
        field.text.setLength(0)
        field.readContext()
    }
}

private fun check(name: String, got: String, expected: String) {
    val ok = got == expected
    if (!ok) failures++
    println(
        "${if (ok) "ok  " else "FAIL"}  $name -> $got" +
            if (ok) "" else "   expected $expected   got=${got.map { it.code }} want=${expected.map { it.code }}"
    )
}

fun main() {
    // One engine for the whole run, exactly like the IME (the engine keeps its
    // word state in globals, so a second Driver in the process corrupts it).
    val sim = Sim()
    fun word(name: String, expected: String, block: Sim.() -> Unit) {
        sim.reset()
        sim.block()
        check(name, sim.field.text.toString().trimEnd(), expected)
    }

    word("telex", "tiếng") { type("tieengs"); type(" ") }
    word("telex ư", "người") { type("nguowif"); type(" ") }
    word("modern off", "hòa") { type("hoaf"); type(" ") }
    word("qu", "quả") { type("quar"); type(" ") }
    word("mid-word tone", "máy") { type("masy"); type(" ") }
    word("english shape", "cluster") { type("cluster"); type(" ") }
    word("english restore", "for") { type("for"); type(" ") }
    word("triple letter", "door") { type("dooor"); type(" ") }
    word("auto-cap", "Tiếng") { character('t', capitalize = true); type("ieengs"); type(" ") }
    word("caps telex", "â") { character('a'); character('a'); type(" ") }

    // Backspace walks the word back through the engine, not the raw keys.
    word("backspace mid-word", "tô") { type("tooi"); backspace() }
    word("backspace tone key", "tiến") { type("tieengs"); backspace() }
    word("backspace to empty", "") { type("tooi"); backspace(); backspace(); backspace() }
    // The old engine kept a literal prefix after an English fallback; the new one
    // simply re-resolves ("door" typed as dooor, then backspace).
    word("backspace at empty field", "") { backspace() }

    // Double-tap secondary: take the first tap back, type the secondary.
    word("double-tap replace", "x.") { type("x"); type(","); replaceWith('.') }
    word("double-tap tone key", "baz") { type("ba"); type("s"); replaceWith('z') }
    word("double-tap vowel", "dance") { type("dan"); type("f"); replaceWith('c'); type("e") }
    word("double-tap one-letter word", "az") { type("a"); type("s"); replaceWith('z') }
    word("double-tap, word start", "quả") { type("x"); replaceWith('q'); type("uar") }

    // Numeric layer: the word ends where the digit starts.
    word("digit after word", "tôi5") { type("tooi"); direct('5') }
    word("replace digit", "tôi)") { type("tooi"); direct('5'); driver.resetContext(); field.apply(Edit(1, ")", false)) }

    // Punctuation is a word break inside the engine.
    word("comma", "tôi,") { type("tooi"); type(",") }
    word("auto-switch on space", "tôi và bạn") { type("tooi"); type(" "); type("vaf"); type(" "); type("banj"); type(" ") }
    word("english sentence", "fix this bug") { type("fix"); type(" "); type("this"); type(" "); type("bug"); type(" ") }

    sim.driver.close()
    println(if (failures == 0) "all cases passed" else "$failures case(s) failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
