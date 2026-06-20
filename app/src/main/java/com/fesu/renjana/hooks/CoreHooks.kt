package com.fesu.renjana.hooks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Bundle
import com.fesu.renjana.models.GoogleAccount
import com.fesu.renjana.utils.RenjanaLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Core hook definitions shared by both Xposed (root) and Pine (non-root) modes.
 *
 * Each hook is implemented as an XC_MethodHook that can be directly used by Xposed
 * and adapted for Pine. Hooks are categorized by what they intercept:
 *
 * 1. ActivityThread hooks - intercept Activity lifecycle
 * 2. PackageManager hooks - spoof signatures and package info
 * 3. Google Sign-In hooks - return virtualized accounts
 * 4. Context hooks - redirect SharedPreferences to virtual paths
 * 5. File hooks - redirect file operations to virtual filesystem
 * 6. Detection evasion hooks - File.exists(), Build properties, System.getProperty()
 */
object CoreHooks {
    private const val TAG = "CoreHooks"

    /**
     * ThreadLocal tracking which container instance is active on the current thread.
     * This allows hooks to determine which virtual context to use.
     */
    val currentInstanceId = ThreadLocal<String?>()

    /**
     * Maps package names to their virtual data paths.
     * Populated when instances are launched.
     */
    val packageDataPaths = ConcurrentHashMap<String, String>()

    /**
     * Maps package names to their GoogleAccount for sign-in spoofing.
     */
    val virtualAccounts = ConcurrentHashMap<String, GoogleAccount>()

    /**
     * Tracks which packages have active hooks to avoid duplicate installations.
     */
    private val hookedPackages = ConcurrentHashMap<String, Boolean>()

    /**
     * Container package name (used to identify our own code in hooks).
     */
    private const val CONTAINER_PACKAGE = "com.renjana.container"

    // ==================== 1. ActivityThread Hooks ====================

    /**
     * Hook ActivityThread.handleLaunchActivity() to intercept Activity creation.
     *
     * This is the core hook that allows the container to:
     * - Set the correct class loader for the guest app
     * - Inject the virtual context before Activity.onCreate()
     * - Track which instance is active
     *
     * Target: android.app.ActivityThread.handleLaunchActivity(IBinder r, List pendingResults)
     */
    fun createActivityThreadHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val activityThread = param.thisObject
                    val recordToken = param.args[0]

                    // Get the ActivityClientRecord from the token
                    val mActivitiesField = XposedHelpers.findField(
                        activityThread.javaClass,
                        "mActivities"
                    )
                    @Suppress("UNCHECKED_CAST")
                    val activities = mActivitiesField.get(activityThread) as? Map<Any, Any>
                        ?: return

                    val record = activities[recordToken] ?: return

                    // Extract the intent from the record
                    val intentField = XposedHelpers.findField(record.javaClass, "intent")
                    val intent = intentField.get(record) as? Intent ?: return

                    val component = intent.component ?: return
                    val packageName = component.packageName

                    // Check if this package is managed by our container
                    val dataPath = packageDataPaths[packageName] ?: return

                    // Set the instance ID for this thread
                    val instanceId = extractInstanceId(dataPath)
                    currentInstanceId.set(instanceId)
                    AntiDetection.setCurrentInstance(instanceId)

                    RenjanaLog.d(TAG, "Launching activity in instance $instanceId: ${component.className}")

                    // Set the correct class loader for the guest package
                    val classLoaderField = XposedHelpers.findField(
                        record.javaClass, "packageInfo"
                    )
                    val packageInfo = classLoaderField.get(record)
                    if (packageInfo != null) {
                        val loaderField = XposedHelpers.findField(
                            packageInfo.javaClass, "classLoader"
                        )
                        val guestClassLoader = getGuestClassLoader(packageName, dataPath)
                        if (guestClassLoader != null) {
                            loaderField.set(packageInfo, guestClassLoader)
                        }
                    }

                    // Inject virtual extras into the intent
                    injectVirtualExtras(intent, instanceId, packageName)
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in ActivityThread.beforeHook: ${e.message}")
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.result as? Activity ?: return
                    val instanceId = currentInstanceId.get() ?: return

                    RenjanaLog.d(TAG, "Activity launched: ${activity.javaClass.name} in instance $instanceId")

                    // Apply anti-detection to the Activity's window
                    applyWindowAntiDetection(activity)
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in ActivityThread.afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook Activity.performCreate() to inject virtual context before Activity.onCreate().
     *
     * This runs after the Activity object is created but before its onCreate() is called,
     * giving us a chance to swap out the base context with a virtual one.
     */
    fun createActivityPerformCreateHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as? Activity ?: return
                    val instanceId = currentInstanceId.get() ?: return
                    val packageName = activity.packageName

                    // Check if this activity belongs to a managed package
                    val dataPath = packageDataPaths[packageName]
                    if (dataPath == null) {
                        // Not a managed package, skip
                        return
                    }

                    RenjanaLog.d(TAG, "Activity.performCreate for $packageName in instance $instanceId")

                    // Rewrite the Activity's data dir to the virtual path
                    val mBaseField = try {
                        XposedHelpers.findField(activity.javaClass, "mBase")
                    } catch (e: Exception) {
                        // ContextThemeWrapper.mBase
                        XposedHelpers.findField(Context::class.java.superclass, "mBase")
                    }

                    val baseContext = mBaseField.get(activity) as? Context
                    if (baseContext != null) {
                        // Override the data directory in ContextImpl
                        val mDataDirField = try {
                            XposedHelpers.findField(baseContext.javaClass, "mDataDir")
                        } catch (e: Exception) {
                            null
                        }
                        if (mDataDirField != null) {
                            val virtualDataDir = File(dataPath)
                            if (!virtualDataDir.exists()) {
                                virtualDataDir.mkdirs()
                            }
                            mDataDirField.set(baseContext, virtualDataDir)
                        }

                        // Override SharedPreferences path
                        overrideSharedPrefsPath(baseContext, dataPath, instanceId)
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in Activity.performCreate.beforeHook: ${e.message}")
                }
            }
        }
    }

    // ==================== 2. PackageManager Hooks ====================

    /**
     * Hook ApplicationPackageManager.getPackageInfo() to spoof signatures.
     *
     * When a guest app queries its own package info, we intercept the result
     * and replace the signatures with the original ones.
     */
    fun createGetPackageInfoHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val packageName = param.args[0] as? String ?: return
                    val flags = param.args[1] as? Int ?: return

                    // Only intercept if signature flags are requested
                    val sigFlags = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        @Suppress("DEPRECATION")
                        PackageManager.GET_SIGNATURES
                    }

                    if (flags and sigFlags == 0) {
                        return // No signature info requested, skip
                    }

                    val packageInfo = param.result as? PackageInfo ?: return

                    // Check if we should spoof this package
                    if (SignatureSpoof.hasCachedSignatures(packageName)) {
                        val spoofed = SignatureSpoof.spoofPackageInfo(packageInfo, packageName)
                        if (spoofed) {
                            param.result = packageInfo
                            RenjanaLog.d(TAG, "Spoofed PackageInfo signatures for $packageName")
                        }
                    }

                    // Filter hook framework packages from the result
                    if (AntiDetection.isHookFrameworkPackage(packageName) ||
                        packageName == CONTAINER_PACKAGE
                    ) {
                        // Throw NameNotFoundException to hide container packages
                        param.throwable = PackageManager.NameNotFoundException(packageName)
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in getPackageInfo.afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook ApplicationPackageManager.getInstalledPackages() to filter out
     * hook frameworks and the container app from the results.
     */
    fun createGetInstalledPackagesHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val packages = param.result as? List<PackageInfo> ?: return

                    val filtered = packages.filter { info ->
                        !AntiDetection.isHookFrameworkPackage(info.packageName) &&
                        info.packageName != CONTAINER_PACKAGE &&
                        !info.packageName.startsWith("$CONTAINER_PACKAGE.")
                    }

                    param.result = filtered
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in getInstalledPackages.afterHook: ${e.message}")
                }
            }
        }
    }

    // ==================== 3. Google Sign-In Hooks ====================

    /**
     * Hook GoogleSignIn.getSignedInAccountFromIntent() to return a virtualized account.
     *
     * When a guest app tries to retrieve a signed-in Google account from the result intent,
     * we intercept and return our virtual account instead.
     *
     * Target: com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(Intent)
     */
    fun createGoogleSignInHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    @Suppress("UNUSED_VARIABLE")
                    val intent = param.args[0] as? Intent ?: return
                    val instanceId = currentInstanceId.get() ?: return

                    // Determine which package is requesting sign-in
                    val requestingPackage = findRequestingPackage() ?: return

                    // Check if we have a virtual account for this instance
                    val virtualAccount = virtualAccounts[requestingPackage]
                    if (virtualAccount == null) {
                        RenjanaLog.d(TAG, "No virtual account for $requestingPackage, passing through")
                        return
                    }

                    // Replace the result with our virtual account
                    val spoofedTask = createSpoofedSignInResult(virtualAccount, instanceId)
                    if (spoofedTask != null) {
                        param.result = spoofedTask
                        RenjanaLog.i(TAG, "Spoofed Google Sign-In for $requestingPackage (account: ${virtualAccount.email})")
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in GoogleSignIn.afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook GoogleSignInAccount to intercept account creation.
     * Target: com.google.android.gms.auth.api.signin.GoogleSignInAccount constructor
     */
    fun createGoogleSignInAccountHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val account = param.thisObject ?: return
                    @Suppress("UNUSED_VARIABLE")
                    val instanceId = currentInstanceId.get() ?: return
                    val requestingPackage = findRequestingPackage() ?: return

                    val virtualAccount = virtualAccounts[requestingPackage] ?: return

                    // Replace account fields with virtual account data
                    replaceAccountField(account, "mEmail", virtualAccount.email)
                    replaceAccountField(account, "mDisplayName", virtualAccount.displayName)
                    replaceAccountField(account, "mIdToken", virtualAccount.idToken)
                    if (virtualAccount.photoUrl != null) {
                        replaceAccountField(account, "mPhotoUrl", virtualAccount.photoUrl)
                    }

                    RenjanaLog.d(TAG, "Spoofed GoogleSignInAccount for $requestingPackage")
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in GoogleSignInAccount.afterHook: ${e.message}")
                }
            }
        }
    }

    // ==================== 4. Context / SharedPreferences Hooks ====================

    /**
     * Hook ContextImpl.getSharedPreferences() to redirect to virtual paths.
     *
     * Each instance gets its own SharedPreferences directory so cloned apps
     * don't share data with each other or with the real installation.
     */
    fun createSharedPreferencesHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val context = param.thisObject as? Context ?: return
                    @Suppress("UNUSED_VARIABLE")
                    val instanceId = currentInstanceId.get() ?: return
                    val packageName = context.packageName

                    val dataPath = packageDataPaths[packageName] ?: return

                    // Rewrite the shared prefs file path
                    val sharedPrefsDir = File(dataPath, "shared_prefs")
                    if (!sharedPrefsDir.exists()) {
                        sharedPrefsDir.mkdirs()
                    }

                    // Override the mSharedPrefsDir field in ContextImpl
                    try {
                        val dirField = XposedHelpers.findField(
                            context.javaClass, "mSharedPrefsDir"
                        )
                        dirField.set(context, sharedPrefsDir)
                    } catch (e: NoSuchFieldError) {
                        // Fallback: use reflection directly
                        val field = context.javaClass.getDeclaredField("mSharedPrefsDir")
                        field.isAccessible = true
                        field.set(context, sharedPrefsDir)
                    }

                    RenjanaLog.v(TAG, "Redirected SharedPreferences to $sharedPrefsDir for $packageName")
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in SharedPreferences.beforeHook: ${e.message}")
                }
            }
        }
    }

    // ==================== 5. File Constructor Hooks ====================

    /**
     * Hook File(String) constructor to redirect file operations to virtual filesystem.
     *
     * When a guest app creates a File with a path, we check if it should be
     * redirected to the instance-specific virtual path.
     */
    fun createFileConstructorHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val file = param.thisObject as? File ?: return
                    val instanceId = currentInstanceId.get() ?: return
                    val originalPath = file.absolutePath

                    // Find the requesting package
                    val requestingPackage = findRequestingPackage() ?: return
                    @Suppress("UNUSED_VARIABLE")
                    val dataPath = packageDataPaths[requestingPackage] ?: return

                    // Check if the path should be hidden
                    if (AntiDetection.shouldHidePath(originalPath)) {
                        RenjanaLog.v(TAG, "File constructor: hiding path $originalPath")
                        return
                    }

                    // Rewrite the path
                    val rewrittenPath = AntiDetection.rewritePath(
                        originalPath, instanceId, requestingPackage
                    )

                    if (rewrittenPath != originalPath) {
                        // Replace the file's internal path
                        val pathField = try {
                            XposedHelpers.findField(File::class.java, "path")
                        } catch (e: Exception) {
                            val field = File::class.java.getDeclaredField("path")
                            field.isAccessible = true
                            field
                        }

                        pathField.set(file, rewrittenPath)

                        // Ensure the target directory exists
                        val parentDir = File(rewrittenPath).parentFile
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs()
                        }

                        RenjanaLog.v(TAG, "File redirected: $originalPath -> $rewrittenPath")
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in File(String).afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook File(String, String) constructor for parent/child path redirection.
     */
    fun createFileConstructor2Hook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val file = param.thisObject as? File ?: return
                    val instanceId = currentInstanceId.get() ?: return
                    val originalPath = file.absolutePath

                    val requestingPackage = findRequestingPackage() ?: return
                    @Suppress("UNUSED_VARIABLE")
                    val dataPath = packageDataPaths[requestingPackage] ?: return

                    if (AntiDetection.shouldHidePath(originalPath)) {
                        return
                    }

                    val rewrittenPath = AntiDetection.rewritePath(
                        originalPath, instanceId, requestingPackage
                    )

                    if (rewrittenPath != originalPath) {
                        val pathField = try {
                            XposedHelpers.findField(File::class.java, "path")
                        } catch (e: Exception) {
                            val field = File::class.java.getDeclaredField("path")
                            field.isAccessible = true
                            field
                        }
                        pathField.set(file, rewrittenPath)

                        val parentDir = File(rewrittenPath).parentFile
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs()
                        }

                        RenjanaLog.v(TAG, "File(parent,child) redirected: $originalPath -> $rewrittenPath")
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in File(String,String).afterHook: ${e.message}")
                }
            }
        }
    }

    // ==================== 6. Anti-Detection Hooks ====================

    /**
     * Hook File.exists() to hide container and hook framework files.
     */
    fun createFileExistsHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val file = param.thisObject as? File ?: return
                    val path = file.absolutePath

                    if (AntiDetection.shouldHidePath(path)) {
                        param.result = false
                        RenjanaLog.v(TAG, "File.exists() hidden: $path")
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in File.exists().afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook System.getProperty() to intercept system property queries.
     */
    fun createSystemGetPropertyHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val key = param.args[0] as? String ?: return
                    val intercepted = AntiDetection.getInterceptedSystemProperty(key)
                    if (intercepted != null) {
                        param.result = intercepted
                        RenjanaLog.v(TAG, "System.getProperty($key) intercepted -> $intercepted")
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in System.getProperty.afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook android.os.SystemProperties.get() to intercept native property queries.
     * This catches calls that bypass System.getProperty().
     */
    fun createSystemPropertiesGetHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val key = param.args[0] as? String ?: return
                    val intercepted = AntiDetection.getInterceptedSystemProperty(key)
                    if (intercepted != null) {
                        param.result = intercepted
                        RenjanaLog.v(TAG, "SystemProperties.get($key) intercepted -> $intercepted")
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in SystemProperties.get.afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook Thread.getStackTrace() to obfuscate stack traces.
     * Prevents guest apps from detecting hook frameworks via stack inspection.
     */
    fun createGetStackTraceHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val stackTrace = param.result as? Array<StackTraceElement> ?: return

                    val obfuscated = AntiDetection.obfuscateStackTrace(stackTrace)
                    param.result = obfuscated
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in getStackTrace.afterHook: ${e.message}")
                }
            }
        }
    }

    /**
     * Hook Class.forName() to block reflection on container classes.
     */
    fun createClassForNameHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val className = param.args[0] as? String ?: return
                    val blockMsg = AntiDetection.checkClassAccess(className)
                    if (blockMsg != null) {
                        param.throwable = ClassNotFoundException(blockMsg)
                    }
                } catch (e: Exception) {
                    RenjanaLog.e(TAG, "Error in Class.forName.beforeHook: ${e.message}")
                }
            }
        }
    }

    // ==================== Hook Installation API ====================

    /**
     * Register a package for hook management.
     * Called when an instance is launched.
     *
     * @param packageName Package name of the guest app
     * @param dataPath Virtual data path for this instance
     * @param instanceId Unique instance identifier
     */
    fun registerPackage(packageName: String, dataPath: String, instanceId: String) {
        packageDataPaths[packageName] = dataPath
        hookedPackages[packageName] = true
        RenjanaLog.i(TAG, "Registered package $packageName for instance $instanceId")
    }

    /**
     * Register a virtual Google account for a package.
     *
     * @param packageName Package name of the guest app
     * @param account Virtual GoogleAccount to use for sign-in
     */
    fun registerVirtualAccount(packageName: String, account: GoogleAccount) {
        virtualAccounts[packageName] = account
        RenjanaLog.i(TAG, "Registered virtual account for $packageName: ${account.email}")
    }

    /**
     * Unregister a package when its instance is stopped.
     */
    fun unregisterPackage(packageName: String) {
        packageDataPaths.remove(packageName)
        virtualAccounts.remove(packageName)
        hookedPackages.remove(packageName)
        RenjanaLog.d(TAG, "Unregistered package $packageName")
    }

    /**
     * Check if a package is currently managed by hooks.
     */
    fun isPackageHooked(packageName: String): Boolean {
        return hookedPackages.containsKey(packageName)
    }

    // ==================== Internal Helpers ====================

    /**
     * Extract instance ID from a data path.
     * Path format: /data/data/com.renjana.container/files/<instanceId>/data
     */
    private fun extractInstanceId(dataPath: String): String {
        val parts = dataPath.split("/")
        val filesIdx = parts.indexOf("files")
        return if (filesIdx >= 0 && filesIdx + 1 < parts.size) {
            parts[filesIdx + 1]
        } else {
            dataPath.hashCode().toString()
        }
    }

    /**
     * Find the package name of the code calling the current hook.
     * Uses stack trace analysis to identify the guest app.
     */
    private fun findRequestingPackage(): String? {
        val stackTrace = Thread.currentThread().stackTrace
        for (element in stackTrace) {
            val instanceId = currentInstanceId.get()
            if (instanceId != null) {
                // Find which registered package matches the current instance
                for ((pkg, path) in packageDataPaths) {
                    if (path.contains(instanceId)) {
                        return pkg
                    }
                }
            }
        }
        return null
    }

    /**
     * Get or create a guest ClassLoader for the specified package.
     * The guest ClassLoader loads the guest app's DEX files.
     */
    private fun getGuestClassLoader(packageName: String, dataPath: String): ClassLoader? {
        try {
            val dexPath = File(dataPath, "dex")
            if (!dexPath.exists() || !dexPath.isDirectory) {
                RenjanaLog.w(TAG, "No DEX directory found at $dexPath for $packageName")
                return null
            }

            val dexFiles = dexPath.listFiles { file -> file.extension == "dex" }
            if (dexFiles == null || dexFiles.isEmpty()) {
                RenjanaLog.w(TAG, "No DEX files found in $dexPath for $packageName")
                return null
            }

            val dexPathStr = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
            val optimizedDir = File(dataPath, "dex_opt")
            if (!optimizedDir.exists()) {
                optimizedDir.mkdirs()
            }

            val loader = dalvik.system.DexClassLoader(
                dexPathStr,
                optimizedDir.absolutePath,
                null, // no native lib path
                ClassLoader.getSystemClassLoader()
            )

            RenjanaLog.d(TAG, "Created guest ClassLoader for $packageName with ${dexFiles.size} DEX file(s)")
            return loader
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to create guest ClassLoader for $packageName: ${e.message}")
            return null
        }
    }

    /**
     * Inject virtual extras into the launching intent.
     * These extras provide the guest app with instance-specific metadata.
     */
    private fun injectVirtualExtras(intent: Intent, instanceId: String, packageName: String) {
        try {
            intent.putExtra("renjana.instance_id", instanceId)
            intent.putExtra("renjana.package_name", packageName)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to inject virtual extras: ${e.message}")
        }
    }

    /**
     * Apply anti-detection measures to an Activity's window.
     * Sets FLAG_SECURE if the instance has anti-detection enabled.
     */
    private fun applyWindowAntiDetection(activity: Activity) {
        try {
            @Suppress("UNUSED_VARIABLE")
            val instanceId = currentInstanceId.get() ?: return
            // Set FLAG_SECURE to prevent screenshots and screen recording
            // of the container's internal state
            activity.window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to apply window anti-detection: ${e.message}")
        }
    }

    /**
     * Override SharedPreferences directory path in a ContextImpl.
     */
    private fun overrideSharedPrefsPath(context: Context, dataPath: String, @Suppress("UNUSED_PARAMETER") instanceId: String) {
        try {
            val sharedPrefsDir = File(dataPath, "shared_prefs")
            if (!sharedPrefsDir.exists()) {
                sharedPrefsDir.mkdirs()
            }

            // Try to override the mPreferencesDir field
            val prefsDirField = try {
                context.javaClass.getDeclaredField("mPreferencesDir")
            } catch (e: NoSuchFieldException) {
                try {
                    context.javaClass.superclass.getDeclaredField("mPreferencesDir")
                } catch (e2: NoSuchFieldException) {
                    null
                }
            }

            if (prefsDirField != null) {
                prefsDirField.isAccessible = true
                prefsDirField.set(context, sharedPrefsDir)
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to override shared prefs path: ${e.message}")
        }
    }

    /**
     * Replace a field value on a GoogleSignInAccount via reflection.
     */
    private fun replaceAccountField(account: Any, fieldName: String, value: String?) {
        try {
            val field = try {
                account.javaClass.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                // Try superclass
                account.javaClass.superclass?.getDeclaredField(fieldName)
                    ?: throw e
            }
            field.isAccessible = true
            field.set(account, value)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to replace field $fieldName: ${e.message}")
        }
    }

    /**
     * Create a spoofed GoogleSignInTask result from a virtual account.
     * Returns a Task<GoogleSignInAccount> that resolves to our virtual account.
     */
    private fun createSpoofedSignInResult(account: GoogleAccount, @Suppress("UNUSED_PARAMETER") instanceId: String): Any? {
        try {
            // Build a GoogleSignInAccount from our virtual account
            val accountClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInAccount")
            val builderMethod = accountClass.getMethod(
                "createDefault",
                String::class.java // email
            )

            // Try to create account with full details via reflection
            @Suppress("UNUSED_VARIABLE")
            val emailField = try {
                accountClass.getDeclaredField("mEmail")
            } catch (e: Exception) { null }

            val accountInstance = builderMethod.invoke(null, account.email)

            // Set additional fields
            if (accountInstance != null) {
                replaceAccountField(accountInstance, "mEmail", account.email)
                replaceAccountField(accountInstance, "mDisplayName", account.displayName)
                replaceAccountField(accountInstance, "mIdToken", account.idToken)
            }

            return accountInstance
        } catch (e: ClassNotFoundException) {
            RenjanaLog.w(TAG, "GoogleSignInAccount class not found in guest app")
            return null
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to create spoofed sign-in result: ${e.message}")
            return null
        }
    }

    /**
     * Reset all hook state. Called on container shutdown.
     */
    fun reset() {
        currentInstanceId.remove()
        packageDataPaths.clear()
        virtualAccounts.clear()
        hookedPackages.clear()
        RenjanaLog.i(TAG, "CoreHooks state reset")
    }
}
