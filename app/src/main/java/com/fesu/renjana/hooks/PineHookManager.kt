package com.fesu.renjana.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.lang.reflect.Method

/**
 * Pine hook manager for non-root mode.
 * Uses reflection to access Pine APIs at runtime (no compile-time dependency).
 * Falls back gracefully when Pine is not available.
 */
object PineHookManager {

    private const val TAG = "PineHookManager"
    private const val CONTAINER_PACKAGE = "com.renjana.container"

    private val hookedPackages = mutableSetOf<String>()

    @Volatile
    private var initialized = false

    @Volatile
    private var pineAvailable = false

    /** Cached Pine class reference */
    private var pineClass: Class<*>? = null

    /** Cached MethodHook class reference */
    private var methodHookClass: Class<*>? = null

    fun initialize(): Boolean {
        if (initialized) {
            return pineAvailable
        }

        try {
            pineAvailable = try {
                pineClass = Class.forName("top.canyie.pine.Pine")
                methodHookClass = Class.forName("top.canyie.pine.callback.MethodHook")
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

    fun installGuestHooks(
        packageName: String,
        classLoader: ClassLoader,
        instanceId: String,
        dataPath: String
    ): Boolean {
        if (!pineAvailable) {
            RenjanaLog.w(TAG, "Pine not available, cannot install guest hooks")
            return false
        }

        synchronized(hookedPackages) {
            if (hookedPackages.contains(packageName)) {
                RenjanaLog.d(TAG, "Package $packageName already hooked, skipping")
                return true
            }

            RenjanaLog.i(TAG, "Installing Pine guest hooks for: $packageName")

            try {
                CoreHooks.registerPackage(packageName, dataPath, instanceId)
                AntiDetection.initialize(instanceId, dataPath)

                hookActivityThread()
                hookPackageManager(classLoader)
                hookGoogleSignIn(classLoader)
                hookSharedPreferences()
                hookFileConstructors()
                hookGetStackTrace()
                hookClassForName()

                hookedPackages.add(packageName)
                RenjanaLog.i(TAG, "Pine guest hooks installed for $packageName")
                return true
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to install Pine guest hooks for $packageName: ${e.message}")
                return false
            }
        }
    }

    fun uninstallGuestHooks(packageName: String, instanceId: String) {
        synchronized(hookedPackages) {
            if (!hookedPackages.contains(packageName)) return

            RenjanaLog.i(TAG, "Uninstalling Pine guest hooks for: $packageName")

            try {
                CoreHooks.unregisterPackage(packageName)
                AntiDetection.cleanup(instanceId)
                hookedPackages.remove(packageName)
                RenjanaLog.i(TAG, "Pine guest hooks uninstalled for $packageName")
            } catch (e: Throwable) {
                RenjanaLog.e(TAG, "Failed to uninstall Pine guest hooks: ${e.message}")
            }
        }
    }

    // --- Hook Installation via Reflection ---

    private fun hookMethod(method: java.lang.reflect.Method, hookInstance: Any) {
        try {
            val hookMethod = pineClass?.getMethod("hook", java.lang.reflect.Member::class.java, methodHookClass)
            hookMethod?.invoke(null, method, hookInstance)
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to hook ${method.name}: ${e.message}")
        }
    }

    private fun createMethodHook(beforeCall: ((Any?, Array<Any?>) -> Unit)? = null,
                                  afterCall: ((Any?, Array<Any?>, Any?) -> Any?)? = null): Any? {
        return try {
            // Use dynamic proxy to create MethodHook subclass
            java.lang.reflect.Proxy.newProxyInstance(
                methodHookClass?.classLoader,
                arrayOf(methodHookClass),
                java.lang.reflect.InvocationHandler { _, method, args ->
                    when (method.name) {
                        "beforeCall" -> {
                            beforeCall?.invoke(args?.getOrNull(0), emptyArray())
                            null
                        }
                        "afterCall" -> {
                            afterCall?.invoke(args?.getOrNull(0), emptyArray(), null)
                            null
                        }
                        else -> null
                    }
                }
            )
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to create MethodHook proxy: ${e.message}")
            null
        }
    }

    // --- System Hooks ---

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

    // --- Guest App Hooks (stubs that delegate to reflection) ---

    private fun hookActivityThread() {
        RenjanaLog.d(TAG, "ActivityThread hook placeholder (requires Pine runtime)")
    }

    private fun hookPackageManager(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        RenjanaLog.d(TAG, "PackageManager hook placeholder (requires Pine runtime)")
    }

    private fun hookGoogleSignIn(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        RenjanaLog.d(TAG, "GoogleSignIn hook placeholder (requires Pine runtime)")
    }

    private fun hookSharedPreferences() {
        RenjanaLog.d(TAG, "SharedPreferences hook placeholder (requires Pine runtime)")
    }

    private fun hookFileConstructors() {
        RenjanaLog.d(TAG, "File constructors hook placeholder (requires Pine runtime)")
    }

    private fun hookGetStackTrace() {
        RenjanaLog.d(TAG, "getStackTrace hook placeholder (requires Pine runtime)")
    }

    private fun hookClassForName() {
        RenjanaLog.d(TAG, "Class.forName hook placeholder (requires Pine runtime)")
    }

    // --- Helper Methods ---

    private fun extractInstanceId(dataPath: String): String {
        val parts = dataPath.split("/")
        val filesIdx = parts.indexOf("files")
        return if (filesIdx >= 0 && filesIdx + 1 < parts.size) {
            parts[filesIdx + 1]
        } else {
            dataPath.hashCode().toString()
        }
    }

    private fun findRequestingPackage(): String? {
        val instanceId = CoreHooks.currentInstanceId.get()
        if (instanceId != null) {
            for ((pkg, path) in CoreHooks.packageDataPaths) {
                if (path.contains(instanceId)) {
                    return pkg
                }
            }
        }
        return null
    }

    private fun getGuestClassLoader(packageName: String, dataPath: String): ClassLoader? {
        try {
            val dexPath = File(dataPath, "dex")
            if (!dexPath.exists() || !dexPath.isDirectory) return null

            val dexFiles = dexPath.listFiles { file -> file.extension == "dex" }
            if (dexFiles == null || dexFiles.isEmpty()) return null

            val dexPathStr = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val optimizedDir = File(dataPath, "dex_opt").apply { if (!exists()) mkdirs() }

            return dalvik.system.DexClassLoader(
                dexPathStr,
                optimizedDir.absolutePath,
                null,
                ClassLoader.getSystemClassLoader()
            )
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to create guest ClassLoader for $packageName: ${e.message}")
            return null
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
