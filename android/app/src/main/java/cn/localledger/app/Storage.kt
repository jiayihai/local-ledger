package cn.localledger.app

import androidx.room.*
import cn.localledger.core.*
import java.time.Instant
import java.time.YearMonth

@Entity(tableName = "ledger_groups", indices = [Index(value = ["name"], unique = true)])
data class GroupRow(@PrimaryKey val id: String, val name: String, val direction: String, val sortOrder: Int, val active: Boolean)
@Entity(tableName = "ledger_categories", foreignKeys = [ForeignKey(entity = GroupRow::class, parentColumns = ["id"], childColumns = ["groupId"])], indices = [Index("groupId"), Index(value = ["groupId", "name"], unique = true)])
data class CategoryRow(@PrimaryKey val id: String, val groupId: String, val name: String, val sortOrder: Int, val active: Boolean)
@Entity(tableName = "ledger_entries", foreignKeys = [ForeignKey(entity = CategoryRow::class, parentColumns = ["id"], childColumns = ["categoryId"])], indices = [Index("categoryId"), Index("occurredAt")])
data class EntryRow(@PrimaryKey val id: String, val categoryId: String, val cents: Long, val occurredAt: String, val note: String, val createdAt: String, val updatedAt: String, val deletedAt: Long?)

data class GroupTotalRow(val groupId: String, val cents: Long)
data class CategoryHistoryRow(val categoryId: String, val count: Long)

@Dao interface LedgerDao {
    @Query("SELECT * FROM ledger_groups ORDER BY sortOrder, id") suspend fun groups(): List<GroupRow>
    @Query("SELECT * FROM ledger_categories ORDER BY sortOrder, id") suspend fun categories(): List<CategoryRow>
    @Query("SELECT * FROM ledger_entries ORDER BY occurredAt DESC, id") suspend fun entries(): List<EntryRow>
    @Query("""
        SELECT * FROM ledger_entries
        WHERE occurredAt >= :start AND occurredAt < :endExclusive AND deletedAt IS NULL
        ORDER BY occurredAt DESC, id
    """)
    suspend fun entriesIn(start: String, endExclusive: String): List<EntryRow>
    @Query("""
        SELECT category.groupId AS groupId, SUM(entry.cents) AS cents
        FROM ledger_entries AS entry
        JOIN ledger_categories AS category ON category.id = entry.categoryId
        WHERE entry.occurredAt >= :start AND entry.occurredAt < :endExclusive AND entry.deletedAt IS NULL
        GROUP BY category.groupId
    """)
    suspend fun groupTotalsIn(start: String, endExclusive: String): List<GroupTotalRow>
    @Query("SELECT categoryId, COUNT(*) AS count FROM ledger_entries GROUP BY categoryId")
    suspend fun historyCounts(): List<CategoryHistoryRow>
    @Query("SELECT * FROM ledger_entries WHERE id = :entryId") suspend fun entry(entryId: String): EntryRow?
    @Insert suspend fun addGroup(row: GroupRow)
    @Insert suspend fun addCategory(row: CategoryRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addEntry(row: EntryRow): Long
    @Query("DELETE FROM ledger_entries") suspend fun clearEntries()
    @Query("DELETE FROM ledger_categories") suspend fun clearCategories()
    @Query("DELETE FROM ledger_groups") suspend fun clearGroups()
    @Query("SELECT COUNT(*) FROM ledger_entries INNER JOIN ledger_categories ON ledger_entries.categoryId = ledger_categories.id WHERE ledger_categories.groupId = :groupId") suspend fun groupEntryCount(groupId: String): Int
    @Query("UPDATE ledger_groups SET active = :active WHERE id = :groupId") suspend fun setGroupActive(groupId: String, active: Boolean)
    @Query("DELETE FROM ledger_categories WHERE groupId = :groupId") suspend fun deleteGroupCategories(groupId: String)
    @Query("DELETE FROM ledger_groups WHERE id = :groupId") suspend fun deleteGroup(groupId: String)
    @Query("UPDATE ledger_groups SET sortOrder = :position WHERE id = :groupId") suspend fun setGroupOrder(groupId: String, position: Int)
    @Query("UPDATE ledger_categories SET name = :name WHERE id = :categoryId") suspend fun renameCategory(categoryId: String, name: String)
    @Query("UPDATE ledger_categories SET active = :active WHERE id = :categoryId") suspend fun setCategoryActive(categoryId: String, active: Boolean)
    @Query("SELECT COUNT(*) FROM ledger_entries WHERE categoryId = :categoryId") suspend fun categoryEntryCount(categoryId: String): Int
    @Query("DELETE FROM ledger_categories WHERE id = :categoryId") suspend fun deleteCategory(categoryId: String)
    @Query("UPDATE ledger_groups SET name = :name WHERE id = :groupId") suspend fun renameGroup(groupId: String, name: String)
    @Query("UPDATE ledger_entries SET categoryId = :categoryId, cents = :cents, occurredAt = :occurredAt, note = :note, updatedAt = :updatedAt WHERE id = :entryId AND deletedAt IS NULL")
    suspend fun updateEntry(entryId: String, categoryId: String, cents: Long, occurredAt: String, note: String, updatedAt: String): Int
    @Query("UPDATE ledger_entries SET deletedAt = :stamp WHERE id = :entryId AND deletedAt IS NULL")
    suspend fun markEntryDeleted(entryId: String, stamp: Long): Int
    @Query("UPDATE ledger_entries SET deletedAt = NULL WHERE id = :entryId AND deletedAt = :stamp")
    suspend fun unmarkEntryDeleted(entryId: String, stamp: Long): Int
}
@Database(entities = [GroupRow::class, CategoryRow::class, EntryRow::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }

internal data class EntryDeletion(val entryId: String, val stamp: Long)
internal data class LedgerHome(
    val month: YearMonth,
    val catalog: Snapshot,
    val groupTotals: Map<String, Long>,
    val summary: Summary,
)

class LedgerRepository(private val database: LedgerDatabase) {
    private var lastDeletionStamp = 0L

    internal suspend fun deleteEntry(original: Entry): EntryDeletion = database.withTransaction {
        val dao = database.ledger()
        val current = dao.entry(original.id)?.toEntry() ?: error("这笔记录已不存在")
        check(current.deletedAt == null) { "这笔记录已删除" }
        check(current == original) { "记录已变化，请重新选择后再删除" }
        val stamp = maxOf(Instant.now().toEpochMilli(), lastDeletionStamp + 1)
        check(dao.markEntryDeleted(original.id, stamp) == 1) { "无法删除这笔记录" }
        lastDeletionStamp = stamp
        EntryDeletion(original.id, stamp)
    }

    internal suspend fun undoDeleteEntry(deletion: EntryDeletion) = database.withTransaction {
        check(database.ledger().unmarkEntryDeleted(deletion.entryId, deletion.stamp) == 1) { "这笔删除已无法撤销" }
    }

    suspend fun renameGroup(groupId: String, name: String) = database.withTransaction {
        val dao = database.ledger()
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 40) { "名称请填写 1 至 40 个字符" }
        val groups = dao.groups()
        require(groups.any { it.id == groupId }) { "大类不存在" }
        require(groups.none { it.id != groupId && it.name == clean }) { "已有同名大类" }
        dao.renameGroup(groupId, clean)
    }
    suspend fun updateEntry(original: Entry, categoryId: String, cents: Long, date: java.time.LocalDate, note: String) = database.withTransaction {
        val dao = database.ledger()
        val current = dao.entry(original.id)?.toEntry() ?: error("记录不存在，请返回明细刷新")
        require(current.deletedAt == null) { "记录已删除，无法修改" }
        require(current == original) { "记录已变化，请返回明细重新打开后修改" }
        val category = dao.categories().firstOrNull { it.id == categoryId } ?: error("小类不存在")
        val group = dao.groups().firstOrNull { it.id == category.groupId } ?: error("大类不存在")
        // An archived original classification can be kept while correcting its amount or note.
        if (categoryId != current.categoryId) require(category.active && group.active) { "请选择启用的大类和小类" }
        val revised = current.edited(categoryId, cents, date, note, java.time.Instant.now().toString())
        check(dao.updateEntry(revised.id, revised.categoryId, revised.cents, revised.occurredAt, revised.note, revised.updatedAt) == 1) { "保存失败，请重新打开记录" }
    }
    // Reorder visible groups within their existing slots; archived groups retain their position.
    suspend fun reorderActiveGroups(ids: List<String>) = database.withTransaction {
        val dao = database.ledger()
        val groups = dao.groups()
        val activeIds = groups.filter { it.active }.map { it.id }
        require(ids.size == activeIds.size && ids.toSet() == activeIds.toSet()) { "分类已变化，请重新排序" }
        val ordered = ids.iterator()
        groups.map { if (it.active) ordered.next() else it.id }.forEachIndexed { index, id -> dao.setGroupOrder(id, index) }
    }
    suspend fun renameCategory(categoryId: String, name: String) = database.withTransaction {
        val dao = database.ledger()
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 40) { "名称请填写 1 至 40 个字符" }
        val categories = dao.categories()
        val category = categories.firstOrNull { it.id == categoryId } ?: error("小类不存在")
        require(categories.none { it.id != categoryId && it.groupId == category.groupId && it.name == clean }) { "该大类下已有同名小类" }
        dao.renameCategory(categoryId, clean)
    }
    suspend fun setCategoryActive(categoryId: String, active: Boolean) = database.withTransaction {
        val dao = database.ledger()
        val category = dao.categories().firstOrNull { it.id == categoryId } ?: error("小类不存在")
        if (active) require(dao.groups().any { it.id == category.groupId && it.active }) { "请先重新启用所属大类" }
        dao.setCategoryActive(categoryId, active)
    }
    suspend fun deleteEmptyCategory(categoryId: String) = database.withTransaction {
        val dao = database.ledger()
        require(dao.categories().any { it.id == categoryId }) { "小类不存在" }
        require(dao.categoryEntryCount(categoryId) == 0) { "这个小类有历史记录，请使用停用以保留账目" }
        dao.deleteCategory(categoryId)
    }
    suspend fun setGroupActive(groupId: String, active: Boolean) = database.withTransaction {
        require(database.ledger().groups().any { it.id == groupId }) { "大类不存在" }
        database.ledger().setGroupActive(groupId, active)
    }
    suspend fun deleteEmptyGroup(groupId: String) = database.withTransaction {
        val dao = database.ledger()
        require(dao.groups().any { it.id == groupId }) { "大类不存在" }
        require(dao.groupEntryCount(groupId) == 0) { "这个大类有历史记录，请使用停用以保留账目" }
        dao.deleteGroupCategories(groupId)
        dao.deleteGroup(groupId)
    }
    // Data-layer primitive; the M3 restore service must protect current data and obtain confirmation first.
    suspend fun replaceAll(snapshot: Snapshot) {
        snapshot.validate()
        database.withTransaction {
            val dao = database.ledger()
            dao.clearEntries(); dao.clearCategories(); dao.clearGroups()
            snapshot.groups.forEach { dao.addGroup(GroupRow(it.id, it.name, it.direction.name, it.order, it.active)) }
            snapshot.categories.forEach { dao.addCategory(CategoryRow(it.id, it.groupId, it.name, it.order, it.active)) }
            snapshot.entries.forEach { dao.addEntry(EntryRow(it.id, it.categoryId, it.cents, it.occurredAt, it.note, it.createdAt, it.updatedAt, it.deletedAt)) }
        }
    }
    internal suspend fun loadHome(month: YearMonth): LedgerHome = database.withTransaction {
        val dao = database.ledger()
        val catalog = dao.catalog()
        val totals = dao.groupTotalsIn(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())
            .associate { it.groupId to it.cents }
        var expense = 0L
        var income = 0L
        catalog.groups.forEach { group ->
            val cents = totals[group.id] ?: 0L
            if (group.direction == Direction.INCOME) income = Math.addExact(income, cents)
            else expense = Math.addExact(expense, cents)
        }
        LedgerHome(month, catalog, totals, Summary(expense, income))
    }

    internal suspend fun loadEntries(range: LedgerDateRange): List<Entry> =
        database.ledger().entriesIn(range.start.toString(), range.end.plusDays(1).toString()).map { it.toEntry() }

    internal suspend fun loadHistoryCounts(): Map<String, Long> =
        database.ledger().historyCounts().associate { it.categoryId to it.count }

    suspend fun load(): Snapshot = database.withTransaction {
        val dao = database.ledger()
        dao.catalog().copy(entries = dao.entries().map { it.toEntry() })
    }
    suspend fun group(name: String, direction: Direction) = database.withTransaction {
        val clean = name.trim(); require(clean.isNotEmpty() && clean.length <= 40) { "名称请填写 1 至 40 个字符" }
        require(database.ledger().groups().none { it.name == clean }) { "已有同名大类" }
        database.ledger().addGroup(GroupRow(java.util.UUID.randomUUID().toString(), clean, direction.name, (database.ledger().groups().maxOfOrNull { it.sortOrder } ?: -1) + 1, true))
    }
    suspend fun category(groupId: String, name: String) = database.withTransaction {
        val clean = name.trim(); require(clean.isNotEmpty() && clean.length <= 40) { "名称请填写 1 至 40 个字符" }
        require(database.ledger().groups().any { it.id == groupId && it.active }) { "大类不可用" }
        val rows = database.ledger().categories()
        require(rows.none { it.groupId == groupId && it.name == clean }) { "已有同名小类" }
        database.ledger().addCategory(CategoryRow(java.util.UUID.randomUUID().toString(), groupId, clean, (rows.filter { it.groupId == groupId }.maxOfOrNull { it.sortOrder } ?: -1) + 1, true))
    }
    suspend fun save(entry: Entry) = database.withTransaction {
        require(entry.cents in 1..Money.MAX_CENTS)
        val category = database.ledger().categories().firstOrNull { it.id == entry.categoryId && it.active } ?: error("小类不可用")
        require(database.ledger().groups().any { it.id == category.groupId && it.active }) { "大类不可用" }
        database.ledger().addEntry(EntryRow(entry.id, entry.categoryId, entry.cents, entry.occurredAt, entry.note, entry.createdAt, entry.updatedAt, entry.deletedAt))
    }
}

private fun EntryRow.toEntry() = Entry(id, categoryId, cents, occurredAt, note, createdAt, updatedAt, deletedAt)

private suspend fun LedgerDao.catalog() = Snapshot(
    groups = groups().map { Group(it.id, it.name, Direction.valueOf(it.direction), it.sortOrder, it.active) },
    categories = categories().map { Category(it.id, it.groupId, it.name, it.sortOrder, it.active) },
)
