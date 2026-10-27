package pl.prywatny.dziennik.auto

import pl.prywatny.dziennik.auto.librus.AgendaEvent
import pl.prywatny.dziennik.auto.librus.Day
import pl.prywatny.dziennik.auto.librus.Grade
import pl.prywatny.dziennik.auto.librus.Lesson
import pl.prywatny.dziennik.auto.librus.LessonKind
import pl.prywatny.dziennik.auto.librus.LuckyNumber
import pl.prywatny.dziennik.auto.librus.StudentData
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What matters "now" in the car: the same rules as the CarPlay widgets of the iOS app. */
object Today {
    data class NextLesson(val lesson: Lesson, val isNow: Boolean)

    private fun at(day: String, hhmm: String): LocalDateTime? = runCatching {
        LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(hhmm))
    }.getOrNull()

    private fun isCancelled(l: Lesson) = l.kind == LessonKind.CANCELLED || l.kind == LessonKind.SHIFTED_SOURCE

    /** The lesson running now, else the next one today, else the first one of the next school day. */
    fun nextLesson(data: StudentData, now: LocalDateTime = LocalDateTime.now()): NextLesson? {
        val upcoming = data.timetable.flatMap { it.lessons }
            .mapNotNull { l -> val start = at(l.day, l.start); val end = at(l.day, l.end); if (start != null && end != null) Triple(l, start, end) else null }
            .filter { it.third.isAfter(now) }
            .sortedBy { it.second }
        val first = upcoming.firstOrNull { !isCancelled(it.first) } ?: upcoming.firstOrNull() ?: return null
        return NextLesson(first.first, !first.second.isAfter(now))
    }

    /** Lessons of today while school is on, else of the next day with lessons. */
    fun schoolDay(data: StudentData, now: LocalDateTime = LocalDateTime.now()): Pair<String, List<Lesson>>? {
        val days = data.timetable.filter { it.lessons.isNotEmpty() && it.day >= now.toLocalDate().toString() }.sortedBy { it.day }
        val today = days.firstOrNull { it.day == now.toLocalDate().toString() }
        val lastEnd = today?.lessons?.mapNotNull { at(it.day, it.end) }?.maxOrNull()
        val day = if (today != null && lastEnd != null && lastEnd.isAfter(now)) today else days.firstOrNull { it.day != now.toLocalDate().toString() } ?: today
        return day?.let { it.day to it.lessons }
    }

    fun upcomingEvents(data: StudentData, today: String = Day.today(), days: Long = 14): List<AgendaEvent> =
        data.events.filter { it.day >= today && it.day <= Day.addDays(today, days) }.sortedBy { it.day }

    fun lucky(data: StudentData, today: String = Day.today()): LuckyNumber? = data.luckyNumber?.takeIf { it.day >= today }

    fun recentGrades(data: StudentData, count: Int = 6): List<Grade> =
        data.grades.filter { it.addedDate != null }.sortedByDescending { it.addedDate }.take(count)

    fun subjectName(data: StudentData, id: Int) = data.subjects.firstOrNull { it.id == id }?.name ?: "Przedmiot"

    fun lessonStatus(l: Lesson): String? = when (l.kind) {
        LessonKind.NORMAL -> null
        LessonKind.CANCELLED -> "odwołana"
        LessonKind.CHANGE -> l.oldSubject?.let { "zastępstwo za: $it" } ?: "zastępstwo"
        LessonKind.SHIFTED_SOURCE -> "przeniesiona"
        LessonKind.SHIFTED_TARGET -> "przeniesiona tutaj"
    }
}

/** Polish date labels. */
object Labels {
    private val DAY_SHORT = listOf("pon", "wt", "śr", "czw", "pt", "sob", "nd")
    private val MONTHS_SHORT = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")

    fun day(day: String, today: String = Day.today()): String = when (day) {
        today -> "Dziś"
        Day.addDays(today, 1) -> "Jutro"
        else -> LocalDate.parse(day).let { "${DAY_SHORT[it.dayOfWeek.value - 1]} ${it.dayOfMonth} ${MONTHS_SHORT[it.monthValue - 1]}" }
    }

    fun added(dateTime: String?): String = dateTime?.take(10)?.let { day(it) } ?: ""
}
