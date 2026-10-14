package com.palli.mobile.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.palli.mobile.data.session.SessionStore
import com.palli.mobile.ui.chat.ChatScreen
import com.palli.mobile.ui.pair.PairScreen
import com.palli.mobile.ui.reminders.RemindersScreen
import com.palli.mobile.ui.settings.SettingsScreen

object Routes {
    const val PAIR = "pair"
    const val CHAT = "chat"
    const val REMINDERS = "reminders"
    const val SETTINGS = "settings"
}

/**
 * Unpaired → Pair; paired → Chat (and from it Reminders and Settings). Losing the session anywhere (unpaired, revoked) comes
 * back to Pair, with the back stack cleared.
 *
 * [pairLink] is a `palli://pair` link the app was opened with; the Pair screen takes it and calls [onPairLinkConsumed].
 * A link that arrives while this phone is already paired is dropped here, not kept: it neither unpairs, re-pairs nor
 * navigates, and it cannot wait around to pop a "Pair with …?" question if the phone is unpaired later.
 */
@Composable
fun PalliNavHost(sessions: SessionStore, pairLink: String?, onPairLinkConsumed: () -> Unit = {}) {
    val session by sessions.session.collectAsStateWithLifecycle()
    val loaded by sessions.loaded.collectAsStateWithLifecycle()
    if (!loaded) { Box(Modifier.fillMaxSize()); return }

    val paired = session != null
    LaunchedEffect(pairLink, paired) { if (pairLink != null && paired) onPairLinkConsumed() }

    val nav = rememberNavController()
    // Fixed at first sight: a start destination that follows the session would rebuild the graph on every pair and unpair.
    val start = remember { if (paired) Routes.CHAT else Routes.PAIR }
    NavHost(nav, startDestination = start) {
        composable(Routes.PAIR) { PairScreen(initialLink = pairLink, onLinkConsumed = onPairLinkConsumed) }
        composable(Routes.CHAT) {
            ChatScreen(onReminders = { nav.navigate(Routes.REMINDERS) }, onSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.REMINDERS) { RemindersScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
    // Paired, only Pair is the wrong screen: Chat, Reminders and Settings are all the paired phone's, and this runs again
    // when the activity is rebuilt (a turn of the phone) with the nav controller already back on Settings. Unpaired, the
    // back stack is cleared, so back from Pair leaves the app and never returns to a screen of the old pairing.
    LaunchedEffect(paired) {
        val route = nav.currentDestination?.route
        val wrong = if (paired) route == Routes.PAIR else route != Routes.PAIR
        if (wrong) nav.navigate(if (paired) Routes.CHAT else Routes.PAIR) { popUpTo(0) { inclusive = true } }
    }
}
