package com.fesu.renjana.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A container instance wrapping a single guest APK.
 *
 * Renjana is NOT a virtual machine or full OS sandbox. Each instance is a
 * lightweight container — it runs the guest APK's code directly on the host
 * Android runtime, but with isolated storage, optional identity spoofing,
 * and optional GMS virtualization.
 *
 * Think: smart container, not virtual machine.
 */
@Parcelize
data class Instance(
    val id: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val apkPath: String,
    val iconPath: String?,
    val accountId: String?,      // linked Google account (null = no account)
    val dataPath: String,
    val createdAt: Long,
    val lastUsed: Long,
    val isActive: Boolean,
    val config: InstanceConfig = InstanceConfig()
) : Parcelable

/**
 * Per-instance feature toggles.
 *
 * All features are opt-in. A minimal container has all defaults (storage
 * isolation only). GMS and fingerprint are independent — you can spoof the
 * device fingerprint without enabling GMS virtualization, or vice versa.
 */
@Parcelize
data class InstanceConfig(

    // ── Storage Isolation ──────────────────────────────────────────────────
    // Always on. Each instance gets its own files/, databases/, shared_prefs/.
    // Not a toggle — isolation is the point of the container.

    // ── Google Mobile Services ─────────────────────────────────────────────
    /** Enable GMS virtualization for this instance.
     *  When ON: Google Sign-In, Firebase, Play Billing are intercepted and
     *  routed through the account assigned to this instance.
     *  When OFF: GMS calls pass through to the host device's real Google
     *  account (or fail silently if no GMS is available). */
    val enableGms: Boolean = false,

    // ── Device Fingerprint ─────────────────────────────────────────────────
    /** Enable per-instance device identity spoofing.
     *  When ON: ANDROID_ID, Build props (SERIAL, FINGERPRINT, MODEL, etc.)
     *  are randomized per-instance so each container looks like a different
     *  physical device. Values are stable across launches for the same instance. */
    val enableFingerprint: Boolean = false,

    /** Override the generated fingerprint seed (null = auto from instance ID).
     *  Set this to pin the instance to a specific spoofed identity. */
    val fingerprintSeed: String? = null,

    // ── Signature Spoofing ─────────────────────────────────────────────────
    /** Return the original APK's signature when the guest app queries its own
     *  PackageInfo. Needed for apps that self-verify (e.g. banking apps). */
    val spoofSignature: Boolean = true,

    // ── Network ────────────────────────────────────────────────────────────
    /** Route all network traffic through an isolated proxy layer.
     *  Enables per-instance traffic inspection and separation. */
    val isolateNetwork: Boolean = false,

    // ── Anti-Detection ─────────────────────────────────────────────────────
    /** Run the full anti-detection stack: hide container paths from the guest,
     *  filter /proc/maps for hook library artifacts, block Frida port scans,
     *  intercept SafetyNet/Play Integrity API calls. */
    val enableAntiDetection: Boolean = true,

    // ── Device Spoof Values (custom override, null = auto-generate) ──────────
    /** Custom device model (e.g., "Pixel 7"). Null = auto from seed. */
    val spoofModel: String? = null,
    /** Custom device brand (e.g., "Google"). Null = auto from seed. */
    val spoofBrand: String? = null,
    /** Custom device manufacturer (e.g., "Google"). Null = auto from seed. */
    val spoofManufacturer: String? = null,
    /** Custom Android version string (e.g., "13"). Null = auto from seed. */
    val spoofAndroidVersion: String? = null,
    /** Custom ANDROID_ID (16-char hex). Null = auto from seed. */
    val spoofAndroidId: String? = null,
    /** Custom build serial. Null = auto from seed. */
    val spoofSerial: String? = null,

    // ── Extended Fingerprint ───────────────────────────────────────────────
    // Canvas fingerprint
    val canvasHash: String? = null,          // 32-char hex hash of canvas rendering
    val canvasNoise: Float? = null,          // subtle noise level 0.0-1.0
    // Screen
    val screenDensityDpi: Int? = null,       // e.g. 440
    val screenWidthDp: Int? = null,          // e.g. 392
    val screenHeightDp: Int? = null,         // e.g. 848
    val screenRefreshRate: Float? = null,    // e.g. 60.0, 90.0, 120.0
    // Sensors
    val sensorAccelerometer: Boolean? = null,
    val sensorGyroscope: Boolean? = null,
    val sensorMagnetometer: Boolean? = null,
    val sensorBarometer: Boolean? = null,
    val sensorProximity: Boolean? = null,
    // Battery
    val batteryCapacityMah: Int? = null,     // e.g. 4500
    // Network
    val wifiMacPrefix: String? = null,       // e.g. "AC:37:43" (first 3 octets)

) : Parcelable

