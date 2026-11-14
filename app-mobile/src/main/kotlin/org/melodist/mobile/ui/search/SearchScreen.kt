package org.melodist.mobile.ui.search

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.LYRIC_SEARCH_MIN_KEYWORD_LENGTH
import org.melodist.api.MusicApiService
import org.melodist.api.search
import org.melodist.api.searchAlbums
import org.melodist.api.searchByLyric
import org.melodist.api.searchPlaylists
import org.melodist.api.streamSongNameSuggestions
import org.melodist.data.SearchKeywordHistoryManager
import org.melodist.data.SongFavCountManager
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.buildHighlightedAnnotatedString
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Album
import org.melodist.model.LyricSearchResult
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.model.SongNameHighlightParser
import org.melodist.model.SongNameSuggestion
import org.melodist.playback.PlaybackManager
import org.melodist.playback.QueuePaginationSource

enum class SearchTab(
    val title: String,
) {
    Song("歌曲"),
    Playlist("歌单"),
    Album("专辑"),
}

/** 联想命中前缀高亮色（对齐官方红色高亮） */
private val SuggestionHighlightColor = Color(0xFFEC4433)

/**
 * 联想行歌名文本：优先按服务端 hilight 标记渲染（包含式），
 * 无标记时回退为本地包含式高亮（A 路 smartbox 条目无 hilight 字段）
 */
private fun buildSuggestionNameText(
    item: SongNameSuggestion,
    keyword: String,
    defaultColor: Color,
): AnnotatedString {
    if (item.highlight.isNotBlank()) {
        val (text, ranges) = SongNameHighlightParser.parse(item.highlight)
        return buildAnnotatedString {
            var cursor = 0
            for (range in ranges) {
                if (range.first > cursor) {
                    append(text.substring(cursor, range.first))
                }
                withStyle(
                    SpanStyle(
                        color = SuggestionHighlightColor,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(text.substring(range.first, range.last + 1))
                }
                cursor = range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }
    return buildHighlightedAnnotatedString(
        text = item.name,
        query = keyword,
        defaultColor = defaultColor,
        highlightColor = SuggestionHighlightColor,
    )
}

class SearchScreenState {
    var query by mutableStateOf("")
    var currentTab by mutableStateOf(SearchTab.Song)
    var historyKeywords by mutableStateOf(SearchKeywordHistoryManager.getKeywords())
    var suggestions by mutableStateOf<List<SongNameSuggestion>>(emptyList())
    var lyricSnippets by mutableStateOf<Map<String, String>>(emptyMap())

    val songListState =
        androidx.compose.foundation.lazy
            .LazyListState()
    val playlistListState =
        androidx.compose.foundation.lazy
            .LazyListState()
    val albumListState =
        androidx.compose.foundation.lazy
            .LazyListState()

    var songResults by mutableStateOf<List<Song>>(emptyList())
    var playlistResults by mutableStateOf<List<Playlist>>(emptyList())
    var albumResults by mutableStateOf<List<Album>>(emptyList())

    /** 搜索结果歌曲收藏数（key = songId），接口不可用时保持为空 */
    var favoriteCounts by mutableStateOf<Map<Long, Int>>(emptyMap())

    var songPage by mutableIntStateOf(1)
    var playlistPage by mutableIntStateOf(1)
    var albumPage by mutableIntStateOf(1)

    var hasMoreSongs by mutableStateOf(true)
    var hasMorePlaylists by mutableStateOf(true)
    var hasMoreAlbums by mutableStateOf(true)

    var isLoadingMoreSongs by mutableStateOf(false)
    var isLoadingMorePlaylists by mutableStateOf(false)
    var isLoadingMoreAlbums by mutableStateOf(false)

    var activeQuery by mutableStateOf("")
    var loadedTabs by mutableStateOf<Set<SearchTab>>(emptySet())
    var isLoading by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)

    fun refreshHistoryKeywords() {
        historyKeywords = SearchKeywordHistoryManager.getKeywords()
    }

    fun clearSearch() {
        query = ""
        hasSearched = false
        activeQuery = ""
        suggestions = emptyList()
        lyricSnippets = emptyMap()
        songResults = emptyList()
        playlistResults = emptyList()
        albumResults = emptyList()
        favoriteCounts = emptyMap()
        loadedTabs = emptySet()
        songPage = 1
        playlistPage = 1
        albumPage = 1
        hasMoreSongs = true
        hasMorePlaylists = true
        hasMoreAlbums = true
        isLoadingMoreSongs = false
        isLoadingMorePlaylists = false
        isLoadingMoreAlbums = false
    }

    fun reset() {
        clearSearch()
        currentTab = SearchTab.Song
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
    searchState: SearchScreenState = remember { SearchScreenState() },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val apiService = remember { MusicApiService() }
    val navController = LocalAppNavigation.current

    LaunchedEffect(Unit) {
        if (!searchState.hasSearched && searchState.query.isBlank()) {
            kotlinx.coroutines.delay(120)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("SearchTab", "Operation failed", e)
            }
        }
    }

    var query by searchState::query
    var currentTab by searchState::currentTab
    var historyKeywords by searchState::historyKeywords
    var suggestions by searchState::suggestions
    var lyricSnippets by searchState::lyricSnippets

    val songListState = searchState.songListState
    val playlistListState = searchState.playlistListState
    val albumListState = searchState.albumListState

    var songResults by searchState::songResults
    var playlistResults by searchState::playlistResults
    var albumResults by searchState::albumResults
    var favoriteCounts by searchState::favoriteCounts

    var songPage by searchState::songPage
    var playlistPage by searchState::playlistPage
    var albumPage by searchState::albumPage

    var hasMoreSongs by searchState::hasMoreSongs
    var hasMorePlaylists by searchState::hasMorePlaylists
    var hasMoreAlbums by searchState::hasMoreAlbums

    var isLoadingMoreSongs by searchState::isLoadingMoreSongs
    var isLoadingMorePlaylists by searchState::isLoadingMorePlaylists
    var isLoadingMoreAlbums by searchState::isLoadingMoreAlbums

    var activeQuery by searchState::activeQuery
    var loadedTabs by searchState::loadedTabs
    var isLoading by searchState::isLoading
    var hasSearched by searchState::hasSearched

    // 歌词命中合并：追加在歌名结果之后（按 songMid 去重），命中片段供徽标与副标题展示
    fun mergeLyricHits(
        nameResults: List<Song>,
        lyricHits: List<LyricSearchResult>,
    ) {
        val existingMids = nameResults.map { it.songMid }.toSet()
        val extra =
            lyricHits
                .filter { it.song.songMid.isNotBlank() && it.song.songMid !in existingMids }
                .distinctBy { it.song.songMid }
        lyricSnippets =
            extra
                .mapNotNull { hit ->
                    hit.snippet.takeIf { it.isNotBlank() }?.let { snippet -> hit.song.songMid to snippet }
                }.toMap()
        if (extra.isNotEmpty()) {
            songResults = nameResults + extra.map { it.song }
        }
    }

    // 搜索结果落地后批量拉取收藏数（10 分钟缓存，接口失败时静默保持未知）
    fun refreshSongFavoriteCounts(targets: List<Song>) {
        val ids = targets.map { it.songId }.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return
        scope.launch {
            val counts = withContext(Dispatchers.IO) { SongFavCountManager.getCounts(ids) }
            if (counts.isEmpty()) return@launch
            val visibleIds = songResults.map { it.songId }.toSet()
            val merged = counts.filterKeys { it in visibleIds }
            if (merged.isNotEmpty()) {
                favoriteCounts = favoriteCounts + merged
            }
        }
    }

    // 点击行首爱心：切换收藏状态，并本地修正计数，避免整批重新拉取
    fun handleFavoriteClick(song: Song) {
        val supported = PlaybackManager.isSongFavoriteSupported(song)
        val wasFavorite = supported && PlaybackManager.isSongFavorite(song.songMid)
        PlaybackManager.toggleSongFavorite(song)
        if (!supported) return
        val delta = if (wasFavorite) -1 else 1
        SongFavCountManager.adjust(song.songId, delta)
        val current = favoriteCounts[song.songId] ?: return
        favoriteCounts = favoriteCounts + (song.songId to (current + delta).coerceAtLeast(0))
    }

    fun executeSearch(
        targetKeyword: String,
        targetTab: SearchTab,
    ) {
        val trimmed = targetKeyword.trim()
        if (trimmed.isBlank()) return

        focusManager.clearFocus()
        SearchKeywordHistoryManager.recordKeyword(trimmed)
        historyKeywords = SearchKeywordHistoryManager.getKeywords()

        val isNewQuery = trimmed != activeQuery
        if (isNewQuery) {
            activeQuery = trimmed
            songResults = emptyList()
            lyricSnippets = emptyMap()
            playlistResults = emptyList()
            albumResults = emptyList()
            favoriteCounts = emptyMap()
            loadedTabs = emptySet()
            hasSearched = true

            songPage = 1
            playlistPage = 1
            albumPage = 1
            hasMoreSongs = true
            hasMorePlaylists = true
            hasMoreAlbums = true
        }

        currentTab = targetTab
        if (targetTab in loadedTabs) return

        scope.launch {
            isLoading = true
            hasSearched = true
            withContext(Dispatchers.IO) {
                try {
                    when (targetTab) {
                        SearchTab.Song -> {
                            songPage = 1
                            // 歌词搜索与歌名搜索并行（短词模糊匹配噪音大，长度达标才触发）
                            val lyricDeferred =
                                if (trimmed.length >= LYRIC_SEARCH_MIN_KEYWORD_LENGTH) {
                                    scope.async {
                                        try {
                                            apiService.searchByLyric(trimmed)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w("SearchScreen", "Lyric search failed for query: $trimmed", e)
                                            emptyList()
                                        }
                                    }
                                } else {
                                    null
                                }
                            val list = apiService.search(trimmed, page = 1, pageSize = 30)
                            songResults = list
                            hasMoreSongs = list.size >= 30
                            val lyricHits = lyricDeferred?.await() ?: emptyList()
                            mergeLyricHits(list, lyricHits)
                            refreshSongFavoriteCounts(songResults)
                        }

                        SearchTab.Playlist -> {
                            playlistPage = 1
                            val list = apiService.searchPlaylists(trimmed, page = 1, pageSize = 30)
                            playlistResults = list
                            hasMorePlaylists = list.size >= 30
                        }

                        SearchTab.Album -> {
                            albumPage = 1
                            val list = apiService.searchAlbums(trimmed, page = 1, pageSize = 30)
                            albumResults = list
                            hasMoreAlbums = list.size >= 30
                        }
                    }
                    loadedTabs = loadedTabs + targetTab
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SearchScreen", "Search failed for query: $trimmed, tab: $targetTab", e)
                }
            }
            isLoading = false
        }
    }

    // 输入联想：防抖 250ms，A 路 smartbox + B 路包含式搜索增量合并渲染，新输入到来时自动取消旧流
    LaunchedEffect(Unit) {
        snapshotFlow { query.trim() }
            .distinctUntilChanged()
            .collectLatest { trimmed ->
                if (trimmed.isBlank() || (hasSearched && trimmed == activeQuery)) {
                    suggestions = emptyList()
                } else {
                    delay(250)
                    apiService
                        .streamSongNameSuggestions(trimmed)
                        .collect { merged -> suggestions = merged }
                }
            }
    }

    // 点击联想词：填入完整歌名并执行搜索，展示所有相关歌曲
    fun applySuggestion(item: SongNameSuggestion) {
        query = item.name
        executeSearch(item.name, SearchTab.Song)
    }

    val searchQueuePaginationSource =
        remember(activeQuery) {
            if (activeQuery.isNotBlank()) {
                object : QueuePaginationSource {
                    override val hasMore: Boolean get() = hasMoreSongs
                    override val isLoadingMore: Boolean get() = isLoadingMoreSongs

                    override suspend fun loadMore(): List<Song> {
                        if (isLoadingMoreSongs || !hasMoreSongs || isLoading || activeQuery.isBlank()) return emptyList()
                        isLoadingMoreSongs = true
                        val nextPage = songPage + 1
                        val nextList =
                            withContext(Dispatchers.IO) {
                                try {
                                    apiService.search(activeQuery, page = nextPage, pageSize = 30)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("SearchScreen", "Failed to load more songs for query: $activeQuery, page: $nextPage", e)
                                    emptyList()
                                }
                            }
                        val newUnique =
                            if (nextList.isEmpty()) {
                                hasMoreSongs = false
                                emptyList()
                            } else {
                                val existingMids = songResults.map { it.songMid }.toSet()
                                val filtered = nextList.filter { it.songMid !in existingMids }
                                if (filtered.isEmpty() || nextList.size < 30) {
                                    hasMoreSongs = false
                                }
                                filtered
                            }
                        if (newUnique.isNotEmpty()) {
                            songResults = songResults + newUnique
                            songPage = nextPage
                            refreshSongFavoriteCounts(newUnique)
                        }
                        isLoadingMoreSongs = false
                        return newUnique
                    }
                }
            } else {
                null
            }
        }

    fun loadMoreSongs() {
        if (isLoadingMoreSongs || !hasMoreSongs || isLoading || activeQuery.isBlank()) return
        scope.launch {
            searchQueuePaginationSource?.loadMore()
        }
    }

    fun loadMorePlaylists() {
        if (isLoadingMorePlaylists || !hasMorePlaylists || isLoading || activeQuery.isBlank()) return
        scope.launch {
            isLoadingMorePlaylists = true
            val nextPage = playlistPage + 1
            val nextList =
                withContext(Dispatchers.IO) {
                    try {
                        apiService.searchPlaylists(activeQuery, page = nextPage, pageSize = 30)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SearchScreen", "Failed to load more playlists for query: $activeQuery, page: $nextPage", e)
                        emptyList()
                    }
                }
            if (nextList.isEmpty()) {
                hasMorePlaylists = false
            } else {
                val existingIds = playlistResults.map { it.dirId }.toSet()
                val newUnique = nextList.filter { it.dirId !in existingIds }
                if (newUnique.isEmpty() || nextList.size < 30) {
                    hasMorePlaylists = false
                }
                playlistResults = playlistResults + newUnique
                playlistPage = nextPage
            }
            isLoadingMorePlaylists = false
        }
    }

    fun loadMoreAlbums() {
        if (isLoadingMoreAlbums || !hasMoreAlbums || isLoading || activeQuery.isBlank()) return
        scope.launch {
            isLoadingMoreAlbums = true
            val nextPage = albumPage + 1
            val nextList =
                withContext(Dispatchers.IO) {
                    try {
                        apiService.searchAlbums(activeQuery, page = nextPage, pageSize = 30)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SearchScreen", "Failed to load more albums for query: $activeQuery, page: $nextPage", e)
                        emptyList()
                    }
                }
            if (nextList.isEmpty()) {
                hasMoreAlbums = false
            } else {
                val existingMids = albumResults.map { it.mid.ifBlank { it.id.toString() } }.toSet()
                val newUnique = nextList.filter { it.mid.ifBlank { it.id.toString() } !in existingMids }
                if (newUnique.isEmpty() || nextList.size < 30) {
                    hasMoreAlbums = false
                }
                albumResults = albumResults + newUnique
                albumPage = nextPage
            }
            isLoadingMoreAlbums = false
        }
    }

    LaunchedEffect(songListState, hasMoreSongs, isLoadingMoreSongs, isLoading) {
        snapshotFlow {
            val layoutInfo = songListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMoreSongs && !isLoadingMoreSongs && !isLoading && activeQuery.isNotBlank()) {
                loadMoreSongs()
            }
        }
    }

    LaunchedEffect(playlistListState, hasMorePlaylists, isLoadingMorePlaylists, isLoading) {
        snapshotFlow {
            val layoutInfo = playlistListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMorePlaylists && !isLoadingMorePlaylists && !isLoading && activeQuery.isNotBlank()) {
                loadMorePlaylists()
            }
        }
    }

    LaunchedEffect(albumListState, hasMoreAlbums, isLoadingMoreAlbums, isLoading) {
        snapshotFlow {
            val layoutInfo = albumListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItemIndex >= totalItems - 4
        }.collect { shouldLoadMore ->
            if (shouldLoadMore && hasMoreAlbums && !isLoadingMoreAlbums && !isLoading && activeQuery.isNotBlank()) {
                loadMoreAlbums()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border =
                        BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
                                    .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions =
                                KeyboardActions(
                                    onSearch = { executeSearch(query, currentTab) },
                                ),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = "搜索歌曲、歌手或专辑...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                innerTextField()
                            },
                        )

                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = ""
                                    hasSearched = false
                                    activeQuery = ""
                                    loadedTabs = emptySet()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }

            val showSuggestions = query.isNotBlank() && (!hasSearched || query.trim() != activeQuery)
            if (showSuggestions) {
                // 输入联想面板
                if (suggestions.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无联想建议",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentPadding =
                            PaddingValues(
                                top = 4.dp,
                                bottom = contentPadding.calculateBottomPadding() + 16.dp,
                            ),
                    ) {
                        itemsIndexed(
                            suggestions,
                            // key 含 index 兜底：mid/歌名任一重复都不会触发 LazyColumn key 冲突闪退
                            key = { index, item -> "$index:${item.mid}:${item.name}" },
                        ) { _, item ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { applySuggestion(item) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                // 联想仅展示歌名：优先服务端包含式高亮，否则本地包含式高亮
                                Text(
                                    text =
                                        buildSuggestionNameText(
                                            item = item,
                                            keyword = query.trim(),
                                            defaultColor = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            } else if (!hasSearched) {
                // 未发起搜索时展示搜索历史
                if (historyKeywords.isNotEmpty()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "搜索历史",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            IconButton(
                                onClick = {
                                    SearchKeywordHistoryManager.clearKeywords()
                                    historyKeywords = emptyList()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "清除搜索记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            historyKeywords.forEach { keyword ->
                                Surface(
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                query = keyword
                                                executeSearch(keyword, SearchTab.Song)
                                            },
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(
                                        text = keyword,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "输入歌曲名、歌单或专辑开始搜索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                // 搜索结果顶部 Tab 选择栏（类似主页 FilterChip）
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchTab.entries.forEach { tab ->
                        FilterChip(
                            selected = currentTab == tab,
                            onClick = {
                                currentTab = tab
                                if (activeQuery.isNotBlank() && tab !in loadedTabs) {
                                    executeSearch(activeQuery, tab)
                                }
                            },
                            label = { Text(tab.title) },
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    when (currentTab) {
                        SearchTab.Song -> {
                            if (songResults.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "未找到相关歌曲",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                CommonSongList(
                                    songs = songResults,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    state = songListState,
                                    paginationSource = searchQueuePaginationSource,
                                    lyricSnippetProvider = { song -> lyricSnippets[song.songMid] },
                                    showFavoriteButton = true,
                                    favoriteCounts = favoriteCounts,
                                    onFavoriteToggle = { song -> handleFavoriteClick(song) },
                                    contentPadding =
                                        PaddingValues(
                                            top = 4.dp,
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                    headerItems = {
                                        item(key = "search_results_header") {
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                val hintSuffix = if (hasMoreSongs) " · 下滑加载更多" else ""
                                                Text(
                                                    text = "共找到 ${songResults.size} 首歌曲$hintSuffix",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    footerItems = {
                                        if (isLoadingMoreSongs) {
                                            item(key = "song_load_more_indicator") {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 16.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        SearchTab.Playlist -> {
                            if (playlistResults.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "未找到相关歌单",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    state = playlistListState,
                                    contentPadding =
                                        PaddingValues(
                                            top = 4.dp,
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                ) {
                                    item(key = "search_playlists_header") {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val hintSuffix = if (hasMorePlaylists) " · 下滑加载更多" else ""
                                            Text(
                                                text = "共找到 ${playlistResults.size} 个歌单$hintSuffix",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    itemsIndexed(playlistResults, key = { _, pl -> pl.dirId }) { _, playlist ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable { navController.navigateToPlaylist(playlist) }
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AlbumArtImage(
                                                coverUrl = playlist.thumbnailPicUrl,
                                                contentDescription = playlist.name,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.size(52.dp),
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = playlist.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${playlist.songCount} 首歌曲",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    if (isLoadingMorePlaylists) {
                                        item(key = "playlist_load_more_indicator") {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SearchTab.Album -> {
                            if (albumResults.isEmpty()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "未找到相关专辑",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                    state = albumListState,
                                    contentPadding =
                                        PaddingValues(
                                            top = 4.dp,
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                ) {
                                    item(key = "search_albums_header") {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val hintSuffix = if (hasMoreAlbums) " · 下滑加载更多" else ""
                                            Text(
                                                text = "共找到 ${albumResults.size} 张专辑$hintSuffix",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    itemsIndexed(albumResults, key = { _, album -> album.mid.ifBlank { album.id.toString() } }) { _, album ->
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable { navController.navigateToAlbum(album.mid, album.name) }
                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AlbumArtImage(
                                                coverUrl = album.coverUrl,
                                                contentDescription = album.name,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.size(52.dp),
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = album.name,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                val subText =
                                                    buildString {
                                                        if (album.artist.isNotBlank()) append(album.artist)
                                                        if (album.songCount > 0) {
                                                            if (isNotEmpty()) append(" · ")
                                                            append("${album.songCount} 首")
                                                        }
                                                    }
                                                Text(
                                                    text = subText.ifBlank { "专辑" },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    if (isLoadingMoreAlbums) {
                                        item(key = "album_load_more_indicator") {
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
