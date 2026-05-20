package com.androidspect.server

import android.content.Context
import android.util.Log
import com.androidspect.root.AppDataReader
import com.androidspect.server.routes.appRoutes
import com.androidspect.server.routes.assetRoutes
import com.androidspect.server.routes.fileRoutes
import com.androidspect.server.routes.liveRoutes
import com.androidspect.server.routes.manifestRoutes
import com.androidspect.server.routes.prefsRoutes
import com.androidspect.server.routes.sqliteRoutes
import com.androidspect.server.routes.systemRoutes
import com.androidspect.server.ws.logcatWebSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The AndroidSpect HTTP + WebSocket facade.
 *
 * Lifecycle is owned by [ServerService] (a foreground service). Routes are
 * grouped by domain and pull data from [AppDataReader] / [RootBridge].
 */
class AndroidSpectServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) {

    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val reader = AppDataReader(context)

    /**
     * Cached TLS state (cert + keystore + SANs + fingerprint). Refreshed on
     * each [start] so the cert always covers the current LAN IP.
     */
    @Volatile
    var tls: TlsManager.TlsState? = null
        private set

    fun start() {
        if (engine != null) return
        // Always pull a fresh TLS state - getOrCreate regenerates the cert if
        // the device's primary IPv4 has changed since last boot.
        val tlsState = TlsManager.getOrCreate(context, ServerService.primaryIPv4())
        tls = tlsState
        Log.i("AndroSrv", "TLS up - fingerprint ${tlsState.fingerprint.take(23)}… sans=${tlsState.sans}")

        // Warm the libsu persistent root shell + the PackageManager metadata
        // cache in parallel with TLS startup. Without this, the first
        // /api/apps + /api/apps/{pkg}/files calls each pay a ~1-2s one-time
        // bootstrap cost the very first time after boot, which makes the
        // dashboard feel laggy on cold start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                com.androidspect.root.RootBridge.exec("id -un")
                // Prime the PM cache so the first /api/apps is fast too.
                reader.listInstalledApps(includeSystem = false)
            }.onFailure { Log.w("AndroSrv", "warm-up failed (non-fatal): ${it.message}") }
        }

        engine = embeddedServer(Netty, configure = {
            sslConnector(
                keyStore = tlsState.keyStore,
                keyAlias = TlsManager.ALIAS,
                keyStorePassword = { TlsManager.KEYSTORE_PW.toCharArray() },
                privateKeyPassword = { TlsManager.KEYSTORE_PW.toCharArray() }
            ) {
                port = this@AndroidSpectServer.port
                host = "0.0.0.0"
            }
        }, module = {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(CORS) {
                allowSameOrigin = true
                allowHost("localhost", schemes = listOf("https"))
                allowHost("127.0.0.1", schemes = listOf("https"))
                allowHeader(HttpHeaders.ContentType)
                allowMethod(io.ktor.http.HttpMethod.Get)
                allowMethod(io.ktor.http.HttpMethod.Post)
                allowMethod(io.ktor.http.HttpMethod.Delete)
            }
            // Simple password + session-cookie auth. Password is set from the
            // Compose UI only - the web cannot rotate it.
            install(passwordAuthPlugin())
            install(WebSockets) {
                // Ktor 3.x: millis variants are the primary API.
                pingPeriodMillis = 15_000L
                timeoutMillis = 30_000L
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            install(StatusPages) {
                // Map our typed root-bridge errors to specific HTTP statuses so the
                // browser UI can show "not found" vs "permission denied" vs "500".
                exception<com.androidspect.root.RootBridge.PathNotFoundException> { call, cause ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (cause.message ?: "not found"), "type" to "PathNotFound"))
                }
                exception<com.androidspect.root.RootBridge.NotADirectoryException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "not a directory"), "type" to "NotADirectory"))
                }
                exception<com.androidspect.root.RootBridge.NotAFileException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "not a file"), "type" to "NotAFile"))
                }
                exception<com.androidspect.root.RootBridge.PermissionDeniedException> { call, cause ->
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to (cause.message ?: "permission denied"), "type" to "PermissionDenied"))
                }
                // Sanitizer rejections (bad package name, path traversal, bad regex,
                // bad bucket, etc.) all surface as IllegalArgumentException via
                // Kotlin's `require(...)`. Map them to a clean 400 so the browser
                // shows a helpful red toast instead of "500 internal error".
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request"), "type" to "ValidationError"))
                }
                exception<Throwable> { call, cause ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf(
                            "error" to (cause.message ?: cause::class.java.simpleName),
                            "type" to cause::class.java.name
                        )
                    )
                }
            }

            routing {
                authRoutes(context)
                systemRoutes(context)
                appRoutes(reader)
                fileRoutes(reader)
                prefsRoutes(reader)
                sqliteRoutes(reader)
                manifestRoutes(context)
                liveRoutes(context)
                assetRoutes(context)
                logcatWebSocket()
            }
        }).also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(500, 1500)
        engine = null
    }

    val isRunning: Boolean get() = engine != null

    companion object {
        const val DEFAULT_PORT = 8008
    }
}
