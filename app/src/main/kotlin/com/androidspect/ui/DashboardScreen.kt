package com.androidspect.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidspect.root.RootBridge
import com.androidspect.server.AndroidSpectServer
import kotlinx.coroutines.delay

/**
 * AndroidSpect on-device dashboard.
 *
 * The Compose UI here is intentionally minimal - it's the launcher and
 * status display. The interesting surface is the browser UI served by the
 * embedded server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    primaryIp: () -> String,
    isServerRunning: () -> Boolean
) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(isServerRunning()) }
    var rootStatus by remember { mutableStateOf<RootStatus>(RootStatus.Checking) }
    var ip by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf(com.androidspect.server.ServerService.currentPort(context)) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val showToast: (String) -> Unit = { msg ->
        scope.launch {
            // Replace any in-flight toast so rapid taps don't stack.
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg, duration = androidx.compose.material3.SnackbarDuration.Short)
        }
    }

    LaunchedEffect(Unit) {
        rootStatus = if (RootBridge.isRooted()) {
            RootStatus.Available(runCatching { RootBridge.whoami() }.getOrDefault("root"))
        } else RootStatus.Unavailable
        while (true) {
            ip = primaryIp()
            running = isServerRunning()
            port = com.androidspect.server.ServerService.currentPort(context)
            delay(2000)
        }
    }

    Scaffold(
        topBar = { AndroidSpectTopBar() },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // Mesh-orb-flavored background tint
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), Color.Transparent),
                        radius = 900f
                    )
                )
            )

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HeroBlock(running = running)
                ServerCard(
                    running = running,
                    rootStatus = rootStatus,
                    ip = ip,
                    port = port,
                    onStart = { onStartServer(); running = true },
                    onStop = { onStopServer(); running = false; showToast("Server stopped") },
                    showToast = showToast
                )
                SettingsCard(
                    running = running,
                    currentSavedPort = port,
                    onPortChanged = { newPort ->
                        com.androidspect.server.ServerService.setPort(context, newPort)
                        if (running) {
                            com.androidspect.server.ServerService.stop(context)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                com.androidspect.server.ServerService.start(context)
                            }, 500)
                            showToast("Saved - restarting on port $newPort")
                        } else {
                            showToast("Port saved")
                        }
                    },
                    showToast = showToast
                )
                DiagnosticsFooter(rootStatus = rootStatus, showToast = showToast)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidSpectTopBar() {
    val toggle = com.androidspect.ui.theme.LocalThemeToggle.current
    val isDark = com.androidspect.ui.theme.LocalIsDark.current
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Real lens mark, always inside a dark chip so the white/green
                // strokes stay legible in both themes.
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Color(0xFF07080B), shape = RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            id = com.androidspect.R.drawable.ic_launcher_foreground
                        ),
                        contentDescription = "AndroidSpect",
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Android") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("Spect") }
                    },
                    fontSize = 17.sp
                )
            }
        },
        actions = {
            androidx.compose.material3.IconButton(onClick = toggle) {
                Icon(
                    if (isDark) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
                    contentDescription = if (isDark) "Switch to light" else "Switch to dark",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
private fun HeroBlock(running: Boolean) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            buildAnnotatedString {
                if (running) {
                    append("Wired up. ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append("Inspect.") }
                } else {
                    append("Inspect any app. ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append("Live.") }
                }
            },
            fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (running)
                "Server is up. Open the URL below in any browser - whatever you touch there is running on this phone right now."
            else
                "Start the server, open the URL in any browser, and inspect any installed app - files, SQLite, prefs, logcat, sockets, manifest, components.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun ServerCard(
    running: Boolean,
    rootStatus: RootStatus,
    ip: String,
    port: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    showToast: (String) -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = MaterialTheme.colorScheme.primary)
    ) {
        Column(Modifier.padding(20.dp)) {
            AnimatedContent(targetState = running, label = "server-state") { isRunning ->
                if (isRunning) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "OPEN IN BROWSER",
                                fontSize = 10.sp, letterSpacing = 1.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // URL panel - mirrors the password panel style: rounded
                        // box, monospace body, primary-tinted border. Horizontally
                        // scrollable so unusual IPs (IPv6) don't wrap mid-token.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "https://",
                                    fontSize = 14.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "$ip:$port",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        // Self-signed cert fingerprint - user compares this against
                        // what the browser shows when accepting the warning.
                        val fp = com.androidspect.server.ServerService.tlsFingerprintShort()
                        if (fp != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "SHA-256 FINGERPRINT (first 8 bytes)",
                                fontSize = 9.sp, letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                fp,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.clickable {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(fp))
                                    showToast("Fingerprint copied")
                                }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton("Copy URL", Icons.Filled.CheckCircle, onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString("https://$ip:$port"))
                                showToast("URL copied to clipboard")
                            })
                            GhostButton("Stop", Icons.Filled.Stop, onStop)
                        }
                    }
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color.Gray.copy(alpha = 0.5f), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "SERVER STOPPED",
                                fontSize = 10.sp, letterSpacing = 1.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton(
                            text = "Start server",
                            icon = Icons.Filled.PlayArrow,
                            onClick = onStart,
                            enabled = rootStatus is RootStatus.Available
                        )
                        when (rootStatus) {
                            is RootStatus.Unavailable -> {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Root not available. Grant AndroidSpect su access in Magisk / KernelSU and tap start again.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 17.sp
                                )
                            }
                            RootStatus.Checking -> {
                                Spacer(Modifier.height(10.dp))
                                Text("Checking root…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    running: Boolean,
    currentSavedPort: Int,
    onPortChanged: (Int) -> Unit,
    showToast: (String) -> Unit
) {
    val context = LocalContext.current
    var portText by remember {
        mutableStateOf(com.androidspect.server.ServerService.currentPort(context).toString())
    }
    var portError by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf(com.androidspect.server.Password.current(context)) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val portIsDirty = portText.toIntOrNull() != currentSavedPort && portText.isNotEmpty()

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {

            // ----- Port -----
            Text("PORT", fontSize = 10.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        if (portError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = portText,
                    onValueChange = { v ->
                        portText = v.filter { it.isDigit() }.take(5)
                        portError = null
                    },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (portError != null) {
                Spacer(Modifier.height(4.dp))
                Text(portError!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val saveLabel = if (running) "Save & restart" else "Save"
                val saveAction: () -> Unit = {
                    val n = portText.toIntOrNull()
                    if (n == null || n !in 1024..65535) {
                        portError = "1024-65535"
                    } else if (n == currentSavedPort) {
                        // No-op save - nothing to do.
                        portError = null
                    } else {
                        portError = null
                        onPortChanged(n)
                    }
                }
                if (portIsDirty) {
                    PrimaryButton(saveLabel, Icons.Filled.CheckCircle, saveAction)
                } else {
                    GhostButton(saveLabel, Icons.Filled.CheckCircle, saveAction)
                }
            }

            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
            Spacer(Modifier.height(20.dp))

            // ----- Browser password -----
            Text("BROWSER PASSWORD", fontSize = 10.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Text(
                    text = password,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = 4.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Copy", Icons.Filled.CheckCircle, onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(password))
                    showToast("Password copied")
                })
                GhostButton("Regenerate", Icons.Filled.Refresh, onClick = {
                    password = com.androidspect.server.Password.regenerate(context)
                    showToast("New password set - existing browser sessions logged out")
                })
            }
        }
    }
}

@Composable
private fun DiagnosticsFooter(
    rootStatus: RootStatus,
    showToast: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // Display: when `whoami` already returns "root", don't echo it back as
    // "root: root" - just say "rooted". Some Magisk setups return a different
    // user (e.g. shell, system) in which case we keep the suffix so the user
    // can see who they're actually running as.
    val rootText = when (rootStatus) {
        is RootStatus.Available ->
            if (rootStatus.user.equals("root", ignoreCase = true)) "rooted"
            else "root · ${rootStatus.user}"
        RootStatus.Unavailable  -> "no su"
        RootStatus.Checking     -> "checking…"
    }
    val rootColor = when (rootStatus) {
        is RootStatus.Available -> MaterialTheme.colorScheme.primary
        RootStatus.Unavailable  -> MaterialTheme.colorScheme.error
        RootStatus.Checking     -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val versionName = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.1"
        }.getOrDefault("0.1")
    }
    val androidApi = android.os.Build.VERSION.SDK_INT
    val androidRel = android.os.Build.VERSION.RELEASE
    val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
    val diagBlob = "AndroidSpect v$versionName · Android $androidRel (API $androidApi) · $model · $abi · $rootText"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(diagBlob))
                showToast("Diagnostics copied")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "v$versionName · Android $androidRel · $abi · ",
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(rootText, fontSize = 10.5.sp, color = rootColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    androidx.compose.material3.Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

private sealed class RootStatus {
    object Checking : RootStatus()
    object Unavailable : RootStatus()
    data class Available(val user: String) : RootStatus()
}
