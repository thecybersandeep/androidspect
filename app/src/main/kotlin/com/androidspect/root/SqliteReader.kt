package com.androidspect.root

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Reads a staged SQLite copy (already pulled from /data/data by [AppDataReader])
 * and exposes schema + paginated row dumps.
 *
 * We never write back - every open is READONLY. The staged file lives in our
 * cache so framework SQLite (not libsu) does the parsing.
 */
class SqliteReader(private val stagedFile: File) {

    suspend fun listTables(): List<TableSummary> = withContext(Dispatchers.IO) {
        openReadOnly().use { db ->
            db.rawQuery(
                "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null
            ).use { c ->
                val out = mutableListOf<TableSummary>()
                while (c.moveToNext()) {
                    val name = c.getString(0)
                    val type = c.getString(1)
                    val count = runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM `$name`", null).use { cc ->
                            if (cc.moveToFirst()) cc.getLong(0) else 0L
                        }
                    }.getOrDefault(-1L)
                    out += TableSummary(name, type, count)
                }
                out
            }
        }
    }

    suspend fun schema(table: String): List<ColumnInfo> = withContext(Dispatchers.IO) {
        openReadOnly().use { db ->
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                val out = mutableListOf<ColumnInfo>()
                while (c.moveToNext()) {
                    out += ColumnInfo(
                        cid = c.getInt(0),
                        name = c.getString(1),
                        type = c.getString(2),
                        notNull = c.getInt(3) == 1,
                        defaultValue = c.getString(4),
                        pk = c.getInt(5)
                    )
                }
                out
            }
        }
    }

    suspend fun page(table: String, offset: Int, limit: Int): TablePage = withContext(Dispatchers.IO) {
        require(limit in 1..1000) { "limit must be 1..1000" }
        openReadOnly().use { db ->
            val total = db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            db.rawQuery("SELECT * FROM `$table` LIMIT ? OFFSET ?",
                arrayOf(limit.toString(), offset.toString())
            ).use { c ->
                val columns = c.columnNames.toList()
                val rows = mutableListOf<List<String?>>()
                while (c.moveToNext()) {
                    val row = ArrayList<String?>(columns.size)
                    for (i in columns.indices) {
                        row += when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_BLOB -> "[blob:${c.getBlob(i).size}b]"
                            else -> c.getString(i)
                        }
                    }
                    rows += row
                }
                TablePage(table = table, columns = columns, rows = rows, total = total, offset = offset, limit = limit)
            }
        }
    }

    /**
     * Run an arbitrary read-only SELECT (denies anything else by simple prefix check).
     * For deep work we recommend the UI's query box, not pure programmatic callers.
     */
    suspend fun query(sql: String, limit: Int = 500): TablePage = withContext(Dispatchers.IO) {
        val trimmed = sql.trim().trimEnd(';')
        check(trimmed.uppercase().let { it.startsWith("SELECT") || it.startsWith("WITH") || it.startsWith("PRAGMA") }) {
            "Only SELECT / WITH / PRAGMA queries are allowed"
        }
        val capped = if (trimmed.contains(" LIMIT ", ignoreCase = true)) trimmed else "$trimmed LIMIT $limit"
        openReadOnly().use { db ->
            db.rawQuery(capped, null).use { c ->
                val columns = c.columnNames.toList()
                val rows = mutableListOf<List<String?>>()
                while (c.moveToNext()) {
                    val row = ArrayList<String?>(columns.size)
                    for (i in columns.indices) {
                        row += when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_BLOB -> "[blob:${c.getBlob(i).size}b]"
                            else -> c.getString(i)
                        }
                    }
                    rows += row
                }
                TablePage("(query)", columns, rows, total = rows.size.toLong(), offset = 0, limit = limit)
            }
        }
    }

    private fun openReadOnly(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            stagedFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )

    // All three are returned via Ktor's JSON content negotiation, so they
    // need @Serializable. Without it Ktor blows up at request time with
    // "Serializer for class 'TableSummary' is not found".
    @Serializable
    data class TableSummary(val name: String, val type: String, val rowCount: Long)

    @Serializable
    data class ColumnInfo(
        val cid: Int, val name: String, val type: String,
        val notNull: Boolean, val defaultValue: String?, val pk: Int
    )

    @Serializable
    data class TablePage(
        val table: String,
        val columns: List<String>,
        val rows: List<List<String?>>,
        val total: Long,
        val offset: Int,
        val limit: Int
    )
}
