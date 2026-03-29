package com.veygax.eventhorizon.system

import android.content.Context
import android.util.Log
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DomainBlockerManager {

    val categories = mapOf(
        "Telemetry / Analytics" to listOf(
            "graph.oculus.com", "graph.facebook.com", "analytics.facebook.com", 
            "connect.facebook.net", "edge-mqtt.facebook.com", "mqtt-mini.facebook.com", 
            "logging.facebook.com", "b-graph.facebook.com", "upload.facebook.com", 
            "rupload.facebook.com", "realitylabs-graph.facebook.com", "beacons.gvt2.com", 
            "beacons.gcp.gvt2.com", "beacons5.gvt2.com", "shortwave.facebook.com"
        ),
        "Presence / Social" to listOf(
            "presence.oculus.com", "social.oculus.com", "api.facebook.com", 
            "portal.fb.com", "horizon.meta.com"
        ),
        "Auth / Account" to listOf(
            "secure.oculus.com", "auth.oculus.com", "api.oculus.com", 
            "graph.facebook.net", "facebook.com", "www.facebook.com", 
            "web.facebook.com", "oculus.com", "www.oculus.com", 
            "meta.graph.meta.com", "meta.graph.facebook.com", "gateway.facebook.com", 
            "graph.facebook-hardware.com", "graph-fallback.oculus.com", "www.meta.com",
            "messenger.com", "www.messenger.com", "msngr.com", "www.msngr.com", 
            "m.me", "www.m.me", "familycenter.messenger.com"
        ),
        "Store / Entitlements" to listOf(
            "store.oculus.com", "securecdn.oculus.com", "scontent.oculuscdn.com", 
            "cdn.oculus.com", "securecdn-ams4-1.oculus.com", "static.xx.fbcdn.net", 
            "ugc.oculuscdn.com", "cdn.fbsbx.com", "scontent-ams4-1.oculuscdn.com", 
            "scontent-ams2-1.oculuscdn.com", "scontent-ams4-1.xx.fbcdn.net", 
            "scontent-ams2-1.xx.fbcdn.net", "scontent.xx.fbcdn.net", 
            "scontent-ord5-2.xx.fbcdn.net", "scontent-ord5-1.xx.fbcdn.net", 
            "scontent-man2-1.xx.fbcdn.net", "scontent-lhr6-2.xx.fbcdn.net", 
            "scontent-lga3-3.xx.fbcdn.net", "scontent-lax3-1.xx.fbcdn.net", 
            "scontent-dfw5-3.xx.fbcdn.net", "scontent-det1-1.xx.fbcdn.net", 
            "scontent-atl3-1.xx.fbcdn.net", "scontent.fluk1-1.fna.fbcdn.net", 
            "scontent.fcps3-1.fna.fbcdn.net", "scontent.fagc3-2.fna.fbcdn.net", 
            "scontent.fagc3-1.fna.fbcdn.net"
        ),
        "Updates / Firmware" to listOf(
            "software.oculus.com", "update.oculus.com"
        ),
        "Media / Streaming" to listOf(
            "video.oculus.com", "media.oculuscdn.com", "lookaside.facebook.com", 
            "lookaside.fbsbx.com"
        ),
        "Misc Backend / Services" to listOf(
            "star.c10r.facebook.com", "b-www.facebook.com"
        )
    )

    // Helper to get SharedPreferences key for a category
    fun getPrefKey(categoryName: String): String {
        return "block_category_${categoryName.replace(" / ", "_").replace(" ", "_").lowercase()}"
    }

    suspend fun generateAndApplyHosts(
        context: Context, 
        isBootTrigger: Boolean = false, 
        preventSoftReboot: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
        val isBlockerEnabled = sharedPrefs.getBoolean("root_blocker_is_running", false)
        val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)

        val sb = java.lang.StringBuilder()
        sb.append("127.0.0.1       localhost\n")
        sb.append("::1             ip6-localhost\n\n")

        if (isBlockerEnabled) {
            sb.append("# ----- Blocked domains by EventHorizon -----\n")
            for ((categoryName, domains) in categories) {
                if (sharedPrefs.getBoolean(getPrefKey(categoryName), false)) {
                    sb.append("\n# $categoryName\n")
                    for (domain in domains) {
                        sb.append("0.0.0.0 $domain\n")
                    }
                }
            }
        }

        try {
            val tempFile = File(context.cacheDir, "hosts_temp")
            tempFile.writeText(sb.toString())

            val moduleDir = "/data/adb/eventhorizon"
            val finalHostsPath = "$moduleDir/hosts"

            val dnsFlushCommand = if (isUnlocked && !preventSoftReboot) {
                if (isBootTrigger) {
                    """
                    if [ "$(getprop sys.eh.boot_flushed)" != "1" ]; then
                        setprop sys.eh.boot_flushed 1
                        stop; sleep 3; start
                    fi
                    """.trimIndent()
                } else {
                    """
                    setprop sys.eh.manual_toggle 1
                    stop; sleep 3; start
                    """.trimIndent()
                }
            } else if (!isUnlocked && !preventSoftReboot) {
                """
                settings put global airplane_mode_on 1
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
                sleep 4
                settings put global airplane_mode_on 0
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
                """.trimIndent()
            } else {
                ""
            }

            val commands = """
                mkdir -p $moduleDir
                mv ${tempFile.absolutePath} $finalHostsPath
                chmod 644 $finalHostsPath
                umount -l /system/etc/hosts
                mount -o bind $finalHostsPath /system/etc/hosts
                $dnsFlushCommand
            """.trimIndent()

            RootUtils.runAsRoot(commands, useMountMaster = true)
            
            val check = RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true)
            return@withContext check.isNotBlank()

        } catch (e: Exception) {
            Log.e("DomainBlockerManager", "Error applying hosts file", e)
            return@withContext false
        }
    }

    suspend fun disableRootBlocker(
        context: Context, 
        preventSoftReboot: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
        val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)
        
        val dnsFlushCommand = if (isUnlocked && !preventSoftReboot) {
            """
            setprop sys.eh.manual_toggle 1
            stop; sleep 3; start
            """.trimIndent()
        } else if (!isUnlocked && !preventSoftReboot) {
            """
            settings put global airplane_mode_on 1
            am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
            sleep 4
            settings put global airplane_mode_on 0
            am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
            """.trimIndent()
        } else {
            ""
        }

        val commands = """
            umount -l /system/etc/hosts
            $dnsFlushCommand
        """.trimIndent()

        RootUtils.runAsRoot(commands, useMountMaster = true)
        val check = RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true)
        return@withContext check.isBlank() 
    }
}