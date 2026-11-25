package org.koitharu.kotatsu.settings.modes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.home.settings.HomeLayoutSettingsFragment
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.home.ui.model.ModeOverrideArea
import org.koitharu.kotatsu.home.ui.model.SuggestionsSource
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SegmentedSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsGroupScope
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import javax.inject.Inject

/**
 * Settings > Override global defaults. Every area (Homepage, Explore, Services, Favourites, Updates & Feed,
 * History, Suggestions) has a switch: off, the area uses one shared value for all modes; on, Manga, Novel and
 * Streaming each keep their own copy. The mode picked at the top is the one being edited, so no mode has to be
 * entered first to change it.
 */
@AndroidEntryPoint
class ModeOverridesSettingsFragment : BaseComposeSettingsFragment(R.string.ds_mode_overrides) {

	@Inject
	lateinit var settings: AppSettings

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				ModeOverridesScreen(
					settings = settings,
					onOpenHomeLayout = {
						(activity as? SettingsActivity)?.openFragment(
							HomeLayoutSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
				)
			}
		}
	}
}

@Composable
private fun ModeOverridesScreen(
	settings: AppSettings,
	onOpenHomeLayout: () -> Unit,
) {
	// Everything below reads straight from the settings, so any write bumps this to redraw.
	var version by remember { mutableIntStateOf(0) }
	var editMode by remember { mutableStateOf(settings.homeMode) }

	// Read so that a write redraws the screen
	val redraw = version
	val modes = HomeMode.entries
	val modeLabels = listOf(
		stringResource(R.string.ds_mode_manga),
		stringResource(R.string.ds_mode_novel),
		stringResource(R.string.ds_mode_streaming),
	)
	val modeName = modeLabels[modes.indexOf(editMode).coerceAtLeast(0)]

	fun overridden(area: ModeOverrideArea) = settings.isModeOverridden(area)

	fun bool(area: ModeOverrideArea, key: String, default: Boolean) =
		settings.getModeBoolean(area, key, default, editMode)

	fun setBool(area: ModeOverrideArea, key: String, value: Boolean) {
		settings.setModeBoolean(area, key, editMode, value)
		version++
	}

	/** The switch that turns an area's per-mode copies on or off. */
	fun SettingsGroupScope.areaSwitch(area: ModeOverrideArea, icon: Int) {
		item { pos ->
			SwitchSettingsItem(
				title = stringResource(area.titleRes),
				subtitle = if (overridden(area)) {
					stringResource(R.string.ds_override_editing, modeName)
				} else {
					stringResource(area.descRes)
				},
				checked = overridden(area),
				onCheckedChange = {
					settings.setModeOverridden(area, it)
					version++
				},
				icon = icon,
				shape = pos.shape,
			)
		}
	}

	SettingsScaffold {
		// Which mode the switches below edit
		item {
			SettingsGroup {
				item { pos ->
					SegmentedSettingsItem(
						title = stringResource(R.string.ds_override_mode_title),
						subtitle = stringResource(R.string.ds_override_mode_desc),
						labels = modeLabels,
						selectedIndex = modes.indexOf(editMode).coerceAtLeast(0),
						onSelected = { editMode = modes[it] },
						icon = R.drawable.ic_services,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Homepage: the layout editor itself stays where it was; it edits the mode it is opened in
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_home)) {
				areaSwitch(ModeOverrideArea.HOME, R.drawable.ic_home_rounded)
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.ds_prefs_layout),
						subtitle = stringResource(R.string.ds_override_home_open),
						icon = R.drawable.ic_home_rounded,
						shape = pos.shape,
						onClick = onOpenHomeLayout,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Explore
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_explore)) {
				areaSwitch(ModeOverrideArea.EXPLORE, R.drawable.ic_manga_source)
				if (overridden(ModeOverrideArea.EXPLORE)) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_explore_discover),
							subtitle = stringResource(R.string.ds_override_explore_discover_desc),
							checked = bool(ModeOverrideArea.EXPLORE, AppSettings.KEY_EXPLORE_DISCOVER_DEFAULT, true),
							onCheckedChange = { setBool(ModeOverrideArea.EXPLORE, AppSettings.KEY_EXPLORE_DISCOVER_DEFAULT, it) },
							icon = R.drawable.ic_manga_source,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_explore_list),
							subtitle = stringResource(R.string.ds_override_explore_list_desc),
							checked = bool(ModeOverrideArea.EXPLORE, AppSettings.KEY_EXPLORE_LIST_VIEW, false),
							onCheckedChange = { setBool(ModeOverrideArea.EXPLORE, AppSettings.KEY_EXPLORE_LIST_VIEW, it) },
							icon = R.drawable.ic_manga_source,
							shape = pos.shape,
						)
					}
					if (editMode != HomeMode.ANIME) {
						item { pos ->
							SwitchSettingsItem(
								title = stringResource(R.string.ds_override_explore_novel_first),
								subtitle = stringResource(R.string.ds_override_explore_novel_first_desc),
								checked = bool(ModeOverrideArea.EXPLORE, AppSettings.KEY_EXPLORE_NOVEL_TAB_FIRST, false),
								onCheckedChange = { setBool(ModeOverrideArea.EXPLORE, AppSettings.KEY_EXPLORE_NOVEL_TAB_FIRST, it) },
								icon = R.drawable.ic_manga_source,
								shape = pos.shape,
							)
						}
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Services: trackers, statistics and related titles
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_services)) {
				areaSwitch(ModeOverrideArea.SERVICES, R.drawable.ic_services)
				if (overridden(ModeOverrideArea.SERVICES)) {
					val area = ModeOverrideArea.SERVICES
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.related_manga),
							subtitle = stringResource(R.string.related_manga_summary),
							checked = bool(area, AppSettings.KEY_RELATED_MANGA, true),
							onCheckedChange = { setBool(area, AppSettings.KEY_RELATED_MANGA, it) },
							icon = R.drawable.ic_heart_outline,
							shape = pos.shape,
						)
					}
					item { pos ->
						// Streaming records watch time, the other two modes reading time
						val key = if (editMode == HomeMode.ANIME) AppSettings.KEY_WATCH_STATS_ENABLED else AppSettings.KEY_STATS_ENABLED
						SwitchSettingsItem(
							title = stringResource(if (editMode == HomeMode.ANIME) R.string.watching_stats else R.string.reading_stats),
							checked = bool(area, key, true),
							onCheckedChange = { setBool(area, key, it) },
							icon = R.drawable.ic_timelapse,
							shape = pos.shape,
						)
					}
					if (editMode != HomeMode.ANIME) {
						item { pos ->
							SwitchSettingsItem(
								title = stringResource(R.string.reading_time_estimation),
								subtitle = stringResource(R.string.reading_time_estimation_summary),
								checked = bool(area, AppSettings.KEY_READING_TIME, true),
								onCheckedChange = { setBool(area, AppSettings.KEY_READING_TIME, it) },
								icon = R.drawable.ic_timelapse,
								shape = pos.shape,
							)
						}
					}
					item { pos ->
						val choices = AppSettings.TrackingChoice.entries
						SegmentedSettingsItem(
							title = stringResource(R.string.ds_override_tracking_title),
							subtitle = stringResource(R.string.ds_override_tracking_desc),
							labels = listOf(
								stringResource(R.string.ds_override_tracking_ask),
								stringResource(R.string.ds_override_tracking_closest),
								stringResource(R.string.ds_override_tracking_exact),
							),
							selectedIndex = choices.indexOf(settings.getModeTrackingChoice(editMode)),
							onSelected = {
								settings.setModeTrackingChoice(editMode, choices[it])
								version++
							},
							icon = R.drawable.ic_services,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_tracking_sync),
							subtitle = stringResource(R.string.ds_override_tracking_sync_desc),
							checked = bool(area, AppSettings.KEY_SCROBBLING_PROGRESS_SYNC, true),
							onCheckedChange = { setBool(area, AppSettings.KEY_SCROBBLING_PROGRESS_SYNC, it) },
							icon = R.drawable.ic_services,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_tracking_autosync),
							subtitle = stringResource(R.string.ds_override_tracking_autosync_desc),
							checked = bool(area, AppSettings.KEY_TRACKER_AUTO_SYNC, true),
							onCheckedChange = { setBool(area, AppSettings.KEY_TRACKER_AUTO_SYNC, it) },
							icon = R.drawable.ic_services,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Favourites
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_favourites)) {
				areaSwitch(ModeOverrideArea.FAVOURITES, R.drawable.ic_heart_outline)
				if (overridden(ModeOverrideArea.FAVOURITES)) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.show_empty_mode_categories),
							subtitle = stringResource(R.string.show_empty_mode_categories_summary),
							checked = bool(ModeOverrideArea.FAVOURITES, AppSettings.KEY_SHOW_EMPTY_MODE_CATEGORIES, false),
							onCheckedChange = { setBool(ModeOverrideArea.FAVOURITES, AppSettings.KEY_SHOW_EMPTY_MODE_CATEGORIES, it) },
							icon = R.drawable.ic_heart_outline,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Updates and Feed
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_updates)) {
				areaSwitch(ModeOverrideArea.UPDATES, R.drawable.ic_feed)
				if (overridden(ModeOverrideArea.UPDATES)) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_updates_page),
							subtitle = stringResource(R.string.ds_override_all_modes_desc),
							checked = bool(ModeOverrideArea.UPDATES, AppSettings.KEY_UPDATES_ALL_MODES, true),
							onCheckedChange = { setBool(ModeOverrideArea.UPDATES, AppSettings.KEY_UPDATES_ALL_MODES, it) },
							icon = R.drawable.ic_feed,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_feed_page),
							subtitle = stringResource(R.string.ds_override_all_modes_desc),
							checked = bool(ModeOverrideArea.UPDATES, AppSettings.KEY_FEED_ALL_MODES, false),
							onCheckedChange = { setBool(ModeOverrideArea.UPDATES, AppSettings.KEY_FEED_ALL_MODES, it) },
							icon = R.drawable.ic_feed,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// History
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_history)) {
				areaSwitch(ModeOverrideArea.HISTORY, R.drawable.ic_history)
				if (overridden(ModeOverrideArea.HISTORY)) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.ds_override_history_all),
							subtitle = stringResource(R.string.ds_override_history_all_desc),
							checked = bool(ModeOverrideArea.HISTORY, AppSettings.KEY_HISTORY_ALL_MODES, false),
							onCheckedChange = { setBool(ModeOverrideArea.HISTORY, AppSettings.KEY_HISTORY_ALL_MODES, it) },
							icon = R.drawable.ic_history,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Suggestions: where the titles come from
		item {
			SettingsGroup(title = stringResource(R.string.ds_override_suggestions)) {
				areaSwitch(ModeOverrideArea.SUGGESTIONS, R.drawable.ic_suggestion)
				if (overridden(ModeOverrideArea.SUGGESTIONS)) {
					item { pos ->
						val sources = SuggestionsSource.entries
						SegmentedSettingsItem(
							title = stringResource(R.string.ds_override_suggestions_source),
							subtitle = stringResource(R.string.ds_override_suggestions_source_desc),
							labels = sources.map { stringResource(it.titleRes) },
							selectedIndex = sources.indexOf(settings.suggestionsSourceFor(editMode)),
							onSelected = {
								settings.setSuggestionsSourceFor(editMode, sources[it])
								version++
							},
							icon = R.drawable.ic_suggestion,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
