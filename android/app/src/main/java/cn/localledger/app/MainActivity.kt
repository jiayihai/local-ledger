package cn.localledger.app

import android.content.SharedPreferences
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.asStateFlow
import java.time.*
import java.util.UUID

internal val Ink = Color(0xFF191919)
internal val Red = Color(0xFFC72D39)
private val EmptyLedgerCatalog = Snapshot()

private fun Long?.displayCents() = this?.let(Money::format) ?: "—"

internal data class LedgerUiData(val home: LedgerHome? = null, val revision: Long = 0)

class LedgerViewModel(private val repo: LedgerRepository, private val settings: SharedPreferences) : ViewModel() {
    private val baselineKey = "daily_expense_baseline_cents"
    private var baselineChanged = false
    var expenseBaseline by mutableStateOf<Long?>(null); private set

    fun saveExpenseBaseline(cents: Long?) {
        baselineChanged = true
        val edit = settings.edit()
        if (cents == null) edit.remove(baselineKey) else edit.putLong(baselineKey, cents)
        edit.apply()
        expenseBaseline = cents
    }

    private val reads = MutableStateFlow(LedgerUiData())
    internal val uiData = reads.asStateFlow()
    private var requestedMonth = YearMonth.now()
    private var homeRefresh: Job? = null
    internal var homeReadFailed by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    private var pendingExport by mutableStateOf<FlowExport?>(null)
    private var writingExport by mutableStateOf(false)
    internal val exportBusy get() = pendingExport != null || writingExport
    private val undoQueue = ArrayDeque<EntryDeletion>()
    private var undoTimer: Job? = null
    internal var pendingUndo by mutableStateOf<EntryDeletion?>(null); private set

    private fun showNextUndo() {
        undoTimer?.cancel()
        pendingUndo = undoQueue.removeFirstOrNull()
        undoTimer = pendingUndo?.let {
            viewModelScope.launch {
                delay(5_000)
                showNextUndo()
            }
        }
    }

    internal fun deleteEntry(original: Entry) = performValue({ repo.deleteEntry(original) }) { deletion ->
        undoQueue.addLast(deletion)
        if (pendingUndo == null) showNextUndo()
    }

    internal fun undoDeleteEntry(deletion: EntryDeletion, done: () -> Unit) {
        if (busy || pendingUndo != deletion) return
        showNextUndo()
        perform({ repo.undoDeleteEntry(deletion) }, done)
    }

    internal fun startExport(request: FlowExport): Boolean {
        if (exportBusy || request.snapshot.entries.isEmpty()) return false
        pendingExport = request
        return true
    }

    internal fun exportPickerUnavailable() {
        pendingExport = null
        error = "无法打开系统文件选择器"
    }

    internal fun finishExport(resolver: ContentResolver, uri: Uri?, done: (Int) -> Unit) {
        val request = pendingExport
        pendingExport = null
        if (uri == null) return
        if (request == null) {
            error = "导出会话已结束，请返回流水页重新导出"
            return
        }
        writingExport = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { request.writeTo(resolver, uri) }
                done(request.snapshot.entries.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Excel 保存失败，请换一个本地位置重试"
            } finally {
                writingExport = false
            }
        }
    }

    init {
        refreshHome()
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { settings.getLong(baselineKey, -1L).takeIf { it >= 0L } }
            if (!baselineChanged) expenseBaseline = saved
        }
    }

    internal fun showMonth(month: YearMonth) {
        if (requestedMonth == month && (reads.value.home?.month == month || homeRefresh?.isActive == true)) return
        requestedMonth = month
        if (!busy) refreshHome()
    }

    internal fun retryHome() {
        if (!busy && homeRefresh?.isActive != true) refreshHome()
    }

    private fun refreshHome() {
        homeRefresh?.cancel()
        homeReadFailed = false
        homeRefresh = viewModelScope.launch {
            try {
                reads.value = reads.value.copy(home = readRequestedHome())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                homeReadFailed = true
                error = failure.message ?: "读取账本失败"
            }
        }
    }

    private suspend fun readRequestedHome(): LedgerHome {
        while (true) {
            val month = requestedMonth
            val value = withContext(Dispatchers.IO) { repo.loadHome(month) }
            if (requestedMonth == month) return value
        }
    }

    internal suspend fun readEntries(range: LedgerDateRange): List<Entry> = repo.loadEntries(range)
    internal suspend fun readHistoryCounts(): Map<String, Long> = repo.loadHistoryCounts()

    private fun perform(action: suspend () -> Unit, success: () -> Unit = {}) =
        performValue(action) { success() }

    private fun <T> performValue(action: suspend () -> T, success: (T) -> Unit) {
        if (busy || reads.value.home == null) return
        busy = true
        homeRefresh?.cancel()
        viewModelScope.launch {
            var changed = false
            try {
                val value = withContext(Dispatchers.IO) { action() }
                changed = true
                reads.value = LedgerUiData(readRequestedHome(), reads.value.revision + 1)
                changed = false
                success(value)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "操作失败，请重试" }
            finally {
                busy = false
                if (changed) reads.value = reads.value.copy(revision = reads.value.revision + 1)
                if (changed || reads.value.home?.month != requestedMonth) refreshHome()
            }
        }
    }
    fun group(name: String, direction: Direction, done: () -> Unit) = perform({ repo.group(name, direction) }, done)
    fun category(group: String, name: String, done: () -> Unit) = perform({ repo.category(group, name) }, done)
    fun save(entry: Entry, done: () -> Unit) = perform({ repo.save(entry) }, done)
    fun setGroupActive(id: String, active: Boolean, done: () -> Unit) = perform({ repo.setGroupActive(id, active) }, done)
    fun deleteEmptyGroup(id: String, done: () -> Unit) = perform({ repo.deleteEmptyGroup(id) }, done)
    fun reorderGroups(ids: List<String>) = perform({ repo.reorderActiveGroups(ids) })
    fun renameCategory(id: String, name: String, done: () -> Unit) = perform({ repo.renameCategory(id, name) }, done)
    fun setCategoryActive(id: String, active: Boolean, done: () -> Unit) = perform({ repo.setCategoryActive(id, active) }, done)
    fun deleteEmptyCategory(id: String, done: () -> Unit) = perform({ repo.deleteEmptyCategory(id) }, done)
    fun renameGroup(id: String, name: String, done: () -> Unit) = perform({ repo.renameGroup(id, name) }, done)
    fun updateEntry(original: Entry, categoryId: String, cents: Long, date: LocalDate, note: String, done: () -> Unit) = perform({ repo.updateEntry(original, categoryId, cents, date, note) }, done)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = Room.databaseBuilder(applicationContext, LedgerDatabase::class.java, "ledger.db").build()
                val settings = applicationContext.getSharedPreferences("ledger_settings", MODE_PRIVATE)
                return LedgerViewModel(LedgerRepository(database), settings) as T
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun LedgerApp(vm: LedgerViewModel) {
    val ui by vm.uiData.collectAsStateWithLifecycle()
    val data = ui.home?.catalog ?: EmptyLedgerCatalog
    val loaded = ui.home != null
    val readEntries = remember(vm) { vm::readEntries }
    var page by rememberSaveable { mutableStateOf("记账") }
    var groupId by remember { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var calendarMode by rememberSaveable { mutableStateOf(true) }
    var calendarDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val calendarScroll = rememberLazyListState()
    val touchAreas = remember { TabTouchAreas() }
    val flowState = rememberSaveableStateHolder()
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var capturedTime by remember { mutableStateOf(LocalTime.now().withNano(0)) }
    var note by remember { mutableStateOf("") }
    var operationId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sheet by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf<String?>(null) }
    var groupAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var manageCategoriesFor by remember { mutableStateOf<String?>(null) }
    var editingCategory by remember { mutableStateOf<String?>(null) }
    var categoryAction by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var editingEntry by remember { mutableStateOf<Entry?>(null) }
    var deletingEntry by remember { mutableStateOf<Entry?>(null) }
    val selected = data.groups.find { it.id == groupId }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val month = YearMonth.from(today)
    LaunchedEffect(month) { vm.showMonth(month) }
    val home = ui.home?.takeIf { it.month == month }
    ReportDrawnWhen { home != null }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) today = LocalDate.now() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    val summary = home?.summary
    val history = if (page == "分类管理" || manageCategoriesFor != null) {
        rememberLedgerRead("category-history", ui.revision, loaded, vm::readHistoryCounts)
    } else null
    val numbers = history?.value
    val childrenByGroup = remember(data.categories) { data.categories.groupBy { it.groupId } }
    val activeGroups = remember(data.groups) { data.groups.filter { it.active } }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun navigateTab(label: String) {
        if (label == "明细" && (page != label || detailsId != null)) {
            calendarMode = true
            calendarDay = null
            calendarScroll.requestScrollToItem(0)
        }
        page = label
        detailsId = null
    }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val exportContract = remember { FlowDocumentContract() }
    val exportLauncher = rememberLauncherForActivityResult(exportContract) { uri ->
        vm.finishExport(resolver, uri) { count ->
            scope.launch { snack.showSnackbar("已保存 Excel，共 $count 笔流水") }
        }
    }
    androidx.activity.compose.BackHandler(enabled = selected != null || page == "分类管理" || detailsId != null) {
        if (sheet) sheet = false else { groupId = null; detailsId = null; page = "记账" }
    }
    Scaffold(snackbarHost = {
        val deletion = vm.pendingUndo
        if (deletion == null) SnackbarHost(snack)
        else Snackbar(
            modifier = Modifier.padding(12.dp),
            action = {
                TextButton(
                    enabled = !vm.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    onClick = {
                        vm.undoDeleteEntry(deletion) {
                            scope.launch { snack.showSnackbar("已恢复这笔记录") }
                        }
                    },
                ) { Text("撤销") }
            },
        ) { Text("已删除一笔记录") }
    }, bottomBar = {
        if (selected == null && editingEntry == null) NavigationBar(containerColor = Color.White) {
            LedgerTabs.forEach { label ->
                NavigationBarItem(selected = page == label, onClick = { navigateTab(label) },
                    icon = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFF2F2F2)))
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            val original = editingEntry
            if (original != null) {
                EntryEditor(original, data, today, vm.busy, onClose = { editingEntry = null }) { categoryId, cents, newDate, newNote ->
                    vm.updateEntry(original, categoryId, cents, newDate, newNote) {
                        editingEntry = null
                        scope.launch { snack.showSnackbar("已保存修改") }
                    }
                }
            } else if (selected != null) {
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
                EntryDateField(date, today, entryColor, !vm.busy) { date = it }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = note, onValueChange = { if (it.length <= 2000) note = it }, colors = fieldColors, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(22.dp))
                Button(onClick = { try { Money.parse(amount); sheet = true } catch (e: Exception) { vm.error = e.message ?: "检查金额" } }, colors = ButtonDefaults.buttonColors(containerColor = entryColor), enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("确定") }
            } else {
                val destination = LedgerPage(page, detailsId)
                SwipeableLedgerPages(
                    page = destination,
                    touchAreas = touchAreas,
                    calendarVisible = page == "明细" && detailsId == null && calendarMode,
                    modifier = Modifier.fillMaxSize(),
                    onTabSelected = ::navigateTab,
                ) { screen ->
                val screenActive = screen == destination
                Column(Modifier.fillMaxSize()) {
                if (screen.title == "明细" && screen.groupId == null) {
                    DetailsHeader(calendarMode) { calendarMode = it }
                } else {
                    Text(if (screen.title == "明细") "分类流水" else screen.title, fontSize = 28.sp)
                }
                Spacer(Modifier.height(if (screen.title == "明细" && screen.groupId == null) 10.dp else 16.dp))
                when (screen.title) {
                    "记账" -> {
                        Text("${month.year}年${month.monthValue}月", color = Color.Gray)
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("本月支出", fontSize = 13.sp, color = Color.Gray); Text("¥ ${summary?.expense.displayCents()}", fontSize = 22.sp) }
                            Column(horizontalAlignment = Alignment.End) { Text("本月收入", fontSize = 13.sp, color = Color.Gray); Text("¥ ${summary?.income.displayCents()}", fontSize = 22.sp, color = Red) }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("本月结余", fontSize = 13.sp, color = Color.Gray); Text("¥ ${summary?.balance.displayCents()}", fontSize = 16.sp) }
                        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
                        Text("长按大类卡片，上下拖动排序", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        ReorderableGroupList(groups = activeGroups, enabled = screenActive && loaded && !vm.busy,
                            onReorder = vm::reorderGroups, modifier = Modifier.weight(1f),
                            footer = {
                                item {
                                    if (loaded) TextButton(onClick = { newGroup = true }) { Text("＋ 新建大类") }
                                    if (home == null && vm.homeReadFailed) TextButton(onClick = vm::retryHome) { Text("读取失败 · 重试") }
                                    else if (!loaded) Text("正在读取本地账目…", color = Color.Gray)
                                    else if (activeGroups.isEmpty()) Text(if (data.groups.isEmpty()) "先创建一个大类，再添加自己的小类" else "大类均已停用，可到设置中的分类管理重新启用", color = Color.Gray)
                                }
                            }) { group, dragModifier ->
                                val total = home?.let { it.groupTotals[group.id] ?: 0L }
                                OutlinedCard(modifier = dragModifier, border = BorderStroke(1.dp, if (group.direction == Direction.INCOME) Red else Ink), colors = CardDefaults.outlinedCardColors(containerColor = Color.White), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(Modifier.weight(1f).clickable(enabled = !vm.busy) { groupId = group.id; amount = ""; note = ""; date = LocalDate.now(); capturedTime = LocalTime.now().withNano(0); operationId = UUID.randomUUID().toString() }.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column { Text(group.name, fontSize = 18.sp); Text(if (group.direction == Direction.INCOME) "本月收入" else "本月支出", color = Color.Gray, fontSize = 12.sp) }
                                            Text(total.displayCents(), color = if (group.direction == Direction.INCOME) Red else Ink, fontSize = 20.sp)
                                        }
                                        TextButton(enabled = !vm.busy, onClick = { detailsId = group.id; page = "明细" }) { Text("明细 ›", fontSize = 12.sp) }
                                    }
                                }
                        }
                    }
                    "明细" -> {
                        if (screen.groupId == null && calendarMode) {
                            CalendarDetails(
                                data = data,
                                catalogReady = loaded,
                                revision = ui.revision,
                                readEntries = readEntries,
                                today = today,
                                selectedDate = calendarDay?.let(LocalDate::ofEpochDay) ?: today,
                                baselineCents = vm.expenseBaseline,
                                scrollState = calendarScroll,
                                enabled = screenActive && !vm.busy,
                                monthGestureArea = if (screenActive) Modifier.onGloballyPositioned { touchAreas.calendar = it } else Modifier,
                                onSelect = { calendarDay = it.toEpochDay() },
                                onBaselineChange = vm::saveExpenseBaseline,
                                onEdit = { editingEntry = it },
                                onDelete = { deletingEntry = it },
                            )
                        } else {
                            flowState.SaveableStateProvider(screen.groupId?.let { "group:$it" } ?: "all") {
                                FlowDetails(
                                    data = data, initialGroupId = screen.groupId, today = today,
                                    catalogReady = loaded, revision = ui.revision, readEntries = readEntries,
                                    enabled = screenActive && !vm.busy, exportBusy = vm.exportBusy, navigationActive = screenActive,
                                    onExitGroup = { detailsId = null; page = "记账" },
                                    onExport = { request ->
                                        if (vm.startExport(request)) {
                                            try { exportLauncher.launch(request.fileName) }
                                            catch (_: android.content.ActivityNotFoundException) { vm.exportPickerUnavailable() }
                                        }
                                    },
                                    onEdit = { editingEntry = it },
                                    onDelete = { deletingEntry = it },
                                )
                            }
                        }
                    }
                    "设置" -> {
                        TextButton(onClick = { page = "分类管理" }) { Text("分类管理 ›") }
                        Spacer(Modifier.height(18.dp)); Text("本地记账 0.1.10", fontSize = 18.sp)
                        Text("明细可按日历或流水查看。点记录修改，长按可删除。流水支持导出 Excel。", color = Color.Gray)
                    }
                    "分类管理" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            TextButton(enabled = loaded, onClick = { newGroup = true }) { Text("＋ 新建大类") }
                            if (history != null && numbers == null) LedgerReadStatus(history)
                        }
                        items(data.groups, key = { it.id }) { group ->
                            val children = childrenByGroup[group.id].orEmpty()
                            val historyCount = numbers?.let { counts -> children.sumOf { counts[it.id] ?: 0L } }
                            OutlinedCard(border = BorderStroke(1.dp, if (group.direction == Direction.INCOME) Red else Ink), colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(group.name + if (group.active) "" else "（已停用）", fontSize = 20.sp)
                                    Text(children.joinToString("、") { it.name }.ifBlank { "暂无小类" }, color = Color.Gray)
                                    Text("${children.size} 个小类" + (historyCount?.let { " · $it 笔历史记录" } ?: ""), fontSize = 12.sp, color = Color.Gray)
                                    TextButton(enabled = !vm.busy, onClick = { manageCategoriesFor = group.id }) { Text("管理小类 ›") }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(enabled = !vm.busy, onClick = { editingGroup = group.id }) { Text("改名") }
                                        if (group.active) TextButton(enabled = !vm.busy, onClick = { newCategory = group.id }) { Text("添加小类") }
                                        TextButton(enabled = !vm.busy, onClick = { groupAction = group.id to if (group.active) "deactivate" else "activate" }) { Text(if (group.active) "停用" else "重新启用") }
                                        if (historyCount == 0L) TextButton(enabled = !vm.busy, onClick = { groupAction = group.id to "delete" }) { Text("删除", color = Red) }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
            }
        }
    }
    manageCategoriesFor?.let { id ->
        data.groups.find { it.id == id }?.let { group ->
            ModalBottomSheet(onDismissRequest = { manageCategoriesFor = null }, containerColor = Color.White) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    Text("${group.name} · 小类管理", fontSize = 22.sp)
                    if (!group.active) Text("所属大类已停用，重新启用大类后才能恢复记账", color = Color.Gray, fontSize = 12.sp)
                    val children = childrenByGroup[id].orEmpty()
                    if (history != null && numbers == null) LedgerReadStatus(history)
                    if (children.isEmpty()) Text("暂无小类", color = Color.Gray)
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(children, key = { it.id }) { category ->
                            val historyCount = numbers?.let { it[category.id] ?: 0L }
                            Column(Modifier.fillMaxWidth()) {
                                Text(category.name + if (category.active) "" else "（已停用）", fontSize = 18.sp)
                                if (historyCount != null) Text("$historyCount 笔历史记录", color = Color.Gray, fontSize = 12.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(enabled = !vm.busy, onClick = { editingCategory = category.id }) { Text("改名") }
                                    TextButton(enabled = !vm.busy && (category.active || group.active), onClick = { categoryAction = category.id to if (category.active) "deactivate" else "activate" }) { Text(if (category.active) "停用" else "重新启用") }
                                    if (historyCount == 0L) TextButton(enabled = !vm.busy, onClick = { categoryAction = category.id to "delete" }) { Text("删除", color = Red) }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    if (group.active) TextButton(enabled = !vm.busy, onClick = { newCategory = id }) { Text("＋ 新建小类") }
                }
            }
        }
    }
    editingGroup?.let { id ->
        data.groups.find { it.id == id }?.let { group ->
            key(id) {
                NameDialog("修改大类名称", false, vm.busy, { editingGroup = null }, initialName = group.name,
                    confirmLabel = "保存", description = "历史明细中的大类名称也会更新，账目、小类和排列顺序保持不变。") { name, _ ->
                    vm.renameGroup(id, name) { editingGroup = null }
                }
            }
        }
    }
    editingCategory?.let { id ->
        data.categories.find { it.id == id }?.let { category ->
            key(id) {
                NameDialog("修改小类名称", false, vm.busy, { editingCategory = null }, initialName = category.name,
                    confirmLabel = "保存", description = "历史明细中的分类名称也会更新，金额和记录保持不变。") { name, _ ->
                    vm.renameCategory(id, name) { editingCategory = null }
                }
            }
        }
    }
    categoryAction?.let { (id, action) ->
        data.categories.find { it.id == id }?.let { category ->
            val title = when (action) { "delete" -> "删除空小类"; "activate" -> "重新启用小类"; else -> "停用小类" }
            val message = when (action) {
                "delete" -> "将删除“${category.name}”。该小类没有记账记录，删除后无法撤销。"
                "activate" -> "“${category.name}”将重新出现在记账时的小类选项中。"
                else -> "“${category.name}”将从记账选项中隐藏，历史记录和累计仍保留，可在此重新启用。"
            }
            AlertDialog(onDismissRequest = { if (!vm.busy) categoryAction = null }, title = { Text(title) }, text = { Text(message) },
                confirmButton = { TextButton(enabled = !vm.busy, onClick = {
                    val done = { categoryAction = null }
                    if (action == "delete") vm.deleteEmptyCategory(id, done) else vm.setCategoryActive(id, action == "activate", done)
                }) { Text(if (vm.busy) "处理中…" else "确认") } },
                dismissButton = { TextButton(enabled = !vm.busy, onClick = { categoryAction = null }) { Text("取消") } })
        }
    }
    groupAction?.let { (id, action) ->
        data.groups.find { it.id == id }?.let { group ->
            val title = when (action) { "delete" -> "删除空大类"; "activate" -> "重新启用大类"; else -> "停用大类" }
            val message = when (action) {
                "delete" -> "将删除“${group.name}”及其小类。该大类没有记账记录，删除后无法撤销。"
                "activate" -> "“${group.name}”将重新出现在记账首页，各小类保留原有的启用状态。"
                else -> "“${group.name}”将从记账首页隐藏。历史记录和累计仍保留，可在分类管理中重新启用。"
            }
            AlertDialog(onDismissRequest = { if (!vm.busy) groupAction = null }, title = { Text(title) }, text = { Text(message) },
                confirmButton = { TextButton(enabled = !vm.busy, onClick = {
                    val done = { groupAction = null }
                    if (action == "delete") vm.deleteEmptyGroup(id, done) else vm.setGroupActive(id, action == "activate", done)
                }) { Text(if (vm.busy) "处理中…" else "确认") } },
                dismissButton = { TextButton(enabled = !vm.busy, onClick = { groupAction = null }) { Text("取消") } })
        }
    }
    if (newGroup) NameDialog("新建大类", true, vm.busy, { newGroup = false }) { name, direction -> vm.group(name, direction) { newGroup = false } }
    newCategory?.let { id -> NameDialog("新建小类", false, vm.busy, { newCategory = null }) { name, _ -> vm.category(id, name) { newCategory = null } } }
    if (sheet && selected != null) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 32.dp)) {
            Text("${selected.name} · 选择小类", fontSize = 22.sp); Text("¥ $amount", fontSize = 26.sp); Spacer(Modifier.height(14.dp))
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                data.categories.filter { it.groupId == selected.id && it.active }.forEach { category ->
                    OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), border = BorderStroke(1.dp, if (selected.direction == Direction.INCOME) Red else Ink), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected.direction == Direction.INCOME) Red else Ink), enabled = !vm.busy, onClick = {
                        val now = Instant.now().toString()
                        vm.save(Entry(operationId, category.id, Money.parse(amount), date.atTime(capturedTime).toString(), note, now, now)) { sheet = false; groupId = null; page = "记账"; scope.launch { snack.showSnackbar("已保存：${selected.name} / ${category.name}") } }
                    }) { Text(category.name, fontSize = 16.sp, lineHeight = 22.sp) }
                }
            }
            TextButton(onClick = { newCategory = selected.id }) { Text("＋ 新建小类") }
        }
    }
    deletingEntry?.let { entry ->
        val category = data.categories.firstOrNull { it.id == entry.categoryId }
        val group = data.groups.firstOrNull { it.id == category?.groupId }
        val direction = if (group?.direction == Direction.INCOME) "收入" else "支出"
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text("删除这笔记录？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOfNotNull(group?.name, category?.name).joinToString(" / "))
                    Text(entry.occurredAt.replace('T', ' '))
                    Text("$direction ¥${Money.format(entry.cents)}")
                    Text("删除后有 5 秒可以撤销。", color = Color.Gray, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = !vm.busy, onClick = {
                    deletingEntry = null
                    vm.deleteEntry(entry)
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deletingEntry = null }) { Text("取消") } },
        )
    }
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("操作未完成") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("知道了") } }) }
}

@Composable private fun NameDialog(title: String, showDirection: Boolean, busy: Boolean, cancel: () -> Unit, initialName: String = "", confirmLabel: String = "创建", description: String? = null, save: (String, Direction) -> Unit) {
    var name by remember { mutableStateOf(initialName) }; var direction by remember { mutableStateOf(Direction.EXPENSE) }
    AlertDialog(onDismissRequest = { if (!busy) cancel() }, title = { Text(title) }, text = {
        Column { if (description != null) Text(description, color = Color.Gray, fontSize = 12.sp)
            OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("名称") }, singleLine = true)
            if (showDirection) Row { FilterChip(direction == Direction.EXPENSE, { direction = Direction.EXPENSE }, label = { Text("支出") }); Spacer(Modifier.width(8.dp)); FilterChip(direction == Direction.INCOME, { direction = Direction.INCOME }, label = { Text("收入") }) }
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { save(name, direction) }) { Text(confirmLabel) } }, dismissButton = { TextButton(enabled = !busy, onClick = cancel) { Text("取消") } })
}
