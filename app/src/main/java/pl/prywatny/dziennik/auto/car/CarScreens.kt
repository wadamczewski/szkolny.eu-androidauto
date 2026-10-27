package pl.prywatny.dziennik.auto.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.annotation.DrawableRes
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pl.prywatny.dziennik.auto.AppGraph
import pl.prywatny.dziennik.auto.Labels
import pl.prywatny.dziennik.auto.R
import pl.prywatny.dziennik.auto.Today
import pl.prywatny.dziennik.auto.data.Account
import pl.prywatny.dziennik.auto.librus.DemoSource
import pl.prywatny.dziennik.auto.librus.LessonKind
import pl.prywatny.dziennik.auto.librus.MessageSummary
import pl.prywatny.dziennik.auto.librus.StudentData

/**
 * Car screens (Android Auto, and Android Automotive OS in the `automotive` flavor). Templates keep
 * them glanceable: short rows, no long text while driving (messages open only when parked; in
 * motion Android Auto reads the notifications aloud instead).
 */
class DziennikCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = DziennikSession()
}

class DziennikSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        AppGraph.init(carContext)
        return if (AppGraph.store.current == null) SignInScreen(carContext) else TodayScreen(carContext)
    }
}

private fun CarContext.icon(@DrawableRes res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { tint?.let { setTint(it) } }.build()

/** Base for screens showing the current student's data: reloads on data changes, refreshes when stale. */
abstract class DataScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    protected var busy = false
    private var job: Job? = null
    private var seenVersion = AppGraph.changes.value

    init {
        lifecycle.addObserver(this)
    }

    protected val account: Account? get() = AppGraph.store.current
    protected val data: StudentData? get() = AppGraph.data(account)

    override fun onStart(owner: LifecycleOwner) {
        job = lifecycleScope.launch {
            AppGraph.changes.collect { if (it != seenVersion) { seenVersion = it; invalidate() } }
        }
        if (AppGraph.isStale(account)) refresh(quiet = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
    }

    protected fun refresh(quiet: Boolean = false) {
        val current = account ?: return
        if (busy) return
        busy = true
        invalidate()
        lifecycleScope.launch {
            try {
                AppGraph.refresh(current)
                if (!quiet) CarToast.makeText(carContext, "Zaktualizowano", CarToast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CarToast.makeText(carContext, e.message ?: "Błąd odświeżania", CarToast.LENGTH_LONG).show()
            } finally {
                busy = false
                invalidate()
            }
        }
    }

    protected fun refreshAction(): Action = Action.Builder()
        .setIcon(carContext.icon(R.drawable.ic_refresh))
        .setOnClickListener { refresh() }
        .build()

    /** "Loading" list or a message with a refresh button when there is no data yet. */
    protected fun noData(title: String): Template =
        if (busy || account == null) ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setLoading(true).build()
        else MessageTemplate.Builder("Brak danych. Odśwież, aby pobrać je z Librusa.")
            .setTitle(title).setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle("Odśwież").setOnClickListener { refresh() }.build())
            .build()
}

class TodayScreen(carContext: CarContext) : DataScreen(carContext) {
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // The unread count needs the inbox; load it once in the background if it isn't cached.
        val current = account ?: return
        if (AppGraph.inbox(current) == null) {
            lifecycleScope.launch { runCatching { AppGraph.refreshInbox(current) } }
        }
    }

    override fun onGetTemplate(): Template {
        val account = account ?: return SignInScreen(carContext).onGetTemplate()
        val data = data ?: return if (busy) {
            ListTemplate.Builder().setTitle(account.studentName).setHeaderAction(Action.APP_ICON).setLoading(true).build()
        } else {
            MessageTemplate.Builder("Brak danych. Odśwież, aby pobrać je z Librusa.")
                .setTitle(account.studentName).setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("Odśwież").setOnClickListener { refresh() }.build())
                .build()
        }
        val items = ItemList.Builder()

        val next = Today.nextLesson(data)
        items.addItem(
            Row.Builder()
                .setTitle(next?.let { (if (it.isNow) "Teraz: " else if (it.lesson.day == pl.prywatny.dziennik.auto.librus.Day.today()) "Następna: " else "${Labels.day(it.lesson.day)}: ") + (it.lesson.subject ?: "Lekcja") } ?: "Brak lekcji w planie")
                .apply { next?.let { addText(listOfNotNull("${it.lesson.start}–${it.lesson.end}", it.lesson.classroom, Today.lessonStatus(it.lesson)).joinToString(" · ")) } }
                .setImage(carContext.icon(R.drawable.ic_lesson))
                .setOnClickListener { screenManager.push(LessonsScreen(carContext)) }
                .setBrowsable(true)
                .build(),
        )

        val lucky = Today.lucky(data)
        items.addItem(
            Row.Builder()
                .setTitle(lucky?.let { "Szczęśliwy numerek: ${it.number}" } ?: "Szczęśliwy numerek: brak")
                .apply { lucky?.let { addText(Labels.day(it.day)) } }
                .setImage(carContext.icon(R.drawable.ic_star))
                .build(),
        )

        val events = Today.upcomingEvents(data)
        items.addItem(
            Row.Builder()
                .setTitle(events.firstOrNull()?.let { listOfNotNull(it.category, it.subject).joinToString(" · ") } ?: "Terminarz: nic w 2 tygodnie")
                .apply { events.firstOrNull()?.let { e -> addText(listOfNotNull(Labels.day(e.day), e.lessonNumber?.let { "lekcja $it" }, if (events.size > 1) "+${events.size - 1} więcej" else null).joinToString(" · ")) } }
                .setImage(carContext.icon(R.drawable.ic_calendar))
                .setOnClickListener { screenManager.push(AgendaScreen(carContext)) }
                .setBrowsable(true)
                .build(),
        )

        val inbox = AppGraph.inbox(account)
        val unread = inbox?.filter { it.unread }.orEmpty()
        items.addItem(
            Row.Builder()
                .setTitle(if (inbox == null) "Wiadomości" else if (unread.isEmpty()) "Wiadomości: brak nowych" else "Wiadomości: ${unread.size} nowe")
                .apply { unread.firstOrNull()?.let { addText("Od: ${it.correspondent}") } }
                .setImage(carContext.icon(R.drawable.ic_mail))
                .setOnClickListener { screenManager.push(MessagesScreen(carContext)) }
                .setBrowsable(true)
                .build(),
        )

        val grades = Today.recentGrades(data, 3)
        items.addItem(
            Row.Builder()
                .setTitle("Ostatnie oceny")
                .addText(grades.joinToString(", ") { "${it.name} (${Today.subjectName(data, it.subjectId).lowercase()})" }.ifEmpty { "brak" })
                .setImage(carContext.icon(R.drawable.ic_grade))
                .setOnClickListener { screenManager.push(GradesScreen(carContext)) }
                .setBrowsable(true)
                .build(),
        )

        val strip = ActionStrip.Builder().addAction(refreshAction())
        if (AppGraph.store.accounts.size > 1) {
            strip.addAction(
                Action.Builder().setTitle("Uczeń").setOnClickListener {
                    val list = AppGraph.store.accounts
                    val next = list[(list.indexOfFirst { it.login == account.login } + 1) % list.size]
                    AppGraph.select(next.login)
                    CarToast.makeText(carContext, next.studentName, CarToast.LENGTH_SHORT).show()
                }.build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle(account.studentName)
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(strip.build())
            .setLoading(false)
            .setSingleList(items.build())
            .build()
    }
}

class LessonsScreen(carContext: CarContext) : DataScreen(carContext) {
    override fun onGetTemplate(): Template {
        val data = data ?: return noData("Plan lekcji")
        val (day, lessons) = Today.schoolDay(data) ?: return MessageTemplate.Builder("Brak lekcji w pobranym planie.")
            .setTitle("Plan lekcji").setHeaderAction(Action.BACK).build()
        val now = Today.nextLesson(data)
        val items = ItemList.Builder()
        lessons.take(maxRows()).forEach { l ->
            items.addItem(
                Row.Builder()
                    .setTitle("${l.number}. ${l.subject ?: "Lekcja"}" + if (now?.lesson == l && now.isNow) " · teraz" else "")
                    .addText(listOfNotNull("${l.start}–${l.end}", l.classroom, Today.lessonStatus(l)).joinToString(" · "))
                    .apply { if (l.kind == LessonKind.CANCELLED) setImage(carContext.icon(R.drawable.ic_cancel, CarColor.RED)) }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Plan: ${Labels.day(day)}")
            .setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(refreshAction()).build())
            .setSingleList(items.build())
            .build()
    }

    private fun maxRows() = runCatching {
        carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6)
}

class AgendaScreen(carContext: CarContext) : DataScreen(carContext) {
    override fun onGetTemplate(): Template {
        val data = data ?: return noData("Terminarz")
        val events = Today.upcomingEvents(data)
        if (events.isEmpty()) {
            return MessageTemplate.Builder("Nic zaplanowanego na najbliższe 2 tygodnie.").setTitle("Terminarz").setHeaderAction(Action.BACK).build()
        }
        val items = ItemList.Builder()
        events.take(6).forEach { e ->
            items.addItem(
                Row.Builder()
                    .setTitle(listOfNotNull(e.category, e.subject).joinToString(" · "))
                    .addText(listOfNotNull(Labels.day(e.day), e.lessonNumber?.let { "lekcja $it" }).joinToString(" · "))
                    .build(),
            )
        }
        return ListTemplate.Builder().setTitle("Terminarz").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}

class GradesScreen(carContext: CarContext) : DataScreen(carContext) {
    override fun onGetTemplate(): Template {
        val data = data ?: return noData("Oceny")
        val grades = Today.recentGrades(data)
        if (grades.isEmpty()) return MessageTemplate.Builder("Brak ocen.").setTitle("Oceny").setHeaderAction(Action.BACK).build()
        val items = ItemList.Builder()
        grades.forEach { g ->
            items.addItem(
                Row.Builder()
                    .setTitle("${g.name} · ${Today.subjectName(data, g.subjectId)}")
                    .addText(listOfNotNull(g.category.ifEmpty { null }, "waga ${if (g.weight % 1.0 == 0.0) g.weight.toInt() else g.weight}", Labels.added(g.addedDate)).joinToString(" · "))
                    .build(),
            )
        }
        return ListTemplate.Builder().setTitle("Ostatnie oceny").setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }
}

class MessagesScreen(carContext: CarContext) : DataScreen(carContext) {
    private var loadingInbox = false

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        loadInbox()
    }

    private fun loadInbox() {
        val current = account ?: return
        if (loadingInbox) return
        loadingInbox = true
        invalidate()
        lifecycleScope.launch {
            try {
                AppGraph.refreshInbox(current)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CarToast.makeText(carContext, e.message ?: "Błąd", CarToast.LENGTH_LONG).show()
            } finally {
                loadingInbox = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val inbox = AppGraph.inbox(account)
        if (inbox == null) return ListTemplate.Builder().setTitle("Wiadomości").setHeaderAction(Action.BACK).setLoading(true).build()
        if (inbox.isEmpty()) return MessageTemplate.Builder("Brak wiadomości.").setTitle("Wiadomości").setHeaderAction(Action.BACK).build()
        val items = ItemList.Builder()
        inbox.take(6).forEach { m ->
            items.addItem(
                Row.Builder()
                    .setTitle((if (m.unread) "● " else "") + m.subject.ifEmpty { "(bez tematu)" })
                    .addText("Od: ${m.correspondent} · ${m.date.take(10).let { if (it.length == 10) Labels.day(it) else it }}")
                    .setOnClickListener { screenManager.push(MessageScreen(carContext, m)) }
                    .setBrowsable(true)
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Wiadomości")
            .setHeaderAction(Action.BACK)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_refresh)).setOnClickListener { loadInbox() }.build()).build())
            .setLoading(loadingInbox && inbox.isEmpty())
            .setSingleList(items.build())
            .build()
    }
}

/** One message, readable only when parked (LongMessageTemplate). Opening it marks it read on Synergia. */
class MessageScreen(carContext: CarContext, private val summary: MessageSummary) : Screen(carContext) {
    private var text: String? = null
    private var error: String? = null

    init {
        lifecycleScope.launch {
            val account = AppGraph.store.current ?: return@launch
            try {
                val detail = AppGraph.source(account).message(summary)
                text = detail.text.ifEmpty { "(pusta wiadomość)" } +
                    if (detail.attachments.isNotEmpty()) "\n\nZałączniki (pobierz w telefonie): " + detail.attachments.joinToString { it.filename } else ""
                if (summary.unread) runCatching { AppGraph.markRead(account, summary) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "Błąd"
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val body = text
        if (body == null) {
            return MessageTemplate.Builder(error ?: "Wczytywanie…").setTitle(summary.subject.ifEmpty { "Wiadomość" }).setHeaderAction(Action.BACK)
                .apply { if (error == null) setLoading(true) }.build()
        }
        val title = summary.subject.ifEmpty { "Wiadomość" }
        // LongMessageTemplate (shown only when parked) needs car API level 2.
        if (carContext.carAppApiLevel < 2) {
            return MessageTemplate.Builder("Od: ${summary.correspondent}. Treść przeczytasz na postoju albo w telefonie.")
                .setTitle(title).setHeaderAction(Action.BACK).build()
        }
        return LongMessageTemplate.Builder("Od: ${summary.correspondent}\n\n$body")
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
    }
}

/** No account yet: log in on the phone (Android Auto) or try the demo. */
class SignInScreen(carContext: CarContext) : Screen(carContext) {
    private val automotiveOs = carContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(
            if (automotiveOs) "Wersja na Android Automotive OS służy do podglądu ekranów. Konto Librusa dodasz w aplikacji Dziennik na telefonie z Android Auto. Tu możesz obejrzeć dane demonstracyjne."
            else "Zaloguj się w aplikacji Dziennik na telefonie loginem i hasłem Synergii. Możesz też obejrzeć dane demonstracyjne.",
        )
            .setTitle("Dziennik")
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder().setTitle("Tryb demo").setOnClickListener {
                    lifecycleScope.launch {
                        runCatching { AppGraph.login(DemoSource.LOGIN, "demo") }
                        screenManager.push(TodayScreen(carContext))
                    }
                }.build(),
            )
            .build()
}
