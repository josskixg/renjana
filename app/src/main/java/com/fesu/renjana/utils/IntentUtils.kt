package com.fesu.renjana.utils

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Utility functions for Intent serialization, validation, and inspection.
 *
 * Used by IntentRouter and IntentHook to safely manipulate Intents without
 * leaking host-app metadata into the guest environment.
 */
object IntentUtils {

    private const val TAG = "IntentUtils"

    // ==================== Serialization ====================

    /**
     * Serialize an Intent to a ByteArray via Parcel marshalling.
     * Returns null if the Intent cannot be serialized (e.g. contains non-parcellable extras).
     */
    fun serialize(intent: Intent): ByteArray? {
        return try {
            val parcel = Parcel.obtain()
            try {
                intent.writeToParcel(parcel, 0)
                parcel.marshall().also { RenjanaLog.v(TAG, "Serialized Intent (${it.size} bytes)") }
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to serialize Intent: ${e.message}")
            null
        }
    }

    /**
     * Deserialize a ByteArray back into an Intent.
     * Returns null if the data is malformed or empty.
     */
    fun deserialize(data: ByteArray): Intent? {
        if (data.isEmpty()) return null
        return try {
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                Intent.CREATOR.createFromParcel(parcel).also {
                    RenjanaLog.v(TAG, "Deserialized Intent: ${describe(it)}")
                }
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to deserialize Intent: ${e.message}")
            null
        }
    }

    /**
     * Copy an Intent into a Bundle under the given key, preserving extras.
     * Useful for passing Intents through IPC boundaries that only accept Bundles.
     */
    fun toBundle(intent: Intent, key: String = "renjana_intent"): Bundle {
        return Bundle().apply {
            putParcelable(key, intent)
        }
    }

    /**
     * Extract an Intent from a Bundle previously stored with [toBundle].
     */
    fun fromBundle(bundle: Bundle?, key: String = "renjana_intent"): Intent? {
        if (bundle == null) return null
        return try {
            bundle.classLoader = Intent::class.java.classLoader
            @Suppress("DEPRECATION")
            bundle.getParcelable(key) as? Intent
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to extract Intent from Bundle: ${e.message}")
            null
        }
    }

    /**
     * Create a deep copy of an Intent by round-tripping through Parcel.
     * This ensures mutations to the copy do not affect the original.
     */
    fun deepCopy(intent: Intent): Intent? {
        val data = serialize(intent) ?: return null
        return deserialize(data)
    }

    // ==================== Component Extraction ====================

    /**
     * Extract the target package name from an Intent, checking component first, then URI authority.
     */
    fun extractTargetPackage(intent: Intent): String? {
        // Explicit component takes priority
        intent.component?.packageName?.let { return it }
        // For content:// URIs, try the authority
        intent.data?.authority?.let { return it }
        // For explicit package set via setPackage()
        intent.`package`?.let { return it }
        return null
    }

    /**
     * Extract the target component (class name) from an Intent.
     * Returns null if the Intent is implicit (no component specified).
     */
    fun extractComponent(intent: Intent): ComponentName? = intent.component

    /**
     * Check if an Intent is explicit (has a component specified).
     */
    fun isExplicit(intent: Intent): Boolean = intent.component != null

    /**
     * Check if an Intent is implicit (relies on filter matching).
     */
    fun isImplicit(intent: Intent): Boolean = intent.component == null

    // ==================== Validation ====================

    /**
     * Validate that an Intent is well-formed and safe to route.
     * Returns a [ValidationResult] with details on any issues found.
     */
    fun validate(intent: Intent): ValidationResult {
        val issues = mutableListOf<String>()

        // Check for null action with no component (completely empty Intent)
        if (intent.action == null && intent.component == null && intent.data == null
            && intent.categories.isNullOrEmpty()) {
            issues.add("Intent has no action, component, data, or categories")
        }

        // Validate URI if present
        intent.data?.let { uri ->
            try {
                uri.scheme
            } catch (e: Exception) {
                issues.add("Malformed URI: ${e.message}")
            }
        }

        // Validate extras don't contain oversized data
        intent.extras?.let { extras ->
            try {
                val parcel = Parcel.obtain()
                try {
                    extras.writeToParcel(parcel, 0)
                    val size = parcel.dataSize()
                    when {
                        size > MAX_EXTRAS_SIZE ->
                            issues.add("Extras too large: ${size} bytes (max $MAX_EXTRAS_SIZE)")
                        else -> { /* size is acceptable */ }
                    }
                } finally {
                    parcel.recycle()
                }
            } catch (e: Exception) {
                issues.add("Failed to validate extras: ${e.message}")
            }
        }

        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues
        )
    }

    /**
     * Strip potentially dangerous extras from an Intent to prevent host leakage.
     * Returns the sanitized Intent (mutated in place).
     */
    fun sanitize(intent: Intent): Intent {
        // Remove any extras that reference host-app internal state
        val extrasToRemove = mutableListOf<String>()
        intent.extras?.let { extras ->
            for (key in extras.keySet()) {
                if (key.startsWith("com.renjana.container.internal.")) {
                    extrasToRemove.add(key)
                }
            }
        }
        for (key in extrasToRemove) {
            intent.removeExtra(key)
            RenjanaLog.d(TAG, "Stripped internal extra: $key")
        }
        return intent
    }

    // ==================== Inspection / Logging ====================

    /**
     * Produce a human-readable one-liner describing the Intent for logging.
     */
    fun describe(intent: Intent): String {
        val sb = StringBuilder("Intent{")
        intent.action?.let { sb.append("act=$it ") }
        intent.categories?.let { cats -> sb.append("cat=$cats ") }
        intent.data?.let { sb.append("dat=$it ") }
        intent.type?.let { sb.append("typ=$it ") }
        intent.component?.let { sb.append("cmp=$it ") }
        intent.`package`?.let { sb.append("pkg=$it ") }
        if (intent.flags != 0) {
            sb.append("flg=0x${Integer.toHexString(intent.flags)} ")
        }
        intent.extras?.let { extras ->
            sb.append("extras=[")
            sb.append(extras.keySet().joinToString(","))
            sb.append("] ")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * Return a list of human-readable flag names for the Intent's flags bitmask.
     */
    fun describeFlags(flags: Int): List<String> {
        val result = mutableListOf<String>()
        for ((mask, name) in FLAG_NAMES) {
            if (flags and mask != 0) result.add(name)
        }
        return result
    }

    /**
     * Check if the Intent has a specific flag set.
     */
    fun hasFlag(intent: Intent, flag: Int): Boolean = intent.flags and flag != 0

    // ==================== Constants ====================

    /** Maximum allowed extras size in bytes (1 MB) */
    const val MAX_EXTRAS_SIZE = 1_048_576

    /** Map of flag bitmasks to human-readable names */
    private val FLAG_NAMES = listOf(
        Intent.FLAG_GRANT_READ_URI_PERMISSION to "GRANT_READ_URI",
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION to "GRANT_WRITE_URI",
        Intent.FLAG_FROM_BACKGROUND to "FROM_BACKGROUND",
        Intent.FLAG_DEBUG_LOG_RESOLUTION to "DEBUG_LOG",
        Intent.FLAG_EXCLUDE_STOPPED_PACKAGES to "EXCLUDE_STOPPED",
        Intent.FLAG_INCLUDE_STOPPED_PACKAGES to "INCLUDE_STOPPED",
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION to "GRANT_PERSISTABLE",
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION to "GRANT_PREFIX",
        Intent.FLAG_ACTIVITY_MATCH_EXTERNAL to "MATCH_EXTERNAL",
        Intent.FLAG_ACTIVITY_NO_HISTORY to "NO_HISTORY",
        Intent.FLAG_ACTIVITY_SINGLE_TOP to "SINGLE_TOP",
        Intent.FLAG_ACTIVITY_NEW_TASK to "NEW_TASK",
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK to "MULTIPLE_TASK",
        Intent.FLAG_ACTIVITY_CLEAR_TOP to "CLEAR_TOP",
        Intent.FLAG_ACTIVITY_FORWARD_RESULT to "FORWARD_RESULT",
        Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP to "PREVIOUS_IS_TOP",
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS to "EXCLUDE_RECENTS",
        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT to "BROUGHT_TO_FRONT",
        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED to "RESET_TASK",
        Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY to "FROM_HISTORY",
        Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET to "CLEAR_WHEN_RESET",
        Intent.FLAG_ACTIVITY_NO_USER_ACTION to "NO_USER_ACTION",
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT to "REORDER_TO_FRONT",
        Intent.FLAG_ACTIVITY_NO_ANIMATION to "NO_ANIMATION",
        Intent.FLAG_ACTIVITY_CLEAR_TASK to "CLEAR_TASK",
        Intent.FLAG_ACTIVITY_TASK_ON_HOME to "TASK_ON_HOME",
        Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS to "RETAIN_IN_RECENTS",
        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT to "LAUNCH_ADJACENT",
        Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER to "REQUIRE_NON_BROWSER",
        Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT to "REQUIRE_DEFAULT",
        Intent.FLAG_RECEIVER_REGISTERED_ONLY to "RCV_REGISTERED_ONLY",
        Intent.FLAG_RECEIVER_REPLACE_PENDING to "RCV_REPLACE_PENDING",
        Intent.FLAG_RECEIVER_FOREGROUND to "RCV_FOREGROUND",
        Intent.FLAG_RECEIVER_NO_ABORT to "RCV_NO_ABORT",
    )
}

/**
 * Result of Intent validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val issues: List<String>
) {
    override fun toString(): String {
        return if (isValid) "Valid" else "Invalid: ${issues.joinToString("; ")}"
    }
}
