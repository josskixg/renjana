package com.fesu.renjana.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.WindowManager
import com.fesu.renjana.hooks.CoreHooks
import com.fesu.renjana.hooks.PineHookManager
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.virtual.VirtualContext
import java.lang.reflect.Method

/**
 * StubActivity - Transparent proxy Activity that delegates to a guest Activity.
 *
 * The Android OS manages this as a real Activity, but internally it:
 * 1. Reads guest activity class info from the launching Intent
 * 2. Loads the guest Activity via VirtualClassLoader
 * 3. Delegates ALL lifecycle, input, and callback methods to the guest
 *
 * 10 instances (StubActivity_0..StubActivity_9) are registered in the manifest,
 * allowing the container to run multiple guest Activities simultaneously.
 * Each instance is managed by [ActivityStubManager].
 */
abstract class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"

        /** Intent extra: fully-qualified guest Activity class name */
        const val EXTRA_GUEST_ACTIVITY_CLASS = "stub_guest_class"

        /** Intent extra: container instance ID this stub belongs to */
        const val EXTRA_INSTANCE_ID = "stub_instance_id"

        /** Intent extra: APK path for the guest app */
        const val EXTRA_APK_PATH = "stub_apk_path"

        /** Intent extra: original Intent the guest wanted to receive */
        const val EXTRA_GUEST_ORIGINAL_INTENT = "stub_guest_intent"

        /** Intent extra: launch mode override (standard=0, singleTop=1, singleTask=2, singleInstance=3) */
        const val EXTRA_LAUNCH_MODE = "stub_launch_mode"

        /** Intent extra: request code for startActivityForResult forwarding */
        const val EXTRA_REQUEST_CODE = "stub_request_code"

        /** Intent extra: stub index (0-9) */
        const val EXTRA_STUB_INDEX = "stub_index"
    }

    /** Each concrete stub must return its index (0-9) */
    abstract fun getStubIndex(): Int

    private var guestActivity: Activity? = null
    private var virtualClassLoader: VirtualClassLoader? = null
    private var guestClassName: String = ""
    private var instanceId: String = ""

    // Cached reflection references for guest lifecycle methods
    private var onCreateMethod: Method? = null
    private var onStartMethod: Method? = null
    private var onResumeMethod: Method? = null
    private var onPauseMethod: Method? = null
    private var onStopMethod: Method? = null
    private var onDestroyMethod: Method? = null
    private var onRestartMethod: Method? = null
    private var onNewIntentMethod: Method? = null
    private var onActivityResultMethod: Method? = null
    private var onSaveInstanceStateMethod: Method? = null
    private var onRestoreInstanceStateMethod: Method? = null
    private var onBackPressedMethod: Method? = null
    private var onKeyDownMethod: Method? = null
    private var onKeyUpMethod: Method? = null
    private var onTouchEventMethod: Method? = null
    private var onCreateOptionsMenuMethod: Method? = null
    private var onOptionsItemSelectedMethod: Method? = null
    private var onRequestPermissionsResultMethod: Method? = null
    private var onConfigurationChangedMethod: Method? = null
    private var onWindowFocusChangedMethod: Method? = null

    // ──────────────────────────────────────────────
    // Lifecycle: attachBaseContext
    // ──────────────────────────────────────────────

    /**
     * Wrap the base Context with a [VirtualContext] that redirects file/data
     * operations to the instance's isolated data path.
     *
     * Called by the Android framework before [onCreate]. The launching Intent
     * (set by [ActivityStubManager]) is already available here, so we extract
     * the instance ID and resolve the instance to obtain its dataPath.
     *
     * If the instance ID is missing or the instance cannot be resolved, we fall
     * back to the un-wrapped base context (the stub will finish() in onCreate).
     */
    override fun attachBaseContext(newBase: Context?) {
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceId != null && newBase != null) {
            // Resolve dataPath from the in-memory cache populated by InstanceLauncher.
            // This avoids runBlocking on the main thread (ANR risk). Falls back to the
            // un-wrapped base context if the instance is not cached (e.g. process restart).
            val dataPath = ActivityStubManager.getDataPathForInstance(instanceId)
            if (dataPath != null) {
                try {
                    super.attachBaseContext(VirtualContext(newBase, dataPath))
                    RenjanaLog.d(TAG, "attachBaseContext: wrapped with VirtualContext for instance $instanceId")
                    return
                } catch (e: Exception) {
                    RenjanaLog.w(TAG, "attachBaseContext: failed to wrap context: ${e.message}")
                }
            } else {
                RenjanaLog.w(TAG, "attachBaseContext: no cached dataPath for instance $instanceId, using base context")
            }
        }
        super.attachBaseContext(newBase)
    }

    // ──────────────────────────────────────────────
    // Lifecycle: onCreate
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract stub routing info from the Intent set by ActivityStubManager
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        guestClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS).orEmpty()
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()

        if (instanceId.isEmpty() || guestClassName.isEmpty() || apkPath.isEmpty()) {
            RenjanaLog.e(TAG, "Missing required extras: instanceId=$instanceId, guest=$guestClassName, apk=$apkPath")
            finish()
            return
        }

        RenjanaLog.i(TAG, "StubActivity[${getStubIndex()}] onCreate → launching guest $guestClassName for instance $instanceId")

        // Notify the stub manager that this stub is now occupied
        ActivityStubManager.onStubOccupied(instanceId, getStubIndex(), guestClassName)

        try {
            // Resolve the instance from the in-memory cache (populated by InstanceLauncher
            // before launch) to avoid runBlocking on the main thread.
            val instance: Instance? = ActivityStubManager.getCachedInstance(instanceId)
            if (instance == null) {
                RenjanaLog.e(TAG, "Instance $instanceId not found in cache")
                finish()
                return
            }

            // Mark this instance as active on the current thread so hooks know
            // which virtual context to use (file redirects, GMS spoofing, etc.)
            CoreHooks.currentInstanceId.set(instanceId)

            // Create isolated classloader for this instance
            val optimizedDir = java.io.File(instance.dataPath, "dex_opt")
            virtualClassLoader = VirtualClassLoader(
                apkPath = apkPath,
                instanceId = instanceId,
                optimizedDir = optimizedDir,
                parent = classLoader
            )

            // Verify Pine guest hooks are installed (InstanceLauncher should have
            // done this before launch, but this is idempotent and acts as a safety net)
            if (PineHookManager.isAvailable()) {
                val hooksOk = PineHookManager.installGuestHooks(
                    instance.packageName,
                    virtualClassLoader!!,
                    instanceId,
                    instance.dataPath,
                    apkPath,
                    instance = instance
                )
                if (!hooksOk) {
                    RenjanaLog.w(TAG, "Pine guest hooks not installed for ${instance.packageName}, isolation may be incomplete")
                }
            } else {
                RenjanaLog.w(TAG, "Pine unavailable — guest running without hook-based isolation")
            }

            // Load and instantiate guest Activity
            val guestClass = virtualClassLoader!!.loadGuestClass(guestClassName)
            guestActivity = guestClass.getDeclaredConstructor().newInstance() as Activity

            // Cache lifecycle method references for performance
            cacheLifecycleMethods(guestClass)

            // Attach guest to this stub: set base context so guest can call getResources(), etc.
            attachGuestToHost()

            // Forward guest's original Intent if present
            val guestIntent = intent.getParcelableExtra<Intent>(EXTRA_GUEST_ORIGINAL_INTENT)
            if (guestIntent != null) {
                setGuestIntent(guestIntent)
            }

            // Delegate onCreate to guest
            onCreateMethod?.invoke(guestActivity, savedInstanceState)
                ?: RenjanaLog.w(TAG, "Guest $guestClassName has no onCreate method")

        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to launch guest $guestClassName: ${e.message}", e)
            finish()
        }
    }

    /**
     * Use reflection to call Activity.attach()-like setup on the guest so it
     * has a valid Context, Window, and Resources from this host.
     */
    private fun attachGuestToHost() {
        val guest = guestActivity ?: return
        try {
            // Skip complex Activity.attach() call - use simpler field injection approach
            // The attach() method signature varies by API level and uses hidden APIs
            injectGuestContext(guest)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest attach failed (non-fatal): ${e.message}")
            finish()
            return
        }
    }

    /**
     * Inject essential Context fields into the guest Activity so it can
     * function (getResources, getAssets, getClassLoader, etc.).
     */
    private fun injectGuestContext(guest: Activity) {
        try {
            // Set mBase on ContextWrapper
            val baseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase")
            baseField.isAccessible = true
            baseField.set(guest, this.baseContext)

            // Set mApplication
            val appField = Activity::class.java.getDeclaredField("mApplication")
            appField.isAccessible = true
            appField.set(guest, application)

            // Set mComponent
            val componentField = Activity::class.java.getDeclaredField("mComponent")
            componentField.isAccessible = true
            val cn = android.content.ComponentName(packageName, guestClassName)
            componentField.set(guest, cn)

            // Set mInstrumentation to a no-op that doesn't interfere
            val instrField = Activity::class.java.getDeclaredField("mInstrumentation")
            instrField.isAccessible = true
            instrField.set(guest, android.app.Instrumentation())

            // FIX 1: Inject mMainThread from host so guest runs on the correct ActivityThread
            try {
                val mainThreadField = Activity::class.java.getDeclaredField("mMainThread")
                mainThreadField.isAccessible = true
                mainThreadField.set(guest, mainThreadField.get(this))
                RenjanaLog.d(TAG, "mMainThread injected into guest")
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Could not inject mMainThread: ${e.message}")
            }

            // FIX 2: Inject mUiThread so guest's runOnUiThread() works correctly
            try {
                val uiThreadField = Activity::class.java.getDeclaredField("mUiThread")
                uiThreadField.isAccessible = true
                uiThreadField.set(guest, Thread.currentThread())
                RenjanaLog.d(TAG, "mUiThread injected into guest")
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Could not inject mUiThread: ${e.message}")
            }

            // FIX 5: Set mToken — copy FROM host (this) TO guest (was a no-op self-copy before)
            try {
                val tokenField = Activity::class.java.getDeclaredField("mToken")
                tokenField.isAccessible = true
                tokenField.set(guest, tokenField.get(this))
                RenjanaLog.d(TAG, "mToken injected into guest")
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Could not inject mToken: ${e.message}")
            }

            // Set mWindowManager
            val wmField = Activity::class.java.getDeclaredField("mWindowManager")
            wmField.isAccessible = true
            wmField.set(guest, windowManager)

            // FIX 3: Create a new PhoneWindow for the guest instead of sharing the host's.
            // Sharing causes DecorView to be built with the host context, which makes
            // AppCompatDelegateImpl.createSubDecor() throw "View must not be null".
            try {
                val phoneWindowClass = Class.forName("com.android.internal.policy.PhoneWindow")
                val ctor = phoneWindowClass.getDeclaredConstructor(android.content.Context::class.java)
                ctor.isAccessible = true
                val guestWindow = ctor.newInstance(guest) as android.view.Window
                // Set windowManager via reflection — Window.windowManager is a val
                try {
                    val wmField = android.view.Window::class.java.getDeclaredField("mWindowManager")
                    wmField.isAccessible = true
                    wmField.set(guestWindow, windowManager)
                } catch (wmEx: Exception) {
                    RenjanaLog.w(TAG, "Could not set windowManager on guest PhoneWindow: ${wmEx.message}")
                }
                val windowField = Activity::class.java.getDeclaredField("mWindow")
                windowField.isAccessible = true
                windowField.set(guest, guestWindow)
                RenjanaLog.d(TAG, "New PhoneWindow created for guest")
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Could not create guest PhoneWindow, sharing host window: ${e.message}")
                // Fall back to sharing host window
                val windowField = Activity::class.java.getDeclaredField("mWindow")
                windowField.isAccessible = true
                windowField.set(guest, window)
            }

            // Set mCalled = true so the guest doesn't throw SuperNotCalledException
            val calledField = Activity::class.java.getDeclaredField("mCalled")
            calledField.isAccessible = true
            calledField.set(guest, true)

            RenjanaLog.d(TAG, "Guest context injection complete for $guestClassName")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest context injection partial failure: ${e.message}")
        }
    }

    /**
     * Cache all lifecycle Method references to avoid repeated reflection lookups.
     */
    private fun cacheLifecycleMethods(guestClass: Class<*>) {
        onCreateMethod = safeMethod(guestClass, "onCreate", Bundle::class.java)
        onStartMethod = safeMethod(guestClass, "onStart")
        onResumeMethod = safeMethod(guestClass, "onResume")
        onPauseMethod = safeMethod(guestClass, "onPause")
        onStopMethod = safeMethod(guestClass, "onStop")
        onDestroyMethod = safeMethod(guestClass, "onDestroy")
        onRestartMethod = safeMethod(guestClass, "onRestart")
        onNewIntentMethod = safeMethod(guestClass, "onNewIntent", Intent::class.java)
        onActivityResultMethod = safeMethod(
            guestClass, "onActivityResult",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java
        )
        onSaveInstanceStateMethod = safeMethod(guestClass, "onSaveInstanceState", Bundle::class.java)
        onRestoreInstanceStateMethod = safeMethod(guestClass, "onRestoreInstanceState", Bundle::class.java)
        onBackPressedMethod = safeMethod(guestClass, "onBackPressed")
        onKeyDownMethod = safeMethod(
            guestClass, "onKeyDown",
            Int::class.javaPrimitiveType!!, KeyEvent::class.java
        )
        onKeyUpMethod = safeMethod(
            guestClass, "onKeyUp",
            Int::class.javaPrimitiveType!!, KeyEvent::class.java
        )
        onTouchEventMethod = safeMethod(guestClass, "onTouchEvent", MotionEvent::class.java)
        onCreateOptionsMenuMethod = safeMethod(guestClass, "onCreateOptionsMenu", Menu::class.java)
        onOptionsItemSelectedMethod = safeMethod(guestClass, "onOptionsItemSelected", MenuItem::class.java)
        onRequestPermissionsResultMethod = safeMethod(
            guestClass, "onRequestPermissionsResult",
            Int::class.javaPrimitiveType!!, Array<String>::class.java, IntArray::class.java
        )
        onConfigurationChangedMethod = safeMethod(guestClass, "onConfigurationChanged", Configuration::class.java)
        onWindowFocusChangedMethod = safeMethod(
            guestClass, "onWindowFocusChanged",
            Boolean::class.javaPrimitiveType!!
        )
    }

    private fun safeMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        return try {
            val m = clazz.getDeclaredMethod(name, *params)
            m.isAccessible = true
            m
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    private fun setGuestIntent(guestIntent: Intent) {
        try {
            val intentField = Activity::class.java.getDeclaredField("mIntent")
            intentField.isAccessible = true
            intentField.set(guestActivity, guestIntent)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to set guest intent: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Lifecycle delegation
    // ──────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        try { onStartMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onStart failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Mark this instance as the currently active one
        ActivityStubManager.onStubResumed(instanceId, getStubIndex())
        try { onResumeMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onResume failed: ${e.message}")
        }
    }

    override fun onPause() {
        try { onPauseMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onPause failed: ${e.message}")
        }
        super.onPause()
    }

    override fun onStop() {
        try { onStopMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onStop failed: ${e.message}")
        }
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        try { onRestartMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRestart failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { onDestroyMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onDestroy failed: ${e.message}")
        }
        // Release this stub back to the pool
        ActivityStubManager.onStubReleased(instanceId, getStubIndex(), guestClassName)
        guestActivity = null
        virtualClassLoader = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Forward the guest's original intent from the re-launched stub
        val guestIntent = intent.getParcelableExtra<Intent>(EXTRA_GUEST_ORIGINAL_INTENT) ?: intent
        try { onNewIntentMethod?.invoke(guestActivity, guestIntent) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onNewIntent failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Activity result forwarding
    // ──────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            // Map the stub's request code back to the guest's original request code
            val originalRequestCode = ActivityStubManager.resolveRequestCode(
                instanceId, getStubIndex(), requestCode
            )
            onActivityResultMethod?.invoke(guestActivity, originalRequestCode, resultCode, data)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onActivityResult failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // State save/restore
    // ──────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        try { onSaveInstanceStateMethod?.invoke(guestActivity, outState) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onSaveInstanceState failed: ${e.message}")
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try { onRestoreInstanceStateMethod?.invoke(guestActivity, savedInstanceState) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRestoreInstanceState failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Input event delegation
    // ──────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onBackPressedMethod != null) {
            try {
                onBackPressedMethod!!.invoke(guestActivity)
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Guest onBackPressed failed: ${e.message}")
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return try {
            onKeyDownMethod?.invoke(guestActivity, keyCode, event) as? Boolean
                ?: super.onKeyDown(keyCode, event)
        } catch (e: Exception) {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return try {
            onKeyUpMethod?.invoke(guestActivity, keyCode, event) as? Boolean
                ?: super.onKeyUp(keyCode, event)
        } catch (e: Exception) {
            super.onKeyUp(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return try {
            onTouchEventMethod?.invoke(guestActivity, event) as? Boolean
                ?: super.onTouchEvent(event)
        } catch (e: Exception) {
            super.onTouchEvent(event)
        }
    }

    // ──────────────────────────────────────────────
    // Menu delegation
    // ──────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        return try {
            onCreateOptionsMenuMethod?.invoke(guestActivity, menu) as? Boolean
                ?: super.onCreateOptionsMenu(menu)
        } catch (e: Exception) {
            super.onCreateOptionsMenu(menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            onOptionsItemSelectedMethod?.invoke(guestActivity, item) as? Boolean
                ?: super.onOptionsItemSelected(item)
        } catch (e: Exception) {
            super.onOptionsItemSelected(item)
        }
    }

    // ──────────────────────────────────────────────
    // Permission result delegation
    // ──────────────────────────────────────────────

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            onRequestPermissionsResultMethod?.invoke(
                guestActivity, requestCode, permissions, grantResults
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRequestPermissionsResult failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Configuration & window
    // ──────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try { onConfigurationChangedMethod?.invoke(guestActivity, newConfig) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onConfigurationChanged failed: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try { onWindowFocusChangedMethod?.invoke(guestActivity, hasFocus) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onWindowFocusChanged failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Resource overrides (delegate to guest's ClassLoader)
    // ──────────────────────────────────────────────

    override fun getResources(): Resources {
        return virtualClassLoader?.getResources(this) ?: super.getResources()
    }

    override fun getAssets(): AssetManager {
        return virtualClassLoader?.getAssets() ?: super.getAssets()
    }

    override fun getClassLoader(): ClassLoader {
        return virtualClassLoader ?: super.getClassLoader()
    }

    // ──────────────────────────────────────────────
    // startActivityForResult interception
    // ──────────────────────────────────────────────

    /**
     * When the guest calls startActivityForResult, the ActivityStarterHook redirects
     * through the stub system. The result comes back to this stub's onActivityResult,
     * which then forwards to the guest via [onActivityResult].
     *
     * This method is called by [ActivityStarterHook] to initiate a result-bearing launch.
     */
    fun startGuestActivityForResult(guestIntent: Intent, stubIntent: Intent, requestCode: Int) {
        // Register the request code mapping so we can reverse it later
        ActivityStubManager.registerRequestCode(instanceId, getStubIndex(), requestCode, guestIntent)
        startActivityForResult(stubIntent, requestCode)
    }
}

// ──────────────────────────────────────────────
// Concrete stub Activity subclasses (registered in AndroidManifest.xml)
// Each is a distinct Android component so the OS can manage them independently.
// ──────────────────────────────────────────────

class StubActivity_0 : StubActivity() {
    override fun getStubIndex(): Int = 0
}
class StubActivity_1 : StubActivity() {
    override fun getStubIndex(): Int = 1
}
class StubActivity_2 : StubActivity() {
    override fun getStubIndex(): Int = 2
}
class StubActivity_3 : StubActivity() {
    override fun getStubIndex(): Int = 3
}
class StubActivity_4 : StubActivity() {
    override fun getStubIndex(): Int = 4
}
class StubActivity_5 : StubActivity() {
    override fun getStubIndex(): Int = 5
}
class StubActivity_6 : StubActivity() {
    override fun getStubIndex(): Int = 6
}
class StubActivity_7 : StubActivity() {
    override fun getStubIndex(): Int = 7
}
class StubActivity_8 : StubActivity() {
    override fun getStubIndex(): Int = 8
}
class StubActivity_9 : StubActivity() {
    override fun getStubIndex(): Int = 9
}
