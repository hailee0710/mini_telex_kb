package com.miniqwerty.kb

/**
 * Stateless Vietnamese Telex input processing engine.
 *
 * Processes a raw character buffer and resolves it into the composed Vietnamese
 * output by applying vowel transformations and tone marking rules.
 *
 * ## Vowel Transformations
 * - aw → ă    aa → â    ee → ê
 * - oo → ô    ow → ơ    uw → ư
 * - uow → ươ   uiw → ưi
 * - dd → đ
 * - Trailing w: a w typed late (after the closing consonant) still converts
 *   the word's uo into ươ ("truotw" → trươt, "truotwj" → trượt); with no uo
 *   to convert, a w typed right after a consonant spells ư directly ("trw" →
 *   trư, "nhw" → như). A w after a vowel stays literal ("hew", "new", "view").
 *
 * ## Tone Keys
 * - s → sắc (´)    f → huyền (`)    r → hỏi (̉)
 * - x → ngã (~)    j → nặng (.)     z → (reset / no tone)
 *
 * ## State Machine Rules
 * 1. Tone keys apply to the main vowel in the current composing span. They
 *    may land mid-word, before the word is complete ("masy" → máy, "mast" →
 *    mát), or at the end of the word ("mays" → máy); a tone key before any
 *    vowel is an onset consonant (sad, run) and a doubled tone-key letter is
 *    a consonant, not a tone (office, message, carry) — both stay literal.
 * 2. Pressing the same tone key twice toggles the tone off (literal char).
 *    The IME commits the word immediately — the toggle is a deliberate "this
 *    word is English" gesture, so a following letter starts a fresh word
 *    ("charr" commits "char", then "z" spells "charz"). A doubled tone-key
 *    followed by a vowel is a real English consonant and stays (carry).
 * 3. Pressing a different tone key replaces the current tone.
 * 4. The key 'z' resets the tone state (literalizes any pending tone).
 *
 * ## Undo (literal override)
 * Pressing a third copy of a transform's last letter undoes the transform
 * and forces the rest of the word literal: `dooor` → "door", `goood` →
 * "good", `uww` → "uw", `oww` → "ow", `uoww` → "uow". Trailing tone keys
 * after an undo stay literal too. Same-tone-twice already spills the tone
 * key literally (`forr` → "for").
 *
 * ## Tone Placement
 * Special vowels carry the tone (ươ tones the ơ), then triphthongs tone the
 * middle vowel, then the gi/qu consonant digraphs pass the tone to the
 * following vowel, then oa/oe/uy with a closing consonant tone the second
 * vowel (toàn, khoét, suýt — modern style: without a coda the first vowel
 * carries it: hòa, khỏe, thúy), then i/y/u/o-ending diphthongs tone the
 * first vowel, defaulting to the first vowel.
 *
 * ## Smart Mode
 * With [resolve]'s `smart` flag, the resolved result is checked against
 * Vietnamese syllable shape (and optionally a word dictionary) and the raw
 * input is returned unchanged when the result is not plausible — English
 * words typed through Telex keys commit literally.
 */
object TelexProcessor {

    // ── Vowel transform pairs ─────────────────────────────────────────────
    private val VOWEL_TRANSFORMS: Map<String, Char> = mapOf(
        "aw" to '\u0103',  // ă
        "aa" to '\u00E2',  // â
        "ee" to '\u00EA',  // ê
        "oo" to '\u00F4',  // ô
        "ow" to '\u01A1',  // ơ
        "uw" to '\u01B0',  // ư
        "dd" to '\u0111',  // đ
    )

    // ── Tone key set ──────────────────────────────────────────────────────
    // Triple digraphs must be checked before the 2-char pairs, otherwise
    // "uow" would greedily resolve "ow" → ơ and leave "uơ" instead of "ươ".
    private val TRIPLE_TRANSFORMS: Map<String, String> = mapOf(
        "uow" to "ươ",  // ươ
        "uiw" to "ưi",  // ưi — a w typed after the i still spells ư:
        // "guiwr" → gửi, parallel to canonical "guwir" (u-w-i).
    )

    private val TONE_KEYS: Set<Char> = setOf('s', 'f', 'r', 'x', 'j')

    // ── Tone mark lookup (tone char → precomposed vowel map) ──────────────
    private val TONE_MAP: Map<Char, Map<Char, Char>> = mapOf(
        's' to mapOf( // sắc
            'a' to 'á', 'ă' to '\u1EAF', 'â' to '\u1EA5',
            'e' to 'é', 'ê' to '\u1EBF',
            'i' to 'í',
            'o' to 'ó', 'ô' to '\u1ED1', 'ơ' to '\u1EDB',
            'u' to 'ú', 'ư' to '\u1EE9',
            'y' to 'ý',
        ),
        // Fix: proper precomposed tone+breve/circumflex chars
        'f' to mapOf( // huyền
            'a' to 'à', 'ă' to '\u1EB1', 'â' to '\u1EA7',
            'e' to 'è', 'ê' to '\u1EC1',
            'i' to 'ì',
            'o' to 'ò', 'ô' to '\u1ED3', 'ơ' to '\u1EDD',
            'u' to 'ù', 'ư' to '\u1EEB',
            'y' to 'ỳ',
        ),
        'r' to mapOf( // hỏi
            'a' to 'ả', 'ă' to '\u1EB3', 'â' to '\u1EA9',
            'e' to 'ẻ', 'ê' to '\u1EC3',
            'i' to 'ỉ',
            'o' to 'ỏ', 'ô' to '\u1ED5', 'ơ' to '\u1EDF',
            'u' to 'ủ', 'ư' to '\u1EED',
            'y' to 'ỷ',
        ),
        'x' to mapOf( // ngã
            'a' to 'ã', 'ă' to '\u1EB5', 'â' to '\u1EAB',
            'e' to 'ẽ', 'ê' to '\u1EC5',
            'i' to 'ĩ',
            'o' to 'õ', 'ô' to '\u1ED7', 'ơ' to '\u1EE1',
            'u' to 'ũ', 'ư' to '\u1EEF',
            'y' to 'ỹ',
        ),
        'j' to mapOf( // nặng
            'a' to 'ạ', 'ă' to '\u1EB7', 'â' to '\u1EAD',
            'e' to 'ẹ', 'ê' to '\u1EC7',
            'i' to 'ị',
            'o' to 'ọ', 'ô' to '\u1ED9', 'ơ' to '\u1EE3',
            'u' to 'ụ', 'ư' to '\u1EF1',
            'y' to 'ỵ',
        ),
    )

    // ── Uppercase tone map (built once) ───────────────────────────────────
    private val TONE_MAP_UPPER: Map<Char, Map<Char, Char>> = TONE_MAP.mapValues { (_, vowelMap) ->
        vowelMap.mapKeys { (k, _) -> k.uppercaseChar() }
            .mapValues { (_, v) -> v.uppercaseChar() }
    }

    // ── Character classifications ─────────────────────────────────────────
    private val BASE_VOWELS     = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val SPECIAL_VOWELS  = setOf('ă', 'â', 'ê', 'ô', 'ơ', 'ư')
    private val ALL_VOWELS      = BASE_VOWELS + SPECIAL_VOWELS

    // Characters that trigger an explicit commit and state reset.
    // Space and newline always commit. Standard sentence-terminating punctuation
    // also commits so that the Telex state machine is clean for the next word.
    private val COMMIT_CHARS: Set<Char> = setOf(
        ' ', '\n', '\t', '.', ',', '!', '?', ':', ';', '…',
    )

    /**
     * Resolves a raw character buffer into the fully composed Vietnamese display
     * string. This is the single entry point for the IME.
     *
     * With [smart] enabled, the resolved result is checked against Vietnamese
     * syllable shape (and optionally the [dict] of known words) and the raw
     * input is returned unchanged if the result is not plausible — making
     * English words typed with Telex keys commit literally. When the user
     * explicitly forced a literal (an undo or a tone-key spill), that output
     * is honored even if it fails validation. The composing display calls
     * with smart=true and no dict so the validation applies live while
     * typing; commit-time callers pass the loaded [dict].
     *
     * @param raw   The full raw character buffer typed so far.
     * @param smart Enable English-aware validation.
     * @param dict  Known Vietnamese words; when non-null, a resolved word
     *              absent from this set falls back to [raw]. Never loaded here
     *              — the processor stays Android-free.
     * @return      The composed string ready for [InputConnection.setComposingText].
     */
    fun resolve(raw: String, smart: Boolean = false, dict: Set<String>? = null): String {
        val core = resolveCore(raw)
        val resolved = core.text
        if (!smart || resolved == raw) return resolved
        // User-forced literals (undo / tone spill) are deliberate — fall back
        // to them, not to the raw keystrokes.
        val fallback = if (core.literalRequested) resolved else raw
        if (!isValidVietnameseSequence(stripTonesAndLower(resolved))) return fallback
        if (dict != null && resolved.lowercase() !in dict) return fallback
        return resolved
    }

    /**
     * Core resolution result: the composed [text] plus [literalRequested],
     * set when the user's own keystrokes forced a literal form (an undo, or
     * a tone key spilled by toggling the tone off).
     */
    private data class CoreResult(
        val text: String,
        val literalRequested: Boolean,
        /** True when the same tone key was pressed twice, toggling the tone off
         *  and spilling the key literally ("charr" → "char"). The IME commits
         *  such words immediately. */
        val toneToggle: Boolean = false,
    )

    private fun resolveCore(raw: String): CoreResult {
        if (raw.isEmpty()) return CoreResult("", false)

        // 1. Split into content + trailing tone-key suffix
        val baseLen = raw.indexOfLast { it !in TONE_KEYS } + 1
        // Guard: if EVERY char is a tone key, there is no vowel to tone —
        // treat all as literal.
        if (baseLen == 0) return CoreResult(raw, false)

        val content    = raw.substring(0, baseLen)
        val toneSuffix = raw.substring(baseLen)

        // 1b. Pull tone keys embedded mid-word out of the content. Telex lets
        // the tone key land before the word is complete ("masy" → máy, "mast"
        // → mát); such keys join the trailing suffix in the tone pipeline.
        val (cleaned, midTones) = extractEmbeddedTones(content)

        // 2. Apply left-to-right vowel transformations
        val (base, undone) = applyVowelTransforms(cleaned)

        val toneKeys = midTones + toneSuffix
        if (toneKeys.isEmpty()) return CoreResult(base, undone)

        // An undo means the word is deliberately literal — trailing tone
        // keys included ("dooor" → "door", not "doỏ").
        if (undone) return CoreResult(base + toneKeys, true)

        // 3. If the base has no vowel, trailing tone keys are literal.
        if (!hasVowel(base)) {
            return CoreResult(base + toneKeys, false)
        }

        // 4. Process the tone-key suffix into a final tone + literal spill.
        val (finalTone, literals) = reduceToneSuffix(toneKeys)
        val spilled = literals.isNotEmpty()

        return if (finalTone != null) {
            CoreResult(applyTone(base + literals, finalTone), spilled, toneToggle = spilled)
        } else {
            CoreResult(base + literals, spilled, toneToggle = spilled)
        }
    }

    /**
     * Returns `true` when [char] should commit the current composing word
     * and reset internal state.
     */
    fun shouldCommit(char: Char): Boolean = char in COMMIT_CHARS

    // ── private helpers ───────────────────────────────────────────────────

    /** True when [s] contains at least one Vietnamese vowel character. */
    private fun hasVowel(s: String): Boolean =
        s.any { it.lowercaseChar() in ALL_VOWELS }

    /**
     * Splits [content] into its tone-free form and the tone keys embedded
     * mid-word. A tone key counts as a tone when a vowel precedes it AND it is
     * not adjacent to another tone key: "masy" → máy, "mast" → mát. Before any
     * vowel it is an onset consonant (sad, run) and stays in the cleaned
     * string, and so do doubled tone-key letters — English never spells a
     * doubled consonant, so "ff" in office, "ss" in message and "rr" in carry
     * are consonants, not tones. Vietnamese never doubles letters, so this
     * cannot misfire on Vietnamese input.
     */
    private fun extractEmbeddedTones(content: String): Pair<String, String> {
        val midTones = StringBuilder()
        val cleaned = StringBuilder(content.length)
        var seenVowel = false
        for (i in content.indices) {
            val ch = content[i]
            val prevIsToneKey = i > 0 && content[i - 1] in TONE_KEYS
            val nextIsToneKey = i + 1 < content.length && content[i + 1] in TONE_KEYS
            val isMidTone = ch in TONE_KEYS && seenVowel && !prevIsToneKey && !nextIsToneKey
            if (isMidTone) {
                midTones.append(ch)
            } else {
                cleaned.append(ch)
            }
            if (ch.lowercaseChar() in ALL_VOWELS) seenVowel = true
        }
        return cleaned.toString() to midTones.toString()
    }

    /**
     * True when [raw] holds a mid-word tone key typed before the word was
     * complete (the 's' in "masy" / "mast"). The IME uses this to decide
     * whether the live composing display needs the dictionary check: only
     * words with embedded tones can misfire on English (base → báe passes
     * the shape check as ba+e), so only those pay the dict lookup.
     */
    fun hasEmbeddedTone(raw: String): Boolean {
        val baseLen = raw.indexOfLast { it !in TONE_KEYS } + 1
        if (baseLen <= 0) return false
        return extractEmbeddedTones(raw.substring(0, baseLen)).second.isNotEmpty()
    }

    /**
     * True when [raw] resolves through a same-tone-twice toggle — the tone key
     * was pressed a second time, toggling the tone off and spilling the key
     * literally ("charr" → "char", "forr" → "for"). The IME commits such words
     * immediately: the toggle is a deliberate "this word is English, done"
     * gesture, and committing means a following letter starts a fresh word
     * instead of re-interpreting the doubled tone key as a doubled consonant
     * ("charr" then "z" would otherwise resurrect the r as "charrz").
     */
    fun hasToneToggle(raw: String): Boolean = resolveCore(raw).toneToggle

    /**
     * Left-to-right greedy vowel-transformation pass.
     *
     * Returns the transformed string plus whether an undo happened: a third
     * press of a transform's last letter undoes the transform and emits the
     * plain letters instead (`dooor` → "door", `goood` → "good", `uww` →
     * "uw", `uoww` → "uow"). English words need this to pass through Telex
     * untouched; Vietnamese never uses triple letters, so no false positives.
     */
    private fun applyVowelTransforms(s: String): Pair<String, Boolean> {
        if (s.length < 2) return s to false
        val sb = StringBuilder(s.length)
        var undone = false
        var i = 0
        while (i < s.length) {
            if (i + 2 < s.length &&
                // "uow" after q stays qu + ơ (quờ), not q + ươ (qươ) —
                // no Vietnamese word starts with qư.
                !(i > 0 && s[i - 1].lowercaseChar() == 'q')
            ) {
                val tripleKey = s.substring(i, i + 3).lowercase()
                val tripleReplacement = TRIPLE_TRANSFORMS[tripleKey]
                if (tripleReplacement != null) {
                    if (i + 3 < s.length && s[i + 3].lowercaseChar() == tripleKey[2]) {
                        appendPlain(sb, tripleKey, s[i].isUpperCase())
                        undone = true
                        i += 4
                    } else {
                        tripleReplacement.forEach {
                            sb.append(if (s[i].isUpperCase()) it.uppercaseChar() else it)
                        }
                        i += 3
                    }
                    continue
                }
            }
            if (i + 1 < s.length) {
                val key = s.substring(i, i + 2).lowercase()
                val replacement = VOWEL_TRANSFORMS[key]
                if (replacement != null) {
                    if (i + 2 < s.length && s[i + 2].lowercaseChar() == key[1]) {
                        appendPlain(sb, key, s[i].isUpperCase())
                        undone = true
                        i += 3
                    } else {
                        sb.append(if (s[i].isUpperCase()) replacement.uppercaseChar() else replacement)
                        i += 2
                    }
                    continue
                }
            }
            // A 'w' outside a contiguous vowel transform still produces ư.
            // A late w (typed after the closing consonant) converts the
            // word's uo nucleus into ươ ("truotw" → trươt); with no uo left
            // to convert, a w typed right after a consonant spells ư directly
            // ("trw" → trư, "nhw" → như). A w after a vowel stays literal
            // ("hew", "new", "view").
            if (s[i].lowercaseChar() == 'w') {
                if (convertTrailingUo(sb)) {
                    // The w is consumed converting an earlier uo into ươ.
                } else if (i > 0) {
                    val prev = s[i - 1].lowercaseChar()
                    // Any non-vowel letter counts as a consonant here — even a
                    // tone key (r in "trw", s in "swim") is a real consonant
                    // when it is not applying a tone.
                    if (prev in 'a'..'z' && prev !in ALL_VOWELS && prev != 'w') {
                        sb.append(if (s[i].isUpperCase()) 'Ư' else 'ư')
                    } else {
                        sb.append(s[i])
                    }
                } else {
                    sb.append(s[i])
                }
                i++
                continue
            }

            sb.append(s[i])
            i++
        }
        return sb.toString() to undone
    }

    /**
     * Replaces the last uo pair already written to [sb] with ươ, consuming
     * the trailing w that triggered the conversion ("truotw" → trươt). The u
     * of a qu digraph is a consonant and is skipped. Returns false when no
     * uo pair remains.
     */
    private fun convertTrailingUo(sb: StringBuilder): Boolean {
        for (j in sb.length - 1 downTo 0) {
            if (sb[j].lowercaseChar() != 'u') continue
            if (j > 0 && sb[j - 1].lowercaseChar() == 'q') continue
            if (j + 1 < sb.length && sb[j + 1].lowercaseChar() == 'o') {
                val upper = sb[j].isUpperCase()
                sb.replace(j, j + 2, if (upper) "ƯƠ" else "ươ")
                return true
            }
        }
        return false
    }

    /** Appends [key]'s letters unchanged, cased by [upper]. */
    private fun appendPlain(sb: StringBuilder, key: String, upper: Boolean) {
        for (c in key) sb.append(if (upper) c.uppercaseChar() else c)
    }

    /**
     * Reduces a string consisting exclusively of tone keys into:
     * - [finalTone]: the active tone (or null if toggled off)
     * - [literals]:  literal chars spilled from cancelled tones.
     *
     * Rules: same-tone twice → toggle off (char becomes literal);
     *        different tone → replace (old tone is discarded, no literal).
     */
    private data class ToneResult(val finalTone: Char?, val literals: String)

    private fun reduceToneSuffix(toneSuffix: String): ToneResult {
        var tone: Char? = null
        val literals = StringBuilder()

        for (ch in toneSuffix) {
            if (tone == null) {
                tone = ch
            } else if (tone == ch) {
                // Same tone → toggle off: the tone char becomes literal.
                literals.append(ch)
                tone = null
            } else {
                // Different tone → replace; previous tone is discarded.
                tone = ch
            }
        }
        return ToneResult(tone, literals.toString())
    }

    /**
     * Applies [tone] to the main vowel of [base] and returns the result.
     * If no vowel is found the string is returned unchanged.
     */
    private fun applyTone(base: String, tone: Char): String {
        val idx = findMainVowelIndex(base) ?: return base
        val vowel = base[idx]
        val isUpper = vowel.isUpperCase()

        // Look up the vowel with its own case: TONE_MAP keys are lowercase,
        // TONE_MAP_UPPER keys are uppercase — lowercasing the key would miss
        // the uppercase map and silently drop the tone ("As" → "A").
        val toneMap = if (isUpper) TONE_MAP_UPPER[tone] else TONE_MAP[tone]
        val tonedVowel = toneMap?.get(vowel) ?: return base

        return buildString(base.length) {
            append(base, 0, idx)
            append(tonedVowel)
            append(base, idx + 1, base.length)
        }
    }

    /**
     * Locates the "main vowel" index in a Vietnamese string according to
     * standard orthographic tone-placement rules.
     *
     * Priority:
     *  1. Modified vowels (â ê ô ă ơ ư) always carry the tone.
     *     Exception: in the ươ cluster the tone lands on ơ (người, được).
     *  2. Triphthongs (3+ vowels): tone on the middle vowel.
     *  3. gi- digraph: the i is a consonant, tone on the following vowel
     *     (gió, già); gi + triphthong is handled by Rule 2.
     *  4. qu- digraph: the u is a consonant, tone on the following vowel
     *     (quả, quá).
     *  5. oa/oe/uy with a closing consonant: tone on the second vowel
     *     (toàn, hoàng, khoét, suýt). Without a coda the modern spelling
     *     tones the first vowel — those fall through (hòa, khỏe, thúy).
     *  6. Diphthongs ending with i/y/u/o (ai, ay, ao, au, ui, oi…):
     *     tone on the first vowel.
     *  7. Otherwise: first vowel.
     */
    private fun findMainVowelIndex(word: String): Int? {
        // Rule 1: special vowel. Exception: in the ươ cluster the tone
        // lands on ơ (người, được), not on ư.
        for (i in word.indices) {
            if (word[i].lowercaseChar() in SPECIAL_VOWELS) {
                if (word[i].lowercaseChar() == 'ư' && i + 1 < word.length &&
                    word[i + 1].lowercaseChar() == 'ơ') {
                    return i + 1
                }
                return i
            }
        }

        val positions = word.indices.filter {
            word[it].lowercaseChar() in ALL_VOWELS
        }
        if (positions.isEmpty()) return null
        if (positions.size == 1) return positions[0]

        val first  = word[positions[0]].lowercaseChar()
        val last   = word[positions.last()].lowercaseChar()

        // Rule 2: triphthong
        if (positions.size >= 3) return positions[positions.size - 2]

        // Rule 3: gi- digraph — the i is a consonant, so the following
        // vowel (positions[1]) carries the tone (gió, già).
        if (first == 'i' && positions[0] > 0 &&
            word[positions[0] - 1].lowercaseChar() == 'g') {
            return positions[1]
        }

        // Rule 4: qu- digraph — the u is a consonant, so the remaining
        // vowel (positions[1]) carries the tone (quả, quá).
        if (first == 'u' && positions[0] > 0 &&
            word[positions[0] - 1].lowercaseChar() == 'q') {
            return positions[1]
        }

        // Rule 5: oa/oe/uy with a closing consonant tone the second vowel
        // (toàn, hoàng, khoét, suýt, toát). Without a coda the modern
        // spelling tones the first vowel — those fall through to the
        // rules below (hòa, khỏe, thúy). oi/ui/uo are ending diphthongs
        // handled by Rule 6. uê/uơ never reach here: ê/ơ are special
        // vowels caught by Rule 1.
        val second = word[positions[1]].lowercaseChar()
        if ((first == 'o' || first == 'u') && second in setOf('a', 'e', 'y') &&
            word.last().lowercaseChar() !in ALL_VOWELS) {
            return positions[1]
        }

        // Rule 6: ends with i / y / u / o
        if (last == 'i' || last == 'y' || last == 'u' || last == 'o') return positions[0]

        // Rule 7: default
        return positions[0]
    }

    // ── Smart mode: Vietnamese syllable-shape validation ──────────────────

    /** Toned vowel → base vowel (inverted TONE_MAP, both cases). */
    private val TONED_TO_BASE: Map<Char, Char> =
        TONE_MAP.flatMap { (_, vowelMap) -> vowelMap.entries }
            .flatMap { (base, toned) ->
                listOf(toned to base, toned.uppercaseChar() to base.uppercaseChar())
            }
            .toMap()

    /** Strip tone marks and lowercase so the shape check sees base vowels. */
    private fun stripTonesAndLower(s: String): String =
        buildString(s.length) { for (c in s) append(TONED_TO_BASE[c] ?: c) }.lowercase()

    // Longest-first order is load-bearing: the backtracking parse must try
    // the 3-char digraphs (ngh, iêu, …) before their shorter prefixes.
    private val ONSETS = listOf(
        "ngh", "qu", "gi", "ch", "kh", "ng", "nh", "ph", "th", "tr", "gh",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x",
    )
    private val NUCLEI = listOf(
        "iêu", "yêu", "oai", "oao", "oay", "uây", "ươi", "ươu", "uôi", "uya", "uyê",
        "ai", "ao", "au", "ay", "âu", "ây", "eo", "êu", "ia", "iê", "iu",
        "oa", "oă", "oe", "oi", "ôi", "ơi", "ua", "uâ", "uê", "ui", "uo", "uô", "uơ",
        "ưa", "ươ", "ưi", "ưu", "uy", "yê",
        "a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
    )
    private val CODAS = listOf("ch", "ng", "nh", "c", "m", "n", "p", "t")

    /**
     * True when [s] (tone-stripped, lowercase) is a concatenation of valid
     * Vietnamese syllables: onset? + nucleus + coda?. Backtracking over the
     * longest-first tables, so rare cluster splits still parse. Every
     * Vietnamese word — and every typing prefix of one — parses; English
     * words with invalid onsets/codas (cluster, good, pool) do not.
     */
    private fun isValidVietnameseSequence(s: String): Boolean {
        fun parse(i: Int): Boolean {
            if (i >= s.length) return true
            val onsetEnds = listOf(i) + ONSETS.filter { s.startsWith(it, i) }.map { i + it.length }
            for (oe in onsetEnds) {
                // A word may be just the onset "đ" (yes) — or "đ" plus anything
                // that follows it, like the texting contractions "đc" (được),
                // "đg" (đang), "đt" (điện thoại). Guarded to i == 0 so a
                // trailing consonant after a complete syllable ("good" → god)
                // stays a coda, not a second onset-only syllable. It only fires
                // when the resolved text starts with "đ" — raw input that began
                // with "dd", which English never does.
                if (i == 0 && (oe == s.length || s.startsWith("đ"))) return true
                for (nuc in NUCLEI) {
                    if (!s.startsWith(nuc, oe)) continue
                    val ni = oe + nuc.length
                    val codas = listOf(ni) + CODAS.filter { s.startsWith(it, ni) }.map { ni + it.length }
                    for (ce in codas) if (parse(ce)) return true
                }
            }
            return false
        }
        return parse(0)
    }
}
