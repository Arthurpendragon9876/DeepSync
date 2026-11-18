package org.koitharu.kotatsu.explore.ui.meta

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.metadata.DISCOVER_GENRES
import org.koitharu.kotatsu.metadata.DiscoverFilters
import org.koitharu.kotatsu.metadata.DiscoverTags
import org.koitharu.kotatsu.metadata.DiscoverFormats
import org.koitharu.kotatsu.metadata.DiscoverSort
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle

/** Everything the metadata tab can do, so the screen itself holds no logic beyond layout. */
class ExploreMetaActions(
	val onSearch: (String) -> Unit,
	val onFilters: (DiscoverFilters) -> Unit,
	val onClearFilters: () -> Unit,
	val onOpen: (MetadataTitle) -> Unit,
	val onNextResults: () -> Unit,
	val onPreviousResults: () -> Unit,
	val onNextForYou: () -> Unit,
	val onPreviousForYou: () -> Unit,
	val onReshuffleSamples: () -> Unit,
	val onTrailer: (String) -> Unit,
	val onOpenStats: () -> Unit,
	val onOpenMetadataSettings: () -> Unit,
)

private const val COLUMNS = 3

/** The active Anime / Manga / Novel mode, so a card's badge can name an anime format instead of a manga kind. */
private val LocalMetaMode = androidx.compose.runtime.compositionLocalOf { HomeMode.MANGA }

/**
 * Explore -> Metadata. It lives inside Explore's NestedScrollView, so it is a plain Column (a lazy list
 * would be handed an unbounded height) and the poster grids are built from rows.
 */
@Composable
fun ExploreMetadataScreen(state: ExploreMetaState, imageLoader: ImageLoader, actions: ExploreMetaActions) {
	// This screen is hosted in a bare ComposeView with no Surface/Scaffold above it, so nothing provides a
	// content colour and every Text without an explicit colour (poster titles, section titles) falls back to
	// black. Provide the theme's onSurface so they follow dark mode.
	androidx.compose.runtime.CompositionLocalProvider(
		LocalMetaMode provides state.mode,
		LocalContentColor provides MaterialTheme.colorScheme.onSurface,
	) {
		ExploreMetadataContent(state, imageLoader, actions)
	}
}

@Composable
private fun ExploreMetadataContent(state: ExploreMetaState, imageLoader: ImageLoader, actions: ExploreMetaActions) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		if (!state.isAvailable) {
			UnavailableCard(actions)
			return@Column
		}
		if (state.filters.isActive) {
			ActiveFilters(state.filters, actions)
			ResultsSection(state, imageLoader, actions)
		} else {
			ForYouSection(state, imageLoader, actions)
			SamplesSection(state, imageLoader, actions)
			// Plain text with a diagonal arrow: no background, but it still reads as a link to another screen
			TextButton(onClick = actions.onOpenStats, modifier = Modifier.padding(horizontal = 4.dp)) {
				Text(if (state.mode == HomeMode.ANIME) "See your watching stats" else "See your reading stats")
				Spacer(Modifier.width(8.dp))
				Icon(painterResource(R.drawable.ic_arrow_outward), contentDescription = null, modifier = Modifier.size(18.dp))
			}
		}
	}
}

@Composable
private fun UnavailableCard(actions: ExploreMetaActions) {
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
	) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("Metadata search needs a tracker", style = MaterialTheme.typography.titleMedium)
			Text(
				"Discover browses AniList, MyAnimeList or MangaBaka. Pick one under Quick preferences " +
					"(the sliders button at the top), then come back.",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			FilledTonalButton(onClick = actions.onOpenMetadataSettings) { Text("Choose metadata source") }
		}
	}
}

// ---- filters ----------------------------------------------------------------------------------------------

internal fun DiscoverFilters.activeCount(): Int = include.size + exclude.size + listOf(status, format, year, season).count { it != null }

/**
 * What the current search / filter is made of. Genres the user picked in the filter sheet show as removable
 * chips (and only those: there is no strip of every genre); the rest is one summary line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilters(filters: DiscoverFilters, actions: ExploreMetaActions) {
	Column(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			val parts = buildList {
				if (filters.query.isNotBlank()) add("\u201C${filters.query.trim()}\u201D")
				filters.format?.let { add(it.lowercase().replaceFirstChar(Char::uppercase)) }
				filters.season?.let { add(seasonLabel(it)) }
				filters.status?.let { add(statusLabel(it)) }
				filters.year?.let { add(it.toString()) }
				add(filters.sort.label)
			}
			Text(
				text = parts.joinToString(" \u00B7 "),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			TextButton(onClick = actions.onClearFilters) { Text("Clear") }
		}
		if (filters.include.isNotEmpty() || filters.exclude.isNotEmpty()) {
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				filters.include.forEach { genre ->
					GenreChip(genre, genre) { actions.onFilters(filters.copy(include = filters.include - genre)) }
				}
				filters.exclude.forEach { genre ->
					GenreChip("\u2212 $genre", genre) { actions.onFilters(filters.copy(exclude = filters.exclude - genre)) }
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreChip(label: String, genre: String, onRemove: () -> Unit) {
	InputChip(
		selected = true,
		onClick = onRemove,
		shape = RoundedCornerShape(12.dp),
		label = { Text(label) },
		trailingIcon = {
			Icon(painterResource(R.drawable.ic_close_small), contentDescription = "Remove $genre", modifier = Modifier.size(16.dp))
		},
	)
}

private fun statusLabel(status: String) = when (status) {
	"RELEASING" -> "Releasing"
	"FINISHED" -> "Finished"
	"NOT_YET_RELEASED" -> "Upcoming"
	"HIATUS" -> "On hiatus"
	"CANCELLED" -> "Cancelled"
	else -> status
}

private val SEASONS = listOf("WINTER", "SPRING", "SUMMER", "FALL")

private fun seasonLabel(season: String) = season.lowercase().replaceFirstChar(Char::uppercase)

private val STATUSES = listOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "HIATUS", "CANCELLED")

/**
 * The filter sheet. Genre chips cycle include -> exclude -> off (tap again), like the tri-state filters
 * elsewhere in the app. Providers that cannot filter on something server-side (MAL and MangaBaka have no
 * genre filter) still honour it on the page they return, and the sheet says so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FiltersSheet(
	mode: HomeMode,
	provider: HomeMetadata,
	current: DiscoverFilters,
	onApply: (DiscoverFilters) -> Unit,
	onDismiss: () -> Unit,
) {
	var include by remember { mutableStateOf(current.include) }
	var exclude by remember { mutableStateOf(current.exclude) }
	var sort by remember { mutableStateOf(current.sort) }
	var status by remember { mutableStateOf(current.status) }
	var format by remember { mutableStateOf(current.format) }
	var season by remember { mutableStateOf(current.season) }
	var yearText by remember { mutableStateOf(current.year?.toString().orEmpty()) }
	val formats = remember(mode) { DiscoverFormats.of(mode) }

	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
		shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(
			modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text("Filters", style = MaterialTheme.typography.titleLarge)

			SheetLabel("Sort by")
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				DiscoverSort.entries.forEach { option ->
					FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
				}
			}

			// Genres and tags share the include / exclude sets: tap once to include, twice to exclude, again to clear
			val cycle = { term: String ->
				when {
					term in include -> {
						include = include - term
						exclude = exclude + term
					}

					term in exclude -> exclude = exclude - term
					else -> include = include + term
				}
			}
			SheetLabel("Genres (tap once to include, twice to exclude)")
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				DISCOVER_GENRES.forEach { genre -> TermChip(genre, genre in include, genre in exclude) { cycle(genre) } }
			}
			SheetLabel("Tags: BL, Yaoi, Yuri, Harem, Reverse Harem, Isekai and more")
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				DiscoverTags.all.forEach { tag ->
					TermChip(tag.label, tag.label in include, tag.label in exclude) { cycle(tag.label) }
				}
			}
			if (provider != HomeMetadata.ANILIST) {
				Text(
					"Genres, tags, year and format are applied to each page of results for this metadata source.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			if (formats.isNotEmpty()) {
				SheetLabel("Format")
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					formats.forEach { (key, label) ->
						FilterChip(
							selected = format == key,
							onClick = { format = if (format == key) null else key },
							label = { Text(label) },
						)
					}
				}
			}

			if (provider == HomeMetadata.ANILIST && mode == HomeMode.ANIME) {
				SheetLabel("Season (combine with a year)")
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					SEASONS.forEach { key ->
						FilterChip(
							selected = season == key,
							onClick = { season = if (season == key) null else key },
							label = { Text(seasonLabel(key)) },
						)
					}
				}
			}

			if (provider == HomeMetadata.ANILIST) {
				SheetLabel("Status")
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					STATUSES.forEach { key ->
						FilterChip(
							selected = status == key,
							onClick = { status = if (status == key) null else key },
							label = { Text(statusLabel(key)) },
						)
					}
				}
			}

			OutlinedTextField(
				value = yearText,
				onValueChange = { yearText = it.filter(Char::isDigit).take(4) },
				singleLine = true,
				label = { Text("Release year") },
				keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
				modifier = Modifier.fillMaxWidth(),
			)

			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				OutlinedButton(
					onClick = {
						include = emptySet()
						exclude = emptySet()
						status = null
						format = null
						season = null
						yearText = ""
						sort = DiscoverSort.POPULAR
					},
					modifier = Modifier.weight(1f),
				) { Text("Reset") }
				FilledTonalButton(
					onClick = {
						onApply(
							current.copy(
								include = include,
								exclude = exclude,
								sort = sort,
								status = status,
								format = format,
								season = season,
								year = yearText.toIntOrNull()?.takeIf { it in 1900..2100 },
							),
						)
					},
					modifier = Modifier.weight(1f),
				) { Text("Apply") }
			}
		}
	}
}

@Composable
private fun TermChip(label: String, included: Boolean, excluded: Boolean, onClick: () -> Unit) {
	FilterChip(
		selected = included || excluded,
		onClick = onClick,
		label = { Text(if (excluded) "\u2212 $label" else label) },
	)
}

@Composable
private fun SheetLabel(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(top = 4.dp),
	)
}

// ---- results & suggestions ---------------------------------------------------------------------------

@Composable
private fun ResultsSection(state: ExploreMetaState, imageLoader: ImageLoader, actions: ExploreMetaActions) {
	val listing = state.results
	SectionTitle("Results")
	ListingBody(
		listing = listing,
		emptyText = "Nothing matched. Try fewer filters or a different spelling.",
		isListView = state.isListView,
		imageLoader = imageLoader,
		onOpen = actions.onOpen,
	)
	PagerBar(listing, actions.onPreviousResults, actions.onNextResults)
}

@Composable
private fun ForYouSection(state: ExploreMetaState, imageLoader: ImageLoader, actions: ExploreMetaActions) {
	// No genre strip here: genres are picked in the filter sheet and then show as chips above the results.
	Text(
		text = if (state.feed == ExploreFeed.DISCOVER && state.forYouGenre != null) {
			"Picked from your top genre: ${state.forYouGenre}"
		} else {
			state.feed.subtitle
		},
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
	)
	ListingBody(
		listing = state.forYou,
		emptyText = "No suggestions here yet.",
		isListView = state.isListView,
		imageLoader = imageLoader,
		onOpen = actions.onOpen,
	)
	PagerBar(state.forYou, actions.onPreviousForYou, actions.onNextForYou)
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.titleMedium,
		fontWeight = FontWeight.SemiBold,
		modifier = Modifier.padding(horizontal = 8.dp),
	)
}

@Composable
private fun ListingBody(
	listing: MetaListing,
	emptyText: String,
	isListView: Boolean,
	imageLoader: ImageLoader,
	onOpen: (MetadataTitle) -> Unit,
) {
	when {
		listing.error != null -> Text(
			"Could not load: ${listing.error}",
			color = MaterialTheme.colorScheme.error,
			style = MaterialTheme.typography.bodyMedium,
			modifier = Modifier.padding(horizontal = 8.dp),
		)

		listing.isLoading && listing.items.isEmpty() || !listing.isLoaded && listing.items.isEmpty() ->
			Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}

		listing.items.isEmpty() -> Text(
			emptyText,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 8.dp),
		)

		isListView -> Column(
			verticalArrangement = Arrangement.spacedBy(10.dp),
			modifier = Modifier.padding(horizontal = 4.dp),
		) {
			listing.items.forEach { title -> ListRowCard(title, imageLoader, onOpen) }
		}

		else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
			listing.items.chunked(COLUMNS).forEach { row ->
				Row(
					modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					row.forEach { title ->
						Box(Modifier.weight(1f)) { PosterCard(title, imageLoader, onOpen) }
					}
					repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
				}
			}
		}
	}
}

/** Previous / page number / next, shown once there is more than one page to move between. */
@Composable
private fun PagerBar(listing: MetaListing, onPrevious: () -> Unit, onNext: () -> Unit) {
	if (listing.page == 1 && !listing.hasNext) return
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		OutlinedButton(onClick = onPrevious, enabled = listing.page > 1 && !listing.isLoading) { Text("Previous") }
		Text("Page ${listing.page}", style = MaterialTheme.typography.labelLarge)
		FilledTonalButton(onClick = onNext, enabled = listing.hasNext && !listing.isLoading) { Text("Next page") }
	}
}

@Composable
private fun PosterCard(title: MetadataTitle, imageLoader: ImageLoader, onOpen: (MetadataTitle) -> Unit) {
	Column(
		modifier = Modifier
			.clip(RoundedCornerShape(16.dp))
			.clickable { onOpen(title) },
	) {
		Box {
			AsyncImage(
				model = title.coverUrl,
				imageLoader = imageLoader,
				contentDescription = title.title,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(2f / 3f)
					.clip(RoundedCornerShape(16.dp))
					.background(MaterialTheme.colorScheme.surfaceContainerHigh),
			)
			val format = formatLabel(title, LocalMetaMode.current)
			if (format != null) {
				Badge(format, Modifier.align(Alignment.TopStart).padding(6.dp))
			}
		}
		Text(
			title.title,
			style = MaterialTheme.typography.bodySmall,
			fontWeight = FontWeight.SemiBold,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
		)
		val meta = listOfNotNull(title.score?.let { "\u2605 %.1f".format(it) }, title.year?.toString()).joinToString(" \u00B7 ")
		if (meta.isNotEmpty()) {
			Text(
				meta,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
			)
		}
	}
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
	Text(
		text,
		style = MaterialTheme.typography.labelSmall,
		fontWeight = FontWeight.Bold,
		color = Color.White,
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
			.background(Color.Black.copy(alpha = 0.6f))
			.padding(horizontal = 6.dp, vertical = 2.dp),
	)
}

private fun formatLabel(title: MetadataTitle, mode: HomeMode): String? {
	// The normalised kind is derived from the country of origin (JP = "Manga"), which is wrong for anime:
	// there the tracker's own format (TV, Movie, OVA...) is the label.
	if (mode == HomeMode.ANIME) {
		val f = title.format?.trim()?.takeIf { it.isNotEmpty() } ?: return "Anime"
		val words = f.replace('_', ' ').split(' ')
		return words.joinToString(" ") { w -> if (w.length <= 3) w.uppercase() else w.lowercase().replaceFirstChar(Char::uppercase) }
	}
	return (title.kind ?: title.format)?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase)
}

/** The list-view row: a small cover, then title, score / year / genres and the start of the synopsis. */
@Composable
private fun ListRowCard(title: MetadataTitle, imageLoader: ImageLoader, onOpen: (MetadataTitle) -> Unit) {
	Surface(
		shape = RoundedCornerShape(18.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onOpen(title) },
	) {
		Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			AsyncImage(
				model = title.coverUrl,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.width(76.dp)
					.aspectRatio(2f / 3f)
					.clip(RoundedCornerShape(12.dp)),
			)
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
				Text(title.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
				val line = listOfNotNull(
					title.score?.let { "\u2605 %.1f".format(it) },
					title.year?.toString(),
					formatLabel(title, LocalMetaMode.current),
				).joinToString(" \u00B7 ")
				if (line.isNotEmpty()) {
					Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
				}
				if (title.genres.isNotEmpty()) {
					Text(
						title.genres.take(3).joinToString(", "),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				val hook = hookOf(title.description.orEmpty())
				if (hook.isNotEmpty()) {
					Text(
						hook,
						style = MaterialTheme.typography.bodySmall,
						maxLines = 3,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

// ---- samples -----------------------------------------------------------------------------------------------

@Composable
private fun SamplesSection(state: ExploreMetaState, imageLoader: ImageLoader, actions: ExploreMetaActions) {
	if (state.samples.isEmpty() && !state.samplesLoading) return
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(Modifier.weight(1f)) {
			Text("Samples", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
			Text(
				"A taste of highly rated titles in your genres",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		TextButton(onClick = actions.onReshuffleSamples, enabled = !state.samplesLoading) { Text("Shuffle") }
	}
	if (state.samples.isEmpty()) {
		Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}
	LazyRow(
		contentPadding = PaddingValues(horizontal = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(state.samples, key = { it.id }) { title ->
			SampleCard(title, imageLoader, actions)
		}
	}
}

@Composable
private fun SampleCard(title: MetadataTitle, imageLoader: ImageLoader, actions: ExploreMetaActions) {
	Surface(
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier.width(300.dp),
	) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				AsyncImage(
					model = title.coverUrl,
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.width(84.dp)
						.aspectRatio(2f / 3f)
						.clip(RoundedCornerShape(12.dp)),
				)
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(
						title.title,
						style = MaterialTheme.typography.titleSmall,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					val line = listOfNotNull(
						title.score?.let { "\u2605 %.1f".format(it) },
						title.year?.toString(),
						title.genres.take(2).joinToString(", ").takeIf { it.isNotEmpty() },
					).joinToString(" · ")
					if (line.isNotEmpty()) {
						Text(
							line,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
					}
					Text(
						hookOf(title.description.orEmpty()),
						style = MaterialTheme.typography.bodySmall,
						maxLines = 5,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				title.trailerUrl?.let { url ->
					OutlinedButton(onClick = { actions.onTrailer(url) }) { Text("Trailer") }
				}
				FilledTonalButton(onClick = { actions.onOpen(title) }) { Text("Open") }
			}
		}
	}
}

/** A short teaser from a synopsis: markup stripped, cut at a sentence end near 220 characters. */
private fun hookOf(description: String): String {
	val clean = description
		.replace(Regex("<[^>]*>"), " ")
		.replace(Regex("\\(Source:[^)]*\\)", RegexOption.IGNORE_CASE), " ")
		.replace(Regex("\\s+"), " ")
		.trim()
	if (clean.length <= 220) return clean
	val cut = clean.lastIndexOfAny(charArrayOf('.', '!', '?'), startIndex = 220)
	return if (cut in 90..219) clean.substring(0, cut + 1) else clean.substring(0, 220).trimEnd() + "\u2026"
}
