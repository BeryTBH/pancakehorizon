package com.veygax.eventhorizon.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Environment
import androidx.core.content.FileProvider
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val TIMEOUT_MS = 15000 // 15 seconds

    data class ReleaseInfo(val version: String, val changelog: String, val downloadUrl: String)

    /**
     * Checks GitHub for the latest release and compares it with the current app version.
     * @return ReleaseInfo if a newer version is found, otherwise null.
     */
    suspend fun checkForUpdate(context: Context, owner: String, repo: String): ReleaseInfo? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

                val url = URL(apiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                val jsonText = connection.inputStream.bufferedReader().readText()

                val json = JSONObject(jsonText)

                val latestVersion = json.getString("tag_name")
                val changelog = json.getString("body")
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null

                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (apkUrl == null) {
                    Log.e("UpdateManager", "No APK found in the latest release.")
                    return@withContext null
                }

                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = packageInfo.versionName
                if (currentVersion == null) {
                    Log.e("UpdateManager", "Could not get current app version.")
                    return@withContext null
                }


                if (isNewerVersion(latestVersion, currentVersion)) {
                    return@withContext ReleaseInfo(latestVersion, changelog, apkUrl)
                } else {
                    return@withContext null
                }

            } catch (e: Exception) {
                Log.e("UpdateManager", "Failed to check for updates: ${e.message}")
                e.printStackTrace()
                return@withContext null
            } finally {
                connection?.disconnect()
            }
        }
    }

	/**
	 * Checks both dev and stable channels.
	 * If on dev, it compares stable too, and returns the higher version.
	 */
	suspend fun checkDevAndStableUpdates(
	    context: Context,
	    owner: String,
	    devRepo: String,
	    stableRepo: String
	): ReleaseInfo? {
	    val devRelease = checkForUpdate(context, owner, devRepo)
	    val stableRelease = checkForUpdate(context, owner, stableRepo)

	    return when {
	        devRelease == null && stableRelease == null -> null
	        devRelease == null -> stableRelease
	        stableRelease == null -> devRelease
	        else -> {
	            // Both are valid, compare by version number
	            if (isNewerVersion(stableRelease.version, devRelease.version)) {
	                // Stable has a higher version than dev
	                stableRelease
	            } else {
	                devRelease
	            }
	        }
	    }
	}

	private fun sanitizeVersion(version: String): String {
		return version
	    	.removePrefix("v")
	    	.substringBefore(".r") // Fixes Shizuku
	    	.substringBefore("_B") // Fixes MiXplorer
	    	.substringBefore("-")  // Fixes Beta tags
	}
	
	fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
	    val cleanLatest = sanitizeVersion(latestVersion).split(".").mapNotNull { it.toIntOrNull() }
	    val cleanCurrent = sanitizeVersion(currentVersion).split(".").mapNotNull { it.toIntOrNull() }
	
	    if (cleanLatest.isEmpty() || cleanCurrent.isEmpty()) return false
	
	    val maxLen = maxOf(cleanLatest.size, cleanCurrent.size)
	    for (i in 0 until maxLen) {
	        val latestPart = cleanLatest.getOrElse(i) { 0 }
	        val currentPart = cleanCurrent.getOrElse(i) { 0 }
	        if (latestPart > currentPart) return true
	        if (latestPart < currentPart) return false
	    }
	    return false 
	}

    /**
     * Downloads an APK from a URL and installs it using root.
     */
	suspend fun downloadAndInstallUpdate(
		context: Context,
		url: String,
		onProgress: (Float) -> Unit,
		onStatusUpdate: (String) -> Unit
	) {
		withContext(Dispatchers.IO) {
			var connection: HttpURLConnection? = null
			val apkFile = File(context.cacheDir, "update.apk")
	
			try {
				onStatusUpdate("Starting download…")
				onProgress(0f)
	
				connection = URL(url).openConnection() as HttpURLConnection
				connection.connectTimeout = TIMEOUT_MS
				connection.readTimeout = TIMEOUT_MS
				connection.connect()
	
				val fileLength = connection.contentLength
	
				connection.inputStream.use { input ->
					FileOutputStream(apkFile).use { output ->
						val data = ByteArray(4096)
						var total: Long = 0
						var count: Int
						while (input.read(data).also { count = it } != -1) {
							total += count
							if (fileLength > 0) {
								val progress = total.toFloat() / fileLength
								withContext(Dispatchers.Main) { onProgress(progress) }
							}
							output.write(data, 0, count)
						}
					}
				}
	
				onStatusUpdate("Download complete. Installing…")
				onProgress(1f)
	
				if (RootUtils.isRootAvailable()) {
					// Silent install for rooted devices
					val result = RootUtils.runAsRoot("pm install -r \"${apkFile.absolutePath}\"")
					apkFile.delete()
					if (result.trim() != "Success") {
						onStatusUpdate("Installation failed (root). See logs for details.")
						Log.e("UpdateManager", "Install failed: $result")
					}
					} else {
					    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
					    if (!downloadsDir.exists()) downloadsDir.mkdirs()
					    val outFile = File(downloadsDir, "eventhorizon-update.apk")

					    if (outFile.exists()) outFile.delete()
					    apkFile.copyTo(outFile, overwrite = true)

					    // Wrap it in FileProvider (like MiXplorer’s content://)
					    val apkUri = FileProvider.getUriForFile(
					        context,
 					       "${context.packageName}.provider",
					        outFile
					    )

					    val installIntent = Intent(Intent.ACTION_VIEW).apply {
					        setDataAndType(apkUri, "application/vnd.android.package-archive")
					        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
					        // Force system package installer instead of VR Resolver
					        setClassName(
					            "com.android.packageinstaller",
					            "com.android.packageinstaller.InstallStart"
					        )
					    }

					    context.startActivity(installIntent)
					    onStatusUpdate("Prompting system installer…")
					}
	
			} catch (e: Exception) {
				onStatusUpdate("An error occurred: ${e.message}")
				Log.e("UpdateManager", "Download/Install error", e)
			} finally {
				connection?.disconnect()
			}
		}
	}
}