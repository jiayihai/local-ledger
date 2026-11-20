package cn.localledger.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.localledger.core.calendarDates
import java.time.LocalDate
import java.time.YearMonth

private val DateMuted = Color(0xFF626262)
private val DateNeighbor = Color(0xFF999999)
private val DayShape = RoundedCornerShape(8.dp)
private val Weekdays = listOf("日", "一", "二", "三", "四", "五", "六")

@Composable
internal fun EntryDateField(
    date: LocalDate,
    today: LocalDate,
    color: Color,
    enabled: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    var showing by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedCard(
        onClick = { keyboard?.hide(); showing = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("日期", color = DateMuted, fontSize = 12.sp)
                Text(date.toString(), color = Ink, fontSize = 16.sp)
            }
            if (date == today) Text("今天", color = DateMuted, fontSize = 12.sp)
            Text("⌄", color = color, fontSize = 17.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
    if (showing) {
        EntryDayDialog(date, today, onDismiss = { showing = false }) {
            onSelect(it)
            showing = false
        }
    }
}

@Composable
private fun EntryDayDialog(
    initial: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    var shownMonth by rememberSaveable(initial.toEpochDay()) { mutableStateOf(YearMonth.from(initial).toString()) }
    val month = remember(shownMonth) { YearMonth.parse(shownMonth) }
    val weeks = remember(month) { month.calendarDates().chunked(7) }
    val lowerYear = minOf(CalendarYearRange.first, initial.year, today.year)
    val upperYear = maxOf(CalendarYearRange.last, initial.year, today.year)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 316.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
                    Text("选择日期", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MonthArrow("上个月", "‹", month > YearMonth.of(lowerYear, 1)) { shownMonth = month.minusMonths(1).toString() }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text("${month.year} 年 ${month.monthValue} 月", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        MonthArrow("下个月", "›", month < YearMonth.of(upperYear, 12)) { shownMonth = month.plusMonths(1).toString() }
                    }
                    HorizontalDivider(color = Color(0xFFE8E8E8))
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Weekdays.forEach {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, fontSize = 11.sp, color = DateMuted) }
                        }
                    }
                    Column(Modifier.selectableGroup()) {
                        weeks.forEach { week ->
                            Row(Modifier.fillMaxWidth()) {
                                week.forEach { day ->
                                    val inMonth = YearMonth.from(day) == month
                                    DateChoice(day, inMonth, day == initial, day == today, Modifier.weight(1f)) { onSelect(day) }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(onClick = { onSelect(today) }) { Text("今天") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(label: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(glyph, fontSize = 25.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun DateChoice(
    day: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val interaction = if (inMonth) Modifier.clip(DayShape).selectable(selected, role = Role.Button, onClick = onSelect)
        .semantics { contentDescription = "${day.year}年${day.monthValue}月${day.dayOfMonth}日" + if (isToday) "，今天" else "" }
    else Modifier.clearAndSetSemantics {}
    Box(modifier.heightIn(min = 38.dp).then(interaction).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.sizeIn(minWidth = 29.dp, minHeight = 30.dp).clip(DayShape)
                .background(if (inMonth && selected) Ink else Color.White)
                .then(if (inMonth && isToday && !selected) Modifier.border(1.dp, Ink, DayShape) else Modifier)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(), fontSize = 14.sp,
                color = if (!inMonth) DateNeighbor else if (selected) Color.White else Ink,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
