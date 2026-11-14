package org.melodist.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 顶部标签：自建歌单 / 收藏歌单（按协议 isFav 分组，对应 GetPlaylistByUin 与 GetPlaylistFavInfo 两个端点） */
enum class LibraryTab(val title: String) {
    Created("自建歌单"),
    Collected("收藏歌单"),
}

/** MusicFree 扁平风格顶部标签栏：surface 底、主色高亮、短圆角指示条，与底部 MusicTabBar 同一视觉语言 */
@Composable
fun LibraryTabBar(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
        divider = {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp,
            )
        },
        indicator = { tabPositions ->
            if (selected.ordinal < tabPositions.size) {
                Box(
                    modifier =
                        Modifier
                            .tabIndicatorOffset(tabPositions[selected.ordinal])
                            .height(3.dp)
                            .padding(horizontal = 36.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                )
            }
        },
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = selected == tab
            Tab(
                selected = isSelected,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                },
            )
        }
    }
}
