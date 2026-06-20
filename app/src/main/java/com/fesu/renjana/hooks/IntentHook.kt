package com.fesu.renjana.hooks

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.IntentRouter
import com.fesu.renjana.utils.IntentUtils
import com.fesu.renjana.utils.RenjanaLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Hook implementations that intercept all Intent operations from guest apps
 * and redirect them through IntentRouter.
 *
 * Hooks are installed using either Xposed (root mode) or Pine (non-root mode)
 * via reflection to avoid compile-time dependencies.
 *
 * Hooked operations:
 * - Activity.startActivity() / startActivityForResult()
 * - Context.sendBroadcast() / sendOrderedBroadcast()
 * - Context.startService() / bindService()
 * - PendingIntent.getActivity() / getBroadcast() / getService()
 */
object IntentHook {

    private const val TAG = "IntentHook"
    private const val CONTAINER_PACKAGE = "com.renjana.container"

    /**
     * Tracks which hooks have been installed to avoid duplicates.
     */
    private val installedHooks = ConcurrentHashMap<String, Boolean>()

    /**
     * ThreadLocal tracking the current instance ID for hook context.
     */
    private val currentInstanceId = ThreadLocal<String?>()

    /**
     * Reference to the IntentRouter singleton.
     */
    private var intentRouter: IntentRouter? = null

    // ==================== Installation ====================

    /**
     * Install all Intent hooks for a guest app.
     *
     * @param instanceId The virtual instance ID
     * @param classLoader The guest app's ClassLoader (for Xposed/Pine)
     * @param router The IntentRouter to use for routing
     */
    fun installHooks(
        instanceId: String,
        classLoader: ClassLoader,
        router: IntentRouter
    ) {
        intentRouter = router

        RenjanaLog.i(TAG, "Installing Intent hooks for instance=$instanceId")

        try {
            hookActivityStartActivity(instanceId, classLoader)
            hookActivityStartActivityForResult(instanceId, classLoader)
            hookContextSendBroadcast(instanceId, classLoader)
            hookContextSendOrderedBroadcast(instanceId, classLoader)
            hookContextStartService(instanceId, classLoader)
            hookContextBindService(instanceId, classLoader)
            hookPendingIntentGetActivity(instanceId, classLoader)
            hookPendingIntentGetBroadcast(instanceId, classLoader)
            hookPendingIntentGetService(instanceId, classLoader)

            RenjanaLog.i(TAG, "All Intent hooks installed successfully")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to install Intent hooks: ${e.message}", e)
        }
    }

    /**
     * Set the current instance ID for the calling thread.
     * Called by WrapperActivity before invoking guest lifecycle methods.
     */
    fun setCurrentInstance(instanceId: String?) {
        currentInstanceId.set(instanceId)
        CoreHooks.currentInstanceId.set(instanceId)
    }

    // ==================== Activity.startActivity() ====================

    /**
     * Hook Activity.startActivity(Intent) to intercept Activity launches.
     */
    private fun hookActivityStartActivity(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "Activity.startActivity_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val activityClass = classLoader.loadClass("android.app.Activity")
            val startActivityMethod = activityClass.getDeclaredMethod("startActivity", Intent::class.java)

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleStartActivity(param, instanceId)
                }
            }

            XposedHelpers.findAndHookMethod(
                activityClass,
                "startActivity",
                Intent::class.java,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked Activity.startActivity(Intent)")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to hook Activity.startActivity: ${e.message}")
        }
    }

    /**
     * Handle intercepted Activity.startActivity() call.
     */
    private fun handleStartActivity(param: XC_MethodHook.MethodHookParam, instanceId: String) {
        val activity = param.thisObject as? Activity ?: return
        val intent = param.args[0] as? Intent ?: return

        // Skip if this is the container app itself
        if (activity.packageName == CONTAINER_PACKAGE) return

        val sourceInstanceId = currentInstanceId.get() ?: instanceId
        RenjanaLog.d(TAG, "Intercepted startActivity: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val result = router.route(sourceInstanceId, intent, IntentRouter.OperationType.ACTIVITY)

        // Replace the Intent with the routed version
        param.args[0] = result.routedIntent

        RenjanaLog.d(TAG, "Routed to strategy=${result.strategy}: ${result.reason}")
    }

    // ==================== Activity.startActivityForResult() ====================

    /**
     * Hook Activity.startActivityForResult(Intent, int) to intercept Activity launches with result.
     */
    private fun hookActivityStartActivityForResult(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "Activity.startActivityForResult_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val activityClass = classLoader.loadClass("android.app.Activity")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleStartActivityForResult(param, instanceId)
                }
            }

            XposedHelpers.findAndHookMethod(
                activityClass,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked Activity.startActivityForResult(Intent, int)")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to hook Activity.startActivityForResult: ${e.message}")
        }
    }

    /**
     * Handle intercepted Activity.startActivityForResult() call.
     */
    private fun handleStartActivityForResult(
        param: XC_MethodHook.MethodHookParam,
        instanceId: String
    ) {
        val activity = param.thisObject as? Activity ?: return
        val intent = param.args[0] as? Intent ?: return
        val requestCode = param.args[1] as Int

        if (activity.packageName == CONTAINER_PACKAGE) return

        val sourceInstanceId = currentInstanceId.get() ?: instanceId
        RenjanaLog.d(TAG, "Intercepted startActivityForResult: requestCode=$requestCode ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val result = router.route(sourceInstanceId, intent, IntentRouter.OperationType.ACTIVITY)

        param.args[0] = result.routedIntent

        RenjanaLog.d(TAG, "Routed startActivityForResult: strategy=${result.strategy}")
    }

    // ==================== Context.sendBroadcast() ====================

    /**
     * Hook Context.sendBroadcast(Intent) to intercept broadcast sends.
     */
    private fun hookContextSendBroadcast(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "Context.sendBroadcast_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val contextClass = classLoader.loadClass("android.content.Context")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleSendBroadcast(param, instanceId)
                }
            }

            XposedHelpers.findAndHookMethod(
                contextClass,
                "sendBroadcast",
                Intent::class.java,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked Context.sendBroadcast(Intent)")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to hook Context.sendBroadcast: ${e.message}")
        }
    }

    /**
     * Handle intercepted Context.sendBroadcast() call.
     */
    private fun handleSendBroadcast(param: XC_MethodHook.MethodHookParam, instanceId: String) {
        val context = param.thisObject as? Context ?: return
        val intent = param.args[0] as? Intent ?: return

        if (context.packageName == CONTAINER_PACKAGE) return

        val sourceInstanceId = currentInstanceId.get() ?: instanceId
        RenjanaLog.d(TAG, "Intercepted sendBroadcast: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val result = router.route(sourceInstanceId, intent, IntentRouter.OperationType.BROADCAST)

        param.args[0] = result.routedIntent

        RenjanaLog.d(TAG, "Routed broadcast: strategy=${result.strategy}")
    }

    // ==================== Context.sendOrderedBroadcast() ====================

    /**
     * Hook Context.sendOrderedBroadcast() to intercept ordered broadcast sends.
     */
    private fun hookContextSendOrderedBroadcast(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "Context.sendOrderedBroadcast_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val contextClass = classLoader.loadClass("android.content.Context")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleSendOrderedBroadcast(param, instanceId)
                }
            }

            // sendOrderedBroadcast(Intent, String, BroadcastReceiver, Handler, int, String, Bundle)
            XposedHelpers.findAndHookMethod(
                contextClass,
                "sendOrderedBroadcast",
                Intent::class.java,
                String::class.java,
                BroadcastReceiver::class.java,
                android.os.Handler::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Bundle::class.java,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked Context.sendOrderedBroadcast()")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to hook sendOrderedBroadcast: ${e.message}")
        }
    }

    /**
     * Handle intercepted Context.sendOrderedBroadcast() call.
     */
    private fun handleSendOrderedBroadcast(
        param: XC_MethodHook.MethodHookParam,
        instanceId: String
    ) {
        val context = param.thisObject as? Context ?: return
        val intent = param.args[0] as? Intent ?: return

        if (context.packageName == CONTAINER_PACKAGE) return

        val sourceInstanceId = currentInstanceId.get() ?: instanceId
        RenjanaLog.d(TAG, "Intercepted sendOrderedBroadcast: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val result = router.route(sourceInstanceId, intent, IntentRouter.OperationType.BROADCAST)

        param.args[0] = result.routedIntent

        RenjanaLog.d(TAG, "Routed ordered broadcast: strategy=${result.strategy}")
    }

    // ==================== Context.startService() ====================

    /**
     * Hook Context.startService(Intent) to intercept service starts.
     */
    private fun hookContextStartService(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "Context.startService_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val contextClass = classLoader.loadClass("android.content.Context")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleStartService(param, instanceId)
                }
            }

            XposedHelpers.findAndHookMethod(
                contextClass,
                "startService",
                Intent::class.java,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked Context.startService(Intent)")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to hook Context.startService: ${e.message}")
        }
    }

    /**
     * Handle intercepted Context.startService() call.
     */
    private fun handleStartService(param: XC_MethodHook.MethodHookParam, instanceId: String) {
        val context = param.thisObject as? Context ?: return
        val intent = param.args[0] as? Intent ?: return

        if (context.packageName == CONTAINER_PACKAGE) return

        val sourceInstanceId = currentInstanceId.get() ?: instanceId
        RenjanaLog.d(TAG, "Intercepted startService: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val result = router.route(sourceInstanceId, intent, IntentRouter.OperationType.SERVICE)

        param.args[0] = result.routedIntent

        RenjanaLog.d(TAG, "Routed service: strategy=${result.strategy}")
    }

    // ==================== Context.bindService() ====================

    /**
     * Hook Context.bindService() to intercept service binding.
     */
    private fun hookContextBindService(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "Context.bindService_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val contextClass = classLoader.loadClass("android.content.Context")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleBindService(param, instanceId)
                }
            }

            XposedHelpers.findAndHookMethod(
                contextClass,
                "bindService",
                Intent::class.java,
                android.content.ServiceConnection::class.java,
                Int::class.javaPrimitiveType,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked Context.bindService()")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to hook bindService: ${e.message}")
        }
    }

    /**
     * Handle intercepted Context.bindService() call.
     */
    private fun handleBindService(param: XC_MethodHook.MethodHookParam, instanceId: String) {
        val context = param.thisObject as? Context ?: return
        val intent = param.args[0] as? Intent ?: return

        if (context.packageName == CONTAINER_PACKAGE) return

        val sourceInstanceId = currentInstanceId.get() ?: instanceId
        RenjanaLog.d(TAG, "Intercepted bindService: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val result = router.route(sourceInstanceId, intent, IntentRouter.OperationType.SERVICE)

        param.args[0] = result.routedIntent

        RenjanaLog.d(TAG, "Routed bindService: strategy=${result.strategy}")
    }

    // ==================== PendingIntent.getActivity() ====================

    /**
     * Hook PendingIntent.getActivity() to intercept PendingIntent creation.
     */
    private fun hookPendingIntentGetActivity(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "PendingIntent.getActivity_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val pendingIntentClass = classLoader.loadClass("android.app.PendingIntent")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handlePendingIntentGetActivity(param, instanceId)
                }
            }

            // PendingIntent.getActivity(Context, int, Intent, int)
            XposedHelpers.findAndHookMethod(
                pendingIntentClass,
                "getActivity",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked PendingIntent.getActivity()")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to hook PendingIntent.getActivity: ${e.message}")
        }
    }

    /**
     * Handle intercepted PendingIntent.getActivity() call.
     */
    private fun handlePendingIntentGetActivity(
        param: XC_MethodHook.MethodHookParam,
        instanceId: String
    ) {
        val intent = param.args[2] as? Intent ?: return
        val sourceInstanceId = currentInstanceId.get() ?: instanceId

        RenjanaLog.d(TAG, "Intercepted PendingIntent.getActivity: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val wrapped = router.wrapForPendingIntent(sourceInstanceId, intent)

        param.args[2] = wrapped

        RenjanaLog.d(TAG, "Wrapped PendingIntent for instance=$sourceInstanceId")
    }

    // ==================== PendingIntent.getBroadcast() ====================

    /**
     * Hook PendingIntent.getBroadcast() to intercept broadcast PendingIntent creation.
     */
    private fun hookPendingIntentGetBroadcast(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "PendingIntent.getBroadcast_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val pendingIntentClass = classLoader.loadClass("android.app.PendingIntent")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handlePendingIntentGetBroadcast(param, instanceId)
                }
            }

            // PendingIntent.getBroadcast(Context, int, Intent, int)
            XposedHelpers.findAndHookMethod(
                pendingIntentClass,
                "getBroadcast",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked PendingIntent.getBroadcast()")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to hook PendingIntent.getBroadcast: ${e.message}")
        }
    }

    /**
     * Handle intercepted PendingIntent.getBroadcast() call.
     */
    private fun handlePendingIntentGetBroadcast(
        param: XC_MethodHook.MethodHookParam,
        instanceId: String
    ) {
        val intent = param.args[2] as? Intent ?: return
        val sourceInstanceId = currentInstanceId.get() ?: instanceId

        RenjanaLog.d(TAG, "Intercepted PendingIntent.getBroadcast: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val wrapped = router.wrapForPendingIntent(sourceInstanceId, intent)

        param.args[2] = wrapped

        RenjanaLog.d(TAG, "Wrapped broadcast PendingIntent for instance=$sourceInstanceId")
    }

    // ==================== PendingIntent.getService() ====================

    /**
     * Hook PendingIntent.getService() to intercept service PendingIntent creation.
     */
    private fun hookPendingIntentGetService(instanceId: String, classLoader: ClassLoader) {
        val hookKey = "PendingIntent.getService_$instanceId"
        if (installedHooks[hookKey] == true) return

        try {
            val pendingIntentClass = classLoader.loadClass("android.app.PendingIntent")

            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handlePendingIntentGetService(param, instanceId)
                }
            }

            // PendingIntent.getService(Context, int, Intent, int)
            XposedHelpers.findAndHookMethod(
                pendingIntentClass,
                "getService",
                Context::class.java,
                Int::class.javaPrimitiveType,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                hook
            )

            installedHooks[hookKey] = true
            RenjanaLog.d(TAG, "Hooked PendingIntent.getService()")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to hook PendingIntent.getService: ${e.message}")
        }
    }

    /**
     * Handle intercepted PendingIntent.getService() call.
     */
    private fun handlePendingIntentGetService(
        param: XC_MethodHook.MethodHookParam,
        instanceId: String
    ) {
        val intent = param.args[2] as? Intent ?: return
        val sourceInstanceId = currentInstanceId.get() ?: instanceId

        RenjanaLog.d(TAG, "Intercepted PendingIntent.getService: ${IntentUtils.describe(intent)}")

        val router = intentRouter ?: return
        val wrapped = router.wrapForPendingIntent(sourceInstanceId, intent)

        param.args[2] = wrapped

        RenjanaLog.d(TAG, "Wrapped service PendingIntent for instance=$sourceInstanceId")
    }

    // ==================== Cleanup ====================

    /**
     * Uninstall all hooks for a specific instance.
     */
    fun uninstallHooks(instanceId: String) {
        val keysToRemove = installedHooks.keys.filter { it.endsWith("_$instanceId") }
        keysToRemove.forEach { installedHooks.remove(it) }
        RenjanaLog.i(TAG, "Uninstalled ${keysToRemove.size} hooks for instance=$instanceId")
    }

    /**
     * Reset all hook state. Called on container shutdown.
     */
    fun reset() {
        installedHooks.clear()
        currentInstanceId.remove()
        intentRouter = null
        RenjanaLog.i(TAG, "IntentHook state reset")
    }
}
