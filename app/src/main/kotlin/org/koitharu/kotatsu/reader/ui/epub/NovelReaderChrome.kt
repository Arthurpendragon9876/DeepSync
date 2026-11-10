package org.koitharu.kotatsu.reader.ui.epub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.ui.tts.ReaderTts
import kotlin.math.roundToInt

/**
 * Compose chrome for the novel (EPUB) reader, modelled on Novery's reader:
 * a top bar with chapter title + progress, a floating bottom bar (Chapters / Listen / Settings)
 * and an inline settings panel with Presets / Text / Layout / Theme / Options tabs.
 *
 * It only draws and writes [AppSettings]; everything else goes through the callbacks that
 * ReaderActivity installs on [NovelChromeState].
 */
@Stable
class NovelChromeState {
	var active by mutableStateOf(false)
	var visible by mutableStateOf(true)
	var chapterTitle by mutableStateOf("")
	var chapterNumber by mutableIntStateOf(0)
	var totalChapters by mutableIntStateOf(0)
	var progressPermille by mutableIntStateOf(0)
	var minutesLeft by mutableIntStateOf(-1)
	var isHighlightArmed by mutableStateOf(false)
	var topInset by mutableStateOf(0.dp)
	var bottomInset by mutableStateOf(0.dp)

	/** True while the Listen player is on screen (it replaces the bottom bar). */
	var ttsOpen by mutableStateOf(false)

	/** The listen panel is tucked away (audio keeps playing); a small button brings it back. */
	var ttsMinimized by mutableStateOf(false)

	/** Bumped to ask the bottom bar to open its settings panel (e.g. from the hidden toolbar menu). */
	var openSettingsTick by mutableIntStateOf(0)

	var onTtsClose: () -> Unit = {}
	var onTtsChapter: (Int) -> Unit = {}

	var onBack: () -> Unit = {}
	var onHighlightClick: () -> Unit = {}
	var onChaptersClick: () -> Unit = {}
	var onListenClick: () -> Unit = {}
	var onSearchClick: () -> Unit = {}
	var onAutoScrollClick: () -> Unit = {}

	private val regions = HashMap<String, Rect>()

	fun setRegion(key: String, rect: Rect?) {
		if (rect == null) regions.remove(key) else regions[key] = rect
	}

	/** True when the screen point is on a bar or panel, so the reader must not treat it as a tap. */
	fun isOnChrome(x: Int, y: Int): Boolean {
		if (!active) return false
		val point = Offset(x.toFloat(), y.toFloat())
		return regions.values.any { it.contains(point) }
	}
}

@Immutable
internal class ChromeColors(
	val background: Color,
	val surface: Color,
	val surfaceVariant: Color,
	val surfaceElevated: Color,
	val accent: Color,
	val onAccent: Color,
	val accentMuted: Color,
	val onAccentMuted: Color,
	val accentSubtle: Color,
	val textPrimary: Color,
	val textSecondary: Color,
	val textMuted: Color,
	val textDisabled: Color,
	val border: Color,
	val warning: Color,
) {
	val radius = 28.dp
	val radiusMedium = 24.dp
	val radiusSmall = 16.dp
}

internal val LocalChromeColors = staticCompositionLocalOf<ChromeColors> { error("ChromeColors not provided") }

/** Flat Material colours taken from the app theme, so the chrome follows DeepSync's own look. */
@Composable
internal fun rememberChromeColors(): ChromeColors {
	val c = MaterialTheme.colorScheme
	return remember(c) {
		ChromeColors(
			background = c.surface,
			surface = c.surfaceContainer,
			surfaceVariant = c.surfaceContainerHigh,
			surfaceElevated = c.surfaceContainerHighest,
			accent = c.primary,
			onAccent = c.onPrimary,
			accentMuted = c.primaryContainer,
			onAccentMuted = c.onPrimaryContainer,
			accentSubtle = c.primaryContainer.copy(alpha = 0.35f),
			textPrimary = c.onSurface,
			textSecondary = c.onSurfaceVariant,
			textMuted = c.onSurfaceVariant.copy(alpha = 0.8f),
			textDisabled = c.onSurfaceVariant.copy(alpha = 0.6f),
			border = c.outlineVariant,
			warning = c.tertiary,
		)
	}
}

internal val Bar: ChromeColors
	@Composable @ReadOnlyComposable get() = LocalChromeColors.current

private enum class NovelTab(val title: String, val icon: Int) {
	PRESETS("Presets", R.drawable.ic_sparkles),
	TEXT("Text", R.drawable.ic_title),
	LAYOUT("Layout", R.drawable.ic_move_horizontal),
	THEME("Theme", R.drawable.ic_novel_palette),
	OPTIONS("Options", R.drawable.ic_settings),
}

@Composable
fun NovelReaderChrome(state: NovelChromeState, settings: AppSettings, tts: ReaderTts) {
	if (!state.active) return
	CompositionLocalProvider(LocalChromeColors provides rememberChromeColors()) {
		Box(Modifier.fillMaxSize()) {
			AnimatedVisibility(
				visible = state.visible,
				modifier = Modifier.align(Alignment.TopCenter),
				enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
				exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)),
			) {
				NovelTopBar(state)
			}
			LaunchedEffect(state.ttsOpen) { if (!state.ttsOpen) state.ttsMinimized = false }
			if (state.ttsOpen && state.ttsMinimized) {
				NovelTtsMiniButton(state, tts, Modifier.align(Alignment.BottomEnd))
			} else if (state.ttsOpen) {
				NovelTtsPlayer(state, tts, settings, Modifier.align(Alignment.BottomCenter))
			} else {
				AnimatedVisibility(
					visible = state.visible,
					modifier = Modifier.align(Alignment.BottomCenter),
					enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
					exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
				) {
					NovelBottomBar(state, settings)
				}
			}
		}
	}
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun NovelTopBar(state: NovelChromeState) {
	DisposableEffect(Unit) { onDispose { state.setRegion("top", null) } }
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.onGloballyPositioned { state.setRegion("top", it.boundsInWindow()) },
		color = Bar.background,
		shadowElevation = 0.dp,
	) {
		Column {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = state.topInset)
					.padding(horizontal = 4.dp, vertical = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconButton(onClick = state.onBack, modifier = Modifier.size(48.dp)) {
					Icon(
						painter = painterResource(R.drawable.ic_arrow_back),
						contentDescription = null,
						tint = Bar.textPrimary,
					)
				}
				Column(
					modifier = Modifier.weight(1f),
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					Text(
						text = state.chapterTitle,
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = FontWeight.Medium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						textAlign = TextAlign.Center,
						color = Bar.textPrimary,
					)
					Row(verticalAlignment = Alignment.CenterVertically) {
						val percent = (state.progressPermille / 10f).roundToInt()
						Text(
							text = "${state.chapterNumber} / ${state.totalChapters} \u2022 $percent%",
							style = MaterialTheme.typography.labelSmall,
							color = Bar.textSecondary,
						)
						if (state.minutesLeft >= 0) {
							Text(
								text = " \u2022 ",
								style = MaterialTheme.typography.labelSmall,
								color = Bar.textSecondary,
							)
							Icon(
								painter = painterResource(R.drawable.ic_timer),
								contentDescription = null,
								tint = Bar.textSecondary,
								modifier = Modifier.size(12.dp),
							)
							Text(
								text = " ${state.minutesLeft.coerceAtLeast(1)} min",
								style = MaterialTheme.typography.labelSmall,
								color = Bar.textSecondary,
							)
						}
					}
				}
				IconButton(onClick = state.onHighlightClick, modifier = Modifier.size(48.dp)) {
					Icon(
						painter = painterResource(
							if (state.isHighlightArmed) R.drawable.ic_ink_highlighter_filled else R.drawable.ic_ink_highlighter,
						),
						contentDescription = null,
						tint = if (state.isHighlightArmed) Bar.accent else Bar.textPrimary,
					)
				}
			}
			LinearProgressIndicator(
				progress = { (state.progressPermille / 1000f).coerceIn(0f, 1f) },
				modifier = Modifier
					.fillMaxWidth()
					.height(2.dp),
				color = Bar.accent,
				trackColor = Bar.border.copy(alpha = 0.3f),
			)
		}
	}
}

// ---------------------------------------------------------------------------------------------
// Bottom bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun NovelBottomBar(state: NovelChromeState, settings: AppSettings) {
	var showSettings by remember { mutableStateOf(false) }
	androidx.compose.runtime.LaunchedEffect(state.openSettingsTick) {
		if (state.openSettingsTick > 0) showSettings = true
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp)
			.padding(bottom = state.bottomInset + 8.dp),
	) {
		AnimatedVisibility(
			visible = showSettings,
			enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
			exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
		) {
			Column {
				NovelSettingsPanel(
					state = state,
					settings = settings,
					onDismiss = { showSettings = false },
				)
				Spacer(Modifier.height(8.dp))
			}
		}
		DisposableEffect(Unit) { onDispose { state.setRegion("bar", null) } }
		Surface(
			modifier = Modifier
				.fillMaxWidth()
				.onGloballyPositioned { state.setRegion("bar", it.boundsInWindow()) },
			shape = RoundedCornerShape(Bar.radius),
			color = Color.Transparent,
			shadowElevation = 0.dp,
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.background(Bar.surface)
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 6.dp, vertical = 10.dp),
					horizontalArrangement = Arrangement.SpaceEvenly,
					verticalAlignment = Alignment.CenterVertically,
				) {
					BarButton(R.drawable.ic_novel_menu_book, "Chapters", false) { state.onChaptersClick() }
					ListenButton { state.onListenClick() }
					BarButton(
						icon = if (showSettings) R.drawable.ic_close else R.drawable.ic_tune,
						label = "Settings",
						active = showSettings,
					) { showSettings = !showSettings }
				}
			}
		}
	}
}

@Composable
private fun BarButton(icon: Int, label: String, active: Boolean, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(16.dp),
		color = if (active) Bar.accentMuted else Color.Transparent,
	) {
		Column(
			modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Icon(
				painter = painterResource(icon),
				contentDescription = label,
				tint = if (active) Bar.onAccentMuted else Bar.textMuted,
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.height(4.dp))
			Text(
				text = label,
				fontSize = 11.sp,
				fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
				color = if (active) Bar.onAccentMuted else Bar.textDisabled,
			)
		}
	}
}

@Composable
private fun ListenButton(onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(20.dp),
		color = Bar.accent,
		shadowElevation = 0.dp,
		modifier = Modifier.height(54.dp),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 28.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_novel_headphones),
				contentDescription = null,
				tint = Bar.onAccent,
				modifier = Modifier.size(22.dp),
			)
			Text(
				text = "Listen",
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Bold,
				letterSpacing = 0.5.sp,
				color = Bar.onAccent,
			)
		}
	}
}

// ---------------------------------------------------------------------------------------------
// Inline settings panel
// ---------------------------------------------------------------------------------------------

private class NovelPreset(
	val name: String,
	val fontSize: Int,
	val lineHeight: Int,
	val paragraphSpacing: Int,
	val horizontalPadding: Int,
	val letterSpacing: Int,
	val indent: Int,
	val textAlign: String,
	val font: String,
	val theme: String,
	val hyphenation: Boolean,
	val maxWidth: Int,
)

private val NOVEL_DEFAULT = NovelPreset("Default", 100, 160, 0, 20, 0, 0, "justify", "serif", "system", false, 0)

private val NOVEL_PRESETS = listOf(
	NOVEL_DEFAULT,
	NovelPreset("Compact", 90, 140, 0, 12, 0, 0, "left", "sans-serif", "system", false, 0),
	NovelPreset("Comfortable", 105, 175, 6, 24, 0, 0, "left", "bundled:literata", "sepia", false, 640),
	NovelPreset("Large Text", 130, 180, 8, 16, 2, 0, "left", "bundled:atkinson_hyperlegible", "system", false, 0),
	NovelPreset("E-Reader", 100, 160, 0, 24, 0, 3, "justify", "bundled:source_serif_4", "sepia", true, 0),
	NovelPreset("Night Mode", 100, 170, 4, 20, 0, 0, "left", "bundled:literata", "dusk", false, 0),
	NovelPreset("Dyslexia Friendly", 105, 200, 12, 24, 5, 0, "left", "bundled:open_dyslexic", "sepia", false, 640),
)

private fun applyPreset(settings: AppSettings, p: NovelPreset) {
	settings.epubFontSize = p.fontSize
	settings.epubLineHeight = p.lineHeight
	settings.epubParagraphSpacing = p.paragraphSpacing
	settings.epubHorizontalPadding = p.horizontalPadding
	settings.epubLetterSpacing = p.letterSpacing
	settings.epubParagraphIndent = p.indent
	settings.epubTextAlign = p.textAlign
	settings.epubFontFamily = p.font
	settings.epubTheme = p.theme
	settings.isEpubHyphenationEnabled = p.hyphenation
	settings.epubMaxTextWidth = p.maxWidth
}

private fun isPresetActive(s: AppSettings, p: NovelPreset): Boolean =
	s.epubFontSize == p.fontSize && s.epubFontFamily == p.font && s.epubTheme == p.theme &&
		s.epubLineHeight == p.lineHeight && s.epubMaxTextWidth == p.maxWidth

private fun countModified(s: AppSettings): Int {
	val d = NOVEL_DEFAULT
	var n = 0
	if (s.epubFontSize != d.fontSize) n++
	if (s.epubFontFamily != d.font) n++
	if (s.epubLineHeight != d.lineHeight) n++
	if (s.epubParagraphSpacing != d.paragraphSpacing) n++
	if (s.epubHorizontalPadding != d.horizontalPadding) n++
	if (s.epubLetterSpacing != d.letterSpacing) n++
	if (s.epubParagraphIndent != d.indent) n++
	if (s.epubTextAlign != d.textAlign) n++
	if (s.epubTheme != d.theme) n++
	if (s.isEpubHyphenationEnabled != d.hyphenation) n++
	if (s.epubMaxTextWidth != d.maxWidth) n++
	if (s.epubReadingMode != "scroll") n++
	if (s.isEpubBionicReadingEnabled) n++
	if (s.isEpubPublisherStyleEnabled) n++
	return n
}

private fun fontLabel(value: String): String = when {
	value == "serif" -> "Serif"
	value == "sans-serif" -> "Sans"
	value == "monospace" -> "Mono"
	value == "custom" -> "Custom"
	else -> EpubBundledFonts.find(value)?.label ?: "Serif"
}

private fun themeLabel(value: String): String = when (value) {
	"system" -> "Follow system"
	"white", "light" -> "White"
	"gray", "dark" -> "Gray"
	"black" -> "Black"
	"sepia" -> "Sepia"
	"dusk" -> "Dusk"
	"forest" -> "Forest"
	else -> "Custom"
}

private fun presetDescription(p: NovelPreset): String = buildString {
	append("${p.fontSize}%")
	append(" \u00B7 ${fontLabel(p.font)}")
	append(" \u00B7 ${themeLabel(p.theme)}")
	if (p.lineHeight != NOVEL_DEFAULT.lineHeight) append(" \u00B7 ${"%.1f".format(p.lineHeight / 100f)}x line")
}

@Composable
private fun NovelSettingsPanel(state: NovelChromeState, settings: AppSettings, onDismiss: () -> Unit) {
	var tab by remember { mutableStateOf(NovelTab.PRESETS) }
	// Bumped after every write so the panel re-reads AppSettings.
	var revision by remember { mutableIntStateOf(0) }
	val refresh: () -> Unit = { revision++ }
	// Passed to every tab: AppSettings is not observable state, so without a changing argument Compose
	// skips the tab and the slider handle / value label keep showing the old value while dragging.
	val tick = revision
	DisposableEffect(Unit) { onDispose { state.setRegion("panel", null) } }

	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(max = 450.dp)
			.onGloballyPositioned { state.setRegion("panel", it.boundsInWindow()) },
		shape = RoundedCornerShape(Bar.radiusMedium),
		color = Color.Transparent,
		shadowElevation = 0.dp,
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.background(Bar.surfaceVariant)
		) {
			Column {
				val modified = countModified(settings)
				PanelHeader(
					modified = modified,
					onReset = {
						applyPreset(settings, NOVEL_DEFAULT)
						settings.epubReadingMode = "scroll"
						settings.epubScrollStyle = "continuous"
						settings.isEpubBionicReadingEnabled = false
						settings.isEpubPublisherStyleEnabled = false
						refresh()
					},
					onDismiss = onDismiss,
				)
				TabBar(tab) { tab = it }
				Column(
					modifier = Modifier
						.weight(1f, fill = false)
						.verticalScroll(rememberScrollState())
						.padding(16.dp),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					when (tab) {
						NovelTab.PRESETS -> PresetsTab(settings, refresh, tick)
						NovelTab.TEXT -> TextTab(settings, refresh, tick)
						NovelTab.LAYOUT -> LayoutTab(settings, refresh, tick)
						NovelTab.THEME -> ThemeTab(settings, refresh, tick)
						NovelTab.OPTIONS -> OptionsTab(state, settings, refresh, onDismiss, tick)
					}
				}
			}
		}
	}
}

@Composable
private fun PanelHeader(modified: Int, onReset: () -> Unit, onDismiss: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Surface(shape = CircleShape, color = Bar.accentMuted, modifier = Modifier.size(38.dp)) {
				Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
					Icon(
						painter = painterResource(R.drawable.ic_tune),
						contentDescription = null,
						tint = Bar.accent,
						modifier = Modifier.size(18.dp),
					)
				}
			}
			Column {
				Text(
					text = "Reader Settings",
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = Bar.textPrimary,
				)
				if (modified > 0) {
					Text(
						text = "$modified changes from default",
						style = MaterialTheme.typography.labelSmall,
						color = Bar.textMuted,
					)
				}
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			if (modified > 0) {
				Surface(
					onClick = onReset,
					shape = RoundedCornerShape(10.dp),
					color = Bar.warning.copy(alpha = 0.15f),
					border = BorderStroke(1.dp, Bar.warning.copy(alpha = 0.3f)),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(
							painter = painterResource(R.drawable.ic_restart),
							contentDescription = null,
							tint = Bar.warning,
							modifier = Modifier.size(16.dp),
						)
						Text(
							text = "Reset",
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.Medium,
							color = Bar.warning,
						)
					}
				}
			}
			Surface(onClick = onDismiss, shape = CircleShape, color = Bar.surfaceVariant, modifier = Modifier.size(36.dp)) {
				Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
					Icon(
						painter = painterResource(R.drawable.ic_close),
						contentDescription = null,
						tint = Bar.textMuted,
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun TabBar(selected: NovelTab, onSelect: (NovelTab) -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		NovelTab.entries.forEach { t ->
			val isSelected = t == selected
			Surface(
				onClick = { onSelect(t) },
				shape = RoundedCornerShape(10.dp),
				color = if (isSelected) Bar.accent else Bar.surfaceVariant,
			) {
				Row(
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					val color = if (isSelected) Bar.onAccent else Bar.textMuted
					Icon(
						painter = painterResource(t.icon),
						contentDescription = null,
						tint = color,
						modifier = Modifier.size(16.dp),
					)
					Text(
						text = t.title,
						style = MaterialTheme.typography.labelMedium,
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
						color = color,
					)
				}
			}
		}
	}
	Spacer(Modifier.height(8.dp))
}

// ---- tabs ----------------------------------------------------------------------------------

@Composable
private inline fun PresetsTab(settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	Surface(
		shape = RoundedCornerShape(12.dp),
		color = Bar.accentSubtle,
		border = BorderStroke(1.dp, Bar.accent.copy(alpha = 0.2f)),
	) {
		Row(
			modifier = Modifier.padding(12.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_sparkles),
				contentDescription = null,
				tint = Bar.accent,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = "Choose a preset for quick setup, then customize individual settings in other tabs.",
				style = MaterialTheme.typography.bodySmall,
				color = Bar.textSecondary,
			)
		}
	}
	NOVEL_PRESETS.forEach { preset ->
		val active = isPresetActive(settings, preset)
		Surface(
			onClick = { applyPreset(settings, preset); refresh() },
			shape = RoundedCornerShape(Bar.radiusSmall),
			color = if (active) Bar.accentSubtle else Bar.surfaceVariant.copy(alpha = 0.5f),
			border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Bar.accent else Bar.border),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(14.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(modifier = Modifier.weight(1f)) {
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = preset.name,
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.SemiBold,
							color = if (active) Bar.accent else Bar.textPrimary,
						)
						if (active) {
							Surface(shape = RoundedCornerShape(4.dp), color = Bar.accent) {
								Text(
									text = "Active",
									style = MaterialTheme.typography.labelSmall,
									color = Bar.onAccent,
									modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
								)
							}
						}
					}
					Spacer(Modifier.height(4.dp))
					Text(
						text = presetDescription(preset),
						style = MaterialTheme.typography.bodySmall,
						color = Bar.textMuted,
					)
				}
				if (active) {
					Surface(shape = CircleShape, color = Bar.accent, modifier = Modifier.size(24.dp)) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							Icon(
								painter = painterResource(R.drawable.ic_check),
								contentDescription = null,
								tint = Bar.onAccent,
								modifier = Modifier.size(14.dp),
							)
						}
					}
				}
			}
		}
	}
	// Presets saved from Settings > Novel reader
	NovelPresetStore.load(settings).forEach { preset ->
		val active = NovelPresetStore.isActive(settings, preset)
		Surface(
			onClick = { NovelPresetStore.apply(settings, preset); refresh() },
			shape = RoundedCornerShape(Bar.radiusSmall),
			color = if (active) Bar.accentSubtle else Bar.surfaceVariant.copy(alpha = 0.5f),
			border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Bar.accent else Bar.border),
		) {
			Text(
				text = preset.name + if (active) "  \u00B7  Active" else "",
				style = MaterialTheme.typography.bodyMedium,
				fontWeight = FontWeight.SemiBold,
				color = if (active) Bar.accent else Bar.textPrimary,
				modifier = Modifier.fillMaxWidth().padding(14.dp),
			)
		}
	}
}

@Composable
private inline fun TextTab(settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	Section("Font size", "${settings.epubFontSize}%") {
		SliderRow(settings.epubFontSize.toFloat(), 50f..200f) { settings.epubFontSize = it.roundToInt(); refresh() }
	}
	Section("Font", null) {
		val fonts = listOf("serif" to "Serif", "sans-serif" to "Sans", "monospace" to "Mono") +
			EpubBundledFonts.all.map { it.value to it.label }
		ChipFlow(
			options = fonts,
			selected = settings.epubFontFamily,
			fontFor = { value ->
				when (value) {
					"serif" -> FontFamily.Serif
					"sans-serif" -> FontFamily.SansSerif
					"monospace" -> FontFamily.Monospace
					else -> EpubBundledFonts.find(value)?.let { FontFamily(Font(it.res)) }
				}
			},
		) { settings.epubFontFamily = it; refresh() }
	}
	Section("Line height", "%.1fx".format(settings.epubLineHeight / 100f)) {
		SliderRow(settings.epubLineHeight.toFloat(), 100f..240f) { settings.epubLineHeight = it.roundToInt(); refresh() }
	}
	Section("Letter spacing", "%.2f em".format(settings.epubLetterSpacing / 100f)) {
		SliderRow(settings.epubLetterSpacing.toFloat(), -5f..15f) { settings.epubLetterSpacing = it.roundToInt(); refresh() }
	}
	Section("Paragraph spacing", "${settings.epubParagraphSpacing} dp") {
		SliderRow(settings.epubParagraphSpacing.toFloat(), -32f..48f) { settings.epubParagraphSpacing = it.roundToInt(); refresh() }
	}
	Section("Alignment", null) {
		ChipFlow(
			options = listOf("left" to "Left", "justify" to "Justified", "center" to "Center"),
			selected = settings.epubTextAlign,
		) { settings.epubTextAlign = it; refresh() }
	}
}

@Composable
private inline fun LayoutTab(settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	Section("Reading mode", null) {
		ChipFlow(
			options = listOf("scroll" to "Scroll", "paged" to "Paged"),
			selected = settings.epubReadingMode,
		) { settings.epubReadingMode = it; refresh() }
	}
	if (settings.epubReadingMode == "scroll") {
		Section("Scroll style", null) {
			ChipFlow(
				options = listOf("continuous" to "Continuous", "overscroll" to "Overscroll"),
				selected = settings.epubScrollStyle,
			) { settings.epubScrollStyle = it; refresh() }
		}
	}
	Section("Horizontal margin", "${settings.epubHorizontalPadding} dp") {
		SliderRow(settings.epubHorizontalPadding.toFloat(), 0f..64f) { settings.epubHorizontalPadding = it.roundToInt(); refresh() }
	}
	Section("Vertical margin", "${settings.epubVerticalPadding} dp") {
		SliderRow(settings.epubVerticalPadding.toFloat(), 0f..112f) { settings.epubVerticalPadding = it.roundToInt(); refresh() }
	}
	Section("Paragraph indent", "%.1f em".format(settings.epubParagraphIndent / 2f)) {
		SliderRow(settings.epubParagraphIndent.toFloat(), 0f..8f) { settings.epubParagraphIndent = it.roundToInt(); refresh() }
	}
	val widthOff = settings.epubMaxTextWidth == 0
	Section("Text width", if (widthOff) "Full width" else "${settings.epubMaxTextWidth} dp") {
		SliderRow(
			value = if (widthOff) 300f else settings.epubMaxTextWidth.toFloat(),
			range = 300f..900f,
		) {
			val v = it.roundToInt()
			settings.epubMaxTextWidth = if (v <= 300) 0 else v
			refresh()
		}
	}
	ToggleRow("Hyphenation", settings.isEpubHyphenationEnabled) { settings.isEpubHyphenationEnabled = it; refresh() }
}

@Composable
private inline fun ThemeTab(settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	val themes = listOf(
		Triple("system", Color(0xFF1B1B1F), Color(0xFFE4E4E8)),
		Triple("white", Color(0xFFFFFFFF), Color(0xFF1B1B1F)),
		Triple("gray", Color(0xFF2B2B30), Color(0xFFE4E4E8)),
		Triple("black", Color(0xFF000000), Color(0xFFE4E4E8)),
		Triple("sepia", Color(0xFFF4ECD8), Color(0xFF433422)),
		Triple("dusk", Color(0xFF1C2030), Color(0xFFC9D1E8)),
		Triple("forest", Color(0xFF14211A), Color(0xFFC4D6C8)),
	)
	val current = when (settings.epubTheme) {
		"light" -> "white"
		"dark" -> "gray"
		else -> settings.epubTheme
	}
	themes.chunked(3).forEach { row ->
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
			row.forEach { (id, bg, fg) ->
				val selected = current == id
				Surface(
					onClick = { settings.epubTheme = id; refresh() },
					modifier = Modifier.weight(1f),
					shape = RoundedCornerShape(Bar.radiusSmall),
					color = bg,
					border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Bar.accent else Bar.border),
				) {
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 14.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
					) {
						Text(text = "Aa", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = fg)
						Text(text = themeLabel(id), fontSize = 11.sp, color = fg.copy(alpha = 0.8f), maxLines = 1)
					}
				}
			}
			repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
		}
	}
}

@Composable
private inline fun OptionsTab(state: NovelChromeState, settings: AppSettings, noinline refresh: () -> Unit, noinline onDismiss: () -> Unit, rev: Int) {
	ToggleRow("Chapter header", settings.isEpubChapterHeaderEnabled) { settings.isEpubChapterHeaderEnabled = it; refresh() }
	ToggleRow("Bionic reading", settings.isEpubBionicReadingEnabled) { settings.isEpubBionicReadingEnabled = it; refresh() }
	ToggleRow("Use book formatting", settings.isEpubPublisherStyleEnabled) { settings.isEpubPublisherStyleEnabled = it; refresh() }
	ActionRow("Search in book", R.drawable.ic_search) { onDismiss(); state.onSearchClick() }
	ActionRow("Auto scroll", R.drawable.ic_timer) { onDismiss(); state.onAutoScrollClick() }
}

// ---- building blocks -----------------------------------------------------------------------

@Composable
internal fun Section(title: String, value: String?, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text(
				text = title,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Medium,
				color = Bar.textPrimary,
			)
			if (value != null) {
				Text(text = value, style = MaterialTheme.typography.labelMedium, color = Bar.textMuted)
			}
		}
		content()
	}
}

@Composable
internal fun SliderRow(
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	onFinished: () -> Unit = {},
	onChange: (Float) -> Unit,
) {
	// While a finger is down the handle follows the finger from this local state, so it moves every frame
	// even when the settings behind it (not observable by Compose) or the reader reflow are slow.
	var dragValue by remember { mutableStateOf<Float?>(null) }
	// The panel scrolls vertically. A finger that drifts up or down while dragging the handle must not scroll
	// it, so once the slider has seen the movement, mark it consumed before it reaches the scroll container.
	Box(
		modifier = Modifier.pointerInput(Unit) {
			awaitEachGesture {
				awaitFirstDown(requireUnconsumed = false)
				do {
					val event = awaitPointerEvent()
					event.changes.forEach { if (it.positionChanged()) it.consume() }
				} while (event.changes.any { it.pressed })
			}
		},
	) {
		Slider(
			value = (dragValue ?: value).coerceIn(range.start, range.endInclusive),
			onValueChange = {
				dragValue = it
				onChange(it)
			},
			onValueChangeFinished = {
				dragValue = null
				onFinished()
			},
			valueRange = range,
			colors = SliderDefaults.colors(
				thumbColor = Bar.accent,
				activeTrackColor = Bar.accent,
				inactiveTrackColor = Bar.surfaceVariant,
			),
		)
	}
}

@Composable
internal fun ChipFlow(
	options: List<Pair<String, String>>,
	selected: String,
	fontFor: (String) -> FontFamily? = { null },
	onSelect: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		options.chunked(3).forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				row.forEach { (value, label) ->
					val isSelected = selected == value
					Surface(
						onClick = { onSelect(value) },
						modifier = Modifier.weight(1f),
						shape = RoundedCornerShape(12.dp),
						color = if (isSelected) Bar.accentMuted else Bar.surfaceVariant,
						border = BorderStroke(1.dp, if (isSelected) Bar.accent else Bar.border.copy(alpha = 0.5f)),
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) {
							Text(
								text = label,
								fontFamily = fontFor(value),
								fontSize = 13.sp,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								color = if (isSelected) Bar.onAccentMuted else Bar.textSecondary,
							)
						}
					}
				}
				repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
			}
		}
	}
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Surface(
		onClick = { onChange(!checked) },
		shape = RoundedCornerShape(12.dp),
		color = Bar.surfaceVariant.copy(alpha = 0.5f),
		border = BorderStroke(1.dp, Bar.border.copy(alpha = 0.5f)),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Bar.textPrimary)
			Surface(
				shape = RoundedCornerShape(50),
				color = if (checked) Bar.accent else Bar.surfaceElevated,
				border = BorderStroke(1.dp, Bar.border),
			) {
				Text(
					text = if (checked) "On" else "Off",
					style = MaterialTheme.typography.labelMedium,
					color = if (checked) Bar.onAccent else Bar.textMuted,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
				)
			}
		}
	}
}

@Composable
internal fun ActionRow(label: String, icon: Int, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(12.dp),
		color = Bar.surfaceVariant.copy(alpha = 0.5f),
		border = BorderStroke(1.dp, Bar.border.copy(alpha = 0.5f)),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(painter = painterResource(icon), contentDescription = null, tint = Bar.textSecondary, modifier = Modifier.size(20.dp))
			Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Bar.textPrimary)
		}
	}
}
