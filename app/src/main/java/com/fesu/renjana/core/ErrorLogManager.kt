package com.fesu.renjana.core

import android.content.Context
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ErrorLogManager — CRUD operations for crash log files.
 */
class ErrorLogManager(private val context: Context) {

    companion object {
        private const val TAG = "ErrorLogManager"
    }

    data class CrashLog(
        val file: File,
        val fileName: String,
        val timestamp: Long,
        val timestampFormatted: String,
        val sizeBytes: Long,
        val preview: String
    )

    private val crashDir: File get() = CrashHandler.getCrashDir(context)

    /**
     * List all crash logs, newest first.
     */
    fun listLogs(): List<CrashLog> {
        val files = crashDir.listFiles { f -> f.name.endsWith(".txt") }
            ?: return emptyList()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val preview = try {
                    file.readLines()
                        .dropWhile { it.startsWith("═") || it.startsWith("  RENJANA") }
                        .take(5)
                        .joinToString("\n")
                } catch (e: Exception) {
                    "(unreadable)"
                }

                CrashLog(
                    file = file,
                    fileName = file.name,
                    timestamp = file.lastModified(),
                    timestampFormatted = dateFormat.format(Date(file.lastModified())),
                    sizeBytes = file.length(),
                    preview = preview
                )
            }
    }

    /**
     * Read full content of a crash log.
     */
    fun readLog(fileName: String): String? {
        val file = File(crashDir, fileName)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to read crash log $fileName", e)
            null
        }
    }

    /**
     * Delete a specific crash log.
     */
    fun deleteLog(fileName: String): Boolean {
        val file = File(crashDir, fileName)
        return file.delete()
    }

    /**
     * Delete all crash logs.
     */
    fun clearAll(): Int {
        val files = crashDir.listFiles { f -> f.name.endsWith(".txt") } ?: return 0
        var count = 0
        files.forEach { if (it.delete()) count++ }
        return count
    }

    /**
     * Get count of crash logs.
     */
    fun count(): Int {
        return crashDir.listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0
    }

    /**
     * Capture fresh logcat snapshot (for manual export from UI).
     */
    fun captureLiveLogcat(): String {
        return LogcatCapture.capture(1000)
    }

    /**
     * Save a logcat snapshot to file.
     */
    fun saveLogcatSnapshot(): File? {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val file = File(crashDir, "logcat_$timestamp.txt")
        return try {
            file.writeText(LogcatCapture.capture(1000))
            RenjanaLog.i(TAG, "Logcat snapshot saved: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to save logcat snapshot", e)
            null
        }
    }
}
