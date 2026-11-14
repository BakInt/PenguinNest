package org.melodist.mobile.ui.components

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.AddSongResult
import org.melodist.api.MusicApiService
import org.melodist.api.addSongsToPlaylist
import org.melodist.api.deleteSongsFromPlaylist
import org.melodist.data.UserLibraryCacheManager
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

/**
 * 通用添加到自建歌单选择器弹窗。
 * 支持传入单曲或多歌曲列表，统一负责选择自建歌单、批量提交网络、
 * 乐观增量更新用户收藏数据、喜欢状态同步以及反馈提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    songs: List<Song>,
    onDismissRequest: () -> Unit,
    onSuccess: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }

    val userLibraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val createdPlaylists =
        remember(userLibraryData) {
            userLibraryData.playlists.filter { it.isCreated || it.isMyFavorite }
        }

    val onlineSongs =
        remember(songs) {
            songs.filter { it.songMid.isNotBlank() && !it.isLocal }
        }

    // 单曲场景才启用"点击=切换归属"语义；多选维持仅添加
    val toggleSong = remember(onlineSongs) { onlineSongs.singleOrNull() }

    // 歌单曲目缓存写入版本号：新增/移除/预热完成后自增，驱动归属标记重组
    val membershipVersion by UserLibraryCacheManager.membershipVersionFlow.collectAsState()

    var isOperating by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    // 打开弹窗后预热各歌单曲目缓存，使归属标记尽快从"未知"变为确定
    LaunchedEffect(createdPlaylists) {
        val pending =
            createdPlaylists.filter { playlist ->
                !UserLibraryCacheManager.isPlaylistSongsCacheValid(playlist.dirId, playlist.tid)
            }
        if (pending.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            pending.forEach { playlist ->
                UserLibraryCacheManager.loadPlaylistSongs(
                    apiService = apiService,
                    dirId = playlist.dirId,
                    tid = playlist.tid,
                    isFav = playlist.isFav,
                    forceRefresh = true,
                    targetTotalCount = playlist.songCount,
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isOperating) onDismissRequest()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "选择要添加到的歌单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(vertical = 12.dp),
                )
                IconButton(
                    onClick = { showCreatePlaylistDialog = true },
                    enabled = !isOperating,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "新建并添加",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            if (createdPlaylists.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无自建歌单，请先在我的收藏页面创建歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                ) {
                    items(createdPlaylists, key = { it.dirId }) { playlist ->
                        // 归属状态：null = 未知（缓存缺失/过期），true/false = 确定
                        val isMember =
                            remember(playlist.dirId, playlist.tid, membershipVersion) {
                                toggleSong?.let { song ->
                                    UserLibraryCacheManager
                                        .getPlaylistSongMids(playlist.dirId, playlist.tid)
                                        ?.contains(song.songMid)
                                }
                            }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isOperating) {
                                        if (onlineSongs.isEmpty()) {
                                            Toast.makeText(context, "所选歌曲中没有可添加的在线音乐", Toast.LENGTH_SHORT).show()
                                            return@clickable
                                        }
                                        isOperating = true
                                        scope.launch {
                                            val singleSong = toggleSong
                                            if (singleSong != null && isMember == true) {
                                                // 已在此歌单：点击即移除（取消收藏到该歌单）
                                                val removed =
                                                    apiService.deleteSongsFromPlaylist(
                                                        dirId = playlist.dirId,
                                                        songs = listOf(singleSong),
                                                    )
                                                isOperating = false
                                                if (removed) {
                                                    UserLibraryCacheManager.onSongsRemovedFromPlaylist(
                                                        playlist.dirId,
                                                        listOf(singleSong),
                                                    )
                                                    Toast
                                                        .makeText(context, "已从《${playlist.name}》移除", Toast.LENGTH_SHORT)
                                                        .show()
                                                    onDismissRequest()
                                                    onSuccess?.invoke()
                                                } else {
                                                    Toast.makeText(context, "从歌单移除失败", Toast.LENGTH_SHORT).show()
                                                }
                                                return@launch
                                            }

                                            val result =
                                                apiService.addSongsToPlaylist(
                                                    dirId = playlist.dirId,
                                                    songs = onlineSongs,
                                                )
                                            isOperating = false
                                            when (result) {
                                                AddSongResult.Success -> {
                                                    val msg =
                                                        if (onlineSongs.size == 1) {
                                                            "已添加到《${playlist.name}》"
                                                        } else {
                                                            "已将 ${onlineSongs.size} 首歌曲添加到《${playlist.name}》"
                                                        }
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    UserLibraryCacheManager.onSongsAddedToPlaylist(playlist.dirId, onlineSongs)
                                                    if (playlist.dirId == 201L) {
                                                        PlaybackManager.addFavoriteSongMids(onlineSongs.map { it.songMid })
                                                    }
                                                    onDismissRequest()
                                                    onSuccess?.invoke()
                                                }

                                                AddSongResult.AlreadyExists -> {
                                                    // 服务端判定已存在。仅当归属明确为"不在该歌单"（本地判断失误）
                                                    // 且为单曲场景时，才继续按"点击=切换"语义执行移除；
                                                    // 归属未知时不做删除，避免在无视觉提示下移除用户数据。
                                                    val single = toggleSong
                                                    if (single != null && isMember == false) {
                                                        val removed =
                                                            apiService.deleteSongsFromPlaylist(
                                                                dirId = playlist.dirId,
                                                                songs = listOf(single),
                                                            )
                                                        if (removed) {
                                                            UserLibraryCacheManager.onSongsRemovedFromPlaylist(
                                                                playlist.dirId,
                                                                listOf(single),
                                                            )
                                                            Toast
                                                                .makeText(context, "已从《${playlist.name}》移除", Toast.LENGTH_SHORT)
                                                                .show()
                                                            onDismissRequest()
                                                            onSuccess?.invoke()
                                                        } else {
                                                            Toast.makeText(context, "从歌单移除失败", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        val msg =
                                                            if (onlineSongs.size == 1) {
                                                                "该歌曲已在此歌单中"
                                                            } else {
                                                                "所选歌曲已在此歌单中"
                                                            }
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    }
                                                }

                                                AddSongResult.Failed -> {
                                                    Toast.makeText(context, "添加到歌单失败", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArtImage(
                                coverUrl = playlist.thumbnailPicUrl,
                                contentDescription = playlist.name,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(44.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${playlist.songCount} 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isMember == true) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "已在该歌单中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                isOperating = false
            },
            title = "新建并添加",
            confirmButtonText = "创建并添加",
            onCreated = { dirId, name ->
                if (onlineSongs.isNotEmpty() && dirId > 0L) {
                    val addResult =
                        apiService.addSongsToPlaylist(
                            dirId = dirId,
                            songs = onlineSongs,
                        )
                    when (addResult) {
                        AddSongResult.Success -> {
                            val toastMsg =
                                if (onlineSongs.size == 1) {
                                    "已创建并添加到《$name》"
                                } else {
                                    "已创建《$name》并添加 ${onlineSongs.size} 首歌曲"
                                }
                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                            UserLibraryCacheManager.onSongsAddedToPlaylist(dirId, onlineSongs)
                            onDismissRequest()
                            onSuccess?.invoke()
                            true
                        }

                        AddSongResult.AlreadyExists -> {
                            Toast.makeText(context, "歌单中已存在所选歌曲", Toast.LENGTH_SHORT).show()
                            onDismissRequest()
                            onSuccess?.invoke()
                            true
                        }

                        AddSongResult.Failed -> {
                            Toast.makeText(context, "歌单创建成功，但添加歌曲失败", Toast.LENGTH_SHORT).show()
                            false
                        }
                    }
                } else {
                    Toast.makeText(context, "歌单创建成功", Toast.LENGTH_SHORT).show()
                    true
                }
            },
        )
    }
}
