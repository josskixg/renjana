package com.fesu.renjana.hooks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.ActivityStubManager
import com.fesu.renjana.core.InstanceManager
import com.fesu.renjana.core.StubActivity
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method

/**
 * ActivityStarterHook - Intercepts guest app's startActivity() and
 * startActivityForResult() calls, redirecting them through the stub system.
 *
 * How it works:
 * 1. When a guest Activity calls startActivity(intent), the hook fires.
 * 2. We inspect the Intent's ComponentName to determine the guest target Activity.
 * 3. We allocate a free stub from ActivityStubManager.
 * 4. We rewrite the Intent to target the stub, embedding the guest class info in extras.
 * 5. The stub's onCreate loads and delegates to the guest Activity.
 *
 * This hook works in two modes:
 * - REFLECTION_MODE: Directly replaces the Activity's mStartActivityForResults field
 *   via reflection (used when Pine/Xposed hooks are not available).
 * - HOOK_MODE: Provides XC_MethodHook-compatible callbacks for use with
 *   PineHookManager or Xposed (when available).
 */
object ActivityStarterHook {

    private const val TAG = "ActivityStarterHook"
    private const val CONTAINER_PACKAGE = "com.fesu.renjana"

    /** Maps instanceId → apkPath (cached to avoid repeated DB lookups) */
    private val apkPathCache = mutableMapOf<String, String>()

    /** Maps instanceId → package name */
    private val packageCache = mutableMapOf<String, String>()

    // ──────────────────────────────────────────────
    // Public API: Install hooks
    // ──────────────────────────────────────────────

    /**
     * Install activity start hooks for a given instance.
     * Call this after the guest Activity has been loaded.
     *
     * @param hostActivity  the StubActivity hosting the guest
     * @param guestActivity the guest Activity whose startActivity to intercept
     * @param instanceId    virtual container instance ID
     */
    fun installForActivity(
        hostActivity: Activity,
        guestActivity: Activity,
        instanceId: String
    ) {
        try {
            installReflectionHook(hostActivity, guestActivity, instanceId)
            RenjanaLog.i(TAG, "Activity start hook installed for instance $instanceId")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to install activity start hook: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────
    // Core interception logic
    // ──────────────────────────────────────────────

    /**
     * The main interception point. Called whenever a guest Activity tries to
     * start another Activity.
     *
     * @param callerActivity the Activity that called startActivity (could be stub or guest)
     * @param originalIntent the Intent the guest wants to send
     * @param instanceId     the virtual instance ID
     * @param requestCode    request code for startActivityForResult, or -1 if none
     * @return true if the launch was redirected to a stub, false if it should proceed normally
     */
    fun interceptStartActivity(
        callerActivity: Activity,
        originalIntent: Intent,
        instanceId: String,
        requestCode: Int = -1
    ): Boolean {
        // Don't intercept our own container intents
        val targetComponent = originalIntent.component
        if (targetComponent != null && targetComponent.packageName == CONTAINER_PACKAGE) {
            RenjanaLog.d(TAG, "Skipping container-internal intent to ${targetComponent.className}")
            return false
        }

        // Don't intercept implicit intents (no component specified) — let Android handle them,
        // EXCEPT for deep links whose scheme belongs to a registered virtual instance.
        if (targetComponent == null) {
            val action = originalIntent.action
            val scheme = originalIntent.data?.scheme
            val isDeepLink = (action == Intent.ACTION_VIEW) && scheme != null
            if (isDeepLink) {
                // If scheme is registered to a virtual package, let the router handle it
                val router = RenjanaApplication.get().intentRouter
                if (router.hasRegisteredScheme(scheme!!)) {
                    RenjanaLog.d(TAG, "Deep link with registered scheme '$scheme' — routing through Renjana")
                    // Fall through: do NOT return false, let normal stub routing continue
                } else {
                    RenjanaLog.d(TAG, "Deep link with unknown scheme '$scheme' — passing to OS")
                    return false
                }
            } else if (action != null && action != Intent.ACTION_MAIN) {
                RenjanaLog.d(TAG, "Skipping implicit intent: action=$action")
                return false
            }
        }

        // Determine the guest target class
        val guestClassName = targetComponent?.className
            ?: originalIntent.getStringExtra(StubActivity.EXTRA_GUEST_ACTIVITY_CLASS)
            ?: run {
                RenjanaLog.w(TAG, "Cannot determine guest class from intent")
                return false
            }

        // Get the APK path for this instance
        val apkPath = getApkPath(instanceId) ?: run {
            RenjanaLog.e(TAG, "No APK path for instance $instanceId")
            return false
        }

        // Determine launch mode from the guest Activity's manifest info
        val launchMode = resolveLaunchMode(instanceId, guestClassName)

        // Build a stub intent via the manager
        val stubIntent = ActivityStubManager.buildStubIntent(
            context = callerActivity,
            instanceId = instanceId,
            guestClass = guestClassName,
            guestIntent = originalIntent,
            apkPath = apkPath,
            launchMode = launchMode
        )

        if (stubIntent == null) {
            RenjanaLog.e(TAG, "No free stub available for $guestClassName")
            return false
        }

        // Launch the stub
        if (requestCode >= 0) {
            // Find the host StubActivity to call startActivityForResult on it
            val hostStub = findHostStub(callerActivity)
            if (hostStub != null) {
                hostStub.startGuestActivityForResult(originalIntent, stubIntent, requestCode)
            } else {
                callerActivity.startActivityForResult(stubIntent, requestCode)
            }
        } else {
            callerActivity.startActivity(stubIntent)
        }

        RenjanaLog.i(
            TAG,
            "Redirected startActivity → $guestClassName via stub (instance=$instanceId, requestCode=$requestCode)"
        )
        return true
    }

    // ──────────────────────────────────────────────
    // Reflection-based hook installation
    // ──────────────────────────────────────────────

    /**
     * Install hooks by replacing the Activity's internal startActivity dispatch
     * via reflection. This is used when Pine/Xposed are not available.
     *
     * We hook Activity.startActivityForResult (which all startActivity variants funnel through)
     * by swapping the Instrumentation reference with a proxy.
     */
    private fun installReflectionHook(
        hostActivity: Activity,
        guestActivity: Activity,
        instanceId: String
    ) {
        try {
            // Replace the guest's Instrumentation with our proxy
            val instrField = Activity::class.java.getDeclaredField("mInstrumentation")
            instrField.isAccessible = true
            val originalInstrumentation = instrField.get(guestActivity) as android.app.Instrumentation

            val proxyInstrumentation = ProxyInstrumentation(
                delegate = originalInstrumentation,
                guestActivity = guestActivity,
                hostActivity = hostActivity,
                instanceId = instanceId
            )

            instrField.set(guestActivity, proxyInstrumentation)
            RenjanaLog.d(TAG, "Replaced Instrumentation for guest ${guestActivity.javaClass.name}")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Reflection hook installation failed: ${e.message}", e)
        }
    }

    // ──────────────────────────────────────────────
    // Instrumentation proxy
    // ──────────────────────────────────────────────

    /**
     * A proxy Instrumentation that intercepts execStartActivity calls from the guest.
     *
     * The Android framework calls Instrumentation.execStartActivity() whenever an Activity
     * calls startActivity() or startActivityForResult(). By replacing the guest's
     * Instrumentation with this proxy, we can intercept and redirect those calls.
     */
    class ProxyInstrumentation(
        private val delegate: android.app.Instrumentation,
        private val guestActivity: Activity,
        private val hostActivity: Activity,
        private val instanceId: String
    ) : android.app.Instrumentation() {

        companion object {
            private const val TAG = "ProxyInstrumentation"
        }

        /**
         * Intercept execStartActivity with the most common signature (API 26+):
         * execStartActivity(Context who, IBinder contextThread, IBinder token,
         *                   Activity target, Intent intent, int requestCode, Bundle options)
         */
        fun execStartActivity(
            who: Context,
            contextThread: android.os.IBinder?,
            token: android.os.IBinder?,
            target: Activity?,
            intent: Intent,
            requestCode: Int,
            options: Bundle?
        ): android.app.Instrumentation.ActivityResult? {
            RenjanaLog.d(TAG, "Intercepted execStartActivity: ${intent.component?.className}, rc=$requestCode")

            val intercepted = interceptStartActivity(
                callerActivity = target ?: hostActivity,
                originalIntent = intent,
                instanceId = instanceId,
                requestCode = requestCode
            )

            if (intercepted) {
                // We handled the launch ourselves — return a dummy result
                // (real result will arrive via onActivityResult on the stub)
                return null
            }

            // Not intercepted — delegate to the real Instrumentation
            return try {
                val method = delegate.javaClass.getDeclaredMethod(
                    "execStartActivity",
                    Context::class.java,
                    android.os.IBinder::class.java,
                    android.os.IBinder::class.java,
                    Activity::class.java,
                    Intent::class.java,
                    Int::class.javaPrimitiveType,
                    Bundle::class.java
                )
                method.isAccessible = true
                method.invoke(delegate, who, contextThread, token, target, intent, requestCode, options)
                        as? android.app.Instrumentation.ActivityResult
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Delegate execStartActivity failed: ${e.message}")
                null
            }
        }

        /**
         * Fallback for older API signature without Bundle options.
         */
        fun execStartActivity(
            who: Context,
            contextThread: android.os.IBinder?,
            token: android.os.IBinder?,
            target: Activity?,
            intent: Intent,
            requestCode: Int
        ): android.app.Instrumentation.ActivityResult? {
            return execStartActivity(who, contextThread, token, target, intent, requestCode, null)
        }
    }

    // ──────────────────────────────────────────────
    // Helper: resolve launch mode from guest APK
    // ──────────────────────────────────────────────

    /**
     * Determine the launch mode for a guest Activity class.
     * Returns one of ActivityStubManager.LAUNCH_* constants.
     *
     * We try to read this from the guest APK's AndroidManifest via PackageManager,
     * falling back to LAUNCH_STANDARD if unavailable.
     */
    private fun resolveLaunchMode(instanceId: String, guestClassName: String): Int {
        return try {
            val packageName = packageCache[instanceId] ?: return ActivityStubManager.LAUNCH_STANDARD
            val context = RenjanaApplication.get().applicationContext
            val pm = context.packageManager
            // We cannot use the system PM for the guest's manifest, so check cached info
            // In a full implementation, the ApkLoader would parse the manifest.
            // For now, return STANDARD (the hook will handle singleTop via stack inspection).
            ActivityStubManager.LAUNCH_STANDARD
        } catch (e: Exception) {
            ActivityStubManager.LAUNCH_STANDARD
        }
    }

    // ──────────────────────────────────────────────
    // Helper: get APK path with caching
    // ──────────────────────────────────────────────

    private fun getApkPath(instanceId: String): String? {
        apkPathCache[instanceId]?.let { return it }

        return try {
            val instance: Instance? = runBlocking {
                RenjanaApplication.get().instanceManager.getInstanceById(instanceId)
            }
            instance?.apkPath?.also {
                apkPathCache[instanceId] = it
                packageCache[instanceId] = instance.packageName
            }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to get APK path for instance $instanceId: ${e.message}")
            null
        }
    }

    // ──────────────────────────────────────────────
    // Helper: find the host StubActivity
    // ──────────────────────────────────────────────

    /**
     * Walk up the Activity hierarchy to find the StubActivity host.
     * Returns null if the caller is not inside a StubActivity.
     */
    private fun findHostStub(activity: Activity): StubActivity? {
        var ctx: Context? = activity
        while (ctx != null) {
            if (ctx is StubActivity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return null
    }

    // ──────────────────────────────────────────────
    // Public API: convenience methods
    // ──────────────────────────────────────────────

    /**
     * Start a guest Activity from outside the guest's context.
     * Used by InstanceLauncher to start the initial Activity.
     *
     * @param context     any Context (Application or Activity)
     * @param instanceId  virtual instance ID
     * @param guestClass  fully-qualified guest Activity class name
     * @param apkPath     path to the guest APK
     * @param extras      optional Intent extras for the guest Activity
     */
    fun startGuestActivity(
        context: Context,
        instanceId: String,
        guestClass: String,
        apkPath: String,
        extras: Bundle? = null
    ): Boolean {
        val guestIntent = Intent().apply {
            extras?.let { putExtras(it) }
        }

        val stubIntent = ActivityStubManager.buildStubIntent(
            context = context,
            instanceId = instanceId,
            guestClass = guestClass,
            guestIntent = guestIntent,
            apkPath = apkPath,
            launchMode = ActivityStubManager.LAUNCH_STANDARD
        ) ?: return false

        stubIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(stubIntent)
        return true
    }

    /**
     * Pre-cache the APK path and package name for an instance.
     * Should be called when an instance is launched to avoid repeated DB lookups.
     */
    fun cacheInstanceInfo(instanceId: String, apkPath: String, packageName: String) {
        apkPathCache[instanceId] = apkPath
        packageCache[instanceId] = packageName
    }

    /**
     * Clear cached info for an instance. Called when an instance is stopped.
     */
    fun clearInstanceCache(instanceId: String) {
        apkPathCache.remove(instanceId)
        packageCache.remove(instanceId)
        ActivityStubManager.clearInstance(instanceId)
    }

    /**
     * Reset all hook state. Called on application shutdown.
     */
    fun reset() {
        apkPathCache.clear()
        packageCache.clear()
        ActivityStubManager.reset()
        RenjanaLog.i(TAG, "ActivityStarterHook reset")
    }
}
