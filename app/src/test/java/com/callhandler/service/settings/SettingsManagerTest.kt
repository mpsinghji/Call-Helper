package com.callhandler.service.settings

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SettingsManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.applicationContext).thenReturn(mockContext)
        `when`(mockContext.packageName).thenReturn("com.callhandler.service")
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
    }

    @Test
    fun testKeyConstants() {
        assertEquals("pref_check_updates_enabled", SettingsManager.KEY_CHECK_UPDATES)
        assertEquals("pref_voice_commands_enabled", SettingsManager.KEY_VOICE_COMMANDS)
        assertEquals("pref_announcement_enabled", SettingsManager.KEY_ANNOUNCEMENT)
        assertEquals("pref_spam_announcement_policy", SettingsManager.KEY_SPAM_POLICY)
    }

    @Test
    fun testCheckUpdatesEnabled_defaultTrue() {
        `when`(mockPrefs.getBoolean(eq(SettingsManager.KEY_CHECK_UPDATES), eq(true))).thenReturn(true)
        val settings = SettingsManager(mockContext)
        assertTrue(settings.checkUpdatesEnabled)
    }

    @Test
    fun testCheckUpdatesEnabled_disabled() {
        `when`(mockPrefs.getBoolean(eq(SettingsManager.KEY_CHECK_UPDATES), eq(true))).thenReturn(false)
        val settings = SettingsManager(mockContext)
        assertFalse(settings.checkUpdatesEnabled)
    }

    @Test
    fun testSetCheckUpdatesEnabled() {
        val settings = SettingsManager(mockContext)
        settings.setCheckUpdatesEnabled(false)
        verify(mockPrefs).edit()
        verify(mockEditor).putBoolean(SettingsManager.KEY_CHECK_UPDATES, false)
        verify(mockEditor).apply()
    }
}
