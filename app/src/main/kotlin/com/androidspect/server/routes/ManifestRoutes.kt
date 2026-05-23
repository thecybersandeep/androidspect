package com.androidspect.server.routes

import android.content.Context
import com.androidspect.root.ComponentInspector
import com.androidspect.root.ManifestDecoder
import com.androidspect.root.NativeLibScanner
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.io.File
import java.util.zip.ZipFile

fun Routing.manifestRoutes(context: Context) {
    val decoder = ManifestDecoder(context)
    val inspector = ComponentInspector(context)
    val nativeScanner = NativeLibScanner(context)

    route("/api/apps/{pkg}") {

        get("/manifest") {
            val pkg = call.parameters["pkg"].orEmpty()
            val dump = decoder.decode(pkg)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "package not found"))
            call.respond(dump)
        }

        get("/components") {
            val pkg = call.parameters["pkg"].orEmpty()
            call.respond(inspector.list(pkg))
        }

        get("/native") {
            val pkg = call.parameters["pkg"].orEmpty()
            call.respond(mapOf("libs" to nativeScanner.scan(pkg)))
        }

        /**
         * Stream a single .so back to the browser for analysis (IDA, Ghidra,
         * `objdump`, etc.). The `path` is one of two forms the scanner emits:
         *   - extracted file under [ApplicationInfo.nativeLibraryDir]
         *   - APK zip-entry shape: `<apkPath>!lib/<abi>/<name>.so`
         * We resolve the package's expected paths first and only serve a file
         * if [path] equals one of those - no arbitrary file reads.
         */
        get("/native/raw") {
            val pkg = call.parameters["pkg"].orEmpty()
            val path = call.request.queryParameters["path"].orEmpty()
            require(path.isNotBlank()) { "path required" }
            val ai = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "package not found"))

            val nativeDir = ai.nativeLibraryDir
            val apkPath = ai.sourceDir

            if (path.contains("!")) {
                // APK zip-entry form. Authorise the apk side strictly.
                val bang = path.indexOf('!')
                val apk = path.substring(0, bang)
                val entryName = path.substring(bang + 1)
                require(apk == apkPath) { "apk path mismatch" }
                require(entryName.startsWith("lib/") && entryName.endsWith(".so")) { "not a lib entry" }
                ZipFile(apk).use { zip ->
                    val entry = zip.getEntry(entryName)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "entry not found"))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val filename = entryName.substringAfterLast('/')
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
                    call.respondBytes(bytes, ContentType.Application.OctetStream)
                }
            } else {
                // Extracted file. Must live under the app's nativeLibraryDir.
                require(nativeDir != null && path.startsWith("$nativeDir/")) { "path outside nativeLibraryDir" }
                val f = File(path)
                require(f.exists() && f.isFile) { "not found" }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${f.name}\"")
                call.respondBytes(f.readBytes(), ContentType.Application.OctetStream)
            }
        }
    }
}
