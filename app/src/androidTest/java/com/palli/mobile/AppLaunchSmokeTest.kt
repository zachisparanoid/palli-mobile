package com.palli.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A fresh install starts on Pair: the Hilt graph, Room, DataStore and the theme all come up on a device. */
@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun a_fresh_install_opens_on_pair() {
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Pair with PaLLi")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pair with PaLLi").assertIsDisplayed()
        compose.onNodeWithText("Scan the QR code").assertIsDisplayed()
    }
}
