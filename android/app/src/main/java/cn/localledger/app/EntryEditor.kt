package cn.localledger.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.localledger.core.*
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryEditor(
    original: Entry,
    data: Snapshot,
    today: LocalDate,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: (categoryId: String, cents: Long, date: LocalDate, note: String) -> Unit,
) {
    val originalCategory = data.categories.first { it.id == original.categoryId }
    val originalDate = remember(original.occurredAt) { LocalDateTime.parse(original.occurredAt).toLocalDate().toEpochDay() }
    var amount by rememberSaveable(original.id) { mutableStateOf(Money.format(original.cents)) }
    var date by rememberSaveable(original.id) { mutableStateOf(originalDate) }
    var note by rememberSaveable(original.id) { mutableStateOf(original.note) }
    var groupId by rememberSaveable(original.id) { mutableStateOf(originalCategory.groupId) }
    var categoryId by rememberSaveable(original.id) { mutableStateOf(original.categoryId) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = amount != Money.format(original.cents) || date != originalDate || note != original.note || categoryId != original.categoryId || groupId != originalCategory.groupId
    val group = data.groups.first { it.id == groupId }
    val color = if (group.direction == Direction.INCOME) Red else Ink
    val categories = data.categories.filter {
        it.groupId == groupId && ((it.active && group.active) || it.id == original.categoryId)
    }
    val validCategory = categories.any { it.id == categoryId }
    val close = { if (!busy) { if (dirty) discard = true else onClose() } }
    BackHandler { close() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("修改记录", fontSize = 26.sp)
            TextButton(enabled = !busy, onClick = close) { Text("取消") }
        }
        Spacer(Modifier.height(16.dp))
        val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = color)
        OutlinedTextField(amount, { amount = it; error = null }, enabled = !busy,
            label = { Text("${if (group.direction == Direction.INCOME) "收入" else "支出"}金额 · 元") }, colors = fieldColors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        EntryDateField(LocalDate.ofEpochDay(date), today, color, !busy) { date = it.toEpochDay(); error = null }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(note, { if (it.length <= 2000) note = it }, enabled = !busy, colors = fieldColors,
            label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Text("大类", fontSize = 18.sp)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            data.groups.filter { it.active || it.id == originalCategory.groupId }.forEach { option ->
                val optionColor = if (option.direction == Direction.INCOME) Red else Ink
                FilterChip(selected = groupId == option.id, enabled = !busy, onClick = {
                    if (groupId != option.id) {
                        groupId = option.id
                        categoryId = if (option.id == originalCategory.groupId) original.categoryId else ""
                        error = null
                    }
                }, label = { Text(option.name + if (option.active) "" else "（已停用）") },
                    border = BorderStroke(1.dp, optionColor),
                    colors = FilterChipDefaults.filterChipColors(labelColor = optionColor, selectedLabelColor = optionColor, selectedContainerColor = Color(0xFFF2F2F2)))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("小类", fontSize = 18.sp)
        if (categories.isEmpty()) Text("这个大类暂无启用的小类，请先到分类管理添加或启用", color = Color.Gray, fontSize = 13.sp)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            categories.forEach { option ->
                FilterChip(selected = categoryId == option.id, enabled = !busy, onClick = { categoryId = option.id; error = null },
                    label = { Text(option.name + if (option.active) "" else "（已停用）") }, border = BorderStroke(1.dp, color),
                    colors = FilterChipDefaults.filterChipColors(labelColor = color, selectedLabelColor = color, selectedContainerColor = Color(0xFFF2F2F2)))
            }
        }
        if (!originalCategory.active || !data.groups.first { it.id == originalCategory.groupId }.active) {
            Text("原分类已停用，可保留在这笔历史记录中，也可改选启用中的分类。", color = Color.Gray, fontSize = 12.sp)
        }
        error?.let { Text(it, color = Red, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(enabled = !busy && validCategory && dirty, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = color), onClick = {
            try {
                val cents = Money.parse(amount)
                onSave(categoryId, cents, LocalDate.ofEpochDay(date), note)
            } catch (e: IllegalArgumentException) { error = e.message ?: "请检查填写内容" }
            catch (_: ArithmeticException) { error = "金额超出支持范围" }
        }) { Text(if (busy) "保存中…" else "保存修改") }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次修改？") }, text = { Text("修改尚未保存，原记录保持不变。") },
        confirmButton = { TextButton(onClick = { discard = false; onClose() }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
