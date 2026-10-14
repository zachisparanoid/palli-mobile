package com.palli.mobile.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.network.HttpException
import com.palli.mobile.domain.timeline.SendStatus
import com.palli.mobile.domain.timeline.TimelineItem
import com.palli.mobile.ui.media.ChatMedia
import com.palli.mobile.ui.media.FileCache
import com.palli.mobile.ui.theme.PalliColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private val MineShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
private val HersShape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
private val PhotoShape = RoundedCornerShape(10.dp)

/**
 * [media] shows, plays and saves the files; with none (a chat that has no media) a file is shown by its name. A failed
 * send of yours retries on a tap ([onRetry]) and, from a long-press menu, can be removed ([onRemove]).
 */
@Composable
fun Bubble(item: TimelineItem, onRetry: (String) -> Unit, onRemove: (String) -> Unit, media: ChatMedia?) = when (item) {
    is TimelineItem.Mine -> MineBubble(item, onRetry, onRemove, media)
    is TimelineItem.Hers -> HersBubble(item)
    is TimelineItem.HerVoice -> HerVoiceBubble(item, media)
    is TimelineItem.HerFile -> HerFileBubble(item, media)
    is TimelineItem.Notice -> NoticeLine(item)
}

/**
 * Yours. A failed one is the only bubble with a menu: a tap retries, as the line under it says, and a long-press opens a
 * small menu whose one item is Remove, so a removal is never one stray tap.
 */
@Composable
private fun MineBubble(item: TimelineItem.Mine, onRetry: (String) -> Unit, onRemove: (String) -> Unit, media: ChatMedia?) {
    val failed = item.status == SendStatus.FAILED
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = if (failed) {
                    Modifier.combinedClickable(onClickLabel = "Retry", onLongClickLabel = "More",
                        onLongClick = { menu = true }, onClick = { onRetry(item.key) })
                } else Modifier,
            ) {
                Surface(shape = MineShape, color = PalliColors.MineBubble, border = BorderStroke(1.dp, PalliColors.MineBorder),
                    modifier = Modifier.widthIn(max = 300.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MineAttachments(item, media)
                        if (item.text.isNotBlank()) Text(item.text)
                        item.heardAs?.let { Text("heard as: $it", style = MaterialTheme.typography.bodySmall, color = PalliColors.Muted) }
                    }
                }
                when (item.status) {
                    SendStatus.SENDING -> Icon(Icons.Outlined.Schedule, "sending", Modifier.size(14.dp), tint = PalliColors.Faint)
                    SendStatus.ACCEPTED -> Icon(Icons.Outlined.Done, "sent", Modifier.size(14.dp), tint = PalliColors.Faint)
                    SendStatus.FAILED -> Text("${item.failure ?: "Not sent"} — tap to retry",
                        style = MaterialTheme.typography.bodySmall, color = PalliColors.Trouble,
                        modifier = Modifier.padding(vertical = 6.dp))
                    null -> Unit
                }
            }
            if (failed) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = PalliColors.Card) {
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menu = false
                        onRemove(item.key)
                    })
                }
            }
        }
    }
}

/** Your photos (from the phone while sending, from PaLLi once delivered), files, and voice note. */
@Composable
fun MineAttachments(item: TimelineItem.Mine, media: ChatMedia?) {
    val small = MaterialTheme.typography.bodySmall
    item.local.forEach { a ->
        when {
            a.kind == "image" && media != null -> AsyncImage(model = File(a.path), imageLoader = media.imageLoader,
                contentDescription = a.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(180.dp).clip(PhotoShape))
            a.kind == "voice" -> Text("Voice note · ${clock((a.durationMs ?: 0L) / 1000)}", style = small, color = PalliColors.Muted)
            else -> Text(a.name, style = small, color = PalliColors.Muted)
        }
    }
    item.files.forEach { f ->
        val url = media?.urlFor(f.id)
        when {
            f.contentType.startsWith("image/") && media != null && url != null -> AsyncImage(model = url,
                imageLoader = media.imageLoader, contentDescription = f.filename, contentScale = ContentScale.Crop,
                modifier = Modifier.size(180.dp).clip(PhotoShape))
            f.contentType.startsWith("audio/") -> {
                Text("Voice note", style = small, color = PalliColors.Muted)
                if (item.heardAs == null) Text("She couldn't hear this one.", style = small, color = PalliColors.Warn)
            }
            else -> Text(f.filename.ifBlank { "A file" }, style = small, color = PalliColors.Muted)
        }
    }
}

@Composable
private fun HersFrame(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(shape = HersShape, color = PalliColors.Card, border = BorderStroke(1.dp, PalliColors.CardBorder),
            modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
        }
    }
}

@Composable
private fun HersBubble(item: TimelineItem.Hers) = HersFrame {
    if (item.reminder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notifications, null, Modifier.size(14.dp), tint = PalliColors.Accent)
            Spacer(Modifier.width(4.dp))
            Text("Reminder", style = MaterialTheme.typography.labelSmall, color = PalliColors.Accent)
        }
    }
    Text(item.text)
}

/** Her voice note: play/pause with its progress, fetched once with the token into the cache; her words on a tap. */
@Composable
fun HerVoiceBubble(item: TimelineItem.HerVoice, media: ChatMedia?) {
    if (media == null) {
        HersBubble(TimelineItem.Hers(item.key, "Voice note" + (item.transcript?.let { ": $it" } ?: ""), false, 0.0))
        return
    }
    val scope = rememberCoroutineScope()
    val playing by media.player.state.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var showWords by remember { mutableStateOf(false) }
    val id = item.file.id
    val isThis = playing.playingId == id
    val sounding = isThis && !playing.paused
    HersFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (fetching) return@IconButton
                fetching = true
                scope.launch {
                    try {
                        media.player.toggle(id, media.files.get(id))      // quick once cached
                        problem = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FileCache.FileGone) {
                        problem = ChatMedia.GONE
                    } catch (e: Exception) {
                        problem = "Couldn't fetch the voice note. Is Tailscale on?"
                    } finally {
                        fetching = false
                    }
                }
            }) {
                Icon(if (sounding) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (sounding) "Pause her voice note" else "Play her voice note", tint = PalliColors.Accent)
            }
            LinearProgressIndicator(
                progress = { if (isThis && playing.durationMs > 0) playing.positionMs.toFloat() / playing.durationMs else 0f },
                modifier = Modifier.width(140.dp))
            Spacer(Modifier.width(8.dp))
            item.file.durationS?.let {
                Text(clock(it.toLong()), style = MaterialTheme.typography.labelSmall, color = PalliColors.Muted)
            }
        }
        item.transcript?.takeIf { it.isNotBlank() }?.let { words ->
            Text(if (showWords) words else "Show what she said",
                style = MaterialTheme.typography.bodySmall,
                color = if (showWords) PalliColors.Text else PalliColors.Accent,
                modifier = Modifier.clickable { showWords = !showWords }.padding(vertical = 4.dp))
        }
        val trouble = problem ?: if (playing.failedId == id) "Couldn't play the voice note." else null
        trouble?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PalliColors.Trouble) }
    }
}

/** Her file: a picture shows inline; anything else is a row. A tap saves either into Downloads/PaLLi. */
@Composable
fun HerFileBubble(item: TimelineItem.HerFile, media: ChatMedia?) {
    val url = media?.urlFor(item.file.id)
    val name = item.file.filename.ifBlank { "A file" }
    if (media != null && url != null && item.file.contentType.startsWith("image/")) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            SubcomposeAsyncImage(
                model = url, imageLoader = media.imageLoader, contentDescription = name,
                contentScale = ContentScale.Fit,
                error = { state ->
                    val gone = (state.result.throwable as? HttpException)?.response?.code == 404
                    HersFrame {
                        Text(if (gone) ChatMedia.GONE else "Couldn't load this picture. Is Tailscale on?", color = PalliColors.Muted)
                    }
                },
                modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).clip(HersShape)
                    .clickable(onClickLabel = "Save to Downloads") { media.download(item.file) },
            )
        }
    } else {
        HersFrame {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = media != null, onClickLabel = "Save to Downloads") {
                    media?.download(item.file)
                }) {
                Icon(Icons.Outlined.Description, null, tint = PalliColors.Accent)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(name)
                    val size = item.file.size?.let(::bytes)
                    val hint = if (media != null) "tap to save" else null
                    listOfNotNull(size, hint).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = PalliColors.Muted)
                    }
                }
            }
        }
    }
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

/** 7 → "0:07", 75 → "1:15". */
internal fun clock(seconds: Long): String = "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)

/** 900 → "900 B", 2 400 000 → "2.3 MB". */
internal fun bytes(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.0f KB".format(Locale.ROOT, size / 1024.0)
    else -> "%.1f MB".format(Locale.ROOT, size / (1024.0 * 1024.0))
}
