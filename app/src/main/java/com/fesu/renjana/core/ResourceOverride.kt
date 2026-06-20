package com.fesu.renjana.core

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-instance resource override engine.
 *
 * Supports overriding strings, colors, drawables (by path), layouts (by path),
 * and integer resources at runtime without repackaging the APK.
 *
 * Overrides are persisted as JSON in the instance's data directory so they
 * survive container restarts.
 *
 * Thread-safety: all mutations go through ConcurrentHashMap-backed stores;
 * persistence is synchronised on a dedicated lock.
 */
class ResourceOverride(
    private val instanceId: String,
    private val dataPath: String
) {
    companion object {
        private const val TAG = "ResourceOverride"
        private const val OVERRIDES_FILE = "resource_overrides.json"
    }

    // ── In-memory stores (resource key → override value) ────────────────
    private val stringOverrides = ConcurrentHashMap<String, String>()
    private val colorOverrides = ConcurrentHashMap<String, Int>()
    private val intOverrides = ConcurrentHashMap<String, Int>()
    private val drawablePathOverrides = ConcurrentHashMap<String, String>() // resKey → file path
    private val layoutPathOverrides = ConcurrentHashMap<String, String>()   // resKey → file path
    private val colorStateListOverrides = ConcurrentHashMap<String, ColorStateList>()
    private val drawableOverrides = ConcurrentHashMap<String, Drawable>()

    // Runtime cache of resolved drawables (path → Drawable) to avoid re-decoding
    private val resolvedDrawables = ConcurrentHashMap<String, Drawable>()

    private val gson = Gson()
    private val persistLock = Any()
    private val overridesFile = File(dataPath, OVERRIDES_FILE)

    init {
        loadFromDisk()
    }

    // ════════════════════════════════════════════════════════════════════
    //  Query API
    // ════════════════════════════════════════════════════════════════════

    fun hasStringOverride(resKey: String): Boolean = stringOverrides.containsKey(resKey)
    fun hasColorOverride(resKey: String): Boolean = colorOverrides.containsKey(resKey)
    fun hasIntOverride(resKey: String): Boolean = intOverrides.containsKey(resKey)
    fun hasDrawableOverride(resKey: String): Boolean =
        drawablePathOverrides.containsKey(resKey) || drawableOverrides.containsKey(resKey)
    fun hasLayoutOverride(resKey: String): Boolean = layoutPathOverrides.containsKey(resKey)
    fun hasColorStateListOverride(resKey: String): Boolean =
        colorStateListOverrides.containsKey(resKey)

    fun getStringOverride(resKey: String): String? = stringOverrides[resKey]
    fun getColorOverride(resKey: String): Int? = colorOverrides[resKey]
    fun getIntOverride(resKey: String): Int? = intOverrides[resKey]

    fun getDrawableOverride(resKey: String, hostResources: android.content.res.Resources): Drawable? {
        // Prefer in-memory Drawable
        drawableOverrides[resKey]?.let { return it }

        // Decode from path
        val path = drawablePathOverrides[resKey] ?: return null
        return resolvedDrawables.getOrPut(path) {
            decodeDrawable(path, hostResources)
        }
    }

    fun getLayoutOverride(resKey: String): String? = layoutPathOverrides[resKey]

    fun getColorStateListOverride(resKey: String): ColorStateList? =
        colorStateListOverrides[resKey]

    // ════════════════════════════════════════════════════════════════════
    //  Mutation API
    // ════════════════════════════════════════════════════════════════════

    fun setStringOverride(resKey: String, value: String) {
        stringOverrides[resKey] = value
        persistAsync()
    }

    fun setColorOverride(resKey: String, argb: Int) {
        colorOverrides[resKey] = argb
        persistAsync()
    }

    fun setIntOverride(resKey: String, value: Int) {
        intOverrides[resKey] = value
        persistAsync()
    }

    fun setDrawablePathOverride(resKey: String, filePath: String) {
        drawablePathOverrides[resKey] = filePath
        resolvedDrawables.remove(filePath) // bust stale cache
        persistAsync()
    }

    fun setDrawableOverride(resKey: String, drawable: Drawable) {
        drawableOverrides[resKey] = drawable
        persistAsync()
    }

    fun setLayoutPathOverride(resKey: String, filePath: String) {
        layoutPathOverrides[resKey] = filePath
        persistAsync()
    }

    fun setColorStateListOverride(resKey: String, csl: ColorStateList) {
        colorStateListOverrides[resKey] = csl
        persistAsync()
    }

    // ── Bulk removal ────────────────────────────────────────────────────

    fun removeOverride(resKey: String) {
        stringOverrides.remove(resKey)
        colorOverrides.remove(resKey)
        intOverrides.remove(resKey)
        drawablePathOverrides.remove(resKey)
        drawableOverrides.remove(resKey)
        layoutPathOverrides.remove(resKey)
        colorStateListOverrides.remove(resKey)
        persistAsync()
    }

    fun clearAll() {
        stringOverrides.clear()
        colorOverrides.clear()
        intOverrides.clear()
        drawablePathOverrides.clear()
        drawableOverrides.clear()
        layoutPathOverrides.clear()
        colorStateListOverrides.clear()
        resolvedDrawables.clear()
        persistAsync()
        RenjanaLog.i(TAG, "[$instanceId] All overrides cleared")
    }

    /**
     * Total number of overrides across all categories.
     */
    fun overrideCount(): Int {
        return stringOverrides.size +
            colorOverrides.size +
            intOverrides.size +
            drawablePathOverrides.size +
            drawableOverrides.size +
            layoutPathOverrides.size +
            colorStateListOverrides.size
    }

    // ════════════════════════════════════════════════════════════════════
    //  Persistence
    // ════════════════════════════════════════════════════════════════════

    /**
     * Persisted model — only primitive / serialisable overrides are saved.
     * In-memory-only: Drawable objects, ColorStateList, resolvedDrawables.
     */
    private data class OverrideBundle(
        val strings: Map<String, String> = emptyMap(),
        val colors: Map<String, Int> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val drawablePaths: Map<String, String> = emptyMap(),
        val layoutPaths: Map<String, String> = emptyMap()
    )

    private fun loadFromDisk() {
        if (!overridesFile.exists()) return
        try {
            val json = overridesFile.readText()
            val bundle = gson.fromJson(json, OverrideBundle::class.java) ?: return
            stringOverrides.putAll(bundle.strings)
            colorOverrides.putAll(bundle.colors)
            intOverrides.putAll(bundle.ints)
            drawablePathOverrides.putAll(bundle.drawablePaths)
            layoutPathOverrides.putAll(bundle.layoutPaths)
            RenjanaLog.d(TAG, "[$instanceId] Loaded ${overrideCount()} overrides from disk")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "[$instanceId] Failed to load overrides: ${e.message}")
        }
    }

    private fun persistAsync() {
        synchronized(persistLock) {
            try {
                val bundle = OverrideBundle(
                    strings = HashMap(stringOverrides),
                    colors = HashMap(colorOverrides),
                    ints = HashMap(intOverrides),
                    drawablePaths = HashMap(drawablePathOverrides),
                    layoutPaths = HashMap(layoutPathOverrides)
                )
                overridesFile.parentFile?.mkdirs()
                overridesFile.writeText(gson.toJson(bundle))
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "[$instanceId] Failed to persist overrides: ${e.message}")
            }
        }
    }

    /**
     * Release in-memory caches. Call on instance destruction.
     */
    fun destroy() {
        resolvedDrawables.clear()
        clearAll()
        RenjanaLog.d(TAG, "[$instanceId] ResourceOverride destroyed")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun decodeDrawable(path: String, hostResources: android.content.res.Resources): Drawable {
        val file = File(path)
        if (!file.exists()) {
            RenjanaLog.w(TAG, "Drawable file not found: $path")
            return android.graphics.drawable.ColorDrawable(0xFFFF00FF.toInt()) // magenta placeholder
        }
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
            if (bitmap != null) {
                android.graphics.drawable.BitmapDrawable(hostResources, bitmap)
            } else {
                android.graphics.drawable.ColorDrawable(0xFFFF00FF.toInt())
            }
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to decode drawable from $path: ${e.message}")
            android.graphics.drawable.ColorDrawable(0xFFFF00FF.toInt())
        }
    }
}
