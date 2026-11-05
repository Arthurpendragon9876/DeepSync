package org.koitharu.kotatsu.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.ui.epub.EpubBundledFonts
import org.koitharu.kotatsu.reader.ui.epub.NovelPresetStore
import org.koitharu.kotatsu.reader.ui.epub.UserNovelPreset
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SegmentedSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.TextInputDialog
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import java.io.File
import javax.inject.Inject

/**
 * Everything the novel reader offers outside the reader itself, plus the things that only make sense here:
 * saving presets and a custom background picture with a blur - all shown on a live preview that stays
 * pinned above the settings.
 */
@AndroidEntryPoint
class NovelReaderSettingsFragment : BaseComposeSettingsFragment(R.string.novel_reader_settings) {

	@Inject
	lateinit var settings: AppSettings

	private val backgroundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) importBackground(uri)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				NovelReaderScreen(
					settings = settings,
					onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
					onClearBackground = ::clearBackground,
				)
			}
		}
	}

	private fun importBackground(uri: Uri) {
		val context = requireContext().applicationContext
		viewLifecycleOwner.lifecycleScope.launch {
			val path = withContext(Dispatchers.IO) {
				runCatching {
					// A new file name every time, so the reader (which watches the path) always reloads.
					val target = File(context.filesDir, "novel_bg_${System.currentTimeMillis()}.img")
					context.contentResolver.openInputStream(uri)?.use { input ->
						target.outputStream().use { input.copyTo(it) }
					} ?: return@runCatching null
					// Reject anything that is not a decodable picture.
					val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
					BitmapFactory.decodeFile(target.path, bounds)
					if (bounds.outWidth <= 0) {
						target.delete()
						null
					} else {
						target.path
					}
				}.getOrNull()
			}
			if (path != null) {
				val old = settings.epubBackgroundImage
				settings.epubBackgroundImage = path
				deleteBackgroundFile(old)
			}
		}
	}

	private fun clearBackground() {
		val old = settings.epubBackgroundImage
		settings.epubBackgroundImage = ""
		settings.epubBackgroundBlur = 0
		deleteBackgroundFile(old)
	}

	private fun deleteBackgroundFile(path: String) {
		if (path.isNotEmpty()) runCatching { File(path).delete() }
	}
}

private val THEME_VALUES = listOf("system", "white", "gray", "black", "sepia", "dusk", "forest", "custom")
private val THEME_LABELS = listOf("Follow system", "White", "Gray", "Black", "Sepia", "Dusk", "Forest", "Custom")
private val ALIGN_VALUES = listOf("left", "justify", "center", "right")
private val ALIGN_LABELS = listOf("Left", "Justify", "Center", "Right")

@Composable
private fun NovelReaderScreen(
	settings: AppSettings,
	onPickBackground: () -> Unit,
	onClearBackground: () -> Unit,
) {
	val fontValues = remember {
		listOf("serif", "sans-serif", "monospace") + EpubBundledFonts.all.map { it.value }
	}
	val fontLabels = remember {
		listOf("Serif", "Sans", "Mono") + EpubBundledFonts.all.map { it.label }
	}

	var readingMode by rememberStringPref(AppSettings.KEY_EPUB_READING_MODE, "scroll")
	var scrollStyle by rememberStringPref(AppSettings.KEY_EPUB_SCROLL_STYLE, "continuous")
	var chapterHeader by rememberBooleanPref(AppSettings.KEY_EPUB_CHAPTER_HEADER, true)
	var fontSize by rememberIntPref(AppSettings.KEY_EPUB_FONT_SIZE, 100)
	var fontFamily by rememberStringPref(AppSettings.KEY_EPUB_FONT_FAMILY, "serif")
	var lineHeight by rememberIntPref(AppSettings.KEY_EPUB_LINE_HEIGHT, 160)
	var letterSpacing by rememberIntPref(AppSettings.KEY_EPUB_LETTER_SPACING, 0)
	var paragraphSpacing by rememberIntPref(AppSettings.KEY_EPUB_PARAGRAPH_SPACING, 0)
	var paragraphIndent by rememberIntPref(AppSettings.KEY_EPUB_PARAGRAPH_INDENT, 0)
	var textAlign by rememberStringPref(AppSettings.KEY_EPUB_TEXT_ALIGN, "justify")
	var hyphenation by rememberBooleanPref(AppSettings.KEY_EPUB_HYPHENATION, false)
	var bionic by rememberBooleanPref(AppSettings.KEY_EPUB_BIONIC_READING, false)
	var publisherStyle by rememberBooleanPref(AppSettings.KEY_EPUB_PUBLISHER_STYLE, false)
	var horizontalPadding by rememberIntPref(AppSettings.KEY_EPUB_HORIZONTAL_PADDING, 20)
	var verticalPadding by rememberIntPref(AppSettings.KEY_EPUB_VERTICAL_PADDING, 112)
	var maxWidth by rememberIntPref(AppSettings.KEY_EPUB_MAX_TEXT_WIDTH, 0)
	var theme by rememberStringPref(AppSettings.KEY_EPUB_THEME, "system")
	val customBg by rememberIntPref(AppSettings.KEY_EPUB_CUSTOM_BACKGROUND_COLOR, 0xFFFFFFFF.toInt())
	val customFg by rememberIntPref(AppSettings.KEY_EPUB_CUSTOM_TEXT_COLOR, 0xFF1B1B1F.toInt())
	val backgroundPath by rememberStringPref(AppSettings.KEY_EPUB_BACKGROUND_IMAGE, "")
	var blur by rememberIntPref(AppSettings.KEY_EPUB_BACKGROUND_BLUR, 0)

	val hasBackground = backgroundPath.isNotEmpty() && File(backgroundPath).isFile
	var presets by remember { mutableStateOf(NovelPresetStore.load(settings)) }
	var showCreatePreset by remember { mutableStateOf(false) }
	val isScroll = readingMode == "scroll"

	Column(modifier = Modifier.fillMaxSize()) {
		NovelPreview(
			fontSize = fontSize,
			fontFamily = fontFamily,
			lineHeight = lineHeight,
			letterSpacing = letterSpacing,
			paragraphSpacing = paragraphSpacing,
			paragraphIndent = paragraphIndent,
			textAlign = textAlign,
			hyphenation = hyphenation,
			horizontalPadding = horizontalPadding,
			maxWidth = maxWidth,
			theme = theme,
			customBackground = customBg,
			customText = customFg,
			backgroundPath = if (hasBackground) backgroundPath else "",
			blur = blur,
			publisherStyle = publisherStyle,
		)
		Box(modifier = Modifier.weight(1f)) {
			SettingsScaffold {
				item {
					SettingsGroup(title = "Reading") {
						item { pos ->
							SegmentedSettingsItem(
								title = "Reading mode",
								labels = listOf("Scroll", "Paged"),
								selectedIndex = if (isScroll) 0 else 1,
								onSelected = { readingMode = if (it == 0) "scroll" else "paged" },
								icon = R.drawable.ic_book_page,
								shape = pos.shape,
							)
						}
						item { pos ->
							SegmentedSettingsItem(
								title = "Scroll style",
								subtitle = if (scrollStyle == "overscroll") {
									"One chapter at a time; pull past the end for the next one"
								} else {
									"One endless scroll with a card between chapters"
								},
								labels = listOf("Continuous", "Overscroll"),
								selectedIndex = if (scrollStyle == "overscroll") 1 else 0,
								onSelected = { scrollStyle = if (it == 1) "overscroll" else "continuous" },
								shape = pos.shape,
								enabled = isScroll,
							)
						}
						item { pos ->
							SwitchSettingsItem(
								title = "Chapter header",
								subtitle = "Show the chapter number and title above each chapter",
								checked = chapterHeader,
								onCheckedChange = { chapterHeader = it },
								shape = pos.shape,
								enabled = isScroll,
							)
						}
					}
				}
				item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
				item {
					SettingsGroup(title = "Text") {
						item { pos ->
							SliderSettingsItem(
								title = "Font size",
								value = fontSize.coerceIn(50, 200),
								valueFrom = 50,
								valueTo = 200,
								stepSize = 5,
								unitSuffix = "%",
								onValueChange = { fontSize = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							ListSettingsItem(
								title = "Font",
								entries = fontLabels,
								entryValues = fontValues,
								selectedValue = fontFamily,
								onValueChange = { fontFamily = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SliderSettingsItem(
								title = "Line height",
								value = lineHeight.coerceIn(100, 240),
								valueFrom = 100,
								valueTo = 240,
								stepSize = 5,
								valueLabel = { "%.2fx".format(it / 100f) },
								onValueChange = { lineHeight = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SliderSettingsItem(
								title = "Letter spacing",
								value = letterSpacing.coerceIn(-5, 15),
								valueFrom = -5,
								valueTo = 15,
								stepSize = 1,
								valueLabel = { "%.2f em".format(it / 100f) },
								onValueChange = { letterSpacing = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SliderSettingsItem(
								title = "Paragraph spacing",
								value = paragraphSpacing.coerceIn(-32, 48),
								valueFrom = -32,
								valueTo = 48,
								stepSize = 2,
								unitSuffix = " dp",
								onValueChange = { paragraphSpacing = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SliderSettingsItem(
								title = "Paragraph indent",
								value = paragraphIndent.coerceIn(0, 8),
								valueFrom = 0,
								valueTo = 8,
								stepSize = 1,
								valueLabel = { "%.1f em".format(it / 2f) },
								onValueChange = { paragraphIndent = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SegmentedSettingsItem(
								title = "Alignment",
								labels = ALIGN_LABELS,
								selectedIndex = ALIGN_VALUES.indexOf(textAlign).coerceAtLeast(0),
								onSelected = { textAlign = ALIGN_VALUES[it] },
								shape = pos.shape,
							)
						}
						item { pos ->
							SwitchSettingsItem(
								title = "Hyphenation",
								checked = hyphenation,
								onCheckedChange = { hyphenation = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SwitchSettingsItem(
								title = "Bionic reading",
								subtitle = "Bold the first letters of each word",
								checked = bionic,
								onCheckedChange = { bionic = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SwitchSettingsItem(
								title = "Publisher style",
								subtitle = "Use the book's own look instead of the settings above",
								checked = publisherStyle,
								onCheckedChange = { publisherStyle = it },
								shape = pos.shape,
							)
						}
					}
				}
				item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
				item {
					SettingsGroup(title = "Margins") {
						item { pos ->
							SliderSettingsItem(
								title = "Horizontal margin",
								value = horizontalPadding.coerceIn(0, 64),
								valueFrom = 0,
								valueTo = 64,
								stepSize = 2,
								unitSuffix = " dp",
								onValueChange = { horizontalPadding = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SliderSettingsItem(
								title = "Vertical margin",
								value = verticalPadding.coerceIn(0, 112),
								valueFrom = 0,
								valueTo = 112,
								stepSize = 4,
								unitSuffix = " dp",
								onValueChange = { verticalPadding = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							SliderSettingsItem(
								title = "Text width",
								value = maxWidth.coerceIn(0, 900),
								valueFrom = 0,
								valueTo = 900,
								stepSize = 20,
								valueLabel = { if (it < 320) "Full width" else "$it dp" },
								onValueChange = { maxWidth = if (it < 320) 0 else it },
								shape = pos.shape,
							)
						}
					}
				}
				item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
				item {
					SettingsGroup(title = "Theme and background") {
						item { pos ->
							ListSettingsItem(
								title = "Reading theme",
								entries = THEME_LABELS,
								entryValues = THEME_VALUES,
								selectedValue = theme,
								onValueChange = { theme = it },
								shape = pos.shape,
							)
						}
						item { pos ->
							ActionSettingsItem(
								title = if (hasBackground) "Change background picture" else "Custom background picture",
								subtitle = "Shown behind the text in the reader",
								icon = R.drawable.ic_images,
								shape = pos.shape,
								onClick = onPickBackground,
							)
						}
						if (hasBackground) {
							item { pos ->
								SliderSettingsItem(
									title = "Background blur",
									value = blur.coerceIn(0, 25),
									valueFrom = 0,
									valueTo = 25,
									stepSize = 1,
									valueLabel = { if (it == 0) "Off" else "$it" },
									onValueChange = { blur = it },
									shape = pos.shape,
								)
							}
							item { pos ->
								ActionSettingsItem(
									title = "Remove background picture",
									icon = R.drawable.ic_delete,
									shape = pos.shape,
									accentColor = MaterialTheme.colorScheme.error,
									onClick = onClearBackground,
								)
							}
						}
					}
				}
				item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
				item {
					SettingsGroup(title = "Presets") {
						item { pos ->
							ActionSettingsItem(
								title = "Create preset",
								subtitle = "Save the current text, layout and theme settings",
								icon = R.drawable.ic_add,
								shape = pos.shape,
								onClick = { showCreatePreset = true },
							)
						}
						presets.forEach { preset ->
							item { pos ->
								SettingsItem(
									title = preset.name,
									subtitle = presetSummary(preset) +
										if (NovelPresetStore.isActive(settings, preset)) " \u00B7 Active" else "",
									icon = R.drawable.ic_sparkles,
									shape = pos.shape,
									onClick = { NovelPresetStore.apply(settings, preset) },
									trailing = {
										IconButton(onClick = {
											NovelPresetStore.delete(settings, preset.name)
											presets = NovelPresetStore.load(settings)
										}) {
											Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete preset")
										}
									},
								)
							}
						}
					}
				}
				item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
			}
		}
	}

	if (showCreatePreset) {
		TextInputDialog(
			title = "Create preset",
			initialValue = "",
			hint = "Preset name",
			onConfirm = {
				NovelPresetStore.create(settings, it)
				presets = NovelPresetStore.load(settings)
			},
			onDismiss = { showCreatePreset = false },
		)
	}
}

private fun presetSummary(p: UserNovelPreset): String = buildString {
	append("${p.fontSize}%")
	append(" \u00B7 ")
	append(EpubBundledFonts.find(p.font)?.label ?: when (p.font) {
		"sans-serif" -> "Sans"
		"monospace" -> "Mono"
		else -> "Serif"
	})
	append(" \u00B7 ")
	append(THEME_LABELS.getOrElse(THEME_VALUES.indexOf(p.theme)) { "Custom" })
}

private fun previewPalette(theme: String, customBg: Int, customFg: Int, systemDark: Boolean): Pair<Int, Int> =
	when (theme) {
		"sepia" -> 0xFFF4ECD8.toInt() to 0xFF433422.toInt()
		"dusk" -> 0xFF1C2030.toInt() to 0xFFC9D1E8.toInt()
		"forest" -> 0xFF14211A.toInt() to 0xFFC4D6C8.toInt()
		"custom" -> ColorUtils.setAlphaComponent(customBg, 255) to ColorUtils.setAlphaComponent(customFg, 255)
		"white", "light" -> 0xFFFFFBFE.toInt() to 0xFF1B1B1F.toInt()
		"gray", "dark" -> 0xFF1C1B1F.toInt() to 0xFFE4E4E8.toInt()
		"black" -> 0xFF000000.toInt() to 0xFFE4E4E8.toInt()
		else -> if (systemDark) 0xFF1C1B1F.toInt() to 0xFFE4E4E8.toInt() else 0xFFFFFBFE.toInt() to 0xFF1B1B1F.toInt()
	}

private fun previewTypeface(context: Context, family: String): Typeface = when {
	family == "custom" -> File(context.filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE)
		.takeIf { it.isFile }
		?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
		?: Typeface.SERIF

	family.startsWith(EpubBundledFonts.PREFIX) -> EpubBundledFonts.typeface(context, family) ?: Typeface.SERIF
	else -> Typeface.create(family.substringBefore(',').trim(), Typeface.NORMAL)
}

private const val PREVIEW_FIRST =
	"The lantern swung once in the draught, and the whole corridor seemed to lean closer to listen."
private const val PREVIEW_SECOND =
	"\u201CYou came back,\u201D she said. It wasn\u2019t a question, and he didn\u2019t treat it like one."

/** Pinned live preview: the reader's text rules applied to a sample, over the theme and background picture. */
@Composable
private fun NovelPreview(
	fontSize: Int,
	fontFamily: String,
	lineHeight: Int,
	letterSpacing: Int,
	paragraphSpacing: Int,
	paragraphIndent: Int,
	textAlign: String,
	hyphenation: Boolean,
	horizontalPadding: Int,
	maxWidth: Int,
	theme: String,
	customBackground: Int,
	customText: Int,
	backgroundPath: String,
	blur: Int,
	publisherStyle: Boolean,
) {
	val context = LocalContext.current
	val density = LocalDensity.current.density
	val (bg, fg) = previewPalette(theme, customBackground, customText, isSystemDarkTheme())
	val size = if (publisherStyle) 100 else fontSize
	val spacing = if (publisherStyle) 0 else paragraphSpacing.coerceAtLeast(0)
	val sampleBitmap by produceState<Bitmap?>(null, backgroundPath, blur) {
		value = if (backgroundPath.isEmpty()) {
			null
		} else {
			withContext(Dispatchers.IO) {
				runCatching {
					val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
					BitmapFactory.decodeFile(backgroundPath, bounds)
					// Below Android 12 nothing can blur live, so decode smaller and let it scale back up.
					val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 720f else 720f / (1f + blur / 2f)
					var sample = 1
					while (bounds.outWidth / (sample * 2) >= wanted) sample *= 2
					BitmapFactory.decodeFile(backgroundPath, BitmapFactory.Options().apply { inSampleSize = sample })
				}.getOrNull()
			}
		}
	}

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp, vertical = 8.dp)
			.height(188.dp)
			.clip(RoundedCornerShape(24.dp))
			.background(Color(bg)),
	) {
		sampleBitmap?.let { bitmap ->
			val softness = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				Modifier.blur((blur * 1.5f / density).dp)
			} else {
				Modifier
			}
			Image(
				bitmap = bitmap.asImageBitmap(),
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.fillMaxSize()
					.then(softness)
					.scale(if (blur > 0) 1f + blur * 0.012f else 1f),
			)
		}
		Column(
			modifier = Modifier
				.align(Alignment.Center)
				.widthIn(max = if (maxWidth >= 320) maxWidth.dp else 2000.dp)
				.padding(horizontal = (if (publisherStyle) 16 else horizontalPadding).dp),
			verticalArrangement = Arrangement.spacedBy(spacing.dp),
		) {
			listOf(PREVIEW_FIRST, PREVIEW_SECOND).forEach { sentence ->
				AndroidView(
					factory = { TextView(it) },
					update = { tv ->
						tv.setTextColor(fg)
						tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * size / 100f)
						tv.typeface = previewTypeface(context, if (publisherStyle) "serif" else fontFamily)
						tv.setLineSpacing(0f, (if (publisherStyle) 120 else lineHeight) / 100f)
						tv.letterSpacing = (if (publisherStyle) 0 else letterSpacing) / 100f
						tv.gravity = when (textAlign) {
							"center" -> Gravity.CENTER_HORIZONTAL
							"right" -> Gravity.END
							else -> Gravity.START
						}
						tv.justificationMode = if (textAlign == "justify") {
							Layout.JUSTIFICATION_MODE_INTER_WORD
						} else {
							Layout.JUSTIFICATION_MODE_NONE
						}
						tv.hyphenationFrequency = if (hyphenation) {
							Layout.HYPHENATION_FREQUENCY_NORMAL
						} else {
							Layout.HYPHENATION_FREQUENCY_NONE
						}
						val indentPx = if (publisherStyle) 0 else
							(paragraphIndent / 2f * tv.textSize).toInt()
						tv.text = SpannableString(sentence).apply {
							if (indentPx > 0) {
								setSpan(LeadingMarginSpan.Standard(indentPx, 0), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
							}
						}
					},
				)
			}
		}
	}
}

@Composable
private fun isSystemDarkTheme(): Boolean = isSystemInDarkTheme()
