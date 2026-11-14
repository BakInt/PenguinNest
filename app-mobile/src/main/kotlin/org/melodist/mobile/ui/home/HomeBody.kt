package org.melodist.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.library.components.FavoriteAlbumsCard
import org.melodist.mobile.ui.library.components.FavoriteMusicCard
import org.melodist.mobile.ui.navigation.HomeFilter
import org.melodist.model.Playlist
import org.melodist.playback.PlaybackManager

/**
 * MusicFree 风格首页内容：快捷入口行（Operations）+ 特色卡片 + 我的歌单封面网格（Sheets）。
 * 原首页 Pager 各功能页改由 [HomeFilter] 功能页承载，从快捷入口与抽屉进入。
 */
@Composable
fun HomeBody(
    contentPadding: PaddingValues,
    onNavigateFeature: (HomeFilter) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onRequireLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLoggedIn = UserSession.isLoggedIn
    val userProfile by UserSession.profileFlow.collectAsState()
    val favoriteMids by PlaybackManager.favoriteSongMids.collectAsState()
    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val playlists = libraryData.playlists
    val favoriteCount = libraryData.favoriteCount

    val apiService = remember { MusicApiService() }
    LaunchedEffect(userProfile.uin, isLoggedIn) {
        if (isLoggedIn) {
            UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = false)
            if (UserLibraryCacheManager.favoriteSongsFlow.value.isEmpty()) {
                UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = false)
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
    ) {
        // MusicFree Operations：四个快捷入口
        item(key = "home_operations") {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OperationEntry(
                    icon = Icons.Rounded.Whatshot,
                    label = "每日推荐",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateFeature(HomeFilter.Discover) },
                )
                OperationEntry(
                    icon = Icons.Rounded.MusicNote,
                    label = "本地音乐",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateFeature(HomeFilter.Local) },
                )
                OperationEntry(
                    icon = Icons.Rounded.History,
                    label = "最近播放",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateFeature(HomeFilter.Recent) },
                )
                OperationEntry(
                    icon = Icons.Rounded.Insights,
                    label = "听歌足迹",
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigateFeature(HomeFilter.Stats) },
                )
            }
        }

        // 特色卡片（我喜欢的音乐 / 收藏专辑），与收藏页同源
        item(key = "home_hero_cards") {
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val cardWidth = (screenWidth - 44.dp).coerceIn(280.dp, 360.dp)

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "home_favorite_music_card") {
                    val effectiveFavCount = if (favoriteCount > 0) favoriteCount else favoriteMids.size
                    FavoriteMusicCard(
                        totalCount = effectiveFavCount,
                        onCardClick = {
                            val favPlaylist =
                                Playlist(
                                    dirId = 201L,
                                    name = "我喜欢的音乐",
                                    songCount = effectiveFavCount,
                                    isFav = true,
                                )
                            onOpenPlaylist(favPlaylist)
                        },
                        modifier = Modifier.width(cardWidth),
                    )
                }

                item(key = "home_favorite_albums_card") {
                    FavoriteAlbumsCard(
                        onCardClick = { onNavigateFeature(HomeFilter.Library) },
                        modifier = Modifier.width(cardWidth),
                    )
                }
            }
        }

        // 我的歌单（MusicFree Sheets）
        item(key = "home_sheets_header") {
            Text(
                text =
                    if (playlists.isNotEmpty()) {
                        "我的歌单 (${playlists.size})"
                    } else {
                        "我的歌单"
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            )
        }

        if (isLoggedIn) {
            val rows = playlists.chunked(GRID_COLUMNS)
            items(rows.size, key = { "home_sheet_row_$it" }) { rowIndex ->
                val row = rows[rowIndex]
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (columnIndex in 0 until GRID_COLUMNS) {
                        val playlist = row.getOrNull(columnIndex)
                        if (playlist == null) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            PlaylistGridCard(
                                playlist = playlist,
                                onClick = { onOpenPlaylist(playlist) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        } else {
            item(key = "home_sheets_login") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "登录后查看我的歌单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRequireLogin) {
                            Text("登录")
                        }
                    }
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 3

@Composable
private fun OperationEntry(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlaylistGridCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(4.dp),
    ) {
        AlbumArtImage(
            coverUrl = playlist.thumbnailPicUrl,
            candidates = playlist.thumbnailCandidates,
            contentDescription = playlist.name,
            shape = RoundedCornerShape(12.dp),
            placeholderIconSize = 28.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.songCount} 首",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
