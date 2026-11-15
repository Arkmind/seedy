package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.arkmind.seedy.ui.components.boxShadow

/** The background of a row picked up to move. */
internal val Dragged = Color(0xFF262626)

private val LiftedShape = RoundedCornerShape(16.dp)

/**
 * Hold-and-drag reordering of a short list, keyed by [K]: [start] picks an item up, [move] follows the finger (past half
 * of a neighbour, the two swap) and [end] drops it.
 */
@Stable
class ReorderState<K> {
    /** The keys while one is dragged, else null (the list as it is). */
    var order by mutableStateOf<List<K>?>(null)
        private set

    /** The key being dragged. */
    var drag by mutableStateOf<K?>(null)
        private set

    /** How far (px) the dragged item sits from its slot. */
    var offset by mutableFloatStateOf(0f)
        private set

    /** Each item's height (px), as laid out. */
    val heights = mutableStateMapOf<K, Int>()

    /** [items] in the dragged order, or as they are. */
    fun <T> shown(items: List<T>, key: (T) -> K): List<T> =
        order?.mapNotNull { k -> items.firstOrNull { key(it) == k } }?.takeIf { it.size == items.size } ?: items

    /** Picks [key] up from [keys] (the order on screen). */
    fun start(keys: List<K>, key: K) {
        order = keys
        drag = key
        offset = 0f
    }

    /**
     * Moves the dragged item by [dy] px. Nothing moves above index [floor]; with [clampAtFloor] the item also stops
     * 8 px past it.
     */
    fun move(dy: Float, floor: Int = 0, clampAtFloor: Boolean = false) {
        val key = drag ?: return
        val list = order?.toMutableList() ?: return
        offset += dy
        var i = list.indexOf(key)
        // Past half of the neighbour: swap with it.
        while (i < list.lastIndex && offset > (heights[list[i + 1]] ?: 0) / 2f) {
            offset -= heights[list[i + 1]] ?: 0
            list.add(i + 1, list.removeAt(i)); i++
        }
        while (i > floor && offset < -(heights[list[i - 1]] ?: 0) / 2f) {
            offset += heights[list[i - 1]] ?: 0
            list.add(i - 1, list.removeAt(i)); i--
        }
        if (clampAtFloor && i == floor && offset < 0) offset = maxOf(offset, -8f)
        order = list
    }

    /** Drops the dragged item: the keys in their new order and the one moved, or null when nothing was dragged. */
    fun end(): Drop<K>? {
        val key = drag
        val list = order
        drag = null
        offset = 0f
        order = null
        return if (key != null && list != null) Drop(list, key) else null
    }

    class Drop<K>(val order: List<K>, val moved: K)
}

@Composable
fun <K> rememberReorder(): ReorderState<K> = remember { ReorderState() }

/**
 * Drags [reorder] by this item ([key], at [keys] on screen): after a long press, or at once for a grip. [floor] and
 * [clampAtFloor] go to [ReorderState.move].
 */
fun <K> Modifier.reorderDrag(
    reorder: ReorderState<K>,
    key: K,
    keys: () -> List<K>,
    longPress: Boolean = true,
    floor: Int = 0,
    clampAtFloor: Boolean = false,
    onEnd: (ReorderState.Drop<K>) -> Unit,
): Modifier = pointerInput(key) {
    val end: () -> Unit = { reorder.end()?.let(onEnd) }
    if (longPress) {
        detectDragGesturesAfterLongPress(onDragStart = { reorder.start(keys(), key) }, onDragEnd = end, onDragCancel = end) { c, d ->
            c.consume(); reorder.move(d.y, floor, clampAtFloor)
        }
    } else {
        detectDragGestures(onDragStart = { reorder.start(keys(), key) }, onDragEnd = end, onDragCancel = end) { c, d ->
            c.consume(); reorder.move(d.y, floor, clampAtFloor)
        }
    }
}

/** A picked-up item rides above the others, 2% bigger, [offset] px from its slot. */
fun Modifier.reorderLift(lifted: Boolean, offset: Density.() -> Float): Modifier =
    zIndex(if (lifted) 1f else 0f).graphicsLayer {
        if (lifted) {
            translationY = offset()
            scaleX = 1.02f
            scaleY = 1.02f
        }
    }

/** A picked-up item's card: shadow, [Dragged] and a faint ring. */
fun Modifier.liftedCard(lifted: Boolean): Modifier =
    if (lifted) boxShadow(Color.Black.copy(alpha = 0.6f), 30.dp, 14.dp, 16.dp).clip(LiftedShape).background(Dragged).border(1.dp, Color.White.copy(alpha = 0.08f), LiftedShape) else this
