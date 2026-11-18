package cn.localledger.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.test.*

class LedgerFlowTest {
    private val november = LedgerPeriod.MONTH.range(LocalDate.parse("2026-11-18"))
    private val snapshot = Snapshot(
        groups = listOf(Group("personal", "个人", Direction.EXPENSE, active = false), Group("income", "收入", Direction.INCOME)),
        categories = listOf(Category("meals", "personal", "饮食", active = false), Category("gift", "personal", "礼物"), Category("salary", "income", "工资")),
        entries = listOf(
            entry("october", "meals", 200, "2026-10-31T23:59:59"),
            entry("first-day", "meals", 1500, "2026-11-01T00:00"),
            entry("salary-payment", "salary", 800000, "2026-11-15T10:00"),
            entry("gift-payment", "gift", 8000, "2026-11-18T09:00"),
            entry("last-day", "meals", 4201, "2026-11-30T23:59:59.999"),
            entry("december", "gift", 5000, "2026-12-01T00:00"),
            entry("deleted-record", "meals", 2000, "2026-11-20T12:00").copy(deletedAt = 1),
        ),
    )

    @Test fun calendarPeriodsHandleCrossYearWeeksAndLeapMonths() {
        val january = LocalDate.parse("2027-01-01")
        assertEquals("2026-12-28 至 2027-01-03", LedgerPeriod.WEEK.range(january).toString())
        assertEquals("2027-01-04 至 2027-01-10", LedgerPeriod.WEEK.range(LedgerPeriod.WEEK.shift(january, 1)).toString())
        val leap = LocalDate.parse("2028-02-29")
        assertEquals("2028-02-01 至 2028-02-29", LedgerPeriod.MONTH.range(leap).toString())
        assertEquals("2028-01-01 至 2028-12-31", LedgerPeriod.YEAR.range(leap).toString())
        assertFailsWith<IllegalArgumentException> { LedgerDateRange(january, january.minusDays(1)) }
        assertEquals(listOf("gift-payment"), snapshot.flow(LedgerDateRange(november.start.plusDays(17), november.start.plusDays(17))).entries.map { it.id })
    }

    @Test fun inclusiveRangeRollsUpBothDirectionsAndKeepsArchivedHistory() {
        val report = snapshot.flow(november)
        assertEquals(listOf("last-day", "gift-payment", "salary-payment", "first-day"), report.entries.map { it.id })
        assertEquals(Summary(expense = 13701, income = 800000), report.summary)
        val personal = report.groups.single { it.group.id == "personal" }
        assertEquals(13701L, personal.cents)
        assertEquals(listOf(5701L, 8000L), personal.categories.map { it.cents })
        assertEquals(listOf("last-day", "first-day"), personal.categories.first().entries.map { it.id })
        assertEquals(Summary(expense = 13701, income = 0), snapshot.flow(november, "personal").summary)
        assertEquals(Summary(expense = 0, income = 0), Snapshot().flow(november).summary)
    }

    @Test fun selectedCategoryExportsOnlyItsRowsWithFixedTemplate() {
        val selected = snapshot.flow(november).groups.first().categories.first().entries
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(snapshot.copy(entries = selected), it, range = november, categoryScope = "个人 / 饮食")
        }.toByteArray()
        val parts = buildMap {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val part = zip.nextEntry ?: break
                    put(part.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        val details = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(XlsxWriter.headers.all { details.contains(">$it<") })
        assertTrue(details.contains(">first-day<") && details.contains(">last-day<"))
        assertTrue(listOf("october", "gift-payment", "salary-payment", "december", "deleted-record").none { details.contains(">$it<") })
        val info = parts.getValue("xl/worksheets/sheet2.xml")
        assertTrue(info.contains("$november（含首尾） · 个人 / 饮食"))
        val target = File("build/verification/flow-range-fixture.xlsx")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
    }

    private fun entry(id: String, category: String, cents: Long, occurredAt: String) =
        Entry(id, category, cents, occurredAt, "", "2026-11-18T00:00:00Z", "2026-11-18T00:00:00Z")
}
