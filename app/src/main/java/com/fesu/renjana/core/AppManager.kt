package com.fesu.renjana.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.fesu.renjana.models.AppInfo
import com.fesu.renjana.utils.RenjanaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppManager(private val context: Context) {
    companion object {
        private const val TAG = "AppManager"
    }

    suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            packages.filter { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { pkg ->
                    AppInfo(
                        packageName = pkg.packageName,
                        appName = pkg.applicationInfo.loadLabel(pm).toString(),
                        versionName = pkg.versionName ?: "1.0",
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else @Suppress("DEPRECATION") pkg.versionCode,
                        apkPath = pkg.applicationInfo.sourceDir,
                        iconPath = null,
                        installedDate = pkg.firstInstallTime,
                        updatedDate = pkg.lastUpdateTime
                    )
                }.sortedBy { it.appName }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to get installed apps: ${e.message}")
            emptyList()
        }
    }

    suspend fun extractAppInfo(apkPath: String): AppInfo? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val pkg = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA) ?: return@withContext null
            val appInfo = pkg.applicationInfo ?: return@withContext null
            AppInfo(
                packageName = pkg.packageName,
                appName = appInfo.loadLabel(pm).toString(),
                versionName = pkg.versionName ?: "1.0",
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else @Suppress("DEPRECATION") pkg.versionCode,
                apkPath = apkPath,
                iconPath = null,
                installedDate = pkg.firstInstallTime,
                updatedDate = pkg.lastUpdateTime
            )
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to extract app info: ${e.message}")
            null
        }
    }

    suspend fun validateApk(apkPath: String): Boolean = withContext(Dispatchers.IO) {
        extractAppInfo(apkPath) != null
    }
}
