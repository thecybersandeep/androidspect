package com.androidspect.server.routes

import com.androidspect.root.AppDataReader
import com.androidspect.root.RootBridge
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File-browser endpoints - directory listing, raw byte download, hex / text peek.
 *
 * `path` query param is relative to /data/data/<pkg>. Empty means the root of
 * the app's private dir.
 */
fun Routing.fileRoutes(reader: AppDataReader) {
    route("/api/apps/{pkg}/files") {

        get {
            val pkg = call.parameters["pkg"].orEmpty()
            val rel = call.request.queryParameters["path"].orEmpty()
            val entries = reader.browseDataDir(pkg, rel)
            call.respond(
                FileListing(
                    packageName = pkg,
                    base = "/data/data/$pkg",
                    relative = rel.ifBlank { "/" },
                    entries = entries.map { FileItem(
                        name = it.name,
                        path = it.path,
                        isDir = it.isDir,
                        size = it.size,
                        modifiedMs = it.modifiedMs,
                        kind = classify(it.name, it.isDir)
                    ) }
                )
            )
        }

        get("/raw") {
            val pkg = call.parameters["pkg"].orEmpty()
            val rel = call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
            // ?download=1 forces a Save dialog in the browser; otherwise inline preview.
            val download = call.request.queryParameters["download"] == "1"
            val bytes = reader.readFile(pkg, rel)
            val name = rel.substringAfterLast('/')
            val mime = mimeFor(name)
            val disp = if (download) ContentDisposition.Attachment else ContentDisposition.Inline
            call.response.header(
                HttpHeaders.ContentDisposition,
                disp.withParameter(ContentDisposition.Parameters.FileName, name).toString()
            )
            call.respondBytes(bytes, ContentType.parse(mime))
        }

        /**
         * Stream a ZIP of a directory under /data/data/<pkg>/<path>.
         *
         * For the "dump everything" pentester workflow: GET /api/apps/<pkg>/files/zip
         * downloads the whole private data dir for offline analysis.
         */
        get("/zip") {
            val pkg = com.androidspect.root.Sanitize.pkg(call.parameters["pkg"].orEmpty())
            val rel = call.request.queryParameters["path"].orEmpty()
            val maxBytes = (call.request.queryParameters["max"]?.toLongOrNull()
                ?: 256L * 1024 * 1024).coerceAtMost(2L * 1024 * 1024 * 1024)
            val rootPath = com.androidspect.root.Sanitize.safePathUnder("/data/data/$pkg", rel)
            // Validate that the directory exists - throws PathNotFoundException -> 404.
            RootBridge.stat(rootPath) ?: throw RootBridge.PathNotFoundException(rootPath)

            val zipName = "${pkg}${if (rel.isBlank()) "" else "_" + rel.replace('/', '_')}.zip"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, zipName).toString()
            )
            call.respondOutputStream(ContentType.Application.Zip) {
                withContext(Dispatchers.IO) {
                    ZipOutputStream(this@respondOutputStream).use { zip ->
                        zipDirRecursive(rootPath, "", zip, maxBytes)
                    }
                }
            }
        }

        /**
         * Recursive content search (a remote `grep -r`).
         * Returns paths + line previews of any file under <path> matching the regex.
         */
        get("/grep") {
            val pkg = com.androidspect.root.Sanitize.pkg(call.parameters["pkg"].orEmpty())
            val rel = call.request.queryParameters["path"].orEmpty()
            val pattern = call.request.queryParameters["q"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "q (pattern) required"))
            val ignoreCase = call.request.queryParameters["i"] != "0"
            val maxResults = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)

            val safePat = com.androidspect.root.Sanitize.safeGrepPattern(pattern)
            val flags = if (ignoreCase) "-iaE" else "-aE"
            val base = "/data/data/$pkg"
            val root = com.androidspect.root.Sanitize.safePathUnder(base, rel)
            val qRoot = com.androidspect.root.Sanitize.shellQuote(root)
            val cmd = "grep -rln $flags '$safePat' $qRoot 2>/dev/null | head -n $maxResults"
            val r = RootBridge.exec(cmd)
            val files = r.stdout.lines().filter { it.isNotBlank() }
            val results = files.map { abs ->
                val rl = abs.removePrefix("/data/data/$pkg").removePrefix("/").ifBlank { "/" }
                val qAbs = com.androidspect.root.Sanitize.shellQuote(abs)
                val sample = RootBridge.exec("grep $flags -m 3 -n '$safePat' $qAbs 2>/dev/null | head -3").stdout.trim()
                GrepHit(relative = rl, absolute = abs, sample = sample)
            }
            call.respond(GrepResult(pattern = pattern, root = root, total = results.size, hits = results))
        }

        get("/text") {
            val pkg = call.parameters["pkg"].orEmpty()
            val rel = call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
            val bytes = reader.readFile(pkg, rel, maxBytes = 2 * 1024 * 1024)
            call.respondText(bytes.toString(Charsets.UTF_8), ContentType.Text.Plain)
        }

        get("/hex") {
            val pkg = call.parameters["pkg"].orEmpty()
            val rel = call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 4096).coerceIn(16, 65536)
            val bytes = reader.readFile(pkg, rel, maxBytes = limit.toLong())
            call.respondText(hexDump(bytes), ContentType.Text.Plain)
        }
    }
}

private fun classify(name: String, isDir: Boolean): String {
    if (isDir) return "dir"
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "db", "sqlite", "sqlite3", "db3" -> "sqlite"
        "xml" -> if (name.endsWith("_preferences.xml") || name.contains("pref")) "prefs" else "xml"
        "json" -> "json"
        "txt", "log", "csv", "yml", "yaml", "ini", "conf" -> "text"
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image"
        "html", "htm" -> "html"
        "js", "css" -> "code"
        "so" -> "native"
        "apk" -> "apk"
        else -> "binary"
    }
}

private fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "txt", "log", "csv" -> "text/plain"
        else -> "application/octet-stream"
    }
}

private fun hexDump(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 4)
    val chunk = 16
    for (i in bytes.indices step chunk) {
        val end = minOf(i + chunk, bytes.size)
        sb.append("%08x  ".format(i))
        val ascii = StringBuilder()
        for (j in i until i + chunk) {
            if (j < end) {
                val b = bytes[j].toInt() and 0xFF
                sb.append("%02x ".format(b))
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            } else sb.append("   ")
            if (j == i + 7) sb.append(' ')
        }
        sb.append(' ').append(ascii).append('\n')
    }
    return sb.toString()
}

@Serializable
private data class FileListing(
    val packageName: String,
    val base: String,
    val relative: String,
    val entries: List<FileItem>
)

@Serializable
private data class FileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val kind: String
)

@Serializable
private data class GrepResult(val pattern: String, val root: String, val total: Int, val hits: List<GrepHit>)
@Serializable
private data class GrepHit(val relative: String, val absolute: String, val sample: String)

/**
 * Recursively zips the contents of [rootPath] into [zip].
 *
 * Files are streamed straight from libsu's SuFileInputStream into the ZIP, so
 * we don't buffer the whole tree in RAM. Honours [maxTotalBytes] - once the
 * cumulative payload hits the cap, additional files are skipped with a marker
 * entry so the zip still completes cleanly.
 */
private fun zipDirRecursive(
    rootPath: String,
    relativePrefix: String,
    zip: ZipOutputStream,
    maxTotalBytes: Long
) {
    val root = com.topjohnwu.superuser.io.SuFile(rootPath)
    val children = root.listFiles() ?: return
    var bytesWritten = 0L
    for (child in children) {
        val rel = if (relativePrefix.isEmpty()) child.name else "$relativePrefix/${child.name}"
        if (child.isDirectory) {
            zip.putNextEntry(ZipEntry("$rel/"))
            zip.closeEntry()
            zipDirRecursive(child.absolutePath, rel, zip, maxTotalBytes - bytesWritten)
        } else {
            if (bytesWritten >= maxTotalBytes) {
                zip.putNextEntry(ZipEntry("$rel.SKIPPED-cap-reached"))
                zip.closeEntry()
                continue
            }
            zip.putNextEntry(ZipEntry(rel))
            try {
                com.topjohnwu.superuser.io.SuFileInputStream.open(child).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n == -1) break
                        zip.write(buf, 0, n)
                        bytesWritten += n
                    }
                }
            } catch (e: Exception) {
                // Permission denied on individual file - log a placeholder + continue.
                zip.write("[unreadable: ${e.message}]".toByteArray())
            }
            zip.closeEntry()
        }
    }
}
