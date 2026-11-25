@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.koitharu.kotatsu.reader.ui.epub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import android.content.SharedPreferences
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NovelBarLayout
import org.koitharu.kotatsu.core.prefs.NovelControl
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot
import org.koitharu.kotatsu.core.prefs.isSliderShown
import org.koitharu.kotatsu.core.prefs.shownIn
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

	/** The settings panel above the bottom bar is open; the Settings button (wherever it sits) reflects and toggles it. */
	var settingsOpen by mutableStateOf(false)

	/** Opens "Customise novel reader bars". */
	var onCustomizeBarsClick: () -> Unit = {}

	var onTtsClose: () -> Unit = {}
	var onTtsChapter: (Int) -> Unit = {}

	var onBack: () -> Unit = {}
	var onHighlightClick: () -> Unit = {}

	/** Opens the chapter being read on the source's website in the built-in browser. */
	var onWebClick: () -> Unit = {}

	/** Holding the web button opens the same page in the phone's browser. */
	var onWebLongClick: () -> Unit = {}
	var canOpenWeb: () -> Boolean = { false }
	var onChaptersClick: () -> Unit = {}

	/** Jump to the start of the previous / next chapter (bottom bar |< and >| buttons). */
	var onPrevChapter: () -> Unit = {}
	var onNextChapter: () -> Unit = {}

	/** Dragging the capsule: 0..1000 is the position inside the current chapter. */
	var onSeekChapter: (Int) -> Unit = {}

	/** Rotate-screen tile in the settings sheet: toggles the orientation lock and reports whether it is locked. */
	var onRotateClick: () -> Unit = {}
	var isRotationLocked: () -> Boolean = { false }
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

/**
 * Bumped after every write to [AppSettings]. The settings are plain SharedPreferences, not Compose state, so
 * every page of the sheet reads this (through [observeSettingsRevision]) to be redrawn with the new values.
 */
internal val LocalSettingsRevision = compositionLocalOf { 0 }

@Composable
internal fun observeSettingsRevision(): Int = LocalSettingsRevision.current

@Composable
fun NovelReaderChrome(state: NovelChromeState, settings: AppSettings, tts: ReaderTts) {
	if (!state.active) return
	val layout = rememberNovelBarLayout(settings)
	CompositionLocalProvider(LocalChromeColors provides rememberChromeColors()) {
		Box(Modifier.fillMaxSize()) {
			// The panel belongs to the visible bars; hiding them (or opening the Listen player) closes it.
			LaunchedEffect(state.visible, state.ttsOpen) {
				if (!state.visible || state.ttsOpen) state.settingsOpen = false
			}
			AnimatedVisibility(
				visible = state.visible,
				modifier = Modifier.align(Alignment.TopCenter),
				enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
				exit = slideOutVertically(tween(180)) { -it } + fadeOut(tween(180)),
			) {
				NovelTopBar(state, layout)
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
					NovelBottomBar(state, settings, layout)
				}
			}
		}
	}
}

/** The user's novel bar layout, redrawn as soon as "Customise novel reader bars" saves a change. */
@Composable
private fun rememberNovelBarLayout(settings: AppSettings): NovelBarLayout {
	var layout by remember { mutableStateOf(settings.novelBarLayout) }
	DisposableEffect(settings) {
		layout = settings.novelBarLayout
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key == AppSettings.KEY_NOVEL_BAR_LAYOUT) layout = settings.novelBarLayout
		}
		settings.subscribe(listener)
		onDispose { settings.unsubscribe(listener) }
	}
	return layout
}

/**
 * Both bars as drawn in the reader, for the "Customise novel reader bars" screen's preview. They
 * are the real bars fed with a stand-in chapter, so the preview always matches the reader.
 */
@Composable
internal fun NovelBarsPreview(layout: NovelBarLayout, modifier: Modifier = Modifier) {
	CompositionLocalProvider(LocalChromeColors provides rememberChromeColors()) {
		val sample = remember {
			NovelChromeState().apply {
				chapterTitle = "Chapter 12"
				chapterNumber = 12
				totalChapters = 120
				progressPermille = 400
				minutesLeft = 7
				canOpenWeb = { true }
			}
		}
		Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Box(Modifier.clip(RoundedCornerShape(Bar.radiusSmall))) { NovelTopBar(sample, layout) }
			NovelBottomBarSurface(sample, layout)
		}
	}
}

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun NovelTopBar(state: NovelChromeState, layout: NovelBarLayout) {
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
				layout.shownIn(ReaderBarSlot.TOP_START).forEach { NovelControlButton(it, state, inBottomBar = false) }
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
				layout.shownIn(ReaderBarSlot.TOP_END).forEach { NovelControlButton(it, state, inBottomBar = false) }
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

private val BottomBarHeight = 68.dp

@Composable
private fun NovelBottomBar(state: NovelChromeState, settings: AppSettings, layout: NovelBarLayout) {
	// The panel's open state lives on [state] because the Settings button may sit in the top bar.
	val showSettings = state.settingsOpen
	// Hoisted here (not read from AppSettings each frame) so the floating button reacts to the Options toggle.
	var ttsEnabled by remember { mutableStateOf(settings.isEpubTtsEnabled) }
	androidx.compose.runtime.LaunchedEffect(state.openSettingsTick) {
		if (state.openSettingsTick > 0) state.settingsOpen = true
	}
	Box(modifier = Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier
				.align(Alignment.BottomCenter)
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
						onTtsEnabledChange = { ttsEnabled = it },
						onDismiss = { state.settingsOpen = false },
					)
					Spacer(Modifier.height(8.dp))
				}
			}
			NovelBottomBarSurface(state, layout)
		}
		// Round white Listen button floating above the bar; hidden while the settings panel is open
		if (ttsEnabled && !showSettings) {
			DisposableEffect(Unit) { onDispose { state.setRegion("fab", null) } }
			Surface(
				onClick = { state.onListenClick() },
				shape = CircleShape,
				color = Bar.textPrimary,
				shadowElevation = 6.dp,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 14.dp, bottom = state.bottomInset + 8.dp + BottomBarHeight + 12.dp)
					.size(52.dp)
					.onGloballyPositioned { state.setRegion("fab", it.boundsInWindow()) },
			) {
				Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
					Icon(
						painter = painterResource(R.drawable.ic_novel_headphones),
						contentDescription = "Listen",
						tint = Bar.background,
						modifier = Modifier.size(26.dp),
					)
				}
			}
		}
	}
}

/** The floating bottom bar itself: the user's left buttons, the chapter slider (if on), then the right buttons. */
@Composable
private fun NovelBottomBarSurface(state: NovelChromeState, layout: NovelBarLayout) {
	DisposableEffect(Unit) { onDispose { state.setRegion("bar", null) } }
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.height(BottomBarHeight)
			.onGloballyPositioned { state.setRegion("bar", it.boundsInWindow()) },
		shape = RoundedCornerShape(Bar.radius),
		color = Bar.surface,
		shadowElevation = 0.dp,
	) {
		Row(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 10.dp),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			layout.shownIn(ReaderBarSlot.BOTTOM_START).forEach { NovelControlButton(it, state, inBottomBar = true) }
			if (layout.isSliderShown) {
				CapsuleSlider(
					permille = state.progressPermille,
					onSeek = { state.onSeekChapter(it) },
					modifier = Modifier.weight(1f),
				)
			} else {
				Spacer(Modifier.weight(1f))
			}
			layout.shownIn(ReaderBarSlot.BOTTOM_END).forEach { NovelControlButton(it, state, inBottomBar = true) }
		}
	}
}

/**
 * One customisable button, drawn for either bar: the bottom bar's halo button or the top bar's plain
 * 48dp tap target. What each control does is wired through [NovelChromeState] by ReaderActivity.
 */
@Composable
private fun NovelControlButton(control: NovelControl, state: NovelChromeState, inBottomBar: Boolean) {
	// The web buttons only make sense for an online novel.
	if ((control == NovelControl.WEB_VIEW || control == NovelControl.EXTERNAL_BROWSER) && !state.canOpenWeb()) return
	var rotationTick by remember { mutableIntStateOf(0) }
	val isRotationLocked = control == NovelControl.ROTATE && rotationTick >= 0 && state.isRotationLocked()
	val isOpen = control == NovelControl.OPTIONS && state.settingsOpen
	val isArmed = control == NovelControl.HIGHLIGHT && state.isHighlightArmed
	val icon = when (control) {
		NovelControl.HIGHLIGHT -> if (isArmed) R.drawable.ic_ink_highlighter_filled else R.drawable.ic_ink_highlighter
		NovelControl.ROTATE -> if (isRotationLocked) R.drawable.ic_screen_rotation_lock else R.drawable.ic_screen_rotation
		NovelControl.OPTIONS -> if (isOpen) R.drawable.ic_close else R.drawable.ic_tune
		else -> control.iconResId
	}
	val label = stringResource(control.titleResId)
	val tint = if (isArmed || isOpen) Bar.accent else Bar.textPrimary
	val onClick: () -> Unit = {
		when (control) {
			NovelControl.CHAPTERS -> state.onChaptersClick()
			NovelControl.PREV_CHAPTER -> state.onPrevChapter()
			NovelControl.NEXT_CHAPTER -> state.onNextChapter()
			NovelControl.WEB_VIEW -> state.onWebClick()
			NovelControl.EXTERNAL_BROWSER -> state.onWebLongClick()
			NovelControl.HIGHLIGHT -> state.onHighlightClick()
			NovelControl.SEARCH -> state.onSearchClick()
			NovelControl.ROTATE -> {
				state.onRotateClick()
				rotationTick++
			}
			NovelControl.AUTO_SCROLL -> state.onAutoScrollClick()
			NovelControl.LISTEN -> state.onListenClick()
			NovelControl.OPTIONS -> {
				state.settingsOpen = !state.settingsOpen
			}
			NovelControl.SLIDER -> Unit
		}
	}
	val onLongClick: (() -> Unit)? = if (control == NovelControl.WEB_VIEW) ({ state.onWebLongClick() }) else null
	if (inBottomBar) {
		BarIconButton(
			icon = icon,
			label = label,
			tint = tint,
			small = control == NovelControl.PREV_CHAPTER || control == NovelControl.NEXT_CHAPTER,
			rotation = if (isOpen) 90f else 0f,
			onLongClick = onLongClick,
			onClick = onClick,
		)
	} else {
		Box(
			modifier = Modifier
				.size(48.dp)
				.clip(CircleShape)
				.combinedClickable(
					indication = LocalIndication.current,
					interactionSource = remember { MutableInteractionSource() },
					role = Role.Button,
					onClick = onClick,
					onLongClick = onLongClick,
				),
			contentAlignment = Alignment.Center,
		) {
			Icon(painter = painterResource(icon), contentDescription = label, tint = tint)
		}
	}
}

/**
 * Bottom-bar button: a soft halo fades in behind the icon while the finger is down, and the icon itself
 * dips with the app's pulse press effect (the halo is drawn before the clickable, so only the icon moves).
 */
@Composable
private fun BarIconButton(
	icon: Int,
	label: String,
	tint: Color,
	small: Boolean = false,
	rotation: Float = 0f,
	onLongClick: (() -> Unit)? = null,
	onClick: () -> Unit,
) {
	val source = remember { MutableInteractionSource() }
	val pressed by source.collectIsPressedAsState()
	val halo = animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 70 else 260), label = "halo")
	val turn by animateFloatAsState(rotation, tween(240), label = "turn")
	val haloColor = Bar.textPrimary
	Box(
		modifier = Modifier
			.size(if (small) 42.dp else 50.dp, 54.dp)
			.drawBehind {
				val a = 0.16f * halo.value
				if (a > 0f) {
					drawRoundRect(haloColor.copy(alpha = a), cornerRadius = CornerRadius(size.minDimension / 2f))
				}
			}
			.clip(CircleShape)
			.combinedClickable(
				interactionSource = source,
				indication = LocalIndication.current,
				role = Role.Button,
				onClickLabel = label,
				onLongClick = onLongClick,
				onClick = onClick,
			),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painter = painterResource(icon),
			contentDescription = label,
			tint = tint,
			modifier = Modifier
				.size(if (small) 22.dp else 26.dp)
				.rotate(turn),
		)
	}
}

/**
 * Chapter progress drawn as a capsule with dark dots inside the filled part and a thin marker at the
 * current position. Dragging (or tapping) seeks inside the chapter; while a finger is down the capsule
 * follows it instead of the reader's own progress, which lags behind.
 */
@Composable
private fun CapsuleSlider(permille: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
	var drag by remember { mutableStateOf<Float?>(null) }
	val seek by rememberUpdatedState(onSeek)
	val fraction = (drag ?: (permille / 1000f)).coerceIn(0f, 1f)
	val rail = Bar.surfaceVariant
	val fill = Bar.textPrimary
	val dot = Bar.surface
	// The capsule swells while a finger is on it, so touching it gives visible feedback
	val swell = animateFloatAsState(if (drag != null) 1f else 0f, tween(160), label = "swell")
	Canvas(
		modifier = modifier
			.height(40.dp)
			.pointerInput(Unit) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					var last = -1
					fun move(x: Float) {
						val f = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
						drag = f
						val pm = (f * 1000).roundToInt()
						if (pm != last) {
							last = pm
							seek(pm)
						}
					}
					down.consume()
					move(down.position.x)
					do {
						val event = awaitPointerEvent()
						event.changes.forEach {
							if (it.positionChanged()) {
								it.consume()
								move(it.position.x)
							}
						}
					} while (event.changes.any { it.pressed })
					drag = null
				}
			},
	) {
		val railH = (16f + 6f * swell.value).dp.toPx()
		val top = (size.height - railH) / 2f
		val radius = CornerRadius(railH / 2f, railH / 2f)
		drawRoundRect(rail, Offset(0f, top), Size(size.width, railH), radius)
		if (fraction > 0.002f) {
			val fillW = (size.width * fraction).coerceAtLeast(railH)
			drawRoundRect(fill, Offset(0f, top), Size(fillW, railH), radius)
			val dotR = 1.9.dp.toPx()
			val pitch = 9.dp.toPx()
			clipRect(left = 0f, top = top, right = fillW, bottom = top + railH) {
				var x = 6.dp.toPx() + dotR
				while (x < fillW) {
					drawCircle(dot, dotR, Offset(x, size.height / 2f))
					x += pitch
				}
			}
			val markW = 3.dp.toPx()
			val markX = (size.width * fraction - markW / 2f).coerceIn(0f, size.width - markW)
			drawRoundRect(
				fill.copy(alpha = 0.35f),
				Offset(markX - 2.dp.toPx(), size.height / 2f - 14.dp.toPx()),
				Size(markW + 4.dp.toPx(), 28.dp.toPx()),
				CornerRadius(3.5.dp.toPx()),
			)
			drawRoundRect(
				fill,
				Offset(markX, size.height / 2f - 12.dp.toPx()),
				Size(markW, 24.dp.toPx()),
				CornerRadius(markW / 2f),
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

private enum class NovelSheet { MAIN, ADVANCED, PRESETS }

private enum class NovelPage(val title: String, val icon: Int) {
	READING("Reading", R.drawable.ic_novel_menu_book),
	MORE("More", R.drawable.ic_list_group),
	EXTRA("Extra", R.drawable.ic_tune),
}

private enum class NovelMode(val label: String, val icon: Int, val caption: String) {
	PAGED("Paged", R.drawable.ic_novel_mode_paged, "Page-by-page novel cards"),
	CONTINUOUS("Continuous", R.drawable.ic_novel_mode_continuous, "Smooth vertical scroll"),
	OVERSCROLL("Overscroll", R.drawable.ic_novel_mode_overscroll, "Pull past the edge for the next chapter"),
	TTS("TTS", R.drawable.ic_novel_headphones, "Voice narration"),
}

private val THEME_IDS = listOf("system", "white", "gray", "black", "sepia", "dusk", "forest")

private fun resetReaderSettings(settings: AppSettings) {
	applyPreset(settings, NOVEL_DEFAULT)
	settings.epubReadingMode = "scroll"
	settings.epubScrollStyle = "continuous"
	settings.isEpubBionicReadingEnabled = false
	settings.isEpubPublisherStyleEnabled = false
}

/**
 * The reader's quick settings sheet. Main page: mode selector, four tiles and a way into the advanced
 * options, with Reading / More / Extra pages along the bottom. Advanced options and presets are sub-screens.
 */
@Composable
private fun NovelSettingsPanel(
	state: NovelChromeState,
	settings: AppSettings,
	onTtsEnabledChange: (Boolean) -> Unit,
	onDismiss: () -> Unit,
) {
	var sheet by remember { mutableStateOf(NovelSheet.MAIN) }
	var page by remember { mutableStateOf(NovelPage.READING) }
	// Where the back arrow of the presets list leads: it opens from the Reading page and from Advanced options
	var presetsBack by remember { mutableStateOf(NovelSheet.MAIN) }
	// Bumped after every write so the panel re-reads AppSettings (it is not observable Compose state).
	var revision by remember { mutableIntStateOf(0) }
	val refresh: () -> Unit = { revision++ }
	val tick = revision
	val scroll = rememberScrollState()
	LaunchedEffect(sheet, page) { scroll.scrollTo(0) }
	DisposableEffect(Unit) { onDispose { state.setRegion("panel", null) } }

	CompositionLocalProvider(LocalSettingsRevision provides tick) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(max = 540.dp)
			.onGloballyPositioned { state.setRegion("panel", it.boundsInWindow()) },
		shape = RoundedCornerShape(Bar.radius),
		color = Bar.surface,
		shadowElevation = 0.dp,
	) {
		Column {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 10.dp, bottom = 8.dp),
				contentAlignment = Alignment.Center,
			) {
				Box(
					Modifier
						.size(36.dp, 4.dp)
						.clip(CircleShape)
						.background(Bar.textDisabled.copy(alpha = 0.5f)),
				)
			}
			Column(
				modifier = Modifier
					.weight(1f, fill = false)
					.verticalScroll(scroll)
					.padding(horizontal = 14.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				when (sheet) {
					NovelSheet.MAIN -> when (page) {
						NovelPage.READING -> ReadingPage(
							state, settings, tick, refresh, onDismiss,
							onOpenAdvanced = { sheet = NovelSheet.ADVANCED },
							onOpenPresets = { presetsBack = NovelSheet.MAIN; sheet = NovelSheet.PRESETS },
						)
						NovelPage.MORE -> MorePage(state, settings, tick, refresh, onTtsEnabledChange)
						NovelPage.EXTRA -> ExtraPage(state, settings, tick, refresh, onDismiss)
					}
					NovelSheet.ADVANCED -> AdvancedView(
						settings, tick, refresh,
						onBack = { sheet = NovelSheet.MAIN },
						onPresets = { presetsBack = NovelSheet.ADVANCED; sheet = NovelSheet.PRESETS },
					)
					NovelSheet.PRESETS -> {
						SubHeader("Presets", onBack = { sheet = presetsBack })
						PresetsTab(settings, refresh, tick)
					}
				}
				Spacer(Modifier.height(2.dp))
			}
			if (sheet == NovelSheet.MAIN) {
				PillTabs(page) { page = it }
			} else {
				Spacer(Modifier.height(12.dp))
			}
		}
	}
	}
}

// ---- main sheet pages -----------------------------------------------------------------------

@Composable
private fun ReadingPage(
	state: NovelChromeState,
	settings: AppSettings,
	rev: Int,
	refresh: () -> Unit,
	onDismiss: () -> Unit,
	onOpenAdvanced: () -> Unit,
	onOpenPresets: () -> Unit,
) {
	observeSettingsRevision()
	// The TTS mode is always there; the Options toggle only controls the floating Listen button
	val modes = NovelMode.entries
	val current = when {
		state.ttsOpen -> NovelMode.TTS
		settings.epubReadingMode == "paged" -> NovelMode.PAGED
		settings.epubScrollStyle == "overscroll" -> NovelMode.OVERSCROLL
		else -> NovelMode.CONTINUOUS
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(22.dp))
			.background(Bar.surfaceVariant)
			.padding(6.dp),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		modes.forEach { mode ->
			val selected = mode == current
			Surface(
				onClick = {
					when (mode) {
						NovelMode.PAGED -> settings.epubReadingMode = "paged"
						NovelMode.CONTINUOUS -> {
							settings.epubReadingMode = "scroll"
							settings.epubScrollStyle = "continuous"
						}
						NovelMode.OVERSCROLL -> {
							settings.epubReadingMode = "scroll"
							settings.epubScrollStyle = "overscroll"
						}
						NovelMode.TTS -> {
							onDismiss()
							state.onListenClick()
						}
					}
					refresh()
				},
				modifier = Modifier.weight(1f),
				shape = RoundedCornerShape(17.dp),
				color = if (selected) Bar.textPrimary else Color.Transparent,
			) {
				Column(
					modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					val tint = if (selected) Bar.background else Bar.textSecondary
					Icon(painterResource(mode.icon), null, tint = tint, modifier = Modifier.size(20.dp))
					Text(
						text = mode.label,
						fontSize = 11.sp,
						fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
						color = tint,
						maxLines = 1,
					)
				}
			}
		}
	}
	Text(
		text = current.caption,
		style = MaterialTheme.typography.labelSmall,
		color = Bar.textMuted,
		modifier = Modifier.padding(horizontal = 4.dp),
	)
	Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
		SettingsTile(
			title = "Auto scroll",
			subtitle = "Set the speed",
			modifier = Modifier.weight(1f),
			icon = { Icon(painterResource(R.drawable.ic_timer), null, tint = Bar.textPrimary, modifier = Modifier.size(16.dp)) },
			onClick = { onDismiss(); state.onAutoScrollClick() },
		)
		SettingsTile(
			title = "Text & font",
			subtitle = "${fontLabel(settings.epubFontFamily)} \u2022 %.1fx".format(settings.epubLineHeight / 100f),
			badge = "${settings.epubFontSize}%",
			modifier = Modifier.weight(1f),
			icon = { Text("Aa", fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, color = Bar.textPrimary) },
			onClick = onOpenAdvanced,
		)
	}
	Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
		val locked = state.isRotationLocked()
		SettingsTile(
			title = "Rotate screen",
			subtitle = if (locked) "Orientation locked" else "Auto-rotate enabled",
			modifier = Modifier.weight(1f),
			icon = {
				Icon(
					painterResource(if (locked) R.drawable.ic_screen_rotation_lock else R.drawable.ic_screen_rotation),
					null,
					tint = Bar.textPrimary,
					modifier = Modifier.size(16.dp),
				)
			},
			onClick = { state.onRotateClick(); refresh() },
		)
		val currentTheme = when (settings.epubTheme) {
			"light" -> "white"
			"dark" -> "gray"
			else -> settings.epubTheme
		}
		SettingsTile(
			title = "Color & theme",
			subtitle = themeLabel(currentTheme),
			modifier = Modifier.weight(1f),
			icon = { Icon(painterResource(R.drawable.ic_novel_palette), null, tint = Bar.textPrimary, modifier = Modifier.size(16.dp)) },
			onClick = {
				val next = THEME_IDS[(THEME_IDS.indexOf(currentTheme) + 1) % THEME_IDS.size]
				settings.epubTheme = next
				refresh()
			},
		)
	}
	Surface(
		onClick = onOpenAdvanced,
		shape = RoundedCornerShape(20.dp),
		color = Bar.surfaceVariant,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TileIcon(size = 36.dp) {
				Icon(painterResource(R.drawable.ic_settings), null, tint = Bar.textPrimary, modifier = Modifier.size(16.dp))
			}
			Column(Modifier.weight(1f)) {
				Text("Advanced reader options", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary)
				Text(
					"Font size, fonts, margins, theme & presets",
					style = MaterialTheme.typography.labelSmall,
					color = Bar.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Bar.textMuted, modifier = Modifier.size(20.dp))
		}
	}
	// Active preset (yours first, then the built-in ones); "Custom" once settings drift from all of them
	val activePreset = NovelPresetStore.load(settings).firstOrNull { NovelPresetStore.isActive(settings, it) }?.name
		?: NOVEL_PRESETS.firstOrNull { isPresetActive(settings, it) }?.name
		?: "Custom"
	Surface(
		onClick = onOpenPresets,
		shape = RoundedCornerShape(20.dp),
		color = Bar.surfaceVariant,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TileIcon(size = 36.dp) {
				Icon(painterResource(R.drawable.ic_sparkles), null, tint = Bar.textPrimary, modifier = Modifier.size(16.dp))
			}
			Column(Modifier.weight(1f)) {
				Text("Presets", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary)
				Text(
					activePreset,
					style = MaterialTheme.typography.labelSmall,
					color = Bar.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Bar.textMuted, modifier = Modifier.size(20.dp))
		}
	}
}

@Composable
private fun MorePage(
	state: NovelChromeState,
	settings: AppSettings,
	rev: Int,
	refresh: () -> Unit,
	onTtsEnabledChange: (Boolean) -> Unit,
) {
	observeSettingsRevision()
	ToggleRow("Bionic reading", settings.isEpubBionicReadingEnabled, "Bold word prefixes for faster scanning") {
		settings.isEpubBionicReadingEnabled = it; refresh()
	}
	ToggleRow("Use book formatting", settings.isEpubPublisherStyleEnabled, "Follow the book's own typography") {
		settings.isEpubPublisherStyleEnabled = it; refresh()
	}
	ToggleRow("Chapter header", settings.isEpubChapterHeaderEnabled, "Display the chapter title above the text") {
		settings.isEpubChapterHeaderEnabled = it; refresh()
	}
	if (settings.isEpubChapterHeaderEnabled) {
		ToggleRow("Name only", settings.isEpubHeaderNameOnly, "Remove the chapter number from the header title") {
			settings.isEpubHeaderNameOnly = it; refresh()
		}
		val redundancyLabels = listOf("off" to "Off", "number" to "Number", "name" to "Name", "both" to "Both")
		Section("Remove redundancy", redundancyLabels.firstOrNull { it.first == settings.epubRemoveRedundancy }?.second) {
			ChipFlow(redundancyLabels, settings.epubRemoveRedundancy) {
				settings.epubRemoveRedundancy = it; refresh()
			}
		}
		Text(
			text = "Removes the chapter number, name or both when the text itself repeats it at the top (scroll mode)",
			style = MaterialTheme.typography.labelSmall,
			color = Bar.textMuted,
		)
	}
	ToggleRow("TTS floating icon", settings.isEpubTtsEnabled, "Show the round Listen button above the bar") {
		settings.isEpubTtsEnabled = it
		// Only the shortcut goes away: the TTS mode stays in the quick settings, and anything playing keeps playing
		onTtsEnabledChange(it)
		refresh()
	}
}

@Composable
private fun ExtraPage(state: NovelChromeState, settings: AppSettings, rev: Int, refresh: () -> Unit, onDismiss: () -> Unit) {
	observeSettingsRevision()
	ToggleRow("Translate chapters", settings.isNovelTranslationEnabled, "Auto-localize translated webnovel prose") {
		settings.isNovelTranslationEnabled = it; refresh()
	}
	if (settings.isNovelTranslationEnabled) {
		SheetCard {
			Section("Translate to", NovelTranslationLanguages.nameOf(settings.novelTranslationTarget)) {
				ChipFlow(
					options = NovelTranslationLanguages.all,
					selected = settings.novelTranslationTarget,
				) { settings.novelTranslationTarget = it; refresh() }
			}
		}
		SheetCard {
			Section("Translate from", NovelTranslationLanguages.nameOf(settings.novelTranslationSource)) {
				ChipFlow(
					options = listOf(NovelTranslationLanguages.AUTO to "Auto") + NovelTranslationLanguages.all,
					selected = settings.novelTranslationSource,
				) { settings.novelTranslationSource = it; refresh() }
			}
		}
	}
	ActionRow("Search in book", R.drawable.ic_search) { onDismiss(); state.onSearchClick() }
	ActionRow(stringResource(R.string.novel_bars_customize), R.drawable.ic_tune) { onDismiss(); state.onCustomizeBarsClick() }
}

// ---- advanced options sub-screen --------------------------------------------------------------

@Composable
private fun AdvancedView(
	settings: AppSettings,
	rev: Int,
	refresh: () -> Unit,
	onBack: () -> Unit,
	onPresets: () -> Unit,
) {
	observeSettingsRevision()
	val modified = countModified(settings)
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.clip(RoundedCornerShape(12.dp))
				.combinedClickable(onClick = onBack)
				.padding(vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(painterResource(R.drawable.ic_arrow_back), null, tint = Bar.textPrimary, modifier = Modifier.size(20.dp))
			Column {
				Text("Advanced reader options", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary, maxLines = 1)
				if (modified > 0) {
					Text("$modified changes from default", style = MaterialTheme.typography.labelSmall, color = Bar.textMuted)
				}
			}
		}
		SmallChip("Presets", onPresets)
		SmallChip("Reset") { resetReaderSettings(settings); refresh() }
	}
	SheetCard { TextTab(settings, refresh, rev) }
	SheetCard { LayoutTab(settings, refresh, rev) }
	SheetCard { Section("Theme", null) { ThemeTab(settings, refresh, rev) } }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.combinedClickable(onClick = onBack)
			.padding(vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Icon(painterResource(R.drawable.ic_arrow_back), null, tint = Bar.textPrimary, modifier = Modifier.size(20.dp))
		Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary)
	}
}

// ---- sheet pieces -------------------------------------------------------------------------------

@Composable
private fun PillTabs(selected: NovelPage, onSelect: (NovelPage) -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 14.dp)
			.padding(top = 8.dp, bottom = 12.dp)
			.clip(CircleShape)
			.background(Bar.surfaceVariant)
			.padding(4.dp),
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		NovelPage.entries.forEach { p ->
			val sel = p == selected
			Surface(
				onClick = { onSelect(p) },
				modifier = Modifier.weight(1f),
				shape = CircleShape,
				color = if (sel) Bar.textPrimary else Color.Transparent,
			) {
				Row(
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
					verticalAlignment = Alignment.CenterVertically,
				) {
					val tint = if (sel) Bar.background else Bar.textSecondary
					Icon(painterResource(p.icon), null, tint = tint, modifier = Modifier.size(14.dp))
					Text(p.title, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold, color = tint, maxLines = 1)
				}
			}
		}
	}
}

@Composable
private fun TileIcon(size: Dp = 32.dp, content: @Composable () -> Unit) {
	Surface(shape = CircleShape, color = Bar.surfaceElevated, modifier = Modifier.size(size)) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
	}
}

@Composable
private fun SettingsTile(
	title: String,
	subtitle: String,
	modifier: Modifier = Modifier,
	badge: String? = null,
	icon: @Composable () -> Unit,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		modifier = modifier.height(108.dp),
		shape = RoundedCornerShape(20.dp),
		color = Bar.surfaceVariant,
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(14.dp),
			verticalArrangement = Arrangement.SpaceBetween,
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.Top,
			) {
				TileIcon { icon() }
				if (badge != null) {
					Text(badge, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Bar.textMuted)
				}
			}
			Column {
				Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary, maxLines = 1)
				Text(
					subtitle,
					style = MaterialTheme.typography.labelSmall,
					color = Bar.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
	Surface(onClick = onClick, shape = CircleShape, color = Bar.surfaceElevated) {
		Text(
			label,
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.SemiBold,
			color = Bar.textPrimary,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
		)
	}
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(18.dp))
			.background(Bar.surfaceVariant)
			.padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(14.dp),
	) { content() }
}

// ---- tabs ----------------------------------------------------------------------------------

@Composable
private inline fun PresetsTab(settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	observeSettingsRevision()
	Surface(
		shape = RoundedCornerShape(12.dp),
		color = Bar.accentSubtle,
		border = BorderStroke(1.dp, Bar.accent.copy(alpha = 0.2f)),
	) {
		Row(
			modifier = Modifier.padding(12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_sparkles),
				contentDescription = null,
				tint = Bar.accent,
				modifier = Modifier.size(20.dp),
			)
			Text(
				text = "Choose a preset for quick setup, then fine-tune it in Advanced reader options.",
				style = MaterialTheme.typography.bodySmall,
				color = Bar.textSecondary,
			)
		}
	}
	// The user's own presets come first, newest at the top, then the built-in ones
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
									modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
}

@Composable
private inline fun TextTab(settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	observeSettingsRevision()
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
	observeSettingsRevision()
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
	observeSettingsRevision()
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
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
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
							.padding(vertical = 12.dp),
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

// ---- building blocks -----------------------------------------------------------------------

@Composable
internal fun Section(title: String, value: String?, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text(
				text = title,
				fontSize = 12.sp,
				fontWeight = FontWeight.Bold,
				color = Bar.textPrimary,
			)
			if (value != null) {
				Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Bar.textSecondary)
			}
		}
		content()
	}
}

/**
 * Slider drawn as a rail with a white fill and a thin vertical marker. While a finger is down the handle
 * follows the finger from local state, so it moves every frame even when the settings behind it (not
 * observable by Compose) or the reader reflow are slow. Every pointer movement is consumed so a finger that
 * drifts up or down while dragging cannot scroll the panel.
 */
@Composable
internal fun SliderRow(
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	onFinished: () -> Unit = {},
	onChange: (Float) -> Unit,
) {
	var drag by remember { mutableStateOf<Float?>(null) }
	val change by rememberUpdatedState(onChange)
	val finished by rememberUpdatedState(onFinished)
	val shown = (drag ?: value).coerceIn(range.start, range.endInclusive)
	val span = (range.endInclusive - range.start).coerceAtLeast(0.0001f)
	val fraction = ((shown - range.start) / span).coerceIn(0f, 1f)
	val rail = Bar.surfaceElevated
	val fill = Bar.textPrimary
	val endDot = Bar.textMuted
	Canvas(
		modifier = Modifier
			.fillMaxWidth()
			.height(28.dp)
			.pointerInput(range.start, range.endInclusive) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					fun move(x: Float) {
						val f = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
						val v = range.start + f * span
						drag = v
						change(v)
					}
					down.consume()
					move(down.position.x)
					do {
						val event = awaitPointerEvent()
						event.changes.forEach {
							if (it.positionChanged()) {
								it.consume()
								move(it.position.x)
							}
						}
					} while (event.changes.any { it.pressed })
					drag = null
					finished()
				}
			},
	) {
		val railH = 11.dp.toPx()
		val top = (size.height - railH) / 2f
		val radius = CornerRadius(railH / 2f, railH / 2f)
		drawRoundRect(rail, Offset(0f, top), Size(size.width, railH), radius)
		val fillW = (size.width * fraction).coerceAtLeast(railH)
		drawRoundRect(fill, Offset(0f, top), Size(fillW, railH), radius)
		drawCircle(endDot, 2.dp.toPx(), Offset(size.width - 10.dp.toPx(), size.height / 2f))
		val markW = 3.dp.toPx()
		val markX = (size.width * fraction - markW / 2f).coerceIn(0f, size.width - markW)
		drawRoundRect(
			fill,
			Offset(markX, size.height / 2f - 10.dp.toPx()),
			Size(markW, 20.dp.toPx()),
			CornerRadius(markW / 2f),
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
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		options.chunked(3).forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
				row.forEach { (value, label) ->
					val isSelected = selected == value
					Surface(
						onClick = { onSelect(value) },
						modifier = Modifier.weight(1f),
						shape = RoundedCornerShape(12.dp),
						color = if (isSelected) Bar.textPrimary else Bar.surfaceElevated,
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp)) {
							Text(
								text = label,
								fontFamily = fontFor(value),
								fontSize = 12.sp,
								fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								color = if (isSelected) Bar.background else Bar.textSecondary,
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
internal fun ToggleRow(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
	// The row keeps the value it shows, so the switch flips the moment it is tapped and the next tap reverses it,
	// even if the caller only learns the new value from a preference that Compose cannot observe.
	var shown by remember(checked) { mutableStateOf(checked) }
	Surface(
		onClick = {
			shown = !shown
			onChange(shown)
		},
		shape = RoundedCornerShape(18.dp),
		color = Bar.surfaceVariant,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 10.dp),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(text = label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary)
				if (subtitle != null) {
					Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Bar.textMuted)
				}
			}
			Spacer(Modifier.size(12.dp))
			val knobX by animateDpAsState(if (shown) 20.dp else 0.dp, label = "knob")
			Box(
				modifier = Modifier
					.size(46.dp, 26.dp)
					.clip(CircleShape)
					.background(if (shown) Bar.textPrimary else Bar.surfaceElevated)
					.padding(3.dp),
			) {
				Box(
					modifier = Modifier
						.offset(x = knobX)
						.size(20.dp)
						.clip(CircleShape)
						.background(if (shown) Bar.background else Bar.textMuted),
				)
			}
		}
	}
}

@Composable
internal fun ActionRow(label: String, icon: Int, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(18.dp),
		color = Bar.surfaceVariant,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(painter = painterResource(icon), contentDescription = null, tint = Bar.textSecondary, modifier = Modifier.size(20.dp))
			Text(text = label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Bar.textPrimary)
		}
	}
}
