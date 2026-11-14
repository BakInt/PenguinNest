package org.melodist.mobile.ui.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.melodist.api.MusicApiService
import org.melodist.mobile.ui.components.AlbumArtImage
import org.melodist.model.Album

private const val ALBUM_GRID_COLUMNS = 3
private const val ALBUM_GRID_PREVIEW_COUNT = 9

/**
 * 收藏歌单 Tab 内的"收藏的专辑"小节：封面网格（前 9 张，3 列）+ 查看全部入口（→ FavoriteAlbumsScreen）。
 */
@Composable
fun FavoriteAlbumsSection(
    albums: List<Album>,
    onViewAll: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewRows =
        remember(albums) {
            albums.take(ALBUM_GRID_PREVIEW_COUNT).chunked(ALBUM_GRID_COLUMNS)
        }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "收藏的专辑 (${albums.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        )

        previewRows.forEachIndexed { rowIndex, row ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (columnIndex in 0 until ALBUM_GRID_COLUMNS) {
                    val album = row.getOrNull(columnIndex)
                    if (album == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        AlbumGridCard(
                            album = album,
                            onClick = { onOpenAlbum(album) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onViewAll)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "查看全部收藏专辑",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AlbumGridCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverUrl =
        remember(album.mid, album.coverUrl) {
            album.coverUrl.ifBlank {
                if (album.mid.isNotBlank()) MusicApiService.getAlbumCoverUrl(album.mid) else ""
            }
        }

    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        AlbumArtImage(
            coverUrl = coverUrl,
            contentDescription = album.name,
            shape = RoundedCornerShape(10.dp),
            elevation = 2.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (album.artist.isNotBlank()) {
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
