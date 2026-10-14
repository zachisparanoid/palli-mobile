package com.palli.mobile.ui.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palli.mobile.ui.theme.PalliColors

/**
 * Words, an attach menu (photo, camera, file) and hold-to-talk. It is the screen's bottom bar, drawn edge to edge, so
 * it keeps itself clear of both system bars below it: the navigation bar and the keyboard. Each pads only by what the
 * other has not already taken, so they do not stack: the taller of the two is the room left under the composer.
 *
 * Send is always there, and on only when there are words to send. The microphone sits beside it while the box is
 * empty: hold to record, let go to send, slide off to drop the note.
 *
 * [enabled] is off while the app refuses to chat with this PaLLi (another api version): nothing can be typed, attached,
 * recorded or sent.
 */
@Composable
fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    recording: Boolean = false,
    onPickPhotos: () -> Unit = {},
    onCamera: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onRecordStart: () -> Unit = {},
    onRecordStop: () -> Unit = {},
    onRecordCancel: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val canAttach = enabled && !recording
        LaunchedEffect(canAttach) { if (!canAttach) menu = false }
        Box {
            IconButton(onClick = { menu = true }, enabled = canAttach) {
                Icon(Icons.Outlined.AttachFile, "Attach", tint = if (canAttach) PalliColors.Accent else PalliColors.Faint)
            }
            DropdownMenu(expanded = menu && canAttach, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                    onClick = { menu = false; onPickPhotos() })
                DropdownMenuItem(text = { Text("Camera") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                    onClick = { menu = false; onCamera() })
                DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Outlined.Description, null) },
                    onClick = { menu = false; onPickFile() })
            }
        }
        if (recording) {
            Text("Recording… let go to send, slide off to drop it", color = PalliColors.Warn,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        } else {
            OutlinedTextField(draft, onDraft, enabled = enabled, placeholder = { Text("Message PaLLi") }, maxLines = 5,
                modifier = Modifier.weight(1f))
        }
        if (draft.isBlank() || recording) {
            Mic(enabled = enabled, recording = recording, onStart = onRecordStart, onStop = onRecordStop, onCancel = onRecordCancel)
        }
        val canSend = enabled && !recording && draft.isNotBlank()
        IconButton(onClick = onSend, enabled = canSend) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = if (canSend) PalliColors.Accent else PalliColors.Faint)
        }
    }
}

/**
 * Hold to record. Letting go sends ([onStop]); a press that ends any other way (a finger slid off, the gesture taken
 * away, the composer gone or switched off mid-hold) drops the note ([onCancel]), so a recording never outlives its press.
 */
@Composable
private fun Mic(enabled: Boolean, recording: Boolean, onStart: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit) {
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val press = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            start()
            var released = false
            try {
                released = tryAwaitRelease()
            } finally {
                if (released) stop() else cancel()
            }
        })
    }
    Box(
        Modifier.size(48.dp)
            .semantics {
                contentDescription = "Hold to record a voice note"
                role = Role.Button
                if (!enabled) disabled()
            }
            .then(press),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Mic, null, tint = when {
            !enabled -> PalliColors.Faint
            recording -> PalliColors.Trouble
            else -> PalliColors.Accent
        })
    }
}
