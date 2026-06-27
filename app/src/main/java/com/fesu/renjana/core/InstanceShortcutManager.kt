package com.fesu.renjana.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.fesu.renjana.R
import com.fesu.renjana.models.Instance

/**
 * Manages pinned homescreen shortcuts for Renjana instances.
 *
 * Uses ShortcutManagerCompat for full API compatibility (API 25 pinned shortcuts
 * path on Android 8+, graceful no-op on older versions).
 */
object InstanceShortcutManager {

    private fun shortcutId(instanceId: String) = "instance_$instanceId"

    /**
     * Request the OS to pin a shortcut for the given instance.
     * Checks support first — no-op if launcher doesn't support pinned shortcuts.
     *
     * @return true if the request was sent to the OS, false if unsupported or failed
     */
    fun requestPinShortcut(context: Context, instance: Instance): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val icon = buildIcon(context, instance.packageName)

        val launchIntent = Intent(context, InstanceLaunchActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(InstanceLaunchActivity.EXTRA_INSTANCE_ID, instance.id)
            // Ensure each shortcut tap creates a fresh delivery of the intent
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId(instance.id))
            .setShortLabel(instance.appName)
            .setLongLabel("${instance.appName} (Renjana)")
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
    }

    /**
     * Update the label and icon of an already-published shortcut (e.g. after rename).
     * Only updates dynamic/pinned shortcuts that are still accessible via the API.
     */
    fun updateShortcut(context: Context, instance: Instance) {
        val icon = buildIcon(context, instance.packageName)

        val launchIntent = Intent(context, InstanceLaunchActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(InstanceLaunchActivity.EXTRA_INSTANCE_ID, instance.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(context, shortcutId(instance.id))
            .setShortLabel(instance.appName)
            .setLongLabel("${instance.appName} (Renjana)")
            .setIcon(icon)
            .setIntent(launchIntent)
            .build()

        ShortcutManagerCompat.updateShortcuts(context, listOf(shortcutInfo))
    }

    /**
     * Remove the shortcut associated with the given instance ID.
     * Safe to call even if the shortcut doesn't exist.
     */
    fun removeShortcut(context: Context, instanceId: String) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId(instanceId)))
        // Also disavow the pinned shortcut so it becomes disabled on launchers that support it
        ShortcutManagerCompat.disableShortcuts(
            context,
            listOf(shortcutId(instanceId)),
            "This instance has been deleted"
        )
    }

    /**
     * Check whether a pinned shortcut exists for the given instance.
     */
    fun isShortcutPinned(context: Context, instanceId: String): Boolean {
        val id = shortcutId(instanceId)
        return ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_PINNED
        ).any { it.id == id }
    }

    // ── Icon helpers ──────────────────────────────────────────────────────────

    /**
     * Build an IconCompat from the guest app's launcher icon.
     * Falls back to ic_launcher if the package is not installed or icon load fails.
     */
    private fun buildIcon(context: Context, packageName: String): IconCompat {
        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = drawableToBitmap(drawable)
            IconCompat.createWithBitmap(bitmap)
        } catch (e: PackageManager.NameNotFoundException) {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        }
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
