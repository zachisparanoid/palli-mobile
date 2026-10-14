package com.palli.mobile.ui.pair

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.palli.mobile.R
import com.palli.mobile.ui.theme.PalliColors

/**
 * [initialLink] is a `palli://pair` link the app was opened with. The screen takes it once and calls [onLinkConsumed],
 * so the same link is never replayed (after a rotation, or when the Pair screen comes back after an unpair).
 */
@Composable
fun PairScreen(initialLink: String?, onLinkConsumed: () -> Unit = {}, vm: PairViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reason by vm.unpairedReason.collectAsStateWithLifecycle()
    LaunchedEffect(initialLink) {
        initialLink?.let {
            vm.onScanned(it)
            onLinkConsumed()
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(vm::onScanned) }

    // A scan or a link never pairs by itself: any web page can open palli://pair and point this phone at its own server.
    state.pendingAddress?.let { address ->
        AlertDialog(
            onDismissRequest = vm::cancelPair,
            title = { Text("Pair with $address?") },
            text = { Text("Your messages will go to this address. Pair only if it is your own PaLLi.") },
            confirmButton = { TextButton(onClick = vm::confirmPair) { Text("Pair") } },
            dismissButton = { TextButton(onClick = vm::cancelPair) { Text("Cancel") } },
            containerColor = PalliColors.Card,
        )
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(painterResource(R.drawable.palli_macaw), contentDescription = null,
            modifier = Modifier.size(96.dp).clip(CircleShape).align(Alignment.CenterHorizontally))
        Text("Pair with PaLLi", style = MaterialTheme.typography.headlineSmall)
        reason?.let { Text(it, color = PalliColors.Warn) }
        Text("On her door page choose Pair a phone (or run palli_ctl phone pair), then scan the code it shows.",
            color = PalliColors.Muted)
        Button(
            onClick = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan PaLLi's pairing code").setBeepEnabled(false))
            },
            enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan the QR code")
        }
        HorizontalDivider(color = PalliColors.CardBorder)
        Text("Or type them in", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(state.address, vm::onAddress, label = { Text("Address") },
            placeholder = { Text("http://your-host.your-tailnet.ts.net:8000") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.code, vm::onCode, label = { Text("Code") }, placeholder = { Text("K7QM-2XPA") },
            singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.deviceName, vm::onName, label = { Text("This phone's name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::pair, enabled = !state.busy) { Text("Pair") }
            if (state.busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        state.error?.let { Text(it, color = PalliColors.Trouble) }
    }
}
