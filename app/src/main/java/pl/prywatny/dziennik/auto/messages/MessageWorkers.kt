package pl.prywatny.dziennik.auto.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import pl.prywatny.dziennik.auto.AppGraph
import pl.prywatny.dziennik.auto.librus.MessageFolder
import pl.prywatny.dziennik.auto.librus.MessageSummary
import java.util.concurrent.TimeUnit

/** Checks the inboxes every 30 minutes and announces messages that weren't there before. */
class MessageCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val store = AppGraph.store
        if (!store.notificationsEnabled) return Result.success()
        val accounts = store.accounts
        for (account in accounts) {
            val inbox = try {
                AppGraph.refreshInbox(account)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                continue
            }
            val seen = store.seenMessages(account.login)
            // First check: remember what is there, announce only what arrives later.
            if (seen != null) {
                inbox.filter { it.unread && it.id !in seen }.take(MAX_PER_CHECK).forEach {
                    MessageNotifier.show(applicationContext, account, it, multipleStudents = accounts.size > 1)
                }
            }
            store.saveSeenMessages(account.login, (seen ?: emptySet()) + inbox.map { it.id })
        }
        return Result.success()
    }

    companion object {
        private const val MAX_PER_CHECK = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MessageCheckWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("message-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Receives the reply / mark-as-read actions (no UI, as Android Auto requires) and hands them to a worker. */
class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (login, message) = MessageNotifier.messageFrom(intent) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifier.KEY_REPLY)?.toString()
        val request = OneTimeWorkRequestBuilder<MessageActionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    "action" to intent.action, "login" to login, "id" to message.id, "correspondent" to message.correspondent,
                    "subject" to message.subject, "date" to message.date, "reply" to reply,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}

/** Sends a reply (to the sender, found in Synergia's recipient list like on iOS) or marks a message read. */
class MessageActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val login = inputData.getString("login") ?: return Result.failure()
        val account = AppGraph.store.accounts.firstOrNull { it.login == login } ?: return Result.failure()
        val message = MessageSummary(
            id = inputData.getString("id") ?: return Result.failure(),
            folder = MessageFolder.INBOX,
            correspondent = inputData.getString("correspondent") ?: "",
            subject = inputData.getString("subject") ?: "",
            date = inputData.getString("date") ?: "",
            unread = true,
            hasAttachments = false,
        )
        val multiple = AppGraph.store.accounts.size > 1
        when (inputData.getString("action")) {
            MARK_READ -> {
                MessageNotifier.cancel(applicationContext, login, message.id)
                runCatching { AppGraph.markRead(account, message) }
            }
            REPLY -> {
                val text = inputData.getString("reply")?.trim().orEmpty()
                if (text.isEmpty()) return Result.success()
                if (message.id.startsWith("test-")) {
                    // The test notification from the phone app: nothing goes to Librus.
                    MessageNotifier.show(applicationContext, account, message, multiple, replies = listOf(text), note = "To był test, nic nie wysłano.")
                    return Result.success()
                }
                try {
                    AppGraph.source(account).reply(message, text)
                    MessageNotifier.show(applicationContext, account, message, multiple, replies = listOf(text))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    MessageNotifier.show(applicationContext, account, message, multiple, note = "Nie wysłano odpowiedzi. ${e.message ?: ""}".trim())
                }
            }
        }
        return Result.success()
    }

    // Expedited work on Android 11 and older needs a notification while it runs.
    override suspend fun getForegroundInfo() = androidx.work.ForegroundInfo(
        0x5157,
        androidx.core.app.NotificationCompat.Builder(applicationContext, MessageNotifier.CHANNEL)
            .setSmallIcon(pl.prywatny.dziennik.auto.R.drawable.ic_notification)
            .setContentTitle("Dziennik: wysyłanie")
            .setSilent(true)
            .build(),
    )

    companion object {
        const val REPLY = "pl.prywatny.dziennik.auto.REPLY"
        const val MARK_READ = "pl.prywatny.dziennik.auto.MARK_READ"
    }
}
