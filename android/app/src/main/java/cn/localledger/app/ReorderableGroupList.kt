package cn.localledger.app

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cn.localledger.core.Group
import kotlinx.coroutines.isActive

/** Owns the drag preview only. The repository commits the final permutation on release. */
private class GroupDragState(val list: LazyListState) {
    var ids by mutableStateOf(emptyList<String>())
    var dragging by mutableStateOf<String?>(null)
    var top by mutableFloatStateOf(0f)
    var pointerY by mutableFloatStateOf(0f)
    var enabled = true

    fun start(y: Float): Boolean {
        if (!enabled) return false
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key in ids && y >= it.offset && y < it.offset + it.size
        } ?: return false
        dragging = item.key as String
        top = item.offset.toFloat()
        pointerY = y
        return true
    }

    fun move(delta: Float) {
        if (dragging == null) return
        top += delta
        pointerY += delta
    }

    fun movePastNeighbours() {
        val id = dragging ?: return
        val from = ids.indexOf(id)
        val visible = list.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        // Wait for the previous move to be measured before choosing another target.
        if (current.index != from) return
        val center = top + current.size / 2f
        val currentCenter = current.offset + current.size / 2f
        val candidates = visible.filter { it.key in ids && it.key != id && ids.indexOf(it.key) == it.index }
        val target = if (center > currentCenter) {
            candidates.filter { it.index > from && center > it.offset + it.size / 2f }.maxByOrNull { it.index }
        } else {
            candidates.filter { it.index < from && center < it.offset + it.size / 2f }.minByOrNull { it.index }
        } ?: return
        val firstIndex = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        ids = ids.toMutableList().apply { add(target.index, removeAt(from)) }
        // Keep the viewport in place when its first item's stable key changes index.
        list.requestScrollToItem(firstIndex, firstOffset)
    }
}

@Composable
internal fun ReorderableGroupList(
    groups: List<Group>,
    enabled: Boolean,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    footer: LazyListScope.() -> Unit,
    content: @Composable (Group, Modifier) -> Unit,
) {
    val list = rememberLazyListState()
    val drag = remember(list) { GroupDragState(list) }
    val sourceIds = groups.map { it.id }
    val latestSource by rememberUpdatedState(sourceIds)
    val commit by rememberUpdatedState(onReorder)
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 52.dp.toPx() }
    val maxStep = with(LocalDensity.current) { 12.dp.toPx() }
    SideEffect { drag.enabled = enabled }
    LaunchedEffect(sourceIds, enabled) {
        // Keep the preview while saving; reset it from persisted state on success or failure.
        if (enabled && drag.dragging == null) drag.ids = sourceIds
    }
    LaunchedEffect(drag.dragging) {
        if (drag.dragging == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive && drag.dragging != null) {
            val now = withFrameNanos { it }
            val frameScale = ((now - previousFrame) / 16_666_667f).coerceIn(0f, 3f)
            previousFrame = now
            val layout = list.layoutInfo
            val topEdge = layout.viewportStartOffset + edge
            val bottomEdge = layout.viewportEndOffset - edge
            val velocity = when {
                drag.pointerY < topEdge -> -((topEdge - drag.pointerY) / edge).coerceIn(0f, 1f)
                drag.pointerY > bottomEdge -> ((drag.pointerY - bottomEdge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (velocity != 0f) list.scrollBy(velocity * maxStep * frameScale)
            drag.movePastNeighbours()
        }
    }
    val groupMap = groups.associateBy { it.id }
    LazyColumn(
        state = list,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.pointerInput(drag) {
            detectDragGesturesAfterLongPress(
                onDragStart = { if (drag.start(it.y)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onDrag = { change, delta ->
                    if (drag.dragging != null) { change.consume(); drag.move(delta.y) }
                },
                onDragEnd = {
                    if (drag.dragging != null) {
                        drag.dragging = null
                        if (drag.ids != latestSource) commit(drag.ids)
                    }
                },
                onDragCancel = { drag.dragging = null; drag.ids = latestSource },
            )
        },
    ) {
        items(drag.ids.mapNotNull { groupMap[it] }, key = { it.id }) { group ->
            val moving = drag.dragging == group.id
            val index = drag.ids.indexOf(group.id)
            val actions = if (!enabled) emptyList() else buildList {
                fun moveTo(target: Int): Boolean {
                    commit(drag.ids.toMutableList().apply { add(target, removeAt(index)) })
                    return true
                }
                if (index > 0) add(CustomAccessibilityAction("上移") { moveTo(index - 1) })
                if (index < drag.ids.lastIndex) add(CustomAccessibilityAction("下移") { moveTo(index + 1) })
            }
            val itemModifier = Modifier
                .zIndex(if (moving) 1f else 0f)
                .graphicsLayer {
                    translationY = if (moving) drag.top - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == group.id }?.offset ?: 0) else 0f
                    scaleX = if (moving) 1.02f else 1f
                    scaleY = if (moving) 1.02f else 1f
                    shadowElevation = if (moving) 8.dp.toPx() else 0f
                }
                .semantics { customActions = actions }
            content(group, itemModifier)
        }
        footer()
    }
}
