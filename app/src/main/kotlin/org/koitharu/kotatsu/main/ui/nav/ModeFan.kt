package org.koitharu.kotatsu.main.ui.nav

import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.home.ui.model.HomeMode
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
 *  - **Hold** for a second without pulling: opens the details of the last title, with a ring charging around the
 *    button and two soft ticks on the way.
 *
 * Every step has its own haptic: a start when the fan opens, a tick each time the highlighted mode changes, a
 * confirm when the mode is switched, a long press when the hold completes. All of them go through the app's
 * haptics switch.
 */
private const val HOLD_MS = 1000

/** Node angles measured from "straight left" (0) to "straight up" (90), as in the prototype. */
private fun HomeMode.fanAngle(): Float = when (this) {
	HomeMode.ANIME -> 75f
	HomeMode.MANGA -> 45f
	HomeMode.NOVEL -> 15f
}

/** Room kept free above the fan for the status bar and the "Switch mode" pill. */
private val FAN_TOP_ROOM = 150.dp
private val FAN_MIN_RADIUS = 230.dp
private val FAN_MIN_NODE_RADIUS = 150.dp
private val FAN_EDGE_MARGIN = 16.dp
private val FAN_CORNER = 28.dp
private val PILL_MAX_WIDTH = 250.dp
private val NODE_W = 76.dp
private val NODE_H = 70.dp
private val FAB_SIZE = 52.dp

private enum class FanEnd { TAP, PULL, HOLD }

@DrawableRes
internal fun modeIconRes(mode: HomeMode): Int = when (mode) {
	HomeMode.ANIME -> R.drawable.ic_play_circle
	HomeMode.NOVEL -> R.drawable.ic_novel_menu_book
	HomeMode.MANGA -> R.drawable.ic_read
}

private fun modeTitleRes(mode: HomeMode): Int = when (mode) {
	HomeMode.ANIME -> R.string.ds_mode_anime
	HomeMode.NOVEL -> R.string.ds_mode_novel
	HomeMode.MANGA -> R.string.ds_mode_manga
}

/** Where the fan sits: [origin] is the bottom-right corner of the usable screen, [nodeRadius] the ring the three modes are on. */
private class FanGeometry(val origin: Offset, val radius: Float, val nodeRadius: Float)

/** The navigation bar (gesture line or three buttons) at the edges of [this] window, so the fan can sit above it. */
private fun View.navigationInsets(): Insets =
	ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.navigationBars()) ?: Insets.NONE

/**
 * The fan is a 90-degree sector whose tip is the bottom-right corner of the screen, just above the navigation bar, and
 * whose straight edges run along the right edge and that bar. It stays compact (70% of the width).
 */
private fun Density.fanGeometry(window: Size, insets: Insets): FanGeometry {
	val origin = Offset(window.width - insets.right, window.height - insets.bottom)
	val radius = min(window.width * 0.7f, origin.y - FAN_TOP_ROOM.toPx()).coerceAtLeast(FAN_MIN_RADIUS.toPx())
	return FanGeometry(origin, radius, radius * 0.8f)
}

/** The sector from the corner, with its two outer ends softly rounded. */
private fun sectorPath(origin: Offset, r: Float, c: Float): Path {
	val tDeg = Math.toDegrees((c / r).toDouble()).toFloat()
	val t = Math.toRadians(tDeg.toDouble())
	val rect = Rect(origin.x - r, origin.y - r, origin.x + r, origin.y + r)
	return Path().apply {
		moveTo(origin.x, origin.y)
		lineTo(origin.x, origin.y - r + c)
		quadraticBezierTo(origin.x, origin.y - r, origin.x - r * sin(t).toFloat(), origin.y - r * cos(t).toFloat())
		arcTo(rect, 270f - tDeg, -(90f - 2f * tDeg), false)
		quadraticBezierTo(origin.x - r, origin.y, origin.x - r + c, origin.y)
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
		angle >= 60f -> HomeMode.ANIME
		angle >= 30f -> HomeMode.MANGA
		else -> HomeMode.NOVEL
	}
}

@Composable
internal fun ModeFanButton(
	mode: HomeMode,
	continueEnabled: Boolean,
	fanOpen: Boolean,
	onFanOpenChange: (Boolean) -> Unit,
	onModeSelected: (HomeMode) -> Unit,
	onContinue: () -> Unit,
	onDetails: () -> Unit,
) {
	val haptic = rememberHapticEffect()
	val view = LocalView.current
	val scope = rememberCoroutineScope()
	val ring = remember { Animatable(0f) }
	var holding by remember { mutableStateOf(false) }
	var highlighted by remember { mutableStateOf<HomeMode?>(null) }
	var locked by remember { mutableStateOf(false) }
	var fabCenter by remember { mutableStateOf(Offset.Zero) }
	val currentMode by rememberUpdatedState(mode)
	val canContinue by rememberUpdatedState(continueEnabled)
	val setOpen by rememberUpdatedState(onFanOpenChange)
	val select by rememberUpdatedState(onModeSelected)
	val doContinue by rememberUpdatedState(onContinue)
	val doDetails by rememberUpdatedState(onDetails)

	fun closeFan() {
		highlighted = null
		locked = false
		setOpen(false)
	}

	fun openFan(isLocked: Boolean) {
		locked = isLocked
		highlighted = currentMode
		haptic(HapticEffect.GESTURE_START)
		setOpen(true)
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
	val labelManga = stringResource(R.string.ds_mode_manga)
	val labelNovel = stringResource(R.string.ds_mode_novel)

	Box(modifier = Modifier.padding(horizontal = 2.dp)) {
		Surface(
			shape = RoundedCornerShape(20.dp),
			color = container,
			contentColor = content,
			border = if (fanOpen) androidx.compose.foundation.BorderStroke(1.5.dp, cs.outline.copy(alpha = 0.5f)) else null,
			modifier = Modifier
				.size(FAB_SIZE)
				.onGloballyPositioned { fabCenter = it.boundsInWindow().center }
				.graphicsLayer { scaleX = pressScale; scaleY = pressScale }
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
						CustomAccessibilityAction(labelAnime) { select(HomeMode.ANIME); true },
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
										val g = fanGeometry(Size(view.rootView.width.toFloat(), view.rootView.height.toFloat()), view.navigationInsets())
										val winX = fabCenter.x + (change.position.x - size.width / 2f)
										val winY = fabCenter.y + (change.position.y - size.height / 2f)
										val dx = g.origin.x - winX
										val dy = g.origin.y - winY
										// Ignore the stretch between the corner and the button, so a small pull never flips the highlight
										val dead = max(deadPx, hypot(g.origin.x - fabCenter.x, g.origin.y - fabCenter.y) + 12.dp.toPx())
										val picked = pickMode(dx, dy, g.nodeRadius, nearPx, dead)
										if (picked != null && picked != highlighted) {
											highlighted = picked
											haptic(HapticEffect.TICK)
										}
									}
									val chosen = highlighted
									closeFan()
									if (chosen != null && chosen != currentMode) {
										haptic(HapticEffect.CONFIRM)
										select(chosen)
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
						painter = painterResource(modeIconRes(m)),
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
			fabCenter = fabCenter,
			current = mode,
			highlighted = highlighted ?: mode,
			insets = view.navigationInsets(),
			onPick = { picked ->
				val changed = picked != currentMode
				closeFan()
				haptic(if (changed) HapticEffect.CONFIRM else HapticEffect.TICK)
				if (changed) select(picked)
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

/** The dimmed page, the quarter-circle with its three modes, the spoke, the preview pill and a copy of the button on top. */
@Composable
private fun ModeFanPopup(
	progress: Float,
	fabCenter: Offset,
	current: HomeMode,
	highlighted: HomeMode,
	insets: Insets,
	onPick: (HomeMode) -> Unit,
	onDismiss: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
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
	val sectorFill = cs.surfaceContainerHigh
	val sectorEdge = cs.outline.copy(alpha = 0.4f)
	val ringColor = cs.onSurface.copy(alpha = 0.16f)
	val spokeColor = cs.primary

	Popup(
		popupPositionProvider = ZeroPosition,
		properties = PopupProperties(focusable = false, clippingEnabled = false),
	) {
		BoxWithConstraints(Modifier.fillMaxSize()) {
			val windowW = constraints.maxWidth.toFloat()
			val windowH = constraints.maxHeight.toFloat()
			val g = with(density) { fanGeometry(Size(windowW, windowH), insets) }
			val cornerPx = with(density) { FAN_CORNER.toPx() }
			val marginPx = with(density) { FAN_EDGE_MARGIN.toPx() }
			val pillWidth: Dp = min(PILL_MAX_WIDTH.value, maxWidth.value - FAN_EDGE_MARGIN.value * 2).dp
			val pillWidthPx = with(density) { pillWidth.toPx() }
			// The pill sits clear above the top of the fan, against the right margin
			val pillX = (windowW - pillWidthPx - marginPx).coerceAtLeast(marginPx)
			val pillBottom = g.origin.y - g.radius - with(density) { 14.dp.toPx() }

			Box(
				Modifier
					.fillMaxSize()
					.graphicsLayer { this.alpha = alpha }
					.background(Color.Black.copy(alpha = 0.66f))
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
						transformOrigin = TransformOrigin(1f, 1f)
					},
			) {
				Canvas(Modifier.fillMaxSize()) {
					val origin = g.origin
					val path = sectorPath(origin, g.radius, cornerPx)
					drawPath(
						path = path,
						brush = Brush.radialGradient(
							0f to sectorFill.copy(alpha = 0.98f),
							1f to sectorFill.copy(alpha = 0.92f),
							center = origin,
							radius = g.radius,
						),
					)
					drawPath(path = path, color = sectorEdge, style = Stroke(1.5.dp.toPx()))
					val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 8.dp.toPx()))
					listOf(g.nodeRadius, g.nodeRadius * 0.55f).forEach { rr ->
						drawArc(
							color = ringColor, startAngle = 180f, sweepAngle = 90f, useCenter = false,
							topLeft = Offset(origin.x - rr, origin.y - rr), size = Size(rr * 2, rr * 2),
							style = Stroke(1.dp.toPx(), pathEffect = dash),
						)
					}
					// The glowing spoke from the button to the highlighted node, stopping short of the node
					val a = Math.toRadians(spoke.toDouble())
					val node = Offset(origin.x - g.nodeRadius * cos(a).toFloat(), origin.y - g.nodeRadius * sin(a).toFloat())
					val len = hypot(node.x - fabCenter.x, node.y - fabCenter.y).coerceAtLeast(1f)
					val stop = 46.dp.toPx()
					val end = Offset(node.x - (node.x - fabCenter.x) / len * stop, node.y - (node.y - fabCenter.y) / len * stop)
					drawLine(
						brush = Brush.linearGradient(listOf(spokeColor.copy(alpha = 0.95f), spokeColor.copy(alpha = 0.12f)), fabCenter, end),
						start = fabCenter, end = end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
					)
				}
				HomeMode.entries.forEach { m ->
					val a = Math.toRadians(m.fanAngle().toDouble())
					val cx = g.origin.x - g.nodeRadius * cos(a).toFloat()
					val cy = g.origin.y - g.nodeRadius * sin(a).toFloat()
					FanNode(
						mode = m,
						selected = m == highlighted,
						isCurrent = m == current,
						modifier = Modifier.offset { IntOffset((cx - nodeHalfW).roundToInt(), (cy - nodeHalfH).roundToInt()) },
						onClick = { onPick(m) },
					)
				}
			}
			// Preview pill above the fan: what the thumb is on right now. It lives outside the fan so it can never overlap a node.
			Surface(
				shape = RoundedCornerShape(20.dp),
				color = cs.surfaceContainerHigh.copy(alpha = 0.97f),
				shadowElevation = 6.dp,
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
						color = cs.onSurfaceVariant,
					)
					AnimatedContent(
						targetState = highlighted,
						transitionSpec = {
							(slideInVertically(spring(0.7f, 600f)) { it / 2 } + fadeIn(tween(120))) togetherWith
								(slideOutVertically(tween(120)) { -it / 2 } + fadeOut(tween(90)))
						},
						label = "previewTitle",
						modifier = Modifier.padding(top = 4.dp),
					) { m ->
						Row(verticalAlignment = Alignment.CenterVertically) {
							Icon(painterResource(modeIconRes(m)), null, tint = cs.primary, modifier = Modifier.size(20.dp))
							Text(
								text = stringResource(modeTitleRes(m)),
								fontSize = 18.sp,
								fontWeight = FontWeight.ExtraBold,
								color = cs.onSurface,
								modifier = Modifier.padding(start = 8.dp),
							)
						}
					}
					Text(
						text = stringResource(R.string.ds_mode_fan_hint),
						fontSize = 11.sp,
						lineHeight = 15.sp,
						color = cs.onSurfaceVariant,
						modifier = Modifier.padding(top = 6.dp),
					)
				}
			}
			// A copy of the button on top of the dimmed page, previewing the mode under the thumb
			Surface(
				shape = RoundedCornerShape(20.dp),
				color = cs.surfaceContainerHighest,
				border = androidx.compose.foundation.BorderStroke(1.5.dp, cs.outline.copy(alpha = 0.5f)),
				modifier = Modifier
					.offset { IntOffset((fabCenter.x - fabHalf).roundToInt(), (fabCenter.y - fabHalf).roundToInt()) }
					.size(FAB_SIZE)
					.graphicsLayer { this.alpha = alpha },
			) {
				Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
					Icon(painterResource(modeIconRes(highlighted)), null, tint = cs.onSurface, modifier = Modifier.size(24.dp))
				}
			}
		}
	}
}

@Composable
private fun FanNode(
	mode: HomeMode,
	selected: Boolean,
	isCurrent: Boolean,
	modifier: Modifier,
	onClick: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	val scale by animateFloatAsState(
		targetValue = if (selected) 1.16f else 1f,
		animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
		label = "nodeScale",
	)
	val container by animateColorAsState(
		targetValue = if (selected) cs.primary else cs.surfaceContainerHighest,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "nodeContainer",
	)
	val content by animateColorAsState(
		targetValue = if (selected) cs.onPrimary else cs.onSurface,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "nodeContent",
	)
	Surface(
		shape = RoundedCornerShape(21.dp),
		color = container,
		contentColor = content,
		shadowElevation = if (selected) 10.dp else 4.dp,
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
				Icon(painterResource(modeIconRes(mode)), null, tint = content, modifier = Modifier.size(22.dp))
				Text(
					text = stringResource(modeTitleRes(mode)),
					fontSize = 11.5.sp,
					fontWeight = FontWeight.Bold,
					color = content,
					modifier = Modifier.padding(top = 4.dp),
				)
			}
			if (isCurrent) {
				// The mode the app is in right now
				Box(
					Modifier
						.align(Alignment.TopEnd)
						.padding(7.dp)
						.size(6.dp)
						.background(if (selected) cs.onPrimary else cs.tertiary, RoundedCornerShape(50)),
				)
			}
		}
	}
}
