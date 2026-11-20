package com.callhandler.service.identity

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.callhandler.service.core.CallEvent
import com.callhandler.service.core.CallSessionManager
import com.callhandler.service.core.SpamStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class TruecallerNotificationDiagnosticTest {

    @Before
    fun setUp() {
        CallSessionManager.reset()
        TruecallerDiagnosticStore.clear()
    }

    @After
    fun tearDown() {
        CallSessionManager.reset()
        TruecallerDiagnosticStore.clear()
    }

    private fun createMockSbn(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        subText: String? = null,
        category: String? = Notification.CATEGORY_CALL,
        postTimeMs: Long = System.currentTimeMillis(),
        flags: Int = Notification.FLAG_ONGOING_EVENT
    ): StatusBarNotification {
        val sbn = mock(StatusBarNotification::class.java)
        val notif = mock(Notification::class.java)
        val extras = mock(Bundle::class.java)

        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn(title)
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn(text)
        `when`(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).thenReturn(bigText)
        `when`(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).thenReturn(subText)

        notif.extras = extras
        notif.category = category
        notif.flags = flags

        `when`(sbn.notification).thenReturn(notif)
        `when`(sbn.postTime).thenReturn(postTimeMs)
        `when`(sbn.key).thenReturn("0|com.truecaller|100|null|1000")
        `when`(sbn.packageName).thenReturn("com.truecaller")

        return sbn
    }

    @Test
    fun testIncomingCallNotification_correlatedMatch() {
        val now = 100000L
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "+919876543210", timestampMs = now))

        val sbn = createMockSbn(
            title = "Sukhvinder Singh",
            text = "+91 98765 43210 - Incoming call",
            category = Notification.CATEGORY_CALL,
            postTimeMs = now + 400L // Arrives 400ms after GSM ring
        )

        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)

        assertEquals(TruecallerNotifType.INCOMING_CALL, report.notifType)
        assertEquals(TruecallerCorrelationStatus.CORRELATED_MATCH, report.correlationStatus)
        assertEquals("Sukhvinder Singh", report.extractedCallerName)
        assertEquals(400L, report.latencyFromGsmRingMs)
        assertEquals(SpamStatus.NORMAL, report.spamStatus)
        assertTrue(report.isOngoing)
        assertTrue(report.extractedPhoneNumberRedacted?.contains("***") == true)
    }

    @Test
    fun testNumberMismatch_preventsCrossContamination() {
        val now = 100000L
        // Current GSM ringing call is from number A
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "+919876543210", timestampMs = now))

        // Truecaller notification arrives with number B
        val sbn = createMockSbn(
            title = "Different Caller",
            text = "+91 70183 08746 - Incoming call",
            category = Notification.CATEGORY_CALL,
            postTimeMs = now + 500L
        )

        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)

        assertEquals(TruecallerNotifType.INCOMING_CALL, report.notifType)
        assertEquals(TruecallerCorrelationStatus.NUMBER_MISMATCH, report.correlationStatus)
    }

    @Test
    fun testStaleNotification_rejected() {
        val gsmStart = 100000L
        CallSessionManager.processEvent(CallEvent.GsmRinging(number = "+919876543210", timestampMs = gsmStart))

        // Truecaller notification with post time 5 seconds BEFORE the GSM call
        val sbn = createMockSbn(
            title = "Old Notification",
            text = "+91 98765 43210",
            category = Notification.CATEGORY_CALL,
            postTimeMs = gsmStart - 5000L
        )

        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)

        assertEquals(TruecallerCorrelationStatus.STALE_NOTIFICATION, report.correlationStatus)
    }

    @Test
    fun testMissedCallNotification_classifiedCorrectly() {
        val sbn = createMockSbn(
            title = "Missed call",
            text = "Missed call from Ramesh (+91 98111 22233)",
            category = null,
            flags = 0
        )

        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)

        assertEquals(TruecallerNotifType.MISSED_CALL, report.notifType)
        assertEquals(TruecallerCorrelationStatus.NO_ACTIVE_GSM_CALL, report.correlationStatus)
    }

    @Test
    fun testPromotionalNoiseNotification_classifiedCorrectly() {
        val sbn = createMockSbn(
            title = "Who viewed your profile",
            text = "5 people searched for you today. Upgrade to Premium.",
            category = null,
            flags = 0
        )

        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)

        assertEquals(TruecallerNotifType.PROMOTIONAL_NOISE, report.notifType)
    }

    @Test
    fun testSpamDetectionInNotification() {
        val sbn = createMockSbn(
            title = "Telemarketing Sales",
            text = "Suspected Spam (+91 140 1234567)",
            category = Notification.CATEGORY_CALL
        )

        val report = TruecallerNotificationDiagnosticInspector.inspect(sbn)

        assertEquals(SpamStatus.SPAM, report.spamStatus)
    }

    @Test
    fun testDiagnosticStore_recordsAndGeneratesReport() {
        val sbn = createMockSbn(
            title = "Test Caller",
            text = "+91 98765 43210",
            category = Notification.CATEGORY_CALL
        )
        TruecallerNotificationDiagnosticInspector.inspect(sbn)

        val reports = TruecallerDiagnosticStore.reports.value
        assertEquals(1, reports.size)

        val summary = TruecallerDiagnosticStore.buildFeasibilityReport()
        assertTrue(summary.contains("TRUECALLER NOTIFICATIONLISTENER FEASIBILITY"))
        assertTrue(summary.contains("Total notifications: 1"))
    }

    @Test
    fun testStrictNumberMatching_noAmbiguityOrGuessing() {
        // Standard 10-digit national match across differing country codes
        assertTrue(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                "+919816939576", "09816939576"
            )
        )
        assertTrue(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                "+91 98169 39576", "9816939576"
            )
        )

        // Ambiguous short code suffix match MUST be rejected (Requirement 8)
        assertFalse(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                "1234", "9876541234"
            )
        )
        assertFalse(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                "911", "9876540911"
            )
        )

        // Different 10-digit numbers rejected
        assertFalse(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                "9816939576", "9816939577"
            )
        )

        // Null or blank rejected
        assertFalse(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                null, "9816939576"
            )
        )
        assertFalse(
            TruecallerNotificationDiagnosticInspector.areNumbersStrictlyMatching(
                "", ""
            )
        )
    }
}
