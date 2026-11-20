package cn.localledger.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal val LedgerTabs = listOf("记账", "明细", "设置")

internal data class LedgerPage(val title: String, val groupId: String?) {
    val tabIndex: Int get() = if (groupId == null) LedgerTabs.indexOf(title) else -1
}

internal class TabTouchAreas {
    var content: LayoutCoordinates? = null
    var calendar: LayoutCoordinates? = null

    fun startsInCalendar(position: Offset): Boolean {
        val origin = content?.takeIf { it.isAttached } ?: return false
        val grid = calendar?.takeIf { it.isAttached } ?: return false
        return grid.boundsInRoot().contains(origin.localToRoot(position))
    }
}

@Composable
internal fun SwipeableLedgerPages(
    page: LedgerPage,
    touchAreas: TabTouchAreas,
    calendarVisible: Boolean,
    modifier: Modifier = Modifier,
    onTabSelected: (String) -> Unit,
    content: @Composable (LedgerPage) -> Unit,
) {
    val select by rememberUpdatedState(onTabSelected)
    val currentPage by rememberUpdatedState(page)
    val reserveCalendar by rememberUpdatedState(calendarVisible)
    AnimatedContent(
        targetState = page,
        modifier = modifier.clipToBounds()
            .onGloballyPositioned { touchAreas.content = it }
            .pointerInput(touchAreas) {
                val threshold = maxOf(48.dp.toPx(), viewConfiguration.touchSlop * 2f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = currentPage
                    if (start.tabIndex < 0 || reserveCalendar && touchAreas.startsInCalendar(down.position)) {
                        return@awaitEachGesture
                    }
                    var horizontal = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (currentPage != start || event.changes.any { it.id != down.id && it.pressed }) break
                        val delta = change.position - down.position
                        val across = abs(delta.x)
                        val downwards = abs(delta.y)
                        if (!change.pressed) {
                            if (!change.isConsumed && horizontal && across >= threshold && across >= downwards * 1.3f) {
                                LedgerTabs.getOrNull(start.tabIndex + if (delta.x < 0f) 1 else -1)?.let(select)
                            }
                            break
                        }
                        if (change.isConsumed) break
                        if (!horizontal) {
                            if (downwards > viewConfiguration.touchSlop && downwards >= across) break
                            horizontal = across > viewConfiguration.touchSlop && across >= downwards * 1.3f
                        }
                        if (horizontal) change.consume()
                    }
                }
            },
        transitionSpec = {
            val from = initialState.tabIndex
            val to = targetState.tabIndex
            if (from < 0 || to < 0 || from == to) EnterTransition.None togetherWith ExitTransition.None
            else {
                val direction = to.compareTo(from)
                slideInHorizontally(tween(200)) { direction * it } togetherWith
                    slideOutHorizontally(tween(200)) { -direction * it }
            }
        },
        label = "主页面",
    ) { shown -> content(shown) }
}
