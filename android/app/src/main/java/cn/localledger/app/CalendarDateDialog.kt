package cn.localledger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

internal val CalendarYearRange = 1900..2100
private val WheelTint = Color(0xFFF2F2F2)
private val WheelShape = RoundedCornerShape(8.dp)
private val WheelLabel = Color(0xFF666666)

private enum class DatePart(val label: String) {
    YEAR("年"), MONTH("月"), DAY("日");

    fun valueIn(date: LocalDate) = when (this) {
        YEAR -> date.year
        MONTH -> date.monthValue
        DAY -> date.dayOfMonth
    }
}

private fun LazyListState.centerPosition(rowPixels: Int): Float =
    firstVisibleItemIndex + firstVisibleItemScrollOffset.toFloat() / rowPixels

@Composable
internal fun CalendarDateDialog(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val rowHeight = with(LocalDensity.current) { maxOf(36.dp, 22.sp.toDp() + 8.dp) }
    val rowPixels = with(LocalDensity.current) { rowHeight.roundToPx() }
    val yearState = rememberLazyListState(initial.year - CalendarYearRange.first)
    val monthState = rememberLazyListState(initial.monthValue - 1)
    val dayState = rememberLazyListState(initial.dayOfMonth - 1)
    val year by remember(rowPixels) { derivedStateOf {
        CalendarYearRange.first + yearState.centerPosition(rowPixels).roundToInt().coerceIn(0, CalendarYearRange.last - CalendarYearRange.first)
    } }
    val month by remember(rowPixels) { derivedStateOf { (monthState.centerPosition(rowPixels).roundToInt() + 1).coerceIn(1, 12) } }
    val monthStart = remember(year, month) { LocalDate.of(year, month, 1) }
    val lastDay = monthStart.lengthOfMonth()
    val rawDay by remember(rowPixels) { derivedStateOf { dayState.centerPosition(rowPixels).roundToInt() + 1 } }
    val date = monthStart.withDayOfMonth(rawDay.coerceIn(1, lastDay))
    var editing by remember { mutableStateOf<DatePart?>(null) }
    var input by remember { mutableStateOf(TextFieldValue()) }
    var error by remember { mutableStateOf<String?>(null) }
    val moving = yearState.isScrollInProgress || monthState.isScrollInProgress || dayState.isScrollInProgress

    fun acceptEdit(): LocalDate? {
        val part = editing ?: return date
        val value = input.text.toIntOrNull()
        val valid = when (part) {
            DatePart.YEAR -> CalendarYearRange
            DatePart.MONTH -> 1..12
            DatePart.DAY -> 1..lastDay
        }
        if (value == null || value !in valid) {
            error = "请输入 ${valid.first} 至 ${valid.last} 的数字"
            return null
        }
        return when (part) {
            DatePart.YEAR -> date.withYear(value)
            DatePart.MONTH -> date.withMonth(value)
            DatePart.DAY -> date.withDayOfMonth(value)
        }
    }

    fun finishEdit(): LocalDate? {
        val revised = acceptEdit() ?: return null
        if (editing != null) {
            editing = null
            error = null
            yearState.requestScrollToItem(revised.year - CalendarYearRange.first)
            monthState.requestScrollToItem(revised.monthValue - 1)
            dayState.requestScrollToItem(revised.dayOfMonth - 1)
        }
        return revised
    }

    fun startEdit(part: DatePart) {
        val revised = finishEdit() ?: return
        val text = part.valueIn(revised).toString()
        input = TextFieldValue(text, TextRange(0, text.length))
        editing = part
    }

    val finishInput: () -> Unit = { if (finishEdit() != null) keyboard?.hide() }
    val canScrollToOption: () -> Boolean = {
        if (editing == null) true else (finishEdit() != null).also { if (it) keyboard?.hide() }
    }
    val onChangeInput: (TextFieldValue) -> Unit = { next ->
        if (next.text.length <= (if (editing == DatePart.YEAR) 4 else 2) && next.text.all { it in '0'..'9' }) {
            input = next
            error = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 286.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 0.dp,
            ) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("选择日期", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple(DatePart.YEAR, CalendarYearRange, yearState),
                            Triple(DatePart.MONTH, 1..12, monthState),
                            Triple(DatePart.DAY, 1..lastDay, dayState),
                        ).forEach { (part, range, state) ->
                            DateWheel(
                                part = part,
                                range = range,
                                state = state,
                                rowHeight = rowHeight,
                                rowPixels = rowPixels,
                                input = input.takeIf { editing == part },
                                invalid = editing == part && error != null,
                                onInputChange = onChangeInput,
                                onDone = finishInput,
                                onEdit = { if (!moving) startEdit(part) },
                                scrollEnabled = editing == null,
                                onSelectOption = canScrollToOption,
                                modifier = Modifier.weight(if (part == DatePart.YEAR) 1.25f else 1f),
                            )
                        }
                    }
                    Text(
                        error ?: "滑动选择，点中间数字编辑",
                        color = if (error == null) WheelLabel else Red,
                        fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = WheelTint, contentColor = Ink),
                        ) { Text("取消") }
                        Button(
                            enabled = !moving,
                            onClick = {
                                val revised = acceptEdit()
                                if (revised != null) {
                                    keyboard?.hide()
                                    onConfirm(revised)
                                }
                            },
                            modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        ) { Text("跳转") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateWheel(
    part: DatePart,
    range: IntRange,
    state: LazyListState,
    rowHeight: Dp,
    rowPixels: Int,
    input: TextFieldValue?,
    invalid: Boolean,
    onInputChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
    onEdit: () -> Unit,
    scrollEnabled: Boolean,
    onSelectOption: () -> Boolean,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val selectedIndex = state.centerPosition(rowPixels).roundToInt().coerceIn(0, range.last - range.first)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(part.label, color = WheelLabel, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(rowHeight * 3).clipToBounds(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(rowHeight).background(WheelTint, WheelShape))
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = rowHeight),
                flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
                userScrollEnabled = scrollEnabled,
            ) {
                items(range.last - range.first + 1, key = { range.first + it }) { index ->
                    val selected = index == selectedIndex
                    val value = range.first + index
                    val distance = abs(index - state.centerPosition(rowPixels)).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth().height(rowHeight)
                            .semantics { this.selected = selected }
                            .clickable(
                                onClickLabel = if (selected) "编辑${part.label}" else "选择${value}${part.label}",
                                role = Role.Button,
                            ) {
                                if (selected) onEdit()
                                else if (onSelectOption()) scope.launch { state.animateScrollToItem(index) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            value.toString(),
                            color = lerp(Ink, Color(0xFFAAAAAA), distance),
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 17.sp, maxLines = 1,
                        )
                    }
                }
            }
            if (input != null) {
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth().height(rowHeight).clip(WheelShape)
                        .background(Color.White).border(1.dp, if (invalid) Red else Ink, WheelShape)
                        .focusRequester(focus),
                    singleLine = true,
                    cursorBrush = SolidColor(Ink),
                    textStyle = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp), contentAlignment = Alignment.Center) { field() }
                    },
                )
                LaunchedEffect(Unit) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        }
    }
}
