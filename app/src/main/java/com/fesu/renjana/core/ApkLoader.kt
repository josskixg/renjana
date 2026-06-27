package com.fesu.renjana.core

import android.content.Context
import android.content.pm.PackageManager
import com.fesu.renjana.models.AppInfo
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ApkLoader(private val context: Context) {
    
    companion object {
        private const val TAG = "ApkLoader"
    }

    suspend fun parseApk(apkPath: String): AppInfo = withContext(Dispatchers.IO) {
        RenjanaLog.d(TAG, "Parsing APK: $apkPath")
        
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
            ) ?: throw ApkLoadException("Failed to parse APK: getPackageArchiveInfo returned null")

            val appInfo = AppInfo(
                packageName = packageInfo.packageName ?: throw ApkLoadException("No package name"),
                appName = packageInfo.applicationInfo?.loadLabel(pm)?.toString() ?: packageInfo.packageName,
                versionName = packageInfo.versionName ?: "1.0",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode
                },
                apkPath = apkPath,
                iconPath = null,
                installedDate = System.currentTimeMillis(),
                updatedDate = System.currentTimeMillis(),
                isSystemApp = false
            )
            
            RenjanaLog.i(TAG, "Parsed APK: ${appInfo.packageName} v${appInfo.versionName}")
            appInfo
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to parse APK: $apkPath")
            throw ApkLoadException("Failed to parse APK: ${e.message}", e)
        }
    }

    suspend fun extractResources(apkPath: String, instanceDataPath: String): File = 
        withContext(Dispatchers.IO) {
            RenjanaLog.d(TAG, "Extracting resources from: $apkPath")
            
            val resDir = File(instanceDataPath, "res").apply { mkdirs() }
            
            try {
                ZipFile(apkPath).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "resources.arsc" || entry.name.startsWith("res/")) {
                            val outFile = File(instanceDataPath, entry.name)
                            outFile.parentFile?.mkdirs()
                            
                            if (!entry.isDirectory) {
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(outFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                }
                
                RenjanaLog.i(TAG, "Extracted resources to: $resDir")
                resDir
            } catch (e: Exception) {
                RenjanaLog.e(TAG, "Failed to extract resources")
                throw ApkLoadException("Failed to extract resources: ${e.message}", e)
            }
        }

    suspend fun extractManifest(apkPath: String): String = withContext(Dispatchers.IO) {
        RenjanaLog.d(TAG, "Extracting manifest from: $apkPath")
        
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS
            ) ?: throw ApkLoadException("Failed to parse manifest")

            val manifest = buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                appendLine("<manifest package=\"${packageInfo.packageName}\">")
                appendLine("  <application android:label=\"${packageInfo.applicationInfo?.loadLabel(pm)}\" />")
                packageInfo.activities?.forEach { activity ->
                    appendLine("  <activity android:name=\"${activity.name}\" android:exported=\"${activity.exported}\" />")
                }
                appendLine("</manifest>")
            }
            
            RenjanaLog.i(TAG, "Manifest extracted successfully")
            manifest
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to extract manifest")
            throw ApkLoadException("Failed to extract manifest: ${e.message}", e)
        }
    }

    suspend fun getLauncherActivity(apkPath: String): String? = withContext(Dispatchers.IO) {
        RenjanaLog.d(TAG, "Finding launcher activity for: $apkPath")
        
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES
            )
            
            if (packageInfo != null && packageInfo.activities != null) {
                for (activity in packageInfo.activities!!) {
                    if (activity.exported) {
                        RenjanaLog.i(TAG, "Found launcher activity: ${activity.name}")
                        return@withContext activity.name
                    }
                }
                
                if (packageInfo.activities!!.isNotEmpty()) {
                    val firstActivity = packageInfo.activities!![0].name
                    RenjanaLog.w(TAG, "No exported activity found, using first: $firstActivity")
                    return@withContext firstActivity
                }
            }
            
            RenjanaLog.w(TAG, "No launcher activity found")
            null
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to find launcher activity")
            null
        }
    }

    suspend fun extractNativeLibraries(
        apkPath: String,
        instanceDataPath: String,
        abi: String = "arm64-v8a"
    ): File = withContext(Dispatchers.IO) {
        RenjanaLog.d(TAG, "Extracting native libraries for ABI: $abi")
        
        val libDir = File(instanceDataPath, "lib").apply { mkdirs() }
        val libPath = "lib/$abi"
        
        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith(libPath) && entry.name.endsWith(".so")) {
                        val soName = File(entry.name).name
                        val outFile = File(libDir, soName)
                        
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        RenjanaLog.d(TAG, "Extracted: $soName")
                    }
                }
            }
            
            RenjanaLog.i(TAG, "Native libraries extracted to: $libDir")
            libDir
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to extract native libraries: ${e.message}")
            libDir
        }
    }
}

class ApkLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
