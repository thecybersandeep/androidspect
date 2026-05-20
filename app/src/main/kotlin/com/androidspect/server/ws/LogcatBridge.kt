package com.androidspect.server.ws

import com.androidspect.root.LogcatStreamer
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * /ws/logcat?filter=<spec>&tail=<n>&pid=<pid>
 *
 * One streamer per socket - close the socket to kill the logcat child.
 * `pid` (optional) constrains output to a single process (logcat --pid=N).
 */
fun Routing.logcatWebSocket() {
    webSocket("/ws/logcat") {
        val filter = call.request.queryParameters["filter"] ?: "*:V"
        val tail = call.request.queryParameters["tail"]?.toIntOrNull() ?: 200
        val pid = call.request.queryParameters["pid"]?.toIntOrNull() ?: 0
        val streamer = LogcatStreamer()
        try {
            streamer.stream(filter, tail, pid).collect { line ->
                send(Frame.Text(line))
            }
        } catch (_: CancellationException) { /* client closed */ }
    }
}
