package com.androidspect

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDex
import com.topjohnwu.superuser.Shell

/**
 * Application root.
 *
 * Two startup duties:
 *  1. Install MultiDex when running on the Dalvik runtime (API 21-22). On
 *     API 23+ (ART) every DEX is loaded automatically.
 *  2. Pin libsu's persistent root shell with mount-master + stderr redirect so
 *     the first privileged call from anywhere in the app doesn't pay the
 *     cold-start cost mid-frame.
 */
class AndroidSpectApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            // Dalvik (API 21) still needs explicit multidex bootstrap before any
            // class from a secondary DEX is touched.
            MultiDex.install(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(20)
        )
        Log.i("AndroidSpect", "App initialized - libsu default builder set, SDK=${Build.VERSION.SDK_INT}.")
    }
}
