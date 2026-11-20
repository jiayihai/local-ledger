package cn.localledger.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.localledger.core.*
import java.time.LocalDate

private val FlowMuted = Color(0xFF626262)
private val FlowLine = Color(0xFFE5E5E5)
private val FlowShape = RoundedCornerShape(16.dp)
private const val CUSTOM = "custom"

private enum class RangeField { ANCHOR, START, END }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowDetails(
    data: Snapshot,
    initialGroupId: String?,
    today: LocalDate,
    catalogReady: Boolean,
    revision: Long,
    readEntries: suspend (LedgerDateRange) -> List<Entry>,
    enabled: Boolean,
    exportBusy: Boolean,
    navigationActive: Boolean = true,
    onExitGroup: () -> Unit,
    onExport: (FlowExport) -> Unit,
    onEdit: (Entry) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    var periodKey by rememberSaveable { mutableStateOf(LedgerPeriod.MONTH.name) }
    var anchorDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var customStart by rememberSaveable { mutableStateOf(0L) }
    var customEnd by rememberSaveable { mutableStateOf(0L) }
    var editingRange by rememberSaveable { mutableStateOf(false) }
    var rangeDraftStart by rememberSaveable { mutableStateOf(0L) }
    var rangeDraftEnd by rememberSaveable { mutableStateOf(0L) }
    var dateField by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable(initialGroupId) { mutableStateOf(initialGroupId) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    val anchor = anchorDay?.let(LocalDate::ofEpochDay) ?: today
    val period = if (periodKey == CUSTOM) null else LedgerPeriod.valueOf(periodKey)
    val range = period?.range(anchor) ?: LedgerDateRange(LocalDate.ofEpochDay(customStart), LocalDate.ofEpochDay(customEnd))
    val read = rememberLedgerRead(range to initialGroupId, revision, catalogReady) {
        data.copy(entries = readEntries(range)).flow(range, initialGroupId)
    }
    val report = read.value
    val loaded = report != null
    val lookup = remember(data.groups, data.categories) { DetailCategories(data) }
    val group = groupId?.let(lookup.groups::get)
    val category = categoryId?.let(lookup.categories::get)
    val groupFlow = report?.groups?.firstOrNull { it.group.id == groupId }
    val categoryFlow = groupFlow?.categories?.firstOrNull { it.category.id == categoryId }
    val rows = when {
        categoryId != null -> categoryFlow?.entries.orEmpty()
        groupId != null -> groupFlow?.entries.orEmpty()
        else -> report?.entries.orEmpty()
    }
    val subtotal = categoryFlow?.cents ?: if (categoryId != null) 0L else groupFlow?.cents ?: 0L
    val scroll = rememberLazyListState()
    val back: () -> Unit = {
        if (categoryId != null) {
            categoryId = null
            scroll.requestScrollToItem(0)
        } else if (initialGroupId != null) {
            onExitGroup()
        } else {
            groupId = null
            scroll.requestScrollToItem(0)
        }
    }
    BackHandler(enabled = navigationActive && (categoryId != null || groupId != null), onBack = back)

    fun openCustomRange() {
        rangeDraftStart = range.start.toEpochDay()
        rangeDraftEnd = range.end.toEpochDay()
        editingRange = true
    }

    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (group != null) item("scope") {
            Column {
                TextButton(onClick = back, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (category != null) "‹ 返回大类" else if (initialGroupId != null) "‹ 返回记账" else "‹ 返回流水总览")
                }
                Text(
                    if (category == null) group.name else "${group.name} / ${category.name}",
                    color = directionColor(group.direction), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        item("filter") {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(), shape = FlowShape, border = BorderStroke(1.dp, FlowLine),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(LedgerPeriod.WEEK to "周", LedgerPeriod.MONTH to "月", LedgerPeriod.YEAR to "年").forEach { (option, label) ->
                            FilterChip(
                                selected = period == option, label = { Text(label) },
                                onClick = {
                                    if (period == null) anchorDay = range.start.toEpochDay()
                                    periodKey = option.name
                                },
                            )
                        }
                        FilterChip(selected = period == null, onClick = ::openCustomRange, label = { Text("自定义") })
                    }
                    if (period != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PeriodArrow("上一个区间", "‹", period.shift(anchor, -1).year in CalendarYearRange) {
                                anchorDay = period.shift(anchor, -1).toEpochDay()
                            }
                            TextButton(
                                onClick = { dateField = RangeField.ANCHOR.name },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) { Text("${periodTitle(period, anchor)} ⌄", fontWeight = FontWeight.SemiBold) }
                            PeriodArrow("下一个区间", "›", period.shift(anchor, 1).year in CalendarYearRange) {
                                anchorDay = period.shift(anchor, 1).toEpochDay()
                            }
                        }
                        Text(range.toString(), modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                            color = FlowMuted, fontSize = 12.sp)
                    } else {
                        TextButton(onClick = ::openCustomRange, modifier = Modifier.fillMaxWidth()) { Text("$range ⌄") }
                    }
                }
            }
        }
        item("totals") {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(), shape = FlowShape, border = BorderStroke(1.dp, FlowLine),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (group == null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowTotal("总开销", report?.summary?.expense, Ink, Modifier.weight(1f))
                            FlowTotal("总收入", report?.summary?.income, Red, Modifier.weight(1f))
                        }
                    } else {
                        FlowTotal(if (group.direction == Direction.EXPENSE) "开销合计" else "收入合计",
                            subtotal.takeIf { loaded }, directionColor(group.direction))
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (loaded) Text(
                            "${rows.size} 笔 · ${if (group == null) "当前日期范围" else "当前分类及日期范围"}",
                            modifier = Modifier.align(Alignment.CenterVertically), fontSize = 11.sp, color = FlowMuted,
                        ) else LedgerReadStatus(read, Modifier.align(Alignment.CenterVertically))
                        TextButton(
                            enabled = loaded && enabled && rows.isNotEmpty() && !exportBusy,
                            onClick = {
                                val categoryScope = group?.let { parent -> category?.let { "${parent.name} / ${it.name}" } ?: parent.name }
                                onExport(FlowExport(data.copy(entries = rows), range, categoryScope))
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) { Text(if (exportBusy) "正在导出…" else "导出 Excel") }
                    }
                }
            }
        }
        if (loaded && group == null) {
            listOf(Direction.EXPENSE, Direction.INCOME).forEach { direction ->
                val label = if (direction == Direction.EXPENSE) "开销大类" else "收入大类"
                val groups = report?.groups.orEmpty().filter { it.group.direction == direction }
                item("heading:${direction.name}") {
                    FlowHeading(label, directionColor(direction))
                    if (groups.isEmpty()) Text(
                        if (direction == Direction.EXPENSE) "这段时间没有开销" else "这段时间没有收入",
                        color = FlowMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                    )
                }
                items(groups, key = { "group:${it.group.id}" }) { flow ->
                    FlowCategoryRow(flow.group.name, flow.entries.size, flow.cents, direction, enabled) {
                        groupId = flow.group.id
                        categoryId = null
                        scroll.requestScrollToItem(0)
                    }
                }
            }
        } else if (loaded && category == null) {
            val categories = groupFlow?.categories.orEmpty()
            if (categories.isNotEmpty() && group != null) {
                item("small-heading") { FlowHeading("小类流水", directionColor(group.direction)) }
                items(categories, key = { "category:${it.category.id}" }) { flow ->
                    FlowCategoryRow(flow.category.name, flow.entries.size, flow.cents, group.direction, enabled) {
                        categoryId = flow.category.id
                        scroll.requestScrollToItem(0)
                    }
                }
            }
        }
        if (loaded && rows.isNotEmpty()) {
            item("records-heading") { FlowHeading("逐笔流水", Ink) }
            items(rows, key = { "record:${it.id}" }) { entry -> DetailRecord(entry, lookup, enabled, showDate = true, onEdit, onDelete) }
        } else if (loaded && group != null) {
            item("no-rows") { Text("当前分类在这段时间没有流水", color = FlowMuted, fontSize = 13.sp) }
        }
    }

    val field = dateField?.let(RangeField::valueOf)
    if (field != null) {
        val initial = when (field) {
            RangeField.ANCHOR -> anchor
            RangeField.START -> LocalDate.ofEpochDay(rangeDraftStart)
            RangeField.END -> LocalDate.ofEpochDay(rangeDraftEnd)
        }
        val bounded = initial.withYear(initial.year.coerceIn(CalendarYearRange))
        CalendarDateDialog(bounded, onDismiss = { dateField = null }) { chosen ->
            when (field) {
                RangeField.ANCHOR -> anchorDay = chosen.toEpochDay()
                RangeField.START -> rangeDraftStart = chosen.toEpochDay()
                RangeField.END -> rangeDraftEnd = chosen.toEpochDay()
            }
            dateField = null
        }
    } else if (editingRange) {
        FlowRangeDialog(
            start = LocalDate.ofEpochDay(rangeDraftStart),
            end = LocalDate.ofEpochDay(rangeDraftEnd),
            onStart = { dateField = RangeField.START.name },
            onEnd = { dateField = RangeField.END.name },
            onDismiss = { editingRange = false },
            onConfirm = {
                customStart = rangeDraftStart
                customEnd = rangeDraftEnd
                periodKey = CUSTOM
                editingRange = false
            },
        )
    }
}

private fun periodTitle(period: LedgerPeriod, anchor: LocalDate): String = when (period) {
    LedgerPeriod.WEEK -> "选择所在周"
    LedgerPeriod.MONTH -> "${anchor.year} 年 ${anchor.monthValue} 月"
    LedgerPeriod.YEAR -> "${anchor.year} 年"
}

private fun directionColor(direction: Direction) = if (direction == Direction.INCOME) Red else Ink

@Composable
private fun PeriodArrow(label: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(glyph, fontSize = 25.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun FlowTotal(label: String, cents: Long?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = FlowMuted, fontSize = 12.sp)
        Text(cents?.let { "¥${Money.format(it)}" } ?: "—", color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FlowHeading(label: String, color: Color) {
    Text(label, modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FlowCategoryRow(name: String, count: Int, cents: Long, direction: Direction, enabled: Boolean, onClick: () -> Unit) {
    val color = directionColor(direction)
    OutlinedCard(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = FlowShape,
        border = BorderStroke(1.dp, color), colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("$count 笔", fontSize = 11.sp, color = FlowMuted)
            }
            Text("¥${Money.format(cents)}", color = color, fontSize = 14.sp)
            Text("›", color = FlowMuted, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun FlowRangeDialog(
    start: LocalDate,
    end: LocalDate,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val valid = !end.isBefore(start)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义日期范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开始：$start ›") }
                OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) { Text("结束：$end ›") }
                Text(
                    if (valid) "包含开始和结束两天" else "结束日期不能早于开始日期",
                    color = if (valid) FlowMuted else Red, fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = onConfirm) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
