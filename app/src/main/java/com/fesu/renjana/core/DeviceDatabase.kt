package com.fesu.renjana.core

import kotlin.random.Random

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

data class DeviceProfile(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val buildFingerprint: String,
    val imeiPrefixes: List<String>,   // 8-digit TAC prefixes
    val displaySize: String,          // e.g. "6.1\""
    val resolution: String,           // e.g. "1080x2340"
    val dpi: Int,
    val ramGb: Int
)

// ---------------------------------------------------------------------------
// Singleton database
// ---------------------------------------------------------------------------

object DeviceDatabase {

    // -----------------------------------------------------------------------
    // Device catalogue  (35 profiles across 17 brands)
    // -----------------------------------------------------------------------

    val profiles: List<DeviceProfile> = listOf(

        // ── Samsung ────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "samsung", manufacturer = "Samsung",
            model = "SM-S911B",
            androidVersion = "14",
            buildFingerprint = "samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXS4CXA1:user/release-keys",
            imeiPrefixes = listOf("35386511", "35386512", "35386513"),
            displaySize = "6.1\"", resolution = "2340x1080", dpi = 425, ramGb = 8
        ),
        DeviceProfile(
            brand = "samsung", manufacturer = "Samsung",
            model = "SM-S918B",
            androidVersion = "14",
            buildFingerprint = "samsung/dm3qxxx/dm3q:14/UP1A.231005.007/S918BXXS4CXA1:user/release-keys",
            imeiPrefixes = listOf("35498711", "35498712", "35498713"),
            displaySize = "6.8\"", resolution = "3088x1440", dpi = 500, ramGb = 12
        ),
        DeviceProfile(
            brand = "samsung", manufacturer = "Samsung",
            model = "SM-A546E",
            androidVersion = "13",
            buildFingerprint = "samsung/a54xnsxx/a54x:13/TP1A.220624.014/A546EXXS3BWI1:user/release-keys",
            imeiPrefixes = listOf("35312211", "35312212"),
            displaySize = "6.4\"", resolution = "2340x1080", dpi = 400, ramGb = 8
        ),
        DeviceProfile(
            brand = "samsung", manufacturer = "Samsung",
            model = "SM-A235F",
            androidVersion = "12",
            buildFingerprint = "samsung/a23xxx/a23:12/SP1A.210812.016/A235FXXS4BXC1:user/release-keys",
            imeiPrefixes = listOf("35876511", "35876512"),
            displaySize = "6.6\"", resolution = "2408x1080", dpi = 400, ramGb = 4
        ),

        // ── Xiaomi ─────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "Xiaomi", manufacturer = "Xiaomi",
            model = "2210132G",   // Xiaomi 13
            androidVersion = "13",
            buildFingerprint = "Xiaomi/fuxi/fuxi:13/TKQ1.221114.001/V14.0.3.0.TMACNXM:user/release-keys",
            imeiPrefixes = listOf("86765410", "86765411", "86765412"),
            displaySize = "6.36\"", resolution = "2400x1080", dpi = 416, ramGb = 12
        ),
        DeviceProfile(
            brand = "Xiaomi", manufacturer = "Xiaomi",
            model = "21061119AG",  // Xiaomi 11T Pro
            androidVersion = "12",
            buildFingerprint = "Xiaomi/vili/vili:12/SKQ1.211006.001/V12.5.7.0.SKHMIXM:user/release-keys",
            imeiPrefixes = listOf("86234510", "86234511"),
            displaySize = "6.67\"", resolution = "2400x1080", dpi = 395, ramGb = 8
        ),
        DeviceProfile(
            brand = "Xiaomi", manufacturer = "Xiaomi",
            model = "22071212AG",  // Xiaomi 12T
            androidVersion = "12",
            buildFingerprint = "Xiaomi/plato/plato:12/SKQ1.211006.001/V13.1.1.0.SLUEUXM:user/release-keys",
            imeiPrefixes = listOf("86098710", "86098711"),
            displaySize = "6.67\"", resolution = "2712x1220", dpi = 446, ramGb = 8
        ),

        // ── Redmi (Xiaomi sub-brand) ────────────────────────────────────────
        DeviceProfile(
            brand = "Redmi", manufacturer = "Xiaomi",
            model = "220333QNY",  // Redmi Note 12 Pro
            androidVersion = "12",
            buildFingerprint = "Redmi/ruby/ruby:12/SKQ1.211019.001/V14.0.2.0.SMMCNXM:user/release-keys",
            imeiPrefixes = listOf("86543210", "86543211"),
            displaySize = "6.67\"", resolution = "2400x1080", dpi = 394, ramGb = 6
        ),

        // ── POCO (Xiaomi sub-brand) ─────────────────────────────────────────
        DeviceProfile(
            brand = "POCO", manufacturer = "Xiaomi",
            model = "21091116AG",  // POCO X3 GT
            androidVersion = "12",
            buildFingerprint = "POCO/casproj/casproj:12/SKQ1.210908.001/V13.0.1.0.SFJMIXM:user/release-keys",
            imeiPrefixes = listOf("86654310", "86654311"),
            displaySize = "6.6\"", resolution = "2400x1080", dpi = 395, ramGb = 8
        ),
        DeviceProfile(
            brand = "POCO", manufacturer = "Xiaomi",
            model = "22041219PG",  // POCO F4
            androidVersion = "13",
            buildFingerprint = "POCO/munch/munch:13/TKQ1.220829.002/V14.0.6.0.TLMMIXM:user/release-keys",
            imeiPrefixes = listOf("86743210", "86743211"),
            displaySize = "6.67\"", resolution = "2400x1080", dpi = 395, ramGb = 8
        ),

        // ── Realme ─────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "realme", manufacturer = "realme",
            model = "RMX3740",   // realme 11 Pro+
            androidVersion = "13",
            buildFingerprint = "realme/RMX3740/RE5C3L1:13/TP1A.220624.014/1690532473:user/release-keys",
            imeiPrefixes = listOf("86187610", "86187611", "86187612"),
            displaySize = "6.7\"", resolution = "2412x1080", dpi = 394, ramGb = 12
        ),
        DeviceProfile(
            brand = "realme", manufacturer = "realme",
            model = "RMX3491",   // realme 9 Pro+
            androidVersion = "12",
            buildFingerprint = "realme/RMX3491/RE54EBL1:12/SP1A.210812.016/1650436872:user/release-keys",
            imeiPrefixes = listOf("86098210", "86098211"),
            displaySize = "6.4\"", resolution = "2400x1080", dpi = 409, ramGb = 8
        ),

        // ── OPPO ───────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "OPPO", manufacturer = "OPPO",
            model = "CPH2449",   // OPPO Reno 8T
            androidVersion = "13",
            buildFingerprint = "OPPO/CPH2449/OP57C1L1:13/TP1A.220624.014/R.CPH2449_13.1:user/release-keys",
            imeiPrefixes = listOf("86365410", "86365411"),
            displaySize = "6.7\"", resolution = "2412x1080", dpi = 394, ramGb = 8
        ),
        DeviceProfile(
            brand = "OPPO", manufacturer = "OPPO",
            model = "CPH2387",   // OPPO A77s
            androidVersion = "12",
            buildFingerprint = "OPPO/CPH2387/OP57A7L1:12/SP1A.210812.016/R.CPH2387_12.1:user/release-keys",
            imeiPrefixes = listOf("86431210", "86431211"),
            displaySize = "6.56\"", resolution = "2412x1080", dpi = 401, ramGb = 8
        ),

        // ── Vivo ───────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "vivo", manufacturer = "vivo",
            model = "V2254",     // Vivo V27 Pro
            androidVersion = "13",
            buildFingerprint = "vivo/V2254/V2254:13/TP1A.220624.014/compiler05191113:user/release-keys",
            imeiPrefixes = listOf("86574310", "86574311"),
            displaySize = "6.78\"", resolution = "2400x1080", dpi = 388, ramGb = 12
        ),
        DeviceProfile(
            brand = "vivo", manufacturer = "vivo",
            model = "V2120",     // Vivo Y35
            androidVersion = "12",
            buildFingerprint = "vivo/V2120/V2120:12/SP1A.210812.016/compiler07011143:user/release-keys",
            imeiPrefixes = listOf("86432110", "86432111"),
            displaySize = "6.58\"", resolution = "2408x1080", dpi = 400, ramGb = 4
        ),

        // ── Huawei ─────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "HUAWEI", manufacturer = "HUAWEI",
            model = "ELS-NX9",   // Huawei P40 Pro
            androidVersion = "10",
            buildFingerprint = "HUAWEI/ELS-NX9/HWELS:10/HUAWEIELDS-NX9/102.0.0.200C00:user/release-keys",
            imeiPrefixes = listOf("86398710", "86398711"),
            displaySize = "6.58\"", resolution = "2640x1200", dpi = 441, ramGb = 8
        ),
        DeviceProfile(
            brand = "HUAWEI", manufacturer = "HUAWEI",
            model = "CDY-NX9A",  // Huawei Mate 40 Pro
            androidVersion = "10",
            buildFingerprint = "HUAWEI/CDY-NX9A/HWCDY:10/HUAWEICDY-NX9A/102.0.0.200C00:user/release-keys",
            imeiPrefixes = listOf("86498710", "86498711"),
            displaySize = "6.76\"", resolution = "2772x1344", dpi = 456, ramGb = 8
        ),

        // ── OnePlus ────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "OnePlus", manufacturer = "OnePlus",
            model = "CPH2449",   // OnePlus 11
            androidVersion = "13",
            buildFingerprint = "OnePlus/CPH2449/OP591L1:13/TP1A.220624.014/R.13_13.1.0.513(EX01):user/release-keys",
            imeiPrefixes = listOf("86834510", "86834511"),
            displaySize = "6.7\"", resolution = "3216x1440", dpi = 525, ramGb = 16
        ),
        DeviceProfile(
            brand = "OnePlus", manufacturer = "OnePlus",
            model = "NE2215",    // OnePlus 10 Pro
            androidVersion = "13",
            buildFingerprint = "OnePlus/NE2215EEA/OP515FL1:13/TP1A.220624.014/R.13_13.1.0.513(EX01):user/release-keys",
            imeiPrefixes = listOf("86712310", "86712311"),
            displaySize = "6.7\"", resolution = "3216x1440", dpi = 525, ramGb = 12
        ),

        // ── Motorola ───────────────────────────────────────────────────────
        DeviceProfile(
            brand = "motorola", manufacturer = "motorola",
            model = "XT2251-1",  // Motorola Edge 30 Pro
            androidVersion = "12",
            buildFingerprint = "motorola/eqs_g/eqs:12/S2SQS32.21-Q3-47-28-2/d4523:user/release-keys",
            imeiPrefixes = listOf("35654310", "35654311"),
            displaySize = "6.7\"", resolution = "2400x1080", dpi = 393, ramGb = 12
        ),
        DeviceProfile(
            brand = "motorola", manufacturer = "motorola",
            model = "XT2175-2",  // Motorola Moto G82
            androidVersion = "12",
            buildFingerprint = "motorola/rhodei_g/rhodei:12/S2SQS32.21-Q3-47-28/d4523:user/release-keys",
            imeiPrefixes = listOf("35765410", "35765411"),
            displaySize = "6.6\"", resolution = "2400x1080", dpi = 400, ramGb = 6
        ),

        // ── Nokia ──────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "Nokia", manufacturer = "HMD Global",
            model = "Nokia G60 5G",
            androidVersion = "12",
            buildFingerprint = "Nokia/Nokia_G60_5G/NokiaG605G:12/SP1A.210812.016/00WW_1_030:user/release-keys",
            imeiPrefixes = listOf("35876510", "35876511"),
            displaySize = "6.58\"", resolution = "2408x1080", dpi = 400, ramGb = 6
        ),
        DeviceProfile(
            brand = "Nokia", manufacturer = "HMD Global",
            model = "Nokia XR20",
            androidVersion = "13",
            buildFingerprint = "Nokia/Nokia_XR20/NokiaXR20:13/TP1A.220624.014/00WW_3_390:user/release-keys",
            imeiPrefixes = listOf("35987610", "35987611"),
            displaySize = "6.67\"", resolution = "2400x1080", dpi = 395, ramGb = 6
        ),

        // ── Google Pixel ───────────────────────────────────────────────────
        DeviceProfile(
            brand = "google", manufacturer = "Google",
            model = "Pixel 8",
            androidVersion = "14",
            buildFingerprint = "google/shiba/shiba:14/UD1A.231105.004/11010374:user/release-keys",
            imeiPrefixes = listOf("35361510", "35361511", "35361512"),
            displaySize = "6.2\"", resolution = "2268x1080", dpi = 428, ramGb = 8
        ),
        DeviceProfile(
            brand = "google", manufacturer = "Google",
            model = "Pixel 7",
            androidVersion = "14",
            buildFingerprint = "google/panther/panther:14/UD1A.231105.004/11010374:user/release-keys",
            imeiPrefixes = listOf("35247610", "35247611"),
            displaySize = "6.3\"", resolution = "2400x1080", dpi = 416, ramGb = 8
        ),
        DeviceProfile(
            brand = "google", manufacturer = "Google",
            model = "Pixel 6a",
            androidVersion = "13",
            buildFingerprint = "google/bluejay/bluejay:13/TP1A.220624.021.A1/8877034:user/release-keys",
            imeiPrefixes = listOf("35136510", "35136511"),
            displaySize = "6.1\"", resolution = "2400x1080", dpi = 429, ramGb = 6
        ),

        // ── Sony ───────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "Sony", manufacturer = "Sony",
            model = "XQ-BE72",   // Xperia 5 IV
            androidVersion = "13",
            buildFingerprint = "Sony/XQ-BE72/XQ-BE72:13/62.1.A.3.109/1039916936:user/release-keys",
            imeiPrefixes = listOf("35987510", "35987511"),
            displaySize = "6.1\"", resolution = "2520x1080", dpi = 449, ramGb = 8
        ),
        DeviceProfile(
            brand = "Sony", manufacturer = "Sony",
            model = "XQ-CT72",   // Xperia 1 V
            androidVersion = "13",
            buildFingerprint = "Sony/XQ-CT72/XQ-CT72:13/62.2.A.1.9/1040213756:user/release-keys",
            imeiPrefixes = listOf("35876410", "35876411"),
            displaySize = "6.5\"", resolution = "3840x1644", dpi = 643, ramGb = 12
        ),

        // ── LG ─────────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "LG", manufacturer = "LG Electronics",
            model = "LM-G900N",  // LG Velvet 5G
            androidVersion = "11",
            buildFingerprint = "lge/caymanlm_skt_kr/caymanlm:11/RKQ1.201202.002/2112202015380:user/release-keys",
            imeiPrefixes = listOf("35498610", "35498611"),
            displaySize = "6.8\"", resolution = "2460x1080", dpi = 395, ramGb = 8
        ),
        DeviceProfile(
            brand = "LG", manufacturer = "LG Electronics",
            model = "LM-V600N",  // LG V60 ThinQ
            androidVersion = "12",
            buildFingerprint = "lge/timelm_skt_kr/timelm:12/SKQ1.211020.001/2203302326180:user/release-keys",
            imeiPrefixes = listOf("35612310", "35612311"),
            displaySize = "6.8\"", resolution = "2460x1080", dpi = 395, ramGb = 8
        ),

        // ── ASUS ROG ───────────────────────────────────────────────────────
        DeviceProfile(
            brand = "asus", manufacturer = "asus",
            model = "ASUS_AI2203",  // ROG Phone 6 Pro
            androidVersion = "12",
            buildFingerprint = "asus/WW_AI2203/ASUS_AI2203:12/SKQ1.211126.001/31.1004.0404.89:user/release-keys",
            imeiPrefixes = listOf("35234510", "35234511"),
            displaySize = "6.78\"", resolution = "2448x1080", dpi = 394, ramGb = 16
        ),
        DeviceProfile(
            brand = "asus", manufacturer = "asus",
            model = "ASUS_AI2302",  // ROG Phone 7 Ultimate
            androidVersion = "13",
            buildFingerprint = "asus/WW_AI2302/ASUS_AI2302:13/TKQ1.230114.001/33.0204.2023.47:user/release-keys",
            imeiPrefixes = listOf("35345610", "35345611"),
            displaySize = "6.78\"", resolution = "2448x1080", dpi = 394, ramGb = 16
        ),

        // ── Infinix ────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "Infinix", manufacturer = "Infinix",
            model = "X6817",     // Infinix NOTE 12 Pro
            androidVersion = "12",
            buildFingerprint = "Infinix/X6817/Infinix-X6817:12/SP1A.210812.016/231113V167:user/release-keys",
            imeiPrefixes = listOf("35876310", "35876311"),
            displaySize = "6.7\"", resolution = "2400x1080", dpi = 395, ramGb = 8
        ),
        DeviceProfile(
            brand = "Infinix", manufacturer = "Infinix",
            model = "X669C",     // Infinix Smart 7 HD
            androidVersion = "12",
            buildFingerprint = "Infinix/X669C/Infinix-X669C:12/SP1A.210812.016/230301V187:user/release-keys",
            imeiPrefixes = listOf("35765310", "35765311"),
            displaySize = "6.6\"", resolution = "1612x720", dpi = 268, ramGb = 2
        ),

        // ── Tecno ──────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "TECNO", manufacturer = "TECNO",
            model = "CG7n",      // TECNO CAMON 19 Pro
            androidVersion = "12",
            buildFingerprint = "TECNO/CG7n/TECNO-CG7n:12/SP1A.210812.016/220629V122:user/release-keys",
            imeiPrefixes = listOf("35987310", "35987311"),
            displaySize = "6.8\"", resolution = "2460x1080", dpi = 396, ramGb = 8
        ),

        // ── itel ───────────────────────────────────────────────────────────
        DeviceProfile(
            brand = "itel", manufacturer = "itel",
            model = "itel P55+",
            androidVersion = "13",
            buildFingerprint = "itel/itel-P661L/itel-itel-P661L:13/TP1A.220624.014/230901V217:user/release-keys",
            imeiPrefixes = listOf("35654210", "35654211"),
            displaySize = "6.78\"", resolution = "2460x1080", dpi = 395, ramGb = 4
        )
    )

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Deterministic profile selection based on seed. */
    fun randomProfile(seed: Long): DeviceProfile {
        val rng = Random(seed)
        return profiles[rng.nextInt(profiles.size)]
    }

    /** Non-deterministic random profile. */
    fun randomProfile(): DeviceProfile = profiles[Random.nextInt(profiles.size)]

    /**
     * Generate a valid 15-digit IMEI for the given profile.
     * Format: TAC (8 digits) + SNR (6 digits) + Luhn check digit.
     *
     * @param seed  deterministic random seed; pass distinct values per instance.
     */
    fun generateImei(profile: DeviceProfile, seed: Long): String {
        val rng = Random(seed)
        val tac = profile.imeiPrefixes[rng.nextInt(profile.imeiPrefixes.size)]
        val snr = buildString {
            repeat(6) { append(rng.nextInt(10)) }
        }
        val partial = tac + snr          // 14 digits
        val check = luhnCheckDigit(partial)
        return partial + check
    }

    /**
     * Generate a realistic 16-character hex Android ID.
     * Android IDs are 64-bit values represented as lowercase hex.
     */
    fun generateAndroidId(seed: Long): String {
        val rng = Random(seed xor 0x4E4E_4E4E_4E4EL)
        return buildString {
            repeat(16) { append("0123456789abcdef"[rng.nextInt(16)]) }
        }
    }

    /**
     * Generate a brand-realistic serial number.
     * Formats:
     *   Samsung   → RXxxxxxxxx  (10 chars, alphanumeric)
     *   Xiaomi/Redmi/POCO → MExxxxxxxx (10 chars)
     *   Realme    → RExxxxxxxx
     *   OPPO      → OPxxxxxxxx
     *   Vivo      → VVxxxxxxxx
     *   Huawei    → HWxxxxxxxx
     *   OnePlus   → ONxxxxxxxx
     *   Motorola  → ZRxxxxxxxx
     *   Nokia     → NKxxxxxxxx
     *   Google    → GA0xxxxxxx
     *   Sony      → SYxxxxxxxx
     *   LG        → LGxxxxxxxx
     *   ASUS      → ASxxxxxxxx
     *   Infinix   → IXxxxxxxxx
     *   Tecno     → TCxxxxxxxx
     *   itel      → ITxxxxxxxx
     *   default   → XXxxxxxxxx
     */
    fun generateSerial(brand: String, seed: Long): String {
        val rng = Random(seed xor 0x5A5A_5A5A_5A5AL)
        val prefix = when (brand.lowercase()) {
            "samsung"                    -> "RX"
            "xiaomi", "redmi", "poco"    -> "ME"
            "realme"                     -> "RE"
            "oppo"                       -> "OP"
            "vivo"                       -> "VV"
            "huawei"                     -> "HW"
            "oneplus"                    -> "ON"
            "motorola"                   -> "ZR"
            "nokia"                      -> "NK"
            "google"                     -> "GA"
            "sony"                       -> "SY"
            "lg"                         -> "LG"
            "asus"                       -> "AS"
            "infinix"                    -> "IX"
            "tecno"                      -> "TC"
            "itel"                       -> "IT"
            else                         -> "XX"
        }
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val suffix = buildString { repeat(8) { append(chars[rng.nextInt(chars.length)]) } }
        return prefix + suffix
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Compute the Luhn check digit for a numeric string.
     *
     * Algorithm:
     *   1. Process digits right-to-left.
     *   2. Double every second digit (starting from the rightmost + 1).
     *   3. If doubled value > 9, subtract 9.
     *   4. Sum all processed digits.
     *   5. Check digit = (10 - (sum % 10)) % 10.
     */
    private fun luhnCheckDigit(partial: String): Int {
        var sum = 0
        var doubleIt = true   // rightmost of the partial → position 1 (double)
        for (i in partial.indices.reversed()) {
            var digit = partial[i].digitToInt()
            if (doubleIt) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleIt = !doubleIt
        }
        return (10 - (sum % 10)) % 10
    }

    // -----------------------------------------------------------------------
    // Extended fingerprint generators
    // -----------------------------------------------------------------------

    fun generateCanvasHash(seed: Long): String {
        val rng = Random(seed xor 0xCAFEBABEL)
        return (0 until 32).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")
    }

    data class ScreenSpecs(val dpi: Int, val widthDp: Int, val heightDp: Int, val refreshRate: Float)

    fun generateScreenSpecs(profile: DeviceProfile, seed: Long): ScreenSpecs {
        val rng = Random(seed)
        val dpi = profile.dpi
        val refreshRate = when {
            profile.brand.contains("asus", true) || profile.brand.contains("poco", true) ->
                listOf(144f, 120f)[rng.nextInt(2)]
            profile.brand.contains("oneplus", true) || profile.brand.contains("samsung", true) ->
                listOf(120f, 90f)[rng.nextInt(2)]
            else -> 60f
        }
        val resolution = profile.resolution.split("x")
        val widthPx = resolution.getOrNull(0)?.toIntOrNull() ?: 1080
        val heightPx = resolution.getOrNull(1)?.toIntOrNull() ?: 2400
        val widthDp = (widthPx * 160 / dpi.coerceAtLeast(1))
        val heightDp = (heightPx * 160 / dpi.coerceAtLeast(1))
        return ScreenSpecs(dpi, widthDp, heightDp, refreshRate)
    }

    data class SensorProfile(
        val accelerometer: Boolean,
        val gyroscope: Boolean,
        val magnetometer: Boolean,
        val barometer: Boolean,
        val proximity: Boolean
    )

    fun generateSensorProfile(profile: DeviceProfile, seed: Long): SensorProfile {
        val rng = Random(seed)
        val isFlagship = profile.ramGb >= 8
        val isMidRange = profile.ramGb >= 6
        return SensorProfile(
            accelerometer = true,
            gyroscope = isMidRange || isFlagship,
            magnetometer = isMidRange || isFlagship,
            barometer = isFlagship && rng.nextBoolean(),
            proximity = true
        )
    }

    fun generateBatteryCapacity(profile: DeviceProfile, seed: Long): Int {
        val rng = Random(seed)
        return when {
            profile.brand.contains("samsung", true) && profile.ramGb >= 8 ->
                listOf(4700, 5000, 4500)[rng.nextInt(3)]
            profile.brand.contains("xiaomi", true) || profile.brand.contains("redmi", true) ->
                listOf(5000, 4500, 5160)[rng.nextInt(3)]
            profile.ramGb >= 8 -> listOf(4600, 5000, 4800)[rng.nextInt(3)]
            else -> listOf(4000, 4500, 5000)[rng.nextInt(3)]
        }
    }

    fun generateWifiMacPrefix(brand: String, seed: Long): String {
        val rng = Random(seed)
        val prefixes = when {
            brand.contains("samsung", true) -> listOf("AC:37:43", "F4:42:8F", "78:40:E4", "A0:07:98")
            brand.contains("xiaomi", true) || brand.contains("redmi", true) -> listOf("00:EC:0A", "64:B4:73", "F8:A2:D6", "34:80:B3")
            brand.contains("realme", true) || brand.contains("oppo", true) -> listOf("44:B7:A1", "FC:3F:DB", "A0:A3:B3")
            brand.contains("vivo", true) -> listOf("E8:5D:86", "C4:0B:CB", "80:EA:96")
            brand.contains("oneplus", true) -> listOf("94:65:2D", "A0:4E:CF", "48:DB:50")
            brand.contains("google", true) -> listOf("54:60:09", "20:DF:B9", "64:BC:0C")
            brand.contains("huawei", true) -> listOf("AC:E2:D3", "00:E0:FC", "54:A5:1B")
            else -> listOf("00:11:22", "AA:BB:CC", "11:22:33")
        }
        return prefixes[rng.nextInt(prefixes.size)]
    }
}