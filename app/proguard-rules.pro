# --- DFTB release rules -----------------------------------------------------------------------
# Keep JNI entry points: the native library resolves them by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class ca.devdcs.backup.core.nativebridge.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ca.devdcs.backup.**$$serializer { *; }
-keepclassmembers class ca.devdcs.backup.** { *** Companion; }
-keepclasseswithmembers class ca.devdcs.backup.** { kotlinx.serialization.KSerializer serializer(...); }

# libsu / Shizuku use reflection and AIDL stubs.
-keep class com.topjohnwu.superuser.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# The Shizuku server reflectively loads the user-service class from this APK's dex and instantiates
# it with its no-arg constructor; keep the class and that constructor.
-keep class ca.devdcs.backup.core.exec.shizuku.ShizukuUserServiceImpl { <init>(); }

# App-package AIDL stubs (IShizukuUserService / IShellCallback and their Stub/Proxy inner classes)
# are marshalled by name across the binder; keep them intact so R8 cannot strip or rename them.
-keep class ca.devdcs.backup.IShizukuUserService { *; }
-keep class ca.devdcs.backup.IShizukuUserService$* { *; }
-keep class ca.devdcs.backup.IShellCallback { *; }
-keep class ca.devdcs.backup.IShellCallback$* { *; }

# sshj / BouncyCastle / smbj load providers and algorithms reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Room entities / DAOs are kept by the Room processor annotations; nothing extra needed.

# smbj -> mbassador optionally supports javax.el expression filters; the EL API is not on Android.
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.dispatch.el.**

# zstd-jni: native code reads Java fields by name.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**
