package com.palli.mobile.ui.battery

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BatteryExemptionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    /** The screens ask from an activity, which is what lets startActivity go without a new task. */
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private fun exempt(on: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, on)

    @Test fun `limited is what the power manager says, and false for an app that is not limited`() {
        exempt(false)
        assertTrue(BatteryExemption.limited(activity))
        exempt(true)
        assertFalse(BatteryExemption.limited(activity))
    }

    @Test fun `the question names this app`() {
        assertTrue(BatteryExemption.ask(activity))
        val asked = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, asked.action)
        assertEquals("package:${app.packageName}", asked.dataString)
        assertNull(shadowOf(activity).nextStartedActivity)                       // and only that one
    }

    @Test fun `a phone without the question gets the list of apps instead`() {
        shadowOf(app).checkActivities(true)
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings"; name = "Apps" } })

        assertTrue(BatteryExemption.ask(activity))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test fun `a phone with neither says no, and nothing is started`() {
        shadowOf(app).checkActivities(true)
        assertFalse(BatteryExemption.ask(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
