package pl.prywatny.dziennik.auto.car

import android.app.Application
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.prywatny.dziennik.auto.AppGraph
import pl.prywatny.dziennik.auto.data.Sealer
import pl.prywatny.dziennik.auto.data.Store
import pl.prywatny.dziennik.auto.librus.DemoSource

/** The car screens build valid templates from the demo (fake) data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CarScreensTest {
    private lateinit var car: TestCarContext

    /** Robolectric has no Android Keystore; the test store keeps accounts unencrypted. */
    private object PlainSealer : Sealer {
        override fun encrypt(plain: String) = plain
        override fun decrypt(encoded: String) = encoded
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("dziennik", 0).edit().clear().commit()
        AppGraph.initForTests(Store(context, PlainSealer))
        car = TestCarContext.createCarContext(context)
    }

    private fun rows(template: ListTemplate): List<Row> = template.singleList!!.items.map { it as Row }

    @Test
    fun signInWithoutAccount() {
        val template = SignInScreen(car).onGetTemplate()
        assertTrue(template is MessageTemplate)
        assertEquals("Tryb demo", (template as MessageTemplate).actions.first().title.toString())
    }

    @Test
    fun todayAndDetailScreensWithDemoData() = runBlocking {
        val account = AppGraph.login(DemoSource.LOGIN, "demo")
        AppGraph.refresh(account)
        AppGraph.refreshInbox(account)

        val today = TodayScreen(car)
        ScreenController(today).moveToState(Lifecycle.State.RESUMED)
        val list = today.onGetTemplate() as ListTemplate
        assertEquals("Uczeń Demonstracyjny", list.title.toString())
        val titles = rows(list).map { it.title.toString() }
        assertEquals(5, titles.size)
        assertTrue(titles[1].startsWith("Szczęśliwy numerek: 13"))
        assertTrue(titles[3].startsWith("Wiadomości: 1 nowe"))

        val lessons = LessonsScreen(car).onGetTemplate()
        assertTrue(lessons is ListTemplate || lessons is MessageTemplate)
        assertTrue(rows(AgendaScreen(car).onGetTemplate() as ListTemplate).isNotEmpty())
        assertEquals(6, rows(GradesScreen(car).onGetTemplate() as ListTemplate).size)
        val messages = MessagesScreen(car).onGetTemplate() as ListTemplate
        assertTrue(rows(messages).first().title.toString().startsWith("● "))
    }
}
