package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.anilist.data.InfoMedia
import org.koitharu.kotatsu.scrobbling.anilist.data.InfoPerson
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataInfo
import java.util.Locale

/**
 * The cards of a tracker client's title page, drawn under the synopsis: stats, alternative titles, genres,
 * tags with percentages, seasons, then characters, relations, staff and recommendations. A card whose data the
 * tracker did not give is simply not drawn.
 */
@Composable
internal fun TrackerInfoSections(
	state: TrackerInfoState,
	isAnime: Boolean,
	accent: Color,
	imageLoader: ImageLoader,
	actions: DetailsExpressiveActions,
) {
	when (state) {
		TrackerInfoState.Hidden -> Unit
		TrackerInfoState.Loading -> TrackerInfoPlaceholder()
		is TrackerInfoState.Loaded -> TrackerInfoCards(state.info, isAnime, accent, imageLoader, actions)
	}
}

@Composable
private fun TrackerInfoCards(
	info: MetadataInfo,
	isAnimeSource: Boolean,
	accent: Color,
	imageLoader: ImageLoader,
	actions: DetailsExpressiveActions,
) {
	// A tracker title can be an anime even before any source was found (opened from a tracker first).
	val isAnime = isAnimeSource || info.episodes != null || info.nextEpisode != null
	InfoStatsCard(info, isAnime)
	InfoAlternativeTitles(info)
	if (info.genres.isNotEmpty()) {
		SectionCard {
			InfoCardTitle(stringResource(R.string.ds_info_genres))
			InfoChips(
				items = info.genres.map { it to it },
				accent = accent,
				highlighted = false,
				onClick = actions.onInfoGenreClick,
			)
		}
	}
	if (info.tags.isNotEmpty()) {
		SectionCard {
			InfoCardTitle(stringResource(R.string.ds_info_tags))
			InfoChips(
				items = info.tags.map { (if (it.rank > 0) "${it.name} ${it.rank}%" else it.name) to it.name },
				accent = accent,
				highlighted = true,
				onClick = actions.onInfoGenreClick,
			)
		}
	}
	InfoSeasons(info, imageLoader, actions)
	if (info.characters.isNotEmpty()) {
		InfoCarouselTitle(
			stringResource(if (isAnime) R.string.ds_info_characters_va else R.string.ds_info_characters),
			accent,
		)
		LazyRow(
			modifier = Modifier.fillMaxWidth(),
			contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(info.characters) { CharacterCard(it, imageLoader) }
		}
	}
	if (info.relations.isNotEmpty()) {
		InfoCarouselTitle(stringResource(R.string.ds_info_relations), accent)
		MediaCarousel(info.relations, imageLoader, accent, showRelation = true, onClick = actions.onInfoMediaClick)
	}
	if (info.staff.isNotEmpty()) {
		InfoCarouselTitle(stringResource(R.string.ds_info_staff), accent)
		LazyRow(
			modifier = Modifier.fillMaxWidth(),
			contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			items(info.staff) { StaffCard(it, imageLoader) }
		}
	}
	if (info.recommendations.isNotEmpty()) {
		InfoCarouselTitle(stringResource(R.string.ds_info_recommended), accent)
		MediaCarousel(info.recommendations, imageLoader, accent, showRelation = false, onClick = actions.onInfoMediaClick)
	}
}

// region Stats and titles

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoStatsCard(info: MetadataInfo, isAnime: Boolean) {
	val minutes = info.duration?.let { stringResource(R.string.ds_info_minutes, it) }
	val season = listOfNotNull(info.season?.let(::prettyEnum), info.year?.toString()).joinToString(" ")
	val nextEpisode = info.nextEpisode?.let { ep ->
		val left = info.nextAiringAt?.let { formatCountdown(it - System.currentTimeMillis() / 1000L) }
		if (left == null) "Ep $ep" else "Ep $ep · $left"
	}
	val stats = listOfNotNull(
		info.studios.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ds_info_studio) to it.joinToString(", ") },
		if (isAnime) info.episodes?.let { stringResource(R.string.ds_info_episodes) to it.toString() } else null,
		if (!isAnime) info.chapters?.let { stringResource(R.string.ds_info_chapters) to it.toString() } else null,
		if (!isAnime) info.volumes?.let { stringResource(R.string.ds_info_volumes) to it.toString() } else null,
		if (isAnime) minutes?.let { stringResource(R.string.ds_info_duration) to it } else null,
		season.takeIf { it.isNotBlank() }?.let { stringResource(R.string.ds_info_season) to it },
		info.status?.let { stringResource(R.string.ds_info_status) to prettyEnum(it).uppercase(Locale.ROOT) },
		formatLabel(info)?.let { stringResource(R.string.ds_info_format) to it },
		info.source?.let { stringResource(R.string.ds_info_source) to prettyEnum(it) },
		info.score?.let { stringResource(R.string.ds_info_score) to "★ " + String.format(Locale.ROOT, "%.1f", it) },
		info.popularity?.let { stringResource(R.string.ds_info_popularity) to it.toString() },
		nextEpisode?.let { stringResource(R.string.ds_info_next_episode) to it },
	)
	if (stats.isEmpty()) return
	SectionCard {
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(24.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			stats.forEach { (label, value) ->
				Column {
					Text(
						text = label,
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(2.dp))
					Text(
						text = value,
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
			}
		}
	}
}

@Composable
private fun InfoAlternativeTitles(info: MetadataInfo) {
	val rows = listOfNotNull(
		info.title.takeIf { it.isNotBlank() }?.let { stringResource(R.string.ds_info_main_title) to it },
		info.romaji?.takeIf { it.isNotBlank() && it != info.title }
			?.let { stringResource(R.string.ds_info_romaji) to it },
		info.english?.takeIf { it.isNotBlank() && it != info.title }
			?.let { stringResource(R.string.ds_info_english) to it },
		info.native?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.ds_info_native) to it },
		info.synonyms.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ds_info_synonyms) to it.joinToString(", ") },
	)
	// A title alone is not an "alternative"; draw the card only when there is something besides it.
	if (rows.size < 2) return
	SectionCard {
		InfoCardTitle(stringResource(R.string.ds_info_alt_titles))
		rows.forEach { (label, value) ->
			Row(modifier = Modifier.padding(bottom = 8.dp)) {
				Text(
					text = label,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.width(96.dp),
				)
				Text(
					text = value,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

@Composable
private fun InfoCardTitle(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier.padding(bottom = 12.dp),
	)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoChips(
	items: List<Pair<String, String>>,
	accent: Color,
	highlighted: Boolean,
	onClick: (String) -> Unit,
) {
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		items.forEach { (label, key) ->
			Pill(text = label, accent = accent, highlighted = highlighted, onClick = { onClick(key) })
		}
	}
}

// endregion

// region Seasons

@Composable
private fun InfoSeasons(info: MetadataInfo, imageLoader: ImageLoader, actions: DetailsExpressiveActions) {
	val entries = listOfNotNull(info.prequel, info.sequel)
	if (entries.isEmpty()) return
	SectionCard {
		InfoCardTitle(stringResource(R.string.ds_info_seasons))
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			entries.forEach { media ->
				SeasonButton(
					media = media,
					imageLoader = imageLoader,
					modifier = Modifier.weight(1f),
					onClick = { actions.onInfoMediaClick(media) },
				)
			}
		}
	}
}

@Composable
private fun SeasonButton(media: InfoMedia, imageLoader: ImageLoader, modifier: Modifier, onClick: () -> Unit) {
	val ctx = LocalContext.current
	val url = media.bannerUrl ?: media.coverUrl
	val request = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(true).build() }
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
		modifier = modifier
			.height(76.dp)
			.clickable(onClick = onClick),
	) {
		Box {
			if (url != null) {
				AsyncImage(
					model = request,
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.align(Alignment.CenterEnd)
						.fillMaxWidth(0.55f)
						.height(76.dp),
				)
				// Fade the picture into the card so the label stays readable.
				Box(
					modifier = Modifier
						.matchParentSize()
						.background(
							Brush.horizontalGradient(
								0.30f to MaterialTheme.colorScheme.surfaceContainerHighest,
								0.75f to Color.Transparent,
							),
						),
				)
			}
			Text(
				text = prettyEnum(media.relation.orEmpty()).uppercase(Locale.ROOT),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier
					.align(Alignment.CenterStart)
					.padding(start = 14.dp),
			)
		}
	}
}

// endregion

// region Carousels

@Composable
private fun InfoCarouselTitle(text: String, accent: Color) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleMedium,
		fontWeight = FontWeight.SemiBold,
		color = accent,
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = SCREEN_PADDING, end = SCREEN_PADDING, top = 20.dp, bottom = 12.dp),
	)
}

@Composable
private fun InfoImage(url: String?, imageLoader: ImageLoader, modifier: Modifier) {
	val ctx = LocalContext.current
	val request = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(true).build() }
	Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
		if (url != null) {
			AsyncImage(
				model = request,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.matchParentSize(),
			)
		}
	}
}

@Composable
private fun CharacterCard(person: InfoPerson, imageLoader: ImageLoader) {
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier.width(250.dp),
	) {
		Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
			Box {
				InfoImage(
					url = person.imageUrl,
					imageLoader = imageLoader,
					modifier = Modifier
						.size(width = 62.dp, height = 88.dp)
						.clip(RoundedCornerShape(14.dp)),
				)
				person.favourites?.let { fav ->
					Text(
						text = "♥ $fav",
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						modifier = Modifier
							.align(Alignment.BottomStart)
							.padding(4.dp)
							.clip(RoundedCornerShape(6.dp))
							.background(Color.Black.copy(alpha = 0.65f))
							.padding(horizontal = 4.dp, vertical = 1.dp),
					)
				}
			}
			Column(modifier = Modifier.padding(start = 12.dp)) {
				Text(
					text = person.name,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = person.role?.let(::prettyEnum)?.uppercase(Locale.ROOT).orEmpty(),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun StaffCard(person: InfoPerson, imageLoader: ImageLoader) {
	Column(modifier = Modifier.width(112.dp)) {
		Box {
			InfoImage(
				url = person.imageUrl,
				imageLoader = imageLoader,
				modifier = Modifier
					.size(width = 112.dp, height = 150.dp)
					.clip(RoundedCornerShape(16.dp)),
			)
			person.role?.takeIf { it.isNotBlank() }?.let { role ->
				Text(
					text = role,
					style = MaterialTheme.typography.labelSmall,
					fontStyle = FontStyle.Italic,
					color = Color.White,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier
						.align(Alignment.BottomCenter)
						.fillMaxWidth()
						.background(Color.Black.copy(alpha = 0.6f))
						.padding(horizontal = 6.dp, vertical = 3.dp),
				)
			}
		}
		Text(
			text = person.name,
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(top = 6.dp),
		)
	}
}

@Composable
private fun MediaCarousel(
	items: List<InfoMedia>,
	imageLoader: ImageLoader,
	accent: Color,
	showRelation: Boolean,
	onClick: (InfoMedia) -> Unit,
) {
	LazyRow(
		modifier = Modifier.fillMaxWidth(),
		contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		items(items) { media ->
			Column(
				modifier = Modifier
					.width(128.dp)
					.clip(RoundedCornerShape(18.dp))
					.clickable { onClick(media) },
			) {
				InfoImage(
					url = media.coverUrl,
					imageLoader = imageLoader,
					modifier = Modifier
						.size(width = 128.dp, height = 184.dp)
						.clip(RoundedCornerShape(18.dp)),
				)
				Text(
					text = media.title,
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(top = 8.dp),
				)
				if (showRelation && media.relation != null) {
					Pill(
						text = prettyEnum(media.relation).uppercase(Locale.ROOT),
						accent = accent,
						highlighted = true,
					)
				} else if (media.score != null) {
					Text(
						text = "★ " + String.format(Locale.ROOT, "%.1f", media.score),
						style = MaterialTheme.typography.labelMedium,
						color = accent,
					)
				}
			}
		}
	}
}

// endregion

// region Placeholder

/** Grey pulsing cards where the tracker cards will appear, so the page does not jump when they arrive. */
@Composable
private fun TrackerInfoPlaceholder() {
	val pulse by rememberInfiniteTransition(label = "info-placeholder").animateFloat(
		initialValue = 0.35f,
		targetValue = 0.85f,
		animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
		label = "info-placeholder-alpha",
	)
	listOf(110.dp, 90.dp, 140.dp).forEach { h ->
		Surface(
			shape = RoundedCornerShape(CARD_CORNER),
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING, vertical = 8.dp)
				.height(h)
				.alpha(pulse),
			content = {},
		)
	}
}

// endregion

// region Formatting

/** "NOT_YET_RELEASED" -> "Not Yet Released", "TV" stays "TV". */
internal fun prettyEnum(raw: String): String {
	if (raw.length <= 3) return raw.uppercase(Locale.ROOT)
	return raw.lowercase(Locale.ROOT).split('_', ' ')
		.filter { it.isNotEmpty() }
		.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}

/** "Manga (South Korea)" - the format, then where it comes from. */
private fun formatLabel(info: MetadataInfo): String? {
	val format = info.format?.let(::prettyEnum) ?: return null
	val country = when (info.country) {
		"JP" -> "Japan"
		"KR" -> "South Korea"
		"CN" -> "China"
		"TW" -> "Taiwan"
		else -> null
	}
	return if (country == null) format else "$format ($country)"
}

private fun formatCountdown(seconds: Long): String? {
	if (seconds <= 0) return null
	val d = seconds / 86_400
	val h = seconds % 86_400 / 3_600
	val m = seconds % 3_600 / 60
	return when {
		d > 0 -> "${d}d ${h}h"
		h > 0 -> "${h}h ${m}m"
		else -> "${m}m"
	}
}

// endregion
