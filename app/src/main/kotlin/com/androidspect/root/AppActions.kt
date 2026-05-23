package com.androidspect.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Privileged actions on a target package via `am` / `pm`.
 *
 * Audit note: every `packageName` is run through [Sanitize.pkg] before it
 * touches the shell. Free-text args go through [Sanitize.shellQuote].
 */
object AppActions {

    suspend fun start(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val pkg = Sanitize.pkg(packageName)
        val r = RootBridge.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; echo $?")
        ActionResult(r.ok, r.stdout.trim() + r.stderr)
    }

    suspend fun forceStop(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val pkg = Sanitize.pkg(packageName)
        val r = RootBridge.exec("am force-stop $pkg")
        ActionResult(r.ok, r.stdout + r.stderr)
    }

    suspend fun clearData(packageName: String): ActionResult = withContext(Dispatchers.IO) {
        val pkg = Sanitize.pkg(packageName)
        val r = RootBridge.exec("pm clear $pkg")
        ActionResult(r.ok, r.stdout + r.stderr)
    }

    /**
     * Pull every APK file that makes up an installed package (base.apk + every
     * `split_config.*.apk`) into [outDir]. Modern apps ship as multi-APK splits
     * for ABI / density / locale.
     */
    suspend fun pullApk(packageName: String, outDir: java.io.File): ActionResult = withContext(Dispatchers.IO) {
        val pkg = Sanitize.pkg(packageName)
        val paths = apkPaths(pkg)
        if (paths.isEmpty()) return@withContext ActionResult(false, "package $pkg not installed")
        outDir.mkdirs()
        val pulled = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((idx, src) in paths.withIndex()) {
            // Reject anything that doesn't look like a legitimate APK path coming
            // out of `pm path`. /data/app/.../*.apk is the only sensible shape.
            if (!src.startsWith("/data/app/") || !src.endsWith(".apk")) {
                failed += "skipped suspicious path: $src"; continue
            }
            val name = src.substringAfterLast('/').takeIf { it.endsWith(".apk") } ?: "${pkg}_$idx.apk"
            val target = java.io.File(outDir, name)
            val r = RootBridge.exec(
                "cat " + Sanitize.shellQuote(src) +
                " > " + Sanitize.shellQuote(target.absolutePath) +
                " && chmod 644 " + Sanitize.shellQuote(target.absolutePath)
            )
            if (r.ok) pulled += target.absolutePath else failed += "$src: ${r.stderr.take(120)}"
        }
        ActionResult(
            ok = failed.isEmpty() && pulled.isNotEmpty(),
            output = pulled.joinToString("\n"),
            error = if (failed.isEmpty()) null else failed.joinToString("\n")
        )
    }

    /** Returns every APK path on disk that makes up [packageName] - base + splits. */
    suspend fun apkPaths(packageName: String): List<String> = withContext(Dispatchers.IO) {
        val pkg = Sanitize.pkg(packageName)
        RootBridge.exec("pm path $pkg").stdout.lines()
            .mapNotNull { line -> line.takeIf { it.startsWith("package:") }?.removePrefix("package:")?.trim()?.takeIf { it.isNotBlank() } }
    }

    @Serializable
    data class ActionResult(val ok: Boolean, val output: String, val error: String? = null)
}
