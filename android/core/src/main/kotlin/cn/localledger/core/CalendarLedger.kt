package cn.localledger.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

data class LedgerDay(val summary: Summary, val entries: List<Entry>) {
    fun exceeds(baselineCents: Long?): Boolean = baselineCents != null && summary.expense > baselineCents
}

fun YearMonth.calendarDates(): List<LocalDate> {
    val first = atDay(1)
    val leading = first.dayOfWeek.value % 7
    val weeks = (leading + lengthOfMonth() + 6) / 7
    return List(weeks * 7) { first.plusDays((it - leading).toLong()) }
}

fun Snapshot.daysIn(month: YearMonth): Map<LocalDate, LedgerDay> {
    val groupsById = groups.associateBy { it.id }
    val categoriesById = categories.associateBy { it.id }
    return entries.asSequence()
        .filter { it.deletedAt == null }
        .map { LocalDateTime.parse(it.occurredAt) to it }
        .filter { YearMonth.from(it.first) == month }
        .groupBy { it.first.toLocalDate() }
        .mapValues { (_, datedEntries) ->
            var expense = 0L
            var income = 0L
            val sortedEntries = datedEntries.sortedByDescending { it.first }.map { (_, entry) ->
                val group = groupsById.getValue(categoriesById.getValue(entry.categoryId).groupId)
                if (group.direction == Direction.INCOME) income = Math.addExact(income, entry.cents)
                else expense = Math.addExact(expense, entry.cents)
                entry
            }
            LedgerDay(Summary(expense, income), sortedEntries)
        }
}
