package com.fesu.renjana.core

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import com.fesu.renjana.utils.RenjanaLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Matches incoming Intents against registered filters to determine routing.
 * 
 * Implements Android's IntentFilter matching algorithm:
 * 1. Action must match (or filter has no actions)
 * 2. Category must match (or filter has no categories)
 * 3. Data must match (type, scheme, host, port, path)
 * 
 * Filters are matched in priority order (higher priority first).
 */
class IntentFilterManager {

    private val TAG = "IntentFilter"

    // Note: companion object with MANIFEST_KEYWORDS is defined at the bottom of the class

    /**
     * Registered filter entry with metadata.
     */
    data class FilterEntry(
        val packageName: String,
        val componentName: String,
        val filter: IntentFilter,
        val priority: Int = 0,
        val isExported: Boolean = true
    )

    /**
     * All registered filters, sorted by priority (descending).
     */
    private val filters = CopyOnWriteArrayList<FilterEntry>()

    /**
     * Register a new filter for a component.
     */
    fun registerFilter(entry: FilterEntry) {
        filters.add(entry)
        filters.sortByDescending { it.priority }
        RenjanaLog.d(TAG, "Registered filter: ${entry.componentName} priority=${entry.priority}")
    }

    /**
     * Unregister all filters for a package.
     */
    fun unregisterPackage(packageName: String) {
        val removed = filters.removeAll { it.packageName == packageName }
        if (removed) {
            RenjanaLog.d(TAG, "Unregistered filters for package: $packageName")
        }
    }

    /**
     * Find all filters matching the given Intent.
     * Returns entries sorted by priority (highest first).
     */
    fun match(intent: Intent): List<FilterEntry> {
        val matches = mutableListOf<FilterEntry>()

        for (entry in filters) {
            if (!entry.isExported) continue
            if (matchesFilter(intent, entry.filter)) {
                matches.add(entry)
            }
        }

        RenjanaLog.d(TAG, "Found ${matches.size} matches for Intent: ${intent.action}")
        return matches
    }

    /**
     * Check if an Intent matches a specific filter.
     * Implements Android's IntentFilter matching logic.
     */
    private fun matchesFilter(intent: Intent, filter: IntentFilter): Boolean {
        // 1. Match action
        if (!matchAction(intent, filter)) return false

        // 2. Match category
        if (!matchCategory(intent, filter)) return false

        // 3. Match data (type + URI)
        if (!matchData(intent, filter)) return false

        return true
    }

    /**
     * Match Intent action against filter.
     * - If filter has no actions, Intent must have no action
     * - If filter has actions, Intent action must be in the list
     */
    private fun matchAction(intent: Intent, filter: IntentFilter): Boolean {
        val intentAction = intent.action
        val filterActionCount = filter.countActions()

        // Filter with no actions matches only Intents with no action
        if (filterActionCount == 0) {
            return intentAction == null
        }

        // Intent with no action cannot match filter with actions
        if (intentAction == null) return false

        // Check if action is in filter
        for (i in 0 until filterActionCount) {
            if (filter.getAction(i) == intentAction) return true
        }

        RenjanaLog.v(TAG, "Action mismatch: $intentAction not in filter")
        return false
    }

    /**
     * Match Intent categories against filter.
     * - All Intent categories must be in filter (or filter has no categories)
     * - CATEGORY_DEFAULT is required for startActivity() resolution
     */
    private fun matchCategory(intent: Intent, filter: IntentFilter): Boolean {
        val intentCategories = intent.categories
        val filterCategoryCount = filter.countCategories()

        // Intent with no categories always matches
        if (intentCategories == null || intentCategories.isEmpty()) return true

        // Filter with no categories cannot match Intent with categories
        if (filterCategoryCount == 0) {
            RenjanaLog.v(TAG, "Category mismatch: Intent has categories but filter has none")
            return false
        }

        // Every Intent category must be in filter
        for (category in intentCategories) {
            if (!filter.hasCategory(category)) {
                RenjanaLog.v(TAG, "Category mismatch: $category not in filter")
                return false
            }
        }

        return true
    }

    /**
     * Match Intent data (type + URI) against filter.
     * Complex matching logic for MIME type, scheme, host, port, path.
     */
    private fun matchData(intent: Intent, filter: IntentFilter): Boolean {
        val intentType = intent.type
        val intentData = intent.data
        val dataTypeCount = filter.countDataTypes()
        val schemeCount = filter.countDataSchemes()

        // Filter with no data specs matches any data
        if (dataTypeCount == 0 && schemeCount == 0) {
            return intentType == null && intentData == null
        }

        // Intent with no data cannot match filter with data specs
        if (intentType == null && intentData == null) {
            RenjanaLog.v(TAG, "Data mismatch: Intent has no data but filter requires it")
            return false
        }

        // Match MIME type
        if (intentType != null && dataTypeCount > 0) {
            var typeMatched = false
            for (i in 0 until dataTypeCount) {
                val filterType = filter.getDataType(i)
                if (matchMimeType(intentType, filterType)) {
                    typeMatched = true
                    break
                }
            }
            if (!typeMatched) {
                RenjanaLog.v(TAG, "Type mismatch: $intentType not in filter")
                return false
            }
        }

        // Match URI scheme/host/port/path
        if (intentData != null) {
            if (!matchUri(intentData, filter)) return false
        }

        return true
    }

    /**
     * Match MIME type with wildcard support.
     * Examples:
     * - "image/&#42;" matches "image/jpeg"
     * - "&#42;/&#42;" matches anything
     */
    private fun matchMimeType(intentType: String, filterType: String): Boolean {
        if (filterType == "*/*") return true

        val intentParts = intentType.split("/")
        val filterParts = filterType.split("/")

        if (intentParts.size != 2 || filterParts.size != 2) return false

        val (intentMajor, intentMinor) = intentParts
        val (filterMajor, filterMinor) = filterParts

        // Major type must match or filter is wildcard
        if (filterMajor != "*" && filterMajor != intentMajor) return false

        // Minor type must match or filter is wildcard
        if (filterMinor != "*" && filterMinor != intentMinor) return false

        return true
    }

    /**
     * Match URI components (scheme, host, port, path).
     */
    private fun matchUri(uri: Uri, filter: IntentFilter): Boolean {
        // Match scheme
        val schemeCount = filter.countDataSchemes()
        if (schemeCount > 0) {
            val uriScheme = uri.scheme
            if (uriScheme == null) return false

            var schemeMatched = false
            for (i in 0 until schemeCount) {
                if (filter.getDataScheme(i) == uriScheme) {
                    schemeMatched = true
                    break
                }
            }
            if (!schemeMatched) {
                RenjanaLog.v(TAG, "Scheme mismatch: $uriScheme not in filter")
                return false
            }
        }

        // Match authority (host + port)
        val authorityCount = filter.countDataAuthorities()
        if (authorityCount > 0) {
            val uriAuthority = uri.authority
            if (uriAuthority == null) return false

            var authorityMatched = false
            for (i in 0 until authorityCount) {
                val auth = filter.getDataAuthority(i)
                if (matchAuthority(uriAuthority, auth)) {
                    authorityMatched = true
                    break
                }
            }
            if (!authorityMatched) {
                RenjanaLog.v(TAG, "Authority mismatch: $uriAuthority not in filter")
                return false
            }
        }

        // Match path
        val pathCount = filter.countDataPaths()
        if (pathCount > 0) {
            val uriPath = uri.path
            if (uriPath == null) return false

            var pathMatched = false
            for (i in 0 until pathCount) {
                val pathPattern = filter.getDataPath(i)
                if (matchPath(uriPath, pathPattern.path, pathPattern.type)) {
                    pathMatched = true
                    break
                }
            }
            if (!pathMatched) {
                RenjanaLog.v(TAG, "Path mismatch: $uriPath not in filter")
                return false
            }
        }

        return true
    }

    /**
     * Match authority with wildcard support.
     * Examples:
     * - "*.example.com" matches "sub.example.com"
     * - "example.com:8080" matches exact host and port
     */
    private fun matchAuthority(uriAuthority: String, filterAuthority: IntentFilter.AuthorityEntry): Boolean {
        val uriHost = uriAuthority.substringBefore(":")
        val uriPort = uriAuthority.substringAfter(":", "").toIntOrNull()

        val filterHost = filterAuthority.host
        val filterPort = filterAuthority.port

        // Match host with wildcard
        if (filterHost.startsWith("*.")) {
            val suffix = filterHost.substring(1) // ".example.com"
            if (!uriHost.endsWith(suffix) && uriHost != filterHost.substring(2)) return false
        } else {
            if (uriHost != filterHost) return false
        }

        // Match port if specified
        if (filterPort != -1 && filterPort != uriPort) return false

        return true
    }

    /**
     * Match path with pattern type.
     * Types: PATTERN_LITERAL, PATTERN_PREFIX, PATTERN_SIMPLE_GLOB, PATTERN_ADVANCED_GLOB
     */
    private fun matchPath(uriPath: String, filterPath: String, patternType: Int): Boolean {
        return when (patternType) {
            android.os.PatternMatcher.PATTERN_LITERAL -> uriPath == filterPath
            android.os.PatternMatcher.PATTERN_PREFIX -> uriPath.startsWith(filterPath)
            android.os.PatternMatcher.PATTERN_SIMPLE_GLOB -> matchSimpleGlob(uriPath, filterPath)
            android.os.PatternMatcher.PATTERN_ADVANCED_GLOB -> matchAdvancedGlob(uriPath, filterPath)
            else -> false
        }
    }

    /**
     * Simple glob matching (* wildcard only).
     */
    private fun matchSimpleGlob(path: String, pattern: String): Boolean {
        if (pattern == "*") return true

        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
        return Regex("^$regex$").matches(path)
    }

    /**
     * Advanced glob matching (supports *, ?, character classes).
     */
    private fun matchAdvancedGlob(path: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$regex$").matches(path)
    }

    // ==================== Deep Link Scheme Registry ====================

    /**
     * Maps URI scheme (e.g. "shopee", "gojek") -> instanceId.
     * Populated when a guest APK is launched by parsing its AndroidManifest.xml.
     */
    private val schemeRegistry = ConcurrentHashMap<String, String>()

    /**
     * Register a custom URI scheme for a virtual instance.
     * Called when a guest APK is launched and its manifest is parsed.
     *
     * @param scheme     URI scheme string, e.g. "shopee", "gojek", "https"
     * @param instanceId The Renjana instance that owns this scheme
     */
    fun registerScheme(scheme: String, instanceId: String) {
        schemeRegistry[scheme] = instanceId
        RenjanaLog.d(TAG, "Registered deep link scheme: $scheme -> $instanceId")
    }

    /**
     * Check if a URI scheme is registered to any virtual instance.
     */
    fun hasScheme(scheme: String): Boolean = schemeRegistry.containsKey(scheme)

    /**
     * Return the instanceId that owns the given URI scheme, or null if not registered.
     */
    fun resolveScheme(scheme: String): String? = schemeRegistry[scheme]

    /**
     * Parse the guest APK's AndroidManifest.xml, extract all <data android:scheme=...>
     * entries from intent-filters, and register each scheme to the given instance.
     *
     * Uses ZipFile + DocumentBuilderFactory — no external dependencies required.
     *
     * @param apkPath    Absolute path to the guest .apk file
     * @param instanceId The Renjana instance to associate schemes with
     */
    fun registerSchemesFromApk(apkPath: String, instanceId: String) {
        try {
            ZipFile(apkPath).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: run {
                    RenjanaLog.w(TAG, "No AndroidManifest.xml in APK: $apkPath")
                    return
                }
                // AndroidManifest.xml inside APK is binary XML — we use the parsed
                // representation via DocumentBuilderFactory only when it is text XML.
                // For binary AXML we fall back to a byte-level scheme scan.
                zip.getInputStream(manifestEntry).use { stream ->
                    val bytes = stream.readBytes()
                    val schemes = extractSchemesFromManifestBytes(bytes)
                    for (scheme in schemes) {
                        if (scheme.isNotBlank()) {
                            registerScheme(scheme, instanceId)
                        }
                    }
                    RenjanaLog.i(TAG, "Registered ${schemes.size} scheme(s) from APK for instance $instanceId")
                }
            }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to parse APK manifest for schemes: ${e.message}")
        }
    }

    /**
     * Extract URI schemes from manifest bytes.
     *
     * Android APK manifests are binary AXML. We use two strategies:
     * 1. Try DocumentBuilderFactory (works for text XML, e.g. in test/debug APKs).
     * 2. Fallback: scan for the string "scheme" in the binary AXML and read the
     *    null-terminated UTF-16 LE value that follows — works for production APKs.
     *
     * This avoids adding any external AXML-parsing dependency.
     */
    private fun extractSchemesFromManifestBytes(bytes: ByteArray): Set<String> {
        val schemes = mutableSetOf<String>()

        // Strategy 1: text XML (test APKs, merged manifests)
        try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(bytes.inputStream())
            val dataNodes = doc.getElementsByTagName("data")
            for (i in 0 until dataNodes.length) {
                val scheme = dataNodes.item(i).attributes
                    ?.getNamedItem("android:scheme")?.nodeValue
                if (!scheme.isNullOrBlank()) schemes.add(scheme)
            }
            if (schemes.isNotEmpty()) return schemes
        } catch (_: Exception) {
            // binary AXML — fall through to Strategy 2
        }

        // Strategy 2: binary AXML string-pool scan.
        // The string pool in binary AXML stores UTF-16 LE strings. We scan for the
        // byte pattern of "scheme" (as UTF-16 LE) and then collect values that appear
        // in data-attribute positions. This is a heuristic but reliable for well-formed APKs.
        try {
            val schemeTag = "scheme".toByteArray(Charsets.UTF_16LE)
            var i = 0
            while (i < bytes.size - schemeTag.size) {
                var match = true
                for (j in schemeTag.indices) {
                    if (bytes[i + j] != schemeTag[j]) { match = false; break }
                }
                if (match) {
                    // The value string typically follows within the next 512 bytes.
                    // Read UTF-16 LE strings nearby that look like URI schemes.
                    val window = bytes.copyOfRange(
                        i + schemeTag.size,
                        minOf(i + schemeTag.size + 512, bytes.size)
                    )
                    val text = String(window, Charsets.UTF_16LE)
                    // Extract tokens that look like URI schemes (alphanumeric + dot/plus/hyphen)
                    val schemeRegex = Regex("[a-zA-Z][a-zA-Z0-9+\\-.]{1,30}")
                    schemeRegex.findAll(text).forEach { result ->
                        val candidate = result.value.lowercase()
                        // Exclude common XML/manifest keywords that aren't URI schemes
                        if (candidate !in Companion.MANIFEST_KEYWORDS) {
                            schemes.add(candidate)
                        }
                    }
                }
                i++
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Binary AXML scheme scan failed: ${e.message}")
        }

        return schemes
    }

    /**
     * Remove all scheme registrations for a given instance.
     * Called when an instance is stopped or deleted.
     */
    fun unregisterSchemes(instanceId: String) {
        val removed = schemeRegistry.entries.removeAll { it.value == instanceId }
        if (removed) {
            RenjanaLog.d(TAG, "Unregistered deep link schemes for instance: $instanceId")
        }
    }

    /**
     * Clear all registered filters AND scheme registrations.
     */
    fun clear() {
        filters.clear()
        schemeRegistry.clear()
        RenjanaLog.i(TAG, "All filters and schemes cleared")
    }

    /**
     * Get count of registered filters.
     */
    fun size(): Int = filters.size

    companion object {
        /** Common XML/manifest attribute names that are not URI schemes. */
        private val MANIFEST_KEYWORDS = setOf(
            "android", "package", "scheme", "host", "path", "port", "type",
            "action", "category", "data", "intent", "filter", "activity",
            "service", "receiver", "provider", "application", "manifest",
            "uses", "permission", "feature", "sdk", "version", "name",
            "label", "icon", "theme", "exported", "enabled", "process",
            "true", "false", "null", "string", "integer", "boolean"
        )
    }
}
