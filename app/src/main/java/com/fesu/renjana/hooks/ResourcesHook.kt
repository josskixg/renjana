package com.fesu.renjana.hooks

import android.content.Context
import android.content.res.Resources
import com.fesu.renjana.core.ResourceManager
import com.fesu.renjana.utils.RenjanaLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * Hooks for Android resource access, redirecting resource calls from
 * guest app code to the virtual ResourceManager.
 *
 * Works with both Xposed (root) and Pine (non-root) hook frameworks.
 * Hooks are installed per-instance and removed when the instance is destroyed.
 *
 * Intercepted methods:
 * - Context.getResources() → returns virtual Resources
 * - Resources.getIdentifier() → resolves through fallback chain
 * - Resources.getString(Int) → checks overrides first
 * - Resources.getDrawable(Int, Theme) → checks overrides first
 * - Resources.getColor(Int, Theme) → checks overrides first
 * - Resources.getInteger(Int) → checks overrides first
 */
object ResourcesHook {
    private const val TAG = "ResourcesHook"
    private const val CONTAINER_PACKAGE = "com.fesu.renjana"

    /**
     * Tracks which instance ↔ classloader combinations have hooks installed
     * to avoid duplicate installation.
     */
    private val hookedClassLoaders = ConcurrentHashMap<Int, Boolean>()

    /**
     * ResourceManager reference, set during initialization.
     */
    @Volatile
    private var resourceManager: ResourceManager? = null

    /**
     * Maps guest Resources objects (by identity hash) to their instance ID.
     * This allows hooks to know which instance a Resources call belongs to.
     */
    private val resourcesToInstance = ConcurrentHashMap<Int, String>()

    // ════════════════════════════════════════════════════════════════════
    //  Initialization
    // ════════════════════════════════════════════════════════════════════

    /**
     * Initialize the hook system with a ResourceManager.
     * Must be called before installing any hooks.
     */
    fun initialize(manager: ResourceManager) {
        resourceManager = manager
        RenjanaLog.i(TAG, "ResourcesHook initialized")
    }

    /**
     * Register a guest Resources object to its instance ID.
     * Called after ResourceManager creates Resources for an instance.
     */
    fun registerResources(resources: Resources, instanceId: String) {
        resourcesToInstance[System.identityHashCode(resources)] = instanceId
    }

    /**
     * Unregister a guest Resources object.
     * Called when an instance is destroyed.
     */
    fun unregisterResources(resources: Resources) {
        resourcesToInstance.remove(System.identityHashCode(resources))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Hook Installation (Xposed mode)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Install resource hooks for a specific guest classloader (Xposed mode).
     *
     * @param classLoader Guest app's classloader
     * @param instanceId Virtual instance ID
     */
    fun installHooks(classLoader: ClassLoader, instanceId: String) {
        val clHash = System.identityHashCode(classLoader)
        if (hookedClassLoaders.containsKey(clHash)) {
            RenjanaLog.d(TAG, "Hooks already installed for classloader $clHash")
            return
        }

        try {
            installContextGetResourcesHook(classLoader, instanceId)
            installGetIdentifierHook(classLoader, instanceId)
            installGetStringHook(classLoader, instanceId)
            installGetDrawableHook(classLoader, instanceId)
            installGetColorHook(classLoader, instanceId)
            installGetIntegerHook(classLoader, instanceId)

            hookedClassLoaders[clHash] = true
            RenjanaLog.i(TAG, "Installed resource hooks for instance $instanceId")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to install resource hooks for $instanceId", e)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Hook Definitions
    // ════════════════════════════════════════════════════════════════════

    /**
     * Hook 1: Context.getResources()
     *
     * When called from guest app code, returns the virtual Resources
     * object instead of the host's Resources.
     */
    private fun installContextGetResourcesHook(classLoader: ClassLoader, instanceId: String) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val currentId = CoreHooks.currentInstanceId.get() ?: return

                // Only intercept for our instance
                if (currentId != instanceId) return

                try {
                    val manager = resourceManager ?: return
                    val context = param.thisObject as? Context ?: return

                    // Skip if this is the container's own context
                    if (context.packageName == CONTAINER_PACKAGE) return

                    // Get the virtual resources for this instance
                    val apkPath = CoreHooks.packageDataPaths[currentId] ?: return
                    val dataPath = "$apkPath/data" // derive data path
                    val virtualRes = manager.getResources(currentId, apkPath, dataPath)

                    if (virtualRes != null) {
                        param.result = virtualRes
                    }
                } catch (e: Exception) {
                    RenjanaLog.v(TAG, "Context.getResources hook: ${e.message}")
                }
            }
        }

        XposedHelpers.findAndHookMethod(
            Context::class.java,
            "getResources",
            hook
        )
    }

    /**
     * Hook 2: Resources.getIdentifier(String, String, String)
     *
     * Intercepts resource ID lookups. When guest code calls getIdentifier()
     * with a resource name, we resolve it through the virtual resources
     * and handle ID conflicts between host and guest packages.
     */
    private fun installGetIdentifierHook(classLoader: ClassLoader, instanceId: String) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val currentId = CoreHooks.currentInstanceId.get() ?: return
                if (currentId != instanceId) return

                try {
                    val resources = param.thisObject as? Resources ?: return

                    // Only intercept for guest Resources objects
                    val ownerId = resourcesToInstance[System.identityHashCode(resources)]
                    if (ownerId != currentId) return

                    val result = param.result as? Int ?: 0
                    if (result != 0) return // ID found normally, no conflict

                    // ID not found — try resolving through ResourceManager's fallback
                    val name = param.args[0] as? String ?: return
                    val defType = param.args[1] as? String
                    val defPackage = param.args[2] as? String

                    val resKey = if (defType != null) "$defType/$name" else name
                    val manager = resourceManager ?: return

                    // Try guest resources directly
                    val guestRes = manager.getResources(currentId, "", "")
                    if (guestRes != null) {
                        val guestId = guestRes.getIdentifier(name, defType, defPackage)
                        if (guestId != 0) {
                            param.result = guestId
                        }
                    }
                } catch (e: Exception) {
                    RenjanaLog.v(TAG, "getIdentifier hook: ${e.message}")
                }
            }
        }

        XposedHelpers.findAndHookMethod(
            Resources::class.java,
            "getIdentifier",
            String::class.java,
            String::class.java,
            String::class.java,
            hook
        )
    }

    /**
     * Hook 3: Resources.getString(Int)
     *
     * Intercepts string resource access to check overrides first.
     */
    private fun installGetStringHook(classLoader: ClassLoader, instanceId: String) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val currentId = CoreHooks.currentInstanceId.get() ?: return
                if (currentId != instanceId) return

                try {
                    val resources = param.thisObject as? Resources ?: return
                    val ownerId = resourcesToInstance[System.identityHashCode(resources)]
                    if (ownerId != currentId) return

                    val resId = param.args[0] as Int
                    val manager = resourceManager ?: return
                    val overrides = manager.getOverrides(currentId) ?: return

                    // Build resource key from ID
                    val resKey = try {
                        resources.getResourceEntryName(resId)
                    } catch (e: Resources.NotFoundException) {
                        null
                    } ?: return

                    val resType = try {
                        resources.getResourceTypeName(resId)
                    } catch (e: Resources.NotFoundException) {
                        null
                    }

                    val fullKey = if (resType != null) "$resType/$resKey" else resKey
                    overrides.getStringOverride(fullKey)?.let {
                        param.result = it
                    }
                } catch (e: Exception) {
                    // Let original method proceed
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getString",
                Int::class.javaPrimitiveType,
                hook
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Could not hook getString: ${e.message}")
        }
    }

    /**
     * Hook 4: Resources.getDrawable(Int, Theme)
     *
     * Intercepts drawable resource access to check overrides first.
     */
    private fun installGetDrawableHook(classLoader: ClassLoader, instanceId: String) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val currentId = CoreHooks.currentInstanceId.get() ?: return
                if (currentId != instanceId) return

                try {
                    val resources = param.thisObject as? Resources ?: return
                    val ownerId = resourcesToInstance[System.identityHashCode(resources)]
                    if (ownerId != currentId) return

                    val resId = param.args[0] as Int
                    val theme = param.args[1] as? Resources.Theme

                    val manager = resourceManager ?: return
                    val drawable = manager.resolveDrawable(currentId, resId.toString(), theme)
                    if (drawable != null) {
                        param.result = drawable
                    }
                } catch (e: Exception) {
                    // Let original method proceed
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getDrawable",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java,
                hook
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Could not hook getDrawable: ${e.message}")
        }
    }

    /**
     * Hook 5: Resources.getColor(Int, Theme)
     *
     * Intercepts color resource access to check overrides first.
     */
    private fun installGetColorHook(classLoader: ClassLoader, instanceId: String) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val currentId = CoreHooks.currentInstanceId.get() ?: return
                if (currentId != instanceId) return

                try {
                    val resources = param.thisObject as? Resources ?: return
                    val ownerId = resourcesToInstance[System.identityHashCode(resources)]
                    if (ownerId != currentId) return

                    val resId = param.args[0] as Int
                    val resKey = try {
                        val entryName = resources.getResourceEntryName(resId)
                        val typeName = resources.getResourceTypeName(resId)
                        "$typeName/$entryName"
                    } catch (e: Resources.NotFoundException) {
                        null
                    } ?: return

                    val manager = resourceManager ?: return
                    val color = manager.resolveInt(currentId, resKey)
                    if (color != null) {
                        param.result = color
                    }
                } catch (e: Exception) {
                    // Let original method proceed
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java,
                hook
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Could not hook getColor: ${e.message}")
        }
    }

    /**
     * Hook 6: Resources.getInteger(Int)
     *
     * Intercepts integer resource access to check overrides first.
     */
    private fun installGetIntegerHook(classLoader: ClassLoader, instanceId: String) {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val currentId = CoreHooks.currentInstanceId.get() ?: return
                if (currentId != instanceId) return

                try {
                    val resources = param.thisObject as? Resources ?: return
                    val ownerId = resourcesToInstance[System.identityHashCode(resources)]
                    if (ownerId != currentId) return

                    val resId = param.args[0] as Int
                    val resKey = try {
                        val entryName = resources.getResourceEntryName(resId)
                        val typeName = resources.getResourceTypeName(resId)
                        "$typeName/$entryName"
                    } catch (e: Resources.NotFoundException) {
                        null
                    } ?: return

                    val manager = resourceManager ?: return
                    val value = manager.resolveInt(currentId, resKey)
                    if (value != null) {
                        param.result = value
                    }
                } catch (e: Exception) {
                    // Let original method proceed
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                Resources::class.java,
                "getInteger",
                Int::class.javaPrimitiveType,
                hook
            )
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Could not hook getInteger: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Pine Mode Installation (non-root)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Install resource hooks via Pine (reflection-based, for non-root mode).
     * Follows the same pattern as PineHookManager — uses reflection to
     * access Pine APIs at runtime.
     *
     * @param classLoader Guest app classloader
     * @param instanceId Virtual instance ID
     * @return true if hooks were installed
     */
    fun installPineHooks(classLoader: ClassLoader, instanceId: String): Boolean {
        if (!PineHookManager.isAvailable()) {
            RenjanaLog.w(TAG, "Pine not available, cannot install Pine resource hooks")
            return false
        }

        return try {
            val pineClass = Class.forName("top.canyie.pine.Pine")
            val hookClass = Class.forName("top.canyie.pine.callback.MethodHook")

            // Hook Context.getResources via Pine
            hookMethodViaPine(
                pineClass, hookClass,
                Context::class.java, "getResources",
                emptyArray(),
                createPineContextHook(instanceId)
            )

            // Hook Resources.getIdentifier via Pine
            hookMethodViaPine(
                pineClass, hookClass,
                Resources::class.java, "getIdentifier",
                arrayOf(String::class.java, String::class.java, String::class.java),
                createPineIdentifierHook(instanceId)
            )

            RenjanaLog.i(TAG, "Installed Pine resource hooks for instance $instanceId")
            true
        } catch (e: Throwable) {
            RenjanaLog.e(TAG, "Failed to install Pine resource hooks: ${e.message}")
            false
        }
    }

    /**
     * Generic Pine method hook helper — mirrors PineHookManager's reflection approach.
     */
    private fun hookMethodViaPine(
        pineClass: Class<*>,
        hookClass: Class<*>,
        targetClass: Class<*>,
        methodName: String,
        paramTypes: Array<Class<*>>,
        hookCallback: Any
    ) {
        try {
            val method = targetClass.getDeclaredMethod(methodName, *paramTypes)
            method.isAccessible = true

            val hookMethod = pineClass.getDeclaredMethod(
                "hook",
                java.lang.reflect.Member::class.java,
                hookClass
            )
            hookMethod.invoke(null, method, hookCallback)
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Pine hook failed for $targetClass.$methodName: ${e.message}")
        }
    }

    /**
     * Create a Pine MethodHook for Context.getResources().
     * Returns an anonymous subclass of top.canyie.pine.callback.MethodHook
     * created via reflection to avoid compile-time Pine dependency.
     */
    private fun createPineContextHook(instanceId: String): Any {
        val hookClass = Class.forName("top.canyie.pine.callback.MethodHook")

        // Create a dynamic proxy or use ProxyBuilder if available.
        // For simplicity, we create an InvocationHandler-style wrapper.
        return java.lang.reflect.Proxy.newProxyInstance(
            hookClass.classLoader,
            arrayOf(hookClass.interfaces.firstOrNull() ?: hookClass)
        ) { _, method, args ->
            when (method.name) {
                "after" -> {
                    val param = args?.getOrNull(0)
                    if (param != null) {
                        val currentId = CoreHooks.currentInstanceId.get()
                        if (currentId == instanceId) {
                            try {
                                val manager = resourceManager
                                if (manager != null) {
                                    val apkPath = CoreHooks.packageDataPaths[currentId]
                                    if (apkPath != null) {
                                        val virtualRes = manager.getResources(
                                            currentId, apkPath, "$apkPath/data"
                                        )
                                        if (virtualRes != null) {
                                            // Set result via Pine's param.setResult()
                                            val setResultMethod = param.javaClass
                                                .getDeclaredMethod("setResult", Any::class.java)
                                            setResultMethod.invoke(param, virtualRes)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore — let original proceed
                            }
                        }
                    }
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Create a Pine MethodHook for Resources.getIdentifier().
     */
    private fun createPineIdentifierHook(instanceId: String): Any {
        val hookClass = Class.forName("top.canyie.pine.callback.MethodHook")

        return java.lang.reflect.Proxy.newProxyInstance(
            hookClass.classLoader,
            arrayOf(hookClass.interfaces.firstOrNull() ?: hookClass)
        ) { _, method, args ->
            when (method.name) {
                "after" -> {
                    val param = args?.getOrNull(0)
                    if (param != null) {
                        val currentId = CoreHooks.currentInstanceId.get()
                        if (currentId == instanceId) {
                            try {
                                val getResultMethod = param.javaClass
                                    .getDeclaredMethod("getResult")
                                val result = getResultMethod.invoke(param) as? Int ?: 0

                                if (result == 0) {
                                    val getArgsMethod = param.javaClass
                                        .getDeclaredMethod("getArgs")
                                    val hookArgs = getArgsMethod.invoke(param) as? Array<*>
                                    val name = hookArgs?.getOrNull(0) as? String
                                    val defType = hookArgs?.getOrNull(1) as? String
                                    val defPackage = hookArgs?.getOrNull(2) as? String

                                    val manager = resourceManager
                                    val guestRes = manager?.getResources(currentId, "", "")
                                    if (guestRes != null) {
                                        val guestId = guestRes.getIdentifier(
                                            name, defType, defPackage
                                        )
                                        if (guestId != 0) {
                                            val setResultMethod = param.javaClass
                                                .getDeclaredMethod("setResult", Any::class.java)
                                            setResultMethod.invoke(param, guestId)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                    null
                }
                else -> null
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Cleanup
    // ════════════════════════════════════════════════════════════════════

    /**
     * Remove hooks for a specific instance.
     * Note: Xposed/Pine hooks cannot be truly "uninstalled" at runtime,
     * but we clear our tracking so they become no-ops.
     */
    fun removeHooks(instanceId: String) {
        // Remove all resources-to-instance mappings for this instance
        resourcesToInstance.entries.removeAll { it.value == instanceId }

        // Clear classloader tracking for this instance
        // (classloader hash → bool, but we can't easily map back)
        RenjanaLog.d(TAG, "Removed resource hook tracking for instance $instanceId")
    }

    /**
     * Reset all hook state. Called on container shutdown.
     */
    fun reset() {
        hookedClassLoaders.clear()
        resourcesToInstance.clear()
        resourceManager = null
        RenjanaLog.i(TAG, "ResourcesHook reset")
    }
}
