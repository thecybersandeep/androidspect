package com.androidspect.root

/**
 * Defensive helpers used at every input boundary that flows into a shell
 * command, file path, or SQL identifier.
 *
 * Audit context: AndroidSpect runs as root and hands user-controlled values
 * directly to libsu's persistent `su` shell. **Every** untrusted string must
 * pass through one of these helpers before being interpolated.
 */
object Sanitize {

    private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

    /**
     * Validates an Android package name. Throws if it contains any character
     * that could break out of a shell command argument. Matches the strict
     * subset Android itself allows ([A-Za-z][\w]*\.…).
     */
    fun pkg(name: String): String {
        require(name.length in 1..255) { "package name length out of range" }
        require(PKG_RE.matches(name)) { "invalid package name: $name" }
        return name
    }

    /**
     * Single-quotes a string for safe inclusion in a `sh -c '…'` command.
     * Uses the canonical `'\''` escape so any embedded `'` is preserved.
     *
     *   shellQuote("hello'world")  →  'hello'\''world'
     *   shellQuote("/sdcard/x;rm") →  '/sdcard/x;rm'   (the `;` is literal)
     */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Resolves [rel] under [base] and verifies the canonical result is still
     * a descendant of [base]. Defeats `../` traversal escapes from app data
     * dirs into root-FS.
     *
     * We deliberately do NOT call `getCanonicalPath()` because that resolves
     * symlinks, and `/data/data/<pkg>` is itself a symlink to
     * `/data/user/0/<pkg>` on modern Android - resolving it would change the
     * prefix and break our own check.
     */
    fun safePathUnder(base: String, rel: String): String {
        val cleanRel = rel.trimStart('/')
        val joined = if (cleanRel.isEmpty()) base else "$base/$cleanRel"
        val canonical = normalize(joined)
        val baseCanonical = normalize(base)
        val allowed = canonical == baseCanonical || canonical.startsWith("$baseCanonical/")
        require(allowed) { "path escapes its sandbox: $rel resolved to $canonical (base $baseCanonical)" }
        return canonical
    }

    /** Lexical path normalization - collapses `.` and `..` segments without touching the filesystem. */
    private fun normalize(path: String): String {
        val absolute = path.startsWith('/')
        val segments = ArrayDeque<String>()
        for (raw in path.split('/')) {
            when (raw) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty() && segments.last() != "..") segments.removeLast()
                        else if (!absolute) segments.addLast("..")
                else -> segments.addLast(raw)
            }
        }
        val joined = segments.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /** Quotes a SQLite identifier (table/column). Doubles embedded `"`. */
    fun sqlIdent(name: String): String {
        require(name.length in 1..128) { "SQL identifier length out of range" }
        require(name.none { it.code < 0x20 }) { "control chars not allowed in SQL identifier" }
        return "\"" + name.replace("\"", "\"\"") + "\""
    }

    /**
     * Sanitizes a pattern for use inside a single-quoted shell context.
     *
     * Inside `'...'` in POSIX sh the *only* character that ends quoting is
     * a literal `'` - newlines, spaces, `;`, `$`, backslash all pass through.
     * Stripping `'` is therefore necessary and sufficient.
     */
    fun safeGrepPattern(pat: String): String {
        require(pat.length in 1..512) { "pattern length out of range" }
        require(pat.none { it.code == 0 }) { "NUL bytes not allowed" }
        return pat.replace("'", "")
    }
}
