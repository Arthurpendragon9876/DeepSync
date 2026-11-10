package org.koitharu.kotatsu.home.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.home.ui.model.HomeBannerStyle
import org.koitharu.kotatsu.settings.compose.ChoiceDialog
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HomeBannerSettingsEntryPoint {
	fun settings(): AppSettings

	fun favourites(): FavouritesRepository
}

/**
 * The Home options for Settings > Appearance: the banner style, auto-rotate, and which favourites
 * categories show up as carousels on Home.
 * Call it from a LazyColumn item of the Appearance screen: `item { HomeBannerSettingsGroup() }`.
 */
@Composable
fun HomeBannerSettingsGroup() {
	val context = LocalContext.current
	val entryPoint = remember {
		EntryPointAccessors.fromApplication(context.applicationContext, HomeBannerSettingsEntryPoint::class.java)
	}
	val settings = remember { entryPoint.settings() }
	var style by remember { mutableStateOf(settings.homeBannerStyle) }
	var rotate by rememberBooleanPref(AppSettings.KEY_HOME_BANNER_ROTATE, true)
	var showDialog by remember { mutableStateOf(false) }
	val styles = HomeBannerStyle.values().toList()

	if (showDialog) {
		ChoiceDialog(
			title = stringResource(R.string.ds_banner_style),
			entries = styles.map { stringResource(it.titleRes) + ": " + stringResource(it.descRes) },
			selectedIndex = styles.indexOf(style),
			onSelect = {
				style = styles[it]
				settings.homeBannerStyle = styles[it]
			},
			onDismiss = { showDialog = false },
		)
	}
	SettingsGroup(title = stringResource(R.string.ds_banner_style)) {
		item { pos ->
			SettingsItem(
				title = stringResource(R.string.ds_banner_style),
				subtitle = stringResource(style.titleRes) + ": " + stringResource(style.descRes),
				icon = R.drawable.ic_appearance,
				shape = pos.shape,
				onClick = { showDialog = true },
			)
		}
		item { pos ->
			SwitchSettingsItem(
				title = stringResource(R.string.ds_banner_rotate),
				subtitle = stringResource(R.string.ds_banner_rotate_summary),
				checked = rotate,
				onCheckedChange = { rotate = it },
				icon = R.drawable.ic_appearance,
				shape = pos.shape,
			)
		}
	}
	Spacer(Modifier.height(8.dp).fillMaxWidth())

	// Favourites categories as carousels on Home
	val categories by remember { entryPoint.favourites().observeCategories() }.collectAsState(initial = emptyList())
	var selected by remember { mutableStateOf(settings.homeFavCategories) }
	SettingsGroup(title = stringResource(R.string.ds_carousels_title)) {
		if (categories.isEmpty()) {
			item { pos ->
				SettingsItem(
					title = stringResource(R.string.ds_carousels_title),
					subtitle = stringResource(R.string.ds_carousels_empty),
					icon = R.drawable.ic_appearance,
					shape = pos.shape,
					onClick = {},
				)
			}
		} else {
			categories.forEach { category ->
				item { pos ->
					SwitchSettingsItem(
						title = category.title,
						subtitle = stringResource(R.string.ds_carousels_summary),
						checked = category.id in selected,
						onCheckedChange = { on ->
							val updated = if (on) selected + category.id else selected - category.id
							selected = updated
							settings.homeFavCategories = updated
						},
						icon = R.drawable.ic_appearance,
						shape = pos.shape,
					)
				}
			}
		}
	}
	Spacer(Modifier.height(8.dp).fillMaxWidth())
}
