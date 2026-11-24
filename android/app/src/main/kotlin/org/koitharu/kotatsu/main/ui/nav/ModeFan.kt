package org.koitharu.kotatsu.main.ui.nav

import android.view.View
import android.app.Activity
import android.content.ContextWrapper
import android.view.Window
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.home.ui.model.StreamingKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The mode button's gestures, after the 90-degree fan prototype:
 *
 *  - **Tap**: continue reading / watching (with nothing to resume, it opens the fan to pick from).
 *  - **Pull** (drag past 14dp): a quarter-circle fan blooms from the button, the nav bar slides away and the page
 *    dims. The mode under the thumb is highlighted with a glowing spoke and a tick; releasing switches to it.
 *  - **Hold** for 0.85 s without pulling: opens the details of the last title, with a ring charging around the
 *    button and two soft ticks on the way.
 *
 * The Anime slot is **Streaming**. With the Streaming default off, the node splits in two while the thumb rests on it:
 * the very same shape, cut into an Anime half and a TV & Movies half. Releasing without touching a half lands on the
 * kind already showing (or Anime). In the tap-opened fan, tapping Streaming splits it and a second tap picks one.
 * With a Streaming default set, the node simply shows that kind (its own icon and name) and never splits.
 *
 * Every step has its own haptic: a start when the fan opens, a tick each time the highlighted mode changes, a
 * confirm when the mode is switched, a long press when the hold completes. All of them go through the app's
 * haptics switch.
 */
private const val HOLD_MS = 850

/** Node angles measured from "straight left" (0) to "straight up" (90), as in the prototype. */
private fun HomeMode.fanAngle(): Float = when (this) {
	HomeMode.ANIME -> 72f
	HomeMode.MANGA -> 45f
	HomeMode.NOVEL -> 18f
}

/** Room kept free above the fan for the status bar and the "Switch mode" pill. */
private val FAN_TOP_ROOM = 150.dp
private val FAN_MIN_RADIUS = 230.dp
private val FAN_MIN_NODE_RADIUS = 150.dp
private val FAN_EDGE_MARGIN = 16.dp

/** The small gap between the button and the panel's right and bottom edges, the same on both sides. */
private val FAN_CORNER_GAP = 6.dp

/** Corner rounding of the panel: at the button's corner, and at the two outer ends of the arc. */
private val FAN_TIP_CORNER = 32.dp
private val FAN_END_CORNER = 26.dp

/** The gap between the panel's corner and the screen's right and bottom edges: barely a millimetre, as in the prototype. */
private val FAN_SCREEN_GAP = 6.dp
private val PILL_MAX_WIDTH = 270.dp
private val NODE_W = 72.dp

/** The Streaming node splits into an Anime half and a TV & Movies half: same width, same corners, a thin gap between. */
private val SPLIT_GAP = 6.dp
private val SPLIT_INNER_CORNER = 6.dp
private val NODE_CORNER = 21.dp
private const val SELECTED_SCALE = 1.16f
private val PILL_SLOP = 6.dp
private val NODE_H = 68.dp
private val FAB_SIZE = 52.dp

/** The fan always floats over a black scrim, so it keeps the prototype's dark glass palette in light and dark themes. */
private val GlassTop = Color(0xFF2E2E36)
private val GlassBottom = Color(0xFF18181D)
private val NodeFill = Color(0xFF2C2C30)
private val PillFill = Color(0xFF18181C)
private val MutedText = Color(0xFFA1A1AA)
private val Emerald = Color(0xFF34C759)
private val EmeraldOnWhite = Color(0xFF059669)

private enum class FanEnd { TAP, PULL, HOLD }

/** The icon of the mode button and chips: in Streaming it shows which half is on. */
@DrawableRes
internal fun modeIconRes(mode: HomeMode, kind: StreamingKind = StreamingKind.ANIME): Int = when (mode) {
	HomeMode.ANIME -> streamingKindIconRes(kind)
	HomeMode.NOVEL -> R.drawable.ic_novel_menu_book
	HomeMode.MANGA -> R.drawable.ic_read
}

@DrawableRes
private fun streamingKindIconRes(kind: StreamingKind): Int = when (kind) {
	StreamingKind.ANIME -> R.drawable.ic_play_circle
	StreamingKind.TV_MOVIES -> R.drawable.ic_tv
}

private fun modeTitleRes(mode: HomeMode): Int = when (mode) {
	HomeMode.ANIME -> R.string.ds_mode_streaming
	HomeMode.NOVEL -> R.string.ds_mode_novel
	HomeMode.MANGA -> R.string.ds_mode_manga
}

/**
 * Where the fan sits: a true quarter-circle whose corner [center] is the bottom-right corner of the screen (a hair
 * indented), exactly like the prototype. The button copy [fabCenter] is tucked into that corner, and the three modes are
 * on a ring of [nodeRadius] around [center]. None of it depends on where the real button is on the page.
 */
private class FanGeometry(val center: Offset, val fabCenter: Offset, val radius: Float, val nodeRadius: Float)

/** The activity window behind [this] view, if any. */
private fun View.hostWindow(): Window? {
	var ctx = context
	while (ctx is ContextWrapper) {
		if (ctx is Activity) return ctx.window
		ctx = ctx.baseContext
	}
	return null
}

private fun Density.fanGeometry(window: Size): FanGeometry {
	val half = (FAB_SIZE / 2).toPx()
	val gap = FAN_CORNER_GAP.toPx()
	val edge = FAN_SCREEN_GAP.toPx()
	// Anchored to the screen corner, not to the real button: the system navigation is hidden while the fan is open,
	// so the panel can sit right at the edge with gesture navigation and with three buttons alike.
	val origin = Offset(window.width - edge, window.height - edge)
	val fab = Offset(origin.x - gap - half, origin.y - gap - half)
	val radius = min(window.width * 0.7f, min(origin.y - FAN_TOP_ROOM.toPx(), origin.x - FAN_EDGE_MARGIN.toPx()))
		.coerceAtLeast(FAN_MIN_RADIUS.toPx())
	return FanGeometry(origin, fab, radius, radius * 0.84f)
}

/** The quarter-circle with soft corners: [tip] at the button's corner, [end] at the two outer ends of the arc. */
private fun fanPath(origin: Offset, r: Float, tip: Float, end: Float): Path {
	val tTop = Math.toRadians(Math.toDegrees((end / r).toDouble()))
	val tDeg = Math.toDegrees((end / r).toDouble()).toFloat()
	val rect = Rect(origin.x - r, origin.y - r, origin.x + r, origin.y + r)
	val tipRect = Rect(origin.x - tip * 2, origin.y - tip * 2, origin.x, origin.y)
	return Path().apply {
		moveTo(origin.x - tip, origin.y)
		arcTo(tipRect, 90f, -90f, false)
		lineTo(origin.x, origin.y - r + end)
		quadraticBezierTo(origin.x, origin.y - r, origin.x - r * sin(tTop).toFloat(), origin.y - r * cos(tTop).toFloat())
		arcTo(rect, 270f - tDeg, -(90f - 2f * tDeg), false)
		quadraticBezierTo(origin.x - r, origin.y, origin.x - r + end, origin.y)
		close()
	}
}

/** Picks the mode for a thumb [dx]/[dy] away from the button (both positive towards left/up), or null near the button. */
private fun pickMode(dx: Float, dy: Float, radiusPx: Float, nearPx: Float, deadPx: Float): HomeMode? {
	if (hypot(dx, dy) < deadPx) return null
	// Magnetic: close to a node wins over the angle zones.
	HomeMode.entries.minByOrNull { m ->
		val a = Math.toRadians(m.fanAngle().toDouble())
		hypot(dx - radiusPx * cos(a).toFloat(), dy - radiusPx * sin(a).toFloat())
	}?.let { m ->
		val a = Math.toRadians(m.fanAngle().toDouble())
		if (hypot(dx - radiusPx * cos(a).toFloat(), dy - radiusPx * sin(a).toFloat()) < nearPx) return m
	}
	val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
	return when {
		angle >= 58.5f -> HomeMode.ANIME
		angle >= 31.5f -> HomeMode.MANGA
		else -> HomeMode.NOVEL
	}
}

/** The half (Anime above, TV & Movies below) under a thumb at [x]/[y] in the window, or null when it is on neither. */
private fun Density.streamingPillAt(x: Float, y: Float, g: FanGeometry): StreamingKind? {
	val a = Math.toRadians(HomeMode.ANIME.fanAngle().toDouble())
	val cx = g.center.x - g.nodeRadius * cos(a).toFloat()
	val cy = g.center.y - g.nodeRadius * sin(a).toFloat()
	val halfW = NODE_W.toPx() * SELECTED_SCALE / 2f + PILL_SLOP.toPx()
	val halfH = NODE_H.toPx() * SELECTED_SCALE / 2f + PILL_SLOP.toPx()
	if (abs(x - cx) > halfW || abs(y - cy) > halfH) return null
	return if (y < cy) StreamingKind.ANIME else StreamingKind.TV_MOVIES
}

@Composable
internal fun ModeFanButton(
	mode: HomeMode,
	streamingKind: StreamingKind,
	/** The kind Streaming shows directly, or null when the fan splits it into Anime and TV & Movies. */
	streamingDefault: StreamingKind?,
	continueEnabled: Boolean,
	fanOpen: Boolean,
	onFanOpenChange: (Boolean) -> Unit,
	onModeSelected: (HomeMode) -> Unit,
	onStreamingSelected: (StreamingKind) -> Unit,
	onContinue: () -> Unit,
	onDetails: () -> Unit,
) {
	val haptic = rememberHapticEffect()
	val view = LocalView.current
	val scope = rememberCoroutineScope()
	val ring = remember { Animatable(0f) }
	var holding by remember { mutableStateOf(false) }
	var highlighted by remember { mutableStateOf<HomeMode?>(null) }
	// Which Streaming pill is lit while Streaming is highlighted
	var highlightedKind by remember { mutableStateOf(streamingDefault ?: streamingKind) }
	var locked by remember { mutableStateOf(false) }
	// Where the real button is in the window right now, for the thumb maths. The fan itself is anchored to the
	// screen corner and ignores it.
	var liveCenter by remember { mutableStateOf(Offset.Zero) }
	val currentMode by rememberUpdatedState(mode)
	val canContinue by rememberUpdatedState(continueEnabled)
	val setOpen by rememberUpdatedState(onFanOpenChange)
	val select by rememberUpdatedState(onModeSelected)
	val currentKind by rememberUpdatedState(streamingKind)
	val defaultKind by rememberUpdatedState(streamingDefault)
	val selectStreaming by rememberUpdatedState(onStreamingSelected)
	val doContinue by rememberUpdatedState(onContinue)
	val doDetails by rememberUpdatedState(onDetails)

	fun closeFan() {
		highlighted = null
		locked = false
		setOpen(false)
	}

	/** What Streaming lands on when the thumb is not on a half: the default if one is set, else the kind already showing. */
	fun restingKind(): StreamingKind = defaultKind ?: if (currentMode == HomeMode.ANIME) currentKind else StreamingKind.ANIME

	fun applyChoice(picked: HomeMode, kind: StreamingKind) {
		if (picked == HomeMode.ANIME) selectStreaming(kind) else select(picked)
	}

	fun openFan(isLocked: Boolean) {
		locked = isLocked
		highlighted = currentMode
		highlightedKind = restingKind()
		haptic(HapticEffect.GESTURE_START)
		setOpen(true)
	}

	// The system navigation (the gesture line or the three buttons) is tucked away while the fan is open, so the
	// corner stays clean like in the prototype. A swipe from the edge still brings it back for a moment.
	DisposableEffect(fanOpen) {
		val controller = view.hostWindow()?.let { WindowCompat.getInsetsController(it, view) }
		val previous = controller?.systemBarsBehavior
		val hide = fanOpen && controller != null
		if (hide && controller != null) {
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			controller.hide(WindowInsetsCompat.Type.navigationBars())
		}
		onDispose {
			if (hide && controller != null) {
				controller.show(WindowInsetsCompat.Type.navigationBars())
				if (previous != null) controller.systemBarsBehavior = previous
			}
		}
	}

	val fanProgress by animateFloatAsState(
		targetValue = if (fanOpen) 1f else 0f,
		animationSpec = spring(dampingRatio = 0.62f, stiffness = 380f),
		label = "fanProgress",
	)
	val pressScale by animateFloatAsState(
		targetValue = if (holding) 0.92f else 1f,
		animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
		label = "fabPress",
	)
	val cs = MaterialTheme.colorScheme
	val container by animateColorAsState(
		targetValue = if (fanOpen) cs.surfaceContainerHighest else cs.primaryContainer,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "fabContainer",
	)
	val content by animateColorAsState(
		targetValue = if (fanOpen) cs.onSurface else cs.onPrimaryContainer,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "fabContent",
	)
	val ringColor = cs.primary
	val ringTrack = cs.onSurface.copy(alpha = 0.12f)
	val label = stringResource(R.string.ds_mode_switch)
	val actionDetails = stringResource(R.string.ds_mode_open_details)
	val actionContinue = stringResource(R.string._continue)
	val labelAnime = stringResource(R.string.ds_mode_anime)
	val labelTvMovies = stringResource(R.string.ds_mode_tv_movies)
	val labelManga = stringResource(R.string.ds_mode_manga)
	val labelNovel = stringResource(R.string.ds_mode_novel)

	Box(modifier = Modifier.padding(horizontal = 2.dp)) {
		Surface(
			shape = RoundedCornerShape(16.dp),
			color = container,
			contentColor = content,
			border = if (fanOpen) androidx.compose.foundation.BorderStroke(1.5.dp, cs.outline.copy(alpha = 0.5f)) else null,
			modifier = Modifier
				.size(FAB_SIZE)
				.onGloballyPositioned { liveCenter = it.boundsInWindow().center }
				// The real button steps aside while the fan is open (its copy sits in the screen corner), but it stays
				// in the layout, so it keeps receiving the gesture and can never ghost through the scrim.
				.graphicsLayer {
					scaleX = pressScale
					scaleY = pressScale
					alpha = 1f - fanProgress.coerceIn(0f, 1f)
				}
				.drawBehind {
					// Charge ring for the one-second hold, drawn just outside the button
					if (ring.value > 0.001f) {
						val pad = 6.dp.toPx()
						val topLeft = Offset(-pad, -pad)
						val ringSize = Size(size.width + pad * 2, size.height + pad * 2)
						drawArc(ringTrack, 0f, 360f, false, topLeft, ringSize, style = Stroke(3.dp.toPx()))
						drawArc(
							ringColor, -90f, 360f * ring.value, false, topLeft, ringSize,
							style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
						)
					}
				}
				.semantics {
					contentDescription = label
					customActions = listOf(
						CustomAccessibilityAction(labelAnime) { selectStreaming(StreamingKind.ANIME); true },
						CustomAccessibilityAction(labelTvMovies) { selectStreaming(StreamingKind.TV_MOVIES); true },
						CustomAccessibilityAction(labelManga) { select(HomeMode.MANGA); true },
						CustomAccessibilityAction(labelNovel) { select(HomeMode.NOVEL); true },
					) + if (continueEnabled) {
						listOf(
							CustomAccessibilityAction(actionContinue) { doContinue(); true },
							CustomAccessibilityAction(actionDetails) { doDetails(); true },
						)
					} else {
						emptyList()
					}
				}
				.pointerInput(Unit) {
					val pullPx = 14.dp.toPx()
					val nearPx = 54.dp.toPx()
					val deadPx = 24.dp.toPx()
					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						down.consume()
						holding = true
						val start = down.position
						val charge = scope.launch {
							ring.snapTo(0f)
							launch {
								// Two soft ticks while the ring fills, so the hold feels like it is charging
								delay(HOLD_MS / 3L)
								haptic(HapticEffect.LIGHT_TICK)
								delay(HOLD_MS / 3L)
								haptic(HapticEffect.LIGHT_TICK)
							}
							ring.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
						}
						var end: FanEnd? = null
						try {
							withTimeoutOrNull(HOLD_MS.toLong()) {
								while (end == null) {
									val event = awaitPointerEvent()
									val change = event.changes.firstOrNull { it.id == down.id }
									if (change == null) {
										end = FanEnd.TAP
										break
									}
									change.consume()
									end = when {
										!change.pressed -> FanEnd.TAP
										(change.position - start).getDistance() > pullPx -> FanEnd.PULL
										else -> null
									}
								}
							}
							val outcome = end ?: FanEnd.HOLD
							if (outcome != FanEnd.HOLD) {
								charge.cancel()
								scope.launch { ring.snapTo(0f) }
							}
							when (outcome) {
								FanEnd.TAP -> {
									holding = false
									if (canContinue) {
										haptic(HapticEffect.CLICK)
										doContinue()
									} else {
										// Nothing to resume: the tap opens the fan to pick from, tap a mode to choose
										openFan(isLocked = true)
									}
								}

								FanEnd.HOLD -> {
									if (canContinue) {
										haptic(HapticEffect.LONG_PRESS)
										doDetails()
									} else {
										haptic(HapticEffect.REJECT)
									}
									// Swallow the rest of this touch
									while (true) {
										val event = awaitPointerEvent()
										val change = event.changes.firstOrNull { it.id == down.id } ?: break
										change.consume()
										if (!change.pressed) break
									}
									scope.launch { ring.snapTo(0f) }
								}

								FanEnd.PULL -> {
									openFan(isLocked = false)
									while (true) {
										val event = awaitPointerEvent()
										val change = event.changes.firstOrNull { it.id == down.id } ?: break
										change.consume()
										if (!change.pressed) break
										// Thumb position in the window, measured from the screen corner the fan grows out of
										val g = fanGeometry(Size(view.rootView.width.toFloat(), view.rootView.height.toFloat()))
										val winX = liveCenter.x + (change.position.x - size.width / 2f)
										val winY = liveCenter.y + (change.position.y - size.height / 2f)
										val dx = g.center.x - winX
										val dy = g.center.y - winY
										// Ignore the stretch between the corner and the button, so a small pull never flips the highlight
										val dead = hypot(g.center.x - g.fabCenter.x, g.center.y - g.fabCenter.y) + 12.dp.toPx()
										// With Streaming split, a thumb on one of its halves wins over the angle zones
										val pill = if (defaultKind == null && highlighted == HomeMode.ANIME) streamingPillAt(winX, winY, g) else null
										if (pill != null) {
											if (pill != highlightedKind) {
												highlightedKind = pill
												haptic(HapticEffect.TICK)
											}
										} else {
											val picked = pickMode(dx, dy, g.nodeRadius, nearPx, dead)
											if (picked != null && picked != highlighted) {
												highlighted = picked
												highlightedKind = restingKind()
												haptic(HapticEffect.TICK)
											} else if (highlighted == HomeMode.ANIME && highlightedKind != restingKind()) {
												// Off the halves again: Streaming falls back to its resting kind
												highlightedKind = restingKind()
												haptic(HapticEffect.LIGHT_TICK)
											}
										}
									}
									val chosen = highlighted
									val chosenKind = highlightedKind
									closeFan()
									val changed = chosen != null &&
										(chosen != currentMode || (chosen == HomeMode.ANIME && chosenKind != currentKind))
									if (chosen != null && changed) {
										haptic(HapticEffect.CONFIRM)
										applyChoice(chosen, chosenKind)
									} else {
										haptic(HapticEffect.GESTURE_END)
									}
								}
							}
						} finally {
							holding = false
						}
					}
				},
		) {
			Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
				// The icon swaps with a little spring and turn when the mode changes
				AnimatedContent(
					targetState = mode,
					transitionSpec = {
						(scaleIn(spring(0.55f, 500f), initialScale = 0.5f) + fadeIn(tween(140))) togetherWith
							(scaleOut(tween(120), targetScale = 1.35f) + fadeOut(tween(100)))
					},
					label = "fabIcon",
				) { m ->
					Icon(
						painter = painterResource(modeIconRes(m, currentKind)),
						contentDescription = null,
						tint = content,
						modifier = Modifier.size(24.dp),
					)
				}
			}
		}
	}

	if (fanOpen || fanProgress > 0.01f) {
		ModeFanPopup(
			progress = fanProgress,
			current = mode,
			currentKind = streamingKind,
			defaultKind = streamingDefault,
			highlighted = highlighted ?: mode,
			highlightedKind = highlightedKind,
			onPick = { picked, kind ->
				val changed = picked != currentMode || (picked == HomeMode.ANIME && kind != currentKind)
				closeFan()
				haptic(if (changed) HapticEffect.CONFIRM else HapticEffect.TICK)
				if (changed) applyChoice(picked, kind)
			},
			// A tap on Streaming unfolds Anime / TV & Movies instead of choosing straight away
			onUnfoldStreaming = {
				highlighted = HomeMode.ANIME
				highlightedKind = restingKind()
				haptic(HapticEffect.TICK)
			},
			onDismiss = {
				closeFan()
				haptic(HapticEffect.LIGHT_CLICK)
			},
		)
	}
}

private object ZeroPosition : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	) = IntOffset.Zero
}

/** The dimmed page, the frosted quarter-circle with its three modes and a gliding spotlight, the preview pill and a copy of the button on top. */
@Composable
private fun ModeFanPopup(
	progress: Float,
	current: HomeMode,
	currentKind: StreamingKind,
	defaultKind: StreamingKind?,
	highlighted: HomeMode,
	highlightedKind: StreamingKind,
	onPick: (HomeMode, StreamingKind) -> Unit,
	onUnfoldStreaming: () -> Unit,
	onDismiss: () -> Unit,
) {
	val density = LocalDensity.current
	val fabHalf = with(density) { (FAB_SIZE / 2).toPx() }
	val nodeHalfW = with(density) { (NODE_W / 2).toPx() }
	val nodeHalfH = with(density) { (NODE_H / 2).toPx() }
	val alpha = progress.coerceIn(0f, 1f)
	val scale = progress.coerceAtLeast(0f)
	val spoke by animateFloatAsState(
		targetValue = highlighted.fanAngle(),
		animationSpec = spring(dampingRatio = 0.6f, stiffness = 520f),
		label = "spoke",
	)
	// Streaming splits into its two halves while it is the highlighted mode (only with no Streaming default)
	val split = defaultKind == null
	val unfold by animateFloatAsState(
		targetValue = if (split && highlighted == HomeMode.ANIME) 1f else 0f,
		animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
		label = "streamingUnfold",
	)
	val glow by rememberInfiniteTransition(label = "fanGlow").animateFloat(
		initialValue = 0.65f,
		targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
		label = "fanGlowAlpha",
	)

	Popup(
		popupPositionProvider = ZeroPosition,
		properties = PopupProperties(focusable = false, clippingEnabled = false),
	) {
		BoxWithConstraints(Modifier.fillMaxSize()) {
			val windowW = constraints.maxWidth.toFloat()
			val windowH = constraints.maxHeight.toFloat()
			val g = with(density) { fanGeometry(Size(windowW, windowH)) }
			val pillWidth: Dp = min(PILL_MAX_WIDTH.value, maxWidth.value - FAN_EDGE_MARGIN.value * 2).dp
			// The pill sits clear above the top of the fan, at the left like in the prototype
			val pillX = with(density) { (FAN_EDGE_MARGIN + 2.dp).toPx() }
			val pillBottom = g.center.y - g.radius - with(density) { 14.dp.toPx() }

			Box(
				Modifier
					.fillMaxSize()
					.graphicsLayer { this.alpha = alpha }
					.background(Color.Black.copy(alpha = 0.74f))
					.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
			)
			// The fan blooms out of the screen corner
			Box(
				Modifier
					.fillMaxSize()
					.graphicsLayer {
						scaleX = scale
						scaleY = scale
						this.alpha = alpha
						transformOrigin = TransformOrigin(g.center.x / windowW, g.center.y / windowH)
					},
			) {
				Canvas(Modifier.fillMaxSize()) {
					val c = g.center
					val path = fanPath(c, g.radius, FAN_TIP_CORNER.toPx(), FAN_END_CORNER.toPx())
					val reach = g.radius
					// Frosted glass body: a bright corner fading into smoky glass, as in the prototype
					drawPath(
						path = path,
						brush = Brush.radialGradient(
							0f to Color.White.copy(alpha = 0.14f),
							0.44f to GlassTop.copy(alpha = 0.68f),
							1f to GlassBottom.copy(alpha = 0.78f),
							center = c,
							radius = reach,
						),
					)
					clipPath(path) {
						// Ambient glow breathing out of the button corner
						drawRect(
							brush = Brush.radialGradient(
								0f to Color.White.copy(alpha = 0.18f * glow),
								0.42f to Color(0xFFA78BFA).copy(alpha = 0.08f * glow),
								1f to Color.Transparent,
								center = c,
								radius = reach,
							),
						)
						// Spotlight beam that glides (with a little spring) to the mode under the thumb
						val f = (180f + spoke) / 360f
						drawRect(
							brush = Brush.sweepGradient(
								(f - 0.075f) to Color.Transparent,
								(f - 0.04f) to Color.White.copy(alpha = 0.06f),
								f to Color.White.copy(alpha = 0.34f),
								(f + 0.04f) to Color.White.copy(alpha = 0.06f),
								(f + 0.075f) to Color.Transparent,
								center = c,
							),
						)
					}
					// Glass rim, brighter on the upper-left edge
					drawPath(
						path = path,
						brush = Brush.linearGradient(
							listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.10f)),
							start = Offset(c.x - reach, c.y - reach),
							end = c,
						),
						style = Stroke(1.5.dp.toPx()),
					)
				}
				HomeMode.entries.forEach { m ->
					val a = Math.toRadians(m.fanAngle().toDouble())
					val cx = g.center.x - g.nodeRadius * cos(a).toFloat()
					val cy = g.center.y - g.nodeRadius * sin(a).toFloat()
					if (m == HomeMode.ANIME && defaultKind != null) {
						// A Streaming default is set: the node is that kind, with its own icon and name, and never splits
						FanNode(
							iconRes = streamingKindIconRes(defaultKind),
							title = stringResource(defaultKind.titleRes),
							selected = m == highlighted,
							isCurrent = current == HomeMode.ANIME && currentKind == defaultKind,
							modifier = Modifier.offset { IntOffset((cx - nodeHalfW).roundToInt(), (cy - nodeHalfH).roundToInt()) },
							onClick = { onPick(HomeMode.ANIME, defaultKind) },
						)
					} else if (m == HomeMode.ANIME) {
						// Streaming: the node hands over to its two halves, the same shape cut in two
						FanNode(
							iconRes = R.drawable.ic_streaming,
							title = stringResource(modeTitleRes(m)),
							selected = m == highlighted,
							isCurrent = m == current,
							modifier = Modifier
								.offset { IntOffset((cx - nodeHalfW).roundToInt(), (cy - nodeHalfH).roundToInt()) }
								.graphicsLayer { this.alpha = 1f - (unfold * 6f).coerceIn(0f, 1f) },
							// Tapping the node while it is folded splits it; between the halves it takes the resting kind
							onClick = {
								if (highlighted == HomeMode.ANIME) {
									onPick(HomeMode.ANIME, if (current == HomeMode.ANIME) currentKind else StreamingKind.ANIME)
								} else {
									onUnfoldStreaming()
								}
							},
						)
						if (unfold > 0.01f) {
							StreamingSplit(
								unfold = unfold,
								litKind = highlightedKind,
								currentKind = if (current == HomeMode.ANIME) currentKind else null,
								modifier = Modifier
									.offset { IntOffset((cx - nodeHalfW).roundToInt(), (cy - nodeHalfH).roundToInt()) }
									.graphicsLayer { this.alpha = (unfold * 6f).coerceIn(0f, 1f) },
								onPick = { kind -> onPick(HomeMode.ANIME, kind) },
							)
						}
					} else {
						FanNode(
							iconRes = modeIconRes(m),
							title = stringResource(modeTitleRes(m)),
							selected = m == highlighted,
							isCurrent = m == current,
							modifier = Modifier.offset { IntOffset((cx - nodeHalfW).roundToInt(), (cy - nodeHalfH).roundToInt()) },
							onClick = { onPick(m, currentKind) },
						)
					}
				}
			}
			// Preview pill above the fan: what the thumb is on right now. It lives outside the fan so it can never overlap a node.
			Surface(
				shape = RoundedCornerShape(20.dp),
				color = PillFill.copy(alpha = 0.96f),
				border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
				shadowElevation = 12.dp,
				modifier = Modifier
					.align(Alignment.BottomStart)
					.offset { IntOffset(pillX.roundToInt(), (pillBottom - windowH).roundToInt()) }
					.width(pillWidth)
					.graphicsLayer {
						this.alpha = alpha
						translationY = (1f - alpha) * 12.dp.toPx()
					},
			) {
				Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
					Text(
						text = stringResource(R.string.ds_mode_switch).uppercase(),
						fontSize = 10.sp,
						fontWeight = FontWeight.Bold,
						letterSpacing = 1.sp,
						color = MutedText,
					)
					// "Streaming · TV & Movies" while split; with a Streaming default the kind's own name
					AnimatedContent(
						targetState = highlighted to (if (highlighted == HomeMode.ANIME) highlightedKind else null),
						transitionSpec = {
							(slideInVertically(spring(0.7f, 600f)) { it / 2 } + fadeIn(tween(120))) togetherWith
								(slideOutVertically(tween(120)) { -it / 2 } + fadeOut(tween(90)))
						},
						label = "previewTitle",
						modifier = Modifier.padding(top = 4.dp),
					) { (m, kind) ->
						Row(verticalAlignment = Alignment.CenterVertically) {
							Icon(
								painterResource(modeIconRes(m, kind ?: StreamingKind.ANIME)),
								null,
								tint = Color.White,
								modifier = Modifier.size(20.dp),
							)
							Text(
								text = if (kind != null && split) {
									stringResource(R.string.ds_mode_streaming_kind, stringResource(modeTitleRes(m)), stringResource(kind.titleRes))
								} else if (kind != null) {
									stringResource(kind.titleRes)
								} else {
									stringResource(modeTitleRes(m))
								},
								fontSize = 18.sp,
								fontWeight = FontWeight.ExtraBold,
								color = Color.White,
								modifier = Modifier.padding(start = 8.dp),
							)
						}
					}
					Text(
						text = stringResource(R.string.ds_mode_fan_hint, (HOLD_MS / 1000f).toString()),
						fontSize = 11.sp,
						lineHeight = 15.sp,
						color = MutedText,
						modifier = Modifier.padding(top = 6.dp),
					)
				}
			}
			// The soft halo around the button, as in the prototype
			val haloPad = with(density) { 6.dp.toPx() }
			Box(
				Modifier
					.offset { IntOffset((g.fabCenter.x - fabHalf - haloPad).roundToInt(), (g.fabCenter.y - fabHalf - haloPad).roundToInt()) }
					.size(FAB_SIZE + 12.dp)
					.graphicsLayer { this.alpha = alpha }
					.background(Color(0xFFE5E5EA).copy(alpha = 0.08f), RoundedCornerShape(22.dp)),
			)
			// A copy of the button on top of the dimmed page, previewing the mode under the thumb
			Surface(
				shape = RoundedCornerShape(16.dp),
				color = Color(0xFF1C1C20),
				border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.22f)),
				modifier = Modifier
					.offset { IntOffset((g.fabCenter.x - fabHalf).roundToInt(), (g.fabCenter.y - fabHalf).roundToInt()) }
					.size(FAB_SIZE)
					.graphicsLayer { this.alpha = alpha },
			) {
				Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
					Icon(
						painterResource(modeIconRes(highlighted, if (highlighted == HomeMode.ANIME) highlightedKind else currentKind)),
						null,
						tint = Color(0xFFE5E5EA),
						modifier = Modifier.size(24.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun FanNode(
	@DrawableRes iconRes: Int,
	title: String,
	selected: Boolean,
	isCurrent: Boolean,
	modifier: Modifier,
	onClick: () -> Unit,
) {
	val scale by animateFloatAsState(
		targetValue = if (selected) SELECTED_SCALE else 1f,
		animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
		label = "nodeScale",
	)
	val container by animateColorAsState(
		targetValue = if (selected) Color.White else NodeFill.copy(alpha = 0.82f),
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "nodeContainer",
	)
	val content by animateColorAsState(
		targetValue = if (selected) Color.Black else Color(0xFFF5F5F7),
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "nodeContent",
	)
	Surface(
		shape = RoundedCornerShape(NODE_CORNER),
		color = container,
		contentColor = content,
		shadowElevation = if (selected) 10.dp else 4.dp,
		border = androidx.compose.foundation.BorderStroke(
			1.5.dp,
			if (selected) Color.White else Color.White.copy(alpha = 0.24f),
		),
		modifier = modifier
			.size(NODE_W, NODE_H)
			.graphicsLayer { scaleX = scale; scaleY = scale }
			.clickable(onClick = onClick),
	) {
		Box(Modifier.fillMaxSize()) {
			Column(
				modifier = Modifier.align(Alignment.Center),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Icon(painterResource(iconRes), null, tint = content, modifier = Modifier.size(22.dp))
				Text(
					text = title,
					fontSize = if (title.length > 9) 10.sp else 11.5.sp,
					lineHeight = if (title.length > 9) 11.sp else 14.sp,
					fontWeight = FontWeight.Bold,
					color = content,
					// "TV & Movies" does not fit one line in the node: break it after the ampersand instead of clipping it
					maxLines = 2,
					softWrap = true,
					textAlign = androidx.compose.ui.text.style.TextAlign.Center,
					modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
				)
			}
			if (isCurrent) {
				// The mode the app is in right now
				Box(
					Modifier
						.align(Alignment.TopEnd)
						.padding(7.dp)
						.size(6.dp)
						.background(if (selected) EmeraldOnWhite else Emerald, RoundedCornerShape(50)),
				)
			}
		}
	}
}

/**
 * What the Streaming node splits into: the node's own shape (same width, same height, same corners) cut across the
 * middle into an Anime half above and a TV & Movies half below. At [unfold] 0 the two halves sit flush and read as the
 * single node; as it grows a thin gap opens and the cut corners round off. The lit half is the one the thumb is on.
 */
@Composable
private fun StreamingSplit(
	unfold: Float,
	litKind: StreamingKind,
	/** The half that is on right now (shown with the green dot), or null when the app is not in Streaming. */
	currentKind: StreamingKind?,
	onPick: (StreamingKind) -> Unit,
	modifier: Modifier = Modifier,
) {
	val u = unfold.coerceIn(0f, 1f)
	val gap = SPLIT_GAP * u
	val half = (NODE_H - gap) / 2
	val inner = SPLIT_INNER_CORNER * u
	Column(
		modifier = modifier
			.size(NODE_W, NODE_H)
			.graphicsLayer {
				val s = 1f + (SELECTED_SCALE - 1f) * u
				scaleX = s
				scaleY = s
			},
	) {
		StreamingKind.entries.forEachIndexed { index, kind ->
			if (index > 0) Spacer(Modifier.height(gap))
			StreamingHalf(
				kind = kind,
				top = index == 0,
				lit = kind == litKind,
				isCurrent = kind == currentKind,
				unfold = u,
				innerCorner = inner,
				modifier = Modifier.size(NODE_W, half),
				onClick = { onPick(kind) },
			)
		}
	}
}

@Composable
private fun StreamingHalf(
	kind: StreamingKind,
	top: Boolean,
	lit: Boolean,
	isCurrent: Boolean,
	unfold: Float,
	innerCorner: Dp,
	modifier: Modifier,
	onClick: () -> Unit,
) {
	val litAmount by animateFloatAsState(
		targetValue = if (lit) 1f else 0f,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "halfLit",
	)
	// Folded, both halves are the selected (white) node; unfolded, only the lit one stays white
	val darkFill = lerp(Color.White, NodeFill.copy(alpha = 0.92f), unfold)
	val darkText = lerp(Color.Black, Color(0xFFF5F5F7), unfold)
	val darkBorder = lerp(Color.White, Color.White.copy(alpha = 0.24f), unfold)
	val fill = lerp(darkFill, Color.White, litAmount)
	val text = lerp(darkText, Color.Black, litAmount)
	val border = lerp(darkBorder, Color.White, litAmount)
	val shape = if (top) {
		RoundedCornerShape(NODE_CORNER, NODE_CORNER, innerCorner, innerCorner)
	} else {
		RoundedCornerShape(innerCorner, innerCorner, NODE_CORNER, NODE_CORNER)
	}
	Surface(
		shape = shape,
		color = fill,
		contentColor = text,
		shadowElevation = if (lit) 10.dp else 4.dp,
		border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
		modifier = modifier.clickable(onClick = onClick),
	) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(
				text = stringResource(kind.titleRes),
				fontSize = 9.sp,
				lineHeight = 10.sp,
				fontWeight = FontWeight.Bold,
				color = text,
				// "TV & Movies" is wider than a half node: centre it and let it break after the ampersand
				maxLines = 2,
				softWrap = true,
				textAlign = androidx.compose.ui.text.style.TextAlign.Center,
				modifier = Modifier.padding(horizontal = 2.dp),
			)
			if (isCurrent) {
				Box(
					Modifier
						.align(if (top) Alignment.TopEnd else Alignment.BottomEnd)
						.padding(horizontal = 9.dp, vertical = 6.dp)
						.size(6.dp)
						.background(if (lit) EmeraldOnWhite else Emerald, RoundedCornerShape(50)),
				)
			}
		}
	}
}
