package com.androidspect.root

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Lists the .so libraries an app ships, with size, ABI, and a stripped-symbols
 * heuristic.
 *
 * Modern split APKs put native libs under `<base.apk>/lib/<abi>/` (compressed
 * into the APK ZIP) AND/OR extract them to `nativeLibraryDir`. We look at
 * nativeLibraryDir first (cheapest), then optionally fall back to listing the
 * APK ZIP entries through the install dir.
 */
class NativeLibScanner(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun scan(packageName: String): List<NativeLib> = withContext(Dispatchers.IO) {
        val ai = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return@withContext emptyList()

        val out = mutableListOf<NativeLib>()
        val seen = HashSet<String>()

        // 1. nativeLibraryDir - the extracted libs the runtime actually loads.
        val extracted = ai.nativeLibraryDir?.let { File(it) }
        if (extracted != null && extracted.exists()) {
            extracted.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { f ->
                seen += f.name
                out += NativeLib(
                    name = f.name,
                    path = f.absolutePath,
                    size = f.length(),
                    arch = extracted.name,
                    stripped = isStripped(f)
                )
            }
        }

        // 2. APK lib/ZIP entries (covers libs that aren't extracted, e.g. extractNativeLibs=false).
        val apkPath = ai.sourceDir ?: return@withContext out
        runCatching {
            java.util.zip.ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory || !e.name.startsWith("lib/") || !e.name.endsWith(".so")) continue
                    val parts = e.name.split('/')
                    val name = parts.last()
                    if (name in seen) continue
                    val abi = if (parts.size >= 3) parts[1] else "?"
                    out += NativeLib(
                        name = name,
                        path = "${apkPath}!${e.name}",
                        size = e.size,
                        arch = abi,
                        stripped = true // we can't read the embedded ELF cheaply here
                    )
                }
            }
        }
        out.sortedWith(compareBy({ it.arch }, { it.name }))
    }

    /**
     * Cheap stripped detector: read the section header table count from the
     * ELF header and look for ".symtab". This is approximate but fast.
     */
    private fun isStripped(file: File): Boolean {
        return runCatching {
            val head = ByteArray(64)
            file.inputStream().use { it.read(head) }
            // ELF magic
            if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return@runCatching true
            // For real symtab presence we'd parse section headers; approximate
            // by scanning the first 4 KB for ".symtab" string literal.
            val buf = ByteArray(4096)
            file.inputStream().use { it.read(buf) }
            !String(buf, Charsets.ISO_8859_1).contains(".symtab")
        }.getOrDefault(true)
    }
}

@Serializable
data class NativeLib(
    val name: String,
    val path: String,
    val size: Long,
    val arch: String,
    val stripped: Boolean
)
