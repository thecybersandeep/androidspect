package com.androidspect.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-start the embedded server after device boot if the user enabled it in
 * preferences (default: off - we don't surprise the user with a background
 * server they didn't ask for).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val prefs = context.getSharedPreferences("androidspect", Context.MODE_PRIVATE)
        if (prefs.getBoolean("autostart", false)) {
            ServerService.start(context)
        }
    }
}
