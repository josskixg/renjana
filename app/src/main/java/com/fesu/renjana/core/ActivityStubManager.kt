package com.fesu.renjana.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.fesu.renjana.utils.RenjanaLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * ActivityStubManager - Manages the pool of stub Activities and routes guest
 * Activity launches to appropriate stubs.
 *
 * Design:
 * - 10 stub Activities (StubActivity_0..StubActivity_9) are pre-registered in the manifest.
 * - Each stub is either FREE or OCCUPIED (bound to a guest Activity in a specific instance).
 * - When a guest wants to start a new Activity, we allocate a free stub and build an
 *   Intent targeting that stub with guest class info in extras.
 * - When a stub is destroyed, it is released back to the pool.
 *
 * Per-instance stack isolation:
 * - Each virtual instance maintains its own [ActivityStackEntry] list, tracking which
 *   stubs are used and what guest classes they host.
 * - Launch modes (standard, singleTop, singleTask, singleInstance) are enforced by
 *   inspecting the stack and either creating a new entry, reusing the top, or clearing
 *   back to an existing entry.
 */
object ActivityStubManager {

    private const val TAG = "ActivityStubManager"
    private const val TOTAL_STUBS = 10
    private const val STUB_PACKAGE = "com.renjana.container"

    // ─── Stub pool state ───────────────────────────────────

    /** Free stub indices available for allocation */
    private val freeStubs = ConcurrentLinkedDeque<Int>()

    /** Occupied stubs: stubIndex → StubRecord */
    private val occupiedStubs = ConcurrentHashMap<Int, StubRecord>()

    // ─── Per-instance stacks ───────────────────────────────

    /** instanceId → ordered stack of activity entries (top = last) */
    private val instanceStacks = ConcurrentHashMap<String, MutableList<ActivityStackEntry>>()

    // ─── Request code mapping for startActivityForResult ────

    /** "instanceId:stubIndex:stubRequestCode" → original guest request code */
    private val requestCodeMap = ConcurrentHashMap<String, Int>()

    // ─── StubActivity component name cache ──────────────────

    private val stubComponentNames = Array(TOTAL_STUBS) { i ->
        ComponentName(STUB_PACKAGE, "$STUB_PACKAGE.core.StubActivity_$i")
    }

    init {
        // All stubs start free
        for (i in 0 until TOTAL_STUBS) {
            freeStubs.addLast(i)
        }
    }

    // ──────────────────────────────────────────────
    // Public API: allocate & route
    // ──────────────────────────────────────────────

    /**
     * Build an Intent that launches a stub Activity for the given guest Activity.
     *
     * @param context        host Context used to start the Activity
     * @param instanceId     virtual container instance ID
     * @param guestClass     fully-qualified guest Activity class name
     * @param guestIntent    original Intent the guest wanted to send (extras, data, etc.)
     * @param apkPath        path to the guest APK
     * @param launchMode     one of [LAUNCH_STANDARD], [LAUNCH_SINGLE_TOP],
     *                       [LAUNCH_SINGLE_TASK], [LAUNCH_SINGLE_INSTANCE]
     * @return Intent targeting the allocated stub, or null if no stubs available
     */
    fun buildStubIntent(
        context: Context,
        instanceId: String,
        guestClass: String,
        guestIntent: Intent?,
        apkPath: String,
        launchMode: Int = LAUNCH_STANDARD
    ): Intent? {
        // Handle launch modes by inspecting the instance stack
        when (launchMode) {
            LAUNCH_SINGLE_TOP -> {
                val top = peekTop(instanceId)
                if (top != null && top.guestClassName == guestClass) {
                    // Reuse existing stub — deliver via onNewIntent
                    return buildReDeliveryIntent(top.stubIndex, guestIntent)
                }
            }
            LAUNCH_SINGLE_TASK, LAUNCH_SINGLE_INSTANCE -> {
                val existing = findInStack(instanceId, guestClass)
                if (existing != null) {
                    if (launchMode == LAUNCH_SINGLE_TASK) {
                        // Clear everything above this entry
                        clearAbove(instanceId, existing.stubIndex)
                    }
                    return buildReDeliveryIntent(existing.stubIndex, guestIntent)
                }
            }
        }

        // Standard or no existing entry found — allocate a new stub
        val stubIndex = allocateStub() ?: run {
            RenjanaLog.e(TAG, "No free stubs available for $guestClass in instance $instanceId")
            return null
        }

        val intent = Intent().apply {
            component = stubComponentNames[stubIndex]
            putExtra(StubActivity.EXTRA_INSTANCE_ID, instanceId)
            putExtra(StubActivity.EXTRA_GUEST_ACTIVITY_CLASS, guestClass)
            putExtra(StubActivity.EXTRA_APK_PATH, apkPath)
            putExtra(StubActivity.EXTRA_STUB_INDEX, stubIndex)
            putExtra(StubActivity.EXTRA_LAUNCH_MODE, launchMode)
            if (guestIntent != null) {
                putExtra(StubActivity.EXTRA_GUEST_ORIGINAL_INTENT, guestIntent)
            }
            // Flags for proper task behavior
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Push onto the instance stack
        pushToStack(instanceId, ActivityStackEntry(
            stubIndex = stubIndex,
            guestClassName = guestClass,
            launchMode = launchMode
        ))

        RenjanaLog.i(TAG, "Allocated StubActivity_$stubIndex for $guestClass (instance=$instanceId, mode=$launchMode)")
        return intent
    }

    /**
     * Allocate a free stub index. Returns null if all stubs are occupied.
     */
    fun allocateStub(): Int? {
        val index = freeStubs.pollFirst()
        if (index != null) {
            RenjanaLog.d(TAG, "Allocated stub #$index (${freeStubs.size} remaining)")
        }
        return index
    }

    /**
     * Check how many free stubs remain.
     */
    fun freeStubCount(): Int = freeStubs.size

    // ──────────────────────────────────────────────
    // Called by StubActivity lifecycle
    // ──────────────────────────────────────────────

    /**
     * Called when a StubActivity's onCreate completes successfully.
     */
    fun onStubOccupied(instanceId: String, stubIndex: Int, guestClassName: String) {
        occupiedStubs[stubIndex] = StubRecord(
            instanceId = instanceId,
            guestClassName = guestClassName,
            stubIndex = stubIndex
        )
        RenjanaLog.d(TAG, "Stub #$stubIndex occupied by $guestClassName (instance=$instanceId)")
    }

    /**
     * Called when a StubActivity resumes.
     */
    fun onStubResumed(instanceId: String, stubIndex: Int) {
        RenjanaLog.v(TAG, "Stub #$stubIndex resumed (instance=$instanceId)")
    }

    /**
     * Called when a StubActivity is destroyed.
     */
    fun onStubReleased(instanceId: String, stubIndex: Int, guestClassName: String) {
        occupiedStubs.remove(stubIndex)
        freeStubs.addLast(stubIndex)
        removeFromStack(instanceId, stubIndex)
        clearRequestCodes(instanceId, stubIndex)
        RenjanaLog.d(TAG, "Stub #$stubIndex released ($guestClassName, instance=$instanceId, ${freeStubs.size} free)")
    }

    // ──────────────────────────────────────────────
    // Activity result code management
    // ──────────────────────────────────────────────

    /**
     * Register a mapping from the stub's request code to the guest's original request code.
     * Called before startActivityForResult is invoked on the stub.
     *
     * @param instanceId    the virtual instance
     * @param stubIndex     which stub is initiating the launch
     * @param guestRequestCode the request code the guest Activity used
     * @param guestIntent   the intent being launched (for debugging)
     */
    fun registerRequestCode(instanceId: String, stubIndex: Int, guestRequestCode: Int, guestIntent: Intent) {
        // Use the guest's request code directly as the stub's request code
        // (simple 1:1 mapping; if collisions are needed, encode them)
        val key = requestCodeKey(instanceId, stubIndex, guestRequestCode)
        requestCodeMap[key] = guestRequestCode
        RenjanaLog.d(TAG, "Registered request code $guestRequestCode for stub[$stubIndex] in $instanceId")
    }

    /**
     * Resolve the guest's original request code from the stub's result callback.
     */
    fun resolveRequestCode(instanceId: String, stubIndex: Int, stubRequestCode: Int): Int {
        val key = requestCodeKey(instanceId, stubIndex, stubRequestCode)
        return requestCodeMap.remove(key) ?: stubRequestCode
    }

    private fun clearRequestCodes(instanceId: String, stubIndex: Int) {
        val prefix = "$instanceId:$stubIndex:"
        requestCodeMap.keys.removeAll { it.startsWith(prefix) }
    }

    private fun requestCodeKey(instanceId: String, stubIndex: Int, requestCode: Int): String {
        return "$instanceId:$stubIndex:$requestCode"
    }

    // ──────────────────────────────────────────────
    // Per-instance stack operations
    // ──────────────────────────────────────────────

    private fun getOrCreateStack(instanceId: String): MutableList<ActivityStackEntry> {
        return instanceStacks.getOrPut(instanceId) { mutableListOf() }
    }

    private fun pushToStack(instanceId: String, entry: ActivityStackEntry) {
        synchronized(this) {
            getOrCreateStack(instanceId).add(entry)
        }
    }

    private fun peekTop(instanceId: String): ActivityStackEntry? {
        synchronized(this) {
            val stack = instanceStacks[instanceId] ?: return null
            return stack.lastOrNull()
        }
    }

    private fun findInStack(instanceId: String, guestClass: String): ActivityStackEntry? {
        synchronized(this) {
            val stack = instanceStacks[instanceId] ?: return null
            return stack.find { it.guestClassName == guestClass }
        }
    }

    private fun removeFromStack(instanceId: String, stubIndex: Int) {
        synchronized(this) {
            val stack = instanceStacks[instanceId] ?: return
            stack.removeAll { it.stubIndex == stubIndex }
            if (stack.isEmpty()) {
                instanceStacks.remove(instanceId)
            }
        }
    }

    private fun clearAbove(instanceId: String, stubIndex: Int) {
        synchronized(this) {
            val stack = instanceStacks[instanceId] ?: return
            val targetPos = stack.indexOfFirst { it.stubIndex == stubIndex }
            if (targetPos >= 0 && targetPos < stack.size - 1) {
                val toRemove = stack.subList(targetPos + 1, stack.size).toList()
                for (entry in toRemove) {
                    // Release the stubs that are above the target
                    val occupied = occupiedStubs.remove(entry.stubIndex)
                    if (occupied != null) {
                        freeStubs.addLast(entry.stubIndex)
                    }
                }
                stack.subList(targetPos + 1, stack.size).clear()
            }
        }
    }

    /**
     * Get a snapshot of the activity stack for a given instance (for debugging).
     */
    fun getStackSnapshot(instanceId: String): List<ActivityStackEntry> {
        synchronized(this) {
            return instanceStacks[instanceId]?.toList() ?: emptyList()
        }
    }

    /**
     * Clear all state for a given instance. Called when a virtual instance is stopped.
     */
    fun clearInstance(instanceId: String) {
        synchronized(this) {
            val stack = instanceStacks.remove(instanceId) ?: return
            for (entry in stack) {
                occupiedStubs.remove(entry.stubIndex)
                freeStubs.addLast(entry.stubIndex)
                clearRequestCodes(instanceId, entry.stubIndex)
            }
            RenjanaLog.i(TAG, "Cleared all stubs for instance $instanceId (${stack.size} entries)")
        }
    }

    /**
     * Reset all state. Called on application shutdown.
     */
    fun reset() {
        synchronized(this) {
            occupiedStubs.clear()
            instanceStacks.clear()
            requestCodeMap.clear()
            freeStubs.clear()
            for (i in 0 until TOTAL_STUBS) {
                freeStubs.addLast(i)
            }
            RenjanaLog.i(TAG, "ActivityStubManager reset")
        }
    }

    // ──────────────────────────────────────────────
    // Re-delivery intent for singleTop/singleTask
    // ──────────────────────────────────────────────

    private fun buildReDeliveryIntent(stubIndex: Int, guestIntent: Intent?): Intent {
        return Intent().apply {
            component = stubComponentNames[stubIndex]
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (guestIntent != null) {
                putExtra(StubActivity.EXTRA_GUEST_ORIGINAL_INTENT, guestIntent)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Launch mode constants
    // ──────────────────────────────────────────────

    const val LAUNCH_STANDARD = 0
    const val LAUNCH_SINGLE_TOP = 1
    const val LAUNCH_SINGLE_TASK = 2
    const val LAUNCH_SINGLE_INSTANCE = 3

    // ──────────────────────────────────────────────
    // Data classes
    // ──────────────────────────────────────────────

    /**
     * Record of an occupied stub.
     */
    data class StubRecord(
        val instanceId: String,
        val guestClassName: String,
        val stubIndex: Int
    )

    /**
     * Entry in a per-instance Activity stack.
     */
    data class ActivityStackEntry(
        val stubIndex: Int,
        val guestClassName: String,
        val launchMode: Int
    )
}
