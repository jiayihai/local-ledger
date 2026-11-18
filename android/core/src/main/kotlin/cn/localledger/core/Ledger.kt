package cn.localledger.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

@Serializable enum class Direction { EXPENSE, INCOME }
@Serializable data class Group(val id: String, val name: String, val direction: Direction, val order: Int = 0, val active: Boolean = true)
@Serializable data class Category(val id: String, val groupId: String, val name: String, val order: Int = 0, val active: Boolean = true)
@Serializable data class Entry(val id: String, val categoryId: String, val cents: Long, val occurredAt: String, val note: String, val createdAt: String, val updatedAt: String, val deletedAt: Long? = null)
@Serializable data class Snapshot(val groups: List<Group> = emptyList(), val categories: List<Category> = emptyList(), val entries: List<Entry> = emptyList())
data class Summary(val expense: Long, val income: Long) { val balance get() = Math.subtractExact(income, expense) }

object Money {
    const val MAX_CENTS = 99_999_999_999L
    fun parse(text: String, allowZero: Boolean = false): Long {
        require(Regex("[0-9]+(\\.[0-9]{1,2})?").matches(text.trim())) { "请输入有效金额，最多两位小数" }
        val result = BigDecimal(text.trim()).movePointRight(2).longValueExact()
        val minimum = if (allowZero) 0L else 1L
        require(result in minimum..MAX_CENTS) { "金额应在 ${format(minimum)} 至 999,999,999.99 元之间" }
        return result
    }
    fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}

fun Snapshot.validate(): Snapshot {
    require(groups.map { it.id }.toSet().size == groups.size) { "大类 ID 重复" }
    require(categories.map { it.id }.toSet().size == categories.size) { "小类 ID 重复" }
    require(entries.map { it.id }.toSet().size == entries.size) { "记录 ID 重复" }
    require(groups.map { it.name.trim() }.toSet().size == groups.size) { "大类名称重复" }
    require(categories.map { it.groupId to it.name.trim() }.toSet().size == categories.size) { "小类名称重复" }
    groups.forEach { require(it.id.isNotBlank() && it.name.trim().isNotEmpty() && it.name.length <= 40) }
    val groupIds = groups.map { it.id }.toSet()
    categories.forEach { require(it.id.isNotBlank() && it.groupId in groupIds && it.name.trim().isNotEmpty() && it.name.length <= 40) }
    val categoryIds = categories.map { it.id }.toSet()
    entries.forEach {
        require(it.id.isNotBlank() && it.categoryId in categoryIds && it.cents in 1..Money.MAX_CENTS && it.note.length <= 2000) { "无效记录" }
        LocalDateTime.parse(it.occurredAt)
        java.time.Instant.parse(it.createdAt); java.time.Instant.parse(it.updatedAt)
    }
    return this
}

fun Snapshot.summary(month: YearMonth): Summary {
    val groupMap = groups.associateBy { it.id }
    val categoryMap = categories.associateBy { it.id }
    var expense = 0L; var income = 0L
    entries.filter { it.deletedAt == null && YearMonth.from(LocalDateTime.parse(it.occurredAt)) == month }.forEach {
        val direction = groupMap.getValue(categoryMap.getValue(it.categoryId).groupId).direction
        if (direction == Direction.INCOME) income = Math.addExact(income, it.cents) else expense = Math.addExact(expense, it.cents)
    }
    return Summary(expense, income)
}
