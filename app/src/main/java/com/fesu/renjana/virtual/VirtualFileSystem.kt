package com.fesu.renjana.virtual

import android.content.Context
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Virtual file system that provides isolated directory structure per instance.
 * All file I/O operations are redirected to instance-specific virtual paths.
 */
class VirtualFileSystem(
    private val context: Context,
    private val instance: Instance
) {
    companion object {
        private const val TAG = "VirtualFS"
        private const val INSTANCES_DIR = "instances"
        private const val DATA_DIR = "data"
        private const val CACHE_DIR = "cache"
        private const val DATABASES_DIR = "databases"
        private const val SHARED_PREFS_DIR = "shared_prefs"
        private const val FILES_DIR = "files"
        private const val CODE_CACHE_DIR = "code_cache"
        private const val NO_BACKUP_DIR = "no_backup"
        private const val EXTERNAL_FILES_DIR = "external_files"
    }

    private val baseDir: File = File(context.filesDir.parent, "$INSTANCES_DIR/${instance.id}")

    init {
        initializeDirectories()
    }

    /**
     * Initialize all required directories for the instance
     */
    private fun initializeDirectories() {
        try {
            val dirs = listOf(
                getDataDir(),
                getCacheDir(),
                getDatabasesDir(),
                getSharedPreferencesDir(),
                getFilesDir(),
                getCodeCacheDir(),
                getNoBackupFilesDir(),
                getExternalFilesDir()
            )

            dirs.forEach { dir ->
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (created) {
                        RenjanaLog.d(TAG, "Created directory: ${dir.absolutePath}")
                    } else {
                        RenjanaLog.e(TAG, "Failed to create directory: ${dir.absolutePath}")
                    }
                }
            }

            RenjanaLog.i(TAG, "Initialized virtual filesystem for instance: ${instance.id}")
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Error initializing virtual filesystem", e)
            throw IOException("Failed to initialize virtual filesystem", e)
        }
    }

    /**
     * Get the base directory for this instance
     */
    fun getBaseDir(): File {
        return baseDir
    }

    /**
     * Get the data directory (equivalent to /data/data/package/)
     */
    fun getDataDir(): File {
        return File(baseDir, DATA_DIR)
    }

    /**
     * Get the cache directory (equivalent to Context.getCacheDir())
     */
    fun getCacheDir(): File {
        return File(baseDir, CACHE_DIR)
    }

    /**
     * Get the databases directory (equivalent to Context.getDatabasePath())
     */
    fun getDatabasesDir(): File {
        return File(baseDir, DATABASES_DIR)
    }

    /**
     * Get the shared preferences directory
     */
    fun getSharedPreferencesDir(): File {
        return File(baseDir, SHARED_PREFS_DIR)
    }

    /**
     * Get the files directory (equivalent to Context.getFilesDir())
     */
    fun getFilesDir(): File {
        return File(baseDir, FILES_DIR)
    }

    /**
     * Get the code cache directory
     */
    fun getCodeCacheDir(): File {
        return File(baseDir, CODE_CACHE_DIR)
    }

    /**
     * Get the no backup files directory
     */
    fun getNoBackupFilesDir(): File {
        return File(baseDir, NO_BACKUP_DIR)
    }

    /**
     * Get the external files directory
     */
    fun getExternalFilesDir(): File {
        return File(baseDir, EXTERNAL_FILES_DIR)
    }

    /**
     * Get database path for a specific database name
     */
    fun getDatabasePath(name: String): File {
        return File(getDatabasesDir(), name)
    }

    /**
     * Get shared preferences file path
     */
    fun getSharedPreferencesPath(name: String): File {
        return File(getSharedPreferencesDir(), "$name.xml")
    }

    /**
     * Create a file in the files directory
     */
    fun createFileInFilesDir(name: String): File {
        val file = File(getFilesDir(), name)
        if (!file.exists()) {
            try {
                file.createNewFile()
                RenjanaLog.d(TAG, "Created file: ${file.absolutePath}")
            } catch (e: IOException) {
                RenjanaLog.e(TAG, "Failed to create file: ${file.absolutePath}", e)
                throw e
            }
        }
        return file
    }

    /**
     * Create a file in the cache directory
     */
    fun createFileInCacheDir(name: String): File {
        val file = File(getCacheDir(), name)
        if (!file.exists()) {
            try {
                file.createNewFile()
                RenjanaLog.d(TAG, "Created cache file: ${file.absolutePath}")
            } catch (e: IOException) {
                RenjanaLog.e(TAG, "Failed to create cache file: ${file.absolutePath}", e)
                throw e
            }
        }
        return file
    }

    /**
     * Copy a file from source to destination within virtual filesystem
     */
    fun copyFile(source: File, destination: File): Boolean {
        return try {
            if (!source.exists()) {
                RenjanaLog.w(TAG, "Source file does not exist: ${source.absolutePath}")
                return false
            }

            destination.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
                }
            }

            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }

            RenjanaLog.d(TAG, "Copied file from ${source.absolutePath} to ${destination.absolutePath}")
            true
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to copy file", e)
            false
        }
    }

    /**
     * Delete a file or directory recursively
     */
    fun deleteRecursively(file: File): Boolean {
        return try {
            if (!file.exists()) {
                return true
            }

            if (file.isDirectory) {
                file.listFiles()?.forEach { child ->
                    deleteRecursively(child)
                }
            }

            val deleted = file.delete()
            if (deleted) {
                RenjanaLog.d(TAG, "Deleted: ${file.absolutePath}")
            } else {
                RenjanaLog.w(TAG, "Failed to delete: ${file.absolutePath}")
            }
            deleted
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Error deleting file: ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Get total size of instance data
     */
    fun getInstanceSize(): Long {
        return calculateSize(baseDir)
    }

    /**
     * Calculate size of directory recursively
     */
    private fun calculateSize(dir: File): Long {
        if (!dir.exists()) return 0

        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isFile) {
                    file.length()
                } else {
                    calculateSize(file)
                }
            }
        } else {
            size = dir.length()
        }

        return size
    }

    /**
     * Clear cache directory
     */
    fun clearCache(): Boolean {
        return try {
            val cacheDir = getCacheDir()
            cacheDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }
            RenjanaLog.i(TAG, "Cache cleared for instance: ${instance.id}")
            true
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to clear cache", e)
            false
        }
    }

    /**
     * Clear all instance data (dangerous operation)
     */
    fun clearAllData(): Boolean {
        return try {
            val dataDir = getDataDir()
            dataDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }

            val cacheDir = getCacheDir()
            cacheDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }

            val databasesDir = getDatabasesDir()
            databasesDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }

            val sharedPrefsDir = getSharedPreferencesDir()
            sharedPrefsDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }

            val filesDir = getFilesDir()
            filesDir.listFiles()?.forEach { file ->
                deleteRecursively(file)
            }

            RenjanaLog.i(TAG, "All data cleared for instance: ${instance.id}")
            true
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to clear all data", e)
            false
        }
    }

    /**
     * Check if instance data exists
     */
    fun hasData(): Boolean {
        return baseDir.exists() && baseDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * List files in a directory
     */
    fun listFiles(dir: File): Array<File> {
        return if (dir.exists() && dir.isDirectory) {
            dir.listFiles() ?: emptyArray()
        } else {
            emptyArray()
        }
    }

    /**
     * Resolve a relative path to absolute virtual path
     */
    fun resolvePath(relativePath: String): File {
        return File(baseDir, relativePath)
    }

    /**
     * Check if a path is within the virtual filesystem
     */
    fun isVirtualPath(path: String): Boolean {
        return path.startsWith(baseDir.absolutePath)
    }

    /**
     * Get relative path from base directory
     */
    fun getRelativePath(file: File): String {
        return file.absolutePath.removePrefix(baseDir.absolutePath).removePrefix("/")
    }
}
