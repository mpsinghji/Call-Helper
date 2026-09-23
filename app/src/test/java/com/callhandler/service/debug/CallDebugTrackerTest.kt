package com.callhandler.service.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallDebugTrackerTest {

    @Before
    fun setUp() {
        DebugLogStore.clear()
        CallDebugTracker.reset()
    }

    @Test
    fun testMatchScenario_whenTruecallerMatchesAnnouncement() {
        val number = "09805305407"
        val name = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onTruecallerResult(number, name)
        CallDebugTracker.onIdentitySelected(number, name, "TRUECALLER")
        CallDebugTracker.onAnnouncementPrepared(name, "Incoming call from $name", "TRUECALLER")
        CallDebugTracker.onTtsStarted("Incoming call from $name")

        assertEquals(number, CallDebugTracker.phoneNumber)
        assertNull(CallDebugTracker.contactName)
        assertEquals(name, CallDebugTracker.truecallerName)
        assertEquals(name, CallDebugTracker.announcementName)
        assertEquals("TRUECALLER", CallDebugTracker.announcementSource)
        assertEquals("Incoming call from $name", CallDebugTracker.ttsText)
        assertFalse(CallDebugTracker.isMismatch)

        CallDebugTracker.onCallEnded()

        val logs = DebugLogStore.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("RESULT         : ✓ IDENTITY MATCH") })
    }

    @Test
    fun testMismatchScenario_whenTruecallerIsPavinder_butAnnouncementIsAman() {
        val number = "09805305407"
        val tcName = "Pavinder Jasrotia"
        val wrongName = "Aman"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onTruecallerResult(number, tcName)
        CallDebugTracker.onIdentitySelected(number, wrongName, "TRUECALLER")
        CallDebugTracker.onAnnouncementPrepared(wrongName, "Call from $wrongName", "TRUECALLER")
        CallDebugTracker.onTtsStarted("Call from $wrongName")

        assertEquals(number, CallDebugTracker.phoneNumber)
        assertNull(CallDebugTracker.contactName)
        assertEquals(tcName, CallDebugTracker.truecallerName)
        assertEquals(wrongName, CallDebugTracker.announcementName)
        assertTrue(CallDebugTracker.isMismatch)

        CallDebugTracker.onCallEnded()

        val logs = DebugLogStore.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("⚠ IDENTITY MISMATCH") })
        assertTrue(logs.any { it.contains("RESULT         : ⚠ IDENTITY MISMATCH") })
    }

    @Test
    fun testContactPriorityMatch() {
        val number = "09805305407"
        val contactName = "Pavinder Contact"
        val tcName = "Pavinder Truecaller"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, contactName)
        CallDebugTracker.onTruecallerResult(number, tcName)
        CallDebugTracker.onIdentitySelected(number, contactName, "CONTACT")
        CallDebugTracker.onAnnouncementPrepared(contactName, "Incoming call from $contactName", "CONTACT")

        assertEquals(contactName, CallDebugTracker.contactName)
        assertEquals(tcName, CallDebugTracker.truecallerName)
        assertEquals(contactName, CallDebugTracker.announcementName)
        assertEquals("CONTACT", CallDebugTracker.announcementSource)
        assertFalse(CallDebugTracker.isMismatch)
    }

    @Test
    fun testGsmCallSourceAndDirection() {
        val number = "09805305407"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")

        assertEquals(DebugCallSource.GSM, CallDebugTracker.callSource)
        assertEquals("INCOMING", CallDebugTracker.callDirection)

        val logs = DebugLogStore.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("Call Source    : GSM") })
        assertTrue(logs.any { it.contains("Direction      : INCOMING") })
    }

    @Test
    fun testWhatsAppIncomingCall() {
        val waNumber = "+919805305407"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.onTruecallerResult(waNumber, tcName)
        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "INCOMING",
            callerName = tcName,
            number = waNumber,
            allowed = true,
            reason = null,
            ttsText = "Incoming call from $tcName",
            packageName = "com.whatsapp"
        )
        CallDebugTracker.onTtsStarted("Incoming call from $tcName")
        CallDebugTracker.onCallEnded()

        assertEquals(DebugCallSource.WHATSAPP, CallDebugTracker.callSource)
        assertEquals("INCOMING", CallDebugTracker.callDirection)
        assertEquals(waNumber, CallDebugTracker.phoneNumber)
        assertEquals(tcName, CallDebugTracker.truecallerName)
        assertEquals(tcName, CallDebugTracker.announcementName)

        val logs = DebugLogStore.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("WHATSAPP CALL") })
        assertTrue(logs.any { it.contains("Call Source   : WhatsApp") })
        assertTrue(logs.any { it.contains("Direction     : INCOMING") })
        assertTrue(logs.any { it.contains("Number        : +919805305407") })
        assertTrue(logs.any { it.contains("Announcement  : Pavinder Jasrotia") })
        assertTrue(logs.any { it.contains("RESULT         : ✓ IDENTITY MATCH") })
    }

    @Test
    fun testWhatsAppOutgoingCall_isBlocked() {
        val contactName = "Tushar"

        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "OUTGOING",
            callerName = contactName,
            number = null,
            allowed = false,
            reason = "OUTGOING CALL",
            ttsText = null,
            packageName = "com.whatsapp"
        )

        assertEquals(DebugCallSource.WHATSAPP, CallDebugTracker.callSource)
        assertEquals("OUTGOING", CallDebugTracker.callDirection)
        assertEquals("BLOCKED", CallDebugTracker.announcementName)
        assertEquals("OUTGOING CALL", CallDebugTracker.reason)

        val logs = DebugLogStore.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("WHATSAPP CALL") })
        assertTrue(logs.any { it.contains("Call Source   : WhatsApp") })
        assertTrue(logs.any { it.contains("Direction     : OUTGOING") })
        assertTrue(logs.any { it.contains("Contact       : Tushar") })
        assertTrue(logs.any { it.contains("Announcement  : BLOCKED") })
        assertTrue(logs.any { it.contains("Reason        : OUTGOING CALL") })
    }

    @Test
    fun testWhatsAppUnknownDirection_isBlocked() {
        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "UNKNOWN",
            callerName = null,
            number = null,
            allowed = false,
            reason = "NO_POSITIVE_INCOMING_EVIDENCE",
            ttsText = null,
            packageName = "com.whatsapp"
        )

        assertEquals(DebugCallSource.WHATSAPP, CallDebugTracker.callSource)
        assertEquals("UNKNOWN", CallDebugTracker.callDirection)
        assertEquals("BLOCKED", CallDebugTracker.announcementName)
        assertEquals("NO_POSITIVE_INCOMING_EVIDENCE", CallDebugTracker.reason)

        val logs = DebugLogStore.logs.value.map { it.message }
        assertTrue(logs.any { it.contains("WHATSAPP CALL") })
        assertTrue(logs.any { it.contains("Direction     : UNKNOWN") })
        assertTrue(logs.any { it.contains("Announcement  : BLOCKED") })
    }

    @Test
    fun testDebugCallSourceMapping() {
        assertEquals(DebugCallSource.WHATSAPP, DebugCallSource.fromPackage("com.whatsapp"))
        assertEquals(DebugCallSource.WHATSAPP, DebugCallSource.fromPackage("com.whatsapp.w4b"))
        assertEquals(DebugCallSource.TELEGRAM, DebugCallSource.fromPackage("org.telegram.messenger"))
        assertEquals(DebugCallSource.MESSENGER, DebugCallSource.fromPackage("com.facebook.orca"))
        assertEquals(DebugCallSource.OTHER_VOIP, DebugCallSource.fromPackage("com.instagram.android"))
        assertEquals(DebugCallSource.OTHER_VOIP, DebugCallSource.fromPackage("com.snapchat.android"))
        assertEquals(DebugCallSource.UNKNOWN, DebugCallSource.fromPackage("com.random.unsupported"))
        assertEquals(DebugCallSource.UNKNOWN, DebugCallSource.fromPackage(null))
    }
}
