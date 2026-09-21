package com.callhandler.service.identity

import android.app.Notification
import android.app.PendingIntent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class VoipCallDetectorTest {

    @Before
    fun setup() {
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

    @Test
    fun testOutgoingWhatsAppCall_dialingState_isBlocked() {
        val sbn = createMockSbn(
            key = "key_out_1",
            title = "Tushar",
            text = "Calling...",
            actions = listOf(createAction("End call"))
        )

        val result = VoipCallDetector.detect(sbn)
        assertNull("Outgoing dialing state must return null (announcement blocked)", result)
    }

    @Test
    fun testOutgoingWhatsAppCall_transitionFromCallingToRinging_isBlocked() {
        val key = "key_out_2"

        // 1. Dialing
        val sbnDialing = createMockSbn(
            key = key,
            title = "Tushar",
            text = "Calling...",
            actions = listOf(createAction("End call"))
        )
        val res1 = VoipCallDetector.detect(sbnDialing)
        assertNull("Outgoing dialing must be blocked", res1)

        // 2. Callee phone is ringing
        val sbnRinging = createMockSbn(
            key = key,
            title = "Tushar",
            text = "Ringing",
            actions = listOf(createAction("End call"))
        )
        val res2 = VoipCallDetector.detect(sbnRinging)
        assertNull("Outgoing call ringing must be blocked", res2)
    }

    @Test
    fun testOutgoingWhatsAppCall_directRingingWithoutPriorCalling_isBlocked() {
        // Even if initial event observed is "Ringing", absence of Answer action must keep it blocked
        val sbn = createMockSbn(
            key = "key_out_3",
            title = "Tushar",
            text = "Ringing",
            actions = listOf(createAction("End call"))
        )

        val result = VoipCallDetector.detect(sbn)
        assertNull("Outgoing ringing without answer action must be blocked", result)
    }

    @Test
    fun testOutgoingWhatsAppCall_activeDuration_isBlocked() {
        val key = "key_out_4"
        val sbnDialing = createMockSbn(
            key = key,
            title = "Tushar",
            text = "Calling...",
            actions = listOf(createAction("End call"))
        )
        VoipCallDetector.detect(sbnDialing)

        val sbnActive = createMockSbn(
            key = key,
            title = "Tushar",
            text = "0:15",
            actions = listOf(createAction("End call"))
        )
        val result = VoipCallDetector.detect(sbnActive)
        assertNull("Active outgoing call duration must be blocked", result)
    }

    @Test
    fun testIncomingWhatsAppCall_ringing_isAllowed() {
        val sbn = createMockSbn(
            key = "key_in_1",
            title = "Tushar",
            text = "Incoming voice call",
            actions = listOf(
                createAction("Decline", semanticAction = 2),
                createAction("Answer", semanticAction = 1)
            )
        )

        val result = VoipCallDetector.detect(sbn)
        assertNotNull("Incoming voice call must be detected", result)
        assertEquals("WhatsApp", result?.appDisplayName)
        assertEquals("voice call", result?.callType)
        assertEquals("Tushar", result?.callerName)
        assertEquals(VoipCallDirection.INCOMING, result?.direction)
        assertEquals(VoipCallState.INCOMING_RINGING, result?.state)
        assertEquals("WhatsApp voice call from Tushar", result?.toAnnouncementText())
    }

    @Test
    fun testIncomingWhatsAppCall_duplicateUpdate_isBlocked() {
        val key = "key_in_2"
        val sbn1 = createMockSbn(
            key = key,
            title = "Tushar",
            text = "Incoming voice call",
            actions = listOf(
                createAction("Decline", semanticAction = 2),
                createAction("Answer", semanticAction = 1)
            )
        )
        val res1 = VoipCallDetector.detect(sbn1)
        assertNotNull("First incoming event must be allowed", res1)

        // Duplicate update while ringing
        val sbn2 = createMockSbn(
            key = key,
            title = "Tushar",
            text = "Incoming voice call",
            actions = listOf(
                createAction("Decline", semanticAction = 2),
                createAction("Answer", semanticAction = 1)
            )
        )
        val res2 = VoipCallDetector.detect(sbn2)
        assertNull("Subsequent incoming update must be blocked (already announced)", res2)
    }

    @Test
    fun testIncomingWhatsAppCall_answeredDuration_isBlocked() {
        val key = "key_in_3"
        val sbn1 = createMockSbn(
            key = key,
            title = "Tushar",
            text = "Incoming voice call",
            actions = listOf(
                createAction("Decline", semanticAction = 2),
                createAction("Answer", semanticAction = 1)
            )
        )
        VoipCallDetector.detect(sbn1)

        val sbnActive = createMockSbn(
            key = key,
            title = "Tushar",
            text = "0:01",
            actions = listOf(createAction("End call"))
        )
        val resActive = VoipCallDetector.detect(sbnActive)
        assertNull("Answered active call must be blocked", resActive)
    }

    @Test
    fun testAmbiguousNotification_withoutIncomingEvidence_isBlocked() {
        val sbn = createMockSbn(
            key = "key_ambiguous",
            title = "Tushar",
            text = "Call",
            actions = emptyList()
        )

        val result = VoipCallDetector.detect(sbn)
        assertNull("Ambiguous notification must be blocked (fail-closed)", result)
    }

    @Test
    fun testUnrelatedMessage_isBlocked() {
        val sbn = createMockSbn(
            key = "key_msg",
            title = "Tushar",
            text = "Hey, call me when free",
            category = Notification.CATEGORY_MESSAGE,
            actions = emptyList()
        )

        val result = VoipCallDetector.detect(sbn)
        assertNull("Unrelated text message must be blocked", result)
    }
}
