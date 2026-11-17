package cn.localledger.app

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.localledger.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build()
    private val repo = LedgerRepository(db)
    @After fun close() = db.close()
    @Test fun sameSubmissionOnlyInsertsOnce() = runBlocking {
        repo.group("个人", Direction.EXPENSE)
        val group = repo.load().groups.single()
        repo.category(group.id, "饮食")
        val category = repo.load().categories.single()
        val entry = Entry("same-submit", category.id, 12800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        repo.save(entry); repo.save(entry)
        assertEquals(listOf(entry), repo.load().entries)
    }
    @Test fun fullReplaceRollsBackWhenTransactionFails() = runBlocking {
        repo.group("原数据", Direction.EXPENSE)
        val original = repo.load()
        val replacement = Snapshot(listOf(Group("new", "收入", Direction.INCOME)))
        try { db.withTransaction { repo.replaceAll(replacement); throw IllegalStateException("simulate interrupted restore") } } catch (_: IllegalStateException) { }
        assertEquals(original, repo.load())
        repo.replaceAll(replacement)
        assertEquals(replacement, repo.load())
    }
    @Test fun emptyGroupDeletionRemovesItsCategories() = runBlocking {
        repo.group("误建分类", Direction.EXPENSE)
        val group = repo.load().groups.single()
        repo.category(group.id, "未使用小类")
        repo.deleteEmptyGroup(group.id)
        assertEquals(Snapshot(), repo.load())
    }
    @Test fun historicalGroupCannotBeDeletedAndArchivePreservesEverything() = runBlocking {
        val entry = Entry("record", "child", 3800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        val original = Snapshot(listOf(Group("parent", "个人", Direction.EXPENSE)), listOf(Category("child", "parent", "饮食")), listOf(entry))
        repo.replaceAll(original)
        var rejected = false
        try { repo.deleteEmptyGroup("parent") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(original, repo.load())
        repo.setGroupActive("parent", false)
        assertEquals(original.copy(groups = original.groups.map { it.copy(active = false) }), repo.load())
        assertEquals(3800L, repo.load().summary(java.time.YearMonth.of(2026, 11)).expense)
        repo.setGroupActive("parent", true)
        assertEquals(original, repo.load())
    }
    @Test fun groupOrderPersistsAndArchivedGroupsKeepTheirSlots() = runBlocking {
        val original = Snapshot(listOf(
            Group("a", "个人", Direction.EXPENSE, 0),
            Group("hidden", "停用分类", Direction.EXPENSE, 1, false),
            Group("b", "收入", Direction.INCOME, 2),
            Group("c", "恋爱", Direction.EXPENSE, 3),
        ))
        repo.replaceAll(original)
        repo.reorderActiveGroups(listOf("c", "a", "b"))
        // Read through a fresh repository to check persisted order, not a UI preview.
        val saved = LedgerRepository(db).load()
        assertEquals(listOf("c", "hidden", "a", "b"), saved.groups.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), saved.groups.map { it.order })
        assertFalse(saved.groups[1].active)
        for (invalid in listOf(listOf("a", "b"), listOf("a", "a", "b"), listOf("a", "b", "hidden"))) {
            var rejected = false
            try { repo.reorderActiveGroups(invalid) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(saved, repo.load())
        }
        repo.group("新大类", Direction.EXPENSE)
        assertEquals("新大类", repo.load().groups.last().name)
    }
    @Test fun categoryRenameKeepsHistoryAndChecksNamesWithinItsParent() = runBlocking {
        val entry = Entry("record", "food", 3800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        val original = Snapshot(listOf(Group("a", "个人", Direction.EXPENSE), Group("b", "恋爱", Direction.EXPENSE)),
            listOf(Category("food", "a", "饮食"), Category("gift", "a", "礼物"), Category("other", "b", "餐饮")), listOf(entry))
        repo.replaceAll(original)
        repo.renameCategory("food", " 餐饮 ")
        assertEquals(original.copy(categories = original.categories.map { if (it.id == "food") it.copy(name = "餐饮") else it }), repo.load())
        val saved = repo.load()
        for (invalid in listOf("礼物", " ", "x".repeat(41))) {
            var rejected = false
            try { repo.renameCategory("food", invalid) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(saved, repo.load())
        }
    }
    @Test fun categoryDeletionAndArchiveProtectHistoricalEntries() = runBlocking {
        val entry = Entry("record", "used", 3800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        val original = Snapshot(listOf(Group("a", "个人", Direction.EXPENSE)),
            listOf(Category("used", "a", "饮食", 0), Category("empty", "a", "误建小类", 1), Category("last", "a", "娱乐", 2)), listOf(entry))
        repo.replaceAll(original)
        repo.deleteEmptyCategory("empty")
        assertEquals(original.copy(categories = original.categories.filter { it.id != "empty" }), repo.load())
        val saved = repo.load()
        var rejected = false
        try { repo.deleteEmptyCategory("used") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(saved, repo.load())
        repo.setCategoryActive("used", false)
        assertEquals(saved.entries, repo.load().entries)
        assertEquals(3800L, repo.load().summary(java.time.YearMonth.of(2026, 11)).expense)
        repo.setCategoryActive("used", true)
        assertEquals(saved, repo.load())
        repo.category("a", "新小类")
        assertEquals("新小类", repo.load().categories.last().name)
    }
    @Test fun archivedParentBlocksCategoryReactivationAndSaving() = runBlocking {
        repo.replaceAll(Snapshot(listOf(Group("a", "个人", Direction.EXPENSE, active = false)), listOf(Category("child", "a", "饮食", active = false))))
        val original = repo.load()
        var rejected = false
        try { repo.setCategoryActive("child", true) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(original, repo.load())
        repo.setGroupActive("a", true)
        repo.setCategoryActive("child", true)
        assertTrue(repo.load().categories.single().active)
    }
    @Test fun groupRenamePreservesIdentityOrderChildrenAndHistory() = runBlocking {
        val entry = Entry("record", "food", 3800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        val original = Snapshot(listOf(Group("a", "个人", Direction.EXPENSE, 4, false), Group("b", "恋爱", Direction.EXPENSE, 5)),
            listOf(Category("food", "a", "饮食")), listOf(entry))
        repo.replaceAll(original)
        repo.renameGroup("a", " 生活开支 ")
        val renamed = original.copy(groups = original.groups.map { if (it.id == "a") it.copy(name = "生活开支") else it })
        assertEquals(renamed, repo.load())
        repo.renameGroup("a", "生活开支")
        for (invalid in listOf("恋爱", " ", "x".repeat(41))) {
            var rejected = false
            try { repo.renameGroup("a", invalid) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(renamed, repo.load())
        }
    }
    @Test fun editingMovesOneExistingRecordAndRecalculatesTotals() = runBlocking {
        val entry = Entry("record", "food", 3800, "2026-11-17T12:34:56", "午餐", "2026-11-17T04:34:56Z", "2026-11-17T04:34:56Z")
        repo.replaceAll(Snapshot(listOf(Group("a", "个人", Direction.EXPENSE), Group("b", "收入", Direction.INCOME)),
            listOf(Category("food", "a", "饮食"), Category("salary", "b", "工资")), listOf(entry)))
        repo.updateEntry(entry, "salary", 12801, java.time.LocalDate.of(2026, 10, 31), "纠正")
        val saved = LedgerRepository(db).load()
        val revised = saved.entries.single()
        assertEquals(entry.copy(categoryId = "salary", cents = 12801, occurredAt = "2026-10-31T12:34:56", note = "纠正", updatedAt = revised.updatedAt), revised)
        java.time.Instant.parse(revised.updatedAt)
        assertEquals(Summary(0, 0), saved.summary(java.time.YearMonth.of(2026, 11)))
        assertEquals(Summary(0, 12801), saved.summary(java.time.YearMonth.of(2026, 10)))
        var rejected = false
        try { repo.updateEntry(entry, "food", 1, java.time.LocalDate.of(2026, 11, 17), "过期编辑") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(saved, repo.load())
    }
    @Test fun archivedOriginalClassificationCanBeKeptButCannotBeNewlyAssigned() = runBlocking {
        val entry = Entry("record", "old", 3800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        repo.replaceAll(Snapshot(listOf(Group("a", "停用大类", Direction.EXPENSE, active = false), Group("b", "个人", Direction.EXPENSE)),
            listOf(Category("old", "a", "旧小类", active = false), Category("other", "a", "另一个旧小类"), Category("active", "b", "饮食"), Category("disabled", "b", "停用小类", active = false)), listOf(entry)))
        repo.updateEntry(entry, "old", 4000, java.time.LocalDate.of(2026, 11, 17), "修改备注")
        val saved = repo.load()
        for (invalid in listOf("other", "disabled", "missing")) {
            var rejected = false
            try { repo.updateEntry(saved.entries.single(), invalid, 1, java.time.LocalDate.of(2026, 11, 17), "") } catch (_: IllegalArgumentException) { rejected = true } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertEquals(saved, repo.load())
        }
        repo.updateEntry(saved.entries.single(), "active", 4000, java.time.LocalDate.of(2026, 11, 17), "迁移分类")
        assertEquals("active", repo.load().entries.single().categoryId)
    }
    @Test fun invalidEditsDoNotInsertOrChangeRecords() = runBlocking {
        val entry = Entry("record", "food", 3800, "2026-11-17T12:00:00", "午餐", "2026-11-17T04:00:00Z", "2026-11-17T04:00:00Z")
        val original = Snapshot(listOf(Group("a", "个人", Direction.EXPENSE)), listOf(Category("food", "a", "饮食")), listOf(entry))
        repo.replaceAll(original)
        for ((cents, note) in listOf(0L to "", (Money.MAX_CENTS + 1) to "", 1L to "x".repeat(2001))) {
            var rejected = false
            try { repo.updateEntry(entry, "food", cents, java.time.LocalDate.of(2026, 11, 17), note) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(original, repo.load())
        }
        var missingRejected = false
        try { repo.updateEntry(entry.copy(id = "missing"), "food", 1, java.time.LocalDate.of(2026, 11, 17), "") } catch (_: IllegalStateException) { missingRejected = true }
        assertTrue(missingRejected)
        assertEquals(original, repo.load())
        val deleted = original.copy(entries = listOf(entry.copy(deletedAt = 1)))
        repo.replaceAll(deleted)
        var deletedRejected = false
        try { repo.updateEntry(deleted.entries.single(), "food", 1, java.time.LocalDate.of(2026, 11, 17), "") } catch (_: IllegalArgumentException) { deletedRejected = true }
        assertTrue(deletedRejected)
        assertEquals(deleted, repo.load())
    }
}
