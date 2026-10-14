package com.palli.mobile.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.palli.mobile.domain.timeline.SendStatus
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.ui.theme.PalliColors

private val MineShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
private val HersShape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)

@Composable
fun Bubble(item: TimelineItem, onRetry: (String) -> Unit) = when (item) {
    is TimelineItem.Mine -> MineBubble(item, onRetry)
    is TimelineItem.Hers -> HersBubble(item)
    is TimelineItem.HerVoice -> HerVoiceBubble(item)
    is TimelineItem.HerFile -> HerFileBubble(item)
    is TimelineItem.Notice -> NoticeLine(item)
}

@Composable
private fun MineBubble(item: TimelineItem.Mine, onRetry: (String) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(shape = MineShape, color = PalliColors.MineBubble, border = BorderStroke(1.dp, PalliColors.MineBorder),
                modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    MineAttachments(item)
                    if (item.text.isNotBlank()) Text(item.text)
                    item.heardAs?.let { Text("heard as: $it", style = MaterialTheme.typography.bodySmall, color = PalliColors.Muted) }
                }
            }
            when (item.status) {
                SendStatus.SENDING -> Icon(Icons.Outlined.Schedule, "sending", Modifier.size(14.dp), tint = PalliColors.Faint)
                SendStatus.ACCEPTED -> Icon(Icons.Outlined.Done, "sent", Modifier.size(14.dp), tint = PalliColors.Faint)
                SendStatus.FAILED -> Text("${item.failure ?: "Not sent"} — tap to retry",
                    style = MaterialTheme.typography.bodySmall, color = PalliColors.Trouble,
                    modifier = Modifier.clickable { onRetry(item.key) }.padding(vertical = 6.dp))
                null -> Unit
            }
        }
    }
}

/** Your photos, files and voice note (Task 10 shows them; text alone needs nothing here). */
@Composable
fun MineAttachments(item: TimelineItem.Mine) {
    val names = item.local.map { it.name } + item.files.map { it.filename }
    names.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = PalliColors.Muted) }
}

@Composable
private fun HersBubble(item: TimelineItem.Hers) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(shape = HersShape, color = PalliColors.Card, border = BorderStroke(1.dp, PalliColors.CardBorder),
            modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (item.reminder) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Notifications, null, Modifier.size(14.dp), tint = PalliColors.Accent)
                        Spacer(Modifier.width(4.dp))
                        Text("Reminder", style = MaterialTheme.typography.labelSmall, color = PalliColors.Accent)
                    }
                }
                Text(item.text)
            }
        }
    }
}

/** Task 10 gives this a player. */
@Composable
fun HerVoiceBubble(item: TimelineItem.HerVoice) {
    HersBubble(TimelineItem.Hers(item.key, "Voice note" + (item.transcript?.let { ": $it" } ?: ""), false, 0.0))
}

/** Task 10 shows images inline and downloads the rest. */
@Composable
fun HerFileBubble(item: TimelineItem.HerFile) {
    HersBubble(TimelineItem.Hers(item.key, "File: ${item.file.filename}", false, 0.0))
}

@Composable
private fun NoticeLine(item: TimelineItem.Notice) {
    Text(item.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        color = if (item.trouble) PalliColors.Trouble else PalliColors.Muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
}

@Composable
fun TypingBubble() {
    val pulse by rememberInfiniteTransition(label = "typing").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dots")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(shape = HersShape, color = PalliColors.Card, border = BorderStroke(1.dp, PalliColors.CardBorder),
            modifier = Modifier.semantics { contentDescription = "PaLLi is typing" }) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { Text("•", Modifier.alpha(pulse), color = PalliColors.Muted) }
            }
        }
    }
}
