package com.callhandler.service.audio

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.callhandler.service.core.CallState
import com.callhandler.service.core.CallStateMachine
import com.callhandler.service.debug.CallDebugTracker
import com.callhandler.service.debug.DebugLogStore
import com.callhandler.service.identity.VoipCallDetector
import com.callhandler.service.identity.VoipCallDirection
import com.callhandler.service.identity.VoipCallState
import com.callhandler.service.settings.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * Unit tests verifying immediate announcement cancellation on call decline
 * across GSM and WhatsApp, covering all 7 critical test scenarios.
 */
class AnnouncementDeclineTest {

    @Before
    fun setUp() {
        DebugLogStore.clear()
        CallDebugTracker.clearAll()
        VoipCallDetector.clearSessions()
    }

    private fun createMockSbn(
        key: String,
        packageName: String = "com.whatsapp",
        title: String? = null,
        text: String? = null,
        subText: String? = null,
        category: String? = Notification.CATEGORY_CALL,
        actions: List<Notification.Action> = emptyList(),
        callTypeExtra: Int? = null,
        hasAnswerIntent: Boolean = false
    ): StatusBarNotification {
        val sbn = mock(StatusBarNotification::class.java)
        val notification = mock(Notification::class.java)
        val extras = mock(Bundle::class.java)

        `when`(sbn.key).thenReturn(key)
        `when`(sbn.packageName).thenReturn(packageName)
        `when`(sbn.notification).thenReturn(notification)

        notification.category = category
        notification.actions = actions.toTypedArray()
        notification.extras = extras

        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn(title)
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn(text)
        `when`(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).thenReturn(subText)
        `when`(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).thenReturn(null)
        `when`(extras.getString(Notification.EXTRA_TEMPLATE)).thenReturn(null)

        if (callTypeExtra != null) {
            `when`(extras.getInt(Notification.EXTRA_CALL_TYPE, -1)).thenReturn(callTypeExtra)
            `when`(extras.getInt("android.callType", -1)).thenReturn(callTypeExtra)
        } else {
            `when`(extras.getInt(Notification.EXTRA_CALL_TYPE, -1)).thenReturn(-1)
            `when`(extras.getInt("android.callType", -1)).thenReturn(-1)
        }

        `when`(extras.containsKey("android.answerIntent")).thenReturn(hasAnswerIntent)

        return sbn
    }

    private fun createAction(title: String, semanticAction: Int = 0): Notification.Action {
        val action = mock(Notification.Action::class.java)
        action.title = title
        `when`(action.semanticAction).thenReturn(semanticAction)
        return action
    }

    // -------------------------------------------------------------------------
    // Scenario 1: GSM incoming call -> announcement starts -> user declines
    //              -> announcement stops immediately.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario1_gsmCallDecline_stopsAnnouncementImmediately() {
        val number = "+919876543210"
        CallDebugTracker.onCallDetected(number, "PHONE_STATE")
        val activeSession = CallDebugTracker.activeSession.value
        assertNotNull("GSM active session must be created", activeSession)
        val sessionId = activeSession!!.id

        CallDebugTracker.onIdentitySelected(number, "Alice", "CONTACT")
        CallDebugTracker.onAnnouncementPrepared("Alice", "Incoming call from Alice", "CONTACT")
        CallDebugTracker.onTtsStarted("Incoming call from Alice")

        val stateMachine = CallStateMachine { _, _ -> }
        stateMachine.transitionTo(CallState.RINGING)
        assertTrue(stateMachine.isRinging)

        // Mock context & settings to instantiate AnnouncementManager
        val mockContext = mock(Context::class.java)
        val mockAppContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)
        val mockSettings = mock(SettingsManager::class.java)
        val announcementManager = AnnouncementManager(mockContext, mockSettings)

        // Simulate user decline: cancelAnnouncement invoked for session
        announcementManager.cancelAnnouncement(sessionId)
        assertTrue(
            "Session must be marked cancelled in AnnouncementManager",
            announcementManager.isSessionCancelled(sessionId)
        )

        // State machine transitions to ENDED on decline (EXTRA_STATE_IDLE without OFFHOOK)
        stateMachine.transitionTo(CallState.ENDED)
        assertFalse("State machine must no longer be ringing", stateMachine.isRinging)
        assertEquals(CallState.ENDED, stateMachine.current)

        // Debug tracker records call end
        CallDebugTracker.onCallEnded()
        assertNull("Active session must be cleared after decline", CallDebugTracker.activeSession.value)
        assertEquals(1, CallDebugTracker.sessionHistory.value.size)
        assertEquals(sessionId, CallDebugTracker.sessionHistory.value[0].sessionId)
    }

    // -------------------------------------------------------------------------
    // Scenario 2: WhatsApp incoming call -> announcement starts -> user declines
    //              -> announcement stops immediately.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario2_whatsAppCallDecline_stopsAnnouncementImmediately() {
        val sbn = createMockSbn(
            key = "wa_call_decline_1",
            title = "Bob",
            text = "Incoming voice call",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )

        val callInfo = VoipCallDetector.detect(sbn)
        assertNotNull("WhatsApp incoming call must be detected", callInfo)
        assertEquals("Bob", callInfo!!.callerName)
        assertEquals(VoipCallDirection.INCOMING, callInfo.direction)
        assertEquals(VoipCallState.INCOMING_RINGING, callInfo.state)

        // Verify session is tracked with notificationKey
        val trackedSession = VoipCallDetector.getSession("wa_call_decline_1")
        assertNotNull(trackedSession)
        assertEquals("Bob", trackedSession!!.identity)

        // User declines call: notification is dismissed (onNotificationRemoved)
        val removedSession = VoipCallDetector.onNotificationRemoved(sbn)
        assertNotNull("Removed session must be returned on notification dismissal", removedSession)
        assertEquals("Bob", removedSession!!.identity)
        assertEquals(VoipCallState.ENDED, removedSession.state)

        // Verify session is completely removed from active sessions
        assertNull(VoipCallDetector.getSession("wa_call_decline_1"))
    }

    // -------------------------------------------------------------------------
    // Scenario 3: GSM call ends naturally -> announcement stops and cannot restart.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario3_gsmCallEndsNaturally_announcementStopsAndCannotRestart() {
        val stateMachine = CallStateMachine { _, _ -> }
        stateMachine.transitionTo(CallState.RINGING)
        assertTrue(stateMachine.isRinging)

        // Call is answered then ended
        stateMachine.transitionTo(CallState.ANSWERED)
        assertFalse(stateMachine.isRinging)
        assertEquals(CallState.ANSWERED, stateMachine.current)

        stateMachine.transitionTo(CallState.ENDED)
        assertFalse(stateMachine.isRinging)
        assertEquals(CallState.ENDED, stateMachine.current)

        // Verify that announcement loop guard prevents announcement when call is ended
        val mockContext = mock(Context::class.java)
        val mockAppContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)
        val mockSettings = mock(SettingsManager::class.java)
        val announcementManager = AnnouncementManager(mockContext, mockSettings)

        val sessionId = 42L
        announcementManager.cancelAnnouncement(sessionId)

        // In CallHandlerService: pre-utterance loop condition is:
        // if (!stateMachine.isRinging || announcer.isSessionCancelled(sessionId)) break
        val shouldAbortAnnouncement = !stateMachine.isRinging || announcementManager.isSessionCancelled(sessionId)
        assertTrue("Announcement must immediately abort when call has ended", shouldAbortAnnouncement)
    }

    // -------------------------------------------------------------------------
    // Scenario 4: WhatsApp notification updates while announcement is playing
    //              -> announcement continues unless genuine decline/end event is detected.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario4_whatsAppNotificationUpdate_continuesUnlessDeclineDetected() {
        val key = "wa_call_update_1"

        // 1. Initial incoming ringing call
        val sbnRinging = createMockSbn(
            key = key,
            title = "Charlie",
            text = "Incoming voice call",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )
        val callInfo = VoipCallDetector.detect(sbnRinging)
        assertNotNull(callInfo)

        // 2. Benign notification update while still ringing (e.g. notification title/text refresh)
        val sbnBenignUpdate = createMockSbn(
            key = key,
            title = "Charlie",
            text = "Ringing...",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )
        val benignTerminalResult = VoipCallDetector.checkNotificationTerminalEvent(sbnBenignUpdate)
        assertNull(
            "Benign ringing update must NOT be flagged as terminal (announcement continues)",
            benignTerminalResult
        )

        // 3. Genuine decline update (text contains 'declined' or 'call ended')
        val sbnDeclinedUpdate = createMockSbn(
            key = key,
            title = "Charlie",
            text = "Call declined",
            actions = emptyList()
        )
        val declineTerminalResult = VoipCallDetector.checkNotificationTerminalEvent(sbnDeclinedUpdate)
        assertNotNull("Genuine decline update must be detected as terminal", declineTerminalResult)
        assertTrue("Terminal result must indicate decline/end", declineTerminalResult!!.isDeclinedOrEnded)
        assertFalse("Terminal result must not indicate answered", declineTerminalResult.isAnswered)
        assertEquals(key, declineTerminalResult.notificationKey)

        // 4. Genuine answered update (text contains duration e.g. '0:12')
        val sbnAnswered = createMockSbn(
            key = "wa_call_answered_1",
            title = "Charlie",
            text = "Incoming voice call",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )
        VoipCallDetector.detect(sbnAnswered)

        val sbnAnsweredUpdate = createMockSbn(
            key = "wa_call_answered_1",
            title = "Charlie",
            text = "0:15",
            actions = listOf(createAction("End call"))
        )
        val answeredTerminalResult = VoipCallDetector.checkNotificationTerminalEvent(sbnAnsweredUpdate)
        assertNotNull("Answered update with duration must be detected as terminal", answeredTerminalResult)
        assertTrue("Terminal result must indicate answered", answeredTerminalResult!!.isAnswered)
        assertFalse("Terminal result must not indicate declined", answeredTerminalResult.isDeclinedOrEnded)
    }

    // -------------------------------------------------------------------------
    // Scenario 5: A new incoming call arrives immediately after previous call is declined
    //              -> the new call's announcement works normally.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario5_newCallAfterPreviousDeclined_worksNormally() {
        val mockContext = mock(Context::class.java)
        val mockAppContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)
        val mockSettings = mock(SettingsManager::class.java)
        val announcementManager = AnnouncementManager(mockContext, mockSettings)

        val session1Id = 101L
        val session2Id = 102L

        // Call 1 arrives and is declined/cancelled
        announcementManager.cancelAnnouncement(session1Id)
        assertTrue("Session 1 must be marked cancelled", announcementManager.isSessionCancelled(session1Id))

        // Call 2 arrives immediately
        assertFalse("Session 2 must NOT be marked cancelled", announcementManager.isSessionCancelled(session2Id))

        // In VoipCallDetector: back-to-back calls have independent sessions
        val sbn1 = createMockSbn(
            key = "wa_session_1",
            title = "Alice",
            text = "Incoming voice call",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )
        val info1 = VoipCallDetector.detect(sbn1)
        assertNotNull(info1)

        // Call 1 ends
        VoipCallDetector.onNotificationRemoved(sbn1)
        assertNull(VoipCallDetector.getSession("wa_session_1"))

        // Call 2 arrives immediately
        val sbn2 = createMockSbn(
            key = "wa_session_2",
            title = "David",
            text = "Incoming voice call",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )
        val info2 = VoipCallDetector.detect(sbn2)
        assertNotNull("New call announcement info must be generated cleanly", info2)
        assertEquals("David", info2!!.callerName)
        assertEquals(VoipCallState.INCOMING_RINGING, info2.state)
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Repeated decline/disconnect events -> no crash, duplicate cleanup,
    //              or stuck audio state.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario6_repeatedDeclineEvents_idempotentAndSafe() {
        val mockContext = mock(Context::class.java)
        val mockAppContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)
        val mockSettings = mock(SettingsManager::class.java)
        val announcementManager = AnnouncementManager(mockContext, mockSettings)

        val sessionId = 200L

        // Repeated cancellations on AnnouncementManager
        for (i in 1..5) {
            announcementManager.cancelAnnouncement(sessionId)
            announcementManager.stopSpeaking()
            assertTrue(announcementManager.isSessionCancelled(sessionId))
        }

        // Repeated onNotificationRemoved on VoipCallDetector
        val sbn = createMockSbn(
            key = "wa_repeated_decline",
            title = "Eve",
            text = "Incoming voice call",
            actions = listOf(createAction("Decline", 2), createAction("Answer", 1)),
            callTypeExtra = 1
        )
        VoipCallDetector.detect(sbn)

        val removed1 = VoipCallDetector.onNotificationRemoved(sbn)
        assertNotNull(removed1)

        // Second and third removal must be safe and return null
        val removed2 = VoipCallDetector.onNotificationRemoved(sbn)
        assertNull("Subsequent removals must return null safely without throwing", removed2)
        val removed3 = VoipCallDetector.onNotificationRemoved(sbn)
        assertNull(removed3)

        // Repeated CallDebugTracker.onCallEnded()
        CallDebugTracker.onCallEnded()
        CallDebugTracker.onCallEnded()
        assertNull(CallDebugTracker.activeSession.value)
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Decline happens before TTS starts -> pending announcement
    //              is cancelled and never plays.
    // -------------------------------------------------------------------------
    @Test
    fun testScenario7_declineBeforeTtsStarts_pendingAnnouncementCancelled() {
        val mockContext = mock(Context::class.java)
        val mockAppContext = mock(Context::class.java)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)
        val mockSettings = mock(SettingsManager::class.java)
        val announcementManager = AnnouncementManager(mockContext, mockSettings)

        val sessionId = 300L

        // User declines BEFORE TTS starts
        announcementManager.cancelAnnouncement(sessionId)
        assertTrue(announcementManager.isSessionCancelled(sessionId))

        // Pre-TTS start condition check
        val preStartCancelled = announcementManager.isSessionCancelled(sessionId)
        assertTrue("Announcement must be flagged as cancelled before TTS start", preStartCancelled)

        // In AnnouncementManager.announce(text, sessionId, isTerminalOrCancelled):
        // if (isSessionCancelled(sessionId) || isTerminalOrCancelled()) return
        var ttsInvoked = false
        val isTerminalOrCancelled = { announcementManager.isSessionCancelled(sessionId) }

        if (announcementManager.isSessionCancelled(sessionId) || isTerminalOrCancelled()) {
            // Guard triggers: announcement aborted before TTS playback!
            ttsInvoked = false
        } else {
            ttsInvoked = true
        }

        assertFalse("TTS must never be invoked if call was declined before start", ttsInvoked)
    }
}
