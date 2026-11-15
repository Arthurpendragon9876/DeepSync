package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.anime.player.AnimePlaybackSession
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeEpisodeMeta
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

private const val PAGE_SIZE = 30

/**
 * Anime episode list shown inline on the details page: a header (Sub|Dub playback preset, sort, count)
 * and one card per episode with its thumbnail, "N. Title" and air date. Replaces the manga/novel
 * chapter pill + sheet when the source is an anime source.
 */
@Composable
internal fun EpisodesSection(
	details: MangaDetails,
	manga: Manga,
	historyInfo: HistoryInfo,
	imageLoader: ImageLoader,
	accent: Color,
	onEpisodeClick: (MangaChapter) -> Unit,
) {
	val all = details.allChapters
	if (all.isEmpty()) return
	var ascending by rememberSaveable { mutableStateOf(true) }
	var visible by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
	val context = androidx.compose.ui.platform.LocalContext.current
	var preset by remember { mutableStateOf(AnimePlaybackSession.audioPreset(context)) }

	// Sub / Dub is not a filter: the list always shows every episode. It is a playback preset the
	// player applies when it starts (Sub = Japanese audio + subtitles, Dub = English audio, no subtitles).
	val filtered = remember(all, ascending) { if (ascending) all else all.asReversed() }
	val currentId = historyInfo.history?.chapterId

	Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			AudioPresetToggle(preset, accent) {
				preset = it
				AnimePlaybackSession.setAudioPreset(context, it)
			}
			HeaderChip(if (ascending) "Ep ↑" else "Ep ↓", leading = R.drawable.ic_ap_sort) { ascending = !ascending }
			Spacer(Modifier.weight(1f))
			Text(
				"${filtered.size} Episodes",
				fontSize = 18.sp,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
		Spacer(Modifier.height(12.dp))
		Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			filtered.take(visible).forEach { chapter ->
				EpisodeCard(chapter, manga, imageLoader, accent, isCurrent = chapter.id == currentId) {
					onEpisodeClick(chapter)
				}
			}
		}
		if (filtered.size > visible) {
			TextButton(
				onClick = { visible += PAGE_SIZE },
				modifier = Modifier.align(Alignment.CenterHorizontally),
			) { Text("Show ${minOf(PAGE_SIZE, filtered.size - visible)} more") }
		}
	}
}

/** Two-segment Sub | Dub switch. */
@Composable
private fun AudioPresetToggle(preset: String, accent: Color, onChange: (String) -> Unit) {
	Row(
		Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
		verticalAlignment = Alignment.CenterVertically,
	) {
		listOf("sub" to "Sub", "dub" to "Dub").forEach { (key, label) ->
			val selected = preset == key
			Text(
				label,
				fontWeight = FontWeight.SemiBold,
				fontSize = 16.sp,
				color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
				modifier = Modifier
					.padding(4.dp)
					.clip(RoundedCornerShape(11.dp))
					.background(if (selected) accent else Color.Transparent)
					.clickable { onChange(key) }
					.padding(horizontal = 16.dp, vertical = 8.dp),
			)
		}
	}
}

@Composable
private fun HeaderChip(text: String, leading: Int? = null, trailing: Int? = null, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(14.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		Row(
			Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			leading?.let { Icon(painterResource(it), null, Modifier.size(18.dp)) }
			Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
			trailing?.let { Icon(painterResource(it), null, Modifier.size(18.dp)) }
		}
	}
}

@Composable
private fun EpisodeCard(
	chapter: MangaChapter,
	manga: Manga,
	imageLoader: ImageLoader,
	accent: Color,
	isCurrent: Boolean,
	onClick: () -> Unit,
) {
	val ctx = LocalContext.current
	val thumb = AnimeEpisodeMeta.previews[chapter.id] ?: manga.coverUrl
	val request = remember(thumb, manga.source) {
		ImageRequest.Builder(ctx).data(thumb).crossfade(true).mangaSourceExtra(manga.source).build()
	}
	val n = chapter.number
	val numberText = if (n > 0f) (if (n % 1f == 0f) n.toInt().toString() else n.toString()) else null
	val title = chapter.title?.takeIf { it.isNotBlank() } ?: "Episode ${numberText.orEmpty()}".trim()
	val heading = if (numberText != null && !title.startsWith(numberText)) "$numberText. $title" else title

	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(22.dp),
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.width(132.dp).height(76.dp).clip(RoundedCornerShape(14.dp))) {
				AsyncImage(
					model = request,
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
				)
				Box(
					Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(Color(0x99000000)),
					contentAlignment = Alignment.Center,
				) {
					Icon(painterResource(R.drawable.ic_ap_play), null, tint = Color.White, modifier = Modifier.size(22.dp))
				}
			}
			Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
				Text(
					heading,
					fontSize = 16.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (chapter.uploadDate > 0L) {
					Text(
						DateUtils.formatDateTime(ctx, chapter.uploadDate, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
						fontSize = 13.sp,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(top = 4.dp),
					)
				}
			}
			if (isCurrent) {
				Icon(
					painterResource(R.drawable.ic_ap_bookmark), "Last watched",
					tint = accent, modifier = Modifier.padding(end = 8.dp).size(24.dp),
				)
			}
		}
	}
}
