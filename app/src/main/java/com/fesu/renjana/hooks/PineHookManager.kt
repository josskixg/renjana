package com.fesu.renjana.hooks

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.telephony.TelephonyManager
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.models.Instance
import com.fesu.renjana.models.InstanceConfig
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.virtual.GuestInfoCache
import dalvik.system.DexClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import top.canyie.pine.PineConfig
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Pine hook manager for non-root mode.
 *
 * Pine (top.canyie.pine:core) is bundled as an `implementation` dependency, so we use
 * its API directly (no reflection). The `top.canyie.pine:xposed` artifact provides an
 * Xposed compatibility bridge — [XposedBridge.hookMethod] and [XposedHelpers] route
 * through Pine's native ART hooks transparently. This lets [CoreHooks]' `XC_MethodHook`
 * callbacks be installed with zero adaptation.
 *
 * Lifecycle:
 *  1. [initialize] — called once at app startup; configures PineConfig, installs
 *     system-wide hooks (Build field spoof).
 *  2. [installGuestHooks] — called per guest instance launch; wires all [CoreHooks]
 *     factories to their target methods and caches guest PackageInfo/Resources.
 *  3. [uninstallGuestHooks] — called when an instance is stopped.
 */
object PineHookManager {

    private const val TAG = "PineHookManager"
    private const val CONTAINER_PACKAGE = "com.fesu.renjana"

    private val hookedPackages = mutableSetOf<Pair<String, String>>() // (instanceId, packageName)

    @Volatile
    private var initialized = false

    @Volatile
    private var pineAvailable = false

    /**
     * L4: counts how many [safeHook] calls succeeded during the current
     * [installGuestHooks] invocation. Reset to 0 at the start of each install
     * and checked at the end so we never report success with zero hooks live.
     * Accessed only under [hookedPackages] lock, so no extra synchronization.
     */
    private var hooksInstalled = 0

    /**
     * Initialize Pine. Safe to call multiple times.
     *
     * Pine has no explicit `init()` — the first [Pine.hook] call triggers
     * `ensureInitialized()` internally. We just set [PineConfig] flags here.
     */
    fun initialize(): Boolean {
        if (initialized) {
            return pineAvailable
        }

        try {
            // Pine is bundled — verify the native library loaded by checking the class exists.
            pineAvailable = try {
                val pineClass = Class.forName("top.canyie.pine.Pine")
                try {
                    pineClass.getMethod("is64Bit").invoke(null)
                } catch (e: UnsatisfiedLinkError) {
                    RenjanaLog.w(TAG, "Pine native lib unavailable (x86?): ${e.message}")
                    pineAvailable = false
                    return false
                }
                true
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Pine library not available: ${e.message}")
                false
            }

            if (!pineAvailable) {
                initialized = true
                return false
            }

            RenjanaLog.i(TAG, "Initializing Pine hook manager (non-root mode)")

            // Configure Pine. debug = verbose logging; debuggable = allow attaching JDWP.
            PineConfig.debug = false
            PineConfig.debuggable = false

            installSystemHooks()
            initialized = true
            RenjanaLog.i(TAG, "Pine hook manager initialized successfully")
            return true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Pine initialization failed: ${e.message}")
            initialized = true
            return false
        }
    }

    fun isAvailable(): Boolean = pineAvailable

    /**
     * Install all guest-app hooks for a specific instance.
     *
     * @param packageName  Guest app package name (e.g. "com.example.app")
     * @param classLoader  The guest app's ClassLoader (VirtualClassLoader)
     * @param instanceId   Unique instance identifier
     * @param dataPath     Virtual data directory for this instance
     * @param apkPath      Optional APK path for [GuestInfoCache] parsing. If null,
     *                     the manager tries to derive it from [dataPath] or the
     *                     [classLoader]. When it cannot be resolved, guest info
     *                     caching is skipped (hooks still work, just without cached
     *                     PackageInfo/Resources).
     * @return true if hooks were installed successfully.
     */
    fun installGuestHooks(
        packageName: String,
        classLoader: ClassLoader,
        instanceId: String,
        dataPath: String,
        apkPath: String? = null,
        instance: Instance? = null
    ): Boolean {
        if (!pineAvailable) {
            RenjanaLog.w(TAG, "Pine not available, cannot install guest hooks")
            return false
        }

        CoreHooks.classLoaderToInstanceId[classLoader] = instanceId

        synchronized(hookedPackages) {
            if (hookedPackages.contains(instanceId to packageName)) {
                // Still register the new ClassLoader — StubActivity creates a different one than InstanceLauncher
                CoreHooks.classLoaderToInstanceId[classLoader] = instanceId
                RenjanaLog.d(TAG, "Package $packageName already hooked; registered new classloader for instance $instanceId")
                return true
            }

            RenjanaLog.i(TAG, "Installing Pine guest hooks for: $packageName (instance=$instanceId)")

            try {
                // ── Register package state ──
                val appDataPath = "$dataPath/$packageName"
                java.io.File(appDataPath).apply {
                    listOf("files", "databases", "shared_prefs", "cache", "code_cache", "dex_opt")
                        .forEach { java.io.File(this, it).mkdirs() }
                }
                CoreHooks.registerPackage(packageName, appDataPath, instanceId)
                // H5: the guest ClassLoader is the PRIMARY instance lookup key.
                // ThreadLocal (below) is only a fast-path hint that is unset on
                // binder threads where most hooks actually fire.
                CoreHooks.registerClassLoader(classLoader, instanceId)
                CoreHooks.currentInstanceId.set(instanceId)

                // ── Register per-instance config for fingerprint hooks ──
                if (instance != null) {
                    CoreHooks.registerInstanceConfig(instanceId, instance.config)
                }

                // ── Initialize DeviceFingerprint (caches real device IDs once) ──
                try {
                    DeviceFingerprint.initialize(RenjanaApplication.get())
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "DeviceFingerprint.initialize failed (non-fatal): ${e.message}")
                }

                // ── Anti-detection modules (guarded by per-instance config) ──
                val config = instance?.config
                if (config == null || config.enableAntiDetection) {
                    AntiDetection.initialize(instanceId, dataPath)
                    FridaDetectionEvasion.initialize(instanceId)
                }
                if (config != null && config.enableSafetyNetBypass) {
                    SafetyNetBypass.initialize(instanceId)
                    PlayIntegrityBypass.initialize(instanceId, packageName)
                }

                // L4: reset the success counter for this install attempt.
                hooksInstalled = 0

                // ── Cache guest PackageInfo / ApplicationInfo / Resources ──
                val resolvedApkPath = apkPath ?: resolveApkPath(dataPath, classLoader)
                if (resolvedApkPath != null) {
                    GuestInfoCache.cache(instanceId, packageName, resolvedApkPath)
                    // ── Populate signature cache so CoreHooks' spoof conditional activates ──
                    SignatureSpoof.storeOriginalSignatures(packageName, resolvedApkPath)
                } else {
                    RenjanaLog.w(TAG, "Could not resolve APK path for $packageName; GuestInfoCache skipped")
                }

                // ── Install all hooks via XposedBridge (bridged to Pine by pine:xposed) ──
                hookActivityThread()
                hookActivityPerformCreate()
                hookPackageManager(classLoader)
                hookGoogleSignIn(classLoader)
                hookSharedPreferences()
                hookFileConstructors()
                hookFileExists()
                hookSystemGetProperty()
                hookSystemPropertiesGet()
                hookGetStackTrace()
                hookClassForName()
                hookProcMaps()

                // ── Device fingerprint hooks (guarded by per-instance config) ──
                if (instance != null && instance.config.enableFingerprint) {
                    hookFingerprintHooks()
                }

                // ── Per-instance notification channel routing ──
                hookNotifications()

                // M1: also install the complete 9-hook GMS implementation from
                // GoogleSignInHook. CoreHooks' 2-hook GMS version (hookGoogleSignIn
                // above) stays as a fallback. GoogleSignInHook auto-initializes its
                // virtualizer from the singleton; if that is not ready it returns
                // false and we simply continue with the CoreHooks fallback.
                if (GoogleSignInHook.installHooks(classLoader, instanceId)) {
                    hooksInstalled++
                }

                // L4: never report success when zero hooks are actually live.
                if (hooksInstalled == 0) {
                    RenjanaLog.w(TAG, "Zero hooks installed despite Pine available")
                    return false
                }

                hookedPackages.add(instanceId to packageName)
                RenjanaLog.i(TAG, "Pine guest hooks installed for $packageName")
                return hooksInstalled > 0
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to install Pine guest hooks for $packageName: ${e.message}")
                return false
            }
        }
    }

    fun uninstallGuestHooks(packageName: String, instanceId: String) {
        synchronized(hookedPackages) {
            if (!hookedPackages.contains(instanceId to packageName)) return

            RenjanaLog.i(TAG, "Uninstalling Pine guest hooks for: $packageName")

            try {
                CoreHooks.unregisterPackage(packageName)
                CoreHooks.currentInstanceId.remove()
                GuestInfoCache.clear(instanceId, packageName)
                AntiDetection.cleanup(instanceId)
                DeviceFingerprint.cleanup(instanceId)
                CoreHooks.instanceConfigs.remove(instanceId)
                hookedPackages.remove(instanceId to packageName)
                RenjanaLog.i(TAG, "Pine guest hooks uninstalled for $packageName")
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to uninstall Pine guest hooks: ${e.message}")
            }
        }
    }

    // ── Hook installation helpers ──────────────────────────────────────────

    /**
     * Install an [XC_MethodHook] callback on [method] via [XposedBridge.hookMethod].
     * Wraps the call in try/catch so one failing hook doesn't abort the rest.
     *
     * L4: increments [hooksInstalled] on success so [installGuestHooks] can detect
     * the "Pine available but zero hooks live" failure mode.
     *
     * @return `true` if the hook was installed, `false` if [method] was null or the
     *         install threw.
     */
    private fun safeHook(method: Member?, hook: XC_MethodHook, label: String): Boolean {
        if (method == null) {
            RenjanaLog.w(TAG, "Target method not found, skipping hook: $label")
            return false
        }
        return try {
            XposedBridge.hookMethod(method, hook)
            hooksInstalled++
            RenjanaLog.d(TAG, "Hook installed: $label")
            true
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook $label: ${e.message}")
            false
        }
    }

    // ── System Hooks ───────────────────────────────────────────────────────

    private fun installSystemHooks() {
        RenjanaLog.d(TAG, "Installing Pine system hooks")
        try {
            hookBuildField("SERIAL", "unknown")
            hookBuildField("TAGS", "release-keys")
            RenjanaLog.i(TAG, "Pine system hooks installed successfully")
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to install Pine system hooks: ${e.message}")
        }
    }

    private fun hookBuildField(fieldName: String, spoofedValue: String) {
        try {
            val buildClass = Class.forName("android.os.Build")
            val field = buildClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(null, spoofedValue)
            RenjanaLog.d(TAG, "Spoofed Build.$fieldName = $spoofedValue via Pine")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to spoof Build.$fieldName: ${e.message}")
        }
    }

    // ── Guest App Hooks ────────────────────────────────────────────────────

    /**
     * Hook ActivityThread.handleLaunchActivity to intercept Activity creation.
     * Target: android.app.ActivityThread.handleLaunchActivity(IBinder, List)
     */
    private fun hookActivityThread() {
        val method = findActivityThreadMethod("handleLaunchActivity")
        safeHook(method, CoreHooks.createActivityThreadHook(), "ActivityThread.handleLaunchActivity")
    }

    /**
     * Hook Activity.performCreate to inject virtual context before onCreate.
     * Target: android.app.Activity.performCreate(Bundle)
     */
    private fun hookActivityPerformCreate() {
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "performCreate", Bundle::class.java
            )
            safeHook(method, CoreHooks.createActivityPerformCreateHook(), "Activity.performCreate")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Activity.performCreate not found: ${e.message}")
        }
    }

    /**
     * Hook PackageManager methods for signature / package info spoofing.
     * Targets:
     *  - ApplicationPackageManager.getPackageInfo(String, int)          — all API levels
     *  - ApplicationPackageManager.getPackageInfo(VersionedPackage, int) — API 26+
     *  - ApplicationPackageManager.getPackageInfo(String, PackageInfoFlags) — API 33+
     *  - ApplicationPackageManager.getInstalledPackages(int)
     */
    private fun hookPackageManager(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        try {
            val appPmClass = Class.forName("android.app.ApplicationPackageManager")

            // ── getPackageInfo(String, int) — primary overload, all API levels ──
            val getPackageInfo = appPmClass.getDeclaredMethod(
                "getPackageInfo", String::class.java, Int::class.javaPrimitiveType
            )
            safeHook(getPackageInfo, CoreHooks.createGetPackageInfoHook(),
                "ApplicationPackageManager.getPackageInfo")

            // ── getPackageInfo(VersionedPackage, int) — API 26+ ──
            try {
                val versionedPackageClass = Class.forName("android.content.pm.VersionedPackage")
                val getPackageInfoVersioned = appPmClass.getDeclaredMethod(
                    "getPackageInfo", versionedPackageClass, Int::class.javaPrimitiveType
                )
                safeHook(getPackageInfoVersioned, CoreHooks.createGetPackageInfoVersionedHook(),
                    "ApplicationPackageManager.getPackageInfo(VersionedPackage)")
            } catch (e: ClassNotFoundException) {
                RenjanaLog.d(TAG, "VersionedPackage not available (API < 26), skipping overload hook")
            } catch (e: NoSuchMethodException) {
                RenjanaLog.d(TAG, "getPackageInfo(VersionedPackage, int) not found: ${e.message}")
            }

            // ── getPackageInfo(String, PackageInfoFlags) — API 33+ ──
            try {
                val packageInfoFlagsClass = Class.forName("android.content.pm.PackageManager\$PackageInfoFlags")
                val getPackageInfoFlags = appPmClass.getDeclaredMethod(
                    "getPackageInfo", String::class.java, packageInfoFlagsClass
                )
                safeHook(getPackageInfoFlags, CoreHooks.createGetPackageInfoFlagsHook(),
                    "ApplicationPackageManager.getPackageInfo(PackageInfoFlags)")
            } catch (e: ClassNotFoundException) {
                RenjanaLog.d(TAG, "PackageInfoFlags not available (API < 33), skipping overload hook")
            } catch (e: NoSuchMethodException) {
                RenjanaLog.d(TAG, "getPackageInfo(String, PackageInfoFlags) not found: ${e.message}")
            }

            val getInstalledPackages = appPmClass.getDeclaredMethod(
                "getInstalledPackages", Int::class.javaPrimitiveType
            )
            safeHook(getInstalledPackages, CoreHooks.createGetInstalledPackagesHook(),
                "ApplicationPackageManager.getInstalledPackages")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook PackageManager: ${e.message}")
        }
    }

    /**
     * Hook Google Sign-In methods if GMS classes are available in the guest classLoader.
     * Targets:
     *  - GoogleSignIn.getSignedInAccountFromIntent(Intent)
     *  - GoogleSignInAccount constructor
     */
    private fun hookGoogleSignIn(classLoader: ClassLoader) {
        try {
            val googleSignInClass = classLoader.loadClass(
                "com.google.android.gms.auth.api.signin.GoogleSignIn"
            )
            val getSignedInAccountFromIntent = googleSignInClass.getDeclaredMethod(
                "getSignedInAccountFromIntent", Intent::class.java
            )
            safeHook(getSignedInAccountFromIntent, CoreHooks.createGoogleSignInHook(),
                "GoogleSignIn.getSignedInAccountFromIntent")
        } catch (e: ClassNotFoundException) {
            RenjanaLog.d(TAG, "GoogleSignIn class not in guest; skipping GMS sign-in hook")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook GoogleSignIn: ${e.message}")
        }

        try {
            val accountClass = classLoader.loadClass(
                "com.google.android.gms.auth.api.signin.GoogleSignInAccount"
            )
            val constructor = accountClass.getDeclaredConstructor(
                String::class.java, String::class.java, String::class.java,
                android.net.Uri::class.java, String::class.java, String::class.java,
                java.util.Collection::class.java, String::class.java,
                java.util.Collection::class.java, java.util.Collection::class.java,
                java.util.Map::class.java, String::class.java
            )
            safeHook(constructor, CoreHooks.createGoogleSignInAccountHook(),
                "GoogleSignInAccount.<init>")
        } catch (e: ClassNotFoundException) {
            RenjanaLog.d(TAG, "GoogleSignInAccount class not in guest; skipping account hook")
        } catch (e: NoSuchMethodException) {
            // GMS updates may change the constructor signature — try a no-arg fallback.
            RenjanaLog.d(TAG, "GoogleSignInAccount constructor signature mismatch; trying default")
            try {
                val accountClass = classLoader.loadClass(
                    "com.google.android.gms.auth.api.signin.GoogleSignInAccount"
                )
                val constructors = accountClass.declaredConstructors
                if (constructors.isNotEmpty()) {
                    safeHook(constructors[0], CoreHooks.createGoogleSignInAccountHook(),
                        "GoogleSignInAccount.<init>(fallback)")
                }
            } catch (e2: Throwable) {
                RenjanaLog.w(TAG, "GoogleSignInAccount fallback hook also failed: ${e2.message}")
            }
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook GoogleSignInAccount: ${e.message}")
        }
    }

    /**
     * Hook ContextImpl.getSharedPreferences to redirect prefs to virtual paths.
     * Target: android.app.ContextImpl.getSharedPreferences(String, int)
     */
    private fun hookSharedPreferences() {
        try {
            val contextImplClass = Class.forName("android.app.ContextImpl")
            val method = contextImplClass.getDeclaredMethod(
                "getSharedPreferences", String::class.java, Int::class.javaPrimitiveType
            )
            safeHook(method, CoreHooks.createSharedPreferencesHook(),
                "ContextImpl.getSharedPreferences")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook getSharedPreferences: ${e.message}")
        }
    }

    /**
     * Hook File constructors for path redirection.
     * Targets: File(String), File(String, String)
     */
    private fun hookFileConstructors() {
        try {
            val ctor1 = File::class.java.getDeclaredConstructor(String::class.java)
            safeHook(ctor1, CoreHooks.createFileConstructorHook(), "File(String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook File(String): ${e.message}")
        }

        try {
            val ctor2 = File::class.java.getDeclaredConstructor(
                String::class.java, String::class.java
            )
            safeHook(ctor2, CoreHooks.createFileConstructor2Hook(), "File(String, String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook File(String, String): ${e.message}")
        }
    }

    /**
     * Hook File.exists() to hide container / hook framework files.
     * Target: java.io.File.exists()
     */
    private fun hookFileExists() {
        try {
            val method = File::class.java.getDeclaredMethod("exists")
            safeHook(method, CoreHooks.createFileExistsHook(), "File.exists")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook File.exists: ${e.message}")
        }
    }

    /**
     * Hook System.getProperty for anti-detection.
     * Target: java.lang.System.getProperty(String)
     */
    private fun hookSystemGetProperty() {
        try {
            val method = System::class.java.getDeclaredMethod(
                "getProperty", String::class.java
            )
            safeHook(method, CoreHooks.createSystemGetPropertyHook(), "System.getProperty")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook System.getProperty: ${e.message}")
        }
    }

    /**
     * Hook android.os.SystemProperties.get for anti-detection.
     * Target: android.os.SystemProperties.get(String, String)
     */
    private fun hookSystemPropertiesGet() {
        try {
            val sysPropsClass = Class.forName("android.os.SystemProperties")
            val method = sysPropsClass.getDeclaredMethod(
                "get", String::class.java, String::class.java
            )
            safeHook(method, CoreHooks.createSystemPropertiesGetHook(),
                "SystemProperties.get")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook SystemProperties.get: ${e.message}")
        }
    }

    /**
     * Hook Thread.getStackTrace to obfuscate container frames.
     * Target: java.lang.Thread.getStackTrace()
     */
    private fun hookGetStackTrace() {
        try {
            val method = Thread::class.java.getDeclaredMethod("getStackTrace")
            safeHook(method, CoreHooks.createGetStackTraceHook(), "Thread.getStackTrace")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Thread.getStackTrace: ${e.message}")
        }
    }

    /**
     * Hook Class.forName to block reflection on container classes.
     * Target: java.lang.Class.forName(String, boolean, ClassLoader)
     */
    private fun hookClassForName() {
        try {
            val method = Class::class.java.getDeclaredMethod(
                "forName", String::class.java,
                Boolean::class.javaPrimitiveType, ClassLoader::class.java
            )
            safeHook(method, CoreHooks.createClassForNameHook(), "Class.forName")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Class.forName: ${e.message}")
        }
    }

    /**
     * Hook FileInputStream(String) and FileReader(String) constructors to intercept
     * reads of /proc/self/maps and filter out hook-framework line entries.
     * Delegates to [CoreHooks.createProcMapsHook] for the XC_MethodHook definitions.
     */
    private fun hookProcMaps() {
        try {
            val method = java.io.FileInputStream::class.java.getDeclaredConstructor(
                String::class.java
            )
            safeHook(method, CoreHooks.createProcMapsHook(), "FileInputStream(String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook FileInputStream(String) for /proc/maps: ${e.message}")
        }

        try {
            val method = java.io.FileReader::class.java.getDeclaredConstructor(
                String::class.java
            )
            safeHook(method, CoreHooks.createProcMapsHook(), "FileReader(String)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook FileReader(String) for /proc/maps: ${e.message}")
        }
    }

    /**
     * Install fingerprint spoofing hooks for the current instance.
     *
     * Hooks three targets:
     *  1. Settings.Secure.getString — intercepts "android_id" key
     *  2. TelephonyManager.getDeviceId / getImei / getSubscriberId
     *  3. java.lang.reflect.Field.get — intercepts Build field reads
     *
     * All three use a single shared [XC_MethodHook] instance from [CoreHooks]
     * so the hook object is consistent with the rest of the factory pattern.
     */
    private fun hookFingerprintHooks() {
        // ── 1. Settings.Secure.getString → android_id spoof ──
        try {
            val method = android.provider.Settings.Secure::class.java.getDeclaredMethod(
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java
            )
            safeHook(method, CoreHooks.createAndroidIdHook(), "Settings.Secure.getString")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Settings.Secure.getString: ${e.message}")
        }

        // ── 2. TelephonyManager telephony ID methods ──
        try {
            val tmClass = TelephonyManager::class.java

            val getDeviceId = tmClass.getDeclaredMethod("getDeviceId")
            safeHook(getDeviceId, CoreHooks.createTelephonyHook(), "TelephonyManager.getDeviceId")

            val getImei = tmClass.getDeclaredMethod("getImei", Int::class.javaPrimitiveType)
            safeHook(getImei, CoreHooks.createTelephonyHook(), "TelephonyManager.getImei")

            val getSubscriberId = tmClass.getDeclaredMethod("getSubscriberId")
            safeHook(getSubscriberId, CoreHooks.createTelephonyHook(), "TelephonyManager.getSubscriberId")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook TelephonyManager: ${e.message}")
        }

        // ── 3. Field.get → Build field spoof ──
        try {
            val method = java.lang.reflect.Field::class.java.getDeclaredMethod(
                "get", Any::class.java
            )
            safeHook(method, CoreHooks.createBuildFieldHook(), "Field.get")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook Field.get for Build fields: ${e.message}")
        }
    }

    /**
     * Hook [android.app.NotificationManager.notify] for both overloads so that
     * guest app notifications are routed to the per-instance channel.
     *
     * Both variants share the same [CoreHooks.createNotificationHook] factory
     * because the hook inspects the last argument (which is always the
     * [android.app.Notification]) regardless of overload.
     */
    private fun hookNotifications() {
        try {
            val nmClass = android.app.NotificationManager::class.java
            val hook = CoreHooks.createNotificationHook()

            // notify(int id, Notification notification)
            val notifyById = nmClass.getDeclaredMethod(
                "notify",
                Int::class.javaPrimitiveType,
                android.app.Notification::class.java
            )
            safeHook(notifyById, hook, "NotificationManager.notify(int,Notification)")

            // notify(String tag, int id, Notification notification)
            val notifyByTag = nmClass.getDeclaredMethod(
                "notify",
                String::class.java,
                Int::class.javaPrimitiveType,
                android.app.Notification::class.java
            )
            safeHook(notifyByTag, hook, "NotificationManager.notify(String,int,Notification)")
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook NotificationManager.notify: ${e.message}")
        }
    }

    // ── Utility Methods ────────────────────────────────────────────────────

    /**
     * Find a method on ActivityThread by name. ActivityThread is hidden API so
     * we use reflection. Returns null if not found.
     */
    private fun findActivityThreadMethod(name: String): Method? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            for (method in activityThreadClass.declaredMethods) {
                if (method.name == name) {
                    return method
                }
            }
            null
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "ActivityThread.$name not found: ${e.message}")
            null
        }
    }

    /**
     * Try to resolve the guest APK path when [apkPath] is not explicitly provided.
     * Strategy:
     *  1. Check `<dataPath>/base.apk` (VirtualApp convention).
     *  2. Check `<dataPath>/source.apk`.
     *  3. Check if the dataPath itself is an APK.
     *  4. Try reflection on the classLoader's DexClassLoader path.
     */
    private fun resolveApkPath(dataPath: String, classLoader: ClassLoader): String? {
        // Strategy 1 & 2: common APK locations inside the data path.
        val candidates = listOf(
            File(dataPath, "base.apk"),
            File(dataPath, "source.apk"),
            File(dataPath, "app.apk")
        )
        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile) {
                return candidate.absolutePath
            }
        }

        // Strategy 3: dataPath itself might be the APK path.
        val dataFile = File(dataPath)
        if (dataFile.isFile && dataFile.name.endsWith(".apk")) {
            return dataFile.absolutePath
        }

        // Strategy 4: try to extract from DexClassLoader via reflection.
        try {
            // VirtualClassLoader wraps a DexClassLoader in field `dexClassLoader`.
            val dexClField = classLoader.javaClass.getDeclaredField("dexClassLoader")
            dexClField.isAccessible = true
            val dexCl = dexClField.get(classLoader) as? DexClassLoader
            if (dexCl != null) {
                // DexClassLoader stores its paths in BaseDexClassLoader.pathList.
                val baseClClass = Class.forName("dalvik.system.BaseDexClassLoader")
                val pathListField = baseClClass.getDeclaredField("pathList")
                pathListField.isAccessible = true
                val pathList = pathListField.get(dexCl) ?: return null

                // DexPathList has a `dexElements` array; each element has a `dexFile`
                // whose `mFileName` is the APK/dex path.
                val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
                dexElementsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val dexElements = dexElementsField.get(pathList) as? Array<Any> ?: return null

                for (element in dexElements) {
                    try {
                        val dexFileField = element.javaClass.getDeclaredField("dexFile")
                        dexFileField.isAccessible = true
                        val dexFile = dexFileField.get(element) ?: continue
                        val fileNameField = dexFile.javaClass.getDeclaredField("mFileName")
                        fileNameField.isAccessible = true
                        val fileName = fileNameField.get(dexFile) as? String ?: continue
                        if (fileName.endsWith(".apk")) {
                            return fileName
                        }
                    } catch (_: Throwable) {
                        // Continue to next element
                    }
                }
            }
        } catch (_: Throwable) {
            // Reflection failed — fall through
        }

        return null
    }

    /**
     * Create a guest ClassLoader for the given package.
     * Reads DEX files from `<dataPath>/dex` and creates a [DexClassLoader].
     */
    @Deprecated("Use the classLoader passed to installGuestHooks() instead.")
    fun createGuestClassLoader(packageName: String, dataPath: String): ClassLoader? {
        return try {
            val dexPath = File(dataPath, "dex")
            if (!dexPath.exists() || !dexPath.isDirectory) return null

            val dexFiles = dexPath.listFiles { file -> file.extension == "dex" }
            if (dexFiles == null || dexFiles.isEmpty()) return null

            val dexPathStr = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val optimizedDir = File(dataPath, "dex_opt").apply { if (!exists()) mkdirs() }

            DexClassLoader(
                dexPathStr,
                optimizedDir.absolutePath,
                null,
                ClassLoader.getSystemClassLoader()
            )
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to create guest ClassLoader for $packageName: ${e.message}")
            null
        }
    }

    fun reset() {
        CoreHooks.reset()
        hookedPackages.clear()
        initialized = false
        pineAvailable = false
        RenjanaLog.i(TAG, "PineHookManager state reset")
    }
}
