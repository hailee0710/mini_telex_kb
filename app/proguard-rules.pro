# Mini Qwerty Keyboard ProGuard Rules
# Keep the InputMethodService entry point
-keep class com.miniqwerty.kb.MiniKeyboardIME { *; }
# Keep the JNI bridge: the native symbol names are derived from the class name
-keep class com.miniqwerty.kb.VikeyDriver { *; }
-keepclasseswithmembernames class * { native <methods>; }
