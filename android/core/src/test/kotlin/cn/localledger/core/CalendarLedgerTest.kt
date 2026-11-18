package cn.localledger.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class CalendarLedgerTest {
    private val november = YearMonth.of(2026, 11)
    private val selectedDay = november.atDay(18)
    private val ledger = Snapshot(
        groups = listOf(Group("expense", "个人", Direction.EXPENSE, active = false), Group("income", "收入", Direction.INCOME)),
        categories = listOf(Category("food", "expense", "饮食", active = false), Category("salary", "income", "工资")),
    )

    @Test fun calendarWeeksCoverWholeMonthAndAllowSixRowsAndLeapDay() {
        val sixRows = YearMonth.of(2026, 8).calendarDates()
        assertEquals(42, sixRows.size)
        assertEquals(LocalDate.of(2026, 7, 26), sixRows.first())
        assertEquals(LocalDate.of(2026, 9, 5), sixRows.last())
        val leapMonth = YearMonth.of(2028, 2).calendarDates()
        assertEquals((1..29).toList(), leapMonth.filter { it.monthValue == 2 }.map { it.dayOfMonth })
        assertEquals(28, YearMonth.of(2026, 2).calendarDates().size)
    }

    @Test fun expenseThresholdUsesGrossDailySpendAndIncludesArchivedHistory() {
        val breakfast = record("breakfast", "food", 1500, "2026-11-18T09:10")
        val lunch = record("lunch", "food", 3500, "2026-11-18T12:30")
        val income = record("income", "salary", 8000, "2026-11-18T15:00")
        val snapshot = ledger.copy(entries = listOf(
            breakfast, income, lunch,
            lunch.copy(id = "deleted", deletedAt = 1),
            lunch.copy(id = "previous-month", occurredAt = "2026-10-31T21:00"),
        ))
        val days = snapshot.daysIn(november)
        assertEquals(setOf(selectedDay), days.keys)
        val day = days.getValue(selectedDay)
        assertEquals(Summary(5000, 8000), day.summary)
        assertEquals(listOf(income, lunch, breakfast), day.entries)
        assertFalse(day.exceeds(5000))
        assertTrue(day.exceeds(4999))
        assertFalse(day.exceeds(null))
        val oneCentMore = snapshot.copy(entries = snapshot.entries + record("extra", "food", 1, "2026-11-18T16:00"))
        assertTrue(oneCentMore.daysIn(november).getValue(selectedDay).exceeds(5000))
    }

    @Test fun movingAnEditedRecordUpdatesBothDatesAndDirections() {
        val purchase = record("purchase", "food", 5700, "2026-11-18T12:34:56")
        val nextDay = selectedDay.plusDays(1)
        val snapshot = ledger.copy(entries = listOf(purchase))
        assertTrue(snapshot.daysIn(november).getValue(selectedDay).exceeds(5000))
        val edited = purchase.edited("salary", 5000, nextDay, "", "2026-11-19T06:00:00Z")
        val days = snapshot.copy(entries = listOf(edited)).daysIn(november)
        assertNull(days[selectedDay])
        assertEquals(LedgerDay(Summary(0, 5000), listOf(edited)), days.getValue(nextDay))
        assertFalse(days.getValue(nextDay).exceeds(0))
    }

    @Test fun zeroBaselineIsValidWithoutAllowingZeroTransactions() {
        assertEquals(0L, Money.parse("0.00", allowZero = true))
        assertEquals(5001L, Money.parse("50.01", allowZero = true))
        assertFails { Money.parse("0.00") }
        assertFails { Money.parse("-1", allowZero = true) }
        assertFails { Money.parse("50.001", allowZero = true) }
    }

    private fun record(id: String, category: String, cents: Long, occurredAt: String) =
        Entry(id, category, cents, occurredAt, "", "2026-11-18T00:00:00Z", "2026-11-18T00:00:00Z")
}
