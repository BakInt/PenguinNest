package org.melodist.mobile.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.mobile.ui.connect.RemoteControlMobileScreen
import org.melodist.mobile.ui.discover.DiscoverScreen
import org.melodist.mobile.ui.download.DownloadMobileScreen
import org.melodist.mobile.ui.library.LibraryScreen
import org.melodist.mobile.ui.local.LocalMusicMobileScreen
import org.melodist.mobile.ui.navigation.HomeFilter
import org.melodist.mobile.ui.recent.RecentPlaybackScreen
import org.melodist.mobile.ui.stats.ListeningStatsScreen
import org.melodist.model.Playlist
import org.melodist.playback.PlaybackManager

/**
 * MusicFree 改版后的功能页外壳：扁平返回头 + 原有功能页内容。
 * 原首页 Pager 内嵌的页面保持实现不变，仅改为推栈进入。
 */
@Composable
fun HomeFeaturePageScreen(
    filter: HomeFilter,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRequireLogin: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = filter.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            when (filter) {
                HomeFilter.Discover -> {
                    DiscoverScreen(
                        contentPadding = contentPadding,
                        onRequireLogin = onRequireLogin,
                        onOpenPlayer = onOpenPlayer,
                    )
                }

                HomeFilter.Library -> {
                    LibraryScreen(
                        contentPadding = contentPadding,
                        onRequireLogin = onRequireLogin,
                        onOpenPlaylist = onOpenPlaylist,
                    )
                }

                HomeFilter.Recent -> {
                    RecentPlaybackScreen(
                        contentPadding = contentPadding,
                    )
                }

                HomeFilter.Stats -> {
                    ListeningStatsScreen(
                        contentPadding = contentPadding,
                        onPlayPlaylist = { songs, startIndex ->
                            if (songs.isNotEmpty()) {
                                PlaybackManager.setPlaylist(songs, startIndex = startIndex)
                            }
                        },
                    )
                }

                HomeFilter.Download -> {
                    DownloadMobileScreen(
                        contentPadding = contentPadding,
                    )
                }

                HomeFilter.Local -> {
                    LocalMusicMobileScreen(
                        contentPadding = contentPadding,
                    )
                }

                HomeFilter.Remote -> {
                    RemoteControlMobileScreen(
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }
}
