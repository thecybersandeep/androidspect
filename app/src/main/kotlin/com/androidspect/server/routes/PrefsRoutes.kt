package com.androidspect.server.routes

import com.androidspect.root.AppDataReader
import com.androidspect.root.SharedPrefsParser
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * GET  /api/apps/{pkg}/prefs           - typed read, includes string/int/bool/etc.
 * POST /api/apps/{pkg}/prefs/set       - overwrite one key with optional type hint
 *                                        body: {bucket, key, value, type?, forceStop?}
 */
fun Routing.prefsRoutes(reader: AppDataReader) {
    route("/api/apps/{pkg}/prefs") {
        get {
            val pkg = call.parameters["pkg"].orEmpty()
            val buckets = reader.readSharedPrefsTyped(pkg)
            call.respond(
                PrefsResponse(
                    packageName = pkg,
                    buckets = buckets.map { (name, kv) ->
                        PrefBucket(
                            name = name,
                            entries = kv.map { (k, e) -> PrefEntry(key = k, type = e.type.name, value = e.value) }
                        )
                    }
                )
            )
        }

        post("/set") {
            val pkg = call.parameters["pkg"].orEmpty()
            val req = call.receive<SetRequest>()
            if (req.bucket.isBlank() || req.key.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bucket and key are required"))
            }
            val typeHint = req.type?.let { runCatching { SharedPrefsParser.PrefType.valueOf(it.uppercase()) }.getOrNull() }
            val result = reader.writeSharedPref(
                packageName = pkg,
                bucket = req.bucket,
                key = req.key,
                value = req.value,             // null = delete
                typeHint = typeHint,
                forceStopFirst = req.forceStop ?: true
            )
            call.respond(result)
        }
    }
}

@Serializable
private data class PrefsResponse(val packageName: String, val buckets: List<PrefBucket>)

@Serializable
private data class PrefBucket(val name: String, val entries: List<PrefEntry>)

@Serializable
private data class PrefEntry(val key: String, val type: String, val value: String)

@Serializable
private data class SetRequest(
    val bucket: String,
    val key: String,
    val value: String? = null,         // null = delete the key
    val type: String? = null,          // STRING / INT / LONG / FLOAT / BOOLEAN / SET
    val forceStop: Boolean? = true
)
