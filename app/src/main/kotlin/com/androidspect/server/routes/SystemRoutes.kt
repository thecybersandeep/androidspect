package com.androidspect.server.routes

import android.content.Context
import android.os.Build
import com.androidspect.BuildConfig
import com.androidspect.root.RootBridge
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Routing.systemRoutes(context: Context) {

    get("/api/status") {
        val rooted = RootBridge.isRooted()
        val who = if (rooted) runCatching { RootBridge.whoami() }.getOrDefault("?") else "no-root"
        call.respond(
            StatusResponse(
                app = "AndroidSpect",
                version = BuildConfig.VERSION_NAME,
                buildType = BuildConfig.BUILD_TYPE,
                rootAvailable = rooted,
                shellUser = who,
                device = DeviceInfo(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    sdk = Build.VERSION.SDK_INT,
                    abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
                )
            )
        )
    }

}

@Serializable
private data class StatusResponse(
    val app: String,
    val version: String,
    val buildType: String,
    val rootAvailable: Boolean,
    val shellUser: String,
    val device: DeviceInfo
)

@Serializable
private data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val abi: String
)
