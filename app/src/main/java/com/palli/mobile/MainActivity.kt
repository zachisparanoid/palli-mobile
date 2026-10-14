package com.palli.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.sync.Notifier
import com.palli.mobile.ui.nav.PalliNavHost
import com.palli.mobile.ui.theme.PalliTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var notifier: Notifier

    /** A palli://pair link this activity was opened with (from a QR scanned elsewhere, or adb in Task 12). */
    private val pairLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark theme only: the default (auto) style would follow the phone's day/night mode and
        // draw dark status-bar icons on the black background, plus a light 3-button nav scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Only a fresh launch brings a link. A recreated activity (rotation) still holds the intent it was first
        // opened with, and must not offer the same link again.
        if (savedInstanceState == null) pairLink.value = intent?.dataString
        setContent {
            PalliTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PalliNavHost(sessions, pairLink.value, onPairLinkConsumed = { pairLink.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { pairLink.value = it }
    }

    override fun onResume() {
        super.onResume()
        notifier.clearConversation()          // she is on screen now
    }
}
