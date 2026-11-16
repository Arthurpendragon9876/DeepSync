package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ChoiceDialog
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.TextInputDialog
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import javax.inject.Inject

/**
 * Novel settings that are about fetching rather than reading, ported from Tsundoku: request throttling
 * and update/import concurrency for novel downloads, and the chapter translation options.
 * Opened from the Novel reader settings screen.
 */
@AndroidEntryPoint
class AdvancedNovelSettingsFragment : BaseComposeSettingsFragment(R.string.advanced_novel_settings) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				AdvancedNovelScreen(settings = settings, sourcesRepository = sourcesRepository)
			}
		}
	}
}

private val LANGUAGE_CODES = listOf(
	"en", "es", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh", "ar", "hi", "id", "tr", "vi", "th", "pl", "nl",
)
private val LANGUAGE_NAMES = listOf(
	"English", "Spanish", "French", "German", "Italian", "Portuguese", "Russian", "Japanese", "Korean",
	"Chinese", "Arabic", "Hindi", "Indonesian", "Turkish", "Vietnamese", "Thai", "Polish", "Dutch",
)

@Composable
private fun AdvancedNovelScreen(settings: AppSettings, sourcesRepository: MangaSourcesRepository) {
	val context = LocalContext.current

	var throttle by rememberBooleanPref(AppSettings.KEY_NOVEL_THROTTLE, true)
	var burst by rememberIntPref(AppSettings.KEY_NOVEL_BURST, 5)
	var delayMs by rememberIntPref(AppSettings.KEY_NOVEL_DELAY, 1000)
	var parallel by rememberIntPref(AppSettings.KEY_NOVEL_PARALLEL_UPDATES, 5)
	var massImports by rememberIntPref(AppSettings.KEY_NOVEL_MASS_IMPORTS, 5)
	var skipErrors by rememberIntPref(AppSettings.KEY_NOVEL_SKIP_ERRORS, 2)

	var translate by rememberBooleanPref(AppSettings.KEY_NOVEL_TRANSLATE, false)
	var engine by rememberStringPref(AppSettings.KEY_NOVEL_TRANSLATE_ENGINE, "google_scraper")
	var sourceLang by rememberStringPref(AppSettings.KEY_NOVEL_TRANSLATE_SOURCE, "auto")
	var targetLang by rememberStringPref(AppSettings.KEY_NOVEL_TRANSLATE_TARGET, "en")
	var autoDownload by rememberBooleanPref(AppSettings.KEY_NOVEL_TRANSLATE_AUTO_DL, true)
	var autoTranslateDownloads by rememberBooleanPref(AppSettings.KEY_NOVEL_TRANSLATE_AUTO_DOWNLOADS, false)

	var overrides by remember { mutableStateOf(settings.novelDelayOverrides) }
	var showSourcePicker by remember { mutableStateOf(false) }
	var editingSource by remember { mutableStateOf<String?>(null) }
	val novelSources = remember { sourcesRepository.getEnabledSources().filter { it.isNovelSource } }

	SettingsScaffold {
		item {
			SettingsGroup(title = "Novel downloads") {
				item { pos ->
					SwitchSettingsItem(
						title = "Enable throttling",
						subtitle = "Add delays between requests to a source to avoid rate limiting",
						checked = throttle,
						onCheckedChange = { throttle = it },
						icon = R.drawable.ic_download,
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = "Requests before delay",
						value = burst.coerceIn(1, 20),
						valueFrom = 1,
						valueTo = 20,
						stepSize = 1,
						onValueChange = { burst = it },
						shape = pos.shape,
						enabled = throttle,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = "Request delay",
						value = delayMs.coerceIn(0, 10_000),
						valueFrom = 0,
						valueTo = 10_000,
						stepSize = 100,
						valueLabel = { "%.1f s".format(it / 1000f) },
						onValueChange = { delayMs = it },
						shape = pos.shape,
						enabled = throttle,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = "Parallel novel updates",
						value = parallel.coerceIn(1, 15),
						valueFrom = 1,
						valueTo = 15,
						stepSize = 1,
						onValueChange = { parallel = it },
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Mass import") {
				item { pos ->
					SliderSettingsItem(
						title = "Concurrent mass imports",
						value = massImports.coerceIn(1, 30),
						valueFrom = 1,
						valueTo = 30,
						stepSize = 1,
						onValueChange = { massImports = it },
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = "Skip source on consecutive errors",
						value = skipErrors.coerceIn(0, 20),
						valueFrom = 0,
						valueTo = 20,
						stepSize = 1,
						valueLabel = { if (it == 0) "Off" else "After $it errors" },
						onValueChange = { skipErrors = it },
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Per-extension overrides") {
				item { pos ->
					ActionSettingsItem(
						title = "Manage source overrides",
						subtitle = "${overrides.size} override(s) configured. Overrides take priority over the global delay.",
						onClick = { showSourcePicker = true },
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Translation") {
				item { pos ->
					SwitchSettingsItem(
						title = "Enable translation",
						subtitle = "Allow translating novel chapters",
						checked = translate,
						onCheckedChange = { translate = it },
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = "Translation engine",
						entries = listOf("Google Translate (Scraper)"),
						entryValues = listOf("google_scraper"),
						selectedValue = engine,
						onValueChange = { engine = it },
						shape = pos.shape,
						enabled = translate,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = "Source language",
						entries = listOf("Auto-detect") + LANGUAGE_NAMES,
						entryValues = listOf("auto") + LANGUAGE_CODES,
						selectedValue = sourceLang,
						onValueChange = { sourceLang = it },
						shape = pos.shape,
						enabled = translate,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = "Target language",
						entries = LANGUAGE_NAMES,
						entryValues = LANGUAGE_CODES,
						selectedValue = targetLang,
						onValueChange = { targetLang = it },
						shape = pos.shape,
						enabled = translate,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = "Auto-download before translating",
						subtitle = "Download chapter content if not already available",
						checked = autoDownload,
						onCheckedChange = { autoDownload = it },
						shape = pos.shape,
						enabled = translate,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = "Auto-translate downloads",
						subtitle = "Translate chapters as they finish downloading",
						checked = autoTranslateDownloads,
						onCheckedChange = { autoTranslateDownloads = it },
						shape = pos.shape,
						enabled = translate,
					)
				}
			}
		}
	}

	if (showSourcePicker) {
		if (novelSources.isEmpty()) {
			showSourcePicker = false
		} else {
			ChoiceDialog(
				title = "Choose a source",
				entries = novelSources.map { source ->
					val ms = overrides[source.name]
					source.getTitle(context) + (ms?.let { " ($it ms)" } ?: "")
				},
				selectedIndex = -1,
				onSelect = { editingSource = novelSources[it].name },
				onDismiss = { showSourcePicker = false },
			)
		}
	}
	editingSource?.let { name ->
		TextInputDialog(
			title = "Request delay (ms)",
			initialValue = overrides[name]?.toString().orEmpty(),
			hint = "Empty = use the global delay",
			onConfirm = { text ->
				val next = overrides.toMutableMap()
				val ms = text.trim().toIntOrNull()
				if (ms == null) next.remove(name) else next[name] = ms.coerceIn(0, 60_000)
				settings.novelDelayOverrides = next
				overrides = next
				editingSource = null
				showSourcePicker = false
			},
			onDismiss = { editingSource = null },
		)
	}
}
