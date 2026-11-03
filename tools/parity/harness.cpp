// Parity harness: drives the Vikey Engine/Driver the way an Android IME does, so its
// output can be diffed against mini_qwerty_kb's TelexProcessor (see Dump.kt).
//
// Reads one raw Telex sequence per line on stdin, simulates typing it followed by a
// space, and prints "<input>\t<field after the space>".
//
// Options:
//   --modern=0|1      vUseModernOrthography   (1 → hoà/thuý, 0 → hòa/thúy)
//   --teencode=0|1    vTeencode               (Vikey default is 1, Android has no UI for it)
//   --spelling=0|1    vCheckSpelling
//   --restore=0|1     vRestoreIfWrongSpelling
//   --autoswitch=0|1  vAutoSwitchByTyping
//   --style=0|1|2     vTelexStyle
//   --jni-keys        emulate the *current* vikey_jni.cpp keyFor(): unmapped characters
//                     reset the word instead of reaching the engine (brackets → ơ/ư missing)
//   --trace           print the text after every keystroke to stderr
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <string>
#include <iostream>
#include "Engine.h"
#include "Driver.h"

// Defined in vikey_jni.cpp on Android; the harness owns them here.
int vLanguage = 1, vSwitchKeyStatus = 0, vCheckSpelling = 1, vUseModernOrthography = 1,
    vRestoreIfWrongSpelling = 1, vFixRecommendBrowser = 1, vUseSmartSwitchKey = 0,
    vOtherLanguage = 0, vAutoSwitchByTyping = 1;

static bool jniKeys = false;

// The exact character set vikey_jni.cpp maps today; everything else calls resetContext().
static bool jniMapped(char32_t c) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
    return c != 0 && std::strchr(" \n,.;:?/!\"'()-", (int)c) != nullptr;
}

static bool keyFor(char32_t c, Uint16& key, bool& shift) {
    if (jniKeys && !jniMapped(c)) return false;
    static const Uint16 letters[26] = {KEY_A,KEY_B,KEY_C,KEY_D,KEY_E,KEY_F,KEY_G,KEY_H,KEY_I,KEY_J,KEY_K,KEY_L,KEY_M,
                                       KEY_N,KEY_O,KEY_P,KEY_Q,KEY_R,KEY_S,KEY_T,KEY_U,KEY_V,KEY_W,KEY_X,KEY_Y,KEY_Z};
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
        case '\'': key = KEY_QUOTE; return true;
        case '"':  key = KEY_QUOTE; shift = true; return true;
        case '(':  key = KEY_9; shift = true; return true;
        case ')':  key = KEY_0; shift = true; return true;
        case '-':  key = KEY_MINUS; return true;
        case '_':  key = KEY_MINUS; shift = true; return true;
        case '=':  key = KEY_EQUALS; return true;
        case '+':  key = KEY_EQUALS; shift = true; return true;
        case '[':  key = KEY_LEFT_BRACKET; return true;      // ơ
        case '{':  key = KEY_LEFT_BRACKET; shift = true; return true;
        case ']':  key = KEY_RIGHT_BRACKET; return true;     // ư
        case '}':  key = KEY_RIGHT_BRACKET; shift = true; return true;
        case '\\': key = KEY_BACK_SLASH; return true;
        case '|':  key = KEY_BACK_SLASH; shift = true; return true;
        case '`':  key = KEY_BACKQUOTE; return true;
        case '~':  key = KEY_BACKQUOTE; shift = true; return true;
        case '@':  key = KEY_2; shift = true; return true;
        case '#':  key = KEY_3; shift = true; return true;
        case '$':  key = KEY_4; shift = true; return true;
        case '%':  key = KEY_5; shift = true; return true;
        case '^':  key = KEY_6; shift = true; return true;
        case '&':  key = KEY_7; shift = true; return true;
        case '*':  key = KEY_8; shift = true; return true;
    }
    return false;
}

static std::string utf8(const std::u16string& s) {
    std::string out;
    for (char16_t c : s) {
        unsigned cp = c;
        if (cp < 0x80) out.push_back((char)cp);
        else if (cp < 0x800) { out.push_back((char)(0xC0 | (cp >> 6))); out.push_back((char)(0x80 | (cp & 0x3F))); }
        else { out.push_back((char)(0xE0 | (cp >> 12))); out.push_back((char)(0x80 | ((cp >> 6) & 0x3F))); out.push_back((char)(0x80 | (cp & 0x3F))); }
    }
    return out;
}

static std::u32string decode(const std::string& s) {
    std::u32string out;
    for (size_t i = 0; i < s.size();) {
        unsigned char c = (unsigned char)s[i];
        unsigned cp; int n;
        if (c < 0x80) { cp = c; n = 0; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; n = 1; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; n = 2; }
        else { cp = c & 0x07; n = 3; }
        for (int k = 1; k <= n && i + k < s.size(); k++) cp = (cp << 6) | ((unsigned char)s[i + k] & 0x3F);
        out.push_back((char32_t)cp);
        i += n + 1;
    }
    return out;
}

static std::u16string field;
static void applyEdit(const VKEdit& e) {
    if (e.deleteCount > 0) {
        size_t d = std::min<size_t>((size_t)e.deleteCount, field.size());
        field.resize(field.size() - d);
    }
    field += e.text;
}
static void typeChar(VikeyDriver& d, char32_t c, double now = 0) {
    Uint16 key; bool shift;
    if (!keyFor(c, key, shift)) { d.resetContext(); field.push_back((char16_t)c); return; }
    applyEdit(d.press(key, shift, now));
}

int main(int argc, char** argv) {
    bool trace = false;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        int v;
        auto val = [&](const char* name) { return a.rfind(name, 0) == 0 ? (v = atoi(a.c_str() + a.find('=') + 1), true) : false; };
        if (a == "--trace") { trace = true; continue; }
        if (a == "--jni-keys") { jniKeys = true; continue; }
        if (val("--modern="))      { vUseModernOrthography = v; continue; }
        if (val("--teencode="))    { vTeencode = v; continue; }
        if (val("--spelling="))    { vCheckSpelling = v; vSetCheckSpelling(); continue; }
        if (val("--restore="))     { vRestoreIfWrongSpelling = v; continue; }
        if (val("--autoswitch="))  { vAutoSwitchByTyping = v; continue; }
        if (val("--style="))       { vTelexStyle = v; continue; }
    }

    VikeyDriver driver;
    driver.autoSwitch = vAutoSwitchByTyping != 0;
    driver.pauseAfterManual = 5;
    driver.setLanguage(vLanguage);

    std::string line;
    while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        field.clear();
        driver.resetContext();
        driver.setLanguage(1);

        for (char32_t c : decode(line)) {
            typeChar(driver, c);
            if (trace) std::fprintf(stderr, "  %s -> [%s]\n", line.c_str(), utf8(field).c_str());
        }
        typeChar(driver, U' ');
        if (!field.empty() && field.back() == u' ') field.pop_back();
        std::printf("%s\t%s\n", line.c_str(), utf8(field).c_str());
        std::fflush(stdout);
    }
    return 0;
}
