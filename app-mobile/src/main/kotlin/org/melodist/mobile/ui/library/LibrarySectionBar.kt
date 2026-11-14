package org.melodist.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 收藏歌单 Tab 内的二次分栏：收藏的歌单 / 收藏的专辑（doc/13） */
enum class CollectedSection(val title: String) {
    Playlists("收藏的歌单"),
    Albums("收藏的专辑"),
}

/**
 * MusicFree 风格的胶囊式子分栏：比外层 [LibraryTabBar] 更轻的视觉层级——
 * surfaceVariant 底槽 + 主色浅底胶囊选中态，用于 Tab 内部的二次分类。
 *
 * @param selected 当前选中栏
 * @param onSelect 选中回调
 * @param albumsCount 收藏专辑数量，用于渲染「收藏的专辑 (N)」；< 0 表示不显示数量
 * @param playlistsCount 收藏歌单数量，用于渲染「收藏的歌单 (N)」；< 0 表示不显示数量
 * @param modifier 外部修饰符
 */
@Composable
fun LibrarySectionBar(
    selected: CollectedSection,
    onSelect: (CollectedSection) -> Unit,
    modifier: Modifier = Modifier,
    albumsCount: Int = -1,
    playlistsCount: Int = -1,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollectedSection.entries.forEach { section ->
            val isSelected = selected == section
            val count =
                when (section) {
                    CollectedSection.Albums -> albumsCount
                    CollectedSection.Playlists -> playlistsCount
                }
            val label =
                if (count >= 0) {
                    "${section.title} ($count)"
                } else {
                    section.title
                }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ).clickable { onSelect(section) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
