package org.melodist.mobile.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import org.melodist.model.Playlist

/** 原首页顶部筛选标签对应的功能页，MusicFree 改版后通过抽屉与快捷入口进入 */
enum class HomeFilter(
    val title: String,
) {
    Discover("每日推荐"),
    Library("我的收藏"),
    Recent("最近播放"),
    Stats("听歌足迹"),
    Download("下载管理"),
    Local("本地音乐"),
    Remote("远程控制"),
}

sealed interface ScreenDestination {
    data class PlaylistDetail(
        val playlist: Playlist,
    ) : ScreenDestination

    data class ArtistDetail(
        val artistMid: String,
        val artistName: String = "",
    ) : ScreenDestination

    data class AlbumDetail(
        val albumMid: String,
        val albumName: String = "",
    ) : ScreenDestination

    data object FavoriteAlbums : ScreenDestination

    data object RecentAlbums : ScreenDestination

    data object RecentPlaylists : ScreenDestination

    data class HomeFeaturePage(
        val filter: HomeFilter,
    ) : ScreenDestination
}

interface AppNavigationController {
    fun navigateToPlaylist(
        playlist: Playlist,
        clearStack: Boolean = false,
    )

    fun navigateToArtist(
        artistMid: String,
        artistName: String = "",
        clearStack: Boolean = false,
    )

    fun navigateToAlbum(
        albumMid: String,
        albumName: String = "",
        clearStack: Boolean = false,
    )

    fun navigateToFavoriteAlbums(clearStack: Boolean = false)

    fun navigateToRecentAlbums(clearStack: Boolean = false)

    fun navigateToRecentPlaylists(clearStack: Boolean = false)

    fun navigateToHomeFeature(
        filter: HomeFilter,
        clearStack: Boolean = false,
    )

    fun navigateBack(): Boolean

    val currentDestination: ScreenDestination?
}

val LocalAppNavigation =
    compositionLocalOf<AppNavigationController> {
        object : AppNavigationController {
            override fun navigateToPlaylist(
                playlist: Playlist,
                clearStack: Boolean,
            ) {}

            override fun navigateToArtist(
                artistMid: String,
                artistName: String,
                clearStack: Boolean,
            ) {}

            override fun navigateToAlbum(
                albumMid: String,
                albumName: String,
                clearStack: Boolean,
            ) {}

            override fun navigateToFavoriteAlbums(clearStack: Boolean) {}

            override fun navigateToRecentAlbums(clearStack: Boolean) {}

            override fun navigateToRecentPlaylists(clearStack: Boolean) {}

            override fun navigateToHomeFeature(
                filter: HomeFilter,
                clearStack: Boolean,
            ) {}

            override fun navigateBack(): Boolean = false

            override val currentDestination: ScreenDestination? = null
        }
    }
