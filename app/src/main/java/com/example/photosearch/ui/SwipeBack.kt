package com.example.photosearch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.photosearch.ui.theme.Primary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v3.7.0：右滑返回上一级。
 *
 * - 只从屏幕左边缘 72dp 内起手才接管，避免与列表垂直滚动、图片横向浏览冲突；
 * - 起手后先判断方向：水平右滑且明显大于垂直位移才会命中，垂直滑动立即放行给子级；
 * - 拖动过程中内容跟手位移，松手超过阈值则滑出并回调 onBack，否则回弹；
 * - 同时注册 BackHandler，让系统返回键与右滑行为完全一致。
 *
 * 项目当前用 when 状态切换页面（未接入 Navigation Compose），因此这里用一个
 * 轻量容器实现，而不依赖 NavController。
 */
@Composable
fun SwipeBackContainer(
    enabled: Boolean = true,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    BackHandler(enabled = enabled) { onBack() }

    val density = LocalDensity.current
    val edgePx = with(density) { 72.dp.toPx() }
    val thresholdPx = with(density) { 110.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFECEFF1))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 非左边缘起手：本次手势完全交给子级（列表滚动等）
                        if (down.position.x > edgePx) return@awaitEachGesture

                        val pointerId = down.id
                        var totalX = 0f
                        var totalY = 0f
                        var accepted = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) break
                            totalX += change.position.x - change.previousPosition.x
                            totalY += change.position.y - change.previousPosition.y

                            if (!accepted) {
                                when {
                                    // 明确的右滑：接管手势
                                    totalX > 12f && totalX > abs(totalY) * 1.2f -> {
                                        accepted = true
                                        dragging = true
                                        change.consume()
                                    }
                                    // 垂直滚动 / 左滑：放行，本次不再拦截
                                    abs(totalY) > 24f || totalX < -24f -> break
                                }
                            }
                            if (accepted) {
                                change.consume()
                                val x = totalX.coerceAtLeast(0f)
                                scope.launch { offsetX.snapTo(x) }
                            }
                        }

                        dragging = false
                        if (accepted) {
                            if (totalX > thresholdPx) {
                                scope.launch {
                                    offsetX.animateTo(screenWidth, tween(150))
                                    onBack()
                                    offsetX.snapTo(0f)
                                }
                            } else {
                                scope.launch { offsetX.animateTo(0f, tween(150)) }
                            }
                        }
                    }
                }
        ) {
            // 左边缘露出的返回提示，随拖动进度淡入
            val progress = (offsetX.value / thresholdPx).coerceIn(0f, 1f)
            if (progress > 0f || dragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.15f + 0.55f * progress)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "右滑返回",
                        tint = Primary.copy(alpha = 0.4f + 0.6f * progress),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .background(Color.White)
            ) {
                content()
            }
        }
    }
}
