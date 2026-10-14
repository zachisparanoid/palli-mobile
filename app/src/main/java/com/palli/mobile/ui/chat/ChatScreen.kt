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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.ui.theme.PalliColors

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
    val newestFirst = remember(items) { items.asReversed() }
    val listState = rememberLazyListState()

    // Notifications need asking for on Android 13+; asked once, the first time the chat opens. Not again when the phone is
    // turned or the chat is come back to (the flag is saved with this screen), and not at all once it is allowed.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !allowed && !asked) {
            asked = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Scrolling near the oldest bubble pages back through her log.
    LaunchedEffect(listState, newestFirst.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (newestFirst.isNotEmpty() && last >= newestFirst.size - 5) vm.loadOlder() }
    }
    // A new bubble lands below the viewport (the list keeps the bubble it was showing), so follow it down: always for
    // something you just sent, and for hers or her typing only when you were already at the bottom, not reading back.
    val newest = newestFirst.firstOrNull()
    LaunchedEffect(newest?.key) {
        if (newest == null) return@LaunchedEffect
        val justSent = newest is TimelineItem.Mine && newest.key.startsWith("p")
        if (justSent || listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    LaunchedEffect(typing) {
        if (typing && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
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
        bottomBar = { Composer(draft, vm::onDraft, vm::send) },
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
