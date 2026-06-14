package com.veygax.eventhorizon.ui.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipFile

interface AdbRootActions {
    fun openSettings()
    fun getAdbHomeDir(): String
    fun getAdbBinary(): File?
    suspend fun getMagiskPatcherDir(): File?
}

class AdbRootActivity : ComponentActivity(), AdbRootActions {
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
                Surface(
                    modifier = Modifier.fillMaxSize(), 
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdbRootScreen(onBack = { finish() }, actions = this@AdbRootActivity)
                }
            }
        }
    }

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:com.android.settings")
        intent.setPackage("com.android.settings")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivity(intent)
    }

    override fun getAdbHomeDir(): String {
        return getDir("adb_keys", Context.MODE_PRIVATE).absolutePath
    }

    override fun getAdbBinary(): File? {
        return prepareAdbBinary(this)
    }

    override suspend fun getMagiskPatcherDir(): File? {
        return prepareMagiskPatcher(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbRootScreen(onBack: () -> Unit, actions: AdbRootActions) {
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("") }

    val mainScrollState = rememberScrollState()
    val logScrollState = rememberScrollState()

    var hasRoot by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }

    val adbHomeDir = remember { actions.getAdbHomeDir() }

    fun log(msg: String) {
        logText += "> $msg\n"
    }

    LaunchedEffect(logText) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ADB Root Setup") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize()
                .verticalScroll(mainScrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = {
                actions.openSettings()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("1. Open Android Settings")
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("What to do next:", fontWeight = FontWeight.Bold)
                    Text("1. Tap 'Open' to launch settings.\n" +
                            "2. About headset -> Tap 'Build number' 7-8 times.\n" +
                            "3. System -> Developer options -> Enable 'Wireless ADB' (Always allow).")
                }
            }

            // --- Wireless ADB Pairing Section ---
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Pair via Wireless ADB", fontWeight = FontWeight.Bold)
                    Text("In Developer Options → Wireless ADB → 'Pair device with pairing code'. Enter the port and 6-digit code shown on screen.")
                    OutlinedTextField(
                        value = pairingPort,
                        onValueChange = { pairingPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Pairing Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter { c -> c.isDigit() } },
                        label = { Text("Pairing Code (6 digits)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (isProcessing) return@Button
                            val port = pairingPort.trim()
                            val code = pairingCode.trim()
                            if (port.isEmpty() || code.isEmpty()) {
                                log("Error: Please enter both the pairing port and code.")
                                return@Button
                            }
                            isProcessing = true
                            scope.launch {
                                val adbFile = actions.getAdbBinary()
                                if (adbFile == null) {
                                    log("Error: Could not locate ADB binary in native libraries.")
                                    isProcessing = false
                                    return@launch
                                }
                                log("Pairing with 127.0.0.1:$port using code $code ...")
                                val pairResult = executeAdb(adbFile, adbHomeDir, "pair", "127.0.0.1:$port", code)
                                val pairOutput = pairResult.trim().ifEmpty { "No output from pair command." }
                                log(pairOutput)

                                val paired = pairOutput.contains("Successfully paired", ignoreCase = true)
                                if (paired) {
                                    log("Pairing successful! Switching device to TCP port 5555...")
                                    val tcpipResult = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:$port", "tcpip", "5555")
                                    log(tcpipResult.trim().ifEmpty { "No output from tcpip command." })
                                    delay(1000)
                                    log("Ready. You can now press 'Escalate Privileges'.")
                                } else {
                                    log("Pairing may have failed. Check the port and code and try again.")
                                }
                                isProcessing = false
                            }
                        },
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isProcessing) "Pairing..." else "Pair Device")
                    }
                }
            }

            Button(
                onClick = {
                    if (isProcessing) return@Button
                    isProcessing = true
                    scope.launch {
                        val adbFile = actions.getAdbBinary()
                        if (adbFile == null) {
                            log("Error: Could not locate binary.")
                            isProcessing = false
                            return@launch
                        }

                        log("Connecting to Wireless ADB...")
                        val connectResult = executeAdb(adbFile, adbHomeDir, "connect", "127.0.0.1:5555")
                        log(connectResult.trim().ifEmpty { "No output from connect command." })

                        delay(1500)

                        val devicesResult = executeAdb(adbFile, adbHomeDir, "devices")
                        log("Devices:\n${devicesResult.trim()}")
                        if (!devicesResult.contains("127.0.0.1:5555\tdevice")) {
                            log("Error: Device not found or unauthorized. Check that pairing completed successfully and Wireless ADB is still enabled.")
                            isProcessing = false
                            return@launch
                        }

                        log("Requesting root access via ADB root...")
                        executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "root")
                        delay(2500)

                        executeAdb(adbFile, adbHomeDir, "connect", "127.0.0.1:5555")
                        
                        log("Verifying root privileges...")
                        val identity = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "whoami").trim()
                        log("Current user: $identity")
                        
                        if (identity == "root") {
                            hasRoot = true
                        } else {
                            hasRoot = false
                            log("Warning: Did not verify root access.")
                        }

                        executeAdb(
                            adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "pm", "grant",
                            "com.veygax.eventhorizon", "android.permission.WRITE_SECURE_SETTINGS"
                        )

                        isProcessing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "Processing..." else "3. Escalate Privileges")
            }

            Button(
                onClick = {
                    if (isProcessing) return@Button
                    isProcessing = true
                    scope.launch {
                        val adbFile = actions.getAdbBinary()
                        if (adbFile == null) {
                            log("Error: ADB binary not found.")
                            isProcessing = false
                            return@launch
                        }

                        log("Fetching current boot slot...")
                        val slotSuffix = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "getprop", "ro.boot.slot_suffix").trim()
                        
                        if (slotSuffix != "_a" && slotSuffix != "_b") {
                            log("Error: Could not determine valid boot slot. Got: '$slotSuffix'")
                            isProcessing = false
                            return@launch
                        }
                        
                        log("Current boot slot is: $slotSuffix")
                        
                        val sourceBlock = "/dev/block/bootdevice/by-name/boot$slotSuffix"
                        val destDir = "/data/adb/eventhorizon"
                        val destFile = "$destDir/boot.img"

                        log("Creating destination directory: $destDir")
                        executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "mkdir", "-p", destDir)

                        log("Extracting $sourceBlock to $destFile...")
                        val copyResult = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "dd", "if=$sourceBlock", "of=$destFile")
                        if (copyResult.isNotBlank() && !copyResult.contains("records in")) log(copyResult.trim())
                        
                        log("Extracting Magisk patcher utilities locally...")
                        val localPatchDir = actions.getMagiskPatcherDir()
                        if (localPatchDir == null) {
                            log("Error: Failed to prepare Magisk patch files.")
                            isProcessing = false
                            return@launch
                        }
                        
                        val remotePatchDir = "/data/adb/eventhorizon/magisk_patcher"
                        log("Pushing patcher utilities to $remotePatchDir...")
                        
                        executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "mkdir", "-p", remotePatchDir)
                        
                        val pushResult = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "push", localPatchDir.absolutePath, "/data/adb/eventhorizon/")
                        if (pushResult.contains("error", ignoreCase = true) || pushResult.contains("failed", ignoreCase = true)) {
                            log("Error pushing files to root directory: $pushResult")
                        }
                        
                        log("Patching boot image with Magisk 30.6...")
                        
                        val patchCmd = "cd $remotePatchDir && " +
                                "chmod 755 boot_patch.sh util_functions.sh init-ld magisk magiskboot magiskinit  && " +
                                "export KEEPVERITY=true && " +
                                "export KEEPFORCEENCRYPT=true && " +
                                "export MAGISKDIR=$remotePatchDir && " +
                                "./boot_patch.sh $destFile"
                        
                        val patchOutput = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", patchCmd)
                        
                        if (patchOutput.contains("- Repacking boot image")) {
                            val patchedImgPath = "$remotePatchDir/new-boot.img"
                            val patchedDestFile = "$destDir/patched_boot.img"
                            
                            log("Moving patched image to $patchedDestFile")
                            executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "mv", patchedImgPath, patchedDestFile)
                            
                            val verifyOutput = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "ls", "-lh", patchedDestFile).trim()
                            if (verifyOutput.contains("No such file")) {
                                log("Error: Patched image not found at destination.")
                            } else {
                                log("Success! Patched boot image saved.")
                                log(verifyOutput)
                                
                                log("Flashing patched image back to $sourceBlock...")
                                val flashResult = executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "shell", "dd", "if=$patchedDestFile", "of=$sourceBlock", "bs=4096", "conv=fsync")

                                if (flashResult.isNotBlank() && !flashResult.contains("records in")) {
                                    log("Warning/Error during flash: \n${flashResult.trim()}")
                                } else {
                                    log("Successfully flashed Magisk to the active slot! Rebooting...")
                                    executeAdb(adbFile, adbHomeDir, "-s", "127.0.0.1:5555", "reboot")
                                }
                            }
                        } else {
                            log("Error: Magisk patch process failed.")
                            log("Output: \n$patchOutput")
                        }

                        isProcessing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing && hasRoot 
            ) {
                Text(
                    if (!hasRoot) "4. Patch boot.img (Requires Root)" 
                    else if (isProcessing) "Processing..." 
                    else "4. Patch boot.img"
                )
            }

            Text("ADB Logs:", style = MaterialTheme.typography.titleMedium)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                SelectionContainer {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(logScrollState)
                    )
                }
            }
        }
    }
}

private suspend fun prepareMagiskPatcher(context: Context): File? = withContext(Dispatchers.IO) {
    try {
        val patchDir = File(context.cacheDir, "magisk_patcher")
        if (!patchDir.exists()) patchDir.mkdirs()

        val apkFile = File(context.cacheDir, "Magisk-v30.6.apk")

        if (!apkFile.exists()) {
            val magiskUrl = "https://github.com/topjohnwu/Magisk/releases/download/v30.6/Magisk-v30.6.apk"
            URL(magiskUrl).openStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        ZipFile(apkFile).use { zip ->
            val filesToExtract = mapOf(
                "assets/boot_patch.sh" to "boot_patch.sh",
                "assets/util_functions.sh" to "util_functions.sh",
                "assets/stub.apk" to "stub.apk",
                "lib/arm64-v8a/libinit-ld.so" to "init-ld",
                "lib/arm64-v8a/libmagisk.so" to "magisk",
                "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
                "lib/arm64-v8a/libmagiskinit.so" to "magiskinit"
            )

            filesToExtract.forEach { (zipPath, outName) ->
                val entry = zip.getEntry(zipPath) ?: throw Exception("Missing $zipPath in APK")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(File(patchDir, outName)).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        patchDir
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun prepareAdbBinary(context: Context): File? {
    val adbFile = File(context.applicationInfo.nativeLibraryDir, "libadb.so")
    
    return try {
        if (adbFile.exists()) {
            adbFile
        } else {
            android.util.Log.e("AdbRootActivity", "Error: libadb.so not found in ${context.applicationInfo.nativeLibraryDir}")
            null
        }
    } catch (e: Exception) { 
        android.util.Log.e("AdbRootActivity", "Exception: ${e.message}")
        null 
    }
}

suspend fun executeAdb(adbFile: File, homeDir: String, vararg args: String): String = withContext(Dispatchers.IO) {
    var process: Process? = null
    try {
        val command = mutableListOf(adbFile.absolutePath)
        command.addAll(args)
        
        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = homeDir }
            .start()
            
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    } catch (e: Exception) { 
        "Error: ${e.message}" 
    } finally {
        try {
            process?.inputStream?.close()
            process?.outputStream?.close()
            process?.errorStream?.close()
        } catch (e: Exception) {
            android.util.Log.e("AdbRootActivity", "Error closing streams: ${e.message}")
        }
        process?.destroy()
    }
}

@Preview(showBackground = true, name = "ADB Root Screen Preview")
@Composable
fun AdbRootScreenPreview() {
    val mockActions = object : AdbRootActions {
        override fun openSettings() {}
        override fun getAdbHomeDir() = "/mock/path/adb_keys"
        override fun getAdbBinary(): File? = null
        override suspend fun getMagiskPatcherDir(): File? = null
    }

    MaterialTheme {
        Surface {
            AdbRootScreen(onBack = {}, actions = mockActions)
        }
    }
}