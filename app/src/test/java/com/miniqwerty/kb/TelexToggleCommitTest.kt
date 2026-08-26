package com.miniqwerty.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-tone-twice toggle behavior: the tone is undone and the key spilled
 * literally, and the IME commits the word immediately — a deliberate "this
 * word is English, done" gesture. The commit keeps a following letter from
 * re-interpreting the doubled tone key as a doubled consonant ("charr" then
 * "z" would otherwise resurrect the r as "charrz").
 */
class TelexToggleCommitTest {

    // ── hasToneToggle ────────────────────────────────────────────────────

    @Test fun `same tone key twice is a toggle`() {
        assertTrue(TelexProcessor.hasToneToggle("charr"))
        assertTrue(TelexProcessor.hasToneToggle("forr"))
        assertTrue(TelexProcessor.hasToneToggle("mayss"))
        assertTrue(TelexProcessor.hasToneToggle("anss"))
    }

    @Test fun `single tone key is not a toggle`() {
        assertFalse(TelexProcessor.hasToneToggle("char"))
        assertFalse(TelexProcessor.hasToneToggle("for"))
        assertFalse(TelexProcessor.hasToneToggle("car"))
        // Mid-word tone key ("masy" → máy) is a tone, not a toggle.
        assertFalse(TelexProcessor.hasToneToggle("masy"))
        assertFalse(TelexProcessor.hasToneToggle("mast"))
        // Different tone keys replace, not toggle.
        assertFalse(TelexProcessor.hasToneToggle("masf"))
        // No vowel → the doubled letters are literal consonants, not a toggle.
        assertFalse(TelexProcessor.hasToneToggle("strr"))
        // A doubled tone-key followed by a vowel is a real English consonant
        // (carry, office) — the word is not a toggle mid-way.
        assertFalse(TelexProcessor.hasToneToggle("carr" + "y"))
    }

    // ── The toggle's resolved form ───────────────────────────────────────

    @Test fun `toggle resolves the tone off and spills the key`() {
        assertEquals("char", TelexProcessor.resolve("charr"))
        assertEquals("for", TelexProcessor.resolve("forr"))
    }

    // ── IME flow: the toggle commits the word immediately ────────────────

    /** Stand-in for MiniKeyboardIME.onCharacter's commit-on-toggle branch. */
    private class Sim {
        val raw = StringBuilder()
        val text = StringBuilder()

        fun type(ch: Char) {
            if (TelexProcessor.shouldCommit(ch)) {
                commitComposing()
                text.append(ch)
                return
            }
            raw.append(ch)
            if (TelexProcessor.hasToneToggle(raw.toString())) {
                commitComposing()
            }
        }

        private fun commitComposing() {
            if (raw.isNotEmpty()) {
                text.append(TelexProcessor.resolve(raw.toString(), smart = true))
                raw.clear()
            }
        }
    }

    @Test fun `toggled word commits so the next letter starts fresh`() {
        val s = Sim()
        "charrz".forEach { s.type(it) }
        // "charr" toggled → "char" committed immediately, buffer cleared.
        assertEquals("char", s.text.toString())
        assertEquals("z", s.raw.toString())
        s.type(' ')
        assertEquals("charz ", s.text.toString())
    }

    @Test fun `toggle then vowel continuation also stays fresh`() {
        val s = Sim()
        "charrzy".forEach { s.type(it) }
        assertEquals("char", s.text.toString())
        assertEquals("zy", s.raw.toString())
        s.type(' ')
        assertEquals("charzy ", s.text.toString())
    }

    @Test fun `doubled English consonant splits at the toggle`() {
        // Documented tradeoff: typing a doubled tone-key (s/f/r/x/j) always
        // hits the toggle, so doubled-consonant words split mid-word.
        val s = Sim()
        "carry".forEach { s.type(it) }
        assertEquals("car", s.text.toString())   // "carr" toggled
        assertEquals("y", s.raw.toString())
        s.type(' ')
        assertEquals("cary ", s.text.toString())
    }

    @Test fun `single tone key keeps composing`() {
        val s = Sim()
        "char".forEach { s.type(it) }
        assertEquals("", s.text.toString())
        assertEquals("chả", TelexProcessor.resolve("char", smart = true))
        assertEquals("char", s.raw.toString())
    }
}
