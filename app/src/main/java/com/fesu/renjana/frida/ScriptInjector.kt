package com.fesu.renjana.frida

import android.content.Context
import com.fesu.renjana.utils.RenjanaLog
import java.io.File

/**
 * Injects JavaScript into virtual app instances via Frida
 */
object ScriptInjector {
    private const val TAG = "ScriptInjector"
    
    data class ScriptInfo(
        val id: String,
        val name: String,
        val path: String,
        val instanceId: String,
        var isActive: Boolean = false
    )
    
    private val injectedScripts = mutableMapOf<String, MutableList<ScriptInfo>>()
    
    /**
     * Inject script into instance
     */
    fun injectScript(context: Context, instanceId: String, scriptPath: String): Boolean {
        try {
            val scriptFile = File(scriptPath)
            if (!scriptFile.exists()) {
                RenjanaLog.e(TAG, "Script file not found: $scriptPath")
                return false
            }
            
            val scriptContent = scriptFile.readText()
            val scriptId = "script_${System.currentTimeMillis()}"
            
            val scriptInfo = ScriptInfo(
                id = scriptId,
                name = scriptFile.name,
                path = scriptPath,
                instanceId = instanceId
            )
            
            injectedScripts.getOrPut(instanceId) { mutableListOf() }.add(scriptInfo)
            
            // In real implementation, this would use Frida API to inject
            RenjanaLog.i(TAG, "Script injected: ${scriptFile.name} -> instance $instanceId")
            
            scriptInfo.isActive = true
            return true
            
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to inject script", e)
            return false
        }
    }
    
    /**
     * Inject inline script content
     */
    fun injectInlineScript(instanceId: String, scriptName: String, scriptContent: String): Boolean {
        try {
            val scriptId = "inline_${System.currentTimeMillis()}"
            
            val scriptInfo = ScriptInfo(
                id = scriptId,
                name = scriptName,
                path = "inline",
                instanceId = instanceId
            )
            
            injectedScripts.getOrPut(instanceId) { mutableListOf() }.add(scriptInfo)
            scriptInfo.isActive = true
            
            RenjanaLog.i(TAG, "Inline script injected: $scriptName -> instance $instanceId")
            return true
            
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to inject inline script", e)
            return false
        }
    }
    
    /**
     * Remove script from instance
     */
    fun removeScript(instanceId: String, scriptId: String): Boolean {
        try {
            val scripts = injectedScripts[instanceId] ?: return false
            val removed = scripts.removeAll { it.id == scriptId }
            
            if (removed) {
                RenjanaLog.i(TAG, "Script removed: $scriptId from instance $instanceId")
            }
            
            return removed
            
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to remove script", e)
            return false
        }
    }
    
    /**
     * Get all scripts for instance
     */
    fun getScripts(instanceId: String): List<ScriptInfo> {
        return injectedScripts[instanceId]?.toList() ?: emptyList()
    }
    
    /**
     * Check if script is active
     */
    fun isScriptActive(instanceId: String, scriptId: String): Boolean {
        return injectedScripts[instanceId]?.any { it.id == scriptId && it.isActive } ?: false
    }
    
    /**
     * Clear all scripts for instance
     */
    fun clearScripts(instanceId: String) {
        injectedScripts[instanceId]?.forEach { it.isActive = false }
        injectedScripts.remove(instanceId)
        RenjanaLog.i(TAG, "All scripts cleared for instance: $instanceId")
    }
    
    /**
     * Cleanup all injected scripts
     */
    fun cleanup() {
        injectedScripts.clear()
        RenjanaLog.i(TAG, "ScriptInjector cleanup complete")
    }
}
