package com.palli.mobile.ui.settings

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.test.core.app.ApplicationProvider
import com.palli.mobile.data.session.Session
import com.palli.mobile.ui.battery.BatteryExemption
import com.palli.mobile.ui.theme.PalliTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The settings screen on Robolectric, over a SettingsViewModel whose stored choice and unpairing are stand-ins. */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val session = MutableStateFlow<Session?>(Session("http://palli-box.example.ts.net:8000", "pmd_test", 1, "Pixel"))
    private val stored = MutableStateFlow(true)
    private val written = mutableListOf<Boolean>()
    private var unpairs = 0
    private var backs = 0

    private fun show(exempt: Boolean = false) {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, exempt)
        val vm = SettingsViewModel(
            session = session,
            stayConnectedSetting = stored,
            writeStayConnected = { on -> written += on; stored.value = on },
            unpairThisPhone = { unpairs++ },
        )
        compose.setContent { PalliTheme { SettingsScreen(onBack = { backs++ }, vm = vm) } }
    }

    private fun assertAbsent(text: String) = assertEquals(0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    private fun nextStarted(): Intent? = shadowOf(compose.activity).nextStartedActivity

    // ---- which PaLLi ----

    @Test fun `it says which PaLLi and which phone this is`() {
        show()
        compose.onNodeWithText("http://palli-box.example.ts.net:8000").assertIsDisplayed()
        compose.onNodeWithText("This phone: Pixel").assertIsDisplayed()
    }

    // ---- staying connected ----

    @Test fun `the switch shows the stored choice, and moving it writes the choice down`() {
        show()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), written)
        compose.onNode(isToggleable()).assertIsOff()

        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), written)
    }

    @Test fun `a choice stored as off shows as off`() {
        stored.value = false
        show()
        compose.onNode(isToggleable()).assertIsOff()
    }

    // ---- the battery ----

    @Test fun `when Android is limiting the app it says so, and offers the system's question`() {
        show(exempt = false)
        compose.onNodeWithText("Android may pause the connection to save battery, which delays her messages.").assertIsDisplayed()
        compose.onNodeWithText("Let PaLLi run in the background").performScrollTo().performClick()

        val asked = nextStarted()!!
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, asked.action)
        assertEquals("package:${app.packageName}", asked.dataString)
    }

    @Test fun `when the app is not limited it says so and has nothing to ask`() {
        show(exempt = true)
        compose.onNodeWithText("Not limited — her messages can arrive at once.").assertIsDisplayed()
        assertAbsent("Let PaLLi run in the background")
    }

    @Test fun `a phone with no screen for the battery question says so, instead of failing`() {
        shadowOf(app).checkActivities(true)                          // nothing on this "phone" can take the intent
        show(exempt = false)
        compose.onNodeWithText("Let PaLLi run in the background").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(BatteryExemption.NO_SCREEN).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(BatteryExemption.NO_SCREEN).assertIsDisplayed()
    }

    @Test fun `notification settings opens this app's page in the system settings`() {
        show()
        compose.onNodeWithText("Notification settings").performScrollTo().performClick()
        val opened = nextStarted()!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    // ---- unpairing ----

    @Test fun `unpairing asks first, and cancel unpairs nothing`() {
        show()
        compose.onNodeWithText("Unpair this phone").performScrollTo().performClick()
        compose.onNodeWithText("Unpair this phone?").assertIsDisplayed()
        compose.onNodeWithText(
            "PaLLi's messages stop coming here until you pair again. The conversation and files on this phone are cleared; " +
                "they stay on PaLLi's machine.").assertIsDisplayed()
        assertEquals(0, unpairs)                                     // asking does not unpair

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertAbsent("Unpair this phone?")
        assertEquals(0, unpairs)
        compose.onNodeWithText("Unpair this phone").assertIsEnabled()
    }

    @Test fun `saying yes unpairs once, and the button then shows it is under way`() {
        show()
        compose.onNodeWithText("Unpair this phone").performScrollTo().performClick()
        compose.onNodeWithText("Unpair").performClick()
        compose.waitForIdle()

        assertEquals(1, unpairs)
        assertAbsent("Unpair this phone?")
        compose.onNodeWithText("Unpairing…").assertIsNotEnabled()
    }

    @Test fun `back goes back`() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        assertNull(nextStarted())
        assertTrue(written.isEmpty())
    }
}
