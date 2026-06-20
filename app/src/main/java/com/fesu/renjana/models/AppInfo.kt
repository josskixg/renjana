package com.fesu.renjana.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents an installed APK that can be cloned
 */
@Parcelize
data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val apkPath: String,
    val iconPath: String?,
    val installedDate: Long,
    val updatedDate: Long,
    val isSystemApp: Boolean = false
) : Parcelable
