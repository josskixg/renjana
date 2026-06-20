package com.fesu.renjana.hooks

import android.os.Build
import android.os.Environment
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Anti-detection module for Renjana container.
 *
 * Prevents cloned apps from detecting they are running inside a container
 * by hooking system APIs, filtering sensitive paths, and cloaking environment
 * properties.
 *
 * Detection vectors addressed:
 * - File existence checks (/proc/self/maps, /system/xbin/su, etc.)
 * - Build property inspection (SERIAL, FINGERPRINT, MODEL, etc.)
 * - System property queries (ro.debuggable, ro.build.tags, etc.)
 * - Package manager queries (is Xposed/VirtualXposed installed?)
 * - Stack trace inspection (looking for hook framework classes)
 * - Reflection on container classes
 * - /proc/self/maps parsing (looking for hook libraries)
 */
object AntiDetection {
    private const val TAG = "AntiDetection"

    /** Current active instance ID for the calling thread */
    private val currentInstanceId = ThreadLocal<String?>()

    /** Container-internal paths that must be hidden from guest apps */
    private val containerPaths = mutableSetOf<String>()

    /** Known hook framework package names to filter from package lists */
    private val hookFrameworkPackages = setOf(
        "de.robv.android.xposed.installer",
        "io.github.vvb2060.xposed.installer",
        "org.meowcat.edxposed.manager",
        "org.lsposed.manager",
        "com.swift.sandhook.xposedcompat",
        "top.canyie.pine",
        "me.weishu.exposed",
        "com.lody.virtual",
        "io.virtualapp",
        "com.elderdrivers.riru.edxp",
        "com.topjohnwu.magisk"
    )

    /** Sensitive file paths that indicate root/hook detection attempts */
    private val sensitivePaths = setOf(
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/data/local/xposed",
        "/data/local/tmp/xposed",
        "/system/framework/XposedBridge.jar",
        "/system/lib/libxposed_art.so",
        "/system/lib64/libxposed_art.so",
        "/data/misc/riru/modules",
        "/data/adb/modules",
        "/proc/self/maps",
        "/proc/self/mountinfo"
    )

    /** Build properties to spoof */
    private val spoofedBuildProps = ConcurrentHashMap<String, String>()

    /** System properties to intercept */
    private val interceptedSystemProps = ConcurrentHashMap<String, String>()

    /**
     * Initialize anti-detection for a container instance.
     *
     * @param instanceId Unique ID of the container instance
     * @param dataPath Virtual data path for this instance
     */
    fun initialize(instanceId: String, dataPath: String) {
        currentInstanceId.set(instanceId)

        // Register container paths that must be hidden
        containerPaths.add(dataPath)
        containerPaths.add("/data/data/com.renjana.container")
        containerPaths.add("/data/user/0/com.renjana.container")

        // Add instance-specific paths
        val instanceDir = File(dataPath)
        if (instanceDir.exists()) {
            containerPaths.add(instanceDir.absolutePath)
            containerPaths.add(instanceDir.canonicalPath)
        }

        setupBuildPropertySpoofing()
        setupSystemPropertyInterception()

        RenjanaLog.i(TAG, "AntiDetection initialized for instance $instanceId")
    }

    /**
     * Set the current instance ID for the calling thread.
     * Must be called when switching context to a different instance.
     */
    fun setCurrentInstance(instanceId: String?) {
        currentInstanceId.set(instanceId)
    }

    /**
     * Get the current instance ID for the calling thread.
     */
    fun getCurrentInstance(): String? {
        return currentInstanceId.get()
    }

    // ---- File Existence Cloaking ----

    /**
     * Check if a file path should be hidden from the guest app.
     * Called from File.exists() hook.
     *
     * @param path The file path being checked
     * @return true if the path should be reported as non-existent
     */
    fun shouldHidePath(path: String): Boolean {
        // Hide container-internal paths
        for (containerPath in containerPaths) {
            if (path.startsWith(containerPath)) {
                RenjanaLog.v(TAG, "Hiding container path: $path")
                return true
            }
        }

        // Hide sensitive paths that indicate root/hook detection
        if (path in sensitivePaths) {
            RenjanaLog.v(TAG, "Hiding sensitive path: $path")
            return true
        }

        // Hide hook framework artifacts
        val lowerPath = path.lowercase()
        if (lowerPath.contains("xposed") ||
            lowerPath.contains("edxposed") ||
            lowerPath.contains("lsposed") ||
            lowerPath.contains("riru") ||
            lowerPath.contains("magisk") ||
            lowerPath.contains("pine") ||
            lowerPath.contains("sandhook") ||
            lowerPath.contains("virtualapp") ||
            lowerPath.contains("virtualxposed") ||
            lowerPath.contains("renjana")
        ) {
            RenjanaLog.v(TAG, "Hiding hook-related path: $path")
            return true
        }

        return false
    }

    /**
     * Filter /proc/self/maps content to remove hook framework entries.
     *
     * @param mapsContent Raw content of /proc/self/maps
     * @return Filtered content with hook libraries removed
     */
    fun filterMapsContent(mapsContent: String): String {
        val lines = mapsContent.lines()
        val filtered = lines.filter { line ->
            val lowerLine = line.lowercase()
            !lowerLine.contains("xposed") &&
            !lowerLine.contains("edxposed") &&
            !lowerLine.contains("lsposed") &&
            !lowerLine.contains("riru") &&
            !lowerLine.contains("sandhook") &&
            !lowerLine.contains("pine") &&
            !lowerLine.contains("virtualapp") &&
            !lowerLine.contains("renjana") &&
            !lowerLine.contains("libxposed") &&
            !lowerLine.contains("libpine")
        }
        return filtered.joinToString("\n")
    }

    // ---- Build Property Spoofing ----

    /**
     * Set up default Build property spoofing values.
     * These make the environment look like a standard, non-rooted device.
     */
    private fun setupBuildPropertySpoofing() {
        // Spoof SERIAL to look like a production device
        spoofedBuildProps["SERIAL"] = "unknown"
        spoofedBuildProps["ro.build.display.id"] = Build.DISPLAY

        // Ensure build tags say "release-keys" (not "test-keys")
        spoofedBuildProps["TAGS"] = "release-keys"

        // Keep FINGERPRINT consistent but looking production
        spoofedBuildProps["FINGERPRINT"] = Build.FINGERPRINT
    }

    /**
     * Get a spoofed Build property value.
     * Called from Build field access hooks.
     *
     * @param fieldName The Build field name (e.g., "SERIAL", "TAGS")
     * @return Spoofed value or null if no spoofing is configured
     */
    fun getSpoofedBuildField(fieldName: String): String? {
        return spoofedBuildProps[fieldName]
    }

    /**
     * Set a custom spoofed Build property.
     *
     * @param fieldName Build field name
     * @param value Spoofed value
     */
    fun setBuildPropertySpoof(fieldName: String, value: String) {
        spoofedBuildProps[fieldName] = value
        RenjanaLog.d(TAG, "Set build property spoof: $fieldName = $value")
    }

    // ---- System Property Interception ----

    /**
     * Set up interception for System.getProperty() and getprop.
     */
    private fun setupSystemPropertyInterception() {
        // Hide root indicators
        interceptedSystemProps["ro.debuggable"] = "0"
        interceptedSystemProps["ro.secure"] = "1"
        interceptedSystemProps["ro.build.tags"] = "release-keys"
        interceptedSystemProps["service.bootanim.exit"] = "1"

        // Hide custom ROM indicators
        interceptedSystemProps["ro.build.type"] = "user"
        interceptedSystemProps["ro.build.selinux"] = "1"
    }

    /**
     * Get intercepted system property value.
     * Called from System.getProperty() and android.os.SystemProperties.get() hooks.
     *
     * @param key Property key
     * @return Spoofed value, or null to pass through to real implementation
     */
    fun getInterceptedSystemProperty(key: String): String? {
        return interceptedSystemProps[key]
    }

    /**
     * Set a custom intercepted system property.
     *
     * @param key Property key
     * @param value Spoofed value to return
     */
    fun setSystemPropertyIntercept(key: String, value: String) {
        interceptedSystemProps[key] = value
        RenjanaLog.d(TAG, "Set system property intercept: $key = $value")
    }

    // ---- Package List Filtering ----

    /**
     * Check if a package name is a known hook framework.
     * Used to filter installed package lists returned by PackageManager.
     *
     * @param packageName Package name to check
     * @return true if the package should be hidden from the guest app
     */
    fun isHookFrameworkPackage(packageName: String): Boolean {
        return packageName in hookFrameworkPackages
    }

    /**
     * Filter a list of installed packages to remove hook frameworks
     * and the container app itself.
     *
     * @param packages List of package names
     * @return Filtered list
     */
    fun filterPackageList(packages: List<String>): List<String> {
        return packages.filter { pkg ->
            !isHookFrameworkPackage(pkg) &&
            pkg != "com.renjana.container" &&
            !pkg.startsWith("com.renjana.container.")
        }
    }

    // ---- Stack Trace Obfuscation ----

    /**
     * Obfuscate a stack trace to remove hook framework entries.
     * Called when guest app inspects its own stack trace.
     *
     * @param stackTrace Original stack trace
     * @return Obfuscated stack trace
     */
    fun obfuscateStackTrace(stackTrace: Array<StackTraceElement>): Array<StackTraceElement> {
        val filtered = stackTrace.filter { element ->
            val className = element.className
            !isHookFrameworkClass(className)
        }

        // Replace container class references with standard Android classes
        return filtered.map { element ->
            if (element.className.startsWith("com.renjana.")) {
                StackTraceElement(
                    "android.app.ActivityThread",
                    element.methodName,
                    "ActivityThread.java",
                    element.lineNumber
                )
            } else {
                element
            }
        }.toTypedArray()
    }

    /**
     * Check if a class name belongs to a hook framework.
     */
    private fun isHookFrameworkClass(className: String): Boolean {
        return className.startsWith("de.robv.android.xposed.") ||
                className.startsWith("top.canyie.pine.") ||
                className.startsWith("me.weishu.") ||
                className.startsWith("com.lody.") ||
                className.startsWith("io.virtualapp.") ||
                className.startsWith("com.elderdrivers.") ||
                className.startsWith("org.meowcat.") ||
                className.startsWith("org.lsposed.") ||
                className.startsWith("com.swift.sandhook.")
    }

    // ---- Reflection Protection ----

    /**
     * Check if a reflection target is a container-internal class that
     * should be hidden from guest app inspection.
     *
     * @param className The class being reflected upon
     * @return true if access should be blocked
     */
    fun shouldBlockReflection(className: String): Boolean {
        return className.startsWith("com.renjana.container.") ||
                className.startsWith("com.renjana.container.hooks.") ||
                className.startsWith("com.renjana.container.core.") ||
                className.startsWith("com.renjana.container.virtual.") ||
                isHookFrameworkClass(className)
    }

    /**
     * Check if a class-for-name lookup should be redirected.
     * Returns a fake ClassNotFoundException message if the class should be hidden.
     *
     * @param className The class name being looked up
     * @return Error message if blocked, null if allowed
     */
    fun checkClassAccess(className: String): String? {
        if (shouldBlockReflection(className)) {
            RenjanaLog.w(TAG, "Blocked reflection on: $className")
            return "Didn't find class \"$className\" on path: DexPathList"
        }
        return null
    }

    // ---- Environment Cloaking ----

    /**
     * Get the spoofed external storage path for the current instance.
     * Guest apps should see their own external storage, not the real one.
     *
     * @param instanceId Instance ID
     * @return Spoofed external storage path
     */
    fun getSpoofedExternalPath(instanceId: String): String {
        return "/storage/emulated/0/Android/data/com.renjana.container/files/$instanceId"
    }

    /**
     * Rewrite a path from the guest app's perspective to the virtual filesystem.
     *
     * @param originalPath Path as seen by the guest app
     * @param instanceId Current instance ID
     * @param originalPackageName The guest app's original package name
     * @return Rewritten path in the virtual filesystem
     */
    fun rewritePath(originalPath: String, instanceId: String, originalPackageName: String): String {
        // Rewrite /data/data/<pkg> to instance-specific path
        val dataPrefix = "/data/data/$originalPackageName"
        if (originalPath.startsWith(dataPrefix)) {
            val subPath = originalPath.removePrefix(dataPrefix)
            return "/data/data/com.renjana.container/files/$instanceId/data$subPath"
        }

        // Rewrite external storage
        val extPrefix = Environment.getExternalStorageDirectory().absolutePath
        if (originalPath.startsWith(extPrefix)) {
            val subPath = originalPath.removePrefix(extPrefix)
            return "/data/data/com.renjana.container/files/$instanceId/external$subPath"
        }

        // Rewrite cache directory
        val cachePrefix = "/data/data/$originalPackageName/cache"
        if (originalPath.startsWith(cachePrefix)) {
            val subPath = originalPath.removePrefix(cachePrefix)
            return "/data/data/com.renjana.container/files/$instanceId/cache$subPath"
        }

        return originalPath
    }

    // ---- Detection Check Helpers ----

    /**
     * Perform a comprehensive detection check.
     * Returns a list of detection vectors that were triggered.
     *
     * @return List of triggered detection vector names (empty = clean)
     */
    fun checkDetectionVectors(): List<String> {
        val triggered = mutableListOf<String>()

        // Check for root binaries
        val rootPaths = listOf(
            "/system/xbin/su", "/system/bin/su", "/sbin/su"
        )
        for (path in rootPaths) {
            if (File(path).exists()) {
                triggered.add("root_binary:$path")
            }
        }

        // Check for Magisk
        if (File("/data/adb/magisk").exists()) {
            triggered.add("magisk_detected")
        }

        // Check build properties
        if (Build.TAGS != "release-keys") {
            triggered.add("build_tags:${Build.TAGS}")
        }

        // Check for Xposed
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            triggered.add("xposed_class_found")
        } catch (_: ClassNotFoundException) {
            // Good, not found
        }

        // Check /proc/self/maps
        try {
            val maps = File("/proc/self/maps").readText()
            if (maps.lowercase().contains("xposed") ||
                maps.lowercase().contains("riru") ||
                maps.lowercase().contains("magisk")
            ) {
                triggered.add("suspicious_maps_entry")
            }
        } catch (_: Exception) {
            // Cannot read maps, that's fine
        }

        if (triggered.isNotEmpty()) {
            RenjanaLog.w(TAG, "Detection vectors triggered: ${triggered.joinToString(", ")}")
        }

        return triggered
    }

    /**
     * Cleanup resources when an instance is stopped.
     *
     * @param instanceId Instance ID being stopped
     */
    fun cleanup(instanceId: String) {
        currentInstanceId.remove()
        RenjanaLog.d(TAG, "AntiDetection cleaned up for instance $instanceId")
    }

    /**
     * Full cleanup on container shutdown.
     */
    fun shutdown() {
        currentInstanceId.remove()
        containerPaths.clear()
        spoofedBuildProps.clear()
        interceptedSystemProps.clear()
        RenjanaLog.i(TAG, "AntiDetection shutdown")
    }
}
