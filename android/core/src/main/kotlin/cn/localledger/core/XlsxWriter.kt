package cn.localledger.core

import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixed-format SpreadsheetML writer. It intentionally does not expose formula cells. */
object XlsxWriter {
    val headers = listOf("发生时间", "收支类型", "大类", "小类", "金额（元）", "备注", "记录ID", "大类ID", "小类ID", "创建时间", "更新时间")
    private fun xml(value: String) = value.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun text(cell: String, value: String, style: Int = 0) = "<c r=\"$cell\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    private fun number(cell: String, value: String, style: Int) = "<c r=\"$cell\" s=\"$style\"><v>$value</v></c>"
    private fun serial(time: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), time.toLocalDate())
        return (days + time.toLocalTime().toSecondOfDay() / 86400.0).toString()
    }
    fun write(
        snapshot: Snapshot,
        output: OutputStream,
        zone: ZoneId = ZoneId.systemDefault(),
        range: LedgerDateRange? = null,
        categoryScope: String? = null,
    ) {
        snapshot.validate()
        val entries = snapshot.entries.filter { it.deletedAt == null }.sortedWith(compareBy<Entry> { it.occurredAt }.thenBy { it.id })
        require(entries.size <= 1_048_575) { "记录超过 Excel 行数限制" }
        val groups = snapshot.groups.associateBy { it.id }; val categories = snapshot.categories.associateBy { it.id }
        ZipOutputStream(output).use { zip ->
            fun part(path: String, body: String) { zip.putNextEntry(ZipEntry(path)); zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + body).toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            part("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/tables/table1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/></Types>""")
            part("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            part("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="收支明细" sheetId="1" r:id="rId1"/><sheet name="导出说明" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            part("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            part("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd hh:mm:ss"/></numFmts><fonts count="3"><font><sz val="11"/><name val="Microsoft YaHei"/></font><font><b/><sz val="11"/><name val="Microsoft YaHei"/></font><font><color rgb="FFC72D39"/><sz val="11"/><name val="Microsoft YaHei"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            val body = buildString {
                append("<row r=\"1\">"); headers.forEachIndexed { i, value -> append(text("${'A' + i}1", value, 1)) }; append("</row>")
                entries.forEachIndexed { index, entry ->
                    val row = index + 2; val category = categories.getValue(entry.categoryId); val group = groups.getValue(category.groupId); val income = group.direction == Direction.INCOME
                    append("<row r=\"$row\">")
                    append(number("A$row", serial(LocalDateTime.parse(entry.occurredAt)), 2)); append(text("B$row", if (income) "收入" else "支出", if (income) 4 else 0))
                    append(text("C$row", group.name)); append(text("D$row", category.name)); append(number("E$row", BigDecimal.valueOf(entry.cents, 2).toPlainString(), if (income) 5 else 3))
                    append(text("F$row", entry.note)); append(text("G$row", entry.id)); append(text("H$row", group.id)); append(text("I$row", category.id))
                    append(number("J$row", serial(LocalDateTime.ofInstant(Instant.parse(entry.createdAt), zone)), 2)); append(number("K$row", serial(LocalDateTime.ofInstant(Instant.parse(entry.updatedAt), zone)), 2)); append("</row>")
                }
            }
            val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            part("xl/worksheets/sheet1.xml", """<worksheet xmlns="$ns" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetViews><sheetView workbookViewId="0" showGridLines="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="23" customWidth="1"/><col min="2" max="4" width="14" customWidth="1"/><col min="5" max="5" width="18" customWidth="1"/><col min="6" max="6" width="32" customWidth="1"/><col min="7" max="9" width="39" customWidth="1"/><col min="10" max="11" width="23" customWidth="1"/></cols><sheetData>$body</sheetData><autoFilter ref="A1:K${entries.size + 1}"/><tableParts count="1"><tablePart r:id="rId1"/></tableParts></worksheet>""")
            part("xl/worksheets/_rels/sheet1.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table1.xml"/></Relationships>""")
            val columns = headers.mapIndexed { i, name -> "<tableColumn id=\"${i + 1}\" name=\"${xml(name)}\"/>" }.joinToString("")
            part("xl/tables/table1.xml", """<table xmlns="$ns" id="1" name="LedgerDetails" displayName="LedgerDetails" ref="A1:K${entries.size + 1}" totalsRowShown="0"><autoFilter ref="A1:K${entries.size + 1}"/><tableColumns count="11">$columns</tableColumns><tableStyleInfo name="TableStyleLight1" showFirstColumn="0" showLastColumn="0" showRowStripes="0" showColumnStripes="0"/></table>""")
            val exportScope = range?.let { "$it（含首尾）" + (categoryScope?.let { name -> " · $name" } ?: "") }
                ?: "传入快照内的全部有效记录"
            val explanation = listOf("模板版本" to "0.1", "导出时间" to LocalDateTime.now(zone).toString(), "币种" to "人民币 CNY，金额单位元", "记录数" to entries.size.toString(), "导出范围" to exportScope, "使用规则" to "同范围分析以最近一次导出为准", "恢复数据" to "Excel 用于分析，恢复使用独立备份文件")
            part("xl/worksheets/sheet2.xml", "<worksheet xmlns=\"$ns\"><cols><col min=\"1\" max=\"1\" width=\"23\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"85\" customWidth=\"1\"/></cols><sheetData>" + (listOf("项目" to "内容") + explanation).mapIndexed { i, pair -> "<row r=\"${i + 1}\">${text("A${i + 1}", pair.first, if (i == 0) 1 else 0)}${text("B${i + 1}", pair.second, if (i == 0) 1 else 0)}</row>" }.joinToString("") + "</sheetData></worksheet>")
        }
    }
}
