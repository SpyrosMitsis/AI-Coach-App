package com.workoutmaker.app.ui.components

// Drag-to-reorder for a LazyColumn (grab a handle, drag the card, rows shuffle
// out of the way). Follows the official Compose drag-and-drop list pattern:
// the dragged item's visual offset is derived from "where the finger put it"
// minus "where the list currently lays it out", so it stays glued to the finger
// across swaps and autoscroll.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Composable
fun rememberDragDropState(
    listState: LazyListState,
    canDrag: (LazyListItemInfo) -> Boolean,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    val state = remember(listState) { DragDropState(listState, scope, canDrag, onMove) }
    LaunchedEffect(state) {
        while (true) {
            val diff = state.scrollChannel.receive()
            listState.scrollBy(diff)
        }
    }
    return state
}

class DragDropState internal constructor(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val canDrag: (LazyListItemInfo) -> Boolean,
    private val onMove: (Int, Int) -> Unit,
) {
    /** LazyColumn index of the item under the finger, null when not dragging. */
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    /** The just-dropped item, still animating home ([settlingItemOffset]). */
    var settlingItemIndex by mutableStateOf<Int?>(null)
        private set
    val settlingItemOffset = Animatable(0f)

    internal val scrollChannel = Channel<Float>(Channel.CONFLATED)

    private var draggedDistance by mutableFloatStateOf(0f)
    private var draggingInitialOffset = 0

    private val draggingLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    /** Visual translation for the dragged composable (read in graphicsLayer). */
    val draggingItemOffset: Float
        get() = draggingLayoutInfo?.let { draggingInitialOffset + draggedDistance - it.offset } ?: 0f

    fun onDragStart(key: Any) {
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        android.util.Log.d("WM.drag", "onDragStart key=$key found=${item != null} canDrag=${item?.let(canDrag)}")
        if (item == null || !canDrag(item)) return
        draggingItemIndex = item.index
        draggingInitialOffset = item.offset
        draggedDistance = 0f
    }

    fun onDrag(deltaY: Float) {
        android.util.Log.d("WM.drag", "onDrag d=$deltaY total=$draggedDistance idx=$draggingItemIndex off=$draggingItemOffset")
        draggedDistance += deltaY
        val current = draggingLayoutInfo ?: return
        val startOffset = draggingInitialOffset + draggedDistance
        val endOffset = startOffset + current.size
        val middle = (startOffset + endOffset) / 2f

        val target = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != current.index && canDrag(item) &&
                middle.toInt() in item.offset..(item.offset + item.size)
        }
        if (target != null) {
            // LazyColumn anchors its scroll position to the first visible item;
            // moving that item would make the viewport chase it (a full-card
            // jump per swap). Pin the scroll position across the move.
            if (current.index == state.firstVisibleItemIndex || target.index == state.firstVisibleItemIndex) {
                state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
            }
            android.util.Log.d("WM.drag", "onMove ${current.index} -> ${target.index}")
            onMove(current.index, target.index)
            draggingItemIndex = target.index
        } else {
            // Near the viewport edges: keep scrolling so long lists are reachable.
            val overscroll = when {
                draggedDistance > 0 -> (endOffset - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                draggedDistance < 0 -> (startOffset - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollChannel.trySend(overscroll)
        }
    }

    fun onDragEnd() {
        val index = draggingItemIndex
        if (index != null) {
            // Glide the dropped card from under the finger into its slot instead
            // of snapping. The item keeps rendering this animated offset until
            // it lands (see settlingItemIndex in the list's item modifier).
            val startOffset = draggingItemOffset
            settlingItemIndex = index
            scope.launch {
                settlingItemOffset.snapTo(startOffset)
                settlingItemOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                if (settlingItemIndex == index) settlingItemIndex = null
            }
        }
        draggingItemIndex = null
        draggedDistance = 0f
    }
}
