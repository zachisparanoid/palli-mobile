package com.palli.mobile.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.ui.theme.PalliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Insets, under the edge-to-edge window MainActivity sets up: the top bar pads itself below the status bar, and the
 * [Composer] is the Scaffold's bottom bar and pads itself above the navigation bar and the keyboard. The Scaffold then
 * hands the list exactly the room between them, and the list's newest bubble stays at the bottom edge as that room shrinks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onReminders: () -> Unit, onSettings: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val typing by vm.herTyping.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()
    val blocked by vm.sendingBlocked.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val newestFirst = remember(items) { items.asReversed() }
    val listState = rememberLazyListState()

    // Notifications need asking for on Android 13+; asked once ever, the first time the chat opens. The question is
    // remembered on the phone (not just for this screen), so after a "no" a later start does not put it again; and it
    // is not put at all once notifications are allowed.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !allowed && vm.shouldAskNotifications()) {
            vm.notificationsAsked()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Scrolling near the oldest bubble pages back through her log.
    LaunchedEffect(listState, newestFirst.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (newestFirst.isNotEmpty() && last >= newestFirst.size - 5) vm.loadOlder() }
    }
    // A new bubble lands below the viewport (the list keeps the bubble it was showing), so follow it down: always for
    // something you just sent, and for hers or her typing only when you were at the bottom, not reading back.
    //
    // "Were at the bottom" is read here, in composition, as the new bubbles arrive and before they are laid out: once the
    // list has re-anchored on the bubble it was showing, several bubbles arriving at once (a multi-beat reply, a replay) make
    // the old bottom look several items away. `follow` carries the decision across a second arrival that comes while the
    // first is still being followed down.
    val follow = remember { Follow() }
    val newest = newestFirst.firstOrNull()
    val newestKey = newest?.key
    val wasAtBottomForBubble = remember(newestKey) { listState.isAtBottom() }
    val wasAtBottomForTyping = remember(typing) { listState.isAtBottom() }
    LaunchedEffect(newestKey) {
        if (newest == null) return@LaunchedEffect
        val justSent = newest is TimelineItem.Mine && newest.key.startsWith("p")
        if (justSent || wasAtBottomForBubble || follow.pending) listState.followToBottom(follow)
    }
    LaunchedEffect(typing) {
        if (typing && (wasAtBottomForTyping || follow.pending)) listState.followToBottom(follow)
    }
    // What could not be done is said once, in a line over the composer.
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it)
            vm.noticeShown(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PaLLi")
                        Text(DoorBanner.connection(connection), style = MaterialTheme.typography.labelSmall, color = PalliColors.Muted)
                    }
                },
                actions = {
                    IconButton(onClick = onReminders) { Icon(Icons.Outlined.Alarm, "Reminders") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PalliColors.Background),
            )
        },
        bottomBar = { Composer(draft, vm::onDraft, vm::send, enabled = !blocked) },
        snackbarHost = {
            SnackbarHost(snackbars) { Snackbar(it, containerColor = PalliColors.Card, contentColor = PalliColors.Text) }
        },
        containerColor = PalliColors.Background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            banner?.let {
                Surface(color = PalliColors.Card, modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = PalliColors.Warn, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp))
                }
            }
            LazyColumn(
                state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (typing) item(key = "typing") { TypingBubble() }
                items(newestFirst, key = { it.key }) { Bubble(it, vm::retry) }
            }
        }
    }
}

/** Whether the list is being followed down to its newest bubble: set when a bubble is followed, cleared when that finishes. */
private class Follow { var pending = false }

/** At the newest bubble, with none of it scrolled out of view. Read without subscribing, so scrolling does not recompose. */
private fun LazyListState.isAtBottom(): Boolean =
    Snapshot.withoutReadObservation { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }

/**
 * Scrolls to the newest bubble. If this is cancelled because a newer arrival replaced it, the decision to follow stays with
 * [follow] for that arrival to finish; if the reader's own finger took the list over, it is dropped, and they are left alone.
 */
private suspend fun LazyListState.followToBottom(follow: Follow) {
    follow.pending = true
    try {
        animateScrollToItem(0)
        follow.pending = false
    } catch (e: CancellationException) {
        if (currentCoroutineContext().isActive) follow.pending = false
        throw e
    }
}
