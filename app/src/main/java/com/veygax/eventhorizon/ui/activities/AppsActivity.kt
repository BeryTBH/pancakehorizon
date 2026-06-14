package com.veygax.eventhorizon.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.veygax.eventhorizon.core.AppInstaller
import com.veygax.eventhorizon.core.UpdateManager
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.launch
import android.util.Log
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// --- Data classes to organize app information and state ---
data class AppInfo(
    val title: String,
    val description: String,
    val packageName: String,
    val installAction: suspend (Context, (String) -> Unit, Uri?) -> Unit,
    val type: AppInstallType = AppInstallType.AUTOMATIC,
    val githubOwner: String? = null,
    val githubRepo: String? = null
)

data class AppItemState(
    val status: String,
    val isProcessing: Boolean,
    val currentVersion: String? = null,
    val hasUpdate: Boolean = false,
    val latestVersion: String? = null
)

enum class AppInstallType {
    AUTOMATIC,
    MANUAL_LINK,
    FILE_PICKER
}

class AppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val useDarkTheme = isSystemInDarkTheme()
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ctx = LocalContext.current
                if (useDarkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (useDarkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppsScreen()
                }
            }
        }
    }
}

// Helper function to get installed app version (returns null if not installed)
fun getInstalledVersion(packageName: String, packageManager: android.content.pm.PackageManager): String? {
    return try {
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        pInfo?.versionName
    } catch (e: Exception) {
        null
    }
}

// NEW: Helper to clean up messy version strings for comparison and UI
fun sanitizeVersionString(version: String?): String? {
    if (version == null) return null
    return version
        .substringBefore(".r") // Fixes Shizuku
        .substringBefore("_B") // Fixes MiXplorer
        .substringBefore("-")  // Fixes Beta tags
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(onRunCommand: suspend (String) -> String = { cmd -> RootUtils.runAsRoot(cmd) }) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val packageManager = context.packageManager
    val sharedPrefs = remember { context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE) }
    var selectedAppToInstall: AppInfo? by remember { mutableStateOf(null) }
    var showSideloadedAppDialog by remember { mutableStateOf(false) }
    var appToLaunchOnBoot by rememberSaveable {
        mutableStateOf(sharedPrefs.getString("start_app_on_boot", ""))
    }

    // --- Uninstall Logic States ---
    var isPreventAutoInstallEnabled by rememberSaveable {
        mutableStateOf(sharedPrefs.getBoolean("prevent_auto_install", false))
    }
    var uninstallableApps by remember { mutableStateOf<List<Triple<String, String, Drawable?>>>(emptyList()) }
    var isRefreshingUninstallList by remember { mutableStateOf(false) }

    // --- App Launcher ---
    val saveAppToLaunchOnBoot: (String?) -> Unit = { packageName ->
        val targetPackageName = if (packageName.isNullOrBlank()) "com.oculus.store" else packageName

        appToLaunchOnBoot = packageName
        sharedPrefs.edit().putString("start_app_on_boot", packageName).apply()
        showSideloadedAppDialog = false

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val existingConfigRaw = RootUtils.runAsRoot("cat /sdcard/boot_config.json")
                val rootJson = if (existingConfigRaw.isNotBlank() && !existingConfigRaw.contains("No such file")) {
                    try { JSONObject(existingConfigRaw) } catch (e: Exception) { JSONObject() }
                } else {
                    JSONObject()
                }

                val appSet = rootJson.optJSONObject("defaultVrAppSet") ?: JSONObject()
                val primaryApp = JSONObject().put("component", targetPackageName)

                appSet.put("primaryApplication", primaryApp)
                appSet.put("secondaryApps", org.json.JSONArray())
                appSet.put("launchPolicy", "first-time")

                rootJson.put("defaultVrAppSet", appSet)

                RootUtils.runAsRoot(LaunchCommands.ENABLE_BOOT_OVERRIDES)

                val formattedJsonString = rootJson.toString(4)

                RootUtils.runAsRoot("echo '$formattedJsonString' > /sdcard/boot_config.json")
    
                Log.d("AppsActivity", "Boot config updated successfully with app: $targetPackageName")
            } catch (e: Exception) {
                Log.e("AppsActivity", "Failed to update boot config: ${e.message}")
            }
        }
    }

    // --- APK Installer ---
    val localApkInstallAction: suspend (Context, (String) -> Unit, Uri?) -> Unit = { ctx, onStatus, uri ->
        uri?.let { fileUri ->
            AppInstaller.installFromUri(ctx, fileUri, "Local APK", onStatus)
        } ?: onStatus("Error: File not found")
    }

    // --- List of apps to be displayed ---
    val appList = listOf(
        AppInfo(
            title = "Install APK",
            description = "Use the Android file manager to select and install an APK file from your device",
            packageName = "com.veygax.eventhorizon.localapkinstaller",
            type = AppInstallType.FILE_PICKER,
            installAction = localApkInstallAction
        ),
        AppInfo(
            title = "App Manager",
            description = "A full-featured package manager and viewer for Android",
            packageName = "io.github.muntashirakon.AppManager",
            githubOwner = "MuntashirAkon",
            githubRepo = "AppManager",
            installAction = { ctx, onStatus, _ ->
                AppInstaller.downloadAndInstall(ctx, "MuntashirAkon", "AppManager", onStatus)
            }
        ),
        AppInfo(
            title = "Dock Editor",
            description = "A simple tool for the Quest 3/3s that allows you to edit the pinned applications on the dock",
            packageName = "com.lumi.dockeditor",
            githubOwner = "Lumince",
            githubRepo = "DockEditor",
            installAction = { ctx, onStatus, _ ->
                AppInstaller.downloadAndInstall(ctx, "Lumince", "DockEditor", onStatus)
            }
        ),
        AppInfo(
            title = "Shizuku",
            description = "Lets other apps use system-level features by giving them elevated permissions",
            packageName = "moe.shizuku.privileged.api",
            githubOwner = "RikkaApps",
            githubRepo = "Shizuku",
            installAction = { ctx, onStatus, _ ->
                AppInstaller.downloadAndInstall(ctx, "RikkaApps", "Shizuku", onStatus)
            }
        ),
        AppInfo(
            title = "MiXplorer",
            description = "Root File Explorer",
            packageName = "com.mixplorer",
            githubOwner = "driftywinds",
            githubRepo = "mixplorer-releases",
            installAction = { ctx, onStatus, _ ->
                AppInstaller.downloadAndInstall(ctx, "driftywinds", "mixplorer-releases", onStatus)
            }
        )
    )

    // Detect installation status on initialization
    val appStates = remember {
        mutableStateMapOf<String, AppItemState>().apply {
            appList.forEach { app ->
                // Use the sanitizer here
                val version = sanitizeVersionString(getInstalledVersion(app.packageName, packageManager))
                val statusText = if (version != null) "Installed (v$version)" else "Ready"
                this[app.packageName] = AppItemState(status = statusText, isProcessing = false, currentVersion = version)
            }
        }
    }

    // Check for updates on GitHub in the background
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            appList.forEach { app ->
                val owner = app.githubOwner
                val repo = app.githubRepo
                val currentVer = appStates[app.packageName]?.currentVersion

                if (owner != null && repo != null && currentVer != null) {
                    try {
                        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
                        val url = URL(apiUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        val jsonText = connection.inputStream.bufferedReader().readText()
                        connection.disconnect()

                        val json = JSONObject(jsonText)
                        // Sanitize the tag pulled from GitHub
                        val latestVersion = sanitizeVersionString(json.getString("tag_name"))!!

                        if (UpdateManager.isNewerVersion(latestVersion, currentVer)) {
                            withContext(Dispatchers.Main) {
                                appStates[app.packageName] = appStates[app.packageName]!!.copy(
                                    hasUpdate = true,
                                    latestVersion = latestVersion,
                                    status = "Update Available ($latestVersion)"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AppsActivity", "Failed to check update for ${app.title}: ${e.message}")
                    }
                }
            }
        }
    }

    // Helper to refresh installation status after action
    val refreshStatus: (String) -> Unit = { packageName ->
        val version = getInstalledVersion(packageName, packageManager)
        val statusText = if (version != null) "Installed (v$version)" else "Ready"
        appStates[packageName] = appStates[packageName]?.copy(
            status = statusText,
            isProcessing = false,
            currentVersion = version,
            hasUpdate = false // Reset update flag after install/update
        ) ?: AppItemState(status = statusText, isProcessing = false, currentVersion = version)
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            selectedAppToInstall?.let { appInfo ->
                val packageName = appInfo.packageName

                appStates[packageName] = appStates[packageName]!!.copy(status = "Starting Installation...", isProcessing = true)
                coroutineScope.launch {
                    appInfo.installAction(context, { newStatus ->
                        appStates[packageName] = appStates[packageName]!!.copy(status = newStatus, isProcessing = true)
                    }, fileUri)
                    // Refresh status after local APK install
                    refreshStatus(packageName)
                }
            }
        }
        selectedAppToInstall = null
    }

    fun refreshUninstallList() {
        coroutineScope.launch(Dispatchers.IO) {
            isRefreshingUninstallList = true
            val targetApps = mapOf(
                "com.oculus.facebook" to "Facebook",
                "com.facebook.orca" to "Facebook Messenger",
                "com.oculus.igvr" to "Instagram",
                "com.oculus.assistant" to "Meta AI",
                "com.facebook.horizon" to "Meta Horizon",
                "com.whatsapp" to "WhatsApp"
            )
            val pm = context.packageManager
            val foundApps = mutableListOf<Triple<String, String, Drawable?>>()

            targetApps.forEach { (pkg, name) ->
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val icon = pm.getApplicationIcon(appInfo)
                    foundApps.add(Triple(name, pkg, icon))
                } catch (e: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                uninstallableApps = foundApps
                isRefreshingUninstallList = false
            }
        }
    }

    // Trigger refresh when toggle is enabled
    LaunchedEffect(isPreventAutoInstallEnabled) {
        if (isPreventAutoInstallEnabled) {
            refreshUninstallList()
        }
    }

    // State for Dogfood Hub feature
    var showRestartDialog by remember { mutableStateOf(false) }
    var restartDialogContent by remember { mutableStateOf<Pair<String, () -> Unit>>(Pair("", {})) }
    var isDogfoodEnabled by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Check Dogfood Hub status
        val buildType = onRunCommand("getprop ro.build.type")
        isDogfoodEnabled = buildType.trim() == "userdebug"

        // Check for Dogfood Hub setup step 2
        if (sharedPrefs.getBoolean("dogfood_pending_step2", false)) {
            sharedPrefs.edit().remove("dogfood_pending_step2").apply()
            onRunCommand(LaunchCommands.ENABLE_DOGFOOD_STEP_2)
        }
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart Required") },
            text = { Text(restartDialogContent.first) },
            confirmButton = { Button(onClick = { restartDialogContent.second(); showRestartDialog = false }) { Text("Confirm") } },
            dismissButton = { Button(onClick = { showRestartDialog = false }) { Text("Cancel") } }
        )
    }

    if (showSideloadedAppDialog) {
        LaunchStartAppOnBootDialog(
            onDismiss = { showSideloadedAppDialog = false },
            onAppSelected = saveAppToLaunchOnBoot
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        val tabTitles = listOf("Launch", "Install", "Uninstall")
        val initialAppPage = sharedPrefs.getInt("last_app_tab", 0)
        val pagerState = rememberPagerState(
            initialPage = initialAppPage,
            pageCount = { tabTitles.size }
        )

        LaunchedEffect(pagerState.currentPage) {
            sharedPrefs.edit().putInt("last_app_tab", pagerState.currentPage).apply()
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = { Text(title) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (page) {
                        0 -> {
                            item {
                                AppCard("Dogfood Hub", "Enables the hidden Dogfood Hub for experimental features") {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Switch(checked = isDogfoodEnabled, onCheckedChange = { isEnabled ->
                                            restartDialogContent = if (isEnabled) {
                                                Pair("This will restart your device's interface. After it reloads, please open this app again to complete the second step automatically.") {
                                                    coroutineScope.launch {
                                                        sharedPrefs.edit().putBoolean("dogfood_pending_step2", true).apply()
                                                        RootUtils.runAsRoot(LaunchCommands.ENABLE_DOGFOOD_STEP_1)
                                                    }
                                                }
                                            } else {
                                                Pair("This will disable the Dogfood Hub and restart your device's interface") {
                                                    coroutineScope.launch { RootUtils.runAsRoot(LaunchCommands.DISABLE_DOGFOOD_HUB) }
                                                }
                                            }
                                            showRestartDialog = true
                                        })
                                        Spacer(Modifier.height(8.dp))
                                        Button(
                                            onClick = { coroutineScope.launch { RootUtils.runAsRoot(LaunchCommands.LAUNCH_DOGFOOD_HUB) } },
                                            enabled = isDogfoodEnabled
                                        ) { Text("Launch") }
                                    }
                                }
                            }
                            item {
                                AppCard("Shell Debug", "Launches employee only debug configuration") {
                                    Button(onClick = {
                                        coroutineScope.launch { 
                                            RootUtils.runAsRoot(LaunchCommands.LAUNCH_SHELL_DEBUG) 
                                        }
                                    }) { 
                                        Text("Launch") 
                                    }
                                }
                            }
                            item {
                                AppCard("Android Settings", "Launches the Android settings app") {
                                    Button(onClick = {
                                        coroutineScope.launch { RootUtils.runAsRoot(LaunchCommands.LAUNCH_ANDROID_SETTINGS) }
                                    }) { Text("Launch") }
                                }
                            }
                            item {
                                AppCard("Android File Manager", "Launches the Android file manager") {
                                    Button(onClick = {
                                        coroutineScope.launch { RootUtils.runAsRoot(LaunchCommands.LAUNCH_FILE_MANAGER) }
                                    }) { Text("Launch") }
                                }
                            }
                            item {
                                AppCard("Start App on Boot",
                                    "Select an app to automatically launch on boot\nCurrent: ${if (appToLaunchOnBoot.isNullOrBlank()) "Meta Store (Default)" else appToLaunchOnBoot}") {
                                    Row {
                                        val isAppSelected = !appToLaunchOnBoot.isNullOrBlank()
                                        val buttonText = if (isAppSelected) "Clear" else "Select"

                                        Button(
                                            onClick = {
                                                if (isAppSelected) {
                                                    saveAppToLaunchOnBoot(null)
                                                } else {
                                                    showSideloadedAppDialog = true
                                                }
                                            },
                                        ) {
                                            Text(buttonText)
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            items(appList) { app ->
                                val state = appStates[app.packageName] ?: AppItemState("Ready", false)
                                val isInstalling = state.isProcessing
                                val isInstalled = state.currentVersion != null
                                val hasUpdate = state.hasUpdate
                                val status = state.status
                            
                                AppCard(
                                    title = app.title,
                                    description = app.description,
                                    status = status
                                ) {
                                    val buttonText = when {
                                        isInstalling -> "Processing..."
                                        hasUpdate -> "Update"
                                        isInstalled -> "Launch"
                                        app.type == AppInstallType.MANUAL_LINK -> "Open Link"
                                        app.type == AppInstallType.FILE_PICKER -> "Select"
                                        else -> "Install"
                                    }

                                    Button(
                                        onClick = {
                                            if (isInstalled && !hasUpdate) {
                                                coroutineScope.launch {
                                                    val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                                                    if (launchIntent != null) {
                                                        context.startActivity(launchIntent)
                                                    } else {
                                                        // Fallback: Use root to force-launch via monkey (useful for Quest system apps)
                                                        withContext(Dispatchers.IO) {
                                                            RootUtils.runAsRoot("monkey -p ${app.packageName} -c android.intent.category.LAUNCHER 1")
                                                        }
                                                    }
                                                }
                                            } else if (app.type == AppInstallType.FILE_PICKER) {
                                                selectedAppToInstall = app
                                                apkPickerLauncher.launch("*/*")
                                            } else {
                                                // Standard Install/Update Logic
                                                appStates[app.packageName] = state.copy(status = "Starting...", isProcessing = true)
                                                coroutineScope.launch {
                                                    app.installAction(context, { newStatus ->
                                                        appStates[app.packageName] = appStates[app.packageName]!!.copy(status = newStatus, isProcessing = true)
                                                    }, null)
                                                    // Refresh status after direct install action
                                                    refreshStatus(app.packageName)
                                                }
                                            }
                                        },
                                        enabled = !isInstalling
                                    ) {
                                        Text(buttonText)
                                    }
                                }
                            }
                        }

                        2 -> {
                            item {
                                AppCard(
                                    title = "Prevent Auto-Installs",
                                    description = "Stops the auto installation of default meta apps."
                                ) {
                                    Switch(
                                        checked = isPreventAutoInstallEnabled,
                                        onCheckedChange = { isEnabled ->
                                            isPreventAutoInstallEnabled = isEnabled
                                            sharedPrefs.edit().putBoolean("prevent_auto_install", isEnabled).apply()
                                            
                                            coroutineScope.launch(Dispatchers.IO) {
                                                if (isEnabled) {
                                                    RootUtils.runAsRoot(LaunchCommands.DISABLE_AUTO_INSTALL_SERVICES)
                                                    refreshUninstallList()
                                                } else {
                                                    RootUtils.runAsRoot(LaunchCommands.ENABLE_AUTO_INSTALL_SERVICES)
                                                    withContext(Dispatchers.Main) {
                                                        uninstallableApps = emptyList()
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                            
                            if (isPreventAutoInstallEnabled) {
                                if (uninstallableApps.isEmpty() && !isRefreshingUninstallList) {
                                    item {
                                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            Text("No default apps found to uninstall.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                } else {
                                    items(uninstallableApps) { (name, pkg, icon) ->
                                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (icon != null) {
                                                    DrawableImage(
                                                        drawable = icon,
                                                        contentDescription = name,
                                                        modifier = Modifier.size(40.dp)
                                                    )
                                                } else {
                                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(40.dp))
                                                }
                                                
                                                Spacer(modifier = Modifier.width(16.dp))
                                                
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                                                    Text(text = pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                
                                                Button(
                                                    onClick = {
                                                        coroutineScope.launch(Dispatchers.IO) {
                                                            RootUtils.runAsRoot("pm uninstall --user 0 $pkg")
                                                            refreshUninstallList()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                                ) {
                                                    Text("Uninstall")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DrawableImage(drawable: Drawable, contentDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            ImageView(it).apply {
                setImageDrawable(drawable)
            }
        },
        update = {
            it.setImageDrawable(drawable)
        },
        modifier = modifier
    )
}

@Composable
fun LaunchStartAppOnBootDialog(
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var appList by remember { mutableStateOf<List<Triple<Drawable, String, String>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val exclusionList = listOf("com.oculus", "com.meta", "com.google", "com.facebook")

    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        try {
            val command = "pm list packages -3"
            val output = RootUtils.runAsRoot(command)
            val pm = context.packageManager
            val defaultIcon = context.getDrawable(android.R.drawable.sym_def_app_icon)!!

            if (output.contains("Execution failed") || output.contains("ERROR:")) {
                errorMessage = "Failed to run command with root. Falling back to non-root method."
                Log.e("SideloadedAppDialog", "Root command failed: $output")

                val installedApps = pm.getInstalledApplications(0)
                appList = installedApps.filter { appInfo ->
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                }.map { appInfo ->
                    Triple(pm.getApplicationIcon(appInfo), pm.getApplicationLabel(appInfo).toString(), appInfo.packageName)
                }.sortedBy { it.second }

            } else {
                val packageNames = output.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.substringAfter("package:").trim() }
                    .filter { packageName -> !exclusionList.any { packageName.startsWith(it) } }

                appList = packageNames.mapNotNull { packageName ->
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        Triple(pm.getApplicationIcon(appInfo), pm.getApplicationLabel(appInfo).toString(), packageName)
                    } catch (e: Exception) {
                        Triple(defaultIcon, packageName, packageName)
                    }
                }.sortedBy { it.second }
            }
        } catch (e: Exception) {
            errorMessage = "An unexpected error occurred: ${e.message}"
            Log.e("SideloadedAppDialog", "Exception during app fetching: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select App to Launch on Boot") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (appList.isEmpty()) {
                         Text("App list could not be loaded.")
                    }
                } else if (appList.isEmpty()) {
                    Text("No user-installed apps found.")
                } else {
                    LazyColumn {
                        items(appList) { (icon, name, packageName) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppSelected(packageName) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DrawableImage(
                                    drawable = icon,
                                    contentDescription = name,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(name, style = MaterialTheme.typography.titleMedium)
                                    Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AppSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand"
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
fun AppCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp) 
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            content()
        }
    }
}

@Composable
fun AppCard(title: String, description: String, status: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                }
                content()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Status: $status",
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Installed")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

object LaunchCommands {
    const val ENABLE_DOGFOOD_STEP_1 = "magisk resetprop ro.build.type userdebug\nstop\nstart"
    const val ENABLE_DOGFOOD_STEP_2 = "am broadcast -a oculus.intent.action.DC_OVERRIDE --esa config_param_value oculus_systemshell:oculus_is_trusted_user:true\nstop\nstart"
    const val DISABLE_DOGFOOD_HUB = "magisk resetprop --delete ro.build.type\nstop\nstart"
    const val LAUNCH_DOGFOOD_HUB = "am start com.oculus.vrshell/com.oculus.panelapp.dogfood.DogfoodMainActivity"
    const val LAUNCH_SHELL_DEBUG = "am start -n com.oculus.vrshell/com.oculus.panelapp.debug.ShellDebugActivity"
    const val LAUNCH_ANDROID_SETTINGS = "am start -n com.android.settings/.Settings"
    const val LAUNCH_FILE_MANAGER = "am start -n com.android.documentsui/.files.FilesActivity"

    const val ENABLE_BOOT_OVERRIDES = "oculuspreferences --setc debug_enable_boot_configuration_overrides true"
    const val DISABLE_BOOT_OVERRIDES = "oculuspreferences --setc debug_enable_boot_configuration_overrides false"

    const val DISABLE_AUTO_INSTALL_SERVICES = """
        setprop persist.ocms.post_nux_default_apps_install_enabled 0
        am force-stop com.oculus.ocms
        pm disable com.oculus.ocms/com.oculus.ocms.defaultapps.DefaultAppsNuxOtaReceiver
        pm disable com.oculus.ocms/com.oculus.ocms.updates.auto.AutoUpdateOnScreenOffAlarmReceiver
        pm disable com.oculus.ocms/com.oculus.ocms.installer2.service.Installer2StatusReceiver
        pm disable com.oculus.ocms/com.oculus.ocms.library.service.BinaryCheckUpdateIntentService
    """

    const val ENABLE_AUTO_INSTALL_SERVICES = """
        setprop persist.ocms.post_nux_default_apps_install_enabled 1
        am force-stop com.oculus.ocms
        pm enable com.oculus.ocms/com.oculus.ocms.defaultapps.DefaultAppsNuxOtaReceiver
        pm enable com.oculus.ocms/com.oculus.ocms.updates.auto.AutoUpdateOnScreenOffAlarmReceiver
        pm enable com.oculus.ocms/com.oculus.ocms.installer2.service.Installer2StatusReceiver
        pm enable com.oculus.ocms/com.oculus.ocms.library.service.BinaryCheckUpdateIntentService
    """
}

@Preview(showBackground = true, name = "Apps Screen Preview", heightDp = 600)
@Composable
fun AppsScreenPreview() {
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppsScreen(onRunCommand = { "" })
        }
    }
}