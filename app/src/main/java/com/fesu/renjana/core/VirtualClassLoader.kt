package com.fesu.renjana.core

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import dalvik.system.DexClassLoader
import java.io.File

/**
 * VirtualClassLoader - Real implementation for loading guest app DEX files
 * 
 * This class creates an isolated classloader for each app instance,
 * allowing multiple instances of the same app to run simultaneously.
 */
class VirtualClassLoader(
    private val apkPath: String,
    private val instanceId: String,
    private val optimizedDir: File,
    parent: ClassLoader? = null
) : ClassLoader(parent ?: ClassLoader.getSystemClassLoader()) {

    private val dexClassLoader: DexClassLoader
    private var resources: Resources? = null
    private var assetManager: AssetManager? = null

    init {
        // Create optimized directory for this instance
        if (!optimizedDir.exists()) {
            optimizedDir.mkdirs()
        }

        // Initialize DexClassLoader with the APK path
        // This will extract and optimize DEX files
        dexClassLoader = DexClassLoader(
            apkPath,
            optimizedDir.absolutePath,
            null, // library path - will be set later if needed
            parent ?: ClassLoader.getSystemClassLoader()
        )
    }

    /**
     * Load a class from the guest APK
     */
    override fun loadClass(name: String): Class<*> {
        return loadClass(name, false)
    }

    /**
     * Load a class from the guest APK with resolve flag
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Try to load from guest APK first
        return try {
            val clazz = dexClassLoader.loadClass(name)
            if (resolve) {
                resolveClass(clazz)
            }
            clazz
        } catch (e: ClassNotFoundException) {
            // Fall back to parent (system classes)
            super.loadClass(name, resolve)
        }
    }

    /**
     * Find a class in the guest APK
     */
    override fun findClass(name: String): Class<*> {
        return dexClassLoader.loadClass(name)
    }

    /**
     * Get resources from the guest APK
     */
    fun getResources(context: android.content.Context): Resources {
        if (resources == null) {
            resources = createResources(context)
        }
        return resources!!
    }

    /**
     * Get AssetManager from the guest APK
     */
    fun getAssets(): AssetManager {
        if (assetManager == null) {
            assetManager = createAssetManager()
        }
        return assetManager!!
    }

    /**
     * Create AssetManager for the guest APK
     * Uses reflection to access hidden Android APIs
     */
    private fun createAssetManager(): AssetManager {
        try {
            // AssetManager() constructor is package-private, use reflection
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val assetManager = ctor.newInstance()
            val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath",
                String::class.java
            )
            addAssetPathMethod.isAccessible = true
            val result = addAssetPathMethod.invoke(assetManager, apkPath) as Int
            
            if (result == 0) {
                throw RuntimeException("Failed to add asset path: $apkPath")
            }
            
            return assetManager
        } catch (e: Exception) {
            throw RuntimeException("Failed to create AssetManager for $apkPath", e)
        }
    }

    /**
     * Create Resources for the guest APK
     */
    private fun createResources(context: android.content.Context): Resources {
        val assets = getAssets()
        // Use Resources.getSystem() to avoid infinite recursion:
        // WrapperActivity.getResources() -> VirtualClassLoader.getResources(context)
        // -> createResources(context) -> context.resources -> WrapperActivity.getResources() ...
        val systemRes = Resources.getSystem()
        return Resources(
            assets,
            systemRes.displayMetrics,
            systemRes.configuration
        )
    }

    /**
     * Set native library path for loading .so files
     */
    fun setLibraryPath(libPath: String) {
        // Use reflection to set library path in DexClassLoader
        try {
            val pathListField = DexClassLoader::class.java.superclass
                .getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(dexClassLoader)
            
            val nativeLibraryDirectoriesField = pathList.javaClass
                .getDeclaredField("nativeLibraryDirectories")
            nativeLibraryDirectoriesField.isAccessible = true
            
            val dirs = nativeLibraryDirectoriesField.get(pathList) as List<File>
            val newDirs = ArrayList(dirs)
            newDirs.add(File(libPath))
            nativeLibraryDirectoriesField.set(pathList, newDirs)
        } catch (e: Exception) {
            // Failed to set library path, native libs won't load
            println("Warning: Failed to set library path: ${e.message}")
        }
    }

    /**
     * Load a specific class by name from the guest APK
     */
    fun loadGuestClass(className: String): Class<*> {
        return dexClassLoader.loadClass(className)
    }

    /**
     * Check if a class exists in the guest APK
     */
    fun hasClass(className: String): Boolean {
        return try {
            dexClassLoader.loadClass(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
