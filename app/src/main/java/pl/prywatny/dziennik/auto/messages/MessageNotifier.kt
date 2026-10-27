package pl.prywatny.dziennik.auto.messages

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import pl.prywatny.dziennik.auto.MainActivityLauncher
import pl.prywatny.dziennik.auto.R
import pl.prywatny.dziennik.auto.data.Account
import pl.prywatny.dziennik.auto.librus.MessageFolder
import pl.prywatny.dziennik.auto.librus.MessageSummary

/**
 * New Librus messages as notifications that Android Auto reads aloud: MessagingStyle with
 * reply and mark-as-read actions that show no UI (both required by Android Auto).
 * Only the subject is announced: opening the message would mark it as read on Synergia.
 */
object MessageNotifier {
    const val CHANNEL = "messages"
    const val KEY_REPLY = "reply"

    private const val EXTRA_LOGIN = "login"
    private const val EXTRA_ID = "id"
    private const val EXTRA_CORRESPONDENT = "correspondent"
    private const val EXTRA_SUBJECT = "subject"
    private const val EXTRA_DATE = "date"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Nowe wiadomości z Librusa", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Wiadomości z dziennika. W Android Auto są czytane na głos."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun id(login: String, messageId: String) = "$login/$messageId".hashCode()

    private val me = Person.Builder().setName("Ty").setKey("me").build()

    /** A new message. [replies] are the user's replies sent from the car so far (shown below it). */
    fun show(
        context: Context,
        account: Account,
        message: MessageSummary,
        multipleStudents: Boolean,
        replies: List<String> = emptyList(),
        note: String? = null,
    ) {
        if (!canNotify(context)) return
        val sender = Person.Builder().setName(message.correspondent).setKey(message.correspondent).build()
        val text = (if (multipleStudents) "${account.studentName}: " else "") + message.subject.ifEmpty { "(bez tematu)" }
        val time = System.currentTimeMillis()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage(NotificationCompat.MessagingStyle.Message(text, time, sender))
        replies.forEach { style.addMessage(NotificationCompat.MessagingStyle.Message(it, time, me)) }
        note?.let { style.addMessage(NotificationCompat.MessagingStyle.Message(it, time, Person.Builder().setName("Dziennik").build())) }

        val reply = NotificationCompat.Action.Builder(R.drawable.ic_reply, "Odpowiedz", actionIntent(context, account, message, MessageActionWorker.REPLY, mutable = true))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Odpowiedź").build())
            .build()
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_done, "Przeczytane", actionIntent(context, account, message, MessageActionWorker.MARK_READ, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(MainActivityLauncher.pendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(replies.isNotEmpty() || note != null)
            .addAction(reply)
            .addAction(markRead)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(id(account.login, message.id), notification)
    }

    fun cancel(context: Context, login: String, messageId: String) {
        NotificationManagerCompat.from(context).cancel(id(login, messageId))
    }

    private fun actionIntent(context: Context, account: Account, message: MessageSummary, action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, MessageActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_LOGIN, account.login)
            putExtra(EXTRA_ID, message.id)
            putExtra(EXTRA_CORRESPONDENT, message.correspondent)
            putExtra(EXTRA_SUBJECT, message.subject)
            putExtra(EXTRA_DATE, message.date)
        }
        // Android Auto fills the RemoteInput into the reply intent, so that one must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, "$action/${id(account.login, message.id)}".hashCode(), intent, flags)
    }

    /** The message an action intent refers to. */
    fun messageFrom(intent: Intent): Pair<String, MessageSummary>? {
        val login = intent.getStringExtra(EXTRA_LOGIN) ?: return null
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        return login to MessageSummary(
            id = id,
            folder = MessageFolder.INBOX,
            correspondent = intent.getStringExtra(EXTRA_CORRESPONDENT) ?: "",
            subject = intent.getStringExtra(EXTRA_SUBJECT) ?: "",
            date = intent.getStringExtra(EXTRA_DATE) ?: "",
            unread = true,
            hasAttachments = false,
        )
    }
}
