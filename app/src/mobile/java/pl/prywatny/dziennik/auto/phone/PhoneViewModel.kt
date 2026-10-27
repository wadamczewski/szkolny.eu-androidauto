package pl.prywatny.dziennik.auto.phone

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import pl.prywatny.dziennik.auto.AppGraph
import pl.prywatny.dziennik.auto.data.Account
import pl.prywatny.dziennik.auto.librus.MessageDetail
import pl.prywatny.dziennik.auto.librus.MessageFolder
import pl.prywatny.dziennik.auto.librus.MessageSummary
import pl.prywatny.dziennik.auto.librus.StudentData
import pl.prywatny.dziennik.auto.messages.MessageNotifier

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    init {
        AppGraph.init(application)
    }

    var accounts by mutableStateOf(AppGraph.store.accounts)
        private set
    var account by mutableStateOf(AppGraph.store.current)
        private set
    var data by mutableStateOf<StudentData?>(AppGraph.data(account))
        private set
    var inbox by mutableStateOf(AppGraph.inbox(account))
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notificationsEnabled by mutableStateOf(AppGraph.store.notificationsEnabled)
        private set
    var addingAccount by mutableStateOf(false)

    init {
        viewModelScope.launch { AppGraph.changes.collect { reload() } }
        if (AppGraph.isStale(account)) refresh()
    }

    private fun reload() {
        accounts = AppGraph.store.accounts
        account = AppGraph.store.current
        data = AppGraph.data(account)
        inbox = AppGraph.inbox(account)
    }

    fun refresh() {
        val current = account ?: return
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                AppGraph.refresh(current)
                AppGraph.refreshInbox(current)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    /** Returns an error message or null on success. */
    suspend fun login(login: String, password: String): String? {
        if (login.isBlank() || password.isEmpty()) return "Wpisz login i hasło."
        return try {
            AppGraph.login(login, password)
            addingAccount = false
            refresh()
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.message ?: "Błąd logowania"
        }
    }

    fun select(login: String) {
        AppGraph.select(login)
        if (AppGraph.isStale(AppGraph.store.current)) refresh()
    }

    fun removeCurrent() {
        account?.let { AppGraph.remove(it.login) }
    }

    fun setNotifications(enabled: Boolean) {
        AppGraph.store.notificationsEnabled = enabled
        notificationsEnabled = enabled
    }

    /** Opening a message marks it as read on Synergia, like opening it there. */
    suspend fun open(message: MessageSummary): Result<MessageDetail> {
        val current = account ?: return Result.failure(IllegalStateException())
        return runCatching {
            AppGraph.source(current).message(message).also {
                if (message.unread) runCatching { AppGraph.markRead(current, message) }
            }
        }
    }

    /** A fake message notification to check how it looks and sounds in Android Auto. */
    fun testNotification() {
        val current: Account = account ?: return
        MessageNotifier.show(
            getApplication(),
            current,
            MessageSummary(
                id = "test-${System.currentTimeMillis()}",
                folder = MessageFolder.INBOX,
                correspondent = "Dziennik (test)",
                subject = "Tak będą wyglądać nowe wiadomości z Librusa",
                date = "",
                unread = true,
                hasAttachments = false,
            ),
            multipleStudents = false,
        )
    }
}
