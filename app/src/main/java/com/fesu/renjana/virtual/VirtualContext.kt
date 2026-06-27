package com.fesu.renjana.virtual

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.fesu.renjana.utils.RenjanaLog
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * A [ContextWrapper] that redirects all storage-related Context calls to a per-instance
 * data path, giving each virtual instance an isolated `/data/data/<pkg>`-equivalent tree.
 *
 * The host context is delegated to for everything that is NOT storage (package manager,
 * resources, classloader, system services, etc.), so the guest APK still sees a real,
 * functional Android context — only its persistent state is sandboxed under [dataPath].
 *
 * Directory layout under [dataPath] mirrors what [com.fesu.renjana.core.InstanceManager]
 * creates at instance birth:
 *
 * ```
 * <dataPath>/
 *   files/          → getFilesDir()
 *   cache/          → getCacheDir()
 *   code_cache/     → getCodeCacheDir()
 *   databases/      → getDatabasePath(name)
 *   shared_prefs/   → getSharedPreferences(name)
 *   no_backup/      → getNoBackupFilesDir()
 *   external_files/ → getExternalFilesDir(type)
 * ```
 *
 * v0.1.0 — storage isolation only. The redirection is path-based; it does not mount a
 * separate filesystem nor intercept native file syscalls.
 *
 * @param hostContext the real application/host context, used for non-storage delegation.
 * @param dataPath    absolute instance data path. Must equal [com.fesu.renjana.models.Instance.dataPath]
 *                    (i.e. the path InstanceManager recorded for this instance).
 */
class VirtualContext(
    hostContext: Context,
    private val dataPath: String
) : ContextWrapper(hostContext) {

    companion object {
        private const val TAG = "VirtualCtx"
        private const val FILES_DIR = "files"
        private const val CACHE_DIR = "cache"
        private const val CODE_CACHE_DIR = "code_cache"
        private const val DATABASES_DIR = "databases"
        private const val SHARED_PREFS_DIR = "shared_prefs"
        private const val NO_BACKUP_DIR = "no_backup"
        private const val EXTERNAL_FILES_DIR = "external_files"
        private const val EXTERNAL_CACHE_DIR = "external_cache"
        private const val OBB_DIR = "obb"
        private const val PREFS_SUFFIX = ".xml"
    }

    private val prefsCache = HashMap<String, SharedPreferences>()
    private val prefsLock = Any()

    init {
        // Ensure the root data dir exists; sub-dirs are created lazily by their getters,
        // mirroring how a real Context guarantees its dirs on first access.
        File(dataPath).mkdirs()
        RenjanaLog.i(TAG, "VirtualContext bound to dataPath=$dataPath")
    }

    // ── Directory roots ──────────────────────────────────────────────────────

    override fun getFilesDir(): File {
        val dir = File(dataPath, FILES_DIR)
        ensureDir(dir)
        return dir
    }

    override fun getCacheDir(): File {
        val dir = File(dataPath, CACHE_DIR)
        ensureDir(dir)
        return dir
    }

    override fun getCodeCacheDir(): File {
        val dir = File(dataPath, CODE_CACHE_DIR)
        ensureDir(dir)
        return dir
    }

    override fun getDataDir(): File {
        val dir = File(dataPath)
        ensureDir(dir)
        return dir
    }

    override fun getNoBackupFilesDir(): File {
        val dir = File(dataPath, NO_BACKUP_DIR)
        ensureDir(dir)
        return dir
    }

    override fun getDir(name: String?, mode: Int): File {
        val dir = safeChildPath(File(dataPath), name)
        ensureDir(dir)
        return dir
    }

    override fun getExternalFilesDir(type: String?): File? {
        val base = File(dataPath, EXTERNAL_FILES_DIR)
        ensureDir(base)
        val dir = if (type == null) base else File(base, type)
        if (type != null) ensureDir(dir)
        return dir
    }

    /**
     * Redirected external cache. Not in the core override list but pairs with
     * [getExternalFilesDir] for completeness so guest apps that cache externally
     * stay isolated too.
     */
    override fun getExternalCacheDir(): File? {
        val dir = File(dataPath, EXTERNAL_CACHE_DIR)
        ensureDir(dir)
        return dir
    }

    override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type)!!)
    override fun getExternalCacheDirs(): Array<File> = arrayOf(getExternalCacheDir()!!)

    override fun getObbDir(): File {
        val dir = File(dataPath, OBB_DIR)
        ensureDir(dir)
        return dir
    }

    override fun getObbDirs(): Array<File> = arrayOf(getObbDir())

    // ── Databases ────────────────────────────────────────────────────────────

    override fun getDatabasePath(name: String?): File {
        val dbDir = File(dataPath, DATABASES_DIR)
        ensureDir(dbDir)
        return safeChildPath(dbDir, name)
    }

    override fun deleteDatabase(name: String?): Boolean {
        if (name == null) return false
        return try {
            val dbDir = File(dataPath, DATABASES_DIR)
            val dbDirCanonical = dbDir.canonicalFile
            var deleted = false
            // A SQLite database may be accompanied by journal/WAL/SHM sidecar files.
            for (suffix in listOf("", "-journal", "-wal", "-shm")) {
                val f = safeChildPath(dbDir, name + suffix)
                // safeChildPath returns the dbDir itself when a traversal is blocked;
                // never delete the container directory.
                if (f.path != dbDirCanonical.path && f.exists()) {
                    deleted = f.delete() || deleted
                }
            }
            RenjanaLog.d(TAG, "deleteDatabase($name) → $deleted")
            deleted
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to delete database: $name", e)
            false
        }
    }

    override fun databaseList(): Array<String> {
        val dir = File(dataPath, DATABASES_DIR)
        return dir.list() ?: emptyArray()
    }

    // ── files/ I/O ───────────────────────────────────────────────────────────

    @Throws(FileNotFoundException::class)
    override fun openFileInput(name: String?): FileInputStream {
        val file = safeChildPath(File(dataPath, FILES_DIR), name)
        if (!file.exists()) {
            throw FileNotFoundException("Virtual file not found: ${file.absolutePath}")
        }
        return FileInputStream(file)
    }

    @Throws(FileNotFoundException::class)
    override fun openFileOutput(name: String?, mode: Int): FileOutputStream {
        val filesDir = File(dataPath, FILES_DIR)
        val file = safeChildPath(filesDir, name)
        ensureDir(file.parentFile)
        val append = (mode and Context.MODE_APPEND) != 0
        return FileOutputStream(file, append)
    }

    override fun deleteFile(name: String?): Boolean {
        val filesDir = File(dataPath, FILES_DIR)
        val file = safeChildPath(filesDir, name)
        // safeChildPath returns the files dir itself when a traversal is blocked;
        // never delete the container directory.
        if (file.path == filesDir.canonicalFile.path) return false
        val deleted = file.exists() && file.delete()
        if (deleted) {
            RenjanaLog.d(TAG, "Deleted virtual file: ${file.absolutePath}")
        }
        return deleted
    }

    override fun fileList(): Array<String> {
        return getFilesDir().list() ?: emptyArray()
    }

    // ── SharedPreferences ────────────────────────────────────────────────────
    //
    // Reuses the in-package [VirtualSharedPreferences] (XML-backed, full
    // SharedPreferences interface) so prefs are stored under
    // <dataPath>/shared_prefs/<name>.xml and never touch the host's prefs.
    // Instances are cached by name — Android's real Context does the same, and
    // caching avoids two live objects racing over the same backing file.

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val prefsName = name ?: ""
        synchronized(prefsLock) {
            prefsCache[prefsName]?.let { return it }
            val prefsDir = File(dataPath, SHARED_PREFS_DIR)
            ensureDir(prefsDir)
            val file = safeChildPath(prefsDir, prefsName + PREFS_SUFFIX)
            val prefs = VirtualSharedPreferences(file, prefsName)
            prefsCache[prefsName] = prefs
            RenjanaLog.d(TAG, "SharedPreferences bound: ${file.absolutePath}")
            return prefs
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolve [child] under [parent], refusing to escape the [parent] tree.
     *
     * Guest-supplied names (database names, file names, dir names, pref names)
     * are concatenated into filesystem paths. Without canonicalization a name
     * like `"../../host_file"` would escape the instance data root. This helper
     * canonicalizes both paths and verifies the child still lives under [parent];
     * if it does not, the parent itself is returned (the call then fails safely
     * on the subsequent I/O) and a warning is logged.
     */
    private fun safeChildPath(parent: File, child: String?): File {
        val p = File(parent, child ?: "")
        val canonical = p.canonicalFile
        val parentCanonical = parent.canonicalFile
        if (canonical != parentCanonical &&
            !canonical.path.startsWith(parentCanonical.path + File.separator)) {
            RenjanaLog.w(TAG, "Path traversal blocked: $child escapes $parent")
            return parentCanonical
        }
        return canonical
    }

    private fun ensureDir(dir: File?) {
        if (dir == null) return
        if (!dir.exists() && !dir.mkdirs()) {
            // Don't throw — a getter that fails to mkdir would crash the guest at odd
            // times. Log and let the subsequent I/O call surface a clearer error.
            RenjanaLog.w(TAG, "Could not create virtual directory: ${dir.absolutePath}")
        }
    }
}
