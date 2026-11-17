package cn.localledger.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class EntryEditTest {
    private val original = Entry("record", "food", 3800, "2026-11-17T12:34:56", "午餐", "2026-11-17T04:34:56Z", "2026-11-17T04:34:56Z")
    private val now = "2026-11-18T03:00:00Z"

    @Test fun editPreservesIdentityCreationTimeAndTimeOfDay() {
        val revised = original.edited("gift", 12801, LocalDate.of(2026, 10, 31), "补记礼物", now)
        assertEquals(original.copy(categoryId = "gift", cents = 12801, occurredAt = "2026-10-31T12:34:56", note = "补记礼物", updatedAt = now), revised)
    }

    @Test fun movingDateAndClassificationRecalculatesTheCorrectMonthsAndDirection() {
        val ledger = Snapshot(listOf(Group("expense", "个人", Direction.EXPENSE), Group("income", "收入", Direction.INCOME)),
            listOf(Category("food", "expense", "饮食"), Category("salary", "income", "工资")), listOf(original))
        assertEquals(Summary(3800, 0), ledger.summary(YearMonth.of(2026, 11)))
        val revised = ledger.copy(entries = listOf(original.edited("salary", 12801, LocalDate.of(2026, 10, 31), "纠正", now)))
        assertEquals(Summary(0, 0), revised.summary(YearMonth.of(2026, 11)))
        assertEquals(Summary(0, 12801), revised.summary(YearMonth.of(2026, 10)))
        assertEquals(1, revised.entries.size)
    }

    @Test fun invalidEditsAndDeletedRecordsAreRejected() {
        val date = LocalDate.of(2026, 11, 17)
        listOf(0L, -1L, Money.MAX_CENTS + 1).forEach { cents -> assertFails { original.edited("food", cents, date, "", now) } }
        assertFails { original.edited(" ", 1, date, "", now) }
        assertFails { original.edited("food", 1, date, "x".repeat(2001), now) }
        assertFails { original.edited("food", 1, date, "", "invalid time") }
        assertFails { original.copy(deletedAt = 1).edited("food", 1, date, "", now) }
    }
}
