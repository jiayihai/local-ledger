package cn.localledger.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LedgerRead<T : Any>(private val result: Result<T>?, val retry: () -> Unit) {
    val value: T? get() = result?.getOrNull()
    val failed: Boolean get() = result?.isFailure == true
}

@Composable
internal fun <T : Any> rememberLedgerRead(
    key: Any,
    revision: Long,
    enabled: Boolean,
    query: suspend () -> T,
): LedgerRead<T> {
    var attempt by remember(key, revision) { mutableIntStateOf(0) }
    val result = remember(key, revision, attempt, enabled) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(result) {
        if (!enabled) return@LaunchedEffect
        result.value = try {
            Result.success(withContext(Dispatchers.IO) { query() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    return LedgerRead(result.value) { attempt++ }
}

@Composable
internal fun LedgerReadStatus(read: LedgerRead<*>, modifier: Modifier = Modifier) {
    if (read.failed) TextButton(onClick = read.retry, modifier = modifier) { Text("读取失败 · 重试") }
    else Text("正在读取本地账目…", fontSize = 13.sp, color = Color.Gray, modifier = modifier)
}
