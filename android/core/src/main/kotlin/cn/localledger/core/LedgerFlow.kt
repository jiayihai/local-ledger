package cn.localledger.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters.previousOrSame

data class LedgerDateRange(val start: LocalDate, val end: LocalDate) {
    init { require(!end.isBefore(start)) { "结束日期不能早于开始日期" } }
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    override fun toString() = "$start 至 $end"
}

enum class LedgerPeriod {
    WEEK, MONTH, YEAR;

    fun range(anchor: LocalDate): LedgerDateRange = when (this) {
        WEEK -> anchor.with(previousOrSame(DayOfWeek.MONDAY)).let { LedgerDateRange(it, it.plusDays(6)) }
        MONTH -> YearMonth.from(anchor).let { LedgerDateRange(it.atDay(1), it.atEndOfMonth()) }
        YEAR -> LedgerDateRange(anchor.withDayOfYear(1), LocalDate.of(anchor.year, 12, 31))
    }

    fun shift(anchor: LocalDate, amount: Long): LocalDate = when (this) {
        WEEK -> anchor.plusWeeks(amount)
        MONTH -> anchor.plusMonths(amount)
        YEAR -> anchor.plusYears(amount)
    }
}

data class CategoryFlow(val category: Category, val entries: List<Entry>) {
    val cents = entryTotal(entries)
}

data class GroupFlow(val group: Group, val categories: List<CategoryFlow>, val entries: List<Entry>) {
    val cents = entryTotal(entries)
}

data class LedgerFlow(val range: LedgerDateRange, val entries: List<Entry>, val groups: List<GroupFlow>) {
    val summary = Summary(total(Direction.EXPENSE), total(Direction.INCOME))

    private fun total(direction: Direction): Long = groups.asSequence().filter { it.group.direction == direction }
        .fold(0L) { total, group -> Math.addExact(total, group.cents) }
}

fun Snapshot.flow(range: LedgerDateRange, groupId: String? = null): LedgerFlow {
    val groupsInScope = if (groupId == null) groups else groups.filter { it.id == groupId }
    val groupIds = groupsInScope.mapTo(mutableSetOf()) { it.id }
    val categoriesInScope = categories.filter { it.groupId in groupIds }
    val byCategory = categoriesInScope.associateBy { it.id }
    val ordered = entries.asSequence()
        .filter { it.deletedAt == null && it.categoryId in byCategory }
        .map { LocalDateTime.parse(it.occurredAt) to it }
        .filter { it.first.toLocalDate() in range }
        .sortedWith(compareByDescending<Pair<LocalDateTime, Entry>> { it.first }.thenBy { it.second.id })
        .map { it.second }
        .toList()
    val entriesByCategory = ordered.groupBy { it.categoryId }
    val entriesByGroup = ordered.groupBy { byCategory.getValue(it.categoryId).groupId }
    val categoryFlows = categoriesInScope.mapNotNull { category ->
        entriesByCategory[category.id]?.let { CategoryFlow(category, it) }
    }.groupBy { it.category.groupId }
    val groupFlows = groupsInScope.mapNotNull { group ->
        entriesByGroup[group.id]?.let { GroupFlow(group, categoryFlows.getValue(group.id), it) }
    }
    return LedgerFlow(range, ordered, groupFlows)
}

private fun entryTotal(entries: List<Entry>): Long =
    entries.fold(0L) { total, entry -> Math.addExact(total, entry.cents) }
