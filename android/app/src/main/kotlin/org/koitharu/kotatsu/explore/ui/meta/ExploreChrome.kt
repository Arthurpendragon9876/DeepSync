package org.koitharu.kotatsu.explore.ui.meta

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.parsers.model.MangaSource

/** One installed source in the feed pill's menu. */
class ExploreSourceEntry(val source: MangaSource, val title: String)

class ExploreHeaderActions(
	val onSelectTab: (Int) -> Unit,
	val onSelectFeed: (ExploreFeed) -> Unit,
	val onOpenSource: (ExploreSourceEntry) -> Unit,
	val onFilters: (org.koitharu.kotatsu.metadata.DiscoverFilters) -> Unit,
	val onOpenSettings: () -> Unit,
	val onSearch: () -> Unit,
	val onLanguageFilter: () -> Unit,
	val onOpenExtensionSettings: () -> Unit,
	val onDiscoverDefault: (Boolean) -> Unit,
	val onSetListView: (Boolean) -> Unit,
	val onAutoUpdateExtensions: (Boolean) -> Unit,
	val onHideAdult: (Boolean) -> Unit,
	val onMetadataFromTracker: (Boolean) -> Unit,
	val onProvider: (HomeMetadata) -> Unit,
)

private val FEED_ICONS = mapOf(
	ExploreFeed.DISCOVER to R.drawable.ic_sparkles,
	ExploreFeed.TRENDING to R.drawable.ic_local_fire,
	ExploreFeed.POPULAR to R.drawable.ic_star_rate,
	ExploreFeed.UPCOMING to R.drawable.ic_arrow_forward,
)

/**
 * The top of Explore, one row: the feed pill (AniList feeds + installed sources), search, filters and quick
 * preferences. The app's settings gear lives inside the quick preferences sheet. The Sources / Discover
 * switch and the grid / list toggle are in the sheet too, so the content starts right below this row.
 * The app-wide search bar is hidden on this tab (see MainActivity.adjustAppbar); the search icon opens it.
 */
@Composable
fun ExploreHeader(
	state: ExploreMetaState,
	tab: Int,
	sources: List<ExploreSourceEntry>,
	prefs: ExploreQuickPrefs,
	actions: ExploreHeaderActions,
) {
	var showFilters by remember { mutableStateOf(false) }
	var showQuickPrefs by remember { mutableStateOf(false) }
	Column(
		modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
			FeedPill(state.feed, tab, sources, actions)
			Spacer(Modifier.weight(1f))
			HeaderIcon(R.drawable.ic_search, "Search", actions.onSearch)
			if (tab == 1 && state.isAvailable) {
				Box {
					HeaderIcon(R.drawable.ic_filter_funnel, "Filters") { showFilters = true }
					if (state.filters.activeCount() > 0) {
						Box(
							Modifier
								.align(Alignment.TopEnd)
								.padding(top = 8.dp, end = 8.dp)
								.size(9.dp)
								.clip(CircleShape)
								.background(MaterialTheme.colorScheme.primary),
						)
					}
				}
			}
			HeaderIcon(R.drawable.ic_tune, "Quick preferences") { showQuickPrefs = true }
		}
	}
	if (showFilters) {
		FiltersSheet(
			mode = state.mode,
			provider = state.provider,
			current = state.filters,
			onApply = {
				showFilters = false
				actions.onFilters(it)
			},
			onDismiss = { showFilters = false },
		)
	}
	if (showQuickPrefs) {
		QuickPrefsSheet(
			prefs = prefs,
			tab = tab,
			discoverLabel = discoverTabLabel(state.provider),
			isListView = state.isListView,
			actions = actions,
			onDismiss = { showQuickPrefs = false },
		)
	}
}

@Composable
private fun HeaderIcon(icon: Int, description: String, onClick: () -> Unit) {
	IconButton(onClick = onClick) {
		Icon(
			painter = painterResource(icon),
			contentDescription = description,
			tint = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.size(22.dp),
		)
	}
}

// ---- feed pill + menu ---------------------------------------------------------------------------------

@Composable
private fun FeedPill(feed: ExploreFeed, tab: Int, sources: List<ExploreSourceEntry>, actions: ExploreHeaderActions) {
	var expanded by remember { mutableStateOf(false) }
	Box {
		Surface(
			onClick = { expanded = true },
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
		) {
			Row(
				Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Icon(
					painter = painterResource(if (tab == 1) FEED_ICONS.getValue(feed) else R.drawable.ic_explore_normal),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(16.dp),
				)
				Text(
					text = (if (tab == 1) feed.label else "Sources").uppercase(),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.ExtraBold,
					letterSpacing = 1.sp,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Icon(
					painter = painterResource(R.drawable.ic_expand_more),
					contentDescription = null,
					modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
				)
			}
		}
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			shape = RoundedCornerShape(24.dp),
			modifier = Modifier.width(300.dp),
		) {
			MenuHeader("Tracker feeds")
			ExploreFeed.entries.forEach { option ->
				val selected = tab == 1 && option == feed
				FeedRow(
					icon = FEED_ICONS.getValue(option),
					title = option.label,
					subtitle = option.subtitle,
					selected = selected,
					onClick = {
						expanded = false
						actions.onSelectFeed(option)
					},
				)
			}
			if (sources.isNotEmpty()) {
				HorizontalDivider(Modifier.padding(vertical = 8.dp))
				MenuHeader("Installed sources (${sources.size})")
				Column(Modifier.height((sources.size.coerceAtMost(5) * 56).dp).verticalScroll(rememberScrollState())) {
					sources.forEach { entry ->
						SourceRow(entry) {
							expanded = false
							actions.onOpenSource(entry)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun MenuHeader(text: String) {
	Text(
		text.uppercase(),
		style = MaterialTheme.typography.labelSmall,
		fontWeight = FontWeight.Bold,
		letterSpacing = 1.sp,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
	)
}

@Composable
private fun FeedRow(icon: Int, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = 8.dp, vertical = 2.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
			.clickable(onClick = onClick)
			.padding(horizontal = 8.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Box(
			Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painter = painterResource(icon),
				contentDescription = null,
				tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
		}
		Column(Modifier.weight(1f)) {
			Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
			Text(
				subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (selected) {
			Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
		}
	}
}

@Composable
private fun SourceRow(entry: ExploreSourceEntry, onClick: () -> Unit) {
	Row(
		Modifier
			.fillMaxWidth()
			.height(56.dp)
			.padding(horizontal = 8.dp, vertical = 2.dp)
			.clip(RoundedCornerShape(12.dp))
			.clickable(onClick = onClick)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Box(
			Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
			contentAlignment = Alignment.Center,
		) {
			Text(
				entry.title.firstOrNull()?.uppercase().orEmpty(),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Black,
				color = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		}
		Text(
			entry.title,
			style = MaterialTheme.typography.titleSmall,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
	}
}

// ---- Sources / Discover label ---------------------------------------------------------------------------

private fun discoverTabLabel(provider: HomeMetadata) = when (provider) {
	HomeMetadata.ANILIST -> "AniList feed"
	HomeMetadata.MAL -> "MyAnimeList feed"
	HomeMetadata.MANGABAKA -> "MangaBaka feed"
	HomeMetadata.SOURCES -> "Discover"
}

// ---- quick preferences ---------------------------------------------------------------------------------

/**
 * Explore's quick preferences. Every switch here is a real app setting (so changing it here changes it in
 * Settings too); the full Settings screen is one tap away at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickPrefsSheet(
	prefs: ExploreQuickPrefs,
	tab: Int,
	discoverLabel: String,
	isListView: Boolean,
	actions: ExploreHeaderActions,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
		shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Icon(painterResource(R.drawable.ic_tune), null, tint = MaterialTheme.colorScheme.primary)
				Text("Quick preferences", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
				HeaderIcon(R.drawable.ic_settings, "App settings") {
					onDismiss()
					actions.onOpenSettings()
				}
			}

			if (prefs.providers.isNotEmpty()) {
				Text(
					"Metadata provider",
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 4.dp),
				)
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					prefs.providers.forEach { provider ->
						FilterChip(
							selected = provider == prefs.provider,
							onClick = { actions.onProvider(provider) },
							label = { Text(providerName(provider)) },
						)
					}
				}
			}

			PrefLabel("Explore shows")
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				FilterChip(
					selected = tab == 0,
					onClick = {
						actions.onSelectTab(0)
						actions.onDiscoverDefault(false)
					},
					label = { Text("Sources") },
					leadingIcon = { Icon(painterResource(R.drawable.ic_explore_normal), null, Modifier.size(18.dp)) },
				)
				FilterChip(
					selected = tab == 1,
					onClick = {
						actions.onSelectTab(1)
						actions.onDiscoverDefault(true)
					},
					label = { Text(discoverLabel) },
					leadingIcon = { Icon(painterResource(R.drawable.ic_sparkles), null, Modifier.size(18.dp)) },
				)
			}
			Text(
				"What Explore opens on, and what it shows right now",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			PrefLabel("Layout")
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				FilterChip(
					selected = !isListView,
					onClick = { actions.onSetListView(false) },
					label = { Text("Grid") },
					leadingIcon = { Icon(painterResource(R.drawable.ic_grid), null, Modifier.size(18.dp)) },
				)
				FilterChip(
					selected = isListView,
					onClick = { actions.onSetListView(true) },
					label = { Text("List") },
					leadingIcon = { Icon(painterResource(R.drawable.ic_list), null, Modifier.size(18.dp)) },
				)
			}

			PrefSwitchRow(
				title = "Covers and info from tracker",
				subtitle = "Title pages open straight from tracker data; the source loads after",
				checked = prefs.metadataFromTracker,
				onChange = actions.onMetadataFromTracker,
			)
			PrefSwitchRow(
				title = "Hide adult titles",
				subtitle = "Keeps 18+ titles out of Home and Discover",
				checked = prefs.hideAdult,
				onChange = actions.onHideAdult,
			)
			PrefSwitchRow(
				title = "Auto-update extensions",
				subtitle = "Fetch the latest extension builds in the background",
				checked = prefs.autoUpdateExtensions,
				onChange = actions.onAutoUpdateExtensions,
			)

			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
				OutlinedButton(
					onClick = {
						onDismiss()
						actions.onOpenExtensionSettings()
					},
					modifier = Modifier.weight(1f),
				) { Text("Extension settings") }
				if (tab == 0) {
					OutlinedButton(
						onClick = {
							onDismiss()
							actions.onLanguageFilter()
						},
						modifier = Modifier.weight(1f),
					) { Text("Language filter") }
				}
			}
		}
	}
}

@Composable
private fun PrefLabel(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(top = 4.dp),
	)
}

@Composable
private fun PrefSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			Modifier.clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
				Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Switch(checked = checked, onCheckedChange = onChange)
		}
	}
}

internal fun providerName(provider: HomeMetadata) = when (provider) {
	HomeMetadata.ANILIST -> "AniList"
	HomeMetadata.MAL -> "MyAnimeList"
	HomeMetadata.MANGABAKA -> "MangaBaka"
	HomeMetadata.SOURCES -> "Sources"
}
