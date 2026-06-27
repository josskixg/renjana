package com.fesu.renjana.core

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.hooks.IntentHook
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * WrapperActivity - Loads and runs guest app Activities in an isolated container.
 *
 * @deprecated Use [StubActivity] instead. WrapperActivity sets mBase to the real
 * host context, providing zero isolation. Kept as emergency fallback only.
 */
@Deprecated("Use StubActivity instead. WrapperActivity sets mBase to real host context, providing zero isolation. Kept as emergency fallback only.")
class WrapperActivity : Activity() {

    private var guestActivity: Activity? = null
    private var classLoader: VirtualClassLoader? = null
    private var guestActivityClassName: String = ""

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_GUEST_ACTIVITY_CLASS = "guest_activity_class"
        const val EXTRA_APK_PATH = "apk_path"
        private const val TAG = "WrapperActivity"
    }

    private var onCreateMethod: Method? = null
    private var onStartMethod: Method? = null
    private var onResumeMethod: Method? = null
    private var onPauseMethod: Method? = null
    private var onStopMethod: Method? = null
    private var onDestroyMethod: Method? = null
    private var onActivityResultMethod: Method? = null
    private var onNewIntentMethod: Method? = null

    /**
     * Walk up the class hierarchy to find a declared field by name.
     * getDeclaredField only searches the immediate class, not inherited fields.
     */
    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val field = c.getDeclaredField(name)
                field.isAccessible = true
                return field
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    /**
     * Walk up the class hierarchy to find a declared method by name and params.
     * getDeclaredMethod only searches the immediate class, not inherited methods.
     */
    private fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                val method = c.getDeclaredMethod(name, *paramTypes)
                method.isAccessible = true
                return method
            } catch (e: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }

    /**
     * Detect the launcher activity class name from PackageManager instead of
     * guessing packageName + ".MainActivity".
     */
    private fun detectLauncherActivity(packageName: String): String {
        try {
            val pm = packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent?.component?.className != null) {
                return launchIntent.component!!.className
            }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to detect launcher activity: ${e.message}")
        }
        return "$packageName.MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceId.isNullOrEmpty()) {
            RenjanaLog.e(TAG, "No instance ID in intent, finishing")
            finish()
            return
        }

        val instanceManager = RenjanaApplication.get().instanceManager
        val instance: Instance? = kotlinx.coroutines.runBlocking {
            instanceManager.getInstanceById(instanceId)
        }
        if (instance == null) {
            RenjanaLog.e(TAG, "Instance not found: $instanceId")
            finish()
            return
        }

        // Detect launcher activity from PackageManager
        guestActivityClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS)
            ?: detectLauncherActivity(instance.packageName)

        RenjanaLog.i(TAG, "Launching instance=$instanceId pkg=${instance.packageName} activity=$guestActivityClassName")

        try {
            val apkFile = File(instance.apkPath)
            if (!apkFile.exists()) {
                throw RuntimeException("APK not found: ${instance.apkPath}")
            }

            val optimizedDir = File(filesDir, "dex_opt_$instanceId")
            classLoader = VirtualClassLoader(
                apkPath = instance.apkPath,
                instanceId = instanceId,
                optimizedDir = optimizedDir,
                parent = ClassLoader.getSystemClassLoader()
            )

            // Load guest Activity class
            val guestClass = classLoader!!.loadGuestClass(guestActivityClassName)
            guestActivity = guestClass.getDeclaredConstructor().newInstance() as Activity

            // Cache lifecycle methods (walk hierarchy, not just declared)
            cacheLifecycleMethods(guestClass)

            // Set up container view
            val container = FrameLayout(this).apply {
                id = android.R.id.content
            }
            setContentView(container)

            // Inject context into guest Activity
            injectContext(guestActivity!!, this)

            // Register virtual package with IntentRouter
            val intentRouter = RenjanaApplication.get().intentRouter
            intentRouter.registerVirtualPackage(instance.packageName, instanceId)

            // Install Intent hooks (non-fatal if they fail — NoClassDefFoundError etc)
            try {
                IntentHook.installHooks(instanceId, classLoader!!, intentRouter)
            } catch (hookError: Throwable) {
                RenjanaLog.w(TAG, "Intent hooks failed (non-fatal): ${hookError.message}")
            }

            // Call guest Activity's onCreate via reflection
            val guestSavedState = savedInstanceState?.getBundle("GUEST_STATE")
            invokeGuestMethod(onCreateMethod, guestSavedState)

            RenjanaLog.i(TAG, "Guest activity launched successfully")

        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to launch guest activity", e)
            e.printStackTrace()
            Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun cacheLifecycleMethods(guestClass: Class<*>) {
        // Use findMethod (walks hierarchy) instead of getDeclaredMethod (immediate class only)
        onCreateMethod = findMethod(guestClass, "onCreate", Bundle::class.java)
        onStartMethod = findMethod(guestClass, "onStart")
        onResumeMethod = findMethod(guestClass, "onResume")
        onPauseMethod = findMethod(guestClass, "onPause")
        onStopMethod = findMethod(guestClass, "onStop")
        onDestroyMethod = findMethod(guestClass, "onDestroy")
        onActivityResultMethod = findMethod(
            guestClass, "onActivityResult",
            Int::class.java, Int::class.java, Intent::class.java
        )
        onNewIntentMethod = findMethod(guestClass, "onNewIntent", Intent::class.java)
    }

    private fun invokeGuestMethod(method: Method?, vararg args: Any?) {
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        try {
            IntentHook.setCurrentInstance(instanceId)
            method?.invoke(guestActivity, *args)
        } finally {
            IntentHook.setCurrentInstance(null)
        }
    }

    /**
     * Inject our Context into the guest Activity via reflection.
     * Uses findField to walk the class hierarchy since mBase is in ContextWrapper
     * (not ContextThemeWrapper which is Activity's direct superclass).
     */
    private fun injectContext(guest: Activity, wrapperContext: Activity) {
        try {
            // Set base context (mBase is in ContextWrapper, 2 levels up from Activity)
            val baseContextField = findField(guest.javaClass, "mBase")
            baseContextField?.set(guest, wrapperContext)

            // Set resources (mResources is in ContextThemeWrapper)
            val resourcesField = findField(guest.javaClass, "mResources")
            resourcesField?.set(guest, classLoader!!.getResources(wrapperContext))

            // Set application (mApplication is in Activity)
            val applicationField = findField(guest.javaClass, "mApplication")
            applicationField?.set(guest, application)

            RenjanaLog.d(TAG, "Context injected into guest activity")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to inject context: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onStart() {
        super.onStart()
        invokeGuestMethod(onStartMethod)
    }

    override fun onResume() {
        super.onResume()
        invokeGuestMethod(onResumeMethod)
        val id = getInstanceId() ?: return
        reportState { it.onInstanceResumed(id) }
    }

    override fun onPause() {
        invokeGuestMethod(onPauseMethod)
        super.onPause()
        val id = getInstanceId() ?: return
        reportState { it.onInstancePaused(id) }
    }

    override fun onStop() {
        invokeGuestMethod(onStopMethod)
        super.onStop()
    }

    override fun onDestroy() {
        val instanceId = getInstanceId()
        try {
            // Swallow any guest lifecycle exceptions (e.g. FragmentManager already destroyed)
            try {
                onDestroyMethod?.let { invokeGuestMethod(it) }
            } catch (guestError: Throwable) {
                RenjanaLog.w(TAG, "Guest onDestroy threw (non-fatal): ${guestError.message}")
            }
        } finally {
            if (instanceId != null) {
                val instanceManager = RenjanaApplication.get().instanceManager
                val instance = kotlinx.coroutines.runBlocking {
                    instanceManager.getInstanceById(instanceId)
                }
                instance?.let {
                    RenjanaApplication.get().intentRouter.unregisterVirtualPackage(it.packageName)
                }
                IntentHook.uninstallHooks(instanceId)
            }
            reportState { it.onInstanceDestroyed(instanceId ?: "") }
            super.onDestroy()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // Save guest Activity state
        try {
            val saveMethod = guestActivity?.javaClass?.getDeclaredMethod(
                "onSaveInstanceState",
                Bundle::class.java
            )
            saveMethod?.isAccessible = true
            
            val guestState = Bundle()
            val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
            try {
                IntentHook.setCurrentInstance(instanceId)
                saveMethod?.invoke(guestActivity, guestState)
            } finally {
                IntentHook.setCurrentInstance(null)
            }
            outState.putBundle("GUEST_STATE", guestState)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        invokeGuestMethod(onActivityResultMethod, requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        invokeGuestMethod(onNewIntentMethod, intent)
    }

    private fun getInstanceId(): String? = intent.getStringExtra(EXTRA_INSTANCE_ID)

    /**
     * Report lifecycle event to InstanceLifecycleService if it's running.
     */
    private fun reportState(action: (InstanceLifecycleService) -> Unit) {
        try {
            val service = RenjanaApplication.get().lifecycleService
            if (service != null) {
                action(service)
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to report state to service: ${e.message}")
        }
    }

    /**
     * Override getResources to return guest app's resources
     */
    override fun getResources(): android.content.res.Resources {
        return classLoader?.getResources(this) ?: super.getResources()
    }

    /**
     * Override getAssets to return guest app's assets
     */
    override fun getAssets(): android.content.res.AssetManager {
        return classLoader?.getAssets() ?: super.getAssets()
    }

    /**
     * Override getClassLoader to return our virtual class loader
     */
    override fun getClassLoader(): ClassLoader {
        return classLoader ?: super.getClassLoader()
    }
}
