package org.koitharu.kotatsu.settings.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NovelBarItem
import org.koitharu.kotatsu.core.prefs.NovelBarLayout
import org.koitharu.kotatsu.core.prefs.NovelBars
import org.koitharu.kotatsu.core.prefs.NovelControl
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.reader.ui.epub.NovelBarsPreview
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ConfirmDialog
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.groupItemShape
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Customises the novel reader's bars. Every control lives in one of four slots (left or right side of
 * the top bar, left or right side of the bottom bar); the page slider sits between the bottom
 * bar's two sides. A control is toggled with its switch, reordered by dragging its handle within
 * its slot, and sent to another slot with its move menu. The preview on top is the novel reader's real
 * bars reading the same preference, so it always matches the reader.
 */
@AndroidEntryPoint
class NovelBarSettingsFragment :
	BaseComposeSettingsFragment(R.string.novel_bars_customize) {

	@Inject
	lateinit var settings: AppSettings

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				NovelBarScreen(
					initial = settings.novelBarLayout,
					onChanged = { settings.novelBarLayout = it },
				)
			}
		}
	}
}

private typealias NovelLayout = NovelBarLayout

@Composable
private fun NovelBarScreen(
	initial: NovelLayout,
	onChanged: (NovelLayout) -> Unit,
) {
	var layout by remember { mutableStateOf(initial) }
	val apply: (NovelLayout) -> Unit = { value ->
		layout = value
		onChanged(value)
	}
	SettingsScaffold {
		item { NovelBarsPreview(layout = layout, modifier = Modifier.fillMaxWidth()) }
		item { Spacer(Modifier.height(16.dp).fillMaxWidth()) }
		item { ControlList(layout = layout, onChange = apply) }
	}
}

/** The controls of one slot, in the order they are drawn. The slider is not part of any slot. */
private fun NovelLayout.inSlot(slot: ReaderBarSlot): List<NovelBarItem> =
	filter { it.slot == slot && it.control != NovelControl.SLIDER }

/** What the bars look like, ignoring how the slots are interleaved in the list. */
private fun NovelLayout.appearance(): List<List<Pair<NovelControl, Boolean>>> =
	ReaderBarSlot.entries.map { slot -> inSlot(slot).map { it.control to it.isShown } } +
		listOf(filter { it.control == NovelControl.SLIDER }.map { it.control to it.isShown })

/** [newOrder] fills the places the slot's controls already had, so other slots stay untouched. */
private fun NovelLayout.withSlotOrder(slot: ReaderBarSlot, newOrder: List<NovelBarItem>): NovelLayout {
	val replacements = newOrder.iterator()
	return map { item -> if (item.slot == slot && item.control != NovelControl.SLIDER) replacements.next() else item }
}

/** Sends a control to the end of [target]. */
private fun NovelLayout.moved(control: NovelControl, target: ReaderBarSlot): NovelLayout {
	val item = firstOrNull { it.control == control } ?: return this
	val rest = filterNot { it.control == control }
	return rest.toMutableList().apply { add(rest.indexOfLast { it.slot == target } + 1, item.copy(slot = target)) }
}

/**
 * Every control of the bars, grouped by slot, each group a reorderable settings group. Rows are
 * uniform height, so a drag is turned into an index shift by dividing the accumulated offset by
 * the row pitch.
 */
@Composable
private fun ControlList(
	layout: NovelLayout,
	onChange: (NovelLayout) -> Unit,
) {
	val haptic = rememberHapticEffect()
	Column {
		var isConfirmingReset by remember { mutableStateOf(false) }
		SettingsGroupTitle(
			title = stringResource(R.string.customize),
			isResetEnabled = layout.appearance() != NovelBars.DEFAULT.appearance(),
			onResetClick = { isConfirmingReset = true },
		)
		if (isConfirmingReset) {
			ConfirmDialog(
				title = stringResource(R.string.reset),
				message = stringResource(R.string.config_reset_confirm),
				confirmLabel = stringResource(R.string.reset),
				onConfirm = {
					onChange(NovelBars.DEFAULT)
					haptic(HapticEffect.CONFIRM)
				},
				onDismiss = { isConfirmingReset = false },
			)
		}
		SlotSection(ReaderBarSlot.TOP_START, layout, onChange)
		SlotSection(ReaderBarSlot.TOP_END, layout, onChange)
		SlotSection(ReaderBarSlot.BOTTOM_START, layout, onChange)
		SliderSection(layout, onChange)
		SlotSection(ReaderBarSlot.BOTTOM_END, layout, onChange)
	}
}

@Composable
private fun SlotTitle(title: String) {
	Text(
		text = title,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
	)
}

/** The page slider: on or off, always between the bottom bar's left and right side. */
@Composable
private fun SliderSection(
	layout: NovelLayout,
	onChange: (NovelLayout) -> Unit,
) {
	val item = layout.firstOrNull { it.control == NovelControl.SLIDER } ?: return
	SettingsItem(
		title = stringResource(item.control.titleResId),
		subtitle = stringResource(R.string.reader_bar_slider_note),
		icon = item.control.iconResId,
		shape = groupItemShape(0, 1),
		modifier = Modifier.padding(top = 14.dp),
		onClick = { onChange(layout.map { if (it.control == item.control) it.copy(isShown = !it.isShown) else it }) },
		trailing = {
			Switch(
				checked = item.isShown,
				onCheckedChange = { shown -> onChange(layout.map { if (it.control == item.control) it.copy(isShown = shown) else it }) },
			)
		},
	)
}

@Composable
private fun SlotSection(
	slot: ReaderBarSlot,
	layout: NovelLayout,
	onChange: (NovelLayout) -> Unit,
) {
	val haptic = rememberHapticEffect()
	val gap = with(LocalDensity.current) { GROUP_GAP.toPx() }
	val rows = layout.inSlot(slot)
	var rowPitch by remember { mutableFloatStateOf(0f) }
	var dragIndex by remember { mutableIntStateOf(-1) }
	var dragOffset by remember { mutableFloatStateOf(0f) }

	val onDrag: (Float) -> Unit = { dy ->
		dragOffset += dy
		if (rowPitch > 0f && dragIndex >= 0) {
			val target = (dragIndex + (dragOffset / rowPitch).roundToInt())
				.coerceIn(0, rows.lastIndex)
			if (target != dragIndex) {
				onChange(layout.withSlotOrder(slot, rows.toMutableList().apply { add(target, removeAt(dragIndex)) }))
				dragOffset -= (target - dragIndex) * rowPitch
				dragIndex = target
				haptic(HapticEffect.LIGHT_TICK)
			}
		}
	}

	SlotTitle(stringResource(slot.titleResId))
	if (rows.isEmpty()) {
		Text(
			text = stringResource(R.string.reader_bar_slot_empty),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
		)
		return
	}
	rows.forEachIndexed { index, item ->
		// Keyed by control so each row's state — and its slide animation — follows the item as
		// the list reorders instead of staying with the slot.
		key(item.control) {
			val isDragging = index == dragIndex
			val slide = rememberReorderSlide(index, rowPitch, isDragging)
			ControlRow(
				control = item.control,
				isShown = item.isShown,
				slot = slot,
				shape = groupItemShape(index, rows.size),
				modifier = Modifier
					.zIndex(if (isDragging) 1f else 0f)
					.graphicsLayer {
						translationY = if (isDragging) dragOffset else slide.floatValue
						shadowElevation = if (isDragging) DRAG_ELEVATION.toPx() else 0f
					}
					.onSizeChanged { rowPitch = it.height + gap },
				onToggle = {
					onChange(layout.map { if (it.control == item.control) it.copy(isShown = !it.isShown) else it })
				},
				onMove = { target ->
					onChange(layout.moved(item.control, target))
					haptic(HapticEffect.CONFIRM)
				},
				onDragStart = {
					dragIndex = index
					dragOffset = 0f
					haptic(HapticEffect.GESTURE_START)
				},
				onDrag = onDrag,
				onDragEnd = {
					dragIndex = -1
					dragOffset = 0f
					haptic(HapticEffect.GESTURE_END)
				},
			)
		}
		if (index < rows.lastIndex) {
			Spacer(Modifier.height(GROUP_GAP))
		}
	}
}

/**
 * Rows displaced by a drag are re-composed straight into their new slot, which reads as a jump.
 * Offset the row back to where it used to be and spring it into place so it visibly travels.
 * The dragged row is excluded — it already follows the finger.
 *
 * The displacement is seeded during composition, not from the effect: an effect runs after the
 * frame is composed, so the row would be drawn once at its destination before jumping back to
 * start the animation — which is exactly the flicker this avoids.
 */
@Composable
private fun rememberReorderSlide(
	index: Int,
	pitch: Float,
	isDragging: Boolean,
): FloatState {
	val slide = remember { mutableFloatStateOf(0f) }
	val previousIndex = remember { mutableIntStateOf(index) }
	if (previousIndex.intValue != index) {
		if (pitch > 0f && !isDragging) {
			slide.floatValue += (previousIndex.intValue - index) * pitch
		}
		previousIndex.intValue = index
	}
	LaunchedEffect(index) {
		if (slide.floatValue != 0f) {
			animate(
				initialValue = slide.floatValue,
				targetValue = 0f,
				animationSpec = spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMediumLow,
				),
			) { value, _ -> slide.floatValue = value }
		}
	}
	return slide
}

/** Group heading with the reset action parked at its end, which is where it stays out of the way. */
@Composable
private fun SettingsGroupTitle(
	title: String,
	isResetEnabled: Boolean,
	onResetClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title.uppercase(),
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.primary,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onResetClick, enabled = isResetEnabled) {
			Icon(
				painter = painterResource(R.drawable.ic_restart),
				contentDescription = null,
				modifier = Modifier.size(18.dp),
			)
			Spacer(Modifier.width(6.dp))
			Text(stringResource(R.string.reset))
		}
	}
}

@Composable
private fun ControlRow(
	control: NovelControl,
	isShown: Boolean,
	slot: ReaderBarSlot,
	shape: Shape,
	modifier: Modifier,
	onToggle: () -> Unit,
	onMove: (ReaderBarSlot) -> Unit,
	onDragStart: () -> Unit,
	onDrag: (Float) -> Unit,
	onDragEnd: () -> Unit,
) {
	SettingsItem(
		title = stringResource(control.titleResId),
		subtitle = control.summaryResId.takeIf { it != 0 }?.let { stringResource(it) },
		icon = control.iconResId,
		shape = shape,
		modifier = modifier,
		onClick = { if (control.canBeHidden) onToggle() },
		trailing = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Switch(checked = isShown || !control.canBeHidden, enabled = control.canBeHidden, onCheckedChange = { onToggle() })
				MoveButton(current = slot, onMove = onMove)
				DragHandle(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd)
			}
		},
	)
}

/** Opens a menu of the other three slots; picking one sends the control to the end of it. */
@Composable
private fun MoveButton(
	current: ReaderBarSlot,
	onMove: (ReaderBarSlot) -> Unit,
) {
	var isExpanded by remember { mutableStateOf(false) }
	Box {
		IconButton(onClick = { isExpanded = true }, modifier = Modifier.size(40.dp)) {
			Icon(
				painter = painterResource(R.drawable.ic_swap),
				contentDescription = stringResource(R.string.reader_bar_move_to),
				modifier = Modifier.size(22.dp),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
			ReaderBarSlot.entries.filterNot { it == current }.forEach { slot ->
				DropdownMenuItem(
					text = { Text(stringResource(slot.titleResId)) },
					onClick = {
						isExpanded = false
						onMove(slot)
					},
				)
			}
		}
	}
}

@Composable
private fun DragHandle(
	onDragStart: () -> Unit,
	onDrag: (Float) -> Unit,
	onDragEnd: () -> Unit,
) {
	// The gesture outlives recompositions of the list, so always call through to the latest
	// lambdas — a captured one would reorder against a stale list.
	val currentOnDragStart by rememberUpdatedState(onDragStart)
	val currentOnDrag by rememberUpdatedState(onDrag)
	val currentOnDragEnd by rememberUpdatedState(onDragEnd)
	Box(
		modifier = Modifier
			.size(44.dp)
			.pointerInput(Unit) {
				detectDragGestures(
					onDragStart = { currentOnDragStart() },
					onDragEnd = { currentOnDragEnd() },
					onDragCancel = { currentOnDragEnd() },
					onDrag = { change, amount ->
						change.consume()
						currentOnDrag(amount.y)
					},
				)
			},
		contentAlignment = Alignment.Center,
	) {
		Image(
			painter = rememberAnyDrawablePainter(R.drawable.ic_reorder_handle),
			contentDescription = null,
			modifier = Modifier.size(24.dp),
			colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
		)
	}
}

private val GROUP_GAP = 2.dp
private val DRAG_ELEVATION = 8.dp
