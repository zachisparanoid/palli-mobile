package com.palli.mobile.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palli.mobile.data.remote.ReminderDto
import com.palli.mobile.ui.theme.PalliColors

/** Her open reminders for you, each with a snooze and a cancel. Pull down to load them again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(onBack: () -> Unit, vm: RemindersViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<ReminderDto?>(null) }
    val now = System.currentTimeMillis() / 1000.0

    // What did not go through is said once, in a line at the bottom.
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it)
            vm.noticeShown(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Reminders") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PalliColors.Background))
        },
        snackbarHost = {
            SnackbarHost(snackbars) { Snackbar(it, containerColor = PalliColors.Card, contentColor = PalliColors.Text) }
        },
        containerColor = PalliColors.Background,
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()) {
                state.error?.let { problem ->
                    item {
                        Column {
                            Text(problem, color = PalliColors.Trouble)
                            TextButton(onClick = vm::refresh) { Text("Try again") }
                        }
                    }
                }
                if (state.loaded && state.reminders.isEmpty() && state.error == null) {
                    item { Text("No reminders. Ask her for one in the chat.", color = PalliColors.Muted) }
                }
                items(state.reminders, key = { it.id }) { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = PalliColors.Card), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(r.text)
                            Text(DueFormat.format(r.dueAt, now), style = MaterialTheme.typography.labelSmall,
                                color = PalliColors.Muted)
                            Row {
                                TextButton(onClick = { vm.snooze(r.id, 15) }) { Text("Snooze 15 min") }
                                TextButton(onClick = { vm.snooze(r.id, 60) }) { Text("1 hr") }
                                TextButton(onClick = { confirming = r }) { Text("Cancel", color = PalliColors.Trouble) }
                            }
                        }
                    }
                }
            }
        }
    }
    confirming?.let { r ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Cancel this reminder?") },
            text = { Text(r.text) },
            confirmButton = { TextButton(onClick = { vm.cancel(r.id); confirming = null }) { Text("Cancel it") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Keep it") } },
        )
    }
}
