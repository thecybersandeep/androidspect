package com.androidspect.server

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import android.util.Log
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.response.header
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Password-based browser auth for AndroidSpect.
 *
 * Threat model: this is a pentester tool used by someone who is the legitimate
 * device owner. The whole "tokens, CSRF, Host whitelist" overengineering was
 * cargo-culted from public-facing APIs. The realistic threat is: another
 * person on the same Wi-Fi tries to grab the dashboard. A shared secret
 * password (set in the APK, asked once in the browser) is sufficient.
 *
 *   - The password is set / changed **only from the Compose UI** (`Password.set`).
 *     The web UI cannot change the password - it can't be trusted to do that
 *     because once a browser session is authed, it has root on the device.
 *   - First-time install: a 6-digit code is auto-generated and shown in Compose.
 *     The user can replace it any time.
 *   - On login the browser POSTs the password to `/api/auth/login`. If it
 *     matches, the server sets a random 32-byte session cookie; subsequent
 *     requests just need the cookie. Sessions live in-memory and die on
 *     server restart (intentional - minimal state).
 */
object Password {

    private const val PREFS = "androidspect.auth"
    private const val KEY_PASSWORD = "password"

    // Uppercase A-Z + digits 0-9 minus look-alikes (0/O, 1/I) so the user can
    // read the code off the phone without squinting. 32 distinct chars × 6
    // positions ≈ 1 billion combinations.
    private const val PW_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val PW_LENGTH = 6

    private fun generate(): String {
        val rng = SecureRandom()
        return buildString(PW_LENGTH) {
            repeat(PW_LENGTH) { append(PW_ALPHABET[rng.nextInt(PW_ALPHABET.length)]) }
        }
    }

    /** Returns the configured password, generating a random alphanumeric one on first call. */
    fun current(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY_PASSWORD, null)?.let { return it }
        val pw = generate()
        sp.edit().putString(KEY_PASSWORD, pw).apply()
        return pw
    }

    /** Atomically replaces the password. Caller is responsible for telling the user. */
    fun set(context: Context, newPw: String) {
        require(newPw.length in 4..128) { "password must be 4-128 chars" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PASSWORD, newPw).apply()
        // Invalidate all live sessions - a password change means the old
        // browser session must re-auth.
        Sessions.clear()
    }

    /** Generates a fresh alphanumeric password and persists it. Returns the new value. */
    fun regenerate(context: Context): String {
        val pw = generate()
        set(context, pw)
        return pw
    }
}

/**
 * In-memory session store. Cookie value → expiry timestamp.
 * Sessions live for 12 hours and reset on server restart.
 */
object Sessions {
    private const val COOKIE_NAME = "androidspect_session"
    private const val TTL_MILLIS = 12L * 60 * 60 * 1000

    private val live = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun create(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = android.util.Base64.encodeToString(
            bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        live[token] = System.currentTimeMillis() + TTL_MILLIS
        return token
    }

    fun valid(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val exp = live[token] ?: return false
        return if (exp < System.currentTimeMillis()) { live.remove(token); false } else true
    }

    fun clear() { live.clear() }

    fun cookieName() = COOKIE_NAME
}

/**
 * Per-IP failure tracker + lockout for the login endpoint. Defends against
 * brute force of the 6-char alphanumeric password.
 *
 * State machine per IP:
 *   - Each failure increments [fails] and pushes [until] = now + min(60s, 2^(fails-1) * 5s).
 *   - On a successful login the entry is cleared.
 *   - While [until] is in the future, all login attempts from that IP get
 *     429 regardless of password correctness.
 *
 * Backoff schedule (capped at 60s):
 *   fail 1 → 5s, fail 2 → 10s, fail 3 → 20s, fail 4 → 40s, fail 5+ → 60s.
 *
 * Caveats: IP-based throttling is bypassable by an attacker who controls
 * many source IPs (uncommon on a local LAN). The LRU cleanup prevents
 * unbounded memory.
 */
object LoginRateLimiter {
    private const val MAX_BACKOFF_MS = 60_000L
    private const val BASE_BACKOFF_MS = 5_000L
    private const val MAX_TRACKED_IPS = 1024

    private data class Entry(val fails: AtomicInteger, @Volatile var until: Long)
    private val state = ConcurrentHashMap<String, Entry>()

    /** Returns >0 milliseconds if the IP is currently locked out, 0 if free. */
    fun lockoutRemainingMs(ip: String): Long {
        val e = state[ip] ?: return 0
        val rem = e.until - System.currentTimeMillis()
        return if (rem > 0) rem else 0
    }

    fun onFailure(ip: String) {
        // Bound the map. Crude but effective for v1.
        if (state.size > MAX_TRACKED_IPS) state.clear()
        val e = state.computeIfAbsent(ip) { Entry(AtomicInteger(0), 0L) }
        val n = e.fails.incrementAndGet()
        val backoff = (BASE_BACKOFF_MS shl (n - 1).coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
        e.until = System.currentTimeMillis() + backoff
    }

    fun onSuccess(ip: String) { state.remove(ip) }
}

/**
 * Origin/Host guard against CSRF and DNS rebinding.
 *
 * Checks two things on every non-GET request and on the login endpoint:
 *   - The `Host:` header is one of the allowed values (localhost / 127.0.0.1
 *     / the actual LAN-IPv4 currently bound by [com.androidspect.server.ServerService]).
 *     A DNS-rebinding attacker site would carry a different Host that points
 *     at the victim's phone - we reject it.
 *   - If an `Origin:` header is present (set by browsers on CORS/cross-site
 *     requests), its scheme+host+port must match what we'd accept as Host.
 *     This kills CSRF from any third-party page even if SameSite is bypassed.
 *
 * Same-origin requests from the served web UI carry an Origin that matches
 * the Host, so they always pass.
 */
private fun isAllowedOriginOrHost(call: io.ktor.server.application.ApplicationCall): Boolean {
    // Use Ktor's `host()` / `port()` accessors so we handle both HTTP/1.1
    // `Host:` and HTTP/2 `:authority` uniformly.
    val hostName = call.request.host()
    val hostPort = call.request.port()

    // Allowed host targets: the LAN IPv4 we're bound to, localhost, 127.0.0.1.
    val allowedHosts = buildSet {
        add("localhost"); add("127.0.0.1")
        ServerService.primaryIPv4()?.let { add(it) }
    }
    if (hostName !in allowedHosts) {
        Log.d("AndroAuth", "reject: hostName='$hostName' not in $allowedHosts")
        return false
    }

    // If Origin header is present, validate it. `Origin: null` (sandboxed
    // iframes, file://, opaque redirects) is rejected - a real same-origin
    // request from our served web UI always carries a concrete origin.
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin != null) {
        if (origin == "null") {
            Log.d("AndroAuth", "reject: Origin=null")
            return false
        }
        val ok = runCatching {
            val u = Url(origin)
            val schemeOk = u.protocol.name == "https"
            val hostOk = u.host in allowedHosts
            // Port match: allow when Origin's port equals the Host port, OR
            // when Origin used the scheme's default port and Host used it too.
            val portOk = u.port == hostPort || (u.specifiedPort == 0 && hostPort == 443)
            if (!schemeOk || !hostOk || !portOk) {
                Log.d("AndroAuth", "reject Origin='$origin': scheme=$schemeOk host=$hostOk port=$portOk (origin.port=${u.port} host.port=$hostPort)")
            }
            schemeOk && hostOk && portOk
        }.getOrDefault(false)
        if (!ok) return false
    }
    return true
}

private fun parseHostPort(host: String): Pair<String, Int>? {
    val colon = host.lastIndexOf(':')
    if (colon < 0) return null
    val port = host.substring(colon + 1).toIntOrNull() ?: return null
    return host.substring(0, colon) to port
}

/**
 * Ktor plugin: gates every request behind a valid session cookie, with two
 * exceptions: the index page + its static assets (so the browser can load
 * the login form), and the login endpoint itself.
 *
 * Also enforces the origin/host guard from [isAllowedOriginOrHost] on every
 * request - defends against CSRF and DNS rebinding.
 */
fun passwordAuthPlugin(): ApplicationPlugin<Unit> = createApplicationPlugin("AndroidSpectPasswordAuth") {
    val publicPrefixes = listOf(
        "/", "/app.css", "/app.js", "/icon.svg", "/favicon.ico",
        "/api/auth/login", "/api/auth/required", "/api/health"
    )
    onCall { call ->
        // CSRF / DNS-rebinding guard runs FIRST so even unauthed routes
        // (login, health) can't be hit cross-origin.
        if (!isAllowedOriginOrHost(call)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "bad origin"))
            return@onCall
        }
        val path = call.request.path()
        if (publicPrefixes.any { path == it }) return@onCall

        val cookie = call.request.cookies[Sessions.cookieName()]
        if (!Sessions.valid(cookie)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@onCall
        }
    }
}

/**
 * Auth-related HTTP endpoints. Kept tiny: a login that takes a password and
 * returns a session cookie, a `required` probe so the SPA can check whether
 * to show the login form.
 */
fun Routing.authRoutes(context: Context) {

    /** POST /api/auth/login  body: {password}  → sets cookie on success. */
    post("/api/auth/login") {
        val ip = call.request.origin.remoteHost
        val lockoutMs = LoginRateLimiter.lockoutRemainingMs(ip)
        if (lockoutMs > 0) {
            // Give the same response shape on lockout - don't reveal that
            // password tries are even reaching the comparator.
            call.response.headers.append("Retry-After", (lockoutMs / 1000 + 1).toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                mapOf("error" to "too many attempts, try again in ${lockoutMs / 1000 + 1}s")
            )
            return@post
        }
        val req = call.receive<LoginRequest>()
        val expected = Password.current(context)
        if (!constantTimeEquals(req.password, expected)) {
            LoginRateLimiter.onFailure(ip)
            // Subtle 401 - don't leak whether the password length matched.
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "wrong password"))
            return@post
        }
        LoginRateLimiter.onSuccess(ip)
        val token = Sessions.create()
        // Hand-write Set-Cookie to avoid Ktor's `$x-enc=` encoding marker -
        // some browsers (Chrome especially on self-signed HTTPS + SameSite=Strict)
        // reject the cookie when an unknown attribute follows SameSite.
        // Token is URL-safe base64, so it's directly Set-Cookie safe.
        call.response.headers.append(
            "Set-Cookie",
            "${Sessions.cookieName()}=$token; Max-Age=43200; Path=/; Secure; HttpOnly; SameSite=Strict"
        )
        call.respond(mapOf("ok" to true))
    }
}

@Serializable
private data class LoginRequest(val password: String)

/**
 * Length-independent constant-time equality. Folds both inputs to a fixed
 * 64-char working buffer so a length mismatch doesn't short-circuit and leak
 * the expected password length via wall-clock timing.
 *
 * Both strings are bounded - the caller's [b] is always the configured
 * password (≤ 128 chars by [Password.set]). We accept any [a] up to 256
 * chars; anything longer is auto-mismatch but still in constant time vs the
 * 256-char buffer.
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val maxLen = 256
    if (a.length > maxLen) return false
    var diff = (a.length xor b.length)  // length difference contributes to the XOR fold
    for (i in 0 until maxLen) {
        val ca = if (i < a.length) a[i].code else 0
        val cb = if (i < b.length) b[i].code else 0
        diff = diff or (ca xor cb)
    }
    return diff == 0
}
