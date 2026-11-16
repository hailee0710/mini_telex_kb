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
    var wordLength = 0
    var wordKeysExact = true
    var undoLastEdit: Edit? = null

    fun type(s: String) {
        for (c in s) typed(c)
    }

    fun typed(char: Char) {
        val e = driver.typeCharacter(char.toString(), 0.0)
        applyEdit(e)
        if (char.isLetter()) {
            wordKeys.append(char)
            accountWordEdit(e)
        } else {
            clearWord()
        }
    }

    /** MiniKeyboardIME.applyEdit: apply an engine edit and remember its inverse. */
    private fun applyEdit(e: Edit) {
        val before = field.lastContext
        undoLastEdit = if (e.deleteCount == 0) {
            Edit(e.text.length, "", false)
        } else if (before != null && before.length >= e.deleteCount) {
            Edit(e.text.length, before.takeLast(e.deleteCount), false)
        } else {
            null
        }
        field.apply(e)
    }

    /** MiniKeyboardIME.insertDirect: commit text without the engine. */
    private fun insertDirect(text: String) {
        field.insertDirect(text)
        undoLastEdit = Edit(text.length, "", false)
    }

    /** MiniKeyboardIME.onCharacter, including auto-capitalize. */
    fun character(char: Char, capitalize: Boolean = false) {
        val c = if (capitalize && char.isLetter()) char.uppercaseChar() else char
        type(c.toString())
    }

    fun backspace() {
        val e = driver.backspace(0.0)
        applyEdit(e)
        accountWordEdit(e)
        wordKeys.setLength(0)
        wordKeysExact = false
    }

    /** MiniKeyboardIME.onReplaceCharacter: replay the word with the secondary. */
    fun replaceWith(char: Char) {
        if (!wordKeysExact || wordKeys.isEmpty() || wordLength == 0) {
            replaceLastTap(char)
            return
        }
        wordKeys.setLength(wordKeys.length - 1)
        wordKeys.append(char)
        replayWord(wordLength)
    }

    /** MiniKeyboardIME.replaceLastTap: reverse the first tap, commit the secondary. */
    private fun replaceLastTap(char: Char) {
        val undo = undoLastEdit
        if (undo == null) {
            applyEdit(driver.backspace(0.0))
            character(char)
            return
        }
        applyEdit(undo)
        resetEngine()
        insertDirect(char.toString())
    }

    /** MiniKeyboardIME.replayWord — re-run the engine over the recorded keys. */
    private fun replayWord(displayed: Int) {
        // resetEngine() empties wordKeys, so the keys must be snapshotted first.
        val keys = wordKeys.toString()
        resetEngine()
        var shadow = ""
        for (c in keys) {
            val e = driver.typeCharacter(c.toString(), 0.0)
            shadow = shadow.dropLast(minOf(e.deleteCount, shadow.length)) + e.text
        }
        field.apply(Edit(displayed, shadow, false))
        wordKeys.append(keys)
        wordKeysExact = true
        wordLength = shadow.length
    }

    /** MiniKeyboardIME.accountWordEdit — keep the word's on-screen length in step. */
    private fun accountWordEdit(e: Edit) {
        if (e.deleteCount > wordLength) {
            wordKeys.setLength(0)
            wordKeysExact = false
            wordLength = 0
        } else {
            wordLength += e.text.length - e.deleteCount
        }
    }

    /** MiniKeyboardIME.clearWord. */
    private fun clearWord() {
        wordKeys.setLength(0)
        wordKeysExact = true
        wordLength = 0
    }

    /** MiniKeyboardIME.onDirectCharacter: end the word, insert without the engine. */
    fun direct(char: Char) {
        driver.resetContext()
        clearWord()
        insertDirect(char.toString())
    }

    fun reset() {
        resetEngine()
        driver.language = Driver.VIETNAMESE
        field.text.setLength(0)
        field.readContext()
    }

    /** MiniKeyboardIME.resetEngine: drop the engine word and the recorded keys. */
    fun resetEngine() {
        clearWord()
        driver.resetContext()
        undoLastEdit = null
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
    // A double-tap must keep the cluster, not delete it, and the word must keep
    // recording keys afterwards (so a second double-tap replays the whole word).
    word("double-tap keeps the cluster", "baz") { type("bas"); replaceWith('z') }
    word("double-tap twice in a word", "azb") {
        type("as"); replaceWith('z'); type("n"); replaceWith('b')
    }
    // The engine resumes a word on backspace right after the space, so the
    // first tap only adds a tone; the fallback must take back that tone, not
    // the last letter ("bán" - 1 would be "bá"), and never the word before it.
    word("double-tap on a resumed word", "bạnz") {
        type("banj"); type(" "); backspace(); type("s"); replaceWith('z')
    }
    // Auto-switch undo puts the Vietnamese rendering back on screen; the
    // double-tap still replaces only its own letter.
    word("double-tap after auto-switch undo", "ưillz") {
        type("will"); type(" "); backspace(); type("s"); replaceWith('z')
    }

    // Numeric layer: the word ends where the digit starts.
    word("digit after word", "tôi5") { type("tooi"); direct('5') }
    word("replace digit", "tôi)") { type("tooi"); direct('5'); driver.resetContext(); field.apply(Edit(1, ")", false)) }

    // Punctuation is a word break inside the engine.
    word("comma", "tôi,") { type("tooi"); type(",") }
    word("auto-switch on space", "tôi và bạn") { type("tooi"); type(" "); type("vaf"); type(" "); type("banj"); type(" ") }
    word("english sentence", "fix this bug") { type("fix"); type(" "); type("this"); type(" "); type("bug"); type(" ") }
    // A Telex word whose raw keys are also a barely-attested English entry must still
    // convert right after an English word: "huw" (Huw) and "tris" (Tris) are names,
    // not "a clearly English word", so the auto switch's D22 rule leaves them alone
    // (Engine.cpp, AS_D22_MIN_EN).
    word("ư after an english word", "server is hư") {
        type("server "); type("is "); type("huw"); type(" ")
    }
    word("tone after an english word", "server is trí") {
        type("server "); type("is "); type("tris"); type(" ")
    }

    sim.driver.close()
    println(if (failures == 0) "all cases passed" else "$failures case(s) failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
