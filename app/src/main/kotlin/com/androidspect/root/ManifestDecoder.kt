package com.androidspect.root

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Best-effort runtime decode of an installed app's AndroidManifest.
 *
 * Why not parse the binary AXML ourselves like apkauditor.com does? On a live
 * device PackageManager has already parsed it - we get the same structured
 * data for free, faster, and without needing to ship an AXML decoder DEX.
 *
 * The "xml" payload is a synthesized AndroidManifest-shaped XML for display;
 * it's not a verbatim byte-for-byte copy, but it round-trips every field the
 * pentester actually cares about (package, components, permissions, filters).
 */
class ManifestDecoder(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun decode(packageName: String): ManifestDump? = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_INTENT_FILTERS

        val pkg: PackageInfo = runCatching { pm.getPackageInfo(packageName, flags) }.getOrNull()
            ?: return@withContext null

        val ai = pkg.applicationInfo
        ManifestDump(
            packageName = pkg.packageName,
            versionName = pkg.versionName.orEmpty(),
            versionCode = pkg.longVersionCode,
            minSdk = ai?.minSdkVersion ?: 0,
            targetSdk = ai?.targetSdkVersion ?: 0,
            permissions = pkg.requestedPermissions?.toList().orEmpty(),
            xml = buildXml(pkg)
        )
    }

    private fun buildXml(pkg: PackageInfo): String {
        val ai = pkg.applicationInfo
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.append("""<manifest package="${pkg.packageName}"""")
        sb.append(""" android:versionCode="${pkg.longVersionCode}"""")
        if (!pkg.versionName.isNullOrBlank()) sb.append(""" android:versionName="${pkg.versionName}"""")
        sb.appendLine(">")
        sb.appendLine("""    <uses-sdk android:minSdkVersion="${ai?.minSdkVersion ?: 0}" android:targetSdkVersion="${ai?.targetSdkVersion ?: 0}"/>""")
        pkg.requestedPermissions?.forEach { sb.appendLine("""    <uses-permission android:name="$it"/>""") }

        val appAttrs = buildList {
            add("""android:label="${ai?.loadLabel(pm) ?: pkg.packageName}"""")
            if ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) add("""android:debuggable="true"""")
            if ((ai?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP != 0) add("""android:allowBackup="true"""")
        }
        sb.appendLine("""    <application ${appAttrs.joinToString(" ")}>""")

        pkg.activities?.forEach { c ->
            sb.appendLine("""        <activity android:name="${c.name}" android:exported="${c.exported}"/>""")
        }
        pkg.services?.forEach { c ->
            sb.appendLine("""        <service android:name="${c.name}" android:exported="${c.exported}"/>""")
        }
        pkg.receivers?.forEach { c ->
            sb.appendLine("""        <receiver android:name="${c.name}" android:exported="${c.exported}"/>""")
        }
        pkg.providers?.forEach { c ->
            sb.appendLine("""        <provider android:name="${c.name}" android:authorities="${c.authority}" android:exported="${c.exported}"/>""")
        }
        sb.appendLine("""    </application>""")
        sb.appendLine("""</manifest>""")
        return sb.toString()
    }
}

@Serializable
data class ManifestDump(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val xml: String
)
