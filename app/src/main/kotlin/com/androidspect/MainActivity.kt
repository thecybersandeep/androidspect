package com.androidspect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.androidspect.server.ServerService
import com.androidspect.ui.DashboardScreen
import com.androidspect.ui.theme.AndroidSpectTheme

class MainActivity : ComponentActivity() {

    private val askPostNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result doesn't affect server start; just nicer notif. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) askPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AndroidSpectTheme(context = this) {
                DashboardScreen(
                    onStartServer   = { ServerService.start(this) },
                    onStopServer    = { ServerService.stop(this) },
                    primaryIp       = { ServerService.primaryIPv4() ?: "127.0.0.1" },
                    isServerRunning = { ServerService.isRunning(this) }
                )
            }
        }
    }
}
