package com.callhandler.service.identity

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class TruecallerParserTest {

    private fun createMockNode(
        text: String? = null,
        viewId: String? = null,
        className: String = "android.widget.TextView",
        isClickable: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo {
        val node = mock(AccessibilityNodeInfo::class.java)
        `when`(node.text).thenReturn(text)
        `when`(node.viewIdResourceName).thenReturn(viewId)
        `when`(node.className).thenReturn(className)
        `when`(node.isClickable).thenReturn(isClickable)
        `when`(node.childCount).thenReturn(children.size)
        children.forEachIndexed { index, child ->
            `when`(node.getChild(index)).thenReturn(child)
        }
        return node
    }

    @Test
    fun testOnboardingScreen_returnsNull() {
        val nodeTitle = createMockNode(
            text = "Protect your family from scams",
            viewId = "android:id/onboarding_title"
        )
        val nodeSubtitle = createMockNode(
            text = "Easily set up and manage spam protection for loved ones, ensuring everyone stays safe from fraudsters.",
            viewId = "android:id/onboarding_subtitle"
        )
        val nodeAccept = createMockNode(
            text = "Get started",
            viewId = "android:id/onboarding_accept-text",
            isClickable = true
        )
        val nodeSkip = createMockNode(
            text = "Skip",
            viewId = "android:id/skip",
            className = "android.widget.Button",
            isClickable = true
        )

        val root = createMockNode(
            children = listOf(nodeTitle, nodeSubtitle, nodeAccept, nodeSkip)
        )

        val result = TruecallerParser.parse(root)
        assertNull("Onboarding screen must return null", result)
    }

    @Test
    fun testHomeScreenWithBottomNav_returnsNull() {
        val navNode = createMockNode(
            viewId = "com.truecaller:id/bottom_navigation",
            text = "Calls"
        )
        val searchNode = createMockNode(
            viewId = "com.truecaller:id/search_bar",
            text = "Search numbers, names & more"
        )
        val root = createMockNode(
            children = listOf(navNode, searchNode)
        )

        val result = TruecallerParser.parse(root)
        assertNull("Home screen with navigation must return null", result)
    }

    @Test
    fun testCallHistoryScreen_returnsNull() {
        val item1Name = createMockNode(text = "Blinkit")
        val item1Label = createMockNode(text = "Promotional call")
        val item1Time = createMockNode(text = "Yesterday")

        val item2Name = createMockNode(text = "Mummy Ji")
        val item2Time = createMockNode(text = "Yesterday")

        val root = createMockNode(
            children = listOf(item1Name, item1Label, item1Time, item2Name, item2Time)
        )

        val result = TruecallerParser.parse(root)
        assertNull("Call history screen must return null", result)
    }

    @Test
    fun testDirectIdCallerOverlay_extractsNameAndNumber() {
        val nameNode = createMockNode(
            text = "Sukhvinder Singh",
            viewId = "com.truecaller:id/nameOrNumber"
        )
        val phoneNode = createMockNode(
            text = "098171 62931",
            viewId = "com.truecaller:id/phoneNumber"
        )
        val root = createMockNode(
            children = listOf(nameNode, phoneNode)
        )

        `when`(root.findAccessibilityNodeInfosByViewId("com.truecaller:id/nameOrNumber"))
            .thenReturn(listOf(nameNode))
        `when`(root.findAccessibilityNodeInfosByViewId("com.truecaller:id/phoneNumber"))
            .thenReturn(listOf(phoneNode))

        val result = TruecallerParser.parse(root)
        assertEquals("Sukhvinder Singh", result?.name)
        assertEquals("098171 62931", result?.phoneNumber)
        assertEquals("Sukhvinder Singh", result?.announcementName)
    }

    @Test
    fun testDirectIdSpamCallerOverlay_extractsSpamAnnouncement() {
        val nameNode = createMockNode(
            text = "ABC Telecom",
            viewId = "com.truecaller:id/nameOrNumber"
        )
        val labelNode = createMockNode(
            text = "Possible spam",
            viewId = "com.truecaller:id/label"
        )
        val root = createMockNode(
            children = listOf(nameNode, labelNode)
        )

        `when`(root.findAccessibilityNodeInfosByViewId("com.truecaller:id/nameOrNumber"))
            .thenReturn(listOf(nameNode))
        `when`(root.findAccessibilityNodeInfosByViewId("com.truecaller:id/label"))
            .thenReturn(listOf(labelNode))

        val result = TruecallerParser.parse(root)
        assertEquals("ABC Telecom", result?.name)
        assertTrue(result?.isSpam == true)
        assertEquals("Spam call from ABC Telecom", result?.announcementName)
    }

    @Test
    fun testSpamCallFromPrefix_extractsCleanName() {
        val nameNode = createMockNode(
            text = "Spam call from ABC Telecom",
            viewId = "com.truecaller:id/nameOrNumber"
        )
        val root = createMockNode(
            children = listOf(nameNode)
        )

        `when`(root.findAccessibilityNodeInfosByViewId("com.truecaller:id/nameOrNumber"))
            .thenReturn(listOf(nameNode))

        val result = TruecallerParser.parse(root)
        assertEquals("ABC Telecom", result?.name)
        assertTrue(result?.isSpam == true)
        assertEquals("Spam call from ABC Telecom", result?.announcementName)
    }
}
