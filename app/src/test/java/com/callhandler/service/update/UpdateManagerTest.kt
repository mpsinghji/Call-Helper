package com.callhandler.service.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testIsNewerVersion_patchIncrement() {
        assertTrue(UpdateManager.isNewerVersion("1.4.1", "1.4.0"))
        assertTrue(UpdateManager.isNewerVersion("v1.4.1", "1.4.0"))
        assertTrue(UpdateManager.isNewerVersion("1.4.10", "1.4.2"))
    }

    @Test
    fun testIsNewerVersion_minorIncrement() {
        assertTrue(UpdateManager.isNewerVersion("1.5.0", "1.4.0"))
        assertTrue(UpdateManager.isNewerVersion("1.10.0", "1.9.5"))
    }

    @Test
    fun testIsNewerVersion_majorIncrement() {
        assertTrue(UpdateManager.isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun testIsNewerVersion_sameOrOlderVersion() {
        assertFalse(UpdateManager.isNewerVersion("1.4.0", "1.4.0"))
        assertFalse(UpdateManager.isNewerVersion("v1.4.0", "1.4.0"))
        assertFalse(UpdateManager.isNewerVersion("1.3.9", "1.4.0"))
        assertFalse(UpdateManager.isNewerVersion("1.0.0", "2.0.0"))
    }
}

