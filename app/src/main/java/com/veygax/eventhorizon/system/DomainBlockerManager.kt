package com.veygax.eventhorizon.system

import android.content.Context
import android.util.Log
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DomainBlockerManager {

    val categories = mapOf(
        "Telemetry / Analytics" to listOf(
            "graph.facebook.com", "analytics.facebook.com", "connect.facebook.net",
            "edge-mqtt.facebook.com", "mqtt-mini.facebook.com", "logging.facebook.com",
            "b-graph.facebook.com", "upload.facebook.com", "rupload.facebook.com",
            "realitylabs-graph.facebook.com", "beacons.gvt2.com", "beacons.gcp.gvt2.com",
            "beacons5.gvt2.com", "shortwave.facebook.com"
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
            "graph.oculus.com", "store.oculus.com", "securecdn.oculus.com",
            "scontent.oculuscdn.com", "cdn.oculus.com", "securecdn-ams4-1.oculus.com",
            "static.xx.fbcdn.net", "ugc.oculuscdn.com", "cdn.fbsbx.com",
            "scontent-ams4-1.oculuscdn.com", "scontent-ams2-1.oculuscdn.com", "scontent-ams4-1.xx.fbcdn.net",
            "scontent-ams2-1.xx.fbcdn.net", "scontent.xx.fbcdn.net", "scontent-ord5-2.xx.fbcdn.net",
            "scontent-ord5-1.xx.fbcdn.net", "scontent-man2-1.xx.fbcdn.net", "scontent-lhr6-2.xx.fbcdn.net",
            "scontent-lga3-3.xx.fbcdn.net", "scontent-lax3-1.xx.fbcdn.net", "scontent-dfw5-3.xx.fbcdn.net",
            "scontent-det1-1.xx.fbcdn.net", "scontent-atl3-1.xx.fbcdn.net", "scontent.fluk1-1.fna.fbcdn.net",
            "scontent.fcps3-1.fna.fbcdn.net", "scontent.fagc3-2.fna.fbcdn.net", "scontent.fagc3-1.fna.fbcdn.net",
            "graph.facebook.com", "analytics.facebook.com", "connect.facebook.net",
            "edge-mqtt.facebook.com", "mqtt-mini.facebook.com", "logging.facebook.com",
            "b-graph.facebook.com", "upload.facebook.com", "rupload.facebook.com",
            "realitylabs-graph.facebook.com", "beacons.gvt2.com", "beacons.gcp.gvt2.com",
            "beacons5.gvt2.com", "shortwave.facebook.com"
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

    suspend fun checkIsEnabled(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
        val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)

        if (isUnlocked) {
            val folderExists = RootUtils.runAsRoot(
                "[ -d /data/adb/modules/eventhorizon-hosts ] && echo 1 || echo 0",
                useMountMaster = true
            ).trim() == "1"

            if (!folderExists) return@withContext false

            val disableExists = RootUtils.runAsRoot(
                "[ -f /data/adb/modules/eventhorizon-hosts/disable ] && echo 1 || echo 0",
                useMountMaster = true
            ).trim() == "1"

            return@withContext !disableExists
        } else {
            val check = RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true)
            return@withContext check.isNotBlank()
        }
    }

    private fun buildHostsContent(context: Context): String {
        val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.append("127.0.0.1       localhost\n")
        sb.append("::1             ip6-localhost\n\n")
        sb.append("# ----- Blocked domains by EventHorizon -----\n")

        for ((categoryName, domains) in categories) {
            if (sharedPrefs.getBoolean(getPrefKey(categoryName), false)) {
                sb.append("\n# $categoryName\n")
                for (domain in domains) {
                    sb.append("0.0.0.0 $domain\n")
                }
            }
        }
        return sb.toString()
    }

    suspend fun generateAndApplyHosts(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
        val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)

        return@withContext if (isUnlocked) {
            generateAndApplyHostsMagisk(context)
        } else {
            generateAndApplyHostsBindMount(context)
        }
    }

    private suspend fun generateAndApplyHostsMagisk(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val installedModDir = "/data/adb/modules/eventhorizon-hosts"

                val cacheModuleDir = File(context.cacheDir, "eh_module").also {
                    it.deleteRecursively()
                    it.mkdirs()
                }
                copyAssetFolderToCache(context, assetPath = "module", destDir = cacheModuleDir)

                val hostsDir = File(cacheModuleDir, "system/etc").also { it.mkdirs() }
                File(hostsDir, "hosts").apply {
                    writeText(buildHostsContent(context))
                    setReadable(true, false)
                }

                val zipFile = File(context.cacheDir, "hosts.zip").also { if (it.exists()) it.delete() }
                zipDirectory(sourceDir = cacheModuleDir, zipFile = zipFile)

                val zipDest = "/data/adb/eventhorizon/hosts.zip"
                RootUtils.runAsRoot(
                    """
                    mkdir -p /data/adb/eventhorizon
                    cp ${zipFile.absolutePath} $zipDest
                    magisk --install-module $zipDest
                    rm -f $installedModDir/disable
                    """.trimIndent(),
                    useMountMaster = true
                )

                cacheModuleDir.deleteRecursively()
                zipFile.delete()

                val folderExists = RootUtils.runAsRoot(
                    "[ -d $installedModDir ] && echo 1 || echo 0",
                    useMountMaster = true
                ).trim() == "1"

                val disableExists = RootUtils.runAsRoot(
                    "[ -f $installedModDir/disable ] && echo 1 || echo 0",
                    useMountMaster = true
                ).trim() == "1"

                folderExists && !disableExists
            } catch (e: Exception) {
                Log.e("DomainBlockerManager", "Error applying Magisk hosts module", e)
                false
            }
        }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                val entryName = file.absolutePath
                    .removePrefix(sourceDir.absolutePath)
                    .removePrefix("/")
                if (entryName.isEmpty()) return@forEach
                val entry = ZipEntry(if (file.isDirectory) "$entryName/" else entryName)
                zos.putNextEntry(entry)
                if (file.isFile) {
                    file.inputStream().use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
    }

    private fun copyAssetFolderToCache(context: Context, assetPath: String, destDir: File) {
        val assetManager = context.assets
        val entries = try { assetManager.list(assetPath) } catch (e: Exception) { null }

        if (entries.isNullOrEmpty()) {
            try {
                assetManager.open(assetPath).use { input ->
                    destDir.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.e("DomainBlockerManager", "Failed to copy asset to cache: $assetPath", e)
            }
        } else {
            destDir.mkdirs()
            for (entry in entries) {
                copyAssetFolderToCache(context, assetPath = "$assetPath/$entry", destDir = File(destDir, entry))
            }
        }
    }

    private suspend fun generateAndApplyHostsBindMount(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "hosts_temp")
            tempFile.writeText(buildHostsContent(context))

            val moduleDir = "/data/adb/eventhorizon"
            val finalHostsPath = "$moduleDir/hosts"

            val dnsFlushCommand = """
                settings put global airplane_mode_on 1
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
                sleep 4
                settings put global airplane_mode_on 0
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
            """.trimIndent()

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
            Log.e("DomainBlockerManager", "Error applying hosts file (bind-mount)", e)
            return@withContext false
        }
    }

    suspend fun disableRootBlocker(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE)
        val isUnlocked = sharedPrefs.getBoolean("is_unlocked_bootloader", false)

        return@withContext if (isUnlocked) {
            disableRootBlockerMagisk()
        } else {
            disableRootBlockerBindMount(context)
        }
    }

    private suspend fun disableRootBlockerMagisk(): Boolean = withContext(Dispatchers.IO) {
        try {
            val installedModDir = "/data/adb/modules/eventhorizon-hosts"
            RootUtils.runAsRoot(
                """
                touch $installedModDir/disable
                rm -f $installedModDir/update
                """.trimIndent(),
                useMountMaster = true
            )
            val disableExists = RootUtils.runAsRoot(
                "[ -f $installedModDir/disable ] && echo 1 || echo 0",
                useMountMaster = true
            ).trim() == "1"
            disableExists
        } catch (e: Exception) {
            Log.e("DomainBlockerManager", "Error disabling Magisk hosts module", e)
            false
        }
    }

    private suspend fun disableRootBlockerBindMount(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dnsFlushCommand = """
            settings put global airplane_mode_on 1
            am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
            sleep 4
            settings put global airplane_mode_on 0
            am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
        """.trimIndent()

        val commands = """
            umount -l /system/etc/hosts
            $dnsFlushCommand
        """.trimIndent()

        RootUtils.runAsRoot(commands, useMountMaster = true)
        val check = RootUtils.runAsRoot("mount | grep /system/etc/hosts", useMountMaster = true)
        return@withContext check.isBlank()
    }
}