package com.palli.mobile.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.palli.mobile.ui.theme.PalliColors

/**
 * Text only; Task 10 extends it. It is the screen's bottom bar, drawn edge to edge, so it keeps itself clear of both
 * system bars below it: the navigation bar and the keyboard. Each pads only by what the other has not already taken, so
 * they do not stack: the taller of the two is the room left under the composer.
 *
 * [enabled] is off while the app refuses to chat with this PaLLi (another api version): nothing can be typed or sent.
 */
@Composable
fun Composer(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(draft, onDraft, enabled = enabled, placeholder = { Text("Message PaLLi") }, maxLines = 5,
            modifier = Modifier.weight(1f))
        val canSend = enabled && draft.isNotBlank()
        IconButton(onClick = onSend, enabled = canSend) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (canSend) PalliColors.Accent else PalliColors.Faint)
        }
    }
}
