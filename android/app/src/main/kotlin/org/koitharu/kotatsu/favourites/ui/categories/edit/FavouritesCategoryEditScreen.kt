package org.koitharu.kotatsu.favourites.ui.categories.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag
import org.koitharu.kotatsu.favourites.domain.CategoryUpdateInterval
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.sort.SortRow
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import androidx.compose.material3.SheetValue

/**
 * Content of [FavouritesCategoryEditActivity]. The activity still owns the toolbar and its Save
 * button; everything below it lives here.
 */
@Composable
fun FavouritesCategoryEditScreen(
	name: String,
	onNameChange: (String) -> Unit,
	sortOrder: ListSortOrder,
	onSortOrderChange: (ListSortOrder) -> Unit,
	isTrackerSectionVisible: Boolean,
	isTrackerEnabled: Boolean,
	onTrackerChange: (Boolean) -> Unit,
	isDownloadEnabled: Boolean,
	onDownloadChange: (Boolean) -> Unit,
	isShelfEnabled: Boolean,
	onShelfChange: (Boolean) -> Unit,
	parentOptions: List<FavouriteCategory>,
	parentId: Long?,
	onParentChange: (Long?) -> Unit,
	updateInterval: CategoryUpdateInterval?,
	onUpdateIntervalChange: (CategoryUpdateInterval?) -> Unit,
	mediaTag: CategoryMediaTag?,
	onMediaTagChange: (CategoryMediaTag?) -> Unit,
	error: String?,
	enabled: Boolean,
	bottomInset: Dp,
) {
	var isSortSheetVisible by rememberSaveable { mutableStateOf(false) }
	var isParentSheetVisible by rememberSaveable { mutableStateOf(false) }
	var isIntervalSheetVisible by rememberSaveable { mutableStateOf(false) }
	var isTagSheetVisible by rememberSaveable { mutableStateOf(false) }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(start = 16.dp, end = 16.dp, top = 8.dp)
			.padding(bottom = bottomInset + 24.dp),
	) {
		OutlinedTextField(
			value = name,
			onValueChange = { if (it.length <= NAME_MAX_LENGTH) onNameChange(it) },
			modifier = Modifier.fillMaxWidth(),
			label = { Text(stringResource(R.string.name)) },
			singleLine = true,
			enabled = enabled,
			shape = RoundedCornerShape(24.dp),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
		)
		Spacer(Modifier.height(16.dp))
		SettingsItem(
			title = stringResource(R.string.sort_order),
			subtitle = sortOrder.displayLabel(),
			icon = R.drawable.ic_sort,
			shape = RoundedCornerShape(24.dp),
			enabled = enabled,
			modifier = Modifier.fillMaxWidth(),
			onClick = { isSortSheetVisible = true },
		)
		Spacer(Modifier.height(8.dp))
		SettingsGroup(
			modifier = Modifier.fillMaxWidth(),
			title = stringResource(R.string.category_media_tag),
		) {
			item { position ->
				SettingsItem(
					title = stringResource(R.string.category_media_tag),
					subtitle = mediaTag.label(),
					icon = R.drawable.ic_label,
					shape = position.shape,
					enabled = enabled,
					modifier = Modifier.fillMaxWidth(),
					onClick = { isTagSheetVisible = true },
				)
			}
			// A category that already has subcategories cannot be nested itself, so it offers no parents.
			if (parentOptions.isNotEmpty() || parentId != null) {
				item { position ->
					SettingsItem(
						title = stringResource(R.string.category_parent),
						subtitle = parentOptions.firstOrNull { it.id == parentId }?.title
							?: stringResource(R.string.category_parent_none),
						icon = R.drawable.ic_folder_file,
						shape = position.shape,
						enabled = enabled,
						modifier = Modifier.fillMaxWidth(),
						onClick = { isParentSheetVisible = true },
					)
				}
			}
		}
		Spacer(Modifier.height(8.dp))
		SettingsGroup(
			modifier = Modifier.fillMaxWidth(),
			title = stringResource(R.string.options),
		) {
			if (isTrackerSectionVisible) {
				item { position ->
					SwitchSettingsItem(
						title = stringResource(R.string.check_for_new_chapters),
						subtitle = stringResource(R.string.category_tracker_summary),
						icon = R.drawable.ic_updated,
						checked = isTrackerEnabled,
						onCheckedChange = onTrackerChange,
						shape = position.shape,
						enabled = enabled,
						modifier = Modifier.fillMaxWidth(),
					)
				}
				item { position ->
					SwitchSettingsItem(
						title = stringResource(R.string.download_new_chapters),
						subtitle = stringResource(R.string.category_download_summary),
						icon = R.drawable.ic_download,
						checked = isDownloadEnabled,
						onCheckedChange = onDownloadChange,
						shape = position.shape,
						// Downloading new chapters is meaningless while the category is not tracked.
						enabled = enabled && isTrackerEnabled,
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
			if (isTrackerSectionVisible) {
				item { position ->
					SettingsItem(
						title = stringResource(R.string.category_update_interval),
						subtitle = updateInterval.label(),
						icon = R.drawable.ic_timer,
						shape = position.shape,
						// Nothing is checked for an untracked category, so there is no schedule to pick.
						enabled = enabled && isTrackerEnabled,
						modifier = Modifier.fillMaxWidth(),
						onClick = { isIntervalSheetVisible = true },
					)
				}
			}
			item { position ->
				SwitchSettingsItem(
					title = stringResource(R.string.show_on_shelf),
					subtitle = stringResource(R.string.category_shelf_summary),
					icon = R.drawable.ic_visibility,
					checked = isShelfEnabled,
					onCheckedChange = onShelfChange,
					shape = position.shape,
					enabled = enabled,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
		if (!error.isNullOrEmpty()) {
			Spacer(Modifier.height(16.dp))
			Text(
				text = error,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}

	if (isTagSheetVisible) {
		ChoiceSheet(
			title = stringResource(R.string.category_media_tag),
			options = listOf<CategoryMediaTag?>(null) + CategoryMediaTag.entries,
			label = { it.label() },
			selected = mediaTag,
			onSelect = onMediaTagChange,
			onDismiss = { isTagSheetVisible = false },
		)
	}
	if (isIntervalSheetVisible) {
		ChoiceSheet(
			title = stringResource(R.string.category_update_interval),
			options = listOf<CategoryUpdateInterval?>(null) + CategoryUpdateInterval.entries,
			label = { it.label() },
			selected = updateInterval,
			onSelect = onUpdateIntervalChange,
			onDismiss = { isIntervalSheetVisible = false },
		)
	}
	if (isParentSheetVisible) {
		ChoiceSheet(
			title = stringResource(R.string.category_parent),
			options = listOf<Long?>(null) + parentOptions.map { it.id },
			label = { id ->
				parentOptions.firstOrNull { it.id == id }?.title ?: stringResource(R.string.category_parent_none)
			},
			selected = parentId,
			onSelect = onParentChange,
			onDismiss = { isParentSheetVisible = false },
		)
	}
	if (isSortSheetVisible) {
		SortOrderSheet(
			current = sortOrder,
			onSelect = onSortOrderChange,
			onDismiss = { isSortSheetVisible = false },
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortOrderSheet(
	current: ListSortOrder,
	onSelect: (ListSortOrder) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
		shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(Modifier.padding(bottom = 24.dp)) {
			Text(
				text = stringResource(R.string.sort_order),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
				modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
			)
			ListSortOrder.FAVORITES.forEach { type ->
				SortRow(
					title = stringResource(type.titleResId),
					isSelected = current.type == type,
					isAscending = current.isAscending,
					// Mihon's rule: tapping the active column flips direction, tapping another keeps it.
					onClick = {
						val isAscending = if (current.type == type) !current.isAscending else current.isAscending
						onSelect(type.toSortOrder(isAscending))
					},
				)
			}
		}
	}
}

/** A single-choice bottom sheet; the chosen row is highlighted and picking one closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSheet(
	title: String,
	options: List<T>,
	label: @Composable (T) -> String,
	selected: T,
	onSelect: (T) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
		shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(
			Modifier
				.verticalScroll(rememberScrollState())
				.padding(bottom = 24.dp),
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
				modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
			)
			options.forEach { option ->
				SortRow(
					title = label(option),
					isSelected = option == selected,
					isAscending = true,
					hasDirection = false,
					onClick = {
						onSelect(option)
						onDismiss()
					},
				)
			}
		}
	}
}

@Composable
private fun CategoryMediaTag?.label(): String = stringResource(
	when (this) {
		null -> R.string.category_tag_auto
		CategoryMediaTag.ANY -> R.string.category_tag_any
		CategoryMediaTag.MANGA -> R.string.category_tag_manga
		CategoryMediaTag.NOVEL -> R.string.category_tag_novel
		CategoryMediaTag.ANIME -> R.string.category_tag_anime
		CategoryMediaTag.TV_MOVIES -> R.string.category_tag_tv_movies
	},
)

@Composable
private fun CategoryUpdateInterval?.label(): String = stringResource(
	when (this) {
		null -> R.string.update_interval_global
		CategoryUpdateInterval.DAILY -> R.string.update_interval_daily
		CategoryUpdateInterval.WEEKLY -> R.string.update_interval_weekly
		CategoryUpdateInterval.PAUSED -> R.string.update_interval_paused
	},
)

/** "Date added (descending)" — the direction has to be spelled out wherever the arrow isn't shown. */
@Composable
private fun ListSortOrder.displayLabel(): String = stringResource(
	if (isAscending) R.string.sort_ascending else R.string.sort_descending,
	stringResource(titleResId),
)

private const val NAME_MAX_LENGTH = 120
private val SHEET_CORNER = 28.dp
