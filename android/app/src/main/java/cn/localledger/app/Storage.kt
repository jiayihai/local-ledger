package cn.localledger.app

import androidx.room.*
import cn.localledger.core.*

@Entity(tableName = "ledger_groups", indices = [Index(value = ["name"], unique = true)])
data class GroupRow(@PrimaryKey val id: String, val name: String, val direction: String, val sortOrder: Int, val active: Boolean)
@Entity(tableName = "ledger_categories", foreignKeys = [ForeignKey(entity = GroupRow::class, parentColumns = ["id"], childColumns = ["groupId"])], indices = [Index("groupId"), Index(value = ["groupId", "name"], unique = true)])
data class CategoryRow(@PrimaryKey val id: String, val groupId: String, val name: String, val sortOrder: Int, val active: Boolean)
@Entity(tableName = "ledger_entries", foreignKeys = [ForeignKey(entity = CategoryRow::class, parentColumns = ["id"], childColumns = ["categoryId"])], indices = [Index("categoryId"), Index("occurredAt")])
data class EntryRow(@PrimaryKey val id: String, val categoryId: String, val cents: Long, val occurredAt: String, val note: String, val createdAt: String, val updatedAt: String, val deletedAt: Long?)

@Dao interface LedgerDao {
    @Query("SELECT * FROM ledger_groups ORDER BY sortOrder, id") suspend fun groups(): List<GroupRow>
    @Query("SELECT * FROM ledger_categories ORDER BY sortOrder, id") suspend fun categories(): List<CategoryRow>
    @Query("SELECT * FROM ledger_entries ORDER BY occurredAt DESC, id") suspend fun entries(): List<EntryRow>
    @Insert suspend fun addGroup(row: GroupRow)
    @Insert suspend fun addCategory(row: CategoryRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addEntry(row: EntryRow): Long
    @Query("DELETE FROM ledger_entries") suspend fun clearEntries()
    @Query("DELETE FROM ledger_categories") suspend fun clearCategories()
    @Query("DELETE FROM ledger_groups") suspend fun clearGroups()
}
@Database(entities = [GroupRow::class, CategoryRow::class, EntryRow::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }

class LedgerRepository(private val database: LedgerDatabase) {
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
    suspend fun load(): Snapshot = database.withTransaction {
        val dao = database.ledger()
        Snapshot(dao.groups().map { Group(it.id, it.name, Direction.valueOf(it.direction), it.sortOrder, it.active) },
            dao.categories().map { Category(it.id, it.groupId, it.name, it.sortOrder, it.active) },
            dao.entries().map { Entry(it.id, it.categoryId, it.cents, it.occurredAt, it.note, it.createdAt, it.updatedAt, it.deletedAt) })
    }
    suspend fun group(name: String, direction: Direction) = database.withTransaction {
        val clean = name.trim(); require(clean.isNotEmpty() && clean.length <= 40) { "名称请填写 1 至 40 个字符" }
        require(database.ledger().groups().none { it.name == clean }) { "已有同名大类" }
        database.ledger().addGroup(GroupRow(java.util.UUID.randomUUID().toString(), clean, direction.name, database.ledger().groups().size, true))
    }
    suspend fun category(groupId: String, name: String) = database.withTransaction {
        val clean = name.trim(); require(clean.isNotEmpty() && clean.length <= 40) { "名称请填写 1 至 40 个字符" }
        require(database.ledger().groups().any { it.id == groupId && it.active }) { "大类不可用" }
        val rows = database.ledger().categories()
        require(rows.none { it.groupId == groupId && it.name == clean }) { "已有同名小类" }
        database.ledger().addCategory(CategoryRow(java.util.UUID.randomUUID().toString(), groupId, clean, rows.count { it.groupId == groupId }, true))
    }
    suspend fun save(entry: Entry) = database.withTransaction {
        require(entry.cents in 1..Money.MAX_CENTS)
        val category = database.ledger().categories().firstOrNull { it.id == entry.categoryId && it.active } ?: error("小类不可用")
        require(database.ledger().groups().any { it.id == category.groupId && it.active }) { "大类不可用" }
        database.ledger().addEntry(EntryRow(entry.id, entry.categoryId, entry.cents, entry.occurredAt, entry.note, entry.createdAt, entry.updatedAt, entry.deletedAt))
    }
}
