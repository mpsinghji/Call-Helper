package com.callhandler.service.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles checking GitHub Releases for newer APKs, downloading the update asset,
 * and launching the package installer via FileProvider.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val GITHUB_REPO_OWNER = "mpsinghji"
    private const val GITHUB_REPO_NAME = "Call-Helper"
    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val releaseName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val assetName: String,
        val assetSize: Long
    )

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Error(val message: String, val throwable: Throwable? = null) : CheckResult()
    }

    /**
     * Queries GitHub Releases API for the latest published release.
     * Compares release tag with the app's current version name.
     */
    suspend fun checkForUpdate(currentVersionName: String): CheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_LATEST_RELEASE_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CallHelper-Android-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext CheckResult.Error("GitHub API returned HTTP $responseCode")
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)

            val tagName = json.optString("tag_name", "").trim()
            val releaseName = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "")

            val cleanRemoteVersion = cleanVersion(tagName)
            val cleanCurrentVersion = cleanVersion(currentVersionName)

            if (!isNewerVersion(cleanRemoteVersion, cleanCurrentVersion)) {
                return@withContext CheckResult.UpToDate(currentVersionName)
            }

            // Find the APK asset in the release
            val assetsArray = json.optJSONArray("assets")
            var apkDownloadUrl = ""
            var apkAssetName = ""
            var apkSize = 0L

            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkAssetName = name
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            // Fallback download URL if assets list was empty
            if (apkDownloadUrl.isEmpty()) {
                apkAssetName = "CallHelper-$tagName.apk"
                apkDownloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$tagName/$apkAssetName"
            }

            val info = UpdateInfo(
                tagName = tagName,
                versionName = cleanRemoteVersion,
                releaseName = releaseName,
                releaseNotes = releaseNotes,
                downloadUrl = apkDownloadUrl,
                assetName = apkAssetName,
                assetSize = apkSize
            )

            CheckResult.UpdateAvailable(info)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update: ${e.message}", e)
            CheckResult.Error("Network error checking update: ${e.message}", e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads the APK file to app cache/updates directory.
     * Handles HTTP 301/302/307 redirects to AWS S3/GitHub CDN.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        targetFileName: String,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updatesDir, targetFileName)
            val tempFile = File(updatesDir, "$targetFileName.download")

            if (tempFile.exists()) tempFile.delete()

            val connection = openConnectionWithRedirects(downloadUrl)
            val contentLength = connection.contentLengthLong

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesReadTotal = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                        val percent = if (contentLength > 0) {
                            ((bytesReadTotal * 100) / contentLength).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(percent, bytesReadTotal, contentLength)
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                throw IOException("Failed to rename temporary download file to $targetFileName")
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download APK: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Launches system PackageInstaller for the downloaded APK using FileProvider.
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(activity, "Update file not found", Toast.LENGTH_SHORT).show()
            return
        }

        // On Android 8.0+ (API 26+), verify install unknown packages permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    activity,
                    "Please allow installing updates from this app",
                    Toast.LENGTH_LONG
                ).show()
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(settingsIntent)
                return
            }
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            Toast.makeText(activity, "Error launching installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Follows HTTP redirects up to maxRedirects to support AWS S3 / CDN redirections.
     */
    private fun openConnectionWithRedirects(initialUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects < maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CallHelper-Android-App")
                setRequestProperty("Accept", "*/*")
            }

            val status = conn.responseCode
            if (status in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val newLocation = conn.getHeaderField("Location")
                conn.disconnect()
                if (!newLocation.isNullOrEmpty()) {
                    currentUrl = newLocation
                    redirects++
                    continue
                }
            }
            return conn
        }
        throw IOException("Too many redirects: $maxRedirects")
    }

    /**
     * Compares remote semantic version against current local version.
     * Returns true if remote is strictly newer.
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = remoteVersion.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val rem = remoteParts.getOrElse(i) { 0 }
            val cur = currentParts.getOrElse(i) { 0 }
            if (rem > cur) return true
            if (rem < cur) return false
        }
        return false
    }

    private fun cleanVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }
}

