package cn.localledger.core

import kotlin.test.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.YearMonth
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class LedgerTest {
    private val fixture = Snapshot(listOf(Group("p", "个人", Direction.EXPENSE), Group("i", "收入", Direction.INCOME)), listOf(Category("food", "p", "饮食"), Category("salary", "i", "工资")), listOf(
        Entry("e1", "food", 186000, "2026-11-17T12:30:00", "=1+1 & <备注>", "2026-11-17T04:30:00Z", "2026-11-17T04:30:00Z"),
        Entry("e2", "salary", 800000, "2026-11-01T00:00:00", "", "2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z"),
        Entry("e3", "food", 100, "2026-10-31T23:59:59", "补记", "2026-11-17T04:30:00Z", "2026-11-17T04:30:00Z")))
    @Test fun moneyIsExactAndRejectsInvalidInput() {
        assertEquals(29L, Money.parse("0.29")); assertEquals(30L, Money.parse("0.1") + Money.parse("0.2")); assertEquals("-0.01", Money.format(-1))
        listOf("0", "-1", "1.001", "NaN", "1e3", "999999999999999999999").forEach { assertFails { Money.parse(it) } }
    }
    @Test fun monthAndDerivedBalanceUseOnlyActiveRecords() {
        assertEquals(Summary(186000, 800000), fixture.summary(YearMonth.of(2026, 11)))
        assertEquals(614000L, fixture.summary(YearMonth.of(2026, 11)).balance)
        assertEquals(100L, fixture.summary(YearMonth.of(2026, 10)).expense)
        assertEquals(800000L, fixture.copy(entries = fixture.entries.map { if (it.id == "e1") it.copy(deletedAt = 1) else it }).summary(YearMonth.of(2026, 11)).balance)
        assertEquals(0L, Snapshot().summary(YearMonth.of(2026, 11)).balance)
    }
    @Test fun backupRoundTripPreservesIdentityAndExcludesDeleted() {
        val data = fixture.copy(entries = fixture.entries + fixture.entries.first().copy(id = "deleted", deletedAt = 1))
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(data, it) }.toByteArray()
        assertEquals(fixture, BackupCodec.read(ByteArrayInputStream(bytes)))
        assertFails { BackupCodec.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        assertFails { fixture.copy(categories = emptyList()).validate() }
    }
    @Test fun backupRejectsTamperingAndUnknownVersions() {
        val bytes = ByteArrayOutputStream().also { BackupCodec.write(fixture, it) }.toByteArray()
        fun change(target: String, transform: (String) -> String): ByteArray {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { out -> ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) { val entry = input.nextEntry ?: break; val content = input.readBytes(); out.putNextEntry(ZipEntry(entry.name)); out.write(if (entry.name == target) transform(content.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else content); out.closeEntry() }
            } }
            return output.toByteArray()
        }
        assertFails { BackupCodec.read(ByteArrayInputStream(change("data.json") { it.replace("186000", "186001") })) }
        assertFails { BackupCodec.read(ByteArrayInputStream(change("manifest.json") { it.replace("\"version\":1", "\"version\":2") })) }
        assertEquals(Snapshot(), BackupCodec.read(ByteArrayInputStream(ByteArrayOutputStream().also { BackupCodec.write(Snapshot(), it) }.toByteArray())))
    }
    @Test fun xlsxHasFixedHeadersAndNeverTurnsNotesIntoFormulas() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(fixture, it) }.toByteArray()
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) { val e = zip.nextEntry ?: break; files[e.name] = zip.readBytes().toString(Charsets.UTF_8) } }
        val sheet = files.getValue("xl/worksheets/sheet1.xml")
        assertEquals(11, XlsxWriter.headers.size); assertTrue(sheet.contains("1860.00")); assertTrue(sheet.contains("&amp; &lt;备注&gt;")); assertFalse(sheet.contains("<f>"))
        val target = java.io.File("build/verification/export-fixture.xlsx"); target.parentFile.mkdirs(); target.writeBytes(bytes)
    }
}
