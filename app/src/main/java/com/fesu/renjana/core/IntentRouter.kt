package com.fesu.renjana.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.hooks.CoreHooks
import com.fesu.renjana.utils.IntentUtils
import com.fesu.renjana.utils.RenjanaLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Central Intent routing engine for the Renjana virtual container.
 *
 * Every Intent operation from guest apps flows through this router:
 * 1. Intent is validated and sanitized
 * 2. Routing strategy is determined (internal / cross-instance / external / blocked)
 * 3. Intent is rewritten to target [WrapperActivity] or system as appropriate
 * 4. The rewritten Intent is dispatched
 *
 * Thread-safe: all mutable state is protected by ConcurrentHashMap / synchronized blocks.
 */
class IntentRouter(private val context: Context) {

    companion object {
        private const val TAG = "IntentRouter"

        /** Extra keys used by the router to carry virtual context metadata. */
        const val EXTRA_ROUTING_STRATEGY = "com.renjana.container.internal.routing_strategy"
        const val EXTRA_ORIGINAL_INTENT = "com.renjana.container.internal.original_intent"
        const val EXTRA_SOURCE_INSTANCE_ID = "com.renjana.container.internal.source_instance"
        const val EXTRA_GUEST_ACTIVITY_CLASS = WrapperActivity.EXTRA_GUEST_ACTIVITY_CLASS

        // Routing strategy constants (stored as Int in Intent extra)
        const val STRATEGY_INTERNAL = 0         // Guest-to-guest within same instance
        const val STRATEGY_CROSS_INSTANCE = 1   // Guest-to-guest across instances
        const val STRATEGY_EXTERNAL = 2         // Forward to system
        const val STRATEGY_STUB = 3             // Redirect to stub handler
        const val STRATEGY_BLOCKED = 4          // Blocked (e.g. FLAG_SECURE conflict)
        const val STRATEGY_BROADCAST = 5        // Internal broadcast routing
        const val STRATEGY_SERVICE = 6          // Internal service routing
    }

    /**
     * Represents a resolved routing decision.
     */
    data class RoutingResult(
        val strategy: Int,
        val routedIntent: Intent,
        val targetPackage: String?,
        val targetComponent: String?,
        val reason: String
    )

    /**
     * Registry of package names that belong to virtual instances.
     * Maps virtual packageName -> instanceId.
     */
    private val virtualPackages = ConcurrentHashMap<String, String>()

    /**
     * Maps instanceId -> set of registered component class names.
     */
    private val registeredComponents = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Intent filter manager for implicit Intent resolution.
     */
    val filterManager = IntentFilterManager()

    // ==================== Registration ====================

    /**
     * Register a virtual package with its instance ID.
     * Called when a guest app is launched inside the container.
     */
    fun registerVirtualPackage(packageName: String, instanceId: String) {
        virtualPackages[packageName] = instanceId
        RenjanaLog.d(TAG, "Registered virtual package: $packageName -> $instanceId")
    }

    /**
     * Unregister a virtual package.
     */
    fun unregisterVirtualPackage(packageName: String) {
        val instanceId = virtualPackages.remove(packageName)
        if (instanceId != null) {
            registeredComponents.remove(instanceId)
            filterManager.unregisterPackage(packageName)
        }
    }

    /**
     * Register a component (Activity/Service/Receiver) that exists within a virtual instance.
     */
    fun registerComponent(instanceId: String, className: String) {
        registeredComponents
            .getOrPut(instanceId) { ConcurrentHashMap.newKeySet() }
            .add(className)
        RenjanaLog.v(TAG, "Registered component: $className in instance $instanceId")
    }

    /**
     * Check if a package belongs to a virtual instance.
     */
    fun isVirtualPackage(packageName: String): Boolean =
        virtualPackages.containsKey(packageName)

    /**
     * Get the instance ID for a virtual package.
     */
    fun getInstanceId(packageName: String): String? =
        virtualPackages[packageName]

    // ==================== Main Routing Entry Point ====================

    /**
     * Route an Intent originating from a guest app.
     *
     * This is the primary API called by [com.renjana.container.hooks.IntentHook]
     * whenever a guest app calls startActivity(), sendBroadcast(), etc.
     *
     * @param sourceInstanceId The instance that originated this Intent
     * @param intent The original Intent from the guest app
     * @param operationType The type of operation (activity, broadcast, service)
     * @return A [RoutingResult] describing how the Intent should be dispatched
     */
    fun route(
        sourceInstanceId: String,
        intent: Intent,
        operationType: OperationType = OperationType.ACTIVITY
    ): RoutingResult {
        RenjanaLog.d(TAG, "Routing ${operationType.name} from instance=$sourceInstanceId: ${IntentUtils.describe(intent)}")

        // 1. Validate
        val validation = IntentUtils.validate(intent)
        if (!validation.isValid) {
            RenjanaLog.w(TAG, "Invalid Intent: $validation")
            return RoutingResult(
                strategy = STRATEGY_BLOCKED,
                routedIntent = intent,
                targetPackage = null,
                targetComponent = null,
                reason = "Validation failed: $validation"
            )
        }

        // 2. Sanitize (remove host-leaked extras)
        IntentUtils.sanitize(intent)

        // 3. Handle flags
        handleIntentFlags(intent)

        // 4. Determine routing based on operation type
        return when (operationType) {
            OperationType.ACTIVITY -> routeActivity(sourceInstanceId, intent)
            OperationType.BROADCAST -> routeBroadcast(sourceInstanceId, intent)
            OperationType.SERVICE -> routeService(sourceInstanceId, intent)
        }
    }

    // ==================== Activity Routing ====================

    /**
     * Route an Activity Intent (startActivity / startActivityForResult).
     */
    private fun routeActivity(sourceInstanceId: String, intent: Intent): RoutingResult {
        // Explicit Intent: component is specified
        if (IntentUtils.isExplicit(intent)) {
            return routeExplicitActivity(sourceInstanceId, intent)
        }

        // Implicit Intent: need filter matching
        return routeImplicitActivity(sourceInstanceId, intent)
    }

    /**
     * Route an explicit Activity Intent.
     */
    private fun routeExplicitActivity(sourceInstanceId: String, intent: Intent): RoutingResult {
        val component = intent.component!!
        val targetPackage = component.packageName
        val targetClass = component.className

        // Case 1: Target is in the same virtual package -> INTERNAL
        val sourcePackage = getPackageForInstance(sourceInstanceId)
        if (sourcePackage == targetPackage) {
            RenjanaLog.d(TAG, "INTERNAL routing: $targetClass within instance $sourceInstanceId")
            val routed = rewriteForWrapperActivity(intent, sourceInstanceId, targetClass)
            return RoutingResult(
                strategy = STRATEGY_INTERNAL,
                routedIntent = routed,
                targetPackage = targetPackage,
                targetComponent = targetClass,
                reason = "Same virtual package"
            )
        }

        // Case 2: Target is a different virtual package -> CROSS_INSTANCE
        if (isVirtualPackage(targetPackage)) {
            val targetInstanceId = virtualPackages[targetPackage]!!
            RenjanaLog.d(TAG, "CROSS_INSTANCE routing: $targetClass -> instance $targetInstanceId")
            val routed = rewriteForWrapperActivity(intent, targetInstanceId, targetClass)
            return RoutingResult(
                strategy = STRATEGY_CROSS_INSTANCE,
                routedIntent = routed,
                targetPackage = targetPackage,
                targetComponent = targetClass,
                reason = "Different virtual package"
            )
        }

        // Case 3: Target is the container app itself -> INTERNAL (container UI)
        if (targetPackage == context.packageName) {
            RenjanaLog.d(TAG, "CONTAINER routing: $targetClass")
            return RoutingResult(
                strategy = STRATEGY_INTERNAL,
                routedIntent = intent,
                targetPackage = targetPackage,
                targetComponent = targetClass,
                reason = "Container package target"
            )
        }

        // Case 4: Target is external system app -> EXTERNAL
        RenjanaLog.d(TAG, "EXTERNAL routing: $targetClass")
        return RoutingResult(
            strategy = STRATEGY_EXTERNAL,
            routedIntent = intent,
            targetPackage = targetPackage,
            targetComponent = targetClass,
            reason = "External package target"
        )
    }

    /**
     * Route an implicit Activity Intent via filter matching.
     */
    private fun routeImplicitActivity(sourceInstanceId: String, intent: Intent): RoutingResult {
        // First try matching against registered virtual filters
        val matches = filterManager.match(intent)

        if (matches.isNotEmpty()) {
            // Pick best match (highest priority, preferring same instance)
            val bestMatch = selectBestMatch(matches, sourceInstanceId)
            val targetClass = bestMatch.componentName
            val targetPackage = bestMatch.packageName

            RenjanaLog.d(TAG, "Implicit match: $targetClass (priority=${bestMatch.priority})")

            val targetInstanceId = virtualPackages[targetPackage] ?: sourceInstanceId
            val routed = rewriteForWrapperActivity(intent, targetInstanceId, targetClass)

            val strategy = if (virtualPackages[targetPackage] == sourceInstanceId) {
                STRATEGY_INTERNAL
            } else {
                STRATEGY_CROSS_INSTANCE
            }

            return RoutingResult(
                strategy = strategy,
                routedIntent = routed,
                targetPackage = targetPackage,
                targetComponent = targetClass,
                reason = "Filter match: ${intent.action}"
            )
        }

        // No virtual match: forward to system for resolution
        RenjanaLog.d(TAG, "No virtual match for implicit Intent, routing EXTERNAL")

        // Check package restriction
        val restrictedPackage = intent.`package`
        if (restrictedPackage != null && isVirtualPackage(restrictedPackage)) {
            // Package was restricted to a virtual package but no match found
            return RoutingResult(
                strategy = STRATEGY_BLOCKED,
                routedIntent = intent,
                targetPackage = restrictedPackage,
                targetComponent = null,
                reason = "Restricted to virtual package but no matching filter"
            )
        }

        return RoutingResult(
            strategy = STRATEGY_EXTERNAL,
            routedIntent = intent,
            targetPackage = null,
            targetComponent = null,
            reason = "No virtual filter match, forward to system"
        )
    }

    // ==================== Broadcast Routing ====================

    /**
     * Route a broadcast Intent (sendBroadcast / sendOrderedBroadcast).
     */
    private fun routeBroadcast(sourceInstanceId: String, intent: Intent): RoutingResult {
        // Broadcasts with explicit package are simpler
        val targetPackage = intent.`package`
        if (targetPackage != null && isVirtualPackage(targetPackage)) {
            RenjanaLog.d(TAG, "Internal broadcast to virtual package: $targetPackage")
            intent.putExtra(EXTRA_SOURCE_INSTANCE_ID, sourceInstanceId)
            intent.putExtra(EXTRA_ROUTING_STRATEGY, STRATEGY_BROADCAST)
            return RoutingResult(
                strategy = STRATEGY_BROADCAST,
                routedIntent = intent,
                targetPackage = targetPackage,
                targetComponent = null,
                reason = "Broadcast to virtual package"
            )
        }

        // Check if any virtual receivers match this broadcast
        val matches = filterManager.match(intent)
        if (matches.isNotEmpty()) {
            intent.putExtra(EXTRA_SOURCE_INSTANCE_ID, sourceInstanceId)
            intent.putExtra(EXTRA_ROUTING_STRATEGY, STRATEGY_BROADCAST)
            return RoutingResult(
                strategy = STRATEGY_BROADCAST,
                routedIntent = intent,
                targetPackage = null,
                targetComponent = matches.first().componentName,
                reason = "Broadcast matched ${matches.size} virtual receiver(s)"
            )
        }

        // No virtual match: forward to system
        RenjanaLog.d(TAG, "Broadcast has no virtual receivers, routing EXTERNAL")
        return RoutingResult(
            strategy = STRATEGY_EXTERNAL,
            routedIntent = intent,
            targetPackage = null,
            targetComponent = null,
            reason = "No virtual broadcast receiver match"
        )
    }

    // ==================== Service Routing ====================

    /**
     * Route a service Intent (startService / bindService).
     */
    private fun routeService(sourceInstanceId: String, intent: Intent): RoutingResult {
        // Explicit service: component specified
        if (IntentUtils.isExplicit(intent)) {
            val component = intent.component!!
            val targetPackage = component.packageName

            if (isVirtualPackage(targetPackage)) {
                val targetInstanceId = virtualPackages[targetPackage]!!
                RenjanaLog.d(TAG, "INTERNAL service routing: ${component.className} in $targetInstanceId")
                intent.putExtra(EXTRA_SOURCE_INSTANCE_ID, sourceInstanceId)
                intent.putExtra(EXTRA_ROUTING_STRATEGY, STRATEGY_SERVICE)
                return RoutingResult(
                    strategy = STRATEGY_SERVICE,
                    routedIntent = intent,
                    targetPackage = targetPackage,
                    targetComponent = component.className,
                    reason = "Virtual service target"
                )
            }

            // External service
            RenjanaLog.d(TAG, "EXTERNAL service routing: ${component.className}")
            return RoutingResult(
                strategy = STRATEGY_EXTERNAL,
                routedIntent = intent,
                targetPackage = targetPackage,
                targetComponent = component.className,
                reason = "External service target"
            )
        }

        // Implicit service: match against filters
        val matches = filterManager.match(intent)
        if (matches.isNotEmpty()) {
            val bestMatch = selectBestMatch(matches, sourceInstanceId)
            intent.putExtra(EXTRA_SOURCE_INSTANCE_ID, sourceInstanceId)
            intent.putExtra(EXTRA_ROUTING_STRATEGY, STRATEGY_SERVICE)
            return RoutingResult(
                strategy = STRATEGY_SERVICE,
                routedIntent = intent,
                targetPackage = bestMatch.packageName,
                targetComponent = bestMatch.componentName,
                reason = "Implicit service match"
            )
        }

        // Forward to system
        return RoutingResult(
            strategy = STRATEGY_EXTERNAL,
            routedIntent = intent,
            targetPackage = null,
            targetComponent = null,
            reason = "No virtual service match, forward to system"
        )
    }

    // ==================== PendingIntent Handling ====================

    /**
     * Wrap an Intent for PendingIntent creation within the virtual environment.
     * The original Intent is embedded as an extra so it can be unwrapped when the
     * PendingIntent fires.
     */
    fun wrapForPendingIntent(
        sourceInstanceId: String,
        intent: Intent
    ): Intent {
        val wrapper = Intent().apply {
            putExtra(EXTRA_ORIGINAL_INTENT, intent)
            putExtra(EXTRA_SOURCE_INSTANCE_ID, sourceInstanceId)
            putExtra(EXTRA_ROUTING_STRATEGY, -1) // sentinel: pending, not yet routed
        }
        // Preserve the action so the PendingIntent can be identified
        intent.action?.let { wrapper.action = "com.renjana.container.PENDING_$it" }
        return wrapper
    }

    /**
     * Unwrap a PendingIntent that was previously wrapped with [wrapForPendingIntent].
     * Returns the original Intent and the source instance ID, or null if not a wrapped PendingIntent.
     */
    fun unwrapPendingIntent(intent: Intent): Pair<Intent, String>? {
        val strategy = intent.getIntExtra(EXTRA_ROUTING_STRATEGY, Int.MIN_VALUE)
        if (strategy != -1) return null

        @Suppress("DEPRECATION")
        val original = intent.getParcelableExtra<Intent>(EXTRA_ORIGINAL_INTENT) ?: return null
        val sourceInstanceId = intent.getStringExtra(EXTRA_SOURCE_INSTANCE_ID) ?: return null

        RenjanaLog.d(TAG, "Unwrapped PendingIntent from instance=$sourceInstanceId")
        return Pair(original, sourceInstanceId)
    }

    // ==================== Dispatch ====================

    /**
     * Execute a routing result: start the appropriate Activity, send broadcast, etc.
     *
     * @param context The Context to use for dispatching
     * @param result The [RoutingResult] from a prior [route] call
     * @param requestCode Optional request code for startActivityForResult
     * @return true if dispatch succeeded, false otherwise
     */
    fun dispatch(
        context: Context,
        result: RoutingResult,
        requestCode: Int = -1
    ): Boolean {
        if (result.strategy == STRATEGY_BLOCKED) {
            RenjanaLog.w(TAG, "Intent BLOCKED: ${result.reason}")
            return false
        }

        return try {
            val routedIntent = result.routedIntent

            // Ensure NEW_TASK flag for non-Activity contexts
            if (context !is android.app.Activity) {
                routedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            when (result.strategy) {
                STRATEGY_INTERNAL,
                STRATEGY_CROSS_INSTANCE,
                STRATEGY_STUB -> {
                    if (requestCode >= 0 && context is android.app.Activity) {
                        context.startActivityForResult(routedIntent, requestCode)
                    } else {
                        context.startActivity(routedIntent)
                    }
                    RenjanaLog.d(TAG, "Dispatched Activity: strategy=${result.strategy} reason=${result.reason}")
                }
                STRATEGY_BROADCAST -> {
                    context.sendBroadcast(routedIntent)
                    RenjanaLog.d(TAG, "Dispatched Broadcast: reason=${result.reason}")
                }
                STRATEGY_SERVICE -> {
                    context.startService(routedIntent)
                    RenjanaLog.d(TAG, "Dispatched Service: reason=${result.reason}")
                }
                STRATEGY_EXTERNAL -> {
                    // Forward to system with NEW_TASK if needed
                    if (routedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                        routedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(routedIntent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        RenjanaLog.w(TAG, "No system handler for external Intent: ${IntentUtils.describe(routedIntent)}")
                        return false
                    }
                    RenjanaLog.d(TAG, "Dispatched External: reason=${result.reason}")
                }
                else -> {
                    RenjanaLog.w(TAG, "Unknown strategy: ${result.strategy}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Dispatch failed: ${e.message}")
            false
        }
    }

    // ==================== Intent Rewriting ====================

    /**
     * Rewrite an Intent to target WrapperActivity, embedding guest class info.
     * The original component is replaced with WrapperActivity's ComponentName,
     * and the guest class name is stored as an extra.
     */
    private fun rewriteForWrapperActivity(
        intent: Intent,
        targetInstanceId: String,
        guestClassName: String
    ): Intent {
        val copy = IntentUtils.deepCopy(intent) ?: intent

        // Replace component with WrapperActivity
        copy.component = ComponentName(context.packageName, WrapperActivity::class.java.name)

        // Embed virtual context
        copy.putExtra(WrapperActivity.EXTRA_INSTANCE_ID, targetInstanceId)
        copy.putExtra(WrapperActivity.EXTRA_GUEST_ACTIVITY_CLASS, guestClassName)
        copy.putExtra(EXTRA_SOURCE_INSTANCE_ID, targetInstanceId)

        // Preserve task affinity: if flags indicate new task, keep them
        // but ensure CLEAR_TOP doesn't kill the host task
        val flags = copy.flags
        if (flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0) {
            copy.flags = flags and Intent.FLAG_ACTIVITY_CLEAR_TASK.inv()
            RenjanaLog.d(TAG, "Stripped CLEAR_TASK flag to protect host task")
        }

        return copy
    }

    // ==================== Flag Handling ====================

    /**
     * Process Intent flags that affect routing behavior.
     */
    private fun handleIntentFlags(intent: Intent) {
        val flags = intent.flags

        // FLAG_ACTIVITY_FORWARD_RESULT: the result should be forwarded to the
        // original caller, not the intermediate Activity
        if (flags and Intent.FLAG_ACTIVITY_FORWARD_RESULT != 0) {
            RenjanaLog.d(TAG, "Intent has FORWARD_RESULT flag")
        }

        // FLAG_ACTIVITY_CLEAR_TOP: clear activities above target in task
        if (flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0) {
            RenjanaLog.d(TAG, "Intent has CLEAR_TOP flag")
        }

        // FLAG_ACTIVITY_SINGLE_TOP: don't create new instance if already on top
        if (flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0) {
            RenjanaLog.d(TAG, "Intent has SINGLE_TOP flag")
        }

        // FLAG_ACTIVITY_NO_HISTORY: Activity should not be kept in history
        if (flags and Intent.FLAG_ACTIVITY_NO_HISTORY != 0) {
            RenjanaLog.d(TAG, "Intent has NO_HISTORY flag")
        }

        // FLAG_GRANT_READ/WRITE_URI_PERMISSION: preserve these for external routing
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 ||
            flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            RenjanaLog.d(TAG, "Intent has URI permission grants")
        }

        // FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS: don't show in recents
        if (flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0) {
            RenjanaLog.d(TAG, "Intent has EXCLUDE_FROM_RECENTS flag")
        }
    }

    // ==================== Helpers ====================

    /**
     * Select the best matching filter entry from a list of matches.
     * Prefers same-instance matches, then highest priority.
     */
    private fun selectBestMatch(
        matches: List<IntentFilterManager.FilterEntry>,
        sourceInstanceId: String
    ): IntentFilterManager.FilterEntry {
        // Prefer same-instance match
        val sameInstance = matches.filter {
            virtualPackages[it.packageName] == sourceInstanceId
        }
        if (sameInstance.isNotEmpty()) {
            return sameInstance.maxByOrNull { it.priority }!!
        }

        // Otherwise highest priority
        return matches.maxByOrNull { it.priority }!!
    }

    /**
     * Get the package name for a given instance ID.
     */
    private fun getPackageForInstance(instanceId: String): String? {
        return virtualPackages.entries.find { it.value == instanceId }?.key
    }

    /**
     * Reset all routing state. Called on container shutdown.
     */
    fun reset() {
        virtualPackages.clear()
        registeredComponents.clear()
        filterManager.clear()
        RenjanaLog.i(TAG, "IntentRouter state reset")
    }

    // ==================== Types ====================

    /**
     * The type of Intent operation being routed.
     */
    enum class OperationType {
        ACTIVITY,
        BROADCAST,
        SERVICE
    }
}
