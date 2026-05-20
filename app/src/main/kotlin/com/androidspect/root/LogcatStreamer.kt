package com.androidspect.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.BufferedReader

/**
 * Streams a live logcat tail by exec'ing `su -c 'logcat -v threadtime ...'`.
 *
 * libsu's persistent shell is great for one-shot commands, but for an
 * indefinite tail we want our own dedicated child process so cancellation
 * destroys it cleanly without touching the shared shell.
 *
 * Implementation note (regression history): the first version used
 * `callbackFlow + trySend`. trySend returns false on a full Channel - at
 * which point our reader loop bailed and closed the producer. With a
 * default 64-slot buffer and a 200-line historical dump, the WebSocket
 * couldn't drain fast enough, so the stream effectively terminated within
 * a second of "Start" and the UI stuck on "stopped". Fixed by switching
 * to `channelFlow + send` (suspends on backpressure instead of dropping)
 * with an unlimited conflated buffer for safety.
 */
class LogcatStreamer {

    /**
     * @param filter    logcat filter spec (e.g. "MyTag:V *:S"). Whitelisted chars only.
     * @param linesBack `-T N` how many historical lines to dump before tailing.
     * @param pid       optional `--pid=N` - only emit lines for this process. 0 = off.
     */
    fun stream(filter: String = "*:V", linesBack: Int = 200, pid: Int = 0): Flow<String> = channelFlow {
        val safeFilter = filter.filter { it.isLetterOrDigit() || it in " :*_-." }
            .ifBlank { "*:V" }
        val script = buildString {
            append("logcat -v threadtime")
            if (linesBack > 0) append(" -T ").append(linesBack)
            if (pid > 0) append(" --pid=").append(pid)
            append(' ').append(safeFilter)
        }

        val proc = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()

        val reader: Job = launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader().use { br: BufferedReader ->
                    while (true) {
                        val line = br.readLine() ?: break
                        // Suspending send - waits if the WebSocket is slow.
                        send(line)
                    }
                }
            } catch (_: Exception) { /* socket or stream closed */ }
        }

        awaitClose {
            reader.cancel()
            runCatching { proc.destroyForcibly() }
        }
    }.flowOn(Dispatchers.IO)
}
