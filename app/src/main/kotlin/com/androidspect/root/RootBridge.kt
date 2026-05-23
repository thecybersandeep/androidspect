package com.androidspect.root

import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Single entry point for all root-privileged file and shell operations.
 *
 * libsu spawns one persistent su shell and routes file IO through it, so we
 * never re-elevate per call. The shell starts lazily on first use.
 *
 * Threading: every method is suspending and dispatched on IO. Callers from
 * the UI or Ktor request handlers must already be on a coroutine.
 */
object RootBridge {

    private const val TAG = "AndroidSpect/Root"

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
    }

    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun whoami(): String = withContext(Dispatchers.IO) {
        Shell.cmd("id -un").exec().out.joinToString("\n").trim()
    }

    suspend fun exec(cmd: String): ShellResult = withContext(Dispatchers.IO) {
        val r = Shell.cmd(cmd).exec()
        ShellResult(r.code, r.out.joinToString("\n"), r.err.joinToString("\n"))
    }

    /**
     * Lists a directory.
     *
     * Throws [PathNotFoundException] if the path doesn't exist, [NotADirectoryException]
     * if it's a file, and [PermissionDeniedException] if [SuFile.listFiles] returns null
     * for a path that does exist (libsu's signal for an unreadable directory).
     *
     * On Android `/data/data/<pkg>` is a symlink to `/data/user/0/<pkg>`. SuFile
     * follows the symlink transparently - we don't need to canonicalize.
     */
    suspend fun listDir(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val root = SuFile(path)
        if (!root.exists()) throw PathNotFoundException(path)
        if (!root.isDirectory) throw NotADirectoryException(path)
        val entries = root.listFiles() ?: throw PermissionDeniedException(path)
        entries
            .map { it.toEntry() }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val f = SuFile(path)
        if (!f.exists()) null else f.toEntry()
    }

    suspend fun readBytes(path: String, maxBytes: Long = 5 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        val f = SuFile(path)
        if (!f.exists()) throw PathNotFoundException(path)
        if (f.isDirectory) throw NotAFileException(path)
        val size = f.length()
        val cap = if (size in 0..maxBytes) size.toInt() else maxBytes.toInt()
        SuFileInputStream.open(f).use { stream ->
            val buf = ByteArray(cap)
            var read = 0
            while (read < cap) {
                val n = stream.read(buf, read, cap - read)
                if (n == -1) break
                read += n
            }
            if (read == cap) buf else buf.copyOf(read)
        }
    }

    suspend fun openRead(path: String): InputStream = withContext(Dispatchers.IO) {
        SuFileInputStream.open(SuFile(path))
    }

    /**
     * Copy a root-owned file into the app's cache so other libraries (e.g. SQLite,
     * BitmapFactory) can read it without root awareness. Returns the cache path.
     */
    suspend fun stageToCache(srcPath: String, cacheDir: File, name: String? = null): File =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, name ?: SuFile(srcPath).name)
            target.parentFile?.mkdirs()
            SuFileInputStream.open(SuFile(srcPath)).use { input ->
                SuFileOutputStream.open(target).use { output ->
                    input.copyTo(output)
                }
            }
            // Make sure the staged copy is world-readable for our own process.
            Shell.cmd("chmod 644 ${target.absolutePath}").exec()
            target
        }

    suspend fun listInstalledPackages(): List<String> = withContext(Dispatchers.IO) {
        val out = Shell.cmd("pm list packages -3").exec().out
        out.mapNotNull { line ->
            line.removePrefix("package:").trim().takeIf { it.isNotEmpty() }
        }.sorted()
    }

    suspend fun listAllPackages(): List<String> = withContext(Dispatchers.IO) {
        Shell.cmd("pm list packages").exec().out
            .mapNotNull { it.removePrefix("package:").trim().takeIf { l -> l.isNotEmpty() } }
            .sorted()
    }

    suspend fun packageDataDir(pkg: String): String = "/data/data/$pkg"

    private fun SuFile.toEntry(): FileEntry = FileEntry(
        name = name,
        path = absolutePath,
        isDir = isDirectory,
        size = if (isDirectory) -1 else length(),
        modifiedMs = lastModified(),
        readable = canRead(),
        writable = canWrite()
    )

    data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
    }

    data class FileEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modifiedMs: Long,
        val readable: Boolean,
        val writable: Boolean
    ) {
        fun logLine() = "${if (isDir) "d" else "-"} ${size.toString().padStart(10)} $path"
    }

    fun logTag() = TAG.also { Log.v(it, "RootBridge initialized") }

    // ----- typed errors so routes can translate to specific HTTP statuses -----
    class PathNotFoundException(path: String) : java.io.IOException("Path does not exist: $path")
    class NotADirectoryException(path: String) : java.io.IOException("Not a directory: $path")
    class NotAFileException(path: String) : java.io.IOException("Not a regular file: $path")
    class PermissionDeniedException(path: String) : java.io.IOException("Permission denied (cannot read): $path")
}
