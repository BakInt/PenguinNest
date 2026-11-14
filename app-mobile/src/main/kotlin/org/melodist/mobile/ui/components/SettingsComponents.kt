package org.melodist.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.model.AudioQualityTier

@Composable
fun SettingsGroupTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}

/**
 * 记忆一个设置分栏的折叠状态（doc/15）。
 *
 * 状态刻意留在各分栏组件内部而非提升到设置页：各分栏本就是自包含组件，
 * 且 `SettingsPlaybackSection` 一个文件内含两个独立分栏，提升会同时污染
 * 设置页与各 section 的参数列表。`rememberSaveable` 保证旋转屏幕 /
 * 进程内重建后不丢失；退出设置页重新进入时重置为默认态（瞬时 UI 状态，不做持久化）。
 *
 * @param key 分栏的稳定标识，同一页面内各分栏必须互不相同
 * @param default 首次进入时的展开状态，默认为收起（设置项按需展开，页面更简洁易找）
 * @return 可读写的折叠状态
 */
@Composable
fun rememberSettingsSectionExpandedState(
    key: String,
    default: Boolean = false,
): MutableState<Boolean> = rememberSaveable(key) { mutableStateOf(default) }

/**
 * 可折叠的设置分栏容器（doc/15）。
 *
 * 由「整行可点的分组标题 + 右侧状态箭头」和「可展开收起的内容区」组成，
 * 用于替代原先「[SettingsGroupTitle] + [SettingsGroupCard] 直接堆叠」的固定写法。
 * 收起时内容区不参与组合，`LazyColumn` 的条目高度自然收缩、不残留空白。
 *
 * 展开 / 收起动画规格与 [LandscapeCollapsibleTopBar] 完全一致，保证全项目折叠手感统一。
 *
 * @param title 分栏标题
 * @param expanded 当前是否展开
 * @param onExpandedChange 折叠状态变更回调
 * @param modifier 外部修饰符
 * @param content 分栏内容（通常为一个 [SettingsGroupCard]）
 */
@Composable
fun SettingsCollapsibleGroup(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 分组头：整行可点，右侧箭头旋转指示展开状态
        val arrowRotation by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            label = "settingsSectionArrow",
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier =
                    Modifier
                        .size(20.dp)
                        .rotate(arrowRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter =
                expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                ) + fadeIn(animationSpec = tween(180)),
            exit =
                shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(150)),
        ) {
            content()
        }
    }
}

@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
fun SettingsClickableRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

fun getQualityTierDetailedLabel(tier: AudioQualityTier): String =
    when (tier) {
        AudioQualityTier.Master -> "母带处理 (Master)"
        AudioQualityTier.Dolby -> "杜比全景声 (Dolby Atmos)"
        AudioQualityTier.DtsX -> "DTS:X 环绕声 (DTS:X)"
        AudioQualityTier.Atmos -> "臻品全景声 (5.1 环绕声)"
        AudioQualityTier.Premium -> "臻品空间音效 (Premium)"
        AudioQualityTier.HiRes -> "高解析 Hi-Res (24bit/96kHz)"
        AudioQualityTier.SQ -> "无损音质 SQ (FLAC)"
        AudioQualityTier.HQ -> "高质量 HQ (320kbps)"
        AudioQualityTier.Standard -> "标准音质 (128kbps)"
    }
