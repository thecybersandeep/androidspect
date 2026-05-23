package com.androidspect.root

import android.content.Context
import android.content.pm.PackageManager
import com.androidspect.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads metadata + private-storage contents for any installed package.
 *
 * Public PackageManager APIs give us metadata cheaply, but /data/data/<pkg>
 * is mode 0700 owned by the per-app UID - so the file payload always goes
 * through [RootBridge].
 */
class AppDataReader(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * In-memory cache for the sidebar list - keyed by `includeSystem`.
     *
     * The previous implementation called `pm.getInstalledPackages(GET_ACTIVITIES
     * | GET_SERVICES | GET_PROVIDERS | GET_RECEIVERS | GET_PERMISSIONS |
     * GET_META_DATA)` on every dashboard load, which round-trips through
     * PackageManager's binder once per app and was measured at ~900 ms even
     * for a 4-app emulator. None of those component flags are consumed by the
     * `AppInfo` DTO we emit (components are fetched separately via
     * /api/apps/{pkg}/components), so we drop them.
     *
     * Permissions are kept because they ride along on the DTO. They're cheap
     * compared to the component lists.
     */
    @Volatile private var listCacheKey: Boolean? = null
    @Volatile private var listCacheValue: List<AppInfo> = emptyList()
    @Volatile private var listCacheAtMs: Long = 0L
    private val listCacheTtlMs = 5_000L

    suspend fun listInstalledApps(includeSystem: Boolean = false): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = listCacheValue
            if (listCacheKey == includeSystem &&
                cached.isNotEmpty() &&
                now - listCacheAtMs < listCacheTtlMs) {
                return@withContext cached
            }

            val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
            val fresh = pm.getInstalledPackages(flags)
                .asSequence()
                .filter { pkg ->
                    val isSystem = (pkg.applicationInfo?.flags
                        ?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0
                    includeSystem || !isSystem
                }
                .map { pkg ->
                    val ai = pkg.applicationInfo
                    AppInfo(
                        packageName = pkg.packageName,
                        label = ai?.loadLabel(pm)?.toString() ?: pkg.packageName,
                        versionName = pkg.versionName.orEmpty(),
                        versionCode = pkg.longVersionCode,
                        uid = ai?.uid ?: -1,
                        targetSdk = ai?.targetSdkVersion ?: 0,
                        minSdk = ai?.minSdkVersion ?: 0,
                        sourceDir = ai?.sourceDir.orEmpty(),
                        dataDir = ai?.dataDir.orEmpty(),
                        nativeLibDir = ai?.nativeLibraryDir.orEmpty(),
                        debuggable = ((ai?.flags ?: 0) and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                        system = ((ai?.flags ?: 0) and
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                        permissions = pkg.requestedPermissions?.toList().orEmpty(),
                        firstInstallTime = pkg.firstInstallTime,
                        lastUpdateTime = pkg.lastUpdateTime
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
            listCacheKey = includeSystem
            listCacheValue = fresh
            listCacheAtMs = now
            fresh
        }

    suspend fun browseDataDir(packageName: String, relative: String = ""): List<RootBridge.FileEntry> {
        val base = RootBridge.packageDataDir(Sanitize.pkg(packageName))
        val full = Sanitize.safePathUnder(base, relative)
        return RootBridge.listDir(full)
    }

    suspend fun readFile(packageName: String, relative: String, maxBytes: Long = 5 * 1024 * 1024): ByteArray {
        val base = RootBridge.packageDataDir(Sanitize.pkg(packageName))
        val full = Sanitize.safePathUnder(base, relative)
        return RootBridge.readBytes(full, maxBytes)
    }

    suspend fun stageSqlite(packageName: String, relative: String): File {
        val pkg = Sanitize.pkg(packageName)
        val base = RootBridge.packageDataDir(pkg)
        val full = Sanitize.safePathUnder(base, relative)
        val cacheRoot = File(context.cacheDir, "staged/$pkg").apply { mkdirs() }
        val name = relative.substringAfterLast('/').ifEmpty { "db.sqlite" }
        return RootBridge.stageToCache(full, cacheRoot, name)
    }

    suspend fun readSharedPrefs(packageName: String): Map<String, Map<String, String>> {
        val pkg = Sanitize.pkg(packageName)
        val base = "${RootBridge.packageDataDir(pkg)}/shared_prefs"
        val files = RootBridge.listDir(base).filter { !it.isDir && it.name.endsWith(".xml") }
        return files.associate { entry ->
            val bytes = RootBridge.readBytes(entry.path)
            entry.name.removeSuffix(".xml") to SharedPrefsParser.parse(bytes)
        }
    }

    /**
     * Read prefs as a typed structure so the UI can render an "Edit" affordance
     * that knows whether the user is editing a bool, int, string, etc.
     */
    suspend fun readSharedPrefsTyped(packageName: String): Map<String, Map<String, SharedPrefsParser.TypedEntry>> {
        val pkg = Sanitize.pkg(packageName)
        val base = "${RootBridge.packageDataDir(pkg)}/shared_prefs"
        val files = RootBridge.listDir(base).filter { !it.isDir && it.name.endsWith(".xml") }
        return files.associate { entry ->
            entry.name.removeSuffix(".xml") to SharedPrefsParser.parseTyped(RootBridge.readBytes(entry.path))
        }
    }

    /**
     * Update one key in a SharedPreferences bucket. Force-stops the target app
     * first so Android's in-memory pref cache is flushed; on next launch the
     * app will read the new value.
     *
     * @param bucket   filename without `.xml` (e.g. `app_prefs`)
     * @param key      the pref key
     * @param value    new value as a string (server casts based on type); `null` deletes
     * @param typeHint when the key doesn't exist yet, what type to create
     */
    suspend fun writeSharedPref(
        packageName: String,
        bucket: String,
        key: String,
        value: String?,
        typeHint: SharedPrefsParser.PrefType? = null,
        forceStopFirst: Boolean = true
    ): WriteResult {
        val pkg = Sanitize.pkg(packageName)
        // bucket is the XML filename without `.xml` - reject anything that
        // could traverse out of shared_prefs/ or contain shell metachars.
        require(bucket.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) { "invalid bucket name: $bucket" }
        // Guard against force-stopping our own process - that would kill the
        // HTTP server mid-request and the browser would see "empty reply".
        val isSelf = pkg == context.packageName
        if (forceStopFirst && !isSelf) RootBridge.exec("am force-stop $pkg")
        val sharedPrefsDir = "${RootBridge.packageDataDir(pkg)}/shared_prefs"
        val path = Sanitize.safePathUnder(sharedPrefsDir, "$bucket.xml")
        val existing = runCatching { RootBridge.readBytes(path) }.getOrDefault(ByteArray(0))
        val updated = SharedPrefsParser.upsert(existing, key, value, typeHint)
        // Stage to cache, then `cat` it back through the persistent root shell.
        val tmpName = "pref_${pkg}_${bucket}.xml"
        val staged = File(context.cacheDir, tmpName).also { it.parentFile?.mkdirs(); it.writeBytes(updated) }
        // Determine the target app's UID + GID so the new file is correctly owned.
        val statCmd = "stat -c '%u %g' " + Sanitize.shellQuote(path) +
            " 2>/dev/null || stat -c '%u %g' " + Sanitize.shellQuote(RootBridge.packageDataDir(pkg))
        val ownerLine = RootBridge.exec(statCmd).stdout.trim()
        val (uidRaw, gidRaw) = ownerLine.split(' ').let { (it.getOrNull(0) ?: "0") to (it.getOrNull(1) ?: "0") }
        // Force these to be numeric - chown takes "uid:gid" so a stray char is RCE.
        val uid = uidRaw.toIntOrNull() ?: 0
        val gid = gidRaw.toIntOrNull() ?: 0
        val script = "cat " + Sanitize.shellQuote(staged.absolutePath) +
            " > " + Sanitize.shellQuote(path) +
            " && chmod 660 " + Sanitize.shellQuote(path) +
            " && chown $uid:$gid " + Sanitize.shellQuote(path)
        val r = RootBridge.exec(script)
        staged.delete()
        return WriteResult(
            ok = r.ok,
            path = path,
            output = r.stdout,
            error = r.stderr.takeIf { it.isNotBlank() },
            note = if (isSelf) "skipped force-stop (target is AndroidSpect itself)" else null
        )
    }

    @kotlinx.serialization.Serializable
    data class WriteResult(
        val ok: Boolean,
        val path: String,
        val output: String,
        val error: String? = null,
        val note: String? = null
    )
}
