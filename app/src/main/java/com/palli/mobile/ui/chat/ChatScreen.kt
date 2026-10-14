package com.palli.mobile.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.ui.battery.BatteryExemption
import com.palli.mobile.ui.theme.PalliColors
import java.io.File

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
    val recording by vm.recording.collectAsStateWithLifecycle()
    val media = vm.media
    val newestFirst = remember(items) { items.asReversed() }
    val listState = rememberLazyListState()

    // Notifications need asking for on Android 13+; asked once ever, the first time the chat opens. The question is
    // remembered on the phone (not just for this screen), so after a "no" a later start does not put it again; and it
    // is not put at all once notifications are allowed.
    val context = LocalContext.current
    var notificationsSettled by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsSettled = true
    }
    LaunchedEffect(Unit) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33 && !allowed && vm.shouldAskNotifications()) {
            vm.notificationsAsked()
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsSettled = true
        }
    }
    // The battery question, once ever and only while Android is limiting the app; after the notification question has been
    // answered, so the two are not put at once. Any way out of it (yes, not now, back) is the answer, written down on the
    // phone so no later start puts it again. Settings has a button for asking again.
    var askBattery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notificationsSettled) {
        if (notificationsSettled && vm.shouldAskBattery(BatteryExemption.limited(context))) askBattery = true
    }
    // Photos, files, the camera and the microphone. What the system hands back goes to the view model, which refuses it
    // while sending is blocked. The camera's file is kept by path across a rebuilt activity: the camera app can outlive it.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        vm.sendPicked(uris, "image")
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.sendPicked(listOf(it), "file") }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        cameraPath?.let { File(it) }?.let { if (saved) vm.sendCameraPhoto(it) else it.delete() }
        cameraPath = null
    }
    fun launching(launch: () -> Unit) = try { launch() } catch (e: ActivityNotFoundException) { vm.noAppForThat() }
    fun launchCamera() {
        val file = vm.newCameraFile() ?: return
        cameraPath = file.path
        launching {
            try {
                takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            } catch (e: ActivityNotFoundException) {
                cameraPath = null
                file.delete()
                throw e
            }
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else vm.refused(Manifest.permission.CAMERA)
    }
    // A yes takes effect at the next hold of the microphone: this press has already ended.
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.refused(Manifest.permission.RECORD_AUDIO)
    }
    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    // No sound without its control on screen: her note stops, and a note being recorded is dropped, when the chat stops
    // being seen (the app goes to the background, or Reminders or Settings is opened) and when it leaves for good.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, media) {
        val silence = {
            media?.player?.stop()
            vm.cancelRecording()
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) silence() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            silence()
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
    // Whether to follow is decided here, in composition, as the new bubbles arrive and before they are laid out: once the
    // list has re-anchored on the bubble it was showing, several bubbles arriving at once (a multi-beat reply, a replay) make
    // the old bottom look several items away. A second arrival that comes while the first is still being followed down
    // (`follow.pending`) is followed too: that is read at the same moment, before the first follow's effect is replaced.
    // Each follow clears `pending` when it ends, however it ends, so a follow that was replaced mid-way (her typing
    // stopping, say) never leaves it set for a later bubble to yank the list down with.
    val follow = remember { Follow() }
    val newest = newestFirst.firstOrNull()
    val newestKey = newest?.key
    val followBubble = remember(newestKey) { listState.isAtBottom() || follow.pending }
    val followTyping = remember(typing) { listState.isAtBottom() || follow.pending }
    LaunchedEffect(newestKey) {
        if (newest == null) return@LaunchedEffect
        val justSent = newest is TimelineItem.Mine && newest.key.startsWith("p")
        if (justSent || followBubble) listState.followToBottom(follow)
    }
    LaunchedEffect(typing) {
        if (typing && followTyping) listState.followToBottom(follow)
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
        bottomBar = {
            Composer(
                draft = draft, onDraft = vm::onDraft, onSend = vm::send, enabled = !blocked, recording = recording,
                onPickPhotos = {
                    launching { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                },
                onCamera = { if (has(Manifest.permission.CAMERA)) launchCamera() else askCamera.launch(Manifest.permission.CAMERA) },
                onPickFile = { launching { pickFile.launch(arrayOf("*/*")) } },
                onRecordStart = {
                    if (has(Manifest.permission.RECORD_AUDIO)) vm.startRecording()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                },
                onRecordStop = vm::stopRecording,
                onRecordCancel = vm::cancelRecording,
            )
        },
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
                contentPadding = PaddingValues(12.dp),
                // Bottom, so a short chat sits just above the composer (the newest bubble is index 0, at the bottom).
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                if (typing) item(key = "typing") { TypingBubble() }
                items(newestFirst, key = { it.key }) { Bubble(it, vm::retry, vm::remove, media) }
            }
        }
    }
    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false; vm.markAskedBattery() },
            title = { Text("Get her messages at once?") },
            text = {
                Text("Android pauses apps in the background to save battery. Letting PaLLi run keeps one quiet connection open, " +
                    "so her messages arrive the moment she sends them.")
            },
            confirmButton = {
                TextButton(onClick = {
                    askBattery = false
                    vm.markAskedBattery()
                    if (!BatteryExemption.ask(context)) vm.noAppForThat()
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { askBattery = false; vm.markAskedBattery() }) { Text("Not now") } },
        )
    }
}

/**
 * Whether the list is being followed down to its newest bubble. Each follow takes a token; only the follow that holds
 * the current token clears it, so an older follow ending late (its effect replaced) cannot clear a newer one's.
 */
private class Follow {
    private var owner: Any? = null
    val pending: Boolean get() = owner != null
    fun begin(): Any = Any().also { owner = it }
    fun end(token: Any) { if (owner === token) owner = null }
}

/** At the newest bubble, with none of it scrolled out of view. Read without subscribing, so scrolling does not recompose. */
private fun LazyListState.isAtBottom(): Boolean =
    Snapshot.withoutReadObservation { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }

/**
 * Scrolls to the newest bubble, marking [follow] pending meanwhile. It is cleared in `finally`: when the scroll lands,
 * when the reader's own finger takes the list over, and when this effect is replaced (a newer arrival, which has already
 * read `pending` in composition and follows on its own; or her typing stopping, which follows nothing).
 */
private suspend fun LazyListState.followToBottom(follow: Follow) {
    val token = follow.begin()
    try {
        animateScrollToItem(0)
    } finally {
        follow.end(token)
    }
}
