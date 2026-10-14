package com.palli.mobile.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palli.mobile.ui.battery.BatteryExemption
import com.palli.mobile.ui.theme.PalliColors

/** Which PaLLi this is, staying connected, the battery question, notifications, and unpairing this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val session by vm.session.collectAsStateWithLifecycle()
    val stay by vm.stayConnected.collectAsStateWithLifecycle()
    val unpairing by vm.unpairing.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var limited by remember { mutableStateOf(false) }
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    // Read again on every return to the screen: the system's question is a screen of its own, and its answer is not sent back.
    LifecycleResumeEffect(Unit) {
        limited = BatteryExemption.limited(context)
        onPauseOrDispose { }
    }

    // What could not be done is said once, in a line at the bottom.
    val snackbars = remember { SnackbarHostState() }
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it)
            vm.noticeShown(it)
        }
    }
    LaunchedEffect(said) {
        said?.let {
            snackbars.showSnackbar(it)
            said = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PalliColors.Background))
        },
        snackbarHost = {
            SnackbarHost(snackbars) { Snackbar(it, containerColor = PalliColors.Card, contentColor = PalliColors.Text) }
        },
        containerColor = PalliColors.Background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PaLLi", style = MaterialTheme.typography.titleSmall)
            Text(session?.baseUrl ?: "—", color = PalliColors.Muted)
            Text("This phone: ${session?.deviceName ?: "—"}", color = PalliColors.Muted)
            HorizontalDivider(color = PalliColors.CardBorder)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Stay connected in the background")
                    Text("Her messages arrive at once. Off: only while the app is open, plus a check every 15 minutes.",
                        style = MaterialTheme.typography.bodySmall, color = PalliColors.Muted)
                }
                // The switch only writes the setting down; the connection follows it on its own.
                Switch(checked = stay ?: true, enabled = stay != null, onCheckedChange = vm::setStayConnected)
            }

            Column {
                Text("Battery")
                Text(if (limited) "Android may pause the connection to save battery, which delays her messages."
                     else "Not limited — her messages can arrive at once.",
                    style = MaterialTheme.typography.bodySmall, color = PalliColors.Muted)
                if (limited) {
                    OutlinedButton(onClick = { if (!BatteryExemption.ask(context)) said = BatteryExemption.NO_SCREEN }) {
                        Text("Let PaLLi run in the background")
                    }
                }
            }

            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                } catch (e: ActivityNotFoundException) {
                    said = "This phone has no screen for that. Look for PaLLi under Notifications in the phone's settings."
                }
            }) { Text("Notification settings") }

            HorizontalDivider(color = PalliColors.CardBorder)
            OutlinedButton(onClick = { confirmUnpair = true }, enabled = !unpairing) {
                Text(if (unpairing) "Unpairing…" else "Unpair this phone", color = PalliColors.Trouble)
            }
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = {
                Text("PaLLi's messages stop coming here until you pair again. The conversation and files on this phone are " +
                    "cleared; they stay on PaLLi's machine.")
            },
            confirmButton = { TextButton(onClick = { confirmUnpair = false; vm.unpair() }) { Text("Unpair", color = PalliColors.Trouble) } },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}
