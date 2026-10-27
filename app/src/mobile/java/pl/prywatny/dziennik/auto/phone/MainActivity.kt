package pl.prywatny.dziennik.auto.phone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.prywatny.dziennik.auto.Labels
import pl.prywatny.dziennik.auto.Today
import pl.prywatny.dziennik.auto.librus.DemoSource
import pl.prywatny.dziennik.auto.librus.MessageDetail
import pl.prywatny.dziennik.auto.librus.MessageSummary
import pl.prywatny.dziennik.auto.librus.StudentData

private val Accent = Color(0xFFE86B2E)

/** Phone app: log in, see today at a glance, and set up messages for Android Auto. */
class MainActivity : ComponentActivity() {
    private val vm: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Accent) else lightColorScheme(primary = Accent)) {
                if (vm.account == null || vm.addingAccount) LoginScreen(vm) else MainScreen(vm)
            }
        }
    }
}

@Composable
private fun LoginScreen(vm: PhoneViewModel) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(l: String, p: String) {
        if (busy) return
        busy = true
        scope.launch {
            error = vm.login(l, p)
            busy = false
        }
    }
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Dziennik", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Nieoficjalny klient Librus Synergia z Android Auto", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(login, { login = it }, label = { Text("Login Synergii") }, placeholder = { Text("np. 1234567u") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("Hasło") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { run(login, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Logowanie…" else "Zaloguj") }
            OutlinedButton(onClick = { run(DemoSource.LOGIN, "demo") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Wypróbuj na danych demonstracyjnych") }
            if (vm.account != null) TextButton(onClick = { vm.addingAccount = false }) { Text("Anuluj") }
            Text(
                "Użyj loginu i hasła do synergia.librus.pl. Hasło jest zaszyfrowane kluczem tego telefonu, bo bez niego nie da się odnowić sesji Synergii.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: PhoneViewModel) {
    var menu by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<MessageSummary?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.data?.studentName ?: vm.account?.studentName ?: "Dziennik", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Odśwież") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Więcej") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        vm.accounts.filter { it.login != vm.account?.login }.forEach { a ->
                            DropdownMenuItem(text = { Text("Przełącz: ${a.studentName}") }, onClick = { menu = false; vm.select(a.login) })
                        }
                        DropdownMenuItem(text = { Text("Dodaj ucznia") }, onClick = { menu = false; vm.addingAccount = true })
                        DropdownMenuItem(text = { Text("Usuń ucznia z telefonu") }, onClick = { menu = false; vm.removeCurrent() })
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            val data = vm.data
            if (data == null) {
                item { Text("Brak danych. Odśwież, aby pobrać je z Librusa.") }
            } else {
                item { TodayCard(data) }
                item { LuckyAndAgendaCard(data) }
                item { MessagesCard(vm.inbox, onOpen = { opened = it }) }
                item { GradesCard(data) }
            }
            item { AndroidAutoCard(vm) }
            data?.let { d ->
                item {
                    Text(
                        "Zaktualizowano: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(d.fetchedAt))}" +
                            if (d.warnings.isNotEmpty()) "\nNie wczytało się: ${d.warnings.joinToString("; ")}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    opened?.let { MessageDialog(vm, it, onClose = { opened = null }) }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun TodayCard(data: StudentData) {
    val next = Today.nextLesson(data)
    val day = Today.schoolDay(data)
    SectionCard(if (day != null) "Plan: ${Labels.day(day.first)}" else "Plan lekcji") {
        next?.let {
            Text(
                (if (it.isNow) "Teraz: " else "Następna: ") + (it.lesson.subject ?: "Lekcja"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
        }
        day?.second?.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${l.number}", Modifier.width(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${l.start}–${l.end}", Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(l.subject, Today.lessonStatus(l)).joinToString(" · "),
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (next?.lesson == l) FontWeight.Bold else null,
                )
                Text(l.classroom ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        } ?: Text("Brak lekcji w pobranym planie.")
    }
}

@Composable
private fun LuckyAndAgendaCard(data: StudentData) {
    SectionCard("Numerek i terminarz") {
        val lucky = Today.lucky(data)
        Text(lucky?.let { "Szczęśliwy numerek: ${it.number} (${Labels.day(it.day)})" } ?: "Szczęśliwy numerek: brak", fontWeight = FontWeight.SemiBold)
        val events = Today.upcomingEvents(data).take(4)
        if (events.isEmpty()) Text("Nic w terminarzu na 2 tygodnie.")
        events.forEach { e -> Text("${Labels.day(e.day)}: ${listOfNotNull(e.category, e.subject).joinToString(" · ")}") }
    }
}

@Composable
private fun MessagesCard(inbox: List<MessageSummary>?, onOpen: (MessageSummary) -> Unit) {
    SectionCard("Wiadomości") {
        if (inbox == null) Text("Odśwież, aby wczytać wiadomości.")
        else if (inbox.isEmpty()) Text("Brak wiadomości.")
        inbox?.take(5)?.forEach { m ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(vertical = 4.dp)) {
                Text(m.subject.ifEmpty { "(bez tematu)" }, fontWeight = if (m.unread) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.correspondent} · ${m.date.take(16)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GradesCard(data: StudentData) {
    SectionCard("Ostatnie oceny") {
        val grades = Today.recentGrades(data, 5)
        if (grades.isEmpty()) Text("Brak ocen.")
        grades.forEach { g ->
            Row {
                Text(g.name, Modifier.width(40.dp), fontWeight = FontWeight.Bold)
                Text(Today.subjectName(data, g.subjectId), Modifier.weight(1f))
                Text(Labels.added(g.addedDate), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AndroidAutoCard(vm: PhoneViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.setNotifications(granted) }
    SectionCard("Android Auto") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Powiadomienia o nowych wiadomościach (czytane w aucie)", Modifier.weight(1f))
            Switch(checked = vm.notificationsEnabled, onCheckedChange = { on ->
                if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setNotifications(on)
            })
        }
        Text(
            "Skrzynka jest sprawdzana co ok. 30 minut. W aucie możesz odpowiedzieć głosem: odpowiedź trafi do nadawcy w Librusie po potwierdzeniu w Asystencie.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.testNotification() }) { Text("Wyślij testowe powiadomienie") }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Ekran „Dziś” w aucie", fontWeight = FontWeight.SemiBold)
        Text(
            "Android Auto pokazuje ekrany aplikacji spoza Google Play tylko z włączonymi „Nieznanymi źródłami”: " +
                "otwórz ustawienia Android Auto (zwykle Ustawienia → Połączone urządzenia → Ustawienia połączeń → Android Auto), " +
                "stuknij 10× „Wersja” na dole, potem menu ⋮ → Ustawienia programisty → Nieznane źródła. " +
                "Potem Dziennik pojawi się w menu aplikacji w aucie.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MessageDialog(vm: PhoneViewModel, message: MessageSummary, onClose: () -> Unit) {
    var detail by remember { mutableStateOf<Result<MessageDetail>?>(null) }
    LaunchedEffect(message.id) { detail = vm.open(message) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Zamknij") } },
        title = { Text(message.subject.ifEmpty { "(bez tematu)" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${message.correspondent} · ${message.date}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(8.dp))
                Text(
                    detail?.fold(
                        onSuccess = { d -> d.text + if (d.attachments.isNotEmpty()) "\n\nZałączniki: " + d.attachments.joinToString { it.filename } else "" },
                        onFailure = { it.message ?: "Błąd" },
                    ) ?: "Wczytywanie…",
                )
            }
        },
    )
}
