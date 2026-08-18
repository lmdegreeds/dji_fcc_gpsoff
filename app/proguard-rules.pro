# R8 rules for the release build.

# JNI_OnLoad binds every native method in one RegisterNatives() call against
# com.dji.fccgpsoff.DumlNative, matching by name AND signature. That call is
# all-or-nothing: if a single entry in the native table has no counterpart in the
# dex, RegisterNatives fails, JNI_OnLoad returns JNI_ERR, System.loadLibrary
# throws UnsatisfiedLinkError, and the class initializer dies — after which every
# use of the transport reports NoClassDefFoundError.
#
# -keepclasseswithmembernames only protects the NAMES of members that survive; it
# does not stop R8 removing them. Two native methods with no Kotlin caller
# (nativeRequest, nativeDussSend) were shrunk away on exactly that basis, which
# broke the release APK while debug — where R8 is off — worked fine.
#
# So keep the class whole. It is one small object; the bytes it costs are nothing
# against a release build that cannot talk to the aircraft at all.
-keep class com.dji.fccgpsoff.DumlNative { *; }

# Any future JNI surface gets the same protection by construction, so this class
# of failure cannot come back through a new native binding.
-keepclasseswithmembers class * {
    native <methods>;
}
