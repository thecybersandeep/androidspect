package com.androidspect.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Reads process info via `ps -A` (toybox).
 *
 * The previous implementation enumerated /proc/<pid> with a 305-statement
 * compound shell script (cat + grep per pid). On a real device that took
 * 4-6 seconds to walk. Toybox `ps -A -o PID,PPID,UID,S,RSS,NAME,ARGS`
 * does the same enumeration in one syscall stream and returns in ~100 ms.
 *
 * Thread count is not exposed by toybox `ps -o`, so the `threads` field is
 * always 0 in the list view. Callers that need it can read
 * /proc/<pid>/status directly for a single PID.
 */
object ProcessReader {

    suspend fun listProcesses(): List<ProcInfo> = withContext(Dispatchers.IO) {
        val out = RootBridge.exec(
            "ps -A -o PID,PPID,UID,S,RSS,NAME,ARGS 2>/dev/null"
        ).stdout
        parsePsOutput(out)
    }

    suspend fun findByPackage(pkg: String): List<ProcInfo> =
        listProcesses().filter { it.cmdline.contains(pkg) || it.name == pkg }

    /**
     * Parse a `ps -A -o PID,PPID,UID,S,RSS,NAME,ARGS` table.
     *
     * Each line: `<pid> <ppid> <uid> <state> <rss> <name> <args…>`
     * NAME column is fixed-width and truncated by ps; ARGS is the rest of
     * the line so it may contain spaces - we capture everything from the
     * 7th column to end of line.
     */
    private fun parsePsOutput(text: String): List<ProcInfo> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<ProcInfo>()
        val lines = text.lines()
        // Find header to skip; some kernels/toybox versions emit it, some don't.
        val startIdx = if (lines.firstOrNull()?.trimStart()?.startsWith("PID") == true) 1 else 0
        for (i in startIdx until lines.size) {
            val raw = lines[i].trimEnd()
            if (raw.isBlank()) continue
            // Split into up to 7 fields - the 7th absorbs the cmdline.
            val parts = raw.trimStart().split(Regex("\\s+"), limit = 7)
            if (parts.size < 6) continue
            val pid   = parts[0].toIntOrNull() ?: continue
            val ppid  = parts[1].toIntOrNull() ?: -1
            val uid   = parts[2].toIntOrNull() ?: -1
            val state = parts[3]
            val rss   = parts[4].toLongOrNull() ?: 0L
            val name  = parts[5]
            val args  = if (parts.size >= 7) parts[6].trim() else name
            out += ProcInfo(
                pid = pid, ppid = ppid, uid = uid,
                name = name, cmdline = args,
                state = stateWord(state),
                threads = 0, rssKb = rss
            )
        }
        return out.sortedByDescending { it.rssKb }
    }

    /** Expand the single-char state from ps -o S into the same words /proc/status uses. */
    private fun stateWord(s: String): String = when (s.firstOrNull()) {
        'R' -> "R (running)"
        'S' -> "S (sleeping)"
        'D' -> "D (uninterruptible)"
        'Z' -> "Z (zombie)"
        'T' -> "T (stopped)"
        'I' -> "I (idle)"
        'X' -> "X (dead)"
        else -> s
    }

    @Serializable
    data class ProcInfo(
        val pid: Int,
        val ppid: Int,
        val uid: Int,
        val name: String,
        val cmdline: String,
        val state: String,
        val threads: Int,
        val rssKb: Long
    )
}
