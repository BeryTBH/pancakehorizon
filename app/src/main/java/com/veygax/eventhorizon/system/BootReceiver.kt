package com.veygax.eventhorizon.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scottyab.rootbeer.RootBeer
import com.veygax.eventhorizon.system.DnsBlockerService
import com.veygax.eventhorizon.ui.activities.MainActivity
import com.veygax.eventhorizon.utils.CpuUtils
import com.veygax.eventhorizon.utils.GpuUtils
import com.veygax.eventhorizon.utils.RootUtils
import com.veygax.eventhorizon.ui.activities.TweakCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
            val rootOnBoot = sharedPrefs.getBoolean("root_on_boot", false)
            val startOnBoot = sharedPrefs.getBoolean("start_on_boot", false)
            val blockerOnBoot = sharedPrefs.getBoolean("blocker_on_boot", false)
            val rainbowLedOnBoot = sharedPrefs.getBoolean("rgb_on_boot", false)
            val customLedOnBoot = sharedPrefs.getBoolean("custom_led_on_boot", false)
            val powerLedOnBoot = sharedPrefs.getBoolean("power_led_on_boot", false)
            val minFreqOnBoot = sharedPrefs.getBoolean("min_freq_on_boot", false)
            val interceptStartupApps = sharedPrefs.getBoolean("intercept_startup_apps", false)
            val wirelessAdbOnBoot = sharedPrefs.getBoolean("wireless_adb_on_boot", false)
            val cycleWifiOnBoot = sharedPrefs.getBoolean("cycle_wifi_on_boot", false)
            val isRootBlockerEnabledOnBoot = sharedPrefs.getBoolean("root_blocker_on_boot", false)
            val isRootBlockerSetup = sharedPrefs.getBoolean("root_blocker_setup", false)
            val usbInterceptorOnBoot = sharedPrefs.getBoolean("usb_interceptor_on_boot", false)
            val proxSensorDisabled = sharedPrefs.getBoolean("prox_sensor_disabled", false)
            val passthroughFixOnBoot = sharedPrefs.getBoolean("passthrough_fix_on_boot", false)
            val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)
            val telemetryDisabledOnBoot = sharedPrefs.getBoolean(TweakCommands.TELEMETRY_TOGGLE_KEY, false)
            val gpuMinFreqOnBoot = sharedPrefs.getBoolean("gpu_min_freq_on_boot", false)
            val gpuMaxFreqOnBoot = sharedPrefs.getBoolean("gpu_max_freq_on_boot", false)
            val scope = CoroutineScope(Dispatchers.IO)

            // --- Activity Boot Logic ---
            // Create a single intent to be launched only if needed.
            val startIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            var shouldStartActivity = false

            if (rootOnBoot) {
                val rootBeer = RootBeer(context)
                if (!rootBeer.isRooted) {
                    // Add the auto_root instruction to our single intent
                    startIntent.putExtra("auto_root", true)
                    shouldStartActivity = true
                }
            }

            if (startOnBoot) {
                shouldStartActivity = true
            }
            
            if (blockerOnBoot) {
                // Add the start_dns_blocker instruction to our single intent
                startIntent.putExtra("start_dns_blocker", true)
                shouldStartActivity = true
            }

            // After checking all conditions, launch the activity just once if required.
            if (shouldStartActivity) {
                context.startActivity(startIntent)
            }

            // --- LED Boot Logic --- (Using TweakService)
            if (customLedOnBoot) {
                // Use TweakService to start custom color
                val r = sharedPrefs.getInt("led_red", 255)
                val g = sharedPrefs.getInt("led_green", 255)
                val b = sharedPrefs.getInt("led_blue", 255)

                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_CUSTOM_LED
                    putExtra("RED", r)
                    putExtra("GREEN", g)
                    putExtra("BLUE", b)
                }
                context.startService(serviceIntent)

            } else if (rainbowLedOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_RGB
                }
                context.startService(serviceIntent)

            } else if (powerLedOnBoot) { // <-- This is the new, required block
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_POWER_LED
                }
                context.startService(serviceIntent)
            }

            // --- CPU Lock Boot Logic (Using TweakService) ---
            if (minFreqOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_MIN_FREQ
                }
                context.startService(serviceIntent)
            }

            // --- GPU Min Lock Boot Logic (Using TweakService) ---
            if (gpuMinFreqOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_GPU_MIN_FREQ
                }
                context.startService(serviceIntent)
            }

            // --- GPU Max Lock Boot Logic (Using TweakService) ---
            if (gpuMaxFreqOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_GPU_MAX_FREQ
                }
                context.startService(serviceIntent)
            }

            with (sharedPrefs.edit()) {
                if (!customLedOnBoot) putBoolean("custom_led_is_running", false)
                if (!rainbowLedOnBoot) putBoolean("rgb_led_is_running", false)
                if (!powerLedOnBoot) putBoolean("power_led_is_running", false)
                if (!minFreqOnBoot) putBoolean("min_freq_is_running", false)
                if (!gpuMinFreqOnBoot) putBoolean("gpu_min_freq_is_running", false)
                if (!gpuMaxFreqOnBoot) putBoolean("gpu_max_freq_is_running", false)
                apply()
            }

            // --- Wireless ADB Boot Logic ---
            if (wirelessAdbOnBoot) {
                scope.launch {
                    RootUtils.runAsRoot("setprop service.adb.tcp.port 5555")
                    RootUtils.runAsRoot("stop adbd && start adbd")
                }
            }

            // --- Intercept Startup Apps Logic (Using TweakService) ---
            if (interceptStartupApps) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_INTERCEPTOR
                }
                context.startService(serviceIntent)
            }

            // --- Wi-Fi Cycle on Boot Logic ---
            if (cycleWifiOnBoot) {
                scope.launch {
                    RootUtils.runAsRoot("svc wifi disable")
                    // Wait for a few seconds before turning it back on
                    kotlinx.coroutines.delay(3000) // 3-second delay
                    RootUtils.runAsRoot("svc wifi enable")
                }
            }

            // --- Domain Blocker Boot Logic ---
            if (isRootBlockerEnabledOnBoot && isRootBlockerSetup) {
                scope.launch {
                    delay(5000) 
                    if (RootUtils.isRootAvailable()) {
                        Log.i("BootReceiver", "Applying categorized domain blocker...")
            
                        val success = DomainBlockerManager.generateAndApplyHosts(context)
                        if (success) {
                            sharedPrefs.edit().putBoolean("root_blocker_is_running", true).apply()
                            
                            val stopVpnIntent = Intent(context, DnsBlockerService::class.java).apply {
                                action = DnsBlockerService.ACTION_STOP
                            }
                            context.startService(stopVpnIntent)
                        }
                    }
                }
            } else if (isRootBlockerEnabledOnBoot && !isRootBlockerSetup) {
                Log.w("BootReceiver", "Blocker enabled on boot but NOT setup. Skipping to avoid empty hosts file.")
            }

            // --- USB Interceptor Boot Logic ---
            if (usbInterceptorOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_USB_INTERCEPTOR
                }
                context.startService(serviceIntent)
            }

            // --- OTA Blocker Boot Logic ---
            val otaBlockerOnBoot = sharedPrefs.getBoolean("ota_blocker_on_boot", false)
            if (otaBlockerOnBoot) {
                val serviceIntent = Intent(context, TweakService::class.java).apply {
                    action = TweakService.ACTION_START_OTA_BLOCKER
                }
                context.startService(serviceIntent)
            }

            // --- Proxy Sensor Boot Logic ---
            if (proxSensorDisabled) {
                scope.launch {
                    RootUtils.runAsRoot("am broadcast -a com.oculus.vrpowermanager.prox_close")
                }
            } else {
                scope.launch {
                    RootUtils.runAsRoot("am broadcast -a com.oculus.vrpowermanager.automation_disable")
                }
            }

            // --- Telemetry Disable on Boot Logic ---
            suspend fun copyTelemetryAssets(context: Context) = withContext(Dispatchers.IO) {
            	val moduleDir = "/data/adb/eventhorizon"
            	val commands = StringBuilder("mkdir -p $moduleDir\n")
            	val filesToCopy = listOf("telemetry", "telemetry.rc", "fflogger_event_store.sql")
            
            	filesToCopy.forEach { fileName ->
            		val assetPath = "telemetry/$fileName"
            		val tempFile = File(context.cacheDir, "${fileName}_temp")
            		try {
            			context.assets.open(assetPath).use { input ->
            				tempFile.outputStream().use { output ->
            					input.copyTo(output)
            				}
            			}
            			commands.append("mv ${tempFile.absolutePath} $moduleDir/$fileName\n")
            			val perms = if (fileName == "telemetry") "755" else "644"
            			commands.append("chmod $perms $moduleDir/$fileName\n")
            		} catch (e: Exception) {
            			Log.e("Telemetry", "Failed to extract asset: $fileName", e)
            		}
            	}
            	RootUtils.runAsRoot(commands.toString(), useMountMaster = true)
            }
            if (telemetryDisabledOnBoot) {
            	scope.launch(Dispatchers.IO) {
            		if (RootUtils.isRootAvailable()) {
                        copyTelemetryAssets(context)
            			RootUtils.runAsRoot(TweakCommands.ENABLE_TELEMETRY_DISABLE, useMountMaster = true)
            		}
            	}
            }

            // --- Passthrough Fix on Boot Logic ---
            if (passthroughFixOnBoot) {
                scope.launch {
                    val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)

                    if (isRootBlockerEnabledOnBoot && isUnlocked) {
                        val flushed = RootUtils.runAsRoot("getprop sys.eh.boot_flushed").trim()
                        if (flushed != "1") {
                            Log.i("BootReceiver", "Passthrough Fix: Waiting for Domain Blocker soft reboot. Skipping this cycle.")
                            return@launch 
                        }
                    }
            
                    delay(8000)
                    if (RootUtils.isRootAvailable()) {
                        Log.i("BootReceiver", "Applying Passthrough Fix after system stability check.")
                        RootUtils.runAsRoot("am force-stop com.oculus.systemdriver", useMountMaster = true)
                        delay(5000)
                        RootUtils.runAsRoot("am start -n com.veygax.eventhorizon/.ui.activities.MainActivity", useMountMaster = true)
                        
                        val appToLaunch = sharedPrefs.getString("start_app_on_boot", null)
                        if (!appToLaunch.isNullOrEmpty()) {
                            val appIntent = context.packageManager.getLaunchIntentForPackage(appToLaunch)
                            appIntent?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(it)
                            }
                        }
                    }
                }
            }

            // --- Start App on Boot Logic ---
            val Startapp = sharedPrefs.getString("start_app_on_boot", null)
            if (!Startapp.isNullOrBlank()) {
                scope.launch {
                    val launchCommand = "monkey -p $Startapp -c android.intent.category.LAUNCHER 1"
                    RootUtils.runAsRoot(launchCommand)
                }
            }
        }
    }
}