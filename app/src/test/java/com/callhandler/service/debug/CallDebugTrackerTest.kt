package com.callhandler.service.debug

import com.callhandler.service.core.CallSessionState
import com.callhandler.service.core.SpamStatus
import com.callhandler.service.settings.SpamAnnouncementPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallDebugTrackerTest {

    @Before
    fun setUp() {
        DebugLogStore.clear()
        CallDebugTracker.clearAll()
        CallDebugTracker.isRepeatEnabled = false
    }

    // 1. Contact beats Truecaller (Priority correct - NOT a bug)
    @Test
    fun testContactBeatsTruecaller_priorityCorrect() {
        val number = "+919805305407"
        val contactName = "Aman"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, contactName)
        CallDebugTracker.onTruecallerResult(number, tcName)
        CallDebugTracker.onIdentitySelected(number, contactName, "CONTACT")
        CallDebugTracker.onAnnouncementPrepared(contactName, "Incoming call from $contactName", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $contactName")

        assertEquals(contactName, CallDebugTracker.contactName)
        assertEquals(tcName, CallDebugTracker.truecallerName)
        assertEquals(contactName, CallDebugTracker.announcementName)
        assertEquals("CONTACT", CallDebugTracker.announcementSource)
        assertFalse("Contact beating Truecaller is correct priority, NOT a mismatch!", CallDebugTracker.isMismatch)

        CallDebugTracker.onCallEnded()
    }

    // Bug test: Priority violation (when Contact exists but Truecaller was announced)
    @Test
    fun testIdentityPriorityViolation_detected() {
        val number = "+919805305407"
        val contactName = "Aman"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, contactName)
        CallDebugTracker.onTruecallerResult(number, tcName)
        // Bug: Truecaller selected over Contact!
        CallDebugTracker.onIdentitySelected(number, tcName, "TRUECALLER")
        CallDebugTracker.onAnnouncementPrepared(tcName, "Incoming call from $tcName", "TRUECALLER")
        CallDebugTracker.onTtsStarted("Incoming call from $tcName")

        assertTrue("Identity priority violation must be flagged as a mismatch/bug", CallDebugTracker.isMismatch)
        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertTrue(active!!.detectedBugs.any { it.type == BugType.IDENTITY_PRIORITY_VIOLATION })

        CallDebugTracker.onCallEnded()
    }

    // 2. Truecaller used when contact unavailable
    @Test
    fun testTruecallerUsedWhenContactUnavailable() {
        val number = "+919805305407"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onTruecallerResult(number, tcName)
        CallDebugTracker.onIdentitySelected(number, tcName, "TRUECALLER")
        CallDebugTracker.onAnnouncementPrepared(tcName, "Incoming call from $tcName", "TRUECALLER")

        assertEquals(tcName, CallDebugTracker.announcementName)
        assertEquals("TRUECALLER", CallDebugTracker.announcementSource)
        assertFalse(CallDebugTracker.isMismatch)
    }

    // 3. Unknown when both unavailable
    @Test
    fun testUnknownWhenBothUnavailable() {
        val number = "+919805305407"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onIdentitySelected(number, "Unknown caller", "UNKNOWN")
        CallDebugTracker.onAnnouncementPrepared("Unknown caller", "Incoming call from Unknown caller", "UNKNOWN")

        assertEquals("Unknown caller", CallDebugTracker.announcementName)
        assertEquals("UNKNOWN", CallDebugTracker.announcementSource)
        assertFalse(CallDebugTracker.isMismatch)
    }

    // 4. Truecaller arriving after TTS does not change announcement
    @Test
    fun testTruecallerArrivingAfterTts_doesNotChangeAnnouncement() {
        val number = "+919805305407"
        val initialName = "Unknown caller"
        val lateTcName = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onIdentitySelected(number, initialName, "UNKNOWN")
        CallDebugTracker.onAnnouncementPrepared(initialName, "Incoming call from $initialName", "UNKNOWN")
        CallDebugTracker.onTtsStarted("Incoming call from $initialName")

        assertTrue(CallDebugTracker.activeSession.value?.isIdentityLocked == true)

        // Late Truecaller update arrives after announcement is locked
        CallDebugTracker.onTruecallerResult(number, lateTcName)

        // Announcement MUST NOT change!
        assertEquals("Unknown caller", CallDebugTracker.announcementName)
        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertTrue(active!!.detectedBugs.any { it.type == BugType.POST_ANNOUNCEMENT_MUTATION })
    }

    // 5. "Call ended less than 1m ago" rejected as a caller name
    @Test
    fun testTruecallerRejectsCallEndedLess1m() {
        val number = "+919805305407"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        val staleText = "Call ended less than 1m ago"
        CallDebugTracker.onTruecallerStaleText(staleText)

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertTrue(active!!.detectedBugs.any { it.type == BugType.TRUECALLER_STALE_TEXT && it.details.contains(staleText) })
    }

    // 6. "Call ended 25m ago" rejected
    @Test
    fun testTruecallerRejectsCallEnded25m() {
        val number = "+919805305407"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        val staleText = "Call ended 25m ago"
        CallDebugTracker.onTruecallerStaleText(staleText)

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertTrue(active!!.detectedBugs.any { it.type == BugType.TRUECALLER_STALE_TEXT && it.details.contains(staleText) })
    }

    // 7. Truecaller number mismatch rejected
    @Test
    fun testTruecallerNumberMismatch_rejected() {
        val activeNumber = "+919805305407"
        val differentNumber = "+918882174388"
        val tcName = "Mummy Ji"

        CallDebugTracker.onCallDetected(activeNumber, "PHONE_STATE")
        CallDebugTracker.onTruecallerResult(differentNumber, tcName)

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertNull("Truecaller name from a different number must not be applied to active call", active!!.truecallerName)
        assertTrue(active.detectedBugs.any { it.type == BugType.TRUECALLER_NUMBER_MISMATCH })
    }

    // 8. Duplicate notification does not create duplicate call session
    @Test
    fun testDuplicateVoipNotification_doesNotCreateDuplicateCallSession() {
        val notifKey = "wa_key_101"
        val caller = "Aman"

        // First notification post
        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "INCOMING",
            callerName = caller,
            number = null,
            allowed = true,
            reason = null,
            ttsText = "WhatsApp voice call from Aman",
            packageName = "com.whatsapp",
            notificationKey = notifKey
        )

        val firstSessionId = CallDebugTracker.activeSession.value?.id
        assertNotNull(firstSessionId)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Second notification post (same key, time elapsed update)
        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "INCOMING",
            callerName = caller,
            number = null,
            allowed = true,
            reason = null,
            ttsText = "WhatsApp voice call from Aman",
            packageName = "com.whatsapp",
            notificationKey = notifKey
        )

        assertEquals("Session ID must remain identical on repeated notification update",
            firstSessionId, CallDebugTracker.activeSession.value?.id)
        assertEquals("Must NOT create a new session entry for repeated notification update",
            1, CallDebugTracker.sessionHistory.value.size)
    }

    // 9. Duplicate Truecaller update does not create duplicate call session
    @Test
    fun testDuplicateTruecallerUpdate_doesNotCreateDuplicateCallSession() {
        val number = "+919805305407"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        val sessionId = CallDebugTracker.activeSession.value?.id
        assertNotNull(sessionId)

        // First Truecaller update
        CallDebugTracker.onTruecallerResult(number, tcName)
        // Second Truecaller update
        CallDebugTracker.onTruecallerResult(number, tcName)

        assertEquals(sessionId, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        assertEquals(2, CallDebugTracker.activeSession.value?.identityUpdateCount)
    }

    // 10. Duplicate TTS detected
    @Test
    fun testDuplicateTts_detected() {
        val number = "+919805305407"
        val name = "Pavinder"
        CallDebugTracker.isRepeatEnabled = false

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onAnnouncementPrepared(name, "Incoming call from $name", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $name")
        // Unexpected second TTS start
        CallDebugTracker.onTtsStarted("Incoming call from $name")

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertTrue("Unexpected repeated TTS announcement must be detected as bug",
            active!!.detectedBugs.any { it.type == BugType.DUPLICATE_TTS })
    }

    // 11. Intentional repeat announcement is NOT falsely reported as duplicate
    @Test
    fun testIntentionalRepeatTts_notReportedAsDuplicate() {
        val number = "+919805305407"
        val name = "Pavinder"
        CallDebugTracker.isRepeatEnabled = true // Configured repeat enabled

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onAnnouncementPrepared(name, "Incoming call from $name", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $name")
        CallDebugTracker.onTtsStarted("Incoming call from $name")

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertFalse("Configured repeat announcement must NOT be flagged as duplicate bug",
            active!!.detectedBugs.any { it.type == BugType.DUPLICATE_TTS })
    }

    // 12. Outgoing WhatsApp is blocked
    @Test
    fun testOutgoingWhatsApp_isBlocked() {
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

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertEquals("OUTGOING", active!!.direction)
        assertEquals(CallSessionState.BLOCKED, active.state)
        assertEquals("BLOCKED", active.announcedName)
        assertEquals("OUTGOING CALL", active.blockReason)
    }

    // 13. Unknown WhatsApp direction is blocked
    @Test
    fun testUnknownWhatsAppDirection_isBlocked() {
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

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertEquals("UNKNOWN", active!!.direction)
        assertEquals(CallSessionState.BLOCKED, active.state)
        assertEquals("BLOCKED", active.announcedName)
        assertEquals("NO_POSITIVE_INCOMING_EVIDENCE", active.blockReason)
    }

    // 14. Incoming WhatsApp can be announced
    @Test
    fun testIncomingWhatsApp_canBeAnnounced() {
        val caller = "Aman"

        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "INCOMING",
            callerName = caller,
            number = null,
            allowed = true,
            reason = null,
            ttsText = "WhatsApp voice call from $caller",
            packageName = "com.whatsapp"
        )

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertEquals("INCOMING", active!!.direction)
        assertEquals(caller, active.announcedName)
        assertEquals("WhatsApp voice call from Aman", active.ttsText)
    }

    // 15. Spam metadata does not override contact identity
    @Test
    fun testSpamMetadata_doesNotOverrideContactIdentity() {
        val number = "+919805305407"
        val contactName = "Aman"
        val tcName = "Casence Support"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, contactName)
        CallDebugTracker.onTruecallerResult(number, tcName, spamStatus = SpamStatus.POSSIBLE_SPAM)
        CallDebugTracker.onIdentitySelected(number, contactName, "CONTACT")

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertEquals("Aman", active!!.announcedName)
        assertEquals("CONTACT", active.announcementSource)
        assertEquals(SpamStatus.POSSIBLE_SPAM, active.spamStatus)
    }

    // 16. Spam policy can block announcement
    @Test
    fun testSpamPolicy_canBlockAnnouncement() {
        val number = "+919805305407"
        val tcName = "Casence Support"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onTruecallerResult(number, tcName, spamStatus = SpamStatus.SPAM)
        // Blocked under spam policy
        CallDebugTracker.onAnnouncementBlocked("SPAM POLICY")

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertEquals(CallSessionState.BLOCKED, active!!.state)
        assertEquals("BLOCKED", active.announcedName)
        assertEquals("SPAM POLICY", active.blockReason)
    }

    // 17. GSM session and WhatsApp session remain separate
    @Test
    fun testGsmAndWhatsAppSessions_remainSeparate() {
        // 1. GSM call
        CallDebugTracker.onCallDetected("+919805305407", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+919805305407", "Aman")
        CallDebugTracker.onIdentitySelected("+919805305407", "Aman", "CONTACT")
        val gsmSessionId = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        // 2. WhatsApp call
        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "INCOMING",
            callerName = "Tushar",
            number = null,
            allowed = true,
            reason = null,
            ttsText = "WhatsApp voice call from Tushar",
            packageName = "com.whatsapp"
        )
        val waSessionId = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        assertNotNull(gsmSessionId)
        assertNotNull(waSessionId)
        assertNotEquals(gsmSessionId, waSessionId)
        assertEquals(2, CallDebugTracker.sessionHistory.value.size)
        assertEquals(DebugCallSource.GSM, CallDebugTracker.sessionHistory.value[0].callSource)
        assertEquals(DebugCallSource.WHATSAPP, CallDebugTracker.sessionHistory.value[1].callSource)
    }

    // 18. Truecaller updates without an active call do not create call-history entries
    @Test
    fun testTruecallerUpdatesWithoutActiveCall_doNotCreateCallSessions() {
        assertEquals(0, CallDebugTracker.sessionHistory.value.size)

        // Truecaller accessibility sees an overlay while no call is active
        CallDebugTracker.onTruecallerResult("+919805305407", "Pavinder Jasrotia")
        CallDebugTracker.onTruecallerResult("+918882174388", "Lenovo Customer Care")

        assertEquals("Truecaller observations without an active GSM call must NEVER create call history sessions",
            0, CallDebugTracker.sessionHistory.value.size)
    }

    // Pipeline conflict: Recognizer was active when TTS started
    @Test
    fun testAudioPipelineConflict_detected() {
        val number = "+919805305407"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onAnnouncementPrepared("Aman", "Incoming call from Aman", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Aman", isRecognizerActive = true)

        val active = CallDebugTracker.activeSession.value
        assertNotNull(active)
        assertTrue(active!!.detectedBugs.any { it.type == BugType.AUDIO_PIPELINE_CONFLICT })
    }

    // Clean session formatting test
    @Test
    fun testCleanSessionCardFormatting() {
        val number = "+919805305407"
        val tcName = "Pavinder Jasrotia"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, null)
        CallDebugTracker.onTruecallerResult(number, tcName)
        CallDebugTracker.onIdentitySelected(number, tcName, "TRUECALLER")
        CallDebugTracker.onAnnouncementPrepared(tcName, "Incoming call from $tcName", "TRUECALLER")
        CallDebugTracker.onTtsStarted("Incoming call from $tcName")
        CallDebugTracker.onCallEnded()

        val active = CallDebugTracker.sessionHistory.value.first()
        val card = CallDebugTracker.formatCleanSessionCard(active)

        assertTrue(card.contains("GSM CALL"))
        assertTrue(card.contains("Direction : INCOMING"))
        assertTrue(card.contains("Number    : +919805305407"))
        assertTrue(card.contains("Truecaller: Pavinder Jasrotia"))
        assertTrue(card.contains("Announced : Pavinder Jasrotia"))
        assertTrue(card.contains("Status    : ✓ ANNOUNCED"))
    }

    // 19. Separate calls create independent sessions (Papa Ji -> 101, Mummy Ji -> 102, Aman -> 103)
    @Test
    fun testSeparateCallsCreateIndependentSessions() {
        // Call 1: Papa Ji
        CallDebugTracker.onCallDetected("+919419100001", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+919419100001", "Papa Ji")
        CallDebugTracker.onIdentitySelected("+919419100001", "Papa Ji", "CONTACT")
        CallDebugTracker.onAnnouncementPrepared("Papa Ji", "Incoming call from Papa Ji", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Papa Ji")
        val session1Id = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        // Call 2: Mummy Ji
        CallDebugTracker.onCallDetected("+919816939576", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+919816939576", "Mummy Ji")
        CallDebugTracker.onIdentitySelected("+919816939576", "Mummy Ji", "CONTACT")
        CallDebugTracker.onAnnouncementPrepared("Mummy Ji", "Incoming call from Mummy Ji", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Mummy Ji")
        val session2Id = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        // Call 3: Aman
        CallDebugTracker.onCallDetected("+917018308746", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+917018308746", "Aman")
        CallDebugTracker.onIdentitySelected("+917018308746", "Aman", "CONTACT")
        CallDebugTracker.onAnnouncementPrepared("Aman", "Incoming call from Aman", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Aman")
        val session3Id = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        assertNotNull(session1Id)
        assertNotNull(session2Id)
        assertNotNull(session3Id)
        assertNotEquals(session1Id, session2Id)
        assertNotEquals(session2Id, session3Id)
        assertNotEquals(session1Id, session3Id)

        val history = CallDebugTracker.sessionHistory.value
        assertEquals(3, history.size)
        assertEquals("Papa Ji", history[0].contactName)
        assertEquals("+919419100001", history[0].number)
        assertEquals("Mummy Ji", history[1].contactName)
        assertEquals("+919816939576", history[1].number)
        assertEquals("Aman", history[2].contactName)
        assertEquals("+917018308746", history[2].number)
    }

    // 20. Multiple GSM detectors (PHONE_STATE + PHONE_STATE_LISTENER) map to the SAME session
    @Test
    fun testMultipleGsmDetectors_sameSession() {
        val number = "+919816939576"

        // PHONE_STATE_LISTENER reports ringing
        CallDebugTracker.onCallDetected(number, "PHONE_STATE_LISTENER")
        val sessionId1 = CallDebugTracker.activeSession.value?.id
        assertNotNull(sessionId1)

        // 80ms later: PHONE_STATE reports ringing for the same number
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        val sessionId2 = CallDebugTracker.activeSession.value?.id

        assertEquals("Both detector sources for the same ringing call must map to the SAME session",
            sessionId1, sessionId2)
        assertEquals("Only one session entry must exist in history",
            1, CallDebugTracker.sessionHistory.value.size)
    }

    // 21. New call after previous call ended creates a new session
    @Test
    fun testNewCallAfterEnded_createsNewSession() {
        // Call A starts & ends
        CallDebugTracker.onCallDetected("+919816939576", "PHONE_STATE")
        val sessionAId = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        assertNull("Active session must be cleared after call ended", CallDebugTracker.activeSession.value)

        // Call B starts
        CallDebugTracker.onCallDetected("+917018308746", "PHONE_STATE")
        val sessionBId = CallDebugTracker.activeSession.value?.id

        assertNotNull(sessionAId)
        assertNotNull(sessionBId)
        assertNotEquals("New call after ended must get a new session ID", sessionAId, sessionBId)
    }

    // 22. Completed history is strictly immutable; later calls never mutate older entries
    @Test
    fun testCompletedHistoryIsImmutable() {
        // Call A: Mummy Ji
        CallDebugTracker.onCallDetected("+919816939576", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+919816939576", "Mummy Ji")
        CallDebugTracker.onIdentitySelected("+919816939576", "Mummy Ji", "CONTACT")
        CallDebugTracker.onAnnouncementPrepared("Mummy Ji", "Incoming call from Mummy Ji", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Mummy Ji")
        CallDebugTracker.onCallEnded()

        val historyBefore = CallDebugTracker.sessionHistory.value[0]
        assertEquals("Mummy Ji", historyBefore.contactName)
        assertEquals("+919816939576", historyBefore.number)
        assertEquals("Mummy Ji", historyBefore.announcedName)

        // Call B: Aman
        CallDebugTracker.onCallDetected("+917018308746", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+917018308746", "Aman")
        CallDebugTracker.onIdentitySelected("+917018308746", "Aman", "CONTACT")
        CallDebugTracker.onAnnouncementPrepared("Aman", "Incoming call from Aman", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Aman")

        // History entry for Call A MUST remain completely intact and NOT mutated to Aman!
        val historyAfterCallB = CallDebugTracker.sessionHistory.value[0]
        assertEquals("History A contact must still be Mummy Ji", "Mummy Ji", historyAfterCallB.contactName)
        assertEquals("History A number must still be +919816939576", "+919816939576", historyAfterCallB.number)
        assertEquals("History A announced name must still be Mummy Ji", "Mummy Ji", historyAfterCallB.announcedName)
    }

    // 23. Active call number resets cleanly and is not inherited from previous session
    @Test
    fun testNumberResetBetweenCalls() {
        // Call A: +919816939576
        CallDebugTracker.onCallDetected("+919816939576", "PHONE_STATE")
        CallDebugTracker.onCallEnded()

        // Call B: +917018308746
        CallDebugTracker.onCallDetected("+917018308746", "PHONE_STATE")
        val activeNumber = CallDebugTracker.activeSession.value?.phoneNumber
        assertEquals("+917018308746", activeNumber)
        assertEquals("+917018308746", CallDebugTracker.phoneNumber)
    }

    // 24. Truecaller observations after call ended must not reopen, create, or modify sessions
    @Test
    fun testTruecallerAfterCallEnded_doesNotModifyHistory() {
        CallDebugTracker.onCallDetected("+919816939576", "PHONE_STATE")
        CallDebugTracker.onContactLookupResult("+919816939576", "Mummy Ji")
        CallDebugTracker.onCallEnded()

        val historyBefore = CallDebugTracker.sessionHistory.value[0]
        assertNull(historyBefore.truecallerName)

        // Late Truecaller observation arrives after call ended
        CallDebugTracker.onTruecallerResult("+919816939576", "Mummy Mobile")

        // History must NOT be modified
        val historyAfter = CallDebugTracker.sessionHistory.value[0]
        assertNull("Truecaller observation after call ended must NOT modify history", historyAfter.truecallerName)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        assertNull("Active session must remain null", CallDebugTracker.activeSession.value)
    }

    // 25. Duplicate announcement guard suppresses duplicate announcement requests when repeat disabled
    @Test
    fun testDuplicateTtsGuard_blocksDuplicateWhenRepeatDisabled() {
        val number = "+917018308746"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")

        // First announcement check
        val canAnnounce1 = CallDebugTracker.canStartAnnouncement(repeatEnabled = false)
        assertTrue("First announcement request must be allowed", canAnnounce1)

        // Parallel / duplicate announcement check from second detector
        val canAnnounce2 = CallDebugTracker.canStartAnnouncement(repeatEnabled = false)
        assertFalse("Duplicate announcement request must be suppressed when repeat is disabled", canAnnounce2)
    }

    // 26. Announcement guard allows repeated announcements when repeat enabled
    @Test
    fun testDuplicateTtsGuard_allowsRepeatWhenRepeatEnabled() {
        val number = "+917018308746"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")

        val canAnnounce1 = CallDebugTracker.canStartAnnouncement(repeatEnabled = true)
        assertTrue("First announcement request must be allowed", canAnnounce1)

        val canAnnounce2 = CallDebugTracker.canStartAnnouncement(repeatEnabled = true)
        assertTrue("Second announcement request must be allowed when repeat is enabled", canAnnounce2)
    }

    // 27. Strictly monotonic session IDs (Call 1 -> #1, Call 2 -> #2, Call 3 -> #3)
    @Test
    fun testMonotonicSessionIds_Call1_2_3() {
        // Call 1
        CallDebugTracker.onCallDetected("+919419100001", "PHONE_STATE")
        val id1 = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        // Call 2
        CallDebugTracker.onCallDetected("+919816939576", "PHONE_STATE")
        val id2 = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        // Call 3
        CallDebugTracker.onCallDetected("+917018308746", "PHONE_STATE")
        val id3 = CallDebugTracker.activeSession.value?.id
        CallDebugTracker.onCallEnded()

        assertEquals(1L, id1)
        assertEquals(2L, id2)
        assertEquals(3L, id3)

        val history = CallDebugTracker.sessionHistory.value
        assertEquals(3, history.size)
        assertEquals(1L, history[0].sessionId)
        assertEquals(2L, history[1].sessionId)
        assertEquals(3L, history[2].sessionId)
    }

    // 28. Single session for physical call: multiple detector events and identity updates must NOT create second session
    @Test
    fun testSingleSessionForPhysicalCall_MultipleDetectorsAndIdentityUpdates() {
        val number = "+917018308746"
        val contactName = "Aman"
        val tcName = "Pavinder Jasrotia"

        // Event 1: PHONE_STATE_LISTENER
        CallDebugTracker.onCallDetected(number, "PHONE_STATE_LISTENER")
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Event 2: PHONE_STATE
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Event 3: onPhoneNumberResolved from CallerIdentityManager
        CallDebugTracker.onPhoneNumberResolved(number, "INCOMING_NUMBER")
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Event 4: Contact lookup
        CallDebugTracker.onContactLookupResult(number, contactName)
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Event 5: Truecaller observation
        CallDebugTracker.onTruecallerResult(number, tcName)
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Event 6: Identity selection
        CallDebugTracker.onIdentitySelected(number, contactName, "CONTACT")
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Event 7: Announcement prepared & TTS started
        CallDebugTracker.onAnnouncementPrepared(contactName, "Incoming call from $contactName", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $contactName")
        assertEquals(1L, CallDebugTracker.activeSession.value?.id)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)

        // Call ended
        CallDebugTracker.onCallEnded()
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        assertEquals(1L, CallDebugTracker.sessionHistory.value[0].sessionId)
        assertEquals(number, CallDebugTracker.sessionHistory.value[0].number)
        assertEquals(contactName, CallDebugTracker.sessionHistory.value[0].contactName)
        assertEquals(tcName, CallDebugTracker.sessionHistory.value[0].truecallerName)
        assertEquals(contactName, CallDebugTracker.sessionHistory.value[0].announcedName)
        assertEquals("CONTACT", CallDebugTracker.sessionHistory.value[0].announcementSource)
    }

    // 29. Developer filter tests
    @Test
    fun testDeveloperFilters() {
        // GSM call
        CallDebugTracker.onCallDetected("+919805305407", "PHONE_STATE")
        CallDebugTracker.onCallEnded()

        // WhatsApp call
        CallDebugTracker.onVoipCallEvent(
            source = DebugCallSource.WHATSAPP,
            direction = "INCOMING",
            callerName = "Aman",
            number = null,
            allowed = true,
            reason = null,
            ttsText = "WhatsApp call from Aman",
            packageName = "com.whatsapp"
        )
        CallDebugTracker.onCallEnded()

        val allTimeline = CallDebugTracker.getFilteredDeveloperTimeline(DeveloperFilter.ALL)
        assertTrue(allTimeline.contains("SESSION #1"))
        assertTrue(allTimeline.contains("SESSION #2"))

        val gsmTimeline = CallDebugTracker.getFilteredDeveloperTimeline(DeveloperFilter.GSM)
        assertTrue(gsmTimeline.contains("SESSION #1"))
        assertFalse(gsmTimeline.contains("SESSION #2"))

        val waTimeline = CallDebugTracker.getFilteredDeveloperTimeline(DeveloperFilter.WHATSAPP)
        assertFalse(waTimeline.contains("SESSION #1"))
        assertTrue(waTimeline.contains("SESSION #2"))
    }

    // 30. Separate exports for Call History and Developer Details
    @Test
    fun testSeparateExports_CleanHistoryVsDeveloperDetails() {
        val number = "+917018308746"
        val name = "Aman"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, name)
        CallDebugTracker.onIdentitySelected(number, name, "CONTACT")
        CallDebugTracker.onAnnouncementPrepared(name, "Incoming call from $name", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $name")
        CallDebugTracker.onCallEnded()

        val cleanHistory = CallDebugTracker.exportCleanCallHistory()
        val devDetails = CallDebugTracker.exportDeveloperDetails()

        // Clean history should look like a clean card, not technical timeline
        assertTrue(cleanHistory.contains("GSM CALL"))
        assertTrue(cleanHistory.contains("Phone     : Aman"))
        assertFalse(cleanHistory.contains("=== SESSION #1 TECHNICAL TIMELINE ==="))
        assertFalse(cleanHistory.contains("EVENTS:"))

        // Developer details must contain full technical timeline
        assertTrue(devDetails.contains("=== SESSION #1 TECHNICAL TIMELINE ==="))
        assertTrue(devDetails.contains("EVENTS:"))
        assertTrue(devDetails.contains("GSM CALL DETECTED"))
    }

    // 31. Test Scenario A — Number arrives immediately: ONE SESSION, Mummy Ji
    @Test
    fun testScenarioA_numberArrivesImmediately() {
        val number = "+919816939576"
        val contact = "Mummy Ji"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, contact)
        CallDebugTracker.onIdentitySelected(number, contact, "CONTACT")
        CallDebugTracker.onAnnouncementPrepared(contact, "Incoming call from $contact", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $contact")
        CallDebugTracker.onCallEnded()

        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        val history = CallDebugTracker.sessionHistory.value[0]
        assertEquals(1L, history.sessionId)
        assertEquals(number, history.number)
        assertEquals(contact, history.contactName)
        assertEquals("CONTACT", history.announcementSource)
        assertEquals(contact, history.announcedName)
    }

    // 32. Test Scenario B — PHONE_STATE initially has no number, later PHONE_STATE_LISTENER has number + CONTACT
    @Test
    fun testScenarioB_phoneStateInitiallyNoNumber_thenListenerWithNumber() {
        val number = "+919816939576"
        val contact = "Mummy Ji"

        // Event 1: PHONE_STATE arrives first without number
        CallDebugTracker.onCallDetected(null, "PHONE_STATE")
        val session = CallDebugTracker.activeSession.value
        assertNotNull(session)
        assertEquals(1L, session?.id)
        assertNull(session?.phoneNumber)

        // Event 2: PHONE_STATE_LISTENER arrives 100ms later with number
        CallDebugTracker.onCallDetected(number, "PHONE_STATE_LISTENER")
        assertEquals("Must be the same session #1", 1L, CallDebugTracker.activeSession.value?.id)
        assertEquals("Phone number must be merged into session #1", number, CallDebugTracker.activeSession.value?.phoneNumber)
        assertEquals("Must only be 1 session in history", 1, CallDebugTracker.sessionHistory.value.size)

        // Contact lookup resolves
        CallDebugTracker.onContactLookupResult(number, contact)
        CallDebugTracker.onIdentitySelected(number, contact, "CONTACT")
        assertEquals(contact, CallDebugTracker.activeSession.value?.announcedName)
        assertEquals("CONTACT", CallDebugTracker.activeSession.value?.announcementSource)

        CallDebugTracker.onCallEnded()
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        assertEquals(number, CallDebugTracker.sessionHistory.value[0].number)
        assertEquals(contact, CallDebugTracker.sessionHistory.value[0].contactName)
    }

    // 33. Test Scenario C — Listener arrives first with number + CONTACT, later PHONE_STATE has number = null (12:56 TEST)
    @Test
    fun testScenarioC_listenerArrivesFirst_thenPhoneStateNoNumber_noDowngrade() {
        val number = "+919816939576"
        val contact = "Mummy Ji"

        // Event 1: PHONE_STATE_LISTENER receives number first
        CallDebugTracker.onCallDetected(number, "PHONE_STATE_LISTENER")
        val session1 = CallDebugTracker.activeSession.value
        assertNotNull(session1)
        assertEquals(1L, session1?.id)
        assertEquals(number, session1?.phoneNumber)

        // Contact lookup completes
        CallDebugTracker.onContactLookupResult(number, contact)
        CallDebugTracker.onIdentitySelected(number, contact, "CONTACT")
        assertEquals(contact, session1?.contactName)
        assertEquals("CONTACT", session1?.announcementSource)
        assertEquals(contact, session1?.announcedName)

        // Event 2: PHONE_STATE arrives 185ms later with number = null (Android broadcast)
        CallDebugTracker.onCallDetected(null, "PHONE_STATE")

        // CRITICAL CHECK: Still Session #1! No Session #2!
        val sessionAfter = CallDebugTracker.activeSession.value
        assertEquals("Must remain the SAME session #1", session1?.id, sessionAfter?.id)
        assertEquals("Must NOT create a second session in history", 1, CallDebugTracker.sessionHistory.value.size)
        assertEquals("Phone number must NOT be overwritten with null", number, sessionAfter?.phoneNumber)

        // Attempted downgrade by a later UNKNOWN identity selection must be rejected!
        CallDebugTracker.onIdentitySelected(null, "Unknown caller", "UNKNOWN")
        assertEquals("CONTACT must remain sticky and NOT be downgraded by UNKNOWN", contact, sessionAfter?.announcedName)
        assertEquals("Source must remain CONTACT", "CONTACT", sessionAfter?.announcementSource)

        // TTS starts for Mummy Ji
        CallDebugTracker.onAnnouncementPrepared(contact, "Incoming call from $contact", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from $contact")

        CallDebugTracker.onCallEnded()
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        val finalHistory = CallDebugTracker.sessionHistory.value[0]
        assertEquals(contact, finalHistory.announcedName)
        assertEquals("CONTACT", finalHistory.announcementSource)
        assertEquals(number, finalHistory.number)
    }

    // 34. Test Scenario D — Truecaller later: Contact = Mummy Ji, later Truecaller = Mummy Ji -> source remains CONTACT
    @Test
    fun testScenarioD_truecallerLater_sourceRemainsContact() {
        val number = "+919816939576"
        val contact = "Mummy Ji"
        val tc = "Mummy Ji"

        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, contact)
        CallDebugTracker.onIdentitySelected(number, contact, "CONTACT")

        // Later Truecaller arrives
        CallDebugTracker.onTruecallerResult(number, tc)
        CallDebugTracker.onIdentitySelected(number, tc, "TRUECALLER")

        val active = CallDebugTracker.activeSession.value
        assertEquals("Source must remain CONTACT", "CONTACT", active?.announcementSource)
        assertEquals("Announced name must remain Mummy Ji", contact, active?.announcedName)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
    }

    // 35. Test Scenario E — No identity: no number, no contact, no Truecaller -> Unknown caller
    @Test
    fun testScenarioE_noIdentity_unknownCallerOnly() {
        // No number from any source
        CallDebugTracker.onCallDetected(null, "PHONE_STATE")
        CallDebugTracker.onIdentitySelected(null, "Unknown caller", "UNKNOWN")
        CallDebugTracker.onAnnouncementPrepared("Unknown caller", "Incoming call from Unknown caller", "UNKNOWN")
        CallDebugTracker.onTtsStarted("Incoming call from Unknown caller")
        CallDebugTracker.onCallEnded()

        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        val history = CallDebugTracker.sessionHistory.value[0]
        assertEquals(1L, history.sessionId)
        assertNull(history.number)
        assertNull(history.contactName)
        assertNull(history.truecallerName)
        assertEquals("Unknown caller", history.announcedName)
        assertEquals("UNKNOWN", history.announcementSource)
    }

    // 36. Bug detector: IDENTITY_DOWNGRADE is flagged when UNKNOWN tries to overwrite CONTACT
    @Test
    fun testIdentityDowngradePreventedAndLogged() {
        val number = "+919816939576"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        CallDebugTracker.onContactLookupResult(number, "Mummy Ji")
        CallDebugTracker.onIdentitySelected(number, "Mummy Ji", "CONTACT")

        // Attempt downgrade to UNKNOWN
        CallDebugTracker.onIdentitySelected(null, "Unknown caller", "UNKNOWN")

        val active = CallDebugTracker.activeSession.value
        assertEquals("Mummy Ji", active?.announcedName)
        assertEquals("CONTACT", active?.announcementSource)
        assertTrue(active?.detectedBugs?.any { it.type == BugType.IDENTITY_DOWNGRADE } == true)
    }
}

