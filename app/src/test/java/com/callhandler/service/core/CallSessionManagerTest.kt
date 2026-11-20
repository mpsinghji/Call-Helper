package com.callhandler.service.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CallSessionManagerTest {

    private class TestListener : CallSessionListener {
        var startedSession: CallSession? = null
        var lastStateChanged: UnifiedSessionState? = null
        var stopRequestedSession: CallSession? = null
        var stopRequestedReason: String? = null
        var terminatedSession: CallSession? = null
        var stopRequestCount = 0

        override fun onSessionStarted(session: CallSession) {
            startedSession = session
        }

        override fun onSessionStateChanged(session: CallSession, newState: UnifiedSessionState) {
            lastStateChanged = newState
        }

        override fun onAnnouncementStopRequested(session: CallSession, reason: String) {
            stopRequestedSession = session
            stopRequestedReason = reason
            stopRequestCount++
        }

        override fun onSessionTerminated(session: CallSession) {
            terminatedSession = session
        }
    }

    private val testListener = TestListener()

    @Before
    fun setUp() {
        CallSessionManager.reset()
        CallSessionManager.addListener(testListener)
    }

    @After
    fun tearDown() {
        CallSessionManager.removeListener(testListener)
        CallSessionManager.reset()
    }

    @Test
    fun testGsmRinging_startsSession() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "+919876543210"))

        val session = CallSessionManager.currentSession
        assertNotNull("Active session should exist", session)
        assertEquals(CallType.GSM, session?.callType)
        assertEquals("+919876543210", session?.initialNumber)
        assertEquals(UnifiedSessionState.INCOMING, session?.state)
        assertTrue("Session job should be active", session?.sessionJob?.isActive == true)
        assertEquals(session, testListener.startedSession)
    }

    @Test
    fun testGsmDeclined_cancelsSessionJobAndStopsAnnouncement() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "+919876543210"))
        val session = CallSessionManager.currentSession
        assertNotNull(session)

        CallSessionManager.processEvent(CallEvent.GsmDeclinedOrEnded(isDeclined = true))

        assertNull("Active session should be cleared after decline", CallSessionManager.currentSession)
        assertFalse("Session job MUST be cancelled on decline", session!!.sessionJob.isActive)
        assertTrue("Session job should be completed or cancelled", session.sessionJob.isCancelled)
        assertEquals(UnifiedSessionState.DECLINED, session.state)

        assertEquals("Announcement stop must be requested for declined session", session, testListener.stopRequestedSession)
        assertEquals("Declined", testListener.stopRequestedReason)
        assertEquals(session, testListener.terminatedSession)
    }

    @Test
    fun testGsmAnswered_completesSessionJobAndStopsAnnouncement() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "+919876543210"))
        val session = CallSessionManager.currentSession
        assertNotNull(session)

        CallSessionManager.processEvent(CallEvent.GsmAnswered())

        assertNull(CallSessionManager.currentSession)
        assertEquals(UnifiedSessionState.ANSWERED, session!!.state)
        assertTrue(session.sessionJob.isCompleted)
        assertEquals(1, testListener.stopRequestCount)
        assertEquals("Answered", testListener.stopRequestedReason)
    }

    @Test
    fun testVoipIncoming_whatsAppDetection() {
        CallSessionManager.processEvent(
            CallEvent.VoipIncoming(
                key = "0|com.whatsapp|1|null|1000",
                packageName = "com.whatsapp",
                callerName = "John Doe"
            )
        )

        val session = CallSessionManager.currentSession
        assertNotNull(session)
        assertEquals(CallType.WHATSAPP, session?.callType)
        assertEquals("John Doe", session?.callerIdentity?.displayName)
        assertTrue(session?.sessionJob?.isActive == true)
    }

    @Test
    fun testVoipTerminal_stopsAnnouncementAndCancelsJob() {
        val notifKey = "0|com.whatsapp|1|null|1000"
        CallSessionManager.processEvent(
            CallEvent.VoipIncoming(
                key = notifKey,
                packageName = "com.whatsapp",
                callerName = "Alice"
            )
        )
        val session = CallSessionManager.currentSession
        assertNotNull(session)

        CallSessionManager.processEvent(
            CallEvent.VoipTerminal(key = notifKey, isDeclined = true)
        )

        assertNull(CallSessionManager.currentSession)
        assertFalse(session!!.sessionJob.isActive)
        assertTrue(session.sessionJob.isCancelled)
        assertEquals(UnifiedSessionState.DECLINED, session.state)
        assertEquals("VoIP Declined", testListener.stopRequestedReason)
    }

    @Test
    fun testVoipTerminal_mismatchedKeyDoesNotCancelActiveGsmCall() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "123456"))
        val gsmSession = CallSessionManager.currentSession
        assertNotNull(gsmSession)

        CallSessionManager.processEvent(
            CallEvent.VoipTerminal(key = "other_key", isDeclined = true)
        )

        assertEquals("GSM session must remain active", gsmSession, CallSessionManager.currentSession)
        assertTrue(gsmSession!!.sessionJob.isActive)
        assertEquals(0, testListener.stopRequestCount)
    }

    @Test
    fun testIdentityResolved_updatesIdentityAndState() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "9998887776"))
        val session = CallSessionManager.currentSession
        assertNotNull(session)

        val identity = CallerIdentity("9998887776", "Bob Smith", IdentitySource.CONTACT)
        CallSessionManager.processEvent(CallEvent.IdentityResolved(session!!.sessionId, identity))

        assertEquals("Bob Smith", session.callerIdentity?.displayName)
        assertEquals(IdentitySource.CONTACT, session.callerIdentity?.source)
        assertEquals(UnifiedSessionState.ANNOUNCING, session.state)
        assertEquals(UnifiedSessionState.ANNOUNCING, testListener.lastStateChanged)
    }

    @Test
    fun testIdentityResolved_lateCallbackDroppedForOldSession() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "111111"))
        val oldSession = CallSessionManager.currentSession!!
        val oldSid = oldSession.sessionId

        CallSessionManager.processEvent(CallEvent.GsmDeclinedOrEnded(isDeclined = true))

        // Late identity resolution callback for the old session
        val lateIdentity = CallerIdentity("111111", "Late Name", IdentitySource.TRUECALLER)
        CallSessionManager.processEvent(CallEvent.IdentityResolved(oldSid, lateIdentity))

        assertNull("Late callback should not mutate identity after decline", oldSession.callerIdentity)
    }

    @Test
    fun testGsmDeclined_idempotency() {
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "555"))
        val session = CallSessionManager.currentSession!!

        CallSessionManager.processEvent(CallEvent.GsmDeclinedOrEnded(isDeclined = true))
        CallSessionManager.processEvent(CallEvent.GsmDeclinedOrEnded(isDeclined = true))
        CallSessionManager.processEvent(CallEvent.GsmDeclinedOrEnded(isDeclined = true))

        assertNull(CallSessionManager.currentSession)
        assertEquals(1, testListener.stopRequestCount)
    }

    @Test
    fun testGsmCall_preemptsVoipNotification() {
        CallSessionManager.processEvent(
            CallEvent.VoipIncoming(
                key = "voip_key",
                packageName = "com.whatsapp",
                callerName = "WhatsApp Caller"
            )
        )
        val voipSession = CallSessionManager.currentSession
        assertNotNull(voipSession)
        assertEquals(CallType.WHATSAPP, voipSession?.callType)

        // Incoming GSM call arrives while VoIP notification was active
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "911"))
        val gsmSession = CallSessionManager.currentSession
        assertNotNull(gsmSession)
        assertEquals(CallType.GSM, gsmSession?.callType)

        assertFalse("Previous VoIP session job must be cancelled", voipSession!!.sessionJob.isActive)
        assertTrue("New GSM session job must be active", gsmSession!!.sessionJob.isActive)
    }
}
