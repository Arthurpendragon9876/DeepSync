package org.koitharu.kotatsu.home.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.home.ui.model.HeroSnap
import org.koitharu.kotatsu.home.ui.model.HeroSnapshot
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.home.ui.model.HomeBannerStyle
import org.koitharu.kotatsu.home.ui.model.HomeCarousel
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HomeLayoutSettingsEntryPoint {
	fun settings(): AppSettings

	fun favourites(): FavouritesRepository
}

/**
 * Settings > Home layout (also opened from the Yugen preferences sheet on Home). The banner style is
 * picked under a preview that is always on screen, then every Home carousel has its own switch, and the
 * Custom carousel takes the Favourites categories you tick.
 */
@AndroidEntryPoint
class HomeLayoutSettingsFragment : BaseComposeSettingsFragment(R.string.ds_prefs_layout) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent { DropSauceTheme { HomeLayoutSettingsContent() } }
	}
}

@Composable
internal fun HomeLayoutSettingsContent() {
	val context = LocalContext.current
	val entryPoint = remember {
		EntryPointAccessors.fromApplication(context.applicationContext, HomeLayoutSettingsEntryPoint::class.java)
	}
	val settings = remember { entryPoint.settings() }
	var style by remember { mutableStateOf(settings.homeBannerStyle) }
	var rotate by rememberBooleanPref(AppSettings.KEY_HOME_BANNER_ROTATE, true)
	var hidden by remember { mutableStateOf(settings.homeHiddenCarousels) }
	var selected by remember { mutableStateOf(settings.homeFavCategories) }
	val heroSnaps = remember { HeroSnapshot.decode(settings.homeHeroSnapshot) }
	val anime = remember { settings.homeMode == HomeMode.ANIME }
	val categories by remember { entryPoint.favourites().observeCategories() }.collectAsState(initial = emptyList())

	fun setCarousel(carousel: HomeCarousel, on: Boolean) {
		val updated = if (on) hidden - carousel.key else hidden + carousel.key
		hidden = updated
		settings.homeHiddenCarousels = updated
	}

	val customOn = HomeCarousel.CUSTOM.key !in hidden

	SettingsScaffold {
		// Banner: the preview is always visible, the chips under it change it
		item {
			BannerStyleCard(
				style = style,
				items = heroSnaps,
				autoRotate = rotate,
				anime = anime,
				bannerOn = HomeCarousel.BANNER.key !in hidden,
				onStyle = {
					style = it
					settings.homeBannerStyle = it
				},
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
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
		}

		// Manage carousels: one switch each
		item {
			SettingsGroup(title = stringResource(R.string.ds_manage_carousels)) {
				HomeCarousel.values().filter { it != HomeCarousel.CUSTOM }.forEach { carousel ->
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(carousel.titleRes),
							subtitle = stringResource(carousel.descRes),
							checked = carousel.key !in hidden,
							onCheckedChange = { setCarousel(carousel, it) },
							icon = R.drawable.ic_appearance,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }

		// Custom carousel: Favourites categories as rows
		item {
			SettingsGroup(title = stringResource(R.string.ds_custom_carousel)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ds_custom_carousel),
						subtitle = stringResource(R.string.ds_custom_carousel_desc),
						checked = customOn,
						onCheckedChange = { setCarousel(HomeCarousel.CUSTOM, it) },
						icon = R.drawable.ic_appearance,
						shape = pos.shape,
					)
				}
				if (categories.isEmpty()) {
					item { pos ->
						SettingsItem(
							title = stringResource(R.string.ds_custom_carousel_choose),
							subtitle = stringResource(R.string.ds_carousels_empty),
							icon = R.drawable.ic_appearance,
							shape = pos.shape,
							enabled = customOn,
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
								enabled = customOn,
							)
						}
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

/** Title, the live preview of the chosen style, then the style chips. */
@Composable
private fun BannerStyleCard(
	style: HomeBannerStyle,
	items: List<HeroSnap>,
	autoRotate: Boolean,
	anime: Boolean,
	bannerOn: Boolean,
	onStyle: (HomeBannerStyle) -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = colors.surfaceContainer,
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Box(
					modifier = Modifier
						.size(48.dp)
						.clip(RoundedCornerShape(14.dp))
						.background(colors.surfaceContainerHighest),
					contentAlignment = Alignment.Center,
				) {
					Icon(painterResource(R.drawable.ic_appearance), contentDescription = null, tint = colors.primary)
				}
				Spacer(Modifier.width(12.dp))
				Column(Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.ds_banner_style),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
					)
					Text(
						text = stringResource(style.descRes),
						style = MaterialTheme.typography.bodySmall,
						color = colors.onSurfaceVariant,
					)
				}
				Spacer(Modifier.width(8.dp))
				Surface(
					shape = CircleShape,
					color = colors.secondaryContainer,
					border = BorderStroke(1.dp, colors.outlineVariant),
				) {
					Text(
						text = stringResource(style.titleRes),
						style = MaterialTheme.typography.labelLarge,
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
					)
				}
			}
			Spacer(Modifier.height(16.dp))
			SectionLabel(stringResource(R.string.ds_banner_preview))
			Spacer(Modifier.height(8.dp))
			// The real banner, drawn live: same layout, covers and timing as Home, in a window that looks like Home's background
			Box(
				Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(16.dp))
					.background(colors.background),
			) {
				BannerLivePreview(
					style = style,
					items = items,
					autoRotate = autoRotate,
					anime = anime,
					modifier = Modifier.alpha(if (bannerOn) 1f else 0.35f),
				)
			}
			Spacer(Modifier.height(8.dp))
			Text(
				text = stringResource(if (bannerOn) R.string.ds_banner_preview_hint else R.string.ds_banner_preview_off),
				style = MaterialTheme.typography.bodySmall,
				color = colors.onSurfaceVariant,
			)
			Spacer(Modifier.height(16.dp))
			SectionLabel(stringResource(R.string.ds_banner_style_label))
			Spacer(Modifier.height(8.dp))
			Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
				HomeBannerStyle.values().forEach { option ->
					FilterChip(
						selected = option == style,
						onClick = { onStyle(option) },
						label = { Text(stringResource(option.titleRes)) },
					)
				}
			}
		}
	}
}

@Composable
private fun SectionLabel(text: String) {
	Text(
		text = text.uppercase(),
		style = MaterialTheme.typography.labelMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}
