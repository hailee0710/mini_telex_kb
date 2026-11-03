// JNI face of Vikey's Engine/Driver (ported from Android/app/src/main/cpp/vikey_jni.cpp).
//
// Only the pieces the IME needs are exported; TouchModel (fat-finger autocorrect and
// suggestions) and SmartSwitchKey (per-app language) are not wired yet.
//
// Deliberate differences from upstream:
//  * keyFor() covers printable ASCII instead of a dozen punctuation keys, so the symbol
//    layers don't silently drop the word being composed.
//  * '[' and ']' stay unmapped. Upstream maps them to ơ/ư (Telex conventions), but this
//    keyboard has no ơ/ư key and uses both brackets as symbols — an unmapped character
//    resets the engine and passes through, which is what the symbol layer wants.
//  * configure() also takes teencode: upstream hard-codes vTeencode = 1 with no UI.
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include "Driver.h"

int vLanguage = 1, vSwitchKeyStatus = 0, vCheckSpelling = 1, vUseModernOrthography = 1, vRestoreIfWrongSpelling = 1,
    vFixRecommendBrowser = 1, vUseSmartSwitchKey = 0, vOtherLanguage = 0, vAutoSwitchByTyping = 1;

static bool keyFor(jchar c, Uint16& key, bool& shift) {
    static const Uint16 letters[26] = {KEY_A,KEY_B,KEY_C,KEY_D,KEY_E,KEY_F,KEY_G,KEY_H,KEY_I,KEY_J,KEY_K,KEY_L,KEY_M,KEY_N,KEY_O,KEY_P,KEY_Q,KEY_R,KEY_S,KEY_T,KEY_U,KEY_V,KEY_W,KEY_X,KEY_Y,KEY_Z};
    static const Uint16 digits[10] = {KEY_0,KEY_1,KEY_2,KEY_3,KEY_4,KEY_5,KEY_6,KEY_7,KEY_8,KEY_9};
    shift = false;
    if (c >= 'a' && c <= 'z') { key = letters[c - 'a']; return true; }
    if (c >= 'A' && c <= 'Z') { key = letters[c - 'A']; shift = true; return true; }
    if (c >= '0' && c <= '9') { key = digits[c - '0']; return true; }
    switch (c) {
        case ' ':  key = KEY_SPACE; return true;
        case '\n': key = KEY_RETURN; return true;
        case '\t': key = KEY_TAB; return true;
        case ',':  key = KEY_COMMA; return true;
        case '.':  key = KEY_DOT; return true;
        case ';':  key = KEY_SEMICOLON; return true;
        case ':':  key = KEY_SEMICOLON; shift = true; return true;
        case '/':  key = KEY_SLASH; return true;
        case '?':  key = KEY_SLASH; shift = true; return true;
        case '!':  key = KEY_1; shift = true; return true;
        case '@':  key = KEY_2; shift = true; return true;
        case '#':  key = KEY_3; shift = true; return true;
        case '$':  key = KEY_4; shift = true; return true;
        case '%':  key = KEY_5; shift = true; return true;
        case '^':  key = KEY_6; shift = true; return true;
        case '&':  key = KEY_7; shift = true; return true;
        case '*':  key = KEY_8; shift = true; return true;
        case '(':  key = KEY_9; shift = true; return true;
        case ')':  key = KEY_0; shift = true; return true;
        case '-':  key = KEY_MINUS; return true;
        case '_':  key = KEY_MINUS; shift = true; return true;
        case '=':  key = KEY_EQUALS; return true;
        case '+':  key = KEY_EQUALS; shift = true; return true;
        case '\\': key = KEY_BACK_SLASH; return true;
        case '|':  key = KEY_BACK_SLASH; shift = true; return true;
        case '`':  key = KEY_BACKQUOTE; return true;
        case '~':  key = KEY_BACKQUOTE; shift = true; return true;
        case '\'': key = KEY_QUOTE; return true;
        case '"':  key = KEY_QUOTE; shift = true; return true;
        // no '[' / ']' / '{' / '}' on purpose — see the header comment
    }
    return false;
}
static VikeyDriver* D(jlong h) { return reinterpret_cast<VikeyDriver*>(h); }
/// Edit result packed as a Java String: first char = deleteCount, second = flags (1 = language changed), then the text.
static jstring packEdit(JNIEnv* env, const VKEdit& e) {
    std::u16string s; s.push_back((char16_t)e.deleteCount); s.push_back((char16_t)(e.languageChanged ? 1 : 0)); s += e.text;
    return env->NewString(reinterpret_cast<const jchar*>(s.data()), (jsize)s.size());
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_miniqwerty_kb_VikeyDriver_create(JNIEnv*, jclass) {
    auto* d = new VikeyDriver(); d->pauseAfterManual = 5; return reinterpret_cast<jlong>(d);
}
JNIEXPORT void JNICALL Java_com_miniqwerty_kb_VikeyDriver_destroy(JNIEnv*, jclass, jlong h) { delete D(h); }
JNIEXPORT jint JNICALL Java_com_miniqwerty_kb_VikeyDriver_language(JNIEnv*, jclass, jlong) { return vLanguage; }
JNIEXPORT void JNICALL Java_com_miniqwerty_kb_VikeyDriver_setLanguage(JNIEnv*, jclass, jlong h, jint l) { D(h)->setLanguage(l); }
JNIEXPORT void JNICALL Java_com_miniqwerty_kb_VikeyDriver_configure(JNIEnv*, jclass, jlong h, jboolean autoSwitch, jdouble pause,
                                                                   jboolean spelling, jboolean restore, jboolean modern, jboolean teencode) {
    D(h)->autoSwitch = autoSwitch; vAutoSwitchByTyping = autoSwitch; D(h)->pauseAfterManual = pause;
    vCheckSpelling = spelling; vSetCheckSpelling(); vRestoreIfWrongSpelling = restore;
    vUseModernOrthography = modern; vTeencode = teencode;
}
JNIEXPORT jstring JNICALL Java_com_miniqwerty_kb_VikeyDriver_typeChar(JNIEnv* env, jclass, jlong h, jchar c, jdouble now) {
    Uint16 key; bool shift;
    if (!keyFor(c, key, shift)) { D(h)->resetContext(); VKEdit e; e.text.push_back((char16_t)c); return packEdit(env, e); }
    return packEdit(env, D(h)->press(key, shift, now));
}
JNIEXPORT jstring JNICALL Java_com_miniqwerty_kb_VikeyDriver_backspace(JNIEnv* env, jclass, jlong h, jdouble now) {
    return packEdit(env, D(h)->press(KEY_DELETE, false, now));
}
JNIEXPORT void JNICALL Java_com_miniqwerty_kb_VikeyDriver_manualSwitch(JNIEnv*, jclass, jlong h, jdouble now) { D(h)->manualSwitch(now); }
JNIEXPORT void JNICALL Java_com_miniqwerty_kb_VikeyDriver_resetContext(JNIEnv*, jclass, jlong h) { D(h)->resetContext(); }
}
