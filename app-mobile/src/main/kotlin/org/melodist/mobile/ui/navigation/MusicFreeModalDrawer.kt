package org.melodist.mobile.ui.navigation

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---- doc/10 §4.4 手势与动画常量（集中可调，M3 现值见括号） ----

/** 边缘起手区宽度（M3 为整屏手势域） */
private val EDGE_ZONE_WIDTH = 28.dp

/** 面板宽度上限（与 M3 DrawerDefaults.MaximumDrawerWidth 一致） */
private val DRAWER_MAX_WIDTH = 360.dp

/** 面板右端圆角（M3 modal 默认 CornerLargeEnd 等效） */
private val DRAWER_END_CORNER_RADIUS = 28.dp

/** 松手位移判定阈值（M3 DrawerPositionalThreshold = 0.5f） */
private const val OPEN_FRACTION_THRESHOLD = 0.35f

/** 松手速度判定阈值（M3 DrawerVelocityThreshold = 400dp/s） */
private val OPEN_VELOCITY_THRESHOLD = 600.dp

/** 拖动释放 settle 与程序化打开共用：无过冲、尾巴短（M3 = spring(0.9, 700)） */
private val SETTLE_SPRING: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)

/** 程序化关闭（点遮罩/点条目/返回键；M3 = spring(1.0, 3800)） */
private val CLOSE_MOTION: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2200f)

private const val DRAWER_PANE_TITLE = "菜单"
private const val SCRIM_CLOSE_DESCRIPTION = "关闭菜单"

/** 抽屉开合状态（Material3 DrawerValue 的 Closed/Open 二值等价物）。 */
enum class MusicFreeDrawerValue {
    Closed,
    Open,
}

/**
 * MusicFree 风格抽屉状态：由 [MusicFreeModalDrawer] 驱动（注入 density 与锚点），
 * 对外提供 M3 DrawerState 等价的 open/close/isOpen API，动画规格为自定弹簧（doc/10 §4.4）。
 */
@Stable
class MusicFreeDrawerState(initialValue: MusicFreeDrawerValue = MusicFreeDrawerValue.Closed) {

    internal var density: Density? by mutableStateOf(null)

    // 带 thresholds/specs 的工厂在 foundation 1.12 标记弃用（新范式走 AnchoredDraggableDefaults.flingBehavior，
    // 但其重载收不下自定义 velocityThreshold/decay）；material3 1.4.0 的 DrawerState 同样使用本工厂，
    // 保持同一行为路径并显式抑制（doc/10 §4.4）
    @Suppress("DEPRECATION")
    internal val anchoredDraggableState: AnchoredDraggableState<MusicFreeDrawerValue> =
        AnchoredDraggableState(
            initialValue = initialValue,
            positionalThreshold = { distance -> distance * OPEN_FRACTION_THRESHOLD },
            velocityThreshold = { with(requireDensity()) { OPEN_VELOCITY_THRESHOLD.toPx() } },
            snapAnimationSpec = SETTLE_SPRING,
            decayAnimationSpec = AnchoredDraggableDefaults.DecayAnimationSpec,
            confirmValueChange = { true },
        )

    /** 是否完全打开（settle 后语义，与 M3 DrawerState.isOpen 一致） */
    val isOpen: Boolean
        get() = anchoredDraggableState.settledValue == MusicFreeDrawerValue.Open

    /** 是否完全关闭（settle 后语义） */
    val isClosed: Boolean
        get() = anchoredDraggableState.settledValue == MusicFreeDrawerValue.Closed

    /** 拖动/动画进行中的目标锚点 */
    val targetValue: MusicFreeDrawerValue
        get() = anchoredDraggableState.targetValue

    private fun requireDensity(): Density =
        requireNotNull(density) {
            "MusicFreeDrawerState must be driven by MusicFreeModalDrawer (density not injected)"
        }

    suspend fun open() {
        anchoredDraggableState.animateTo(MusicFreeDrawerValue.Open, SETTLE_SPRING)
    }

    suspend fun close() {
        anchoredDraggableState.animateTo(MusicFreeDrawerValue.Closed, CLOSE_MOTION)
    }

    companion object {
        // runtime 1.12 起 listSaver 返回 Saver<Original, Any>（save 值类型参数已抹平）
        val Saver: Saver<MusicFreeDrawerState, Any> =
            listSaver<MusicFreeDrawerState, Int>(
                save = { listOf(it.anchoredDraggableState.settledValue.ordinal) },
                restore = { state -> MusicFreeDrawerState(MusicFreeDrawerValue.entries[state.first()]) },
            )
    }
}

@Composable
fun rememberMusicFreeDrawerState(
    initialValue: MusicFreeDrawerValue = MusicFreeDrawerValue.Closed,
): MusicFreeDrawerState = rememberSaveable(saver = MusicFreeDrawerState.Saver) {
    MusicFreeDrawerState(initialValue)
}

/**
 * MusicFree 风格模态抽屉（doc/10）：替换 Material3 ModalNavigationDrawer。
 *
 * 手势模型（全部挂在内容祖先节点上——祖先级 pointer input 不占据触摸命中路径，子孙点击/滚动
 * 完全不受影响；若做成内容上方的兄弟手势层，会被触摸命中测试排他拦截导致内容全部不可点）：
 * - 关闭态：[awaitEdgeOpenDrag] 仅在左边缘 [EDGE_ZONE_WIDTH] 内起手认领横拖（认领前不消费
 *   任何事件，tap 透传、竖滑让给列表滚动），认领后 1:1 跟手，松手 settle；
 * - 打开态 / settle 动画中：[Modifier.anchoredDraggable] 接管全屏横拖（面板与遮罩皆可拖回
 *   关闭），动画中任意位置可打断再抓取；
 * - 首页其余区域横滑不触发抽屉（M3 为整屏手势域，斜滑与列表滚动竞速，见 doc/10 §2.2）。
 *
 * offset 由指针 delta 直接驱动（dispatchRawDelta），经 Modifier.offset 在 placement 阶段生效，
 * 全程零重组；settle 用无过冲弹簧。返回键关闭由调用方在抽屉打开时注册 BackHandler（doc/10 §4.5）。
 */
@Composable
fun MusicFreeModalDrawer(
    state: MusicFreeDrawerState,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    drawerContainerColor: Color = MaterialTheme.colorScheme.background,
    drawerContent: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    SideEffect { state.density = density }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val drawerWidth = minOf(DRAWER_MAX_WIDTH, maxWidth)
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val edgeZonePx = with(density) { EDGE_ZONE_WIDTH.toPx() }
        val draggable = state.anchoredDraggableState
        val drawerVisible by remember(drawerWidthPx) {
            derivedStateOf { drawerFraction(draggable.offset, drawerWidthPx) > 0f }
        }

        SideEffect {
            val anchors = draggable.anchors
            if (anchors.positionOf(MusicFreeDrawerValue.Closed) != -drawerWidthPx ||
                anchors.positionOf(MusicFreeDrawerValue.Open) != 0f
            ) {
                draggable.updateAnchors(
                    DraggableAnchors {
                        MusicFreeDrawerValue.Closed at -drawerWidthPx
                        MusicFreeDrawerValue.Open at 0f
                    },
                )
            }
        }

        // 打开态/settle 期间的拖拽关闭挂在本节点（祖先级，不遮挡内容点击）；
        // flingBehavior 显式对齐 doc/10 §4.4 常量（不传时拖动松手走 foundation 默认 50% + tween(300)）
        val drawerFlingBehavior =
            AnchoredDraggableDefaults.flingBehavior(
                state = draggable,
                positionalThreshold = { distance -> distance * OPEN_FRACTION_THRESHOLD },
                animationSpec = SETTLE_SPRING,
            )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .anchoredDraggable(
                        state = draggable,
                        orientation = Orientation.Horizontal,
                        enabled = gesturesEnabled && (state.isOpen || draggable.isAnimationRunning),
                        flingBehavior = drawerFlingBehavior,
                    )
                    .pointerInput(gesturesEnabled, edgeZonePx) {
                        if (!gesturesEnabled) return@pointerInput
                        // AwaitPointerEventScope 是受限挂起作用域（只能调用其成员/扩展挂起函数），
                        // settle 须在 scope 外执行：每个手势在 scope 内完成认领与跟手并以返回值
                        // 带出松手轨迹速度，settle 在本协程执行（期间再抓取由 anchoredDraggable
                        // 的动画中启用态负责，见 edgeOpenDragGesture 注释）
                        while (true) {
                            val settleVelocity =
                                awaitPointerEventScope {
                                    awaitEdgeOpenDrag(state = state, edgeZonePx = edgeZonePx)
                                }
                            if (settleVelocity != null) {
                                state.anchoredDraggableState.settle(settleVelocity)
                            }
                        }
                    },
        ) {
            content()

            // 遮罩：alpha 跟 offset 进度（绘制阶段读取，零重组）；完全打开后可点击关闭（对齐 M3）。
            // 同时要求 gesturesEnabled：上层覆盖层（设置页/识曲）期间禁用抽屉手势，遮罩点击一并取消，
            // 否则上层空白区域的触摸会穿透下来把抽屉关掉，返回时就回不到抽屉了（doc/16 §2.3）
            val scrimClickable = state.isOpen && gesturesEnabled
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = drawerFraction(draggable.offset, drawerWidthPx) }
                        .then(
                            if (scrimClickable) {
                                Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { scope.launch { state.close() } }
                                    .semantics { contentDescription = SCRIM_CLOSE_DESCRIPTION }
                            } else {
                                Modifier
                            },
                        ),
            )

            Surface(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .offset {
                            val offset = draggable.offset
                            IntOffset(
                                when {
                                    !offset.isNaN() -> offset.roundToInt()
                                    state.isOpen -> 0
                                    else -> -drawerWidthPx.roundToInt()
                                },
                                0,
                            )
                        }
                        .semantics {
                            if (drawerVisible) {
                                paneTitle = DRAWER_PANE_TITLE
                                if (state.isOpen) {
                                    dismiss {
                                        scope.launch { state.close() }
                                        true
                                    }
                                }
                            }
                        },
                shape =
                    RoundedCornerShape(
                        topStart = 0.dp,
                        topEnd = DRAWER_END_CORNER_RADIUS,
                        bottomEnd = DRAWER_END_CORNER_RADIUS,
                        bottomStart = 0.dp,
                    ),
                color = drawerContainerColor,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    drawerContent()
                }
            }
        }
    }
}

/** offset（Closed=-widthPx，Open=0）映射到 0..1 的打开进度；锚点未初始化时为 0。 */
private fun drawerFraction(offset: Float, widthPx: Float): Float =
    if (offset.isNaN() || widthPx <= 0f) {
        0f
    } else {
        ((offset + widthPx) / widthPx).coerceIn(0f, 1f)
    }

/**
 * 认领并跟手一个边缘起手手势（祖先级 pointerInput，事件均为 Main pass：子组件先于本节点处理，
 * 仲裁与 foundation 横向拖拽检测器一致——认领前不消费任何事件，消费时序定胜负）。
 *
 * - 仅当抽屉完全关闭、无 settle 动画且起手点在左 [edgeZonePx] 内才认领向右横拖；认领前 tap
 *   完全透传给下层点击目标，竖滑/其他横滑由子组件先消费即放弃；
 * - 认领后逐帧 dispatchRawDelta（1:1 跟手）并消费事件；
 * - 多指时跟踪首个落点指针，其抬起即结束本手势（边缘滑动为单指手势的合理简化）。
 *
 * @return 松手轨迹速度（px/s）；未认领或中途放弃返回 null（调用方据此跳过 settle）。
 */
private suspend fun AwaitPointerEventScope.awaitEdgeOpenDrag(
    state: MusicFreeDrawerState,
    edgeZonePx: Float,
): Float? {
    val draggable = state.anchoredDraggableState
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!state.isClosed || draggable.isAnimationRunning || down.position.x >= edgeZonePx) {
        return null
    }
    val touchSlop = viewConfiguration.touchSlop
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var totalX = 0f
    var claimed = false
    var settleVelocity = 0f
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!claimed) {
            if (change.isConsumed) {
                // 子组件已认领（竖滑滚动等）→ 放弃本手势，不干扰子组件
                break
            }
            if (change.changedToUpIgnoreConsumed()) {
                // tap：不消费，透传给下层点击目标
                break
            }
            totalX += change.positionChangeIgnoreConsumed().x
            if (totalX > 0f && totalX >= touchSlop) {
                // 横向先过 slop → 认领，首帧 over-slop 一并推进 offset
                claimed = true
                draggable.dispatchRawDelta(totalX - touchSlop)
                change.consume()
            }
        } else {
            if (change.changedToUpIgnoreConsumed()) {
                tracker.addPosition(change.uptimeMillis, change.position)
                settleVelocity = tracker.calculateVelocity().x
                break
            }
            if (change.isConsumed) {
                break
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            draggable.dispatchRawDelta(change.positionChangeIgnoreConsumed().x)
            change.consume()
        }
    }
    return if (claimed) settleVelocity else null
}
