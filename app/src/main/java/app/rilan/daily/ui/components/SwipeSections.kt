package app.rilan.daily.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * 页面内「分段左右滑动切换」——观察式手势，不抢孩子的事件。
 *
 * 1.7.5 放宽判定（用户反馈「非常难滑动」）：
 * - 阈值从宽度 16% 降到 8%，方向比例从 1.5 降到 1.2，斜着滑也能触发。
 * - 不再「某一帧被消费就整段判废」：改为累计「横向被横滑子控件吃掉的位移」，
 *   只有它超过横向总位移的一半（说明确实在 chip 的横向滚动条上滑）才放弃。
 *   普通横滑即使个别帧被纵向列表消费，也不会误判。
 * - 位移只在 Initial 阶段用相邻两帧的绝对坐标差累计（Final 阶段拿到的是残余量）。
 * - up 帧不读 Final：awaitEachGesture 自己会用 Final 排空，抢读会吞掉下一次手势。
 * - onSwipe 用 rememberUpdatedState 读最新闭包，避免切段后拿到旧索引。
 */
@Composable
fun Modifier.swipeSections(
    enabled: Boolean = true,
    threshold: Float = 0.08f,
    onSwipe: (direction: Int) -> Unit,
): Modifier {
    val current by rememberUpdatedState(onSwipe)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var lastX = down.position.x
            var lastY = down.position.y
            var totalX = 0f
            var totalY = 0f
            var consumedX = 0f
            var released = false
            while (true) {
                val initial = awaitPointerEvent(PointerEventPass.Initial)
                val change = initial.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - lastX
                val dy = change.position.y - lastY
                lastX = change.position.x
                lastY = change.position.y
                totalX += dx
                totalY += dy

                val isUp = !change.pressed
                if (!isUp) {
                    val final = awaitPointerEvent(PointerEventPass.Final)
                    val finalChange = final.changes.firstOrNull { it.id == down.id }
                    // 只有「横向占优」的帧被消费，才算被横滑子控件（周期 chip）吃掉；
                    // 纵向列表消费纵向位移不算，避免斜着上下滑被误判。
                    if (finalChange != null && finalChange.isConsumed && abs(dx) > abs(dy) * 1.2f) {
                        consumedX += abs(dx)
                    }
                }

                if (isUp) {
                    released = true
                    break
                }
            }
            val width = size.width.toFloat()
            if (!released || width <= 0f) return@awaitEachGesture
            if (consumedX > abs(totalX) * 0.5f) return@awaitEachGesture
            if (abs(totalX) > width * threshold && abs(totalX) > abs(totalY) * 1.2f) {
                current(if (totalX < 0f) 1 else -1)
            }
        }
    }
}
