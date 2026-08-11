package com.veygax.eventhorizon.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.veygax.eventhorizon.ui.activities.TweakCommands
import com.veygax.eventhorizon.utils.CpuUtils
import com.veygax.eventhorizon.utils.GpuUtils
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.*
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

class TweakService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val TAG = "TweakService"
    private val sharedPrefs by lazy { getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE) }

    // App Interceptor constants moved from AppInterceptor.kt
    private val INTERCEPTOR_SCRIPT = """
            #!/system/bin/sh
            TARGET_EXPLORE_ACTIVITY="com.oculus.explore/.ExploreActivity"
            TARGET_CONNECTIONS_ACTIVITY="com.oculus.socialplatform/com.oculus.panelapp.people.PeopleShelfActivity"
            TARGET_EVENTS_ACTIVITY="com.oculus.explore/.EventsActivity"

            logcat -c
            logcat -T 0 ActivityTaskManager:D *:S | while read -r line; do
                case "${'$'}line" in
                    *"START u0"*cmp=${'$'}TARGET_EXPLORE_ACTIVITY*)
                        pm disable "${'$'}TARGET_EXPLORE_ACTIVITY"
                        pm enable "${'$'}TARGET_EXPLORE_ACTIVITY"
                        ;;
                    *"START u0"*cmp=${'$'}TARGET_CONNECTIONS_ACTIVITY*)
                        pm disable "${'$'}TARGET_CONNECTIONS_ACTIVITY"
                        pm enable "${'$'}TARGET_CONNECTIONS_ACTIVITY"
                        ;;
                    *"START u0"*cmp=${'$'}TARGET_EVENTS_ACTIVITY*)
                        pm disable "${'$'}TARGET_EVENTS_ACTIVITY"
                        pm enable "${'$'}TARGET_EVENTS_ACTIVITY"
                        ;;
                esac
            done
        """.trimIndent()

    private var usbInterceptorProcess: Process? = null

    private val USB_INTERCEPTOR_SCRIPT = """
        #!/system/bin/sh

        logcat -c
        logcat -T 0 OculusNotificationListenerService:D *:S | while read -r line; do
            case "${'$'}line" in
                *"Notification posted:"*"usb_connect_enable_mtp"*)
                    svc usb setFunctions mtp
                    am force-stop com.oculus.notification_proxy
                    ;;
            esac
        done
    """.trimIndent()

    private val OTA_BLOCKER_SCRIPT = """
        #!/system/bin/sh
        STATUS_FILE="/data/adb/eventhorizon/eh_ota_status.txt"
        
        touch "${'$'}STATUS_FILE"
        chmod 666 "${'$'}STATUS_FILE"
        echo "Listening..." > "${'$'}STATUS_FILE"
        
        while true; do
            # Redirecting stderr to stdout (2>&1) so the loop can read the INFO logs
            update_engine_client --follow 2>&1 | while read -r line; do
                case "${'$'}line" in
                    *"UPDATE_AVAILABLE"*|*"DOWNLOADING"*|*"VERIFYING"*|*"FINALIZING"*)
                        echo "Action detected: Canceling..." > "${'$'}STATUS_FILE"
                        update_engine_client --cancel
                        ;;
                    *"UPDATED_NEED_REBOOT"*)
                        echo "Reboot pending: Resetting..." > "${'$'}STATUS_FILE"
                        update_engine_client --reset_status
                        ;;
                    *"IDLE"*)
                        echo "Idle" > "${'$'}STATUS_FILE"
                        ;;
                esac
            done
            sleep 5
        done
    """.trimIndent()

    //private val OVR_LOADER_OVERRIDE_SCRIPT = """
        //#!/system/bin/sh
        //PATCHED="/data/local/tmp/eventhorizon/libovrplatformloader.so"
        //STATUS="/data/local/tmp/eventhorizon/ovr_loader_override_status.txt"
        //TARGETS="/data/local/tmp/eventhorizon/ovr_loader_override_targets.txt"
        //INTERVAL=10

        //mkdir -p /data/local/tmp/eventhorizon 2>/dev/null || true

        //while true; do
            //mounted=0
            //skipped=0
            //missing=0
            //failed=0

            //if [ ! -f "${'$'}PATCHED" ]; then
                //echo "$(date '+%F %T') missing patched loader at ${'$'}PATCHED" > "${'$'}STATUS"
                //: > "${'$'}TARGETS"
                //sleep "${'$'}INTERVAL"
                //continue
            //fi

            //: > "${'$'}TARGETS"
            //for target in $(find /data/app -path '*/lib/arm64/libovrplatformloader.so' -type f 2>/dev/null); do
                //if [ ! -f "${'$'}target" ]; then
                    //missing=$((missing + 1))
                    //continue
                //fi

                //if mount | grep -F " on ${'$'}target " >/dev/null 2>&1; then
                    //echo "${'$'}target" >> "${'$'}TARGETS"
                    //skipped=$((skipped + 1))
                    //continue
                //fi

                //if mount -o bind "${'$'}PATCHED" "${'$'}target" 2>/dev/null; then
                    //echo "${'$'}target" >> "${'$'}TARGETS"
                    //mounted=$((mounted + 1))
                //else
                    //failed=$((failed + 1))
                //fi
            //done

            //{
                //echo "time=$(date '+%F %T')"
                //echo "mounted=${'$'}mounted"
                //echo "already_mounted=${'$'}skipped"
                //echo "missing=${'$'}missing"
                //echo "failed=${'$'}failed"
                //echo "patched_sha256=$(sha256sum "${'$'}PATCHED" 2>/dev/null | awk '{print ${'$'}1}')"
            //} > "${'$'}STATUS"

            //sleep "${'$'}INTERVAL"
        //done
    //""".trimIndent()
    

    companion object {
        const val ACTION_START_RGB = "com.veygax.eventhorizon.START_RGB"
        const val ACTION_STOP_RGB = "com.veygax.eventhorizon.STOP_RGB"
        const val ACTION_START_CUSTOM_LED = "com.veygax.eventhorizon.START_CUSTOM_LED"
        const val ACTION_STOP_CUSTOM_LED = "com.veygax.eventhorizon.STOP_CUSTOM_LED"
        const val ACTION_START_POWER_LED = "com.veygax.eventhorizon.START_POWER_LED"
        const val ACTION_STOP_POWER_LED = "com.veygax.eventhorizon.STOP_POWER_LED"
        const val ACTION_START_MIN_FREQ = "com.veygax.eventhorizon.START_MIN_FREQ"
        const val ACTION_STOP_MIN_FREQ = "com.veygax.eventhorizon.STOP_MIN_FREQ"
        const val ACTION_APPLY_PASSTHROUGH_FIX = "ACTION_APPLY_PASSTHROUGH_FIX"
        const val ACTION_START_INTERCEPTOR = "com.veygax.eventhorizon.START_INTERCEPTOR"
        const val ACTION_STOP_INTERCEPTOR = "com.veygax.eventhorizon.STOP_INTERCEPTOR"
        
        const val ACTION_START_GPU_MIN_FREQ = "com.veygax.eventhorizon.START_GPU_MIN_FREQ"
        const val ACTION_STOP_GPU_MIN_FREQ = "com.veygax.eventhorizon.STOP_GPU_MIN_FREQ"
        const val ACTION_START_GPU_MAX_FREQ = "com.veygax.eventhorizon.START_GPU_MAX_FREQ"
        const val ACTION_STOP_GPU_MAX_FREQ = "com.veygax.eventhorizon.STOP_GPU_MAX_FREQ"
        
        const val ACTION_START_FRIDA = "com.veygax.eventhorizon.START_FRIDA"
        const val ACTION_STOP_FRIDA = "com.veygax.eventhorizon.STOP_FRIDA"

        const val ACTION_STOP_ALL = "com.veygax.eventhorizon.STOP_ALL"

        const val ACTION_START_USB_INTERCEPTOR = "com.veygax.eventhorizon.START_USB_INTERCEPTOR"
        const val ACTION_STOP_USB_INTERCEPTOR = "com.veygax.eventhorizon.STOP_USB_INTERCEPTOR"

        const val ACTION_START_OTA_BLOCKER = "com.veygax.eventhorizon.START_OTA_BLOCKER"
        const val ACTION_STOP_OTA_BLOCKER = "com.veygax.eventhorizon.STOP_OTA_BLOCKER"

        const val ACTION_START_OVR_LOADER_OVERRIDE = "com.veygax.eventhorizon.START_OVR_LOADER_OVERRIDE"
        const val ACTION_STOP_OVR_LOADER_OVERRIDE = "com.veygax.eventhorizon.STOP_OVR_LOADER_OVERRIDE"

        const val ACTION_START_WIRELESS_ADB = "com.veygax.eventhorizon.action.START_WIRELESS_ADB"
        const val ACTION_STOP_WIRELESS_ADB = "com.veygax.eventhorizon.action.STOP_WIRELESS_ADB"
        
        // This is the message the Activity will listen for.
        const val BROADCAST_TWEAKS_STOPPED = "com.veygax.eventhorizon.TWEAKS_STOPPED"

        private const val NOTIFICATION_CHANNEL_ID = "tweak_service_channel"
        private const val NOTIFICATION_ID = 2
    }
    
    // Internal states to track which root tweaks are running
    private var isRgbRunning: Boolean = false
    private var isCustomLedRunning: Boolean = false
    private var isPowerLedRunning: Boolean = false
    private var isMinFreqRunning: Boolean = false
    private var isInterceptorRunning: Boolean = false
    private var isUsbInterceptorRunning = false
    private var isOtaBlockerRunning = false
    //private var isOvrLoaderOverrideRunning = false
    private var isGpuMinFreqRunning: Boolean = false
    private var isGpuMaxFreqRunning: Boolean = false
    private var isFridaRunning: Boolean = false
    
    // Files for scripts
    private lateinit var rgbScriptFile: File
    private lateinit var customLedScriptFile: File
    private lateinit var powerLedScriptFile: File
    private lateinit var minFreqScriptFile: File
    private lateinit var interceptorScriptFile: File
    private lateinit var usbInterceptorScriptFile: File
    private lateinit var otaBlockerScriptFile: File
    //private lateinit var ovrLoaderOverrideScriptFile: File
    private lateinit var gpuMinFreqScriptFile: File
    private lateinit var gpuMaxFreqScriptFile: File

    override fun onCreate() {
        super.onCreate()
        rgbScriptFile = File(filesDir, "rgb_led.sh")
        customLedScriptFile = File(filesDir, "custom_led.sh")
        powerLedScriptFile = File(filesDir, "power_led.sh")
        minFreqScriptFile = File(filesDir, CpuUtils.SCRIPT_NAME)
        interceptorScriptFile = File(filesDir, "interceptor.sh")
        usbInterceptorScriptFile = File(filesDir, "usb_interceptor.sh")
        otaBlockerScriptFile = File(filesDir, "ota_blocker.sh")
        //ovrLoaderOverrideScriptFile = File(filesDir, "ovr_loader_override.sh")
        gpuMinFreqScriptFile = File(filesDir, GpuUtils.GPU_MIN_FREQ_SCRIPT_NAME)
        gpuMaxFreqScriptFile = File(filesDir, GpuUtils.GPU_MAX_FREQ_SCRIPT_NAME)

        // Initialize state robustly on service creation (to catch scripts running from boot)
        serviceScope.launch {
            checkRunningScripts()
        }
        
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    // Function to perform a robust check of all running processes
    private suspend fun checkRunningScripts() {
        // We use ps -ef here as the true source of external truth (i.e., on boot)
        val runningRgb = RootUtils.runAsRoot("ps -ef | grep rgb_led.sh | grep -v grep").trim().isNotEmpty()
        val runningCustom = RootUtils.runAsRoot("ps -ef | grep custom_led.sh | grep -v grep").trim().isNotEmpty()
        val runningCpu = RootUtils.runAsRoot("ps -ef | grep ${CpuUtils.SCRIPT_NAME} | grep -v grep").trim().isNotEmpty()
        val runningInterceptor = RootUtils.runAsRoot("ps -ef | grep interceptor.sh | grep -v grep").trim().isNotEmpty()
        val runningUsbInterceptor = RootUtils.runAsRoot("ps -ef | grep usb_interceptor.sh | grep -v grep").trim().isNotEmpty()
        val runningOtaBlocker = RootUtils.runAsRoot("ps -ef | grep ota_blocker.sh | grep -v grep").trim().isNotEmpty()
        //val runningOvrLoaderOverride = RootUtils.runAsRoot("ps -ef | grep ovr_loader_override.sh | grep -v grep").trim().isNotEmpty()
        val runningGpuMin = RootUtils.runAsRoot("ps -ef | grep ${GpuUtils.GPU_MIN_FREQ_SCRIPT_NAME} | grep -v grep").trim().isNotEmpty()
        val runningGpuMax = RootUtils.runAsRoot("ps -ef | grep ${GpuUtils.GPU_MAX_FREQ_SCRIPT_NAME} | grep -v grep").trim().isNotEmpty()
        val runningFrida = RootUtils.runAsRoot("ps -ef | grep frida-server | grep -v grep").trim().isNotEmpty()

        isRgbRunning = runningRgb
        isCustomLedRunning = runningCustom
        isMinFreqRunning = runningCpu
        isInterceptorRunning = runningInterceptor
        isUsbInterceptorRunning = runningUsbInterceptor
        isOtaBlockerRunning = runningOtaBlocker
        //isOvrLoaderOverrideRunning = runningOvrLoaderOverride
        isGpuMinFreqRunning = runningGpuMin
        isGpuMaxFreqRunning = runningGpuMax
        isFridaRunning = runningFrida
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: run {
            serviceScope.launch { updateServiceState() } // Check state if started with no explicit action
            return START_STICKY
        }

        serviceScope.launch {
            // Process the action. The start/stop functions determine the definitive state.
            when (action) {
                ACTION_START_RGB -> {
                    startRgbLed()
                    sharedPrefs.edit().putBoolean("rgb_led_is_running", true).apply()
                }
                ACTION_STOP_RGB -> {
                    stopRgbLed()
                    sharedPrefs.edit().putBoolean("rgb_led_is_running", false).apply()
                }
                ACTION_START_CUSTOM_LED -> {
                    val r = intent.getIntExtra("RED", 255)
                    val g = intent.getIntExtra("GREEN", 255)
                    val b = intent.getIntExtra("BLUE", 255)
                    startCustomLed(r, g, b)
                    sharedPrefs.edit().putBoolean("custom_led_is_running", true).apply()
                }
                ACTION_STOP_CUSTOM_LED -> {
                    stopCustomLed()
                    sharedPrefs.edit().putBoolean("custom_led_is_running", false).apply()
                }
                ACTION_START_POWER_LED -> {
                    startPowerLed()
                    sharedPrefs.edit().putBoolean("power_led_is_running", true).apply()
                }
                ACTION_STOP_POWER_LED -> {
                    stopPowerLed()
                    sharedPrefs.edit().putBoolean("power_led_is_running", false).apply()
                }
                ACTION_START_MIN_FREQ -> {
                    startMinFreq()
                    sharedPrefs.edit().putBoolean("min_freq_is_running", true).apply()
                }
                ACTION_STOP_MIN_FREQ -> {
                    stopMinFreq()
                    sharedPrefs.edit().putBoolean("min_freq_is_running", false).apply()
                }
                ACTION_START_GPU_MIN_FREQ -> {
                    startGpuMinFreq()
                    sharedPrefs.edit().putBoolean("gpu_min_freq_is_running", true).apply()
                }
                ACTION_STOP_GPU_MIN_FREQ -> {
                    stopGpuMinFreq()
                    sharedPrefs.edit().putBoolean("gpu_min_freq_is_running", false).apply()
                }
                ACTION_START_GPU_MAX_FREQ -> {
                    startGpuMaxFreq()
                    sharedPrefs.edit().putBoolean("gpu_max_freq_is_running", true).apply()
                }
                ACTION_STOP_GPU_MAX_FREQ -> {
                    stopGpuMaxFreq()
                    sharedPrefs.edit().putBoolean("gpu_max_freq_is_running", false).apply()
                }
                ACTION_START_FRIDA -> {
                    startFrida()
                    sharedPrefs.edit().putBoolean("frida_is_running", true).apply()
                }
                ACTION_STOP_FRIDA -> {
                    stopFrida()
                    sharedPrefs.edit().putBoolean("frida_is_running", false).apply()
                }
                ACTION_APPLY_PASSTHROUGH_FIX -> {
                    applyPassthroughFix()
                }
                ACTION_START_INTERCEPTOR -> startInterceptor() 
                ACTION_STOP_INTERCEPTOR -> stopInterceptor() 
                ACTION_START_USB_INTERCEPTOR -> startUsbInterceptor()
                ACTION_STOP_USB_INTERCEPTOR -> stopUsbInterceptor()
                ACTION_START_WIRELESS_ADB -> {
                    Log.i(TAG, "Starting Wireless ADB")
                    serviceScope.launch {
                    RootUtils.runAsRoot("setprop service.adb.tcp.port 5555; stop adbd; start adbd")
                    sharedPrefs.edit().putBoolean("wireless_adb_is_running", true).apply()
                    }
                }
                ACTION_START_OTA_BLOCKER -> startOtaBlocker()
                ACTION_STOP_OTA_BLOCKER -> stopOtaBlocker()
                ACTION_START_OVR_LOADER_OVERRIDE -> {
                    //startOvrLoaderOverride()
                    sharedPrefs.edit()
                        .putBoolean("ovr_loader_override_running", true)
                        .putBoolean("ovr_loader_override_on_boot", true)
                        .apply()
                }
                ACTION_STOP_OVR_LOADER_OVERRIDE -> {
                    //stopOvrLoaderOverride()
                    sharedPrefs.edit()
                        .putBoolean("ovr_loader_override_running", false)
                        .putBoolean("ovr_loader_override_on_boot", false)
                        .apply()
                }
                ACTION_STOP_WIRELESS_ADB -> {
                    Log.i(TAG, "Stopping Wireless ADB")
                    serviceScope.launch {
                        RootUtils.runAsRoot("setprop service.adb.tcp.port -1; stop adbd; start adbd")
                        sharedPrefs.edit().putBoolean("wireless_adb_is_running", false).apply()
                    }
                }
                ACTION_STOP_ALL -> stopAllTweaksAndService()
                else -> { /* Do nothing if action is unknown or null */ }
            }
            
            // Update the notification based on the new, certain internal state.
            updateServiceState()
        }

        return START_STICKY
    }

    private suspend fun startRgbLed() {
        stopAnyLed()
        // Always write the script to ensure it's present and up-to-date
            rgbScriptFile.writeText(TweakCommands.RGB_SCRIPT)
            RootUtils.runAsRoot("chmod +x ${rgbScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${rgbScriptFile.absolutePath} > /dev/null 2>&1 &")
        isRgbRunning = true
        isCustomLedRunning = false
        isPowerLedRunning = false
    }

    private suspend fun stopRgbLed() {
        RootUtils.runAsRoot("pkill -f rgb_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isRgbRunning = false
    }

    private suspend fun startCustomLed(r: Int, g: Int, b: Int) {
        stopAnyLed()
        val customColorScript = """
            #!/system/bin/sh
            RED_LED="/sys/class/leds/red/brightness"
            GREEN_LED="/sys/class/leds/green/brightness"
            BLUE_LED="/sys/class/leds/blue/brightness"
            while true; do
                echo $r > "${'$'}RED_LED"
                echo $g > "${'$'}GREEN_LED"
                echo $b > "${'$'}BLUE_LED"
                sleep 1
            done
        """.trimIndent()
        customLedScriptFile.writeText(customColorScript)
        RootUtils.runAsRoot("chmod +x ${customLedScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${customLedScriptFile.absolutePath} > /dev/null 2>&1 &")
        isCustomLedRunning = true
        isRgbRunning = false
        isPowerLedRunning = false
    }
    
    private suspend fun stopCustomLed() {
        RootUtils.runAsRoot("pkill -f custom_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isCustomLedRunning = false
    }

    private suspend fun startPowerLed() {
        stopAnyLed()
        powerLedScriptFile.writeText(TweakCommands.POWER_LED_SCRIPT) // This line creates the file
        RootUtils.runAsRoot("chmod +x ${powerLedScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${powerLedScriptFile.absolutePath} > /dev/null 2>&1 &")
        isPowerLedRunning = true
        isRgbRunning = false
        isCustomLedRunning = false
    }

    private suspend fun stopPowerLed() {
        RootUtils.runAsRoot("pkill -f power_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isPowerLedRunning = false
    }

    // This function likely already exists, make sure it's up to date
    private suspend fun stopAnyLed() {
        RootUtils.runAsRoot("pkill -f rgb_led.sh || true")
        RootUtils.runAsRoot("pkill -f custom_led.sh || true")
        RootUtils.runAsRoot("pkill -f power_led.sh || true")
        RootUtils.runAsRoot(TweakCommands.LEDS_OFF)
        isRgbRunning = false
        isCustomLedRunning = false
        isPowerLedRunning = false
    }

    private suspend fun startMinFreq() {
        RootUtils.runAsRoot("pkill -f ${CpuUtils.SCRIPT_NAME} || true")
        val scriptContent = CpuUtils.getMinFreqScript(CpuUtils.DEFAULT_LITTLE_FREQ, CpuUtils.DEFAULT_BIG_FREQ)
        minFreqScriptFile.writeText(scriptContent)
        RootUtils.runAsRoot("chmod +x ${minFreqScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${minFreqScriptFile.absolutePath} > /dev/null 2>&1 &")
        isMinFreqRunning = true
    }

    private suspend fun stopMinFreq() {
        RootUtils.runAsRoot("pkill -f ${CpuUtils.SCRIPT_NAME} || true")
        isMinFreqRunning = false
    }

    private suspend fun startGpuMinFreq() {
        RootUtils.runAsRoot("pkill -f ${GpuUtils.GPU_MIN_FREQ_SCRIPT_NAME} || true")
        val scriptContent = GpuUtils.getGpuMinFreqScript(GpuUtils.DEFAULT_GPU_MIN_FREQ)
        gpuMinFreqScriptFile.writeText(scriptContent)
        RootUtils.runAsRoot("chmod +x ${gpuMinFreqScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${gpuMinFreqScriptFile.absolutePath} > /dev/null 2>&1 &")
        isGpuMinFreqRunning = true
    }

    private suspend fun stopGpuMinFreq() {
        RootUtils.runAsRoot("pkill -f ${GpuUtils.GPU_MIN_FREQ_SCRIPT_NAME} || true")
        isGpuMinFreqRunning = false
    }

    private suspend fun startGpuMaxFreq() {
        RootUtils.runAsRoot("pkill -f ${GpuUtils.GPU_MAX_FREQ_SCRIPT_NAME} || true")
        val freqMhz = sharedPrefs.getString("gpu_max_freq_selection", GpuUtils.DEFAULT_GPU_MAX_FREQ) ?: GpuUtils.DEFAULT_GPU_MAX_FREQ
        val scriptContent = GpuUtils.getGpuMaxFreqScript(freqMhz)
        gpuMaxFreqScriptFile.writeText(scriptContent)
        RootUtils.runAsRoot("chmod +x ${gpuMaxFreqScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${gpuMaxFreqScriptFile.absolutePath} > /dev/null 2>&1 &")
        isGpuMaxFreqRunning = true
    }

    private suspend fun stopGpuMaxFreq() {
        RootUtils.runAsRoot("pkill -f ${GpuUtils.GPU_MAX_FREQ_SCRIPT_NAME} || true")
        isGpuMaxFreqRunning = false
        GpuUtils.setGpuMaxFreq("690000000")
    }

    private suspend fun startFrida() {
        RootUtils.runAsRoot("pkill -f frida-server || true")
        RootUtils.runAsRoot("chmod 755 /data/local/tmp/frida-server")
        isFridaRunning = true
        serviceScope.launch {
        RootUtils.runAsRoot("nohup /data/local/tmp/frida-server -D > /dev/null 2>&1 &")
        }
    }

    private suspend fun stopFrida() {
        RootUtils.runAsRoot("pkill -f frida-server >/dev/null 2>&1 || true")
        isFridaRunning = false
    }

    private suspend fun applyPassthroughFix() {
        if (RootUtils.isRootAvailable()) {
            val fixCommand = """
                am force-stop com.oculus.guardian
                am force-stop com.oculus.vrshell
                sleep 5
                am start -n com.veygax.eventhorizon/.ui.activities.MainActivity
            """.trimIndent()
    
            RootUtils.runAsRoot(fixCommand, useMountMaster = true)
            val prefs = getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
            val passthroughFixOnBoot = prefs.getBoolean("passthrough_fix_on_boot", false)
            val appToLaunch = prefs.getString("start_app_on_boot", null)
    
            if (passthroughFixOnBoot && !appToLaunch.isNullOrEmpty()) {
                val ehIntent = packageManager.getLaunchIntentForPackage("com.veygax.eventhorizon")
                ehIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (ehIntent != null) startActivity(ehIntent)
    
                try {
                    val appIntent = packageManager.getLaunchIntentForPackage(appToLaunch)
                    if (appIntent != null) {
                        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(appIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch $appToLaunch", e)
                }
            }
        }
    }
    
    private suspend fun startInterceptor() {
        RootUtils.runAsRoot("pkill -f interceptor.sh || true")
        interceptorScriptFile.writeText(INTERCEPTOR_SCRIPT)
        RootUtils.runAsRoot("chmod +x ${interceptorScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${interceptorScriptFile.absolutePath} > /dev/null 2>&1 &")
        isInterceptorRunning = true
    }

    private suspend fun stopInterceptor() {
        RootUtils.runAsRoot("pkill -f interceptor.sh || true")
        isInterceptorRunning = false
    }

    private suspend fun startUsbInterceptor() {
        RootUtils.runAsRoot("pkill -f usb_interceptor.sh || true")
        usbInterceptorScriptFile.writeText(USB_INTERCEPTOR_SCRIPT)
        RootUtils.runAsRoot("chmod +x ${usbInterceptorScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${usbInterceptorScriptFile.absolutePath} > /dev/null 2>&1 &")
        isUsbInterceptorRunning = true
        sharedPrefs.edit().putBoolean("usb_interceptor_running", true).apply()
        updateServiceState()
    }

    private suspend fun stopUsbInterceptor() {
        RootUtils.runAsRoot("pkill -f usb_interceptor.sh || true")
        isUsbInterceptorRunning = false
        sharedPrefs.edit().putBoolean("usb_interceptor_running", false).apply()
        updateServiceState()
    }

    private suspend fun startOtaBlocker() {
        RootUtils.runAsRoot("pkill -f ota_blocker.sh || true")
        otaBlockerScriptFile.writeText(OTA_BLOCKER_SCRIPT)
        RootUtils.runAsRoot("chmod +x ${otaBlockerScriptFile.absolutePath}")
        RootUtils.runAsRoot("nohup ${otaBlockerScriptFile.absolutePath} > /dev/null 2>&1 &")
        isOtaBlockerRunning = true
        updateServiceState()
    }

    private suspend fun stopOtaBlocker() {
        RootUtils.runAsRoot("pkill -f ota_blocker.sh || true")
        isOtaBlockerRunning = false
        updateServiceState()
    }

    /*
    private suspend fun startOvrLoaderOverride() {
        RootUtils.runAsRoot("pkill -f ovr_loader_override.sh || true", useMountMaster = true)

        val assetCopy = File(filesDir, "libovrplatformloader.so")
        assets.open("ovrplatform/libovrplatformloader.so").use { input ->
            assetCopy.outputStream().use { output -> input.copyTo(output) }
        }

        ovrLoaderOverrideScriptFile.writeText(OVR_LOADER_OVERRIDE_SCRIPT)

        val setupCommand = """
            mkdir -p /data/local/tmp/eventhorizon
            cp ${assetCopy.absolutePath} /data/local/tmp/eventhorizon/libovrplatformloader.so
            chmod 644 /data/local/tmp/eventhorizon/libovrplatformloader.so 2>/dev/null || true
            chmod +x ${ovrLoaderOverrideScriptFile.absolutePath}
            nohup ${ovrLoaderOverrideScriptFile.absolutePath} >/dev/null 2>&1 &
        """.trimIndent()

        RootUtils.runAsRoot(setupCommand, useMountMaster = true)
        isOvrLoaderOverrideRunning = true
        updateServiceState()
    }

    private suspend fun stopOvrLoaderOverride() {
        val stopCommand = """
            pkill -f ovr_loader_override.sh || true
            mount | while read -r line; do
                case "${'$'}line" in
                    *" on /data/app/"*"lib/arm64/libovrplatformloader.so "*) 
                        target="${'$'}{line#* on }"
                        target="${'$'}{target% type *}"
                        umount -l "${'$'}target" 2>/dev/null || true
                        ;;
                esac
            done
            rm -f /data/local/tmp/eventhorizon/ovr_loader_override_targets.txt
        """.trimIndent()

        RootUtils.runAsRoot(stopCommand, useMountMaster = true)
        isOvrLoaderOverrideRunning = false
        updateServiceState()
    }*/

    private fun isAnyTweakRunning(): Boolean {
        return isRgbRunning || isCustomLedRunning || isPowerLedRunning || isMinFreqRunning || isInterceptorRunning || isUsbInterceptorRunning || isOtaBlockerRunning /*|| isOvrLoaderOverrideRunning ||*/ isGpuMinFreqRunning || isGpuMaxFreqRunning || isFridaRunning
    }
    
    private suspend fun stopAllTweaksAndService() {
        // Run all stop commands
        stopAnyLed()
        stopMinFreq()
        stopGpuMinFreq()
        stopGpuMaxFreq()
        stopInterceptor()
        stopUsbInterceptor()
        stopOtaBlocker()
        //stopOvrLoaderOverride()
        stopFrida()

        // Broadcast that all tweaks have been stopped before the service dies.
        // This allows the UI in TweaksActivity to update instantly.
        val intent = Intent(BROADCAST_TWEAKS_STOPPED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // This stops the service, removing the notification.
        stopSelf()
    }
    
    // Checks the state and stops the service if nothing is active
    private fun stopSelfIfInactive() {
        if (!isAnyTweakRunning()) {
            stopSelf()
        }
    }

    private fun updateServiceState() {
        // Check if anything is running before updating the UI
        if (!isAnyTweakRunning()) {
            stopSelfIfInactive() // Will stop service if no tweaks are active
            return
        }

        // Re-post the notification to update its content based on what's running
        val notification = buildNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EventHorizon Background Tweaks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs persistent root tweaks like LED, CPU frequency locks, and app interception."
            }
            val manager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        
        val contentText = "Persistent tweaks are being managed."
        
        val stopIntent = Intent(this, TweakService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Increase Notification Persistence
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("EventHorizon Tweaks Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_power_off) 
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop All Tweaks", stopPendingIntent)
            .setOngoing(true) // Makes it non-swipeable/persistent
        
        // Use the highest available priority settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            notificationBuilder.setCategory(Notification.CATEGORY_SERVICE)
        }
        
        // Correctly applying FLAG_NO_CLEAR to the built Notification object for persistence
        return notificationBuilder.build().apply {
            @Suppress("DEPRECATION")
            flags = flags or Notification.FLAG_NO_CLEAR
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()

        sharedPrefs.edit().apply {
            putBoolean("rgb_led_is_running", false)
            putBoolean("custom_led_is_running", false)
            putBoolean("power_led_is_running", false)
            putBoolean("intercept_startup_apps", false)
            putBoolean("usb_interceptor_running", false)
            putBoolean("ota_blocker_running", false)
            putBoolean("ovr_loader_override_running", false)
            putBoolean("min_freq_is_running", false)
            putBoolean("gpu_min_freq_is_running", false)
            putBoolean("gpu_max_freq_is_running", false)
            putBoolean("frida_is_running", false)
            apply()
        }

        runBlocking(Dispatchers.IO) {
            RootUtils.runAsRoot("pkill -f rgb_led.sh || true")
            RootUtils.runAsRoot("pkill -f custom_led.sh || true")
            RootUtils.runAsRoot("pkill -f power_led.sh || true")
            RootUtils.runAsRoot("pkill -f ${CpuUtils.SCRIPT_NAME} || true")
            RootUtils.runAsRoot("pkill -f ${GpuUtils.GPU_MIN_FREQ_SCRIPT_NAME} || true")
            RootUtils.runAsRoot("pkill -f ${GpuUtils.GPU_MAX_FREQ_SCRIPT_NAME} || true")
            RootUtils.runAsRoot("pkill -f interceptor.sh || true")
            RootUtils.runAsRoot("pkill -f usb_interceptor.sh || true")
            RootUtils.runAsRoot("pkill -f ota_blocker.sh || true")
            RootUtils.runAsRoot("pkill -f ovr_loader_override.sh || true", useMountMaster = true)
            RootUtils.runAsRoot("""
                mount | while read -r line; do
                    case "${'$'}line" in
                        *" on /data/app/"*"lib/arm64/libovrplatformloader.so "*) 
                            target="${'$'}{line#* on }"
                            target="${'$'}{target% type *}"
                            umount -l "${'$'}target" 2>/dev/null || true
                            ;;
                    esac
                done
            """.trimIndent(), useMountMaster = true)
            RootUtils.runAsRoot("pkill -f frida-server || true")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
