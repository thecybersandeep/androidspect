package com.androidspect.server.routes

import com.androidspect.root.AppActions
import com.androidspect.root.AppDataReader
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun Routing.appRoutes(reader: AppDataReader) {
    route("/api/apps") {
        get {
            val includeSystem = call.request.queryParameters["system"] == "1"
            val q = call.request.queryParameters["q"]?.lowercase()?.takeIf { it.isNotBlank() }
            val apps = reader.listInstalledApps(includeSystem)
                .let { list -> if (q == null) list else list.filter { a ->
                    a.label.lowercase().contains(q) || a.packageName.lowercase().contains(q)
                } }
            call.respond(apps)
        }

        /**
         * Stream **every** APK that makes up the package (base + splits) as a
         * single ZIP straight to the browser. The pentester's laptop gets a
         * `<pkg>.apks.zip` they can unzip and feed to `apksigner` / `apktool` /
         * `jadx` without having to scrape `pm path` themselves.
         *
         * Splits typically include:
         *   base.apk                  - code + main resources
         *   split_config.<abi>.apk    - native libs (arm64-v8a / x86_64 / …)
         *   split_config.<lang>.apk   - per-language resources
         *   split_config.<density>.apk - per-density resources
         */
        get("/{pkg}/apk") {
            val pkg = call.parameters["pkg"].orEmpty()
            val paths = AppActions.apkPaths(pkg)
            if (paths.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no APK paths for $pkg (not installed?)"))
                return@get
            }

            // Single-APK case: stream the .apk directly, not zipped.
            if (paths.size == 1) {
                val src = paths.first()
                val name = src.substringAfterLast('/').takeIf { it.endsWith(".apk") } ?: "$pkg.apk"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString()
                )
                call.respondOutputStream(ContentType.parse("application/vnd.android.package-archive")) {
                    withContext(Dispatchers.IO) {
                        SuFileInputStream.open(SuFile(src)).use { it.copyTo(this@respondOutputStream) }
                    }
                }
                return@get
            }

            // Multi-APK case: bundle into one zip with a small manifest text file.
            val zipName = "$pkg.apks.zip"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, zipName).toString()
            )
            call.respondOutputStream(ContentType.Application.Zip) {
                withContext(Dispatchers.IO) {
                    ZipOutputStream(this@respondOutputStream).use { zip ->
                        // First entry: a manifest with the original on-device paths.
                        zip.putNextEntry(ZipEntry("MANIFEST.txt"))
                        zip.write("package: $pkg\n".toByteArray())
                        zip.write("apks:\n".toByteArray())
                        paths.forEach { zip.write("  $it\n".toByteArray()) }
                        zip.closeEntry()

                        for (src in paths) {
                            val entryName = src.substringAfterLast('/')
                            zip.putNextEntry(ZipEntry(entryName))
                            runCatching {
                                SuFileInputStream.open(SuFile(src)).use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = input.read(buf); if (n == -1) break
                                        zip.write(buf, 0, n)
                                    }
                                }
                            }.onFailure { e ->
                                zip.write("[unreadable: ${e.message}]".toByteArray())
                            }
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }
}
