package com.fesu.renjana.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Share text content as a .txt file via FileProvider.
 * Instead of sharing plain text (which gets truncated), writes to a temp file
 * and shares the file URI.
 */
object ShareUtils {

    /**
     * Share text as a .txt file.
     *
     * @param context Activity/Application context
     * @param text Content to share
     * @param fileName Desired file name (without extension)
     * @param subject Email/share subject
     */
    fun shareAsTextFile(
        context: Context,
        text: String,
        fileName: String = "renjana_report",
        subject: String = "Renjana Report"
    ) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.cacheDir, "${fileName}_$timestamp.txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share Report"))
    }
}
