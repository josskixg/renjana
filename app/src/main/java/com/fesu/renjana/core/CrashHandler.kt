package com.fesu.renjana.core

import android.content.Context
import android.os.Build
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashHandler — Global uncaught exception handler.
 *
 * Saat app crash:
 * 1. Catch exception
 * 2. Write stack trace + device info + logcat ke file
 * 3. Set flag "crash happened" (file marker)
 * 4. Call default handler (let process die)
 *
 * Saat app relaunch:
 * - MainActivity cek marker file
 * - Jika ada → tampilkan CrashScreen dengan detail
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_DIR = "crash_logs"
        private const val CRASH_MARKER = "crash_pending"

        fun install(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val handler = CrashHandler(context, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            RenjanaLog.i(TAG, "CrashHandler installed")
        }

        fun getCrashDir(context: Context): File {
            return File(context.filesDir, CRASH_DIR).apply { mkdirs() }
        }

        /**
         * Check if a crash happened in the previous session.
         * Returns the crash log file if exists, null otherwise.
         */
        fun getPendingCrash(context: Context): File? {
            val marker = File(context.filesDir, CRASH_MARKER)
            if (!marker.exists()) return null
            val crashDir = getCrashDir(context)
            val files = crashDir.listFiles { f -> f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
            return files?.firstOrNull()
        }

        /**
         * Clear the crash marker after user has seen the crash screen.
         */
        fun clearCrashMarker(context: Context) {
            File(context.filesDir, CRASH_MARKER).delete()
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        RenjanaLog.e(TAG, "Uncaught exception on thread ${t.name}", e)

        try {
            writeCrashLog(t, e)
            // Set marker so MainActivity knows to show crash screen
            File(context.filesDir, CRASH_MARKER).writeText("crash")
        } catch (writeError: Exception) {
            RenjanaLog.e(TAG, "Failed to write crash log", writeError)
        }

        // Call default handler to let the process die normally
        defaultHandler?.uncaughtException(t, e)
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            .format(Date())
        val fileName = "crash_${timestamp}.txt"
        val crashFile = File(getCrashDir(context), fileName)

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        // Capture logcat
        val logcat = try {
            LogcatCapture.capture(500)
        } catch (e: Exception) {
            "Failed to capture logcat: ${e.message}"
        }

        val content = buildString {
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine("  RENJANA CRASH REPORT")
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine()
            appendLine("Timestamp: ${Date()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine()
            appendLine("─── Device Info ───────────────────────────────────────────")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build: ${Build.DISPLAY}")
            appendLine()
            appendLine("─── App Info ──────────────────────────────────────────────")
            try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("Package: ${context.packageName}")
                appendLine("Version: ${pkgInfo.versionName} (${pkgInfo.versionCode})")
            } catch (e: Exception) {
                appendLine("Package: ${context.packageName}")
            }
            appendLine()
            appendLine("─── Stack Trace ───────────────────────────────────────────")
            appendLine(stackTrace)
            appendLine()
            appendLine("─── Logcat (last 500 lines) ───────────────────────────────")
            appendLine(logcat)
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine("  END OF REPORT")
            appendLine("═══════════════════════════════════════════════════════════")
        }

        crashFile.writeText(content)
        RenjanaLog.i(TAG, "Crash log written: ${crashFile.absolutePath}")

        // Keep only last 20 crash logs
        getCrashDir(context).listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(20)
            ?.forEach { it.delete() }
    }
}
