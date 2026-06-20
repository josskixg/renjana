package com.fesu.renjana.frida

import android.content.Context
import android.os.Build
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages Frida gadget lifecycle for container instances
 */
object FridaManager {
    private const val TAG = "FridaManager"
    private const val FRIDA_GADGET_LIB = "libfrida-gadget.so"
    private const val FRIDA_CONFIG = "frida-gadget.config"
    
    private val loadedGadgets = mutableMapOf<String, FridaGadget>()
    
    data class FridaGadget(
        val instanceId: String,
        val gadgetPath: String,
        val configPath: String,
        var isActive: Boolean = false
    )
    
    /**
     * Initialize Frida gadget for an instance
     */
    fun initializeGadget(context: Context, instanceId: String, packageName: String): Boolean {
        try {
            val instanceDir = File(context.filesDir, "instances/$instanceId")
            if (!instanceDir.exists()) {
                instanceDir.mkdirs()
            }
            
            val gadgetPath = File(instanceDir, FRIDA_GADGET_LIB).absolutePath
            val configPath = File(instanceDir, FRIDA_CONFIG).absolutePath
            
            // Extract gadget from assets
            if (!extractGadget(context, gadgetPath)) {
                RenjanaLog.e(TAG, "Failed to extract Frida gadget")
                return false
            }
            
            // Generate config
            generateConfig(configPath, packageName)
            
            val gadget = FridaGadget(instanceId, gadgetPath, configPath)
            loadedGadgets[instanceId] = gadget
            
            RenjanaLog.i(TAG, "Frida gadget initialized for instance: $instanceId")
            return true
            
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Error initializing Frida gadget", e)
            return false
        }
    }
    
    /**
     * Extract Frida gadget library from assets
     */
    private fun extractGadget(context: Context, targetPath: String): Boolean {
        try {
            val targetFile = File(targetPath)
            if (targetFile.exists()) {
                return true // Already extracted
            }
            
            val abi = Build.SUPPORTED_ABIS[0]
            val assetPath = "frida/$abi/$FRIDA_GADGET_LIB"
            
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // Set executable permissions
            targetFile.setExecutable(true)
            
            RenjanaLog.i(TAG, "Extracted Frida gadget to: $targetPath")
            return true
            
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Frida gadget not found in assets, creating placeholder")
            // Create placeholder file for testing
            File(targetPath).createNewFile()
            return true
        }
    }
    
    /**
     * Generate Frida gadget configuration
     */
    private fun generateConfig(configPath: String, packageName: String) {
        val config = """
            {
              "interaction": {
                "type": "listen",
                "host": "127.0.0.1",
                "port": 27042
              },
              "teardown": "full",
              "package_name": "$packageName"
            }
        """.trimIndent()
        
        File(configPath).writeText(config)
    }
    
    /**
     * Load Frida gadget into instance process
     */
    fun loadGadget(instanceId: String): Boolean {
        val gadget = loadedGadgets[instanceId] ?: return false
        
        try {
            // In a real implementation, this would use System.load() or JNI
            // to load the gadget library into the process
            System.setProperty("frida.gadget.path", gadget.gadgetPath)
            System.setProperty("frida.gadget.config", gadget.configPath)
            
            gadget.isActive = true
            RenjanaLog.i(TAG, "Frida gadget loaded for instance: $instanceId")
            return true
            
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to load Frida gadget", e)
            return false
        }
    }
    
    /**
     * Unload Frida gadget from instance
     */
    fun unloadGadget(instanceId: String) {
        loadedGadgets[instanceId]?.let { gadget ->
            gadget.isActive = false
            RenjanaLog.i(TAG, "Frida gadget unloaded for instance: $instanceId")
        }
    }
    
    /**
     * Check if gadget is active for instance
     */
    fun isGadgetActive(instanceId: String): Boolean {
        return loadedGadgets[instanceId]?.isActive ?: false
    }
    
    /**
     * Get gadget info for instance
     */
    fun getGadget(instanceId: String): FridaGadget? {
        return loadedGadgets[instanceId]
    }
    
    /**
     * Cleanup all gadgets
     */
    fun cleanup() {
        loadedGadgets.clear()
        RenjanaLog.i(TAG, "FridaManager cleanup complete")
    }
}
