package com.androidspect.server.routes

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Serves the embedded browser UI out of `assets/web/`.
 *
 *   GET /            → web/index.html
 *   GET /{file...}   → web/<file...>
 *
 * Cache-Control is set to `no-store` on every asset. The UI iterates fast
 * during pentest engagements - we don't want browsers serving a stale app.js
 * after the user has rebuilt + reinstalled. `no-store` also dodges Chrome's
 * back/forward cache.
 */
fun Routing.assetRoutes(context: Context) {

    get("/") {
        val (bytes, ct) = readAsset(context, "web/index.html")
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(bytes, ct)
    }

    get("/{file...}") {
        val parts = call.parameters.getAll("file").orEmpty()
        val rel = if (parts.isEmpty()) "index.html" else parts.joinToString("/")
        val (bytes, ct) = readAsset(context, "web/$rel")
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "asset not found", "path" to rel))
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(bytes, ct)
    }
}

private fun readAsset(context: Context, assetPath: String): Pair<ByteArray, ContentType>? {
    return try {
        context.assets.open(assetPath).use { it.readBytes() } to contentTypeFor(assetPath)
    } catch (e: Exception) {
        null
    }
}

private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> ContentType.Text.Html
    "css" -> ContentType.Text.CSS
    "js", "mjs" -> ContentType.Text.JavaScript
    "json" -> ContentType.Application.Json
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "ico" -> ContentType.parse("image/x-icon")
    "woff" -> ContentType.parse("font/woff")
    "woff2" -> ContentType.parse("font/woff2")
    "txt" -> ContentType.Text.Plain
    else -> ContentType.Application.OctetStream
}
