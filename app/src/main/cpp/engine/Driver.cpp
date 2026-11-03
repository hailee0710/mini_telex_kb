//
//  Driver.cpp
//  Vikey
//

#include "Driver.h"
#include <cstring>

static Uint16 letterKeys[26] = {KEY_A,KEY_B,KEY_C,KEY_D,KEY_E,KEY_F,KEY_G,KEY_H,KEY_I,KEY_J,KEY_K,KEY_L,KEY_M,KEY_N,
                                KEY_O,KEY_P,KEY_Q,KEY_R,KEY_S,KEY_T,KEY_U,KEY_V,KEY_W,KEY_X,KEY_Y,KEY_Z};
static bool isLetterKey(Uint16 key) { for (Uint16 k : letterKeys) if (k == key) return true; return false; }
static bool isWhitespace(Uint16 key) { return key == KEY_SPACE || key == KEY_RETURN || key == KEY_ENTER || key == KEY_TAB; }
static bool isWordStart(Uint16 key, bool shift) { return isWhitespace(key) || (key == KEY_9 && shift) || key == KEY_LEFT_BRACKET || key == KEY_QUOTE; }
static char16_t charOf(Uint32 d) { return (d & CHAR_CODE_MASK) ? (char16_t)(d & 0xFFFF) : (char16_t)keyCodeToCharacter(d); }
static char16_t typedChar(Uint16 key, bool shift) {
    if (key == KEY_RETURN || key == KEY_ENTER) return u'\n';
    return keyCodeToCharacter(key | (shift ? CAPS_MASK : 0));
}

VikeyDriver::VikeyDriver() { H = (vKeyHookState*)vKeyInit(); }

void VikeyDriver::engineOutput(VKEdit& e, Uint16 key, bool shift) {
    e.deleteCount = H->backspaceCount;
    for (int i = H->newCharCount - 1; i >= 0; i--) e.text.push_back(charOf(H->charData[i]));
    if (H->code == vRestore || H->code == vRestoreAndStartNewSession) { char16_t ch = typedChar(key, shift); if (ch) e.text.push_back(ch); }
    if (H->code == vRestoreAndStartNewSession) startNewSession();
}

void VikeyDriver::rememberFix(int corrected, Uint16 key, bool shift) {
    undoActive = false;
    char16_t brk = typedChar(key, shift);
    if (!brk || brk == u'\n' || vAutoSwitchUndoCount <= 0 || corrected <= 0) return;
    //only a fix of the last word is undone; one that also fixed earlier words stays (D20)
    for (int x = 0; x < vAutoSwitchUndoCount; x++)
        if ((vAutoSwitchUndoData[x] & 0xFFFF) == KEY_SPACE && !(vAutoSwitchUndoData[x] & CHAR_CODE_MASK)) return;
    undoText.clear();
    for (int x = 0; x < vAutoSwitchUndoCount; x++) undoText.push_back(charOf(vAutoSwitchUndoData[x]));
    //like the Mac: Backspace takes the break key too, the cursor ends up at the end of the original word
    undoKeyCount = 0;
    for (int x = 0; x < vAutoSwitchUndoCount && x < MAX_BUFF; x++) {
        if (vAutoSwitchUndoData[x] & CHAR_CODE_MASK) { undoKeyCount = 0; break; }
        undoKeys[undoKeyCount++] = vAutoSwitchUndoData[x];
    }
    undoRemove = corrected + 1;
    undoLanguage = vLanguage;
    undoActive = true;
}

void VikeyDriver::switchTo(int lang) { vLanguage = lang; startNewSession(); vAutoSwitchForget(); autoSwitches++; }

void VikeyDriver::updatePause(double now) {
    if (paused && now >= pauseUntil) {
        paused = false;
        vAutoSwitchByTyping = autoSwitch ? 1 : 0;
        enCount = 0; enValid = false;      //the word being typed now wasn't followed from its start
    }
    if (!paused) vAutoSwitchByTyping = autoSwitch ? 1 : 0;
}

void VikeyDriver::manualSwitch(double now) {
    undoActive = false; undoneCount = 0;
    enCount = 0; enValid = true; prevN = 0; enAfterDouble = false; vAutoSwitchForget();
    vLanguage = vLanguage ? 0 : 1;
    startNewSession();
    if (pauseAfterManual > 0) { paused = true; pauseUntil = now + pauseAfterManual; vAutoSwitchByTyping = 0; }
}

void VikeyDriver::setLanguage(int language) {
    if (vLanguage == language) return;
    vLanguage = language;
    resetContext();
}

void VikeyDriver::resetContext() {
    undoActive = false; undoneCount = 0;
    enCount = 0; enValid = true; prevN = 0; enAfterDouble = false;
    startNewSession();
    vAutoSwitchForget();
}

VKEdit VikeyDriver::press(Uint16 key, bool shift, double now) {
    VKEdit e;
    updatePause(now);
    //Backspace right after an auto-fix puts the typed keys and the language back
    if (key == KEY_DELETE && undoActive) {
        undoActive = false;
        e.deleteCount = undoRemove;
        e.text = undoText;
        if (vLanguage != undoLanguage) { vLanguage = undoLanguage; e.languageChanged = true; }
        startNewSession();
        vAutoSwitchForget();
        enCount = 0; enValid = false; prevN = 0; enAfterDouble = false; undoneCount = 0;
        //back in English with the typed keys on screen: they are the current word again ("vaay" + "j" -> "vậy")
        if (vLanguage == 0 && undoKeyCount > 0) {
            memcpy(enKeys, undoKeys, undoKeyCount * sizeof(Uint32)); enCount = undoKeyCount; enValid = true;
            memcpy(undoneKeys, undoKeys, undoKeyCount * sizeof(Uint32)); undoneCount = undoKeyCount;
        }
        undos++;
        return e;
    }
    undoActive = false;
    if (vLanguage == 1) {
        vAutoSwitchRequest = -1;
        vKeyHandleEvent(vKeyEvent::Keyboard, vKeyEventState::KeyDown, key, shift ? 1 : 0, false);
        if (H->code == vDoNothing) {
            if (key == KEY_DELETE) e.deleteCount = 1;
            else { char16_t ch = typedChar(key, shift); if (ch) e.text.push_back(ch); }
        } else {
            bool atBreak = vAutoSwitchRequest == 0 && !isLetterKey(key);
            int corrected = H->newCharCount;
            engineOutput(e, key, shift);
            if (vAutoSwitchRequest == 0) {
                vAutoSwitchRequest = -1;
                if (atBreak) rememberFix(corrected, key, shift);
                switchTo(0);
                e.languageChanged = true;
                enCount = 0; enValid = atBreak && isWhitespace(key); prevN = 0;
                //D35: the word just put back to English is the previous word for look-back
                if (key == KEY_SPACE && vAutoSwitchRestoredCount > 0) {
                    memcpy(prevKeys[0], vAutoSwitchRestoredKeys, vAutoSwitchRestoredCount * sizeof(Uint32));
                    prevCounts[0] = vAutoSwitchRestoredCount; prevN = 1; prevRestored = true;
                }
                enAfterDouble = !atBreak;
            }
        }
        return e;
    }
    // English mode
    if (isLetterKey(key)) {
        if (enCount < MAX_BUFF - 2) enKeys[enCount++] = key | (shift ? CAPS_MASK : 0); else enValid = false;
        e.text.push_back(typedChar(key, shift));
        return e;
    }
    if (key == KEY_DELETE) {
        if (enCount == 0) { prevN = 0; enAfterDouble = false; }
        if (enCount > 0) enCount--; else enValid = false;
        e.deleteCount = 1;
        return e;
    }
    bool digit = false;
    for (Uint16 d : {KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9}) if (key == d) digit = true;
    if (digit && !shift) {   // digits after a space start a word of their own ("5trawm" -> "5trăm", D33); glued to letters, a code
        enValid = enCount == 0 && enValid; enCount = 0; prevN = 0; enAfterDouble = false;
        e.text.push_back(typedChar(key, shift));
        return e;
    }
    bool wordEnd = key == KEY_SPACE || key == KEY_COMMA || key == KEY_DOT || key == KEY_SEMICOLON || key == KEY_RETURN || key == KEY_ENTER ||
                   (shift && (key == KEY_SLASH || key == KEY_1 || key == KEY_QUOTE || key == KEY_0));
    int count = enCount; bool valid = enValid;
    bool afterDouble = enAfterDouble; enAfterDouble = false;
    if (afterDouble && wordEnd && vAutoSwitchFixDoubledKey(enKeys, count)) {
        enCount = 0; enValid = isWhitespace(key); prevN = 0;
        int corrected = H->newCharCount;
        engineOutput(e, key, shift);
        rememberFix(corrected, key, shift);
        return e;
    }
    enCount = 0; enValid = isWordStart(key, shift);
    const Uint32* prev[2] = {prevKeys[0], prevKeys[1]};
    vAutoSwitchPrevRestored = prevRestored && prevN >= 1;
    bool same = undoneCount > 0 && count == undoneCount && !memcmp(enKeys, undoneKeys, count * sizeof(Uint32));
    if (wordEnd) undoneCount = 0;
    bool converted = wordEnd && valid && count >= 2 && !same && vAutoSwitchCheckEnglishWord(enKeys, count, prev, prevCounts, prevN);
    if (!converted && key == KEY_SPACE && valid && count >= 1) {
        prevRestored = false;
        memcpy(prevKeys[1], prevKeys[0], sizeof(prevKeys[0])); prevCounts[1] = prevCounts[0];
        memcpy(prevKeys[0], enKeys, count * sizeof(Uint32)); prevCounts[0] = count; if (prevN < 2) prevN++;
    } else prevN = 0;
    if (converted) {
        int corrected = H->newCharCount;
        engineOutput(e, key, shift);
        rememberFix(corrected, key, shift);
        switchTo(1);
        e.languageChanged = true;
        return e;
    }
    char16_t ch = typedChar(key, shift); if (ch) e.text.push_back(ch);
    return e;
}
