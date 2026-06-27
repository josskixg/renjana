# Renjana ProGuard Rules

# ── Keep ALL core container classes (loaded via reflection) ──
-keep class com.fesu.renjana.core.** { *; }

# ── Keep ALL hook classes INCLUDING inner/anonymous classes ──
-keep class com.fesu.renjana.hooks.** { *; }
-keepclassmembers class com.fesu.renjana.hooks.** { *; }
-keep,allowobfuscation class com.fesu.renjana.hooks.IntentHook$* { *; }
-keep,allowobfuscation class com.fesu.renjana.hooks.IntentHook** { *; }
-keep,allowobfuscation class com.fesu.renjana.hooks.CoreHooks$* { *; }
-keep,allowobfuscation class com.fesu.renjana.hooks.CoreHooks** { *; }

# ── Keep Xposed hook callback subclasses (anonymous classes extend XC_MethodHook) ──
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
-keep class * extends de.robv.android.xposed.XC_MethodReplacement { *; }
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook { *; }
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodReplacement { *; }

# ── Keep models ──
-keep class com.fesu.renjana.models.** { *; }

# ── Keep hook entry points ──
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class de.robv.android.xposed.** { *; }

# ── Pine hooking ──
# Keep all Pine framework classes (entry points loaded by native lib).
-keep class top.canyie.pine.** { *; }
-keep class top.canyie.pine.callback.** { *; }
# Pine ships native (JNI) methods; never strip them or the native bridge crashes.
-keepclasseswithmembernames class * {
    native <methods>;
}
# Pine MethodHook subclasses created via reflection — keep every override.
-keep class * extends top.canyie.pine.callback.MethodHook { *; }
-keep class * extends top.canyie.pine.callback.MethodReplacement { *; }
-keepclassmembers class * extends top.canyie.pine.callback.MethodHook { *; }
-keepclassmembers class * extends top.canyie.pine.callback.MethodReplacement { *; }
# Suppress R8 warnings about Pine internals it cannot resolve.
-dontwarn top.canyie.pine.**

# ── Keep reflection targets (WrapperActivity uses reflection) ──
-keepclassmembers class com.fesu.renjana.core.WrapperActivity { *; }
-keepclassmembers class com.fesu.renjana.core.VirtualClassLoader { *; }
-keepclassmembers class * {
    public <init>();
    public void onCreate(android.os.Bundle);
    public void onStart();
    public void onResume();
    public void onPause();
    public void onStop();
    public void onDestroy();
}

# ── Keep Room entities and DAOs ──
-keep class com.fesu.renjana.database.** { *; }

# ── Keep GMS virtualization ──
-keep class com.fesu.renjana.gms.** { *; }

# ── Keep virtualization layer (GuestInfoCache accessed via reflection by hooks) ──
-keep class com.fesu.renjana.virtual.** { *; }

# ── Compose UI ──
-dontwarn androidx.compose.**

# ── Don't optimize critical classes ──
-keep,allowobfuscation class com.fesu.renjana.core.VirtualClassLoader
-keep,allowobfuscation class com.fesu.renjana.core.WrapperActivity
-keep,allowobfuscation class com.fesu.renjana.hooks.IntentHook
-keep,allowobfuscation class com.fesu.renjana.hooks.CoreHooks

# ── APK parser ──
-keep class net.dongliu.apk.** { *; }

# ── Suppress R8 warnings about missing classes ──
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
