package com.palli.mobile.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.palli.mobile.MainActivity
import com.palli.mobile.R
import com.palli.mobile.data.remote.EventDto
import com.palli.mobile.data.remote.EventKinds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CONNECTION = "connection"
        const val ID_CONVERSATION = 1
        const val ID_CONNECTION = 2
        const val REPLY_KEY = "reply"
    }

    private val manager get() = NotificationManagerCompat.from(context)
    private val her by lazy {
        Person.Builder().setName("PaLLi").setIcon(IconCompat.createWithResource(context, R.drawable.palli_macaw)).build()
    }
    private val me by lazy { Person.Builder().setName("You").build() }

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages from PaLLi", NotificationManager.IMPORTANCE_HIGH))
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                description = "The quiet notification that keeps her messages arriving at once."
            })
    }

    fun connectionNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_palli)
            .setContentTitle("Connected to PaLLi")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp())
            .build()

    @SuppressLint("MissingPermission")   // areNotificationsEnabled() is checked first
    fun post(events: List<EventDto>) {
        if (events.isEmpty() || !manager.areNotificationsEnabled()) return
        val style = activeStyle() ?: NotificationCompat.MessagingStyle(me)
        events.forEach { style.addMessage(lineFor(it), (it.at * 1000).toLong(), her) }
        manager.notify(ID_CONVERSATION, conversation(style))
    }

    /** Shows the inline reply in the thread, which also stops the reply button's spinner. */
    @SuppressLint("MissingPermission")
    fun addMyReply(text: String) {
        if (!manager.areNotificationsEnabled()) return
        val style = activeStyle() ?: NotificationCompat.MessagingStyle(me)
        style.addMessage(text, System.currentTimeMillis(), null as Person?)
        manager.notify(ID_CONVERSATION, conversation(style))
    }

    fun clearConversation() = manager.cancel(ID_CONVERSATION)

    private fun conversation(style: NotificationCompat.MessagingStyle): Notification =
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_palli)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .addAction(replyAction())
            .build()

    private fun activeStyle(): NotificationCompat.MessagingStyle? =
        manager.activeNotifications.firstOrNull { it.id == ID_CONVERSATION }?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }

    private fun lineFor(e: EventDto): String = when (e.kind) {
        EventKinds.VOICE_NOTE -> "Voice note" + (e.text?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        EventKinds.FILE -> "File: ${e.file?.filename?.takeIf { it.isNotBlank() } ?: "a file"}"
        EventKinds.NOTICE -> e.text.orEmpty()
        else -> if (e.reminder) "Reminder: ${e.text.orEmpty()}" else e.text.orEmpty()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun replyAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build()
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)       // RemoteInput needs MUTABLE
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_palli, "Reply", intent)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .build()
    }
}
