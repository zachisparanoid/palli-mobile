package com.palli.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Her messages sit in Room in plain text: nothing of the app's may go to a cloud backup or travel to another phone in a
 * device-to-device transfer. `allowBackup="false"` covers cloud backup only on Android 12+, so the extraction rules
 * exclude everything from both, and the older full-backup rules exclude everything too.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val everyDomain = setOf("root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref")

    /** Each section of [xml] (or the whole of it, for full-backup-content) and the domains it excludes whole. */
    private fun sections(xml: Int): Map<String, Set<String>> {
        val parser = context.resources.getXml(xml)
        val found = mutableMapOf<String, MutableSet<String>>()
        var section = "full-backup-content"
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer", "full-backup-content" -> { section = parser.name; found.getOrPut(section) { mutableSetOf() } }
                "include" -> error("$section includes ${parser.getAttributeValue(null, "domain")}")
                "exclude" -> if (parser.getAttributeValue(null, "path") == ".") {
                    found.getOrPut(section) { mutableSetOf() } += parser.getAttributeValue(null, "domain")
                }
            }
        }
        return found
    }

    @Test fun `nothing goes to a cloud backup or to another phone`() {
        val rules = sections(R.xml.data_extraction_rules)
        assertEquals(setOf("cloud-backup", "device-transfer"), rules.keys)
        rules.forEach { (section, excluded) -> assertEquals(section, everyDomain, excluded) }
    }

    @Test fun `nor to a backup on Android 11 and older`() {
        assertEquals(mapOf("full-backup-content" to everyDomain), sections(R.xml.backup_rules))
    }

    @Test fun `the manifest names both rules and keeps backup off`() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}
