package com.callhandler.service.update

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UpdateManagerTest {

    private val originalFactory = UpdateManager.connectionFactory

    @Before
    fun setUp() {
        UpdateManager.resetCache()
    }

    @After
    fun tearDown() {
        UpdateManager.connectionFactory = originalFactory
        UpdateManager.resetCache()
    }

    private fun mockJsonResponse(
        status: Int,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        val conn = Mockito.mock(HttpURLConnection::class.java)
        Mockito.`when`(conn.responseCode).thenReturn(status)
        Mockito.`when`(conn.inputStream).thenAnswer { ByteArrayInputStream(jsonBody.toByteArray(Charsets.UTF_8)) }
        for ((key, value) in headers) {
            Mockito.`when`(conn.getHeaderField(key)).thenReturn(value)
        }
        return conn
    }

    // 1. Successful GitHub API response (v1.4.5)
    @Test
    fun test01_successfulGitHubApiResponse() = runBlocking {
        val json = JSONObject().apply {
            put("tag_name", "v1.4.5")
            put("name", "Call Helper v1.4.5")
            put("body", "Release notes for 1.4.5")
            val assets = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "CallHelper-v1.4.5.apk")
                    put("browser_download_url", "https://github.com/mpsinghji/Call-Helper/releases/download/v1.4.5/CallHelper-v1.4.5.apk")
                    put("size", 5020068L)
                })
            }
            put("assets", assets)
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json, mapOf("ETag" to "\"tag-145\""))
        }

        val result = UpdateManager.checkForUpdate("1.4.4")
        assertTrue(result is UpdateManager.CheckResult.UpdateAvailable)
        val available = result as UpdateManager.CheckResult.UpdateAvailable
        assertEquals("1.4.5", available.info.versionName)
        assertEquals("v1.4.5", available.info.tagName)
        assertEquals("CallHelper-v1.4.5.apk", available.info.assetName)
        assertEquals(5020068L, available.info.assetSize)
        assertEquals("\"tag-145\"", UpdateManager.cachedETag)
    }

    // 2. Newer release available (v2.0.0)
    @Test
    fun test02_newerReleaseAvailable() = runBlocking {
        val json = JSONObject().apply {
            put("tag_name", "v2.0.0")
            put("name", "Major Release 2.0.0")
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json)
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpdateAvailable)
        val available = result as UpdateManager.CheckResult.UpdateAvailable
        assertEquals("2.0.0", available.info.versionName)
    }

    // 3. App already up to date
    @Test
    fun test03_appAlreadyUpToDate() = runBlocking {
        val json = JSONObject().apply {
            put("tag_name", "v1.4.5")
            put("name", "Call Helper v1.4.5")
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json)
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpToDate)
        val upToDate = result as UpdateManager.CheckResult.UpToDate
        assertEquals("1.4.5", upToDate.currentVersion)
    }

    // 4. Prerelease filtering (prerelease: true)
    @Test
    fun test04_prereleaseFiltering() = runBlocking {
        val json = JSONObject().apply {
            put("tag_name", "v2.0.0-beta")
            put("name", "Call Helper v2.0.0-beta")
            put("prerelease", true)
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json)
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpToDate)
    }

    // 5. Draft filtering (draft: true)
    @Test
    fun test05_draftFiltering() = runBlocking {
        val json = JSONObject().apply {
            put("tag_name", "v2.0.0")
            put("name", "Draft 2.0.0")
            put("draft", true)
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json)
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpToDate)
    }

    // 6. Malformed JSON
    @Test
    fun test06_malformedJson() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, "{\"unclosed_json: true")
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.MALFORMED_DATA, error.category)
    }

    // 7. Missing tag name
    @Test
    fun test07_missingTagName() = runBlocking {
        val json = JSONObject().apply {
            put("name", "Release with no tag")
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json)
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.MALFORMED_DATA, error.category)
    }

    // 8. Missing APK asset (synthesized fallback URL)
    @Test
    fun test08_missingApkAsset_fallsBackToReleaseApkUrl() = runBlocking {
        val json = JSONObject().apply {
            put("tag_name", "v1.4.6")
            put("name", "Call Helper v1.4.6")
            put("assets", JSONArray()) // empty assets
        }.toString()

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            mockJsonResponse(HttpURLConnection.HTTP_OK, json)
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpdateAvailable)
        val available = result as UpdateManager.CheckResult.UpdateAvailable
        assertEquals("CallHelper-v1.4.6.apk", available.info.assetName)
        assertTrue(available.info.downloadUrl.endsWith("CallHelper-v1.4.6.apk"))
    }

    // 9. HTTP 403 with fallback to redirect
    @Test
    fun test09_http403_fallsBackToRedirect() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory { url ->
            val conn = Mockito.mock(HttpURLConnection::class.java)
            if (url.toString().contains("api.github.com")) {
                Mockito.`when`(conn.responseCode).thenReturn(403)
                Mockito.`when`(conn.getHeaderField("X-RateLimit-Remaining")).thenReturn("0")
                Mockito.`when`(conn.getHeaderField("X-RateLimit-Reset")).thenReturn("1700000000")
            } else {
                // Fallback web redirect endpoint
                Mockito.`when`(conn.responseCode).thenReturn(302)
                Mockito.`when`(conn.getHeaderField("Location"))
                    .thenReturn("https://github.com/mpsinghji/Call-Helper/releases/tag/v2.0.0")
            }
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpdateAvailable)
        val available = result as UpdateManager.CheckResult.UpdateAvailable
        assertEquals("2.0.0", available.info.versionName)
        assertEquals("v2.0.0", available.info.tagName)
        assertEquals("CallHelper-v2.0.0.apk", available.info.assetName)
    }

    // 10. HTTP 404
    @Test
    fun test10_http404_notFound() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            val conn = Mockito.mock(HttpURLConnection::class.java)
            Mockito.`when`(conn.responseCode).thenReturn(HttpURLConnection.HTTP_NOT_FOUND)
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.NOT_FOUND, error.category)
    }

    // 11. HTTP 429
    @Test
    fun test11_http429_rateLimitedBothEndpoints() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            val conn = Mockito.mock(HttpURLConnection::class.java)
            Mockito.`when`(conn.responseCode).thenReturn(429)
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.RATE_LIMITED, error.category)
    }

    // 12. HTTP 500
    @Test
    fun test12_http500_serverError() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            val conn = Mockito.mock(HttpURLConnection::class.java)
            Mockito.`when`(conn.responseCode).thenReturn(HttpURLConnection.HTTP_INTERNAL_ERROR)
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.SERVER_ERROR, error.category)
    }

    // 13. Timeout (SocketTimeoutException)
    @Test
    fun test13_socketTimeoutException() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            val conn = Mockito.mock(HttpURLConnection::class.java)
            Mockito.`when`(conn.responseCode).thenThrow(SocketTimeoutException("Read timed out"))
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.TIMEOUT, error.category)
    }

    // 14. No internet (UnknownHostException)
    @Test
    fun test14_unknownHostException() = runBlocking {
        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            val conn = Mockito.mock(HttpURLConnection::class.java)
            Mockito.`when`(conn.responseCode).thenThrow(UnknownHostException("api.github.com"))
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.Error)
        val error = result as UpdateManager.CheckResult.Error
        assertEquals(UpdateManager.ErrorCategory.NO_NETWORK, error.category)
    }

    // 15. Preference disabled (checkUpdatesEnabled = false)
    @Test
    fun test15_preferenceDisabled_skipsAutomaticCheck() {
        val shouldRun = UpdateManager.shouldCheckForUpdates(
            checkUpdatesEnabled = false,
            manualTrigger = false
        )
        assertFalse(shouldRun)
    }

    // 16. Manual check allowed when automatic checking is disabled
    @Test
    fun test16_preferenceDisabled_allowsManualCheck() {
        val shouldRun = UpdateManager.shouldCheckForUpdates(
            checkUpdatesEnabled = false,
            manualTrigger = true
        )
        assertTrue(shouldRun)
    }

    // Additional coverage: ETag 304 Not Modified
    @Test
    fun test_etag304NotModified_returnsUpToDate() = runBlocking {
        UpdateManager.cachedETag = "\"v145-tag\""
        UpdateManager.cachedLatestTag = "v1.4.5"

        UpdateManager.connectionFactory = UpdateManager.HttpConnectionFactory {
            val conn = Mockito.mock(HttpURLConnection::class.java)
            Mockito.`when`(conn.responseCode).thenReturn(HttpURLConnection.HTTP_NOT_MODIFIED)
            conn
        }

        val result = UpdateManager.checkForUpdate("1.4.5")
        assertTrue(result is UpdateManager.CheckResult.UpToDate)
    }

    // Additional coverage: URL tag extraction
    @Test
    fun test_extractTagFromReleaseUrl() {
        assertEquals("v1.4.5", UpdateManager.extractTagFromReleaseUrl("https://github.com/mpsinghji/Call-Helper/releases/tag/v1.4.5"))
        assertEquals("1.4.6", UpdateManager.extractTagFromReleaseUrl("https://github.com/mpsinghji/Call-Helper/releases/tag/1.4.6/"))
        assertEquals("v2.0.0-rc1", UpdateManager.extractTagFromReleaseUrl("https://github.com/mpsinghji/Call-Helper/releases/tag/v2.0.0-rc1?raw=true"))
    }

    // Additional coverage: Semantic version comparison
    @Test
    fun test_isNewerVersion() {
        assertTrue(UpdateManager.isNewerVersion("1.4.1", "1.4.0"))
        assertTrue(UpdateManager.isNewerVersion("v1.4.1", "1.4.0"))
        assertTrue(UpdateManager.isNewerVersion("1.4.10", "1.4.2"))
        assertTrue(UpdateManager.isNewerVersion("1.5.0", "1.4.0"))
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertFalse(UpdateManager.isNewerVersion("1.4.0", "1.4.0"))
        assertFalse(UpdateManager.isNewerVersion("v1.4.0", "1.4.0"))
        assertFalse(UpdateManager.isNewerVersion("1.3.9", "1.4.0"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "2.0.0"))
    }
}
