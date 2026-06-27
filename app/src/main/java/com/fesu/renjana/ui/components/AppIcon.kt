package com.fesu.renjana.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAG = "AppIcon"

private fun Drawable.toImageBitmap(): ImageBitmap {
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}

@Composable
fun AppIcon(
    packageName: String,
    apkPath: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showRenjanaBadge: Boolean = false,
    instanceColor: String? = null,
    instanceEmoji: String? = null
) {
    val context = LocalContext.current
    val bitmap = remember(packageName, apkPath) {
        val pm = context.packageManager
        // Try installed app first
        var drawable: Drawable? = try {
            pm.getApplicationIcon(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "getApplicationIcon failed for $packageName: ${e.message}")
            null
        }
        // Fallback to APK file if provided
        if (drawable == null && apkPath != null) {
            try {
                val archiveInfo = pm.getPackageArchiveInfo(apkPath, 0)
                archiveInfo?.applicationInfo?.let { appInfo ->
                    appInfo.sourceDir = apkPath
                    appInfo.publicSourceDir = apkPath
                    drawable = pm.getApplicationIcon(appInfo)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getApplicationIcon from APK failed for $apkPath: ${e.message}")
            }
        }
        try {
            drawable?.toImageBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "toImageBitmap failed: ${e.message}")
            null
        }
    }
    // Parse instanceColor safely — fallback to transparent (no ring)
    val ringColor: Color? = instanceColor?.let {
        try { Color(android.graphics.Color.parseColor(it)) } catch (e: Exception) { null }
    }

    Box(modifier = modifier.size(size)) {
        val iconModifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (ringColor != null)
                    Modifier.border(2.dp, ringColor, RoundedCornerShape(12.dp))
                else
                    Modifier
            )
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "App icon",
                modifier = iconModifier
            )
        } else {
            Box(
                modifier = iconModifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        // ── Emoji overlay — TopStart (opposite of Renjana badge) ──────────
        if (!instanceEmoji.isNullOrBlank()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = instanceEmoji,
                        fontSize = 9.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        // ── Renjana "R" badge — BottomStart ───────────────────────────────
        if (showRenjanaBadge) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(16.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "R",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 8.sp,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
