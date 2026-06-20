package com.fesu.renjana.core

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LogcatCapture — Capture logcat output for crash reports and debugging.
 *
 * Uses `logcat -d -t <lines>` to dump recent logcat entries.
 * On Android 16+ (API 36+) logcat access requires READ_LOGS permission
 * or the system will return limited output. For crash reports, the
 * CrashHandler captures what it can.
 */
object LogcatCapture {

    private const val TAG = "LogcatCapture"

    /**
     * Capture the last N lines of logcat.
     *
     * @param maxLines Maximum number of lines to capture
     * @return Logcat output as string
     */
    fun capture(maxLines: Int = 500): String {
        return try {
            val process = ProcessBuilder()
                .command("logcat", "-d", "-t", maxLines.toString())
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            process.waitFor()

            if (output.isBlank()) {
                "(logcat returned empty — may need READ_LOGS permission on newer Android)"
            } else {
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture logcat: ${e.message}")
            "Failed to capture logcat: ${e.message}"
        }
    }

    /**
     * Capture logcat filtered by tag.
     */
    fun captureByTag(tag: String, maxLines: Int = 200): String {
        return try {
            val process = ProcessBuilder()
                .command("logcat", "-d", "-t", maxLines.toString(), "-s", tag)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            process.waitFor()

            output.ifBlank { "(no logcat entries for tag: $tag)" }
        } catch (e: Exception) {
            "Failed to capture logcat for tag $tag: ${e.message}"
        }
    }

    /**
     * Capture logcat filtered by process ID.
     */
    fun captureByPid(pid: Int, maxLines: Int = 500): String {
        return try {
            val process = ProcessBuilder()
                .command("logcat", "-d", "-t", maxLines.toString(), "--pid", pid.toString())
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            reader.close()
            process.waitFor()

            output.ifBlank { "(no logcat entries for pid: $pid)" }
        } catch (e: Exception) {
            "Failed to capture logcat for pid $pid: ${e.message}"
        }
    }
}
