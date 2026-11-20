package cn.localledger.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.localledger.core.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val SoftGray = Color(0xFFF2F2F2)
private val Muted = Color(0xFF666666)
private val Divider = Color(0xFFE5E5E5)
private val TileShape = RoundedCornerShape(10.dp)
private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val EmptyDay = LedgerDay(Summary(0, 0), emptyList())

@Composable
internal fun DetailsHeader(calendarMode: Boolean, onModeChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("明细", fontSize = 28.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(SoftGray).padding(2.dp).selectableGroup()) {
            listOf(true to "日历", false to "流水").forEach { (isCalendar, label) ->
                val selected = calendarMode == isCalendar
                Box(
                    Modifier.clip(TileShape).background(if (selected) Color.White else SoftGray)
                        .selectable(selected, role = Role.Tab) { onModeChange(isCalendar) }
                        .sizeIn(minWidth = 56.dp, minHeight = 44.dp).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.sp, color = if (selected) Ink else Muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarDetails(
    data: Snapshot,
    catalogReady: Boolean,
    revision: Long,
    readEntries: suspend (LedgerDateRange) -> List<Entry>,
    today: LocalDate,
    selectedDate: LocalDate,
    baselineCents: Long?,
    scrollState: LazyListState,
    enabled: Boolean,
    monthGestureArea: Modifier = Modifier,
    onSelect: (LocalDate) -> Unit,
    onBaselineChange: (Long?) -> Unit,
    onEdit: (Entry) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    val month = YearMonth.from(selectedDate)
    val months = remember(month) {
        (if (month > YearMonth.of(CalendarYearRange.first, 1)) listOf(month.minusMonths(1)) else emptyList()) +
            month +
            (if (month < YearMonth.of(CalendarYearRange.last, 12)) listOf(month.plusMonths(1)) else emptyList())
    }
    val reads = mutableMapOf<YearMonth, LedgerRead<Map<LocalDate, LedgerDay>>>()
    for (visibleMonth in months.sortedBy { it != month }) {
        key(visibleMonth) {
            reads[visibleMonth] = rememberLedgerRead(visibleMonth, revision, catalogReady) {
                val range = LedgerDateRange(visibleMonth.atDay(1), visibleMonth.atEndOfMonth())
                data.copy(entries = readEntries(range)).daysIn(visibleMonth)
            }
        }
    }
    val read = reads.getValue(month)
    val loaded = read.value != null
    val days = read.value.orEmpty()
    val day = days[selectedDate] ?: EmptyDay
    val lookup = remember(data.categories, data.groups) { DetailCategories(data) }
    var editBaseline by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        state = scrollState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item("calendar") {
            key(month) {
                CalendarCard(months, month, today, selectedDate, reads, baselineCents, monthGestureArea, onSelect, { pickDate = true })
            }
        }
        item("baseline") {
            OutlinedCard(
                onClick = { editBaseline = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Divider),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
            ) {
                FlowRow(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "每日消费基线", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                    )
                    Text(
                        baselineCents?.let { "¥${Money.format(it)} ›" } ?: "未设置 ›",
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.CenterVertically).clip(TileShape).background(SoftGray)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
        item("day-header") {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${selectedDate.monthValue} 月 ${selectedDate.dayOfMonth} 日", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (selectedDate == today) Text("今天", color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
                    if (loaded) Text("${day.entries.size} 笔", color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
                }
                if (loaded) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text("支出 ¥${Money.format(day.summary.expense)}", color = Ink, fontSize = 13.sp)
                        Text("收入 ¥${Money.format(day.summary.income)}", color = Red, fontSize = 13.sp)
                    }
                }
            }
        }
        if (!loaded) item("day-loading") {
            LedgerReadStatus(read, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        } else if (day.entries.isEmpty()) item("day-empty") {
            Text("这天还没有记录", color = Muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        items(day.entries, key = { "entry:${it.id}" }) { entry ->
            DetailRecord(entry, lookup, enabled, showDate = false, onEdit, onDelete)
        }
    }
    if (editBaseline) {
        BaselineDialog(baselineCents, onDismiss = { editBaseline = false }) {
            onBaselineChange(it)
            editBaseline = false
        }
    }
    if (pickDate) {
        CalendarDateDialog(selectedDate, onDismiss = { pickDate = false }) {
            pickDate = false
            onSelect(it)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarCard(
    months: List<YearMonth>,
    month: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate,
    reads: Map<YearMonth, LedgerRead<Map<LocalDate, LedgerDay>>>,
    baselineCents: Long?,
    monthGestureArea: Modifier,
    onSelect: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val homePage = months.indexOf(month)
    val pager = rememberPagerState(initialPage = homePage) { months.size }
    val scope = rememberCoroutineScope()
    val handleSelection by rememberUpdatedState(onSelect)
    val currentToday by rememberUpdatedState(today)
    fun firstSelection(target: YearMonth, current: LocalDate): LocalDate =
        if (target == YearMonth.from(current)) current else target.atDay(1)

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != homePage) handleSelection(firstSelection(months[page], currentToday))
        }
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Divider),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onPickDate, enabled = !pager.isScrollInProgress, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Text(
                            "${selectedDate.year}年${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 ⌄",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = "${selectedDate.year}年${selectedDate.monthValue}月${selectedDate.dayOfMonth}日，选择日期" },
                        )
                    }
                }
                IconButton(
                    onClick = { scope.launch { pager.animateScrollToPage(homePage - 1) } },
                    enabled = homePage > 0 && !pager.isScrollInProgress, modifier = Modifier.size(40.dp)
                        .semantics { contentDescription = "上个月" },
                ) {
                    Text("‹", fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {})
                }
                IconButton(
                    onClick = { scope.launch { pager.animateScrollToPage(homePage + 1) } },
                    enabled = homePage < months.lastIndex && !pager.isScrollInProgress, modifier = Modifier.size(40.dp)
                        .semantics { contentDescription = "下个月" },
                ) {
                    Text("›", fontSize = 26.sp, modifier = Modifier.clearAndSetSemantics {})
                }
                TextButton(onClick = { onSelect(today) }, enabled = !pager.isScrollInProgress,
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text("今天", fontSize = 12.sp) }
            }
            HorizontalDivider(color = Divider)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(day, fontSize = 11.sp, color = Muted)
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().then(monthGestureArea).clipToBounds(),
                verticalAlignment = Alignment.Top) { page ->
                val pageMonth = months[page]
                val active = page == homePage
                val pageDays = reads[pageMonth]?.value.orEmpty()
                CalendarGrid(
                    pageMonth, today, if (active) selectedDate else firstSelection(pageMonth, today),
                    pageDays, baselineCents, active, { if (!pager.isScrollInProgress) onSelect(it) },
                )
            }
            HorizontalDivider(Modifier.padding(top = 2.dp), color = Divider)
            FlowRow(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("− 支出", color = Ink, fontSize = 11.sp)
                Text("+ 收入", color = Red, fontSize = 11.sp)
                LegendMark("超基线", filled = true)
                LegendMark("已选日期", filled = false)
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate,
    days: Map<LocalDate, LedgerDay>,
    baselineCents: Long?,
    active: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    val weeks = remember(month) { month.calendarDates().chunked(7) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        weeks.forEach { week ->
            val hasAmounts = week.any { it in days }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                week.forEach { date ->
                    CalendarTile(
                        date, YearMonth.from(date) == month, date == today, date == selectedDate, days[date] ?: EmptyDay,
                        baselineCents, hasAmounts, active, Modifier.weight(1f).fillMaxHeight(), { onSelect(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendMark(label: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).border(1.dp, Ink, RoundedCornerShape(3.dp))
            .background(if (filled) Ink else Color.White, RoundedCornerShape(3.dp)))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CalendarTile(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    selected: Boolean,
    day: LedgerDay,
    baselineCents: Long?,
    weekHasAmounts: Boolean,
    active: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val over = day.exceeds(baselineCents)
    val tileLabel = "${date.monthValue} 月 ${date.dayOfMonth} 日" +
        (if (isToday) "，今天" else "") +
        (if (day.summary.expense > 0) "，支出 ${Money.format(day.summary.expense)} 元" else "") +
        (if (day.summary.income > 0) "，收入 ${Money.format(day.summary.income)} 元" else "") +
        (if (over) "，超过基线" else "")
    val tile = if (inMonth) modifier
        .clip(TileShape)
        .then(if (selected) Modifier.border(1.5.dp, Ink, TileShape) else Modifier)
        .then(if (active) Modifier.selectable(selected, role = Role.Button, onClick = onSelect)
            .semantics { contentDescription = tileLabel } else Modifier.clearAndSetSemantics {})
    else modifier.clearAndSetSemantics {}

    Column(
        tile.heightIn(min = if (weekHasAmounts) 49.dp else 44.dp).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (weekHasAmounts) Arrangement.Top else Arrangement.Center,
    ) {
        val dateColor = when {
            !inMonth -> Color(0xFF9E9E9E)
            over -> Color.White
            else -> Ink
        }
        val background = when {
            over -> Ink
            day.entries.isNotEmpty() || isToday && inMonth -> SoftGray
            else -> Color.White
        }
        Box(
            Modifier.sizeIn(minWidth = 26.dp, minHeight = 24.dp).clip(TileShape).background(background)
                .then(if (isToday && inMonth && !selected) Modifier.border(1.dp, Ink, TileShape) else Modifier)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(), fontSize = 15.sp, lineHeight = 19.sp,
                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, color = dateColor,
            )
        }
        if (day.summary.expense > 0) CalendarAmount(day.summary.expense, "−", Ink)
        if (day.summary.income > 0) CalendarAmount(day.summary.income, "+", Red)
    }
}

@Composable
private fun CalendarAmount(cents: Long, sign: String, color: Color) {
    val full = sign + Money.format(cents)
    val short = remember(cents, sign) {
        if (cents < 100_000) full else {
            val (scale, suffix) = when {
                cents >= 10_000_000_000L -> 10 to "亿"
                cents >= 1_000_000L -> 6 to "万"
                else -> 5 to "千"
            }
            sign + BigDecimal.valueOf(cents, scale).setScale(1, RoundingMode.DOWN).stripTrailingZeros().toPlainString() + suffix
        }
    }
    var compact by remember(full) { mutableStateOf(false) }
    Text(
        if (compact) short else full,
        modifier = Modifier.padding(horizontal = 1.dp).clearAndSetSemantics {},
        fontSize = 10.sp, lineHeight = 13.sp, color = color,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!compact && (it.didOverflowWidth || it.isLineEllipsized(0)) && short != full) compact = true },
    )
}

@Composable
private fun BaselineDialog(baselineCents: Long?, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(baselineCents?.let(Money::format) ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("每日消费基线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("以每天的支出合计为准，收入不抵扣。恰好等于基线的日期不会标深。")
                OutlinedTextField(
                    amount, { if (it.length <= 16) { amount = it; error = null } },
                    label = { Text("基线金额 · 元") }, singleLine = true, isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = error?.let { message -> { Text(message) } },
                )
                if (baselineCents != null) TextButton(onClick = { onSave(null) }) { Text("关闭超线标记") }
            }
        },
        confirmButton = {
            TextButton(enabled = amount.isNotBlank(), onClick = {
                val cents = runCatching { Money.parse(amount, allowZero = true) }
                cents.onSuccess { onSave(it) }.onFailure { error = it.message ?: "请输入有效金额" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal class DetailCategories(data: Snapshot) {
    val categories = data.categories.associateBy { it.id }
    val groups = data.groups.associateBy { it.id }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailRecord(
    entry: Entry,
    lookup: DetailCategories,
    enabled: Boolean,
    showDate: Boolean,
    onEdit: (Entry) -> Unit,
    onDelete: (Entry) -> Unit,
) {
    val category = lookup.categories.getValue(entry.categoryId)
    val group = lookup.groups.getValue(category.groupId)
    val income = group.direction == Direction.INCOME
    val color = if (income) Red else Ink
    val date = remember(entry.occurredAt, showDate) {
        if (showDate) entry.occurredAt.replace('T', ' ') else LocalDateTime.parse(entry.occurredAt).format(ClockFormat)
    }
    var actions by remember(entry.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
            enabled = enabled, role = Role.Button, onClickLabel = "修改记录",
            onLongClickLabel = "打开记录操作", onLongClick = { actions = true },
            onClick = { onEdit(entry) },
        ),
        shape = shape, border = BorderStroke(1.dp, if (showDate || income) color else Divider),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(category.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("${group.name} · $date", color = Muted, fontSize = 12.sp)
                if (entry.note.isNotBlank()) Text(
                    entry.note, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${if (income) "+" else "−"}¥${Money.format(entry.cents)}", color = color, fontSize = 14.sp)
                Text("修改 ›", color = Muted, fontSize = 11.sp)
                DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                    DropdownMenuItem(enabled = enabled, text = { Text("修改记录") }, onClick = {
                        actions = false
                        onEdit(entry)
                    })
                    DropdownMenuItem(enabled = enabled, text = { Text("删除记录", color = Red) }, onClick = {
                        actions = false
                        onDelete(entry)
                    })
                }
            }
        }
    }
}
