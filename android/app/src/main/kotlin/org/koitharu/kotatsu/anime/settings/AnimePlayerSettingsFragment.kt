package org.koitharu.kotatsu.anime.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.player.AnimePlaybackSession
import org.koitharu.kotatsu.anime.player.AnimePlayerLauncher
import org.koitharu.kotatsu.anime.player.AnimePlayerPrefs
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref

/** Settings > Player: how episodes open, how streams are picked, and a few playback defaults. */
@AndroidEntryPoint
class AnimePlayerSettingsFragment : BaseComposeSettingsFragment(R.string.ds_player_settings) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent { DropSauceTheme { AnimePlayerSettingsContent() } }
	}
}

@Composable
private fun AnimePlayerSettingsContent() {
	val context = LocalContext.current
	var direct by rememberBooleanPref(AnimePlayerPrefs.KEY_DIRECT_LANDSCAPE, false)
	var autoPick by rememberBooleanPref(AnimePlayerPrefs.KEY_AUTO_PICK, true)
	var quality by rememberStringPref(AnimePlayerPrefs.KEY_PREFERRED_QUALITY, AnimePlayerPrefs.QUALITY_BEST)
	var autoNext by rememberBooleanPref(AnimePlayerPrefs.KEY_AUTOPLAY_NEXT, true)
	var skip by rememberStringPref(AnimePlayerPrefs.KEY_SKIP_SECONDS, "85")
	var subFont by rememberStringPref(AnimePlayerPrefs.KEY_SUB_FONT, "sans")
	var subSize by rememberStringPref(AnimePlayerPrefs.KEY_SUB_SIZE, "normal")
	var subColor by rememberStringPref(AnimePlayerPrefs.KEY_SUB_COLOR, "white")
	var subEdge by rememberStringPref(AnimePlayerPrefs.KEY_SUB_EDGE, "outline")
	var subBackground by rememberStringPref(AnimePlayerPrefs.KEY_SUB_BACKGROUND, "none")
	var subPosition by rememberStringPref(AnimePlayerPrefs.KEY_SUB_POSITION, "normal")
	// These two live in the anime player's own prefs file, so they are held as plain state here.
	var preset by remember { mutableStateOf(AnimePlaybackSession.audioPreset(context)) }
	var vlc by remember { mutableStateOf(AnimePlayerLauncher.isVlcDefault(context)) }

	val qualityLabels = listOf(
		stringResource(R.string.ds_player_quality_best),
		"1080p", "720p", "480p", "360p",
		stringResource(R.string.ds_player_quality_lowest),
	)

	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.ds_player_group_opening)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ds_player_direct_landscape),
						subtitle = stringResource(R.string.ds_player_direct_landscape_summary),
						checked = direct,
						onCheckedChange = { direct = it },
						icon = R.drawable.ic_ap_rotate,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_player_group_streams)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ds_player_auto_pick),
						subtitle = stringResource(R.string.ds_player_auto_pick_summary),
						checked = autoPick,
						onCheckedChange = { autoPick = it },
						icon = R.drawable.ic_ap_settings,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.ds_player_quality),
						entries = qualityLabels,
						entryValues = AnimePlayerPrefs.QUALITY_VALUES,
						selectedValue = quality,
						onValueChange = { quality = it },
						icon = R.drawable.ic_ap_aspect,
						shape = pos.shape,
						enabled = autoPick,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.ds_player_audio_preset),
						entries = listOf(stringResource(R.string.ds_player_preset_sub), stringResource(R.string.ds_player_preset_dub)),
						entryValues = listOf("sub", "dub"),
						selectedValue = preset,
						onValueChange = {
							preset = it
							AnimePlaybackSession.setAudioPreset(context, it)
						},
						icon = R.drawable.ic_ap_audio,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_player_group_playback)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ds_player_autoplay_next),
						subtitle = stringResource(R.string.ds_player_autoplay_next_summary),
						checked = autoNext,
						onCheckedChange = { autoNext = it },
						icon = R.drawable.ic_ap_next,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.ds_player_skip_length),
						entries = AnimePlayerPrefs.SKIP_VALUES.map { "$it s" },
						entryValues = AnimePlayerPrefs.SKIP_VALUES,
						selectedValue = skip,
						onValueChange = { skip = it },
						icon = R.drawable.ic_ap_speed,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ds_player_vlc),
						subtitle = stringResource(R.string.ds_player_vlc_summary),
						checked = vlc,
						onCheckedChange = {
							vlc = it
							AnimePlayerLauncher.setVlcDefault(context, it)
						},
						icon = R.drawable.ic_ap_pip,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_player_group_subtitles)) {
				item { _ ->
					SubtitlePreview(subFont, subSize, subColor, subEdge, subBackground)
				}
				item { pos -> SubtitleChoice(R.string.ds_player_sub_font, AnimePlayerPrefs.SUBTITLE_FONTS, subFont, { subFont = it }, pos.shape) }
				item { pos -> SubtitleChoice(R.string.ds_player_sub_size, AnimePlayerPrefs.SUBTITLE_SIZES, subSize, { subSize = it }, pos.shape) }
				item { pos -> SubtitleChoice(R.string.ds_player_sub_color, AnimePlayerPrefs.SUBTITLE_COLORS, subColor, { subColor = it }, pos.shape) }
				item { pos -> SubtitleChoice(R.string.ds_player_sub_edge, AnimePlayerPrefs.SUBTITLE_EDGES, subEdge, { subEdge = it }, pos.shape) }
				item { pos -> SubtitleChoice(R.string.ds_player_sub_background, AnimePlayerPrefs.SUBTITLE_BACKGROUNDS, subBackground, { subBackground = it }, pos.shape) }
				item { pos -> SubtitleChoice(R.string.ds_player_sub_position, AnimePlayerPrefs.SUBTITLE_POSITIONS, subPosition, { subPosition = it }, pos.shape) }
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun SubtitleChoice(
	titleRes: Int,
	options: List<Pair<String, String>>,
	selected: String,
	onSelected: (String) -> Unit,
	shape: androidx.compose.ui.graphics.Shape,
) {
	ListSettingsItem(
		title = stringResource(titleRes),
		entries = options.map { it.second },
		entryValues = options.map { it.first },
		selectedValue = selected,
		onValueChange = onSelected,
		icon = R.drawable.ic_ap_subtitles,
		shape = shape,
	)
}

/** A sample line drawn the way the player will draw it, so a choice can be judged without opening an episode. */
@Composable
private fun SubtitlePreview(font: String, size: String, color: String, edge: String, background: String) {
	val textColor = Color(AnimePlayerPrefs.colorOf(color))
	val box = Color(AnimePlayerPrefs.backgroundOf(background))
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(96.dp)
			.clip(RoundedCornerShape(16.dp))
			.background(Color(0xFF1B1B1F)),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = "The quick brown fox jumps over the lazy dog",
			modifier = Modifier
				.padding(horizontal = 16.dp)
				.background(box)
				.padding(horizontal = 8.dp, vertical = 2.dp),
			textAlign = TextAlign.Center,
			style = TextStyle(
				color = textColor,
				fontSize = (15f * AnimePlayerPrefs.sizeScale(size)).sp,
				fontFamily = FontFamily(AnimePlayerPrefs.typefaceOf(font)),
				shadow = when (edge) {
					"none" -> null
					"raised" -> Shadow(Color.Black, Offset(-1.5f, -1.5f), 2f)
					else -> Shadow(Color.Black, Offset(2f, 2f), if (edge == "outline") 4f else 3f)
				},
			),
		)
	}
}
