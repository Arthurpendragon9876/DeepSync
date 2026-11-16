package org.koitharu.kotatsu.core.ui.pulse

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** How long a press stays visibly dipped, so even a very quick tap still reads as a pulse. */
private const val MIN_HOLD_MS = 70L

/**
 * The app's press feedback for Compose: no ripple, no circular highlight. Whatever the clickable
 * draws *inside* its own background (the icon, the label) dips on press and springs back with a
 * small overshoot, the same feel as the mode button next to the floating bar.
 *
 * Provided once through `LocalIndication` in `DropSauceTheme`, so every `clickable`, `IconButton`,
 * `Button` and `Surface(onClick)` picks it up. Backgrounds and clips placed before the clickable
 * stay still, which is what makes it the icon that pulses and not the whole button.
 */
object PulseIndication : IndicationNodeFactory {

	override fun create(interactionSource: InteractionSource): DelegatableNode =
		PulseIndicationNode(interactionSource)

	override fun hashCode(): Int = -1

	override fun equals(other: Any?): Boolean = other === this
}

private class PulseIndicationNode(
	private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {

	/** 0 = at rest, 1 = fully pressed. The release spring is allowed to dip below 0: that is the overshoot. */
	private val fraction = Animatable(0f)
	private var pressedAt = 0L

	override fun onAttach() {
		coroutineScope.launch {
			interactionSource.interactions.collect { interaction ->
				when (interaction) {
					is PressInteraction.Press -> {
						pressedAt = System.nanoTime()
						launch { fraction.animateTo(1f, spring(dampingRatio = 1f, stiffness = 3800f)) }
					}

					is PressInteraction.Release, is PressInteraction.Cancel -> launch {
						val held = (System.nanoTime() - pressedAt) / 1_000_000L
						if (held < MIN_HOLD_MS) delay(MIN_HOLD_MS - held)
						fraction.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 520f))
					}
				}
			}
		}
	}

	override fun ContentDrawScope.draw() {
		val f = fraction.value
		if (f == 0f) {
			drawContent()
			return
		}
		// The dip is a fixed distance in dp, so a small icon and a wide pill both move by the same
		// visible amount instead of the same percentage.
		val depth = 4.dp.toPx()
		val longSide = max(size.width, size.height).coerceAtLeast(1f)
		val pressed = (1f - 2f * depth / longSide).coerceIn(0.86f, 0.985f)
		val s = 1f - f * (1f - pressed)
		withTransform({ scale(s, s, center) }) {
			this@draw.drawContent()
		}
	}
}
