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

        /** What the quiet notification says for each state of the connection. It is "Connected" only while it is. */
        fun connectionTitle(state: ConnState): String = when (state) {
            ConnState.CONNECTED -> "Connected to PaLLi"
            ConnState.CONNECTING -> "Connecting to PaLLi"
            ConnState.WAITING -> "Reconnecting to PaLLi"
            ConnState.IDLE -> "Not connected to PaLLi"
        }
    }

    private val manager get() = NotificationManagerCompat.from(context)
    private val her by lazy {
        Person.Builder().setName("PaLLi").setIcon(IconCompat.createWithResource(context, R.drawable.palli_macaw)).build()
    }
    private val me by lazy { Person.Builder().setName("You").build() }
    private val notSent by lazy { Person.Builder().setName("Not sent").build() }

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

    /** The service's own notification. The service starts out connecting: it is posted before the first attempt. */
    fun connectionNotification(state: ConnState = ConnState.CONNECTING): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_palli)
            .setContentTitle(connectionTitle(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp())
            .build()

    /** Re-posts the service's notification under the same id, so it follows the connection. */
    @SuppressLint("MissingPermission")   // the service's own notification; without the permission the system just drops the update
    fun showConnection(state: ConnState) = manager.notify(ID_CONNECTION, connectionNotification(state))

    @SuppressLint("MissingPermission")   // areNotificationsEnabled() is checked first
    fun post(events: List<EventDto>) {
        if (events.isEmpty() || !manager.areNotificationsEnabled()) return
        val style = activeStyle() ?: NotificationCompat.MessagingStyle(me)
        events.forEach { style.addMessage(lineFor(it), (it.at * 1000).toLong(), her) }
        manager.notify(ID_CONVERSATION, conversation(style, alertAgain = true))
    }

    /**
     * Shows the inline reply in the thread, which also stops the reply button's spinner. The reply is your own words,
     * so this update must not buzz the phone again: only-alert-once, for this update alone.
     */
    @SuppressLint("MissingPermission")
    fun addMyReply(text: String) {
        if (!manager.areNotificationsEnabled()) return
        val style = activeStyle() ?: NotificationCompat.MessagingStyle(me)
        style.addMessage(text, System.currentTimeMillis(), null as Person?)
        manager.notify(ID_CONVERSATION, conversation(style, alertAgain = false))
    }

    /**
     * One plain line in the thread, under "Not sent", when a Reply typed here was not sent. Like [addMyReply], it stops
     * the reply button's spinner and does not buzz the phone again.
     */
    @SuppressLint("MissingPermission")
    fun addNotSent(line: String) {
        if (!manager.areNotificationsEnabled()) return
        val style = activeStyle() ?: NotificationCompat.MessagingStyle(me)
        style.addMessage(line, System.currentTimeMillis(), notSent)
        manager.notify(ID_CONVERSATION, conversation(style, alertAgain = false))
    }

    fun clearConversation() = manager.cancel(ID_CONVERSATION)

    private fun conversation(style: NotificationCompat.MessagingStyle, alertAgain: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_palli)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alertAgain)
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
