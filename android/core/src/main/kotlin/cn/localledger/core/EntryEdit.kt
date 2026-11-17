package cn.localledger.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Change a record without changing its identity, creation time or original time of day. */
fun Entry.edited(categoryId: String, cents: Long, date: LocalDate, note: String, updatedAt: String): Entry {
    require(deletedAt == null) { "记录已删除，无法修改" }
    require(categoryId.isNotBlank()) { "请选择小类" }
    require(cents in 1..Money.MAX_CENTS) { "金额应在 0.01 至 999,999,999.99 元之间" }
    require(note.length <= 2000) { "备注最多 2000 个字符" }
    Instant.parse(updatedAt)
    val time = LocalDateTime.parse(occurredAt).toLocalTime()
    return copy(categoryId = categoryId, cents = cents, occurredAt = date.atTime(time).toString(), note = note, updatedAt = updatedAt)
}
