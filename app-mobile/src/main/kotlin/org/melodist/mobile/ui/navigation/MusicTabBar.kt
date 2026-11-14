package org.melodist.mobile.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.melodist.data.StartupTab

/** 底部标签：首页 / 我的收藏 */
enum class HomeTab(
    val title: String,
    val icon: ImageVector,
) {
    Home("首页", Icons.Rounded.Home),
    Library("我的收藏", Icons.Rounded.LibraryMusic),
}

/** 设置项 StartupTab → 底部标签 */
fun StartupTab.toHomeTab(): HomeTab =
    when (this) {
        StartupTab.HOME -> HomeTab.Home
        StartupTab.LIBRARY -> HomeTab.Library
    }

/**
 * 底栏内容高度（不含系统导航条内边距），越小底栏越矮。
 *
 * Material3 的 `NavigationBar` 用 `NavigationBarTokens.TallContainerHeight`（**80dp**）作为内容最小高度，
 * **不提供高度参数**，手机上偏高、遮挡内容。这里对齐其自身的紧凑规格
 * `NavigationBarTokens.ContainerHeight`（64dp）来收窄，只改这一个常量即可继续微调。
 */
private val TabBarContentHeight = 64.dp

/**
 * MusicFree 扁平风格底部标签栏：无阴影、主色高亮、浅色指示器。
 *
 * 高度收窄的做法：给 [NavigationBar] 传 `WindowInsets(0,0,0,0)` 关掉它内部对系统栏内边距的消费，
 * 改由外层 `Column` 统一补底部内边距，这样高度上限只压缩**内容区**，
 * 不会把导航条内边距一起压掉（[org.melodist.mobile.ui.player.PlayerContainer] 约定有底栏时由其处理导航条内边距）。
 */
@Composable
fun MusicTabBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                // 横屏时避开侧边系统栏 / 挖孔，保持与 Material3 底栏默认的横向内边距行为一致
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
    ) {
        NavigationBar(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TabBarContentHeight),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            HomeTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    icon = {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                        )
                    },
                    label =
                        if (showLabels) {
                            { Text(tab.title) }
                        } else {
                            null
                        },
                    colors =
                        NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
        }

        // 系统导航条内边距（有底栏时由本组件承担，见 PlayerContainer 注释）
        Spacer(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars),
        )
    }
}
