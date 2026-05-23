package com.androidspect.server.routes

import android.content.Context
import android.content.pm.PackageManager
import com.androidspect.root.AppActions
import com.androidspect.root.NetReader
import com.androidspect.root.ProcessReader
import com.androidspect.root.RootBridge
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Endpoints backed by /proc reads and `am`/`pm` actions.
 *
 *   GET  /api/live/processes       - full /proc snapshot
 *   GET  /api/live/processes?pkg=… - filter by package
 *   GET  /api/live/connections     - TCP/UDP table (with UIDs)
 *   POST /api/live/exec            - run an arbitrary su shell command (DANGEROUS;
 *                                    UI gates this behind a toggle)
 *   POST /api/apps/{pkg}/actions/{action} - start/stop/clear/pull-apk
 */
fun Routing.liveRoutes(context: Context) {

    val pm = context.packageManager
    val uidCache = HashMap<Int, List<String>>()
    fun pkgsForUid(uid: Int): List<String> = uidCache.getOrPut(uid) {
        runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()
    }

    route("/api/live") {
        get("/processes") {
            val pkg = call.request.queryParameters["pkg"]
            val procs = if (pkg.isNullOrBlank()) ProcessReader.listProcesses()
            else ProcessReader.findByPackage(pkg)
            val enriched = procs.map { p ->
                EnrichedProc(p.pid, p.ppid, p.uid, pkgsForUid(p.uid), p.name, p.cmdline, p.state, p.threads, p.rssKb)
            }
            call.respond(mapOf("processes" to enriched))
        }

        get("/connections") {
            val raw = NetReader.connections()
            val enriched = raw.map { c ->
                EnrichedConn(c.proto, c.localAddr, c.remoteAddr, c.state, c.uid, pkgsForUid(c.uid), c.inode)
            }
            call.respond(mapOf("connections" to enriched))
        }

        post("/exec") {
            val req = call.receive<ExecRequest>()
            if (req.command.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "command required"))
            val r = RootBridge.exec(req.command)
            // Mixed-type maps fail in kotlinx.serialization 1.8+; use a typed DTO.
            call.respond(ExecResponse(code = r.code, stdout = r.stdout, stderr = r.stderr))
        }
    }

    route("/api/apps/{pkg}/actions") {
        post("/start") {
            val pkg = call.parameters["pkg"].orEmpty()
            call.respond(AppActions.start(pkg))
        }
        post("/force-stop") {
            val pkg = call.parameters["pkg"].orEmpty()
            call.respond(AppActions.forceStop(pkg))
        }
        post("/clear") {
            val pkg = call.parameters["pkg"].orEmpty()
            call.respond(AppActions.clearData(pkg))
        }
        post("/pull-apk") {
            val pkg = call.parameters["pkg"].orEmpty()
            val out = java.io.File(System.getenv("EXTERNAL_STORAGE") ?: "/sdcard", "AndroidSpect/apks")
            call.respond(AppActions.pullApk(pkg, out))
        }
    }
}

@Serializable
private data class ExecRequest(val command: String)
@Serializable
private data class ExecResponse(val code: Int, val stdout: String, val stderr: String)

@Serializable
private data class EnrichedProc(
    val pid: Int, val ppid: Int, val uid: Int, val packages: List<String>,
    val name: String, val cmdline: String, val state: String,
    val threads: Int, val rssKb: Long
)

@Serializable
private data class EnrichedConn(
    val proto: String, val localAddr: String, val remoteAddr: String,
    val state: String, val uid: Int, val packages: List<String>, val inode: Long
)
