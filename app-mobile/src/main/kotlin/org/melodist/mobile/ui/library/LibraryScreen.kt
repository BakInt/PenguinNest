package org.melodist.mobile.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.deletePlaylist
import org.melodist.api.renamePlaylist
import org.melodist.api.setPlaylistPrivacy
import org.melodist.data.UserLibraryCacheManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CreatePlaylistDialog
import org.melodist.mobile.ui.library.components.FavoriteAlbumsSection
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Playlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    onRequireLogin: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val navigation = LocalAppNavigation.current
    val apiService = remember { MusicApiService() }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var selectedPlaylistForAction by remember { mutableStateOf<Playlist?>(null) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.Created) }
    // doc/13：收藏歌单 Tab 内的二次分栏（收藏的歌单 / 收藏的专辑），默认先看收藏的歌单
    var collectedSection by rememberSaveable { mutableStateOf(CollectedSection.Playlists) }
    val userProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn

    val libraryData by UserLibraryCacheManager.libraryFlow.collectAsState()
    val isLoading by UserLibraryCacheManager.isLoadingFlow.collectAsState()
    val createdPlaylists = libraryData.playlists.filter { it.isCreated }
    val favoritedPlaylists = libraryData.playlists.filter { it.isFav }

    var isRefreshing by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    val onRefresh: () -> Unit = {
        if (UserSession.isLoggedIn) {
            refreshJob?.cancel()
            isRefreshing = true
            refreshJob =
                scope.launch {
                    try {
                        UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = true)
                        UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = true)
                    } finally {
                        isRefreshing = false
                    }
                }
        }
    }

    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (isRefreshing && available.y < -10f) {
                        refreshJob?.cancel()
                        isRefreshing = false
                    }
                    return Offset.Zero
                }
            }
        }

    LaunchedEffect(userProfile.uin, isLoggedIn) {
        if (isLoggedIn) {
            UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = false)
            if (UserLibraryCacheManager.favoriteSongsFlow.value.isEmpty()) {
                UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = false)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LibraryTabBar(
            selected = selectedTab,
            onSelect = { selectedTab = it },
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
        ) {
            if (!isLoggedIn) {
                LibraryLoginPlaceholder(onRequireLogin = onRequireLogin)
            } else {
                when (selectedTab) {
                    LibraryTab.Created -> {
                        LibraryPlaylistList(
                            contentPadding = contentPadding,
                            headerKey = "library_created_header",
                            headerTitle =
                                if (createdPlaylists.isNotEmpty()) {
                                    "自建歌单 (${createdPlaylists.size})"
                                } else {
                                    "自建歌单"
                                },
                            headerTrailing = {
                                IconButton(
                                    onClick = { showCreatePlaylistDialog = true },
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "新建歌单",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            emptyText = "暂无自建歌单，点右上角 + 新建",
                            playlists = createdPlaylists,
                            onOpenPlaylist = onOpenPlaylist,
                            onPlaylistLongClick = { playlist ->
                                if (!playlist.isMyFavorite) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selectedPlaylistForAction = playlist
                                }
                            },
                            onPlaylistMoreClick = { playlist ->
                                selectedPlaylistForAction = playlist
                            },
                        )
                    }

                    LibraryTab.Collected -> {
                        // doc/13：子分栏只出现在收藏歌单 Tab 内（外层 LibraryTabBar 不变）
                        val sectionHeader: @Composable () -> Unit = {
                            LibrarySectionBar(
                                selected = collectedSection,
                                onSelect = { collectedSection = it },
                                albumsCount = libraryData.favoriteAlbums.size,
                                playlistsCount = favoritedPlaylists.size,
                            )
                        }

                        when (collectedSection) {
                            CollectedSection.Albums ->
                                LibraryPlaylistList(
                                    contentPadding = contentPadding,
                                    headerKey = "library_collected_albums_header",
                                    headerTitle =
                                        if (libraryData.favoriteAlbums.isNotEmpty()) {
                                            "收藏的专辑 (${libraryData.favoriteAlbums.size})"
                                        } else {
                                            "收藏的专辑"
                                        },
                                    headerTrailing = null,
                                    emptyText = "暂无收藏的专辑，在专辑页点击收藏后会展示在这里",
                                    playlists = emptyList(),
                                    onOpenPlaylist = onOpenPlaylist,
                                    onPlaylistLongClick = {},
                                    onPlaylistMoreClick = {},
                                    leadingContent = sectionHeader,
                                    trailingContent =
                                        if (libraryData.favoriteAlbums.isNotEmpty()) {
                                            {
                                                item(key = "library_favorite_albums_section") {
                                                    FavoriteAlbumsSection(
                                                        albums = libraryData.favoriteAlbums,
                                                        onViewAll = {
                                                            navigation.navigateToFavoriteAlbums()
                                                        },
                                                        onOpenAlbum = {
                                                            navigation.navigateToAlbum(it.mid, it.name)
                                                        },
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                )

                            CollectedSection.Playlists ->
                                LibraryPlaylistList(
                                    contentPadding = contentPadding,
                                    headerKey = "library_collected_header",
                                    headerTitle =
                                        if (favoritedPlaylists.isNotEmpty()) {
                                            "收藏的歌单 (${favoritedPlaylists.size})"
                                        } else {
                                            "收藏的歌单"
                                        },
                                    headerTrailing = null,
                                    emptyText =
                                        "暂无收藏歌单，在歌单详情页收藏别人的歌单后会展示在这里",
                                    playlists = favoritedPlaylists,
                                    onOpenPlaylist = onOpenPlaylist,
                                    onPlaylistLongClick = { playlist ->
                                        if (!playlist.isMyFavorite) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedPlaylistForAction = playlist
                                        }
                                    },
                                    onPlaylistMoreClick = { playlist ->
                                        selectedPlaylistForAction = playlist
                                    },
                                    leadingContent = sectionHeader,
                                )
                        }
                    }
                }
            }
        }
    }

    // 歌单长按操作弹窗
    if (selectedPlaylistForAction != null) {
        val playlist = selectedPlaylistForAction!!
        ModalBottomSheet(
            onDismissRequest = { selectedPlaylistForAction = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtImage(
                        coverUrl = playlist.thumbnailPicUrl,
                        candidates = playlist.thumbnailCandidates,
                        contentDescription = playlist.name,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text =
                                if (playlist.isPrivate) {
                                    "私密歌单 · ${playlist.songCount} 首"
                                } else if (playlist.isCreated) {
                                    "自建歌单 · ${playlist.songCount} 首"
                                } else {
                                    "收藏歌单 · ${playlist.songCount} 首"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                val isShareable = playlist.tid > 0L && !playlist.isPrivate
                val copyTextColor =
                    if (isShareable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isShareable) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val url = "https://i.y.qq.com/n2/m/share/details/taoge.html?id=${playlist.tid}"
                                val text = "${playlist.name}\n$url"
                                val clip = ClipData.newPlainText("playlist", text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                selectedPlaylistForAction = null
                            }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制链接",
                        tint = copyTextColor,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "复制链接",
                            style = MaterialTheme.typography.bodyLarge,
                            color = copyTextColor,
                        )
                        if (!isShareable) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (playlist.isPrivate) "私密歌单无法获取分享链接" else "私人歌单无法获取分享链接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }

                if (playlist.isCreated && !playlist.isMyFavorite) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val target = playlist
                                    selectedPlaylistForAction = null
                                    playlistToRename = target
                                }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "重命名歌单",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "重命名歌单",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    val isPrivate = playlist.isPrivate
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val target = playlist
                                    val targetNewPrivate = !isPrivate
                                    selectedPlaylistForAction = null
                                    scope.launch {
                                        val (success, msg) = apiService.setPlaylistPrivacy(target, targetNewPrivate)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        if (success) {
                                            UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = true)
                                        }
                                    }
                                }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isPrivate) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                            contentDescription = if (isPrivate) "公开歌单" else "私密此歌单",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (isPrivate) "公开歌单" else "私密此歌单",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val target = playlist
                                selectedPlaylistForAction = null
                                playlistToDelete = target
                            }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = if (playlist.isCreated) "删除歌单" else "取消收藏歌单",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (playlistToDelete != null) {
        val target = playlistToDelete!!
        val isCreated = target.isCreated
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = if (isCreated) "删除歌单" else "取消收藏歌单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "确定要${if (isCreated) "删除自建歌单" else "取消收藏歌单"}《${target.name}》吗？${if (isCreated) "删除后不可恢复。" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDel = target
                        playlistToDelete = null
                        scope.launch {
                            val success = apiService.deletePlaylist(toDel)
                            if (success) {
                                Toast.makeText(context, if (isCreated) "已删除歌单" else "已取消收藏", Toast.LENGTH_SHORT).show()
                                UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = true)
                            } else {
                                Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text(
                        text = if (isCreated) "删除" else "取消收藏",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (playlistToRename != null) {
        val target = playlistToRename!!
        var newName by remember(target) { mutableStateOf(target.name) }
        var isRenaming by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isRenaming) playlistToRename = null
            },
            title = {
                Text(
                    text = "重命名歌单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("歌单名称") },
                        placeholder = { Text("请输入歌单名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRenaming,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != target.name && !isRenaming,
                    onClick = {
                        val trimmed = newName.trim()
                        isRenaming = true
                        scope.launch {
                            val (success, msg) = apiService.renamePlaylist(target, trimmed)
                            isRenaming = false
                            if (success) {
                                Toast.makeText(context, "歌单重命名成功", Toast.LENGTH_SHORT).show()
                                playlistToRename = null
                                UserLibraryCacheManager.loadLibrary(apiService, forceRefresh = true)
                            } else {
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRenaming,
                    onClick = { playlistToRename = null },
                ) {
                    Text("取消")
                }
            },
        )
    }

    // 新建歌单弹窗
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
        )
    }
}

/**
 * 通用歌单列表（自建/收藏两 Tab 共用）：可选的顶部子分栏 + 小节头 + 空占位 + 歌单行，
 * 列表尾可插入自定义条目（收藏 Tab 的专辑网格小节）。
 */
@Composable
private fun LibraryPlaylistList(
    contentPadding: PaddingValues,
    headerKey: String,
    headerTitle: String,
    headerTrailing: (@Composable () -> Unit)?,
    emptyText: String,
    playlists: List<Playlist>,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlaylistLongClick: (Playlist) -> Unit,
    onPlaylistMoreClick: (Playlist) -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (LazyListScope.() -> Unit)? = null,
) {
    val hasEmptyPlaceholder = playlists.isEmpty() && emptyText.isNotBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = 2.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
    ) {
        if (leadingContent != null) {
            item(key = "${headerKey}_section_bar") {
                leadingContent()
            }
        }

        item(key = headerKey) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = if (headerTrailing != null) 8.dp else 16.dp,
                            top = 16.dp,
                            bottom = 4.dp,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                headerTrailing?.invoke()
            }
        }

        if (hasEmptyPlaceholder) {
            item(key = "${headerKey}_empty") {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
        }

        items(playlists, key = { it.dirId.toString() + "_" + it.tid }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                onClick = { onOpenPlaylist(playlist) },
                onLongClick = { onPlaylistLongClick(playlist) },
                onMoreClick = { onPlaylistMoreClick(playlist) },
            )
        }

        trailingContent?.invoke(this)
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtImage(
            coverUrl = playlist.thumbnailPicUrl,
            candidates = playlist.thumbnailCandidates,
            contentDescription = playlist.name,
            shape = RoundedCornerShape(8.dp),
            elevation = 2.dp,
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
            placeholderIconSize = 24.dp,
            modifier = Modifier.size(48.dp),
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

        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryLoginPlaceholder(onRequireLogin: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "登录后查看你的自建歌单与收藏",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onRequireLogin) {
            Text("去登录")
        }
    }
}
