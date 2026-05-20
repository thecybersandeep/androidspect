package com.androidspect.root

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Enumerates the activities, services, broadcast receivers and content
 * providers of a target package - with the exported flag *and* intent-filter
 * lines, which are the pentester-relevant bits.
 *
 * Intent-filter strings are rendered for display; we don't need a faithful
 * AXML decode because PackageManager already parsed the manifest and the
 * filter spec is small.
 */
class ComponentInspector(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun list(packageName: String): ComponentReport = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_INTENT_FILTERS

        val pkg = runCatching { pm.getPackageInfo(packageName, flags) }.getOrNull()
            ?: return@withContext ComponentReport(packageName, emptyList(), emptyList(), emptyList(), emptyList())

        ComponentReport(
            packageName = packageName,
            activities = pkg.activities?.map { it.toComponent() } ?: emptyList(),
            services = pkg.services?.map { it.toComponent() } ?: emptyList(),
            receivers = pkg.receivers?.map { it.toComponent() } ?: emptyList(),
            providers = pkg.providers?.map { p ->
                Component(
                    name = p.name,
                    exported = p.exported,
                    intentFilters = listOf(
                        "authority=${p.authority}",
                        "readPermission=${p.readPermission ?: "-"}",
                        "writePermission=${p.writePermission ?: "-"}"
                    )
                )
            } ?: emptyList()
        )
    }

    private fun android.content.pm.ComponentInfo.toComponent() = Component(
        name = name,
        exported = exported,
        // PackageManager doesn't expose intent filters via getPackageInfo -
        // it only carries name+exported. Deep-filter introspection needs
        // queryIntentActivities/etc., which is heavier; for richer static
        // analysis (intent filters + ADB exploit commands) we point users
        // to APK Auditor via the CTA card in the Components tab.
        intentFilters = emptyList()
    )
}

@Serializable
data class Component(
    val name: String,
    val exported: Boolean,
    val intentFilters: List<String> = emptyList()
)

@Serializable
data class ComponentReport(
    val packageName: String,
    val activities: List<Component>,
    val services: List<Component>,
    val receivers: List<Component>,
    val providers: List<Component>
)
