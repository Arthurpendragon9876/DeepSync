@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.details.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isAnimeSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo

/**
 * Navigation/action hooks for the expressive details screen. The activity owns the router
 * and supplies these so the Compose layer stays free of Android navigation plumbing.
 */
class DetailsExpressiveActions(
	val onCoverClick: (Manga) -> Unit,
	val onTitleClick: (String) -> Unit,
	val onSourceClick: (Manga) -> Unit,
	val onLocalClick: (Manga) -> Unit,
	val onFavoriteClick: (Manga) -> Unit,
	val onMigrateClick: (Manga) -> Unit,
	val onAuthorClick: (String) -> Unit,
	val onTagClick: (MangaTag) -> Unit,
	val onScrobblingMore: () -> Unit,
	val onScrobblingCardClick: (Int) -> Unit,
	val onRelatedMore: (Manga) -> Unit,
	val onRelatedClick: (Manga) -> Unit,
	val onReadClick: () -> Unit,
	val onIncognitoClick: () -> Unit,
	val onForgetHistoryClick: () -> Unit,
	val onChaptersClick: () -> Unit,
	val onEpisodeClick: (org.koitharu.kotatsu.parsers.model.MangaChapter) -> Unit = {},
	/** The "N episodes" pill: true when the watch page took over, false to fall back to the episodes sheet. */
	val onEpisodesPageClick: () -> Boolean = { false },
	/** A genre or tag chip of the tracker info cards. */
	/** The tracker status button; the index of the tracked entry, or -1 when the title is not tracked yet. */
	val onTrackerClick: (Int) -> Unit = {},
	val onInfoGenreClick: (String) -> Unit = {},
	/** A relation or recommendation of the tracker info cards. */
	val onInfoMediaClick: (org.koitharu.kotatsu.scrobbling.anilist.data.InfoMedia) -> Unit = {},
)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun DetailsExpressiveScreen(
	details: MangaDetails?,
	tags: List<ChipsView.ChipModel>,
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	favouriteCount: Int,
	favouriteLabel: String?,
	scrobblings: List<ScrobblingInfo>,
	related: List<MangaListModel>,
	localSize: Long,
	sourceTitle: String?,
	imageLoader: ImageLoader,
	coverUrl: String?,
	coverRefreshes: Int,
	backdropUrl: String?,
	isBackdropEnabled: Boolean,
	backdropBlurAmount: Int,
	style: DetailsUiMode,
	topInset: Dp,
	bottomContentPadding: Dp,
	onScroll: (Int) -> Unit,
	actions: DetailsExpressiveActions,
	trackerInfo: TrackerInfoState = TrackerInfoState.Hidden,
	/** The tracker whose status the squircle button shows (the home metadata provider's). */
	trackerService: org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService? = null,
	/** False on the preview page, which has no title of its own to track yet. */
	allowTracking: Boolean = true,
	/** Text for the read button while the source is still being found (tracker title opened first). */
	dockOverride: (@Composable () -> Unit)? = null,
) {
	val manga = details?.toManga()
	val isAnime = manga?.source?.isAnimeSource == true
	// With info from the tracker the status button replaces the separate Tracking card.
	var showEpisodes by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
	val trackerMode = allowTracking && trackerInfo !is TrackerInfoState.Hidden
	val tracked = if (trackerMode) {
		scrobblings.firstOrNull { it.scrobbler == trackerService } ?: scrobblings.firstOrNull()
	} else {
		null
	}
	val trackerButton: (@Composable () -> Unit)? = if (trackerMode) {
		{
			TrackerStatusButton(
				info = tracked,
				onClick = { actions.onTrackerClick(if (tracked == null) -1 else scrobblings.indexOf(tracked)) },
			)
		}
	} else {
		null
	}

	val scheme = MaterialTheme.colorScheme
	val accentColor = scheme.primary
	val scrollState = rememberScrollState()
	val centered = style != DetailsUiMode.COMPACT

	LaunchedEffect(scrollState) {
		snapshotFlow { scrollState.value }.collect(onScroll)
	}

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(scheme.surface),
	) {
		if (isBackdropEnabled && backdropUrl != null) {
			ExpressiveBackdrop(
				url = backdropUrl,
				coverRefreshes = coverRefreshes,
				manga = manga,
				imageLoader = imageLoader,
				surface = scheme.surface,
				blurAmount = backdropBlurAmount,
			)
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(scrollState)
				.padding(bottom = bottomContentPadding + DETAIL_DOCK_RESERVE),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			// Push the hero clear of the translucent top bar / back button.
		Spacer(Modifier.height(topInset + if (centered) 84.dp else 72.dp))

		if (manga == null) {
			LoadingHero()
		} else {
			val favLabel = favouriteLabel ?: stringResource(R.string.add_to_favourites)
			val isFavourite = favouriteCount > 0
			HeroSection(
				centered = centered,
				manga = manga,
				details = details,
				sourceTitle = sourceTitle,
				accent = accentColor,
				imageLoader = imageLoader,
				coverUrl = coverUrl,
				coverRefreshes = coverRefreshes,
				favouriteLabel = favLabel,
				isFavourite = isFavourite,
				onFavouriteClick = { actions.onFavoriteClick(manga) },
				onMigrateClick = if (manga.isLocal) null else ({ actions.onMigrateClick(manga) }),
				actions = actions,
			)

			if (centered) {
				Spacer(Modifier.height(20.dp))
				FavouriteMigrateRow(
					label = favLabel,
					isFavourite = isFavourite,
					accent = accentColor,
					onFavouriteClick = { actions.onFavoriteClick(manga) },
					onMigrateClick = if (manga.isLocal) null else ({ actions.onMigrateClick(manga) }),
				)
			}

			Spacer(Modifier.height(8.dp))
			ProgressCard(
				historyInfo = historyInfo,
				isLoading = isLoading,
				accent = accentColor,
				animeTotal = if (isAnime) details.allChapters.size else -1,
				animeCurrent = if (isAnime) {
					details.allChapters.indexOfFirst { it.id == historyInfo.history?.chapterId }
				} else {
					-1
				},
				trailing = trackerButton,
			)

			DescriptionCard(
				description = details.description,
				manga = manga,
				details = details,
				accent = accentColor,
			)

			// With tracker info the Genres and Tags cards below replace the source's chips.
			if (trackerInfo is TrackerInfoState.Hidden) {
				TagsSection(tags = tags, accent = accentColor, onTagClick = actions.onTagClick)
			} else {
				TrackerInfoSections(
					state = trackerInfo,
					isAnime = isAnime,
					accent = accentColor,
					imageLoader = imageLoader,
					actions = actions,
				)
			}

			if (scrobblings.isNotEmpty() && !trackerMode) {
				ScrobblingSection(
					items = scrobblings,
					imageLoader = imageLoader,
					accent = accentColor,
					onMore = actions.onScrobblingMore,
					onCardClick = actions.onScrobblingCardClick,
					anime = isAnime,
				)
			}

			if (related.isNotEmpty()) {
				RelatedSection(
					items = related,
					accent = accentColor,
					onMore = { actions.onRelatedMore(manga) },
					onItemClick = actions.onRelatedClick,
				)
			}

			if (localSize > 0L) {
				LocalSizeRow(size = localSize, manga = manga, onClick = actions.onLocalClick)
			}

				Spacer(Modifier.height(28.dp))
			}
		}

			// Floating action dock: a "N chapters" pill stacked above the read FAB. Both pin to the
			// bottom-end and stay clear of the navigation bar; the modal chapters sheet draws its own
			// scrim over them, so they read as "behind" the sheet without any extra hide/show logic.
			if (dockOverride != null) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(end = SCREEN_PADDING, bottom = bottomContentPadding + 16.dp)
						.dockGlow(scheme.surface),
				) { dockOverride() }
			} else {
				ActionDock(
					historyInfo = historyInfo,
					isLoading = isLoading,
					accent = accentColor,
					actions = actions,
					isAnime = isAnime,
					episodeCount = if (isAnime) details?.allChapters?.size ?: 0 else 0,
					onEpisodesClick = { if (!actions.onEpisodesPageClick()) showEpisodes = true },
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(end = SCREEN_PADDING, bottom = bottomContentPadding + 16.dp)
						.dockGlow(scheme.surface),
				)
			}

		// The episodes live in a sheet of their own, like the chapters of a manga, so they are not buried under the
		// info cards. Picking one closes the sheet and starts playback.
		if (showEpisodes && isAnime && manga != null && details != null) {
			androidx.compose.material3.ModalBottomSheet(
				onDismissRequest = { showEpisodes = false },
				containerColor = scheme.surface,
			) {
				Column(
					modifier = Modifier
						.verticalScroll(rememberScrollState())
						.padding(bottom = bottomContentPadding + 24.dp),
				) {
					EpisodesSection(
						details = details,
						manga = manga,
						historyInfo = historyInfo,
						imageLoader = imageLoader,
						accent = accentColor,
						onEpisodeClick = { episode ->
							showEpisodes = false
							actions.onEpisodeClick(episode)
						},
					)
				}
			}
		}
	}
}

/**
 * Soft radial halo behind the action dock so the pill and FAB keep contrast over scrolling content.
 * Drawn from the dock's own bounds and deliberately spills past them — a round falloff has no
 * visible edge, unlike a rectangular scrim.
 */
internal fun Modifier.dockGlow(surface: Color) = drawBehind {
	val rx = size.width * 0.62f
	val ry = size.height * 0.60f
	val brush = Brush.radialGradient(
		0f to surface.copy(alpha = 0.92f),
		0.40f to surface.copy(alpha = 0.74f),
		0.72f to surface.copy(alpha = 0.32f),
		1f to Color.Transparent,
		center = center,
		radius = rx,
	)
	// Squash the circle into an ellipse so the halo hugs the (wide, short) dock instead of ballooning.
	scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
		drawCircle(brush = brush, radius = rx, center = center)
	}
}

@Composable
private fun ExpressiveBackdrop(
	url: String,
	/** Changes when a pull-to-refresh saved a new copy of the cover, so it is loaded again. */
	coverRefreshes: Int,
	manga: Manga?,
	imageLoader: ImageLoader,
	surface: Color,
	blurAmount: Int,
) {
	val ctx = LocalContext.current
	val request = remember(url, manga?.source, coverRefreshes) {
		ImageRequest.Builder(ctx)
			.data(url)
			.crossfade(true)
			.mangaSourceExtra(manga?.source)
			.build()
	}
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(480.dp),
	) {
		val blurDp = when (blurAmount) {
			0 -> 0.dp
			1 -> 20.dp
			else -> 40.dp
		}
		val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.dp) {
			Modifier.blur(blurDp)
		} else {
			Modifier
		}
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxSize()
				.then(blurMod),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to surface.copy(alpha = 0.30f),
						0.4f to surface.copy(alpha = 0.55f),
						0.78f to surface.copy(alpha = 0.94f),
						1f to surface,
					),
				),
		)
	}
}
