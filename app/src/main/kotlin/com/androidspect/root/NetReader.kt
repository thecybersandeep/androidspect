package com.androidspect.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Live TCP/UDP connection table from /proc/net.
 *
 * Each line of /proc/net/tcp looks like:
 *   sl  local_addr:port  rem_addr:port  st ... uid ...
 * Addresses are hex, network byte order - so 0100007F:1F90 → 127.0.0.1:8080.
 *
 * Mapping uid → package is done from PackageManager in the route layer.
 */
object NetReader {

    suspend fun connections(): List<Conn> = withContext(Dispatchers.IO) {
        // One shell command for all four tables - saves three su round-trips.
        // Markers separate the sections so we don't need a regex over the
        // file paths in the output.
        val raw = RootBridge.exec(
            "echo '##tcp4##'; cat /proc/net/tcp 2>/dev/null; " +
            "echo '##tcp6##'; cat /proc/net/tcp6 2>/dev/null; " +
            "echo '##udp4##'; cat /proc/net/udp 2>/dev/null; " +
            "echo '##udp6##'; cat /proc/net/udp6 2>/dev/null"
        ).stdout
        val sections = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in raw.lines()) {
            val trimmed = line.trimEnd()
            if (trimmed.startsWith("##") && trimmed.endsWith("##")) {
                current = sections.getOrPut(trimmed.trim('#')) { StringBuilder() }
            } else {
                current?.appendLine(line)
            }
        }
        buildList {
            addAll(parse(sections["tcp4"]?.toString().orEmpty(), ip6 = false, proto = "tcp"))
            addAll(parse(sections["tcp6"]?.toString().orEmpty(), ip6 = true,  proto = "tcp6"))
            addAll(parse(sections["udp4"]?.toString().orEmpty(), ip6 = false, proto = "udp"))
            addAll(parse(sections["udp6"]?.toString().orEmpty(), ip6 = true,  proto = "udp6"))
        }.sortedWith(compareBy({ it.uid }, { it.state }, { it.localAddr }))
    }

    private fun parse(text: String, ip6: Boolean, proto: String): List<Conn> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<Conn>()
        text.lines().drop(1).forEach { raw ->
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 10) return@forEach
            val local = parts[1]
            val remote = parts[2]
            val st = parts[3]
            val uid = parts[7].toIntOrNull() ?: -1
            val inode = parts[9].toLongOrNull() ?: 0L
            out += Conn(
                proto = proto,
                localAddr = decodeHex(local, ip6),
                remoteAddr = decodeHex(remote, ip6),
                state = decodeState(st, proto.startsWith("tcp")),
                uid = uid,
                inode = inode
            )
        }
        return out
    }

    private fun decodeHex(s: String, ip6: Boolean): String {
        val (addr, port) = s.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "0" } }
        val portInt = port.toIntOrNull(16) ?: 0
        val ip = if (ip6) decodeIp6(addr) else decodeIp4(addr)
        return "$ip:$portInt"
    }

    private fun decodeIp4(hex: String): String {
        if (hex.length != 8) return hex
        // bytes are little-endian
        val a = hex.substring(6, 8).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        val c = hex.substring(2, 4).toInt(16)
        val d = hex.substring(0, 2).toInt(16)
        return "$a.$b.$c.$d"
    }

    private fun decodeIp6(hex: String): String {
        if (hex.length != 32) return hex
        // Each 32-bit word is little-endian within /proc.
        val words = (0 until 8).map { i ->
            val off = i * 4
            val w = hex.substring(off, off + 4)
            // swap bytes: AABB → BBAA? Actually /proc/net/tcp6 stores 4-byte words in NBO per word.
            // For display we just chunk every 4 hex chars.
            w
        }
        // Group into pairs for IPv6 colon-form (8 groups of 4 hex).
        // Reverse within each 8-byte chunk: the kernel writes each 32-bit word as little-endian.
        val groups = (0 until 4).flatMap { wi ->
            val off = wi * 8
            val word = hex.substring(off, off + 8)
            listOf(
                word.substring(6, 8) + word.substring(4, 6),
                word.substring(2, 4) + word.substring(0, 2)
            )
        }
        return groups.joinToString(":") { it.trimStart('0').ifEmpty { "0" } }
    }

    private fun decodeState(hex: String, tcp: Boolean): String {
        if (!tcp) return "-"
        return when (hex.toIntOrNull(16) ?: -1) {
            1 -> "ESTABLISHED"
            2 -> "SYN_SENT"
            3 -> "SYN_RECV"
            4 -> "FIN_WAIT1"
            5 -> "FIN_WAIT2"
            6 -> "TIME_WAIT"
            7 -> "CLOSE"
            8 -> "CLOSE_WAIT"
            9 -> "LAST_ACK"
            10 -> "LISTEN"
            11 -> "CLOSING"
            else -> hex
        }
    }

    @Serializable
    data class Conn(
        val proto: String,
        val localAddr: String,
        val remoteAddr: String,
        val state: String,
        val uid: Int,
        val inode: Long
    )
}
