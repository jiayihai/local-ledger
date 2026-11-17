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
}
