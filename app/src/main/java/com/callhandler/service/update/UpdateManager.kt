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
import com.callhandler.service.debug.DebugLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Handles checking GitHub Releases for newer APKs, downloading the update asset,
 * and launching the package installer via FileProvider.
 *
 * Implements resilient checking with:
 * - ETag caching (HTTP 304 Not Modified)
 * - Fallback to GitHub web redirect endpoint on API rate limiting (HTTP 403 / 429)
 * - Filtering of draft and pre-release builds
 * - Categorized error reporting
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    const val GITHUB_REPO_OWNER = "mpsinghji"
    const val GITHUB_REPO_NAME = "Call-Helper"
    const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    const val GITHUB_FALLBACK_RELEASE_URL =
        "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    fun interface HttpConnectionFactory {
        fun openConnection(url: URL): HttpURLConnection
    }

    /**
     * Pluggable connection factory for unit testing without network dependencies.
     */
    internal var connectionFactory: HttpConnectionFactory = HttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    }

    /** Cached ETag header from the latest successful release check. */
    @Volatile
    var cachedETag: String? = null
        internal set

    /** Cached release tag name corresponding to [cachedETag]. */
    @Volatile
    var cachedLatestTag: String? = null
        internal set

    fun resetCache() {
        cachedETag = null
        cachedLatestTag = null
    }

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,
        val releaseName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val assetName: String,
        val assetSize: Long
    )

    enum class ErrorCategory {
        NO_NETWORK,
        TIMEOUT,
        RATE_LIMITED,
        NOT_FOUND,
        SERVER_ERROR,
        MALFORMED_DATA,
        UNKNOWN
    }

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Error(
            val message: String,
            val category: ErrorCategory = ErrorCategory.UNKNOWN,
            val userMessage: String = message,
            val throwable: Throwable? = null
        ) : CheckResult()
    }

    /**
     * Determines whether an update check should execute given the user preference
     * and trigger context.
     */
    fun shouldCheckForUpdates(checkUpdatesEnabled: Boolean, manualTrigger: Boolean): Boolean {
        return manualTrigger || checkUpdatesEnabled
    }

    /**
     * Queries GitHub Releases for the latest published release.
     * Uses the REST API as the primary source with ETag support, and falls back
     * to the GitHub web redirect endpoint if the API rate limit is exceeded.
     */
    suspend fun checkForUpdate(currentVersionName: String): CheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_LATEST_RELEASE_URL)
            connection = connectionFactory.openConnection(url).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CallHelper-Android-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                cachedETag?.let { etag ->
                    if (etag.isNotBlank()) {
                        setRequestProperty("If-None-Match", etag)
                    }
                }
            }

            val responseCode = connection.responseCode

            // 1. Handle 304 Not Modified
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                DebugLogStore.log(TAG, "GitHub API returned 304 Not Modified (using cached release state)")
                val cachedTag = cachedLatestTag
                if (cachedTag != null && isNewerVersion(cleanVersion(cachedTag), cleanVersion(currentVersionName))) {
                    val assetName = "CallHelper-$cachedTag.apk"
                    val downloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$cachedTag/$assetName"
                    return@withContext CheckResult.UpdateAvailable(
                        UpdateInfo(
                            tagName = cachedTag,
                            versionName = cleanVersion(cachedTag),
                            releaseName = "Call Helper $cachedTag",
                            releaseNotes = "",
                            downloadUrl = downloadUrl,
                            assetName = assetName,
                            assetSize = 0L
                        )
                    )
                }
                return@withContext CheckResult.UpToDate(currentVersionName)
            }

            // 2. Handle Rate Limiting (403 or 429) -> Fallback to web redirect
            if (responseCode == 403 || responseCode == 429) {
                val limitRemaining = connection.getHeaderField("X-RateLimit-Remaining")
                val limitReset = connection.getHeaderField("X-RateLimit-Reset")
                DebugLogStore.log(
                    TAG,
                    "GitHub API rate limit hit (HTTP $responseCode, remaining=$limitRemaining, reset=$limitReset). Attempting fallback redirect..."
                )
                Log.w(TAG, "GitHub API rate limit hit (HTTP $responseCode). Trying fallback redirect...")
                return@withContext fetchLatestReleaseViaRedirect(currentVersionName)
            }

            // 3. Handle Not Found (404)
            if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                DebugLogStore.log(TAG, "GitHub releases endpoint returned 404 Not Found")
                return@withContext CheckResult.Error(
                    message = "GitHub repository or release not found (HTTP 404)",
                    category = ErrorCategory.NOT_FOUND,
                    userMessage = "No releases found for Call Helper."
                )
            }

            // 4. Handle Server Errors (5xx)
            if (responseCode in 500..599) {
                DebugLogStore.log(TAG, "GitHub server error (HTTP $responseCode)")
                return@withContext CheckResult.Error(
                    message = "GitHub server error (HTTP $responseCode)",
                    category = ErrorCategory.SERVER_ERROR,
                    userMessage = "GitHub server error. Please try again later."
                )
            }

            // 5. Handle any other non-200 code
            if (responseCode != HttpURLConnection.HTTP_OK) {
                DebugLogStore.log(TAG, "GitHub API returned unexpected HTTP $responseCode")
                return@withContext CheckResult.Error(
                    message = "GitHub API returned HTTP $responseCode",
                    category = ErrorCategory.UNKNOWN,
                    userMessage = "Unable to check for updates (HTTP $responseCode)"
                )
            }

            // 6. Parse successful 200 OK response
            val etag = connection.getHeaderField("ETag")
            if (!etag.isNullOrBlank()) {
                cachedETag = etag
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)

            val isDraft = json.optBoolean("draft", false)
            val isPrerelease = json.optBoolean("prerelease", false)
            if (isDraft || isPrerelease) {
                DebugLogStore.log(TAG, "Latest release is draft ($isDraft) or prerelease ($isPrerelease); skipping")
                return@withContext CheckResult.UpToDate(currentVersionName)
            }

            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isEmpty()) {
                DebugLogStore.log(TAG, "Release JSON payload missing tag_name")
                return@withContext CheckResult.Error(
                    message = "Release payload missing tag_name",
                    category = ErrorCategory.MALFORMED_DATA,
                    userMessage = "Received invalid update data."
                )
            }

            cachedLatestTag = tagName

            val cleanRemoteVersion = cleanVersion(tagName)
            val cleanCurrentVersion = cleanVersion(currentVersionName)

            if (!isNewerVersion(cleanRemoteVersion, cleanCurrentVersion)) {
                DebugLogStore.log(TAG, "App is up to date: local=$currentVersionName, remote=$tagName")
                return@withContext CheckResult.UpToDate(currentVersionName)
            }

            val releaseName = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "")

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

            // Fallback download URL if assets list did not contain an explicit .apk asset
            if (apkDownloadUrl.isEmpty()) {
                apkAssetName = "CallHelper-$tagName.apk"
                apkDownloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$tagName/$apkAssetName"
                DebugLogStore.log(TAG, "No explicit APK asset in payload; synthesized fallback URL: $apkDownloadUrl")
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

            DebugLogStore.log(TAG, "Update available: $cleanRemoteVersion ($apkAssetName)")
            CheckResult.UpdateAvailable(info)
        } catch (e: UnknownHostException) {
            DebugLogStore.log(TAG, "No internet connection: ${e.message}")
            CheckResult.Error(
                message = "No internet connection: ${e.message}",
                category = ErrorCategory.NO_NETWORK,
                userMessage = "No internet connection available.",
                throwable = e
            )
        } catch (e: SocketTimeoutException) {
            DebugLogStore.log(TAG, "Update check timed out: ${e.message}")
            CheckResult.Error(
                message = "Update check timed out: ${e.message}",
                category = ErrorCategory.TIMEOUT,
                userMessage = "Update check timed out. Please try again.",
                throwable = e
            )
        } catch (e: JSONException) {
            DebugLogStore.log(TAG, "Malformed update JSON: ${e.message}")
            CheckResult.Error(
                message = "Malformed update JSON: ${e.message}",
                category = ErrorCategory.MALFORMED_DATA,
                userMessage = "Received invalid update information.",
                throwable = e
            )
        } catch (e: Exception) {
            DebugLogStore.log(TAG, "Unexpected error checking update: ${e.message}")
            Log.e(TAG, "Error checking for update: ${e.message}", e)
            CheckResult.Error(
                message = "Network error checking update: ${e.message}",
                category = ErrorCategory.UNKNOWN,
                userMessage = "Unable to check for updates.",
                throwable = e
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Fallback update checker using GitHub's canonical web redirect endpoint:
     * `https://github.com/mpsinghji/Call-Helper/releases/latest`
     *
     * This endpoint redirects (HTTP 302) to `https://github.com/mpsinghji/Call-Helper/releases/tag/<tag>`
     * with no API rate limiting, allowing resilient version resolution even when the REST API quota
     * is completely exhausted.
     */
    fun fetchLatestReleaseViaRedirect(currentVersionName: String): CheckResult {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_FALLBACK_RELEASE_URL)
            connection = connectionFactory.openConnection(url).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CallHelper-Android-App")
                setRequestProperty("Accept", "*/*")
            }

            val responseCode = connection.responseCode
            if (responseCode in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = connection.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    val extractedTag = extractTagFromReleaseUrl(location)
                    if (!extractedTag.isNullOrBlank()) {
                        cachedLatestTag = extractedTag
                        val cleanRemote = cleanVersion(extractedTag)
                        val cleanCurrent = cleanVersion(currentVersionName)

                        if (!isNewerVersion(cleanRemote, cleanCurrent)) {
                            DebugLogStore.log(TAG, "Fallback redirect check: app is up to date ($currentVersionName)")
                            return CheckResult.UpToDate(currentVersionName)
                        }

                        val assetName = "CallHelper-$extractedTag.apk"
                        val downloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$extractedTag/$assetName"
                        val info = UpdateInfo(
                            tagName = extractedTag,
                            versionName = cleanRemote,
                            releaseName = "Call Helper $extractedTag",
                            releaseNotes = "",
                            downloadUrl = downloadUrl,
                            assetName = assetName,
                            assetSize = 0L
                        )
                        DebugLogStore.log(TAG, "Fallback redirect check: update available $cleanRemote")
                        return CheckResult.UpdateAvailable(info)
                    }
                }
            }

            if (responseCode == 429 || responseCode == 403) {
                return CheckResult.Error(
                    message = "GitHub API rate limit exceeded and fallback redirect also rate limited (HTTP $responseCode)",
                    category = ErrorCategory.RATE_LIMITED,
                    userMessage = "Update server busy. Please try again later."
                )
            }

            return CheckResult.Error(
                message = "GitHub API rate limit hit and fallback redirect returned HTTP $responseCode",
                category = ErrorCategory.RATE_LIMITED,
                userMessage = "Update server busy. Please try again later."
            )
        } catch (e: Exception) {
            DebugLogStore.log(TAG, "Fallback check failed: ${e.message}")
            return CheckResult.Error(
                message = "GitHub API rate limit hit and fallback check failed: ${e.message}",
                category = ErrorCategory.RATE_LIMITED,
                userMessage = "Update server busy. Please try again later.",
                throwable = e
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extracts the release tag from a GitHub redirect Location header.
     * e.g. "https://github.com/mpsinghji/Call-Helper/releases/tag/v1.4.5" -> "v1.4.5"
     */
    fun extractTagFromReleaseUrl(url: String): String? {
        val regex = Regex("""/releases/tag/([^/?#]+)""")
        val match = regex.find(url)
        return match?.groupValues?.get(1)?.trim()?.trimEnd('/')
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
            val conn = connectionFactory.openConnection(URL(currentUrl)).apply {
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
        val cleanRemote = cleanVersion(remoteVersion)
        val cleanCurrent = cleanVersion(currentVersion)

        val remoteParts = cleanRemote.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val rem = remoteParts.getOrElse(i) { 0 }
            val cur = currentParts.getOrElse(i) { 0 }
            if (rem > cur) return true
            if (rem < cur) return false
        }
        return false
    }

    fun cleanVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }
}
