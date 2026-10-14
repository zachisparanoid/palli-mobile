package com.palli.mobile.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ChatScreen(onReminders: () -> Unit, onSettings: () -> Unit) {
    Text("Paired.", modifier = Modifier.fillMaxSize().systemBarsPadding())
}
