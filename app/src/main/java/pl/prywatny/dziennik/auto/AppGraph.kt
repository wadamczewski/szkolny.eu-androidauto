package pl.prywatny.dziennik.auto

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.prywatny.dziennik.auto.data.Account
import pl.prywatny.dziennik.auto.data.MessagesCache
import pl.prywatny.dziennik.auto.data.Store
import pl.prywatny.dziennik.auto.librus.Day
import pl.prywatny.dziennik.auto.librus.DemoSource
import pl.prywatny.dziennik.auto.librus.MessageFolder
import pl.prywatny.dziennik.auto.librus.MessageSummary
import pl.prywatny.dziennik.auto.librus.StudentClient
import pl.prywatny.dziennik.auto.librus.StudentData
import pl.prywatny.dziennik.auto.librus.StudentSource
import pl.prywatny.dziennik.auto.librus.SynergiaSession

/**
 * One place for accounts, cached data and Librus clients, shared by the phone screens, the car
 * screens and the background message check (they all run in the same process).
 */
object AppGraph {
    lateinit var store: Store
        private set
    private val sources = mutableMapOf<String, StudentSource>()
    private val _changes = MutableStateFlow(0)

    /** Bumped whenever accounts or cached data change. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (!::store.isInitialized) store = Store(context.applicationContext)
    }

    /** Tests: a store without the Android Keystore (Robolectric has none). */
    @Synchronized
    fun initForTests(store: Store) {
        this.store = store
        synchronized(sources) { sources.clear() }
    }

    fun changed() = _changes.update { it + 1 }

    fun source(account: Account): StudentSource = synchronized(sources) {
        sources.getOrPut(account.login) {
            if (account.login == DemoSource.LOGIN) DemoSource()
            else StudentClient(SynergiaSession(account.login, account.password))
        }
    }

    /** First login of an account; it becomes the current one. */
    suspend fun login(login: String, password: String): Account {
        val candidate = Account(login.trim(), password, login.trim(), false)
        synchronized(sources) { sources.remove(candidate.login) }
        val profile = source(candidate).profile()
        val account = Account(profile.login, password, profile.studentName, profile.isParent)
        store.save(account)
        changed()
        return account
    }

    fun select(login: String) {
        store.select(login)
        changed()
    }

    fun remove(login: String) {
        synchronized(sources) { sources.remove(login) }
        store.remove(login)
        changed()
    }

    fun data(account: Account?): StudentData? = account?.let { store.data(it.login) }

    fun isStale(account: Account?): Boolean = data(account).let { it == null || System.currentTimeMillis() - it.fetchedAt > STALE_MS }

    suspend fun refresh(account: Account): StudentData {
        val data = source(account).sync(Day.defaultTimetableWeek())
        store.saveData(account.login, data)
        changed()
        return data
    }

    fun inbox(account: Account?): List<MessageSummary>? = account?.let { store.messages(it.login).inbox }

    suspend fun refreshInbox(account: Account): List<MessageSummary> {
        val list = source(account).messages(MessageFolder.INBOX)
        store.saveMessages(account.login, MessagesCache(inbox = list, sent = store.messages(account.login).sent))
        changed()
        return list
    }

    /** Marks a message as read on Synergia and in the cached inbox. */
    suspend fun markRead(account: Account, message: MessageSummary) {
        source(account).markRead(message)
        val cache = store.messages(account.login)
        store.saveMessages(account.login, cache.copy(inbox = cache.inbox?.map { if (it.id == message.id) it.copy(unread = false) else it }))
        changed()
    }

    const val STALE_MS = 15 * 60 * 1000L
}
