package pl.prywatny.dziennik.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.prywatny.dziennik.auto.librus.AgendaEvent
import pl.prywatny.dziennik.auto.librus.Lesson
import pl.prywatny.dziennik.auto.librus.LessonKind
import pl.prywatny.dziennik.auto.librus.LuckyNumber
import pl.prywatny.dziennik.auto.librus.Recipient
import pl.prywatny.dziennik.auto.librus.StudentData
import pl.prywatny.dziennik.auto.librus.SynergiaParser
import pl.prywatny.dziennik.auto.librus.TimetableDay
import java.time.LocalDateTime

/** Reply recipients and the "now" logic of the car screens, on synthetic (fake) data. */
class AutoTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun recipientsAndReplyTarget() {
        val groups = SynergiaParser.recipientGroups(text("groups.html"))
        assertEquals(listOf("nauczyciel", "wychowawca"), groups.map { it.value })
        assertEquals("Nauczyciele", groups.first().name)
        val recipients = SynergiaParser.recipients(text("recipients.html"))
        assertEquals(listOf(Recipient("500", "Testowy Jan"), Recipient("501", "Przykładowa Anna")), recipients)
        // Inbox sender "Testowy Jan (Nauczyciel)" → recipient 500, like the iOS reply.
        assertEquals("500", SynergiaParser.matchRecipient("Testowy Jan (Nauczyciel)", recipients)?.id)
        assertEquals("501", SynergiaParser.matchRecipient("Anna Przykładowa", recipients)?.id)
        assertNull(SynergiaParser.matchRecipient("Sekretariat (Administracja)", recipients))
        assertEquals("Wiadomość została wysłana", SynergiaParser.sendResult("<div class=\"container-background\"><p>Wiadomość została wysłana</p></div>"))
        assertEquals("", SynergiaParser.sendResult("<p>coś innego</p>"))
    }

    private val day = "2026-10-27"
    private val next = "2026-10-28"
    private fun lesson(d: String, n: Int, start: String, end: String, subject: String, kind: LessonKind = LessonKind.NORMAL) =
        Lesson(d, n, start, end, kind, subject, "Nauczyciel Testowy", "12")

    private val data = StudentData(
        fetchedAt = 0, studentName = "Uczeń Testowy", accountName = null, subjects = emptyList(), grades = emptyList(),
        events = listOf(
            AgendaEvent(1, next, null, 2, "rozdział 1", "Sprawdzian", null, "Historia", null, null),
            AgendaEvent(2, "2026-10-01", null, 1, "", "Kartkówka", null, "Biologia", null, null),
        ),
        attendance = emptyList(), notices = emptyList(), announcements = emptyList(),
        luckyNumber = LuckyNumber(day, 13), timetableWeek = "2026-10-26",
        timetable = listOf(
            TimetableDay(day, listOf(
                lesson(day, 1, "08:00", "08:45", "Matematyka"),
                lesson(day, 2, "08:55", "09:40", "Fizyka", LessonKind.CANCELLED),
                lesson(day, 3, "09:50", "10:35", "Historia"),
            )),
            TimetableDay(next, listOf(lesson(next, 1, "08:00", "08:45", "Biologia"))),
        ),
        warnings = emptyList(),
    )

    private fun at(d: String, t: String) = LocalDateTime.parse("${d}T$t")

    @Test
    fun nextLessonFollowsTheClock() {
        assertEquals(Today.NextLesson(data.timetable[0].lessons[0], isNow = true), Today.nextLesson(data, at(day, "08:10")))
        // The cancelled lesson is skipped.
        assertEquals("Historia", Today.nextLesson(data, at(day, "08:50"))?.lesson?.subject)
        assertEquals(next, Today.nextLesson(data, at(day, "11:00"))?.lesson?.day)
        assertNull(Today.nextLesson(data, at(next, "09:00")))
    }

    @Test
    fun schoolDayLuckyAndAgenda() {
        assertEquals(day, Today.schoolDay(data, at(day, "09:00"))?.first)
        assertEquals(next, Today.schoolDay(data, at(day, "15:00"))?.first)
        assertEquals(13, Today.lucky(data, day)?.number)
        assertNull(Today.lucky(data, next))
        assertEquals(listOf("Sprawdzian"), Today.upcomingEvents(data, day).map { it.category })
        assertEquals("odwołana", Today.lessonStatus(data.timetable[0].lessons[1]))
        assertTrue(Labels.day(next, day) == "Jutro" && Labels.day(day, day) == "Dziś")
    }
}
