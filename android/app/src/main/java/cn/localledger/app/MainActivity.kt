package cn.localledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.room.Room
import cn.localledger.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.*
import java.util.UUID

private val Ink = Color(0xFF191919)
private val Red = Color(0xFFC72D39)

class LedgerViewModel(private val repo: LedgerRepository) : ViewModel() {
    val snapshot = MutableStateFlow(Snapshot())
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false); private set
    init { refresh() }
    private fun refresh() { viewModelScope.launch { try { snapshot.value = withContext(Dispatchers.IO) { repo.load() }; loaded = true } catch (e: Exception) { error = e.message ?: "读取失败" } } }
    private fun perform(action: suspend () -> Unit, success: () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action(); snapshot.value = repo.load() }; success() }
            catch (e: Exception) { error = e.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    fun group(name: String, direction: Direction, done: () -> Unit) = perform({ repo.group(name, direction) }, done)
    fun category(group: String, name: String, done: () -> Unit) = perform({ repo.category(group, name) }, done)
    fun save(entry: Entry, done: () -> Unit) = perform({ repo.save(entry) }, done)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = Room.databaseBuilder(applicationContext, LedgerDatabase::class.java, "ledger.db").build()
                return LedgerViewModel(LedgerRepository(database)) as T
            }
        })[LedgerViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Ink, tertiary = Red, background = Color.White, surface = Color.White, error = Red,
                surfaceTint = Color.White, surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF666666),
                primaryContainer = Color(0xFFF2F2F2), onPrimaryContainer = Ink, secondaryContainer = Color(0xFFF2F2F2), onSecondaryContainer = Ink,
                surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = Color(0xFFF2F2F2), surfaceContainerLow = Color.White, surfaceContainerLowest = Color.White)) { LedgerApp(vm) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LedgerApp(vm: LedgerViewModel) {
    val data by vm.snapshot.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf("记账") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var detailsId by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var capturedTime by remember { mutableStateOf(LocalTime.now().withNano(0)) }
    var note by remember { mutableStateOf("") }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sheet by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf<String?>(null) }
    val selected = data.groups.find { it.id == groupId }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) month = YearMonth.now() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); month = YearMonth.now() } }
    val summary = data.summary(month)
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = selected != null || page == "分类管理" || detailsId != null) {
        if (sheet) sheet = false else { groupId = null; detailsId = null; page = "记账" }
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = {
        if (selected == null) NavigationBar(containerColor = Color.White) {
            listOf("记账", "明细", "设置").forEach { label -> NavigationBarItem(selected = page == label, onClick = { page = label; detailsId = null }, icon = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF2F2F2))) }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            if (selected != null) {
                val entryColor = if (selected.direction == Direction.INCOME) Red else Ink
                val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = entryColor, unfocusedBorderColor = entryColor)
                val amountFocus = remember(selected.id) { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(selected.id) { amountFocus.requestFocus(); keyboard?.show() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { groupId = null }) { Text("‹ 返回") }; Text(selected.name, fontSize = 22.sp); Text(if (selected.direction == Direction.INCOME) "收入" else "支出", color = if (selected.direction == Direction.INCOME) Red else Ink)
                }
                OutlinedTextField(value = amount, onValueChange = { amount = it }, colors = fieldColors, label = { Text("金额 · 元") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(amountFocus))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = date, onValueChange = { date = it }, colors = fieldColors, label = { Text("日期（yyyy-MM-dd）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = note, onValueChange = { if (it.length <= 2000) note = it }, colors = fieldColors, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
                Button(onClick = { try { Money.parse(amount); LocalDate.parse(date); sheet = true } catch (e: Exception) { vm.error = e.message ?: "检查金额和日期" } }, colors = ButtonDefaults.buttonColors(containerColor = entryColor), enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("确定") }
            } else {
                Text(if (page == "明细" && detailsId != null) "${data.groups.find { it.id == detailsId }?.name ?: "分类"}明细" else page, fontSize = 28.sp)
                Spacer(Modifier.height(16.dp))
                when (page) {
                    "记账" -> {
                        Text("${month.year}年${month.monthValue}月", color = Color.Gray)
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("本月支出", fontSize = 13.sp, color = Color.Gray); Text("¥ ${Money.format(summary.expense)}", fontSize = 22.sp) }
                            Column(horizontalAlignment = Alignment.End) { Text("本月收入", fontSize = 13.sp, color = Color.Gray); Text("¥ ${Money.format(summary.income)}", fontSize = 22.sp, color = Red) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("本月结余", fontSize = 13.sp, color = Color.Gray); Text("¥ ${Money.format(summary.balance)}", fontSize = 16.sp) }
                        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data.groups.filter { it.active }, key = { it.id }) { group ->
                                val total = data.entries.filter { entry -> entry.deletedAt == null && entry.categoryId in data.categories.filter { it.groupId == group.id }.map { it.id } && YearMonth.from(LocalDateTime.parse(entry.occurredAt)) == month }.sumOf { it.cents }
                                OutlinedCard(border = BorderStroke(1.dp, if (group.direction == Direction.INCOME) Red else Ink), colors = CardDefaults.outlinedCardColors(containerColor = Color.White), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f).clickable { groupId = group.id; amount = ""; note = ""; date = LocalDate.now().toString(); capturedTime = LocalTime.now().withNano(0); operationId = UUID.randomUUID().toString() }.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column { Text(group.name, fontSize = 18.sp); Text(if (group.direction == Direction.INCOME) "本月收入" else "本月支出", color = Color.Gray, fontSize = 12.sp) }
                                            Text(Money.format(total), color = if (group.direction == Direction.INCOME) Red else Ink, fontSize = 20.sp)
                                        }
                                        TextButton(onClick = { detailsId = group.id; page = "明细" }) { Text("明细 ›", fontSize = 12.sp) }
                                    }
                                }
                            }
                            item { TextButton(onClick = { newGroup = true }) { Text("＋ 新建大类") }; if (data.groups.isEmpty()) Text(if (vm.loaded) "先创建一个大类，再添加自己的小类" else "正在读取本地账目…", color = Color.Gray) }
                        }
                    }
                    "明细" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val rows = data.entries.filter { it.deletedAt == null && (detailsId == null || data.categories.find { c -> c.id == it.categoryId }?.groupId == detailsId) }
                        if (rows.isEmpty()) item { Text("还没有记录", color = Color.Gray) }
                        items(rows, key = { it.id }) { entry ->
                            val category = data.categories.first { it.id == entry.categoryId }; val group = data.groups.first { it.id == category.groupId }
                            OutlinedCard(border = BorderStroke(1.dp, if (group.direction == Direction.INCOME) Red else Ink), colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) { Text("${group.name} / ${category.name}"); Text("${entry.occurredAt.replace('T', ' ')}  ${if (group.direction == Direction.INCOME) "+" else "−"} ¥ ${Money.format(entry.cents)}", color = if (group.direction == Direction.INCOME) Red else Ink); if (entry.note.isNotBlank()) Text(entry.note, color = Color.Gray) }
                            }
                        }
                    }
                    "设置" -> {
                        TextButton(onClick = { page = "分类管理" }) { Text("分类管理 ›") }
                        Spacer(Modifier.height(18.dp)); Text("M1 功能体验版", fontSize = 18.sp)
                        Text("当前提供分类创建、记账和明细查看。编辑、筛选、导出与备份恢复将随后续阶段交付。", color = Color.Gray)
                    }
                    "分类管理" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { TextButton(onClick = { newGroup = true }) { Text("＋ 新建大类") } }
                        items(data.groups, key = { it.id }) { group ->
                            Column { Text(group.name, fontSize = 20.sp); Text(data.categories.filter { it.groupId == group.id }.joinToString("、") { it.name }.ifBlank { "暂无小类" }, color = Color.Gray); TextButton(onClick = { newCategory = group.id }) { Text("＋ 添加小类") } }
                        }
                    }
                }
            }
        }
    }
    if (newGroup) NameDialog("新建大类", true, vm.busy, { newGroup = false }) { name, direction -> vm.group(name, direction) { newGroup = false } }
    newCategory?.let { id -> NameDialog("新建小类", false, vm.busy, { newCategory = null }) { name, _ -> vm.category(id, name) { newCategory = null } } }
    if (sheet && selected != null) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 32.dp)) {
            Text("${selected.name} · 选择小类", fontSize = 22.sp); Text("¥ $amount", fontSize = 26.sp); Spacer(Modifier.height(14.dp))
            data.categories.filter { it.groupId == selected.id && it.active }.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { category ->
                    OutlinedButton(border = BorderStroke(1.dp, if (selected.direction == Direction.INCOME) Red else Ink), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected.direction == Direction.INCOME) Red else Ink), enabled = !vm.busy, onClick = {
                        val now = Instant.now().toString()
                        vm.save(Entry(operationId, category.id, Money.parse(amount), LocalDate.parse(date).atTime(capturedTime).toString(), note, now, now)) { sheet = false; groupId = null; page = "记账"; scope.launch { snack.showSnackbar("已保存：${selected.name} / ${category.name}") } }
                    }) { Text(category.name) }
                } }
            }
            TextButton(onClick = { newCategory = selected.id }) { Text("＋ 新建小类") }
        }
    }
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("操作未完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } }) }
}

@Composable private fun NameDialog(title: String, showDirection: Boolean, busy: Boolean, cancel: () -> Unit, save: (String, Direction) -> Unit) {
    var name by remember { mutableStateOf("") }; var direction by remember { mutableStateOf(Direction.EXPENSE) }
    AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = {
        Column { OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            if (showDirection) Row { FilterChip(direction == Direction.EXPENSE, { direction = Direction.EXPENSE }, label = { Text("支出") }); Spacer(Modifier.width(8.dp)); FilterChip(direction == Direction.INCOME, { direction = Direction.INCOME }, label = { Text("收入") }) }
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { save(name, direction) }) { Text("创建") } }, dismissButton = { TextButton(onClick = cancel) { Text("取消") } })
}
