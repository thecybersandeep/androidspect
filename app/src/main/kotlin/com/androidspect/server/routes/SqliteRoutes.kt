package com.androidspect.server.routes

import com.androidspect.root.AppDataReader
import com.androidspect.root.SqliteReader
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/**
 * Read-only SQLite browsing for any app's databases.
 *
 * The actual database file lives at /data/data/<pkg>/<path>. We stage a copy
 * into our cache before opening it with the framework SQLite engine so the
 * regular Android SDK can read it (libsu can't be plugged into SQLiteOpenHelper).
 */
fun Routing.sqliteRoutes(reader: AppDataReader) {
    route("/api/apps/{pkg}/sqlite") {

        get("/tables") {
            val pkg = call.parameters["pkg"].orEmpty()
            val path = call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
            val staged = reader.stageSqlite(pkg, path)
            val tables = SqliteReader(staged).listTables()
            call.respond(mapOf("tables" to tables))
        }

        get("/schema") {
            val pkg = call.parameters["pkg"].orEmpty()
            val path = call.request.queryParameters["path"] ?: return@get badRequest(call, "path required")
            val table = call.request.queryParameters["table"] ?: return@get badRequest(call, "table required")
            val staged = reader.stageSqlite(pkg, path)
            call.respond(mapOf("columns" to SqliteReader(staged).schema(table)))
        }

        get("/rows") {
            val pkg = call.parameters["pkg"].orEmpty()
            val path = call.request.queryParameters["path"] ?: return@get badRequest(call, "path required")
            val table = call.request.queryParameters["table"] ?: return@get badRequest(call, "table required")
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val staged = reader.stageSqlite(pkg, path)
            call.respond(SqliteReader(staged).page(table, offset, limit))
        }

        post("/query") {
            val pkg = call.parameters["pkg"].orEmpty()
            val req = call.receive<QueryRequest>()
            val staged = reader.stageSqlite(pkg, req.path)
            val page = runCatching { SqliteReader(staged).query(req.sql, req.limit ?: 500) }
            page.fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, mapOf("error" to it.message)) }
            )
        }

        /**
         * Stream the staged DB copy back to the user as `.db` for offline analysis
         * (DB Browser for SQLite, sqlitebrowser, etc.).
         */
        get("/download") {
            val pkg = call.parameters["pkg"].orEmpty()
            val path = call.request.queryParameters["path"] ?: return@get badRequest(call, "path required")
            val staged = reader.stageSqlite(pkg, path)
            val name = path.substringAfterLast('/').ifEmpty { "database.db" }
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString()
            )
            call.respondBytes(staged.readBytes(), ContentType.parse("application/x-sqlite3"))
        }

        /**
         * Export query results (or a whole table) as CSV - pasteable into Excel,
         * grep-able with awk, etc.
         */
        post("/csv") {
            val pkg = call.parameters["pkg"].orEmpty()
            val req = call.receive<CsvRequest>()
            val staged = reader.stageSqlite(pkg, req.path)
            val rdr = SqliteReader(staged)
            val page = if (!req.sql.isNullOrBlank()) rdr.query(req.sql, req.limit ?: 10000)
                       else if (!req.table.isNullOrBlank()) rdr.page(req.table, 0, req.limit ?: 10000)
                       else { call.respond(HttpStatusCode.BadRequest, mapOf("error" to "either sql or table required")); return@post }
            val csv = buildString {
                append(page.columns.joinToString(",") { csvCell(it) }).append('\n')
                for (row in page.rows) append(row.joinToString(",") { csvCell(it) }).append('\n')
            }
            val name = (req.table ?: "query") + ".csv"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString()
            )
            call.respondText(csv, ContentType.parse("text/csv"))
        }
    }
}

private fun csvCell(v: String?): String {
    if (v == null) return ""
    val needsQuote = v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')
    val escaped = v.replace("\"", "\"\"")
    return if (needsQuote) "\"$escaped\"" else escaped
}

private suspend fun badRequest(call: io.ktor.server.application.ApplicationCall, msg: String) =
    call.respond(HttpStatusCode.BadRequest, mapOf("error" to msg))

@Serializable
private data class QueryRequest(val path: String, val sql: String, val limit: Int? = null)

@Serializable
private data class CsvRequest(val path: String, val sql: String? = null, val table: String? = null, val limit: Int? = null)
