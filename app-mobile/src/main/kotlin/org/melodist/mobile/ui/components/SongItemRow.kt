package org.melodist.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.api.PlaybackCredentialsManager
import org.melodist.api.PlaybackVipTier
import org.melodist.model.Song
import org.melodist.playback.ListenTogetherManager

private val CoverShape = RoundedCornerShape(8.dp)
private val VipBadgeShape = RoundedCornerShape(3.dp)
private val FavoriteRedColor = Color(0xFFEC4433)

/** 行尾收藏块宽度：固定值（≥ 最长文案 `99.9万+` 的 32dp），块内爱心与数字都居中 ⇒ 各行爱心成列、数字也成列。 */
private val FAVORITE_BLOCK_WIDTH = 33.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItemRow(
    song: Song,
    isPlayingThis: Boolean,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    showLocalBadge: Boolean = true,
    showQualityBadge: Boolean = false,
    lyricSnippet: String? = null,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    isFavorite: Boolean? = null,
    favoriteCount: Int? = null,
    onClick: () -> Unit,
    onSelectToggle: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isMultiSelectMode) {
                            onSelectToggle?.invoke()
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = onLongClick,
                ).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CoverShape)
                    .then(
                        if (isMultiSelectMode) {
                            Modifier.clickable { onSelectToggle?.invoke() }
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            AlbumArtImage(
                coverUrl = song.thumbnailCoverUrl,
                candidates = song.thumbnailCandidates,
                songMid = song.songMid,
                contentDescription = song.name,
                shape = CoverShape,
                elevation = 0.dp,
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                placeholderIconSize = 22.dp,
                modifier = Modifier.matchParentSize(),
            )

            if (isMultiSelectMode) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
                                } else {
                                    Color.Black.copy(alpha = 0.25f)
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                        )
                    }
                }
            } else if (isPlayingThis) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clip(CoverShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            val onSurfaceColor = MaterialTheme.colorScheme.onSurface
            val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
            val titleColor = if (isPlayingThis) primaryColor else onSurfaceColor

            val titleText =
                remember(song.name, highlightQuery, titleColor, primaryColor) {
                    buildHighlightedAnnotatedString(
                        text = song.name,
                        query = highlightQuery,
                        defaultColor = titleColor,
                        highlightColor = primaryColor,
                        highlightFontWeight = FontWeight.Bold,
                    )
                }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlayingThis) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showQualityBadge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    QualityTierBadge(tier = song.currentTier)
                }
            }

            val rawSubtitle =
                remember(song.singer, song.album) {
                    buildString {
                        append(song.singer.ifBlank { "未知歌手" })
                        if (song.album.isNotBlank()) {
                            append(" · ")
                            append(song.album)
                        }
                    }
                }

            val subtitleText =
                remember(rawSubtitle, highlightQuery, onSurfaceVariantColor, primaryColor) {
                    buildHighlightedAnnotatedString(
                        text = rawSubtitle,
                        query = highlightQuery,
                        defaultColor = onSurfaceVariantColor,
                        highlightColor = primaryColor,
                        highlightFontWeight = FontWeight.Bold,
                    )
                }

            val currentCreds by PlaybackCredentialsManager.credentialsFlow.collectAsState()
            val currentRoom by ListenTogetherManager.currentRoom.collectAsState()
            val isBothVip by ListenTogetherManager.isBothVip.collectAsState()
            val isListenTogetherNonBothVip = currentRoom != null && !isBothVip
            val shouldShowVipBadge =
                song.isVip &&
                    (
                        isListenTogetherNonBothVip ||
                            PlaybackCredentialsManager.getEffectiveVipTier() == PlaybackVipTier.NONE
                    )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (shouldShowVipBadge) {
                    Text(
                        text = "VIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                    VipBadgeShape,
                                ).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (showLocalBadge && song.isLocal) {
                    Text(
                        text = "本地",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                    VipBadgeShape,
                                ).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (!lyricSnippet.isNullOrBlank()) {
                    Text(
                        text = "歌词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                                    VipBadgeShape,
                                ).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!lyricSnippet.isNullOrBlank()) {
                Text(
                    text = lyricSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }

        if (!isMultiSelectMode && isFavorite != null && onFavoriteToggle != null) {
            FavoriteToggleButton(
                isFavorite = isFavorite,
                countText = formatFavCount(favoriteCount),
                onClick = onFavoriteToggle,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (!isMultiSelectMode && onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 行尾收藏块：爱心 + **下方**收藏数（浅灰小字），位于行尾「更多」按钮左侧（2026-11-15 第八版改纵向堆叠）。
 * 已收藏显示红心，未收藏显示灰心；收藏数未知或为 0 时只显示爱心、不显示数字（但仍占位，保证各行爱心同高）。
 * 整块宽度恒为 [FAVORITE_BLOCK_WIDTH]、内容水平居中、外层右贴心尾 `⋮`：
 * 爱心 x 与数字 x 在所有行完全一致（上下对齐），既不出现「数字长短导致爱心左右飘」，
 * 也不出现「短数字后面空一块」。
 */
@Composable
private fun FavoriteToggleButton(
    isFavorite: Boolean,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint =
        if (isFavorite) {
            FavoriteRedColor
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Column(
        modifier = modifier.width(FAVORITE_BLOCK_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        // 恒渲染（即使文案为空）：保住 11dp 行高，使"无数字的行"爱心与其它行保持同一水平线。
        Text(
            text = countText,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 格式化收藏数（网易云风格：短、避免长数字挤压歌名）：
 * - 未知（`null`）、负数或 0 → 空串，直接不显示数字；
 * - 小于 1000 → 原样数字（如 `128`、`999`）；
 * - 1000 ~ 9999 → 取整千 + `k`（如 `1k`、`9k`）；
 * - 1 万 ~ 99.9 万 → `万` 并保留 1 位小数、整数时省略小数，末尾补 `+` 表示"至少"（如 `1万+`、`15万+`、`99.9万+`）；
 * - 100 万及以上 → 统一封顶 `999万+`（不再随数量增长）。
 */
internal fun formatFavCount(count: Int?): String {
    if (count == null || count <= 0) return ""
    if (count < 1_000) return count.toString()
    if (count < 10_000) return "${count / 1_000}k"
    if (count < 1_000_000) {
        val whole = count / 10_000
        val decimal = (count / 1_000) % 10
        return if (decimal == 0) "${whole}万+" else "$whole.${decimal}万+"
    }
    return "999万+"
}

/**
 * 构建带有子串高亮样式的 AnnotatedString。
 * 当 query 为空或未匹配时返回原始 AnnotatedString。
 */
fun buildHighlightedAnnotatedString(
    text: String,
    query: String,
    defaultColor: Color,
    highlightColor: Color,
    highlightFontWeight: FontWeight = FontWeight.Bold,
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty() || !text.contains(q, ignoreCase = true)) {
        return AnnotatedString(text, SpanStyle(color = defaultColor))
    }
    return buildAnnotatedString {
        var startIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = q.lowercase()
        while (startIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
            if (matchIndex == -1) {
                withStyle(SpanStyle(color = defaultColor)) {
                    append(text.substring(startIndex))
                }
                break
            }
            if (matchIndex > startIndex) {
                withStyle(SpanStyle(color = defaultColor)) {
                    append(text.substring(startIndex, matchIndex))
                }
            }
            val endIndex = matchIndex + lowerQuery.length
            withStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = highlightFontWeight,
                ),
            ) {
                append(text.substring(matchIndex, endIndex))
            }
            startIndex = endIndex
        }
    }
}
