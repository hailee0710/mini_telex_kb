//
//  Driver.h
//  Vikey
//
//  Everything a platform keeps around the engine, without the platform: the English-mode word
//  buffer, auto switch both ways, the end-of-word fix after a doubled tone key, Backspace undo of
//  an auto-fix, and the pause after a manual switch. A keyboard sends keys in and applies the
//  returned edits. Used by the iOS keyboard and by the simulator (so the simulator tests it).
//  Mirrors App/Core/VikeyCore.mm, which does the same inside the macOS event tap.
//

#ifndef VikeyDriver_h
#define VikeyDriver_h

#include "Engine.h"
#include <string>

struct VKEdit {
    int deleteCount = 0;      // characters to delete before the cursor
    std::u16string text;      // then insert this
    bool languageChanged = false;
};

class VikeyDriver {
public:
    VikeyDriver();
    /** A key press (letters, digits, punctuation, KEY_SPACE, KEY_RETURN, KEY_DELETE). now: seconds, any clock. */
    VKEdit press(Uint16 key, bool shift, double now = 0);
    /** The user switched language by hand (VI/EN key). */
    void manualSwitch(double now = 0);
    /** Set the language without counting as a manual switch (start of a field). */
    void setLanguage(int language);
    int language() const { return vLanguage; }
    /** The cursor moved or the text field changed: nothing to look back on, the next word starts fresh. */
    void resetContext();
    /** Pause auto switch for this many seconds after a manual switch (0 = off). */
    double pauseAfterManual = 5;
    /** The user's auto switch setting (the pause turns the engine's switch off temporarily). */
    bool autoSwitch = true;
    int autoSwitches = 0, undos = 0;

private:
    vKeyHookState* H;
    Uint32 enKeys[MAX_BUFF]; int enCount = 0; bool enValid = true; bool enAfterDouble = false;
    Uint32 prevKeys[2][MAX_BUFF]; int prevCounts[2] = {0, 0}; int prevN = 0;
    bool prevRestored = false;   // prevKeys[0] was put back to English by auto switch (D35)
    bool undoActive = false; int undoRemove = 0; int undoLanguage = 1; std::u16string undoText;
    Uint32 undoKeys[MAX_BUFF]; int undoKeyCount = 0;   // keys of the original word (English can go on typing it)
    Uint32 undoneKeys[MAX_BUFF]; int undoneCount = 0;  // the word Backspace put back: finished unchanged, it stays
    double pauseUntil = 0; bool paused = false;
    void engineOutput(VKEdit& e, Uint16 key, bool shift);
    void rememberFix(int corrected, Uint16 key, bool shift);
    void switchTo(int lang);
    void updatePause(double now);
};

#endif
