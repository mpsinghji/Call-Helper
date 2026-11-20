package com.callhandler.service.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AccessibilityConfigTest {

    @Test
    fun testStringsXml_noVcaAndCorrectLabels() {
        val stringsFile = File("src/main/res/values/strings.xml")
        val content = if (stringsFile.exists()) {
            stringsFile.readText()
        } else {
            File("app/src/main/res/values/strings.xml").readText()
        }

        // Verify "VCA" does not exist anywhere in strings.xml
        assertFalse(
            "strings.xml must not contain legacy 'VCA' name",
            content.contains("VCA")
        )

        // Verify truecaller_a11y_label is Caller Identification Service
        assertTrue(
            "truecaller_a11y_label must be set to 'Caller Identification Service'",
            content.contains("<string name=\"truecaller_a11y_label\">Caller Identification Service</string>")
        )
        assertFalse(
            "truecaller_a11y_label must not contain 'Call Handler'",
            content.contains("<string name=\"truecaller_a11y_label\">Call Handler")
        )
        assertFalse(
            "truecaller_a11y_label must not contain 'Truecaller Debug'",
            content.contains("<string name=\"truecaller_a11y_label\">Truecaller Debug")
        )

        // Verify Check update checkbox strings exist
        assertTrue(
            "pref_check_updates_title must be 'Check update'",
            content.contains("<string name=\"pref_check_updates_title\">Check update</string>")
        )
        assertTrue(
            "pref_check_updates_summary must exist",
            content.contains("<string name=\"pref_check_updates_summary\">Automatically check for app updates on startup</string>")
        )
    }

    @Test
    fun testPreferencesXml_containsCheckUpdateCheckbox() {
        val prefFile = File("src/main/res/xml/preferences.xml")
        val content = if (prefFile.exists()) {
            prefFile.readText()
        } else {
            File("app/src/main/res/xml/preferences.xml").readText()
        }

        assertTrue(
            "preferences.xml must declare CheckBoxPreference for updates",
            content.contains("<CheckBoxPreference")
        )
        assertTrue(
            "preferences.xml must use key pref_check_updates_enabled",
            content.contains("android:key=\"pref_check_updates_enabled\"")
        )
        assertTrue(
            "preferences.xml must reference pref_check_updates_title",
            content.contains("android:title=\"@string/pref_check_updates_title\"")
        )
    }

    @Test
    fun testManifest_accessibilityServiceDeclaration() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val content = if (manifestFile.exists()) {
            manifestFile.readText()
        } else {
            File("app/src/main/AndroidManifest.xml").readText()
        }

        assertTrue(
            "Manifest must declare TruecallerAccessibilityService",
            content.contains(".debug.TruecallerAccessibilityService")
        )
        assertTrue(
            "TruecallerAccessibilityService must use truecaller_a11y_label",
            content.contains("android:label=\"@string/truecaller_a11y_label\"")
        )
    }
}
