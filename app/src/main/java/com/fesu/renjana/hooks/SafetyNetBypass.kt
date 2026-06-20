package com.fesu.renjana.hooks

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.fesu.renjana.utils.RenjanaLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * SafetyNet Bypass Module
 *
 * Evades Google SafetyNet Attestation by:
 * 1. Hooking SafetyNet API calls and returning spoofed responses
 * 2. Spoofing device properties to match certified devices
 * 3. Intercepting CTS (Compatibility Test Suite) profile checks
 * 4. Faking SafetyNet client app verification
 */
object SafetyNetBypass {
    private const val TAG = "SafetyNetBypass"

    // Spoofed SafetyNet response (passes basicIntegrity and ctsProfileMatch)
    private const val SPOOFED_JWS_PAYLOAD = """{"nonce":"%s","timestampMs":%d,"apkPackageName":"%s","apkDigestSha256":"%s","ctsProfileMatch":true,"basicIntegrity":true,"apkCertificateDigestSha256":["%s"],"evaluationType":"BASIC"}"""

    // Certified device fingerprints (Pixel 4, Pixel 5, etc.)
    private val certifiedFingerprints = arrayOf(
        "google/redfin/redfin:11/RQ3A.211001.001/7641976:user/release-keys",
        "google/coral/coral:11/RP1A.200720.009/6720564:user/release-keys",
        "google/sunfish/sunfish:11/RQ3A.211001.001/7641976:user/release-keys",
        "google/bramble/bramble:11/RQ3A.211001.001/7641976:user/release-keys"
    )

    // Device property overrides
    private val deviceOverrides = ConcurrentHashMap<String, String>()

    // Per-instance nonce cache
    private val nonceCache = ConcurrentHashMap<String, String>()

    /**
     * Initialize SafetyNet bypass for an instance
     */
    fun initialize(instanceId: String) {
        // Generate unique nonce for this instance
        val nonce = generateNonce(instanceId)
        nonceCache[instanceId] = nonce

        // Set up device property overrides
        setupDeviceOverrides()

        RenjanaLog.i(TAG, "SafetyNet bypass initialized for instance $instanceId")
    }

    /**
     * Set up device property overrides to match certified devices
     */
    private fun setupDeviceOverrides() {
        // Use Pixel 5 (redfin) as base
        deviceOverrides["BRAND"] = "google"
        deviceOverrides["DEVICE"] = "redfin"
        deviceOverrides["HARDWARE"] = "redfin"
        deviceOverrides["MODEL"] = "Pixel 5"
        deviceOverrides["PRODUCT"] = "redfin"
        deviceOverrides["MANUFACTURER"] = "Google"
        deviceOverrides["FINGERPRINT"] = certifiedFingerprints[0]
        deviceOverrides["BOARD"] = "redfin"
        deviceOverrides["BOOTLOADER"] = "redfin-11.0-7641976"

        // Security patch level (recent)
        deviceOverrides["ro.build.version.security_patch"] = "2021-10-01"

        RenjanaLog.d(TAG, "Device property overrides configured")
    }

    /**
     * Generate a unique nonce for SafetyNet requests
     */
    private fun generateNonce(instanceId: String): String {
        val timestamp = System.currentTimeMillis()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$instanceId-$timestamp".toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get spoofed SafetyNet JWS response
     *
     * @param instanceId Instance requesting attestation
     * @param packageName Package name of the app being attested
     * @param apkDigest APK digest hash
     * @param certDigest Certificate digest hash
     * @return Spoofed JWS payload
     */
    fun getSpoofedAttestationResponse(
        instanceId: String,
        packageName: String,
        apkDigest: String,
        certDigest: String
    ): String {
        val nonce = nonceCache[instanceId] ?: generateNonce(instanceId)
        val timestamp = System.currentTimeMillis()

        val payload = SPOOFED_JWS_PAYLOAD.format(
            nonce,
            timestamp,
            packageName,
            apkDigest,
            certDigest
        )

        RenjanaLog.d(TAG, "Generated spoofed attestation for $packageName")
        return payload
    }

    /**
     * Get spoofed device property
     *
     * @param propertyName Build property name (e.g., "BRAND", "MODEL")
     * @return Spoofed value or null if not overridden
     */
    fun getSpoofedProperty(propertyName: String): String? {
        return deviceOverrides[propertyName]
    }

    /**
     * Check if SafetyNet attestation request should be intercepted
     *
     * @param className Class being called
     * @param methodName Method being invoked
     * @return true if this is a SafetyNet API call
     */
    fun shouldInterceptSafetyNetCall(className: String, methodName: String): Boolean {
        val safetyNetClasses = setOf(
            "com.google.android.gms.safetynet.SafetyNet",
            "com.google.android.gms.safetynet.SafetyNetClient",
            "com.google.android.gms.safetynet.SafetyNetApi",
            "com.google.android.gms.safetynet.SafetyNetApi\$AttestationResponse"
        )

        val safetyNetMethods = setOf(
            "attest",
            "verifyWithRecaptcha",
            "lookupUri"
        )

        return className in safetyNetClasses && methodName in safetyNetMethods
    }

    /**
     * Spoof CTS profile match result
     *
     * @return true (always passes CTS check)
     */
    fun spoofCtsProfileMatch(): Boolean {
        RenjanaLog.d(TAG, "Spoofing CTS profile match: true")
        return true
    }

    /**
     * Spoof basic integrity result
     *
     * @return true (always passes basic integrity)
     */
    fun spoofBasicIntegrity(): Boolean {
        RenjanaLog.d(TAG, "Spoofing basic integrity: true")
        return true
    }

    /**
     * Check if device appears to be certified
     *
     * @return true (always certified)
     */
    fun isDeviceCertified(): Boolean {
        return true
    }

    /**
     * Get certified fingerprint for current instance
     *
     * @param instanceId Instance ID
     * @return Certified device fingerprint
     */
    fun getCertifiedFingerprint(instanceId: String): String {
        // Rotate fingerprints based on instance hash to avoid detection patterns
        val hash = instanceId.hashCode()
        val index = Math.abs(hash) % certifiedFingerprints.size
        return certifiedFingerprints[index]
    }

    /**
     * Cleanup instance-specific data
     */
    fun cleanup(instanceId: String) {
        nonceCache.remove(instanceId)
        RenjanaLog.d(TAG, "SafetyNet bypass cleaned up for instance $instanceId")
    }
}
