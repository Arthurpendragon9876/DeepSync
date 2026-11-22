package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

/** One tile of a [PreviewChoiceSettingsItem]: a label and a tiny drawing of what the option looks like. */
class PreviewOption(
	val label: String,
	val draw: DrawScope.() -> Unit,
)

/**
 * Settings row whose options are picked from small illustrated tiles under the title — for the
 * few choices that are easier to recognise by look than by name (theme, list layout).
 */
@Composable
fun PreviewChoiceSettingsItem(
	title: String,
	options: List<PreviewOption>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		icon = icon,
		shape = shape,
		enabled = enabled,
		below = { PreviewChoiceRow(options, selectedIndex, onSelected, enabled = enabled) },
	)
}

/** The tiles of a [PreviewChoiceSettingsItem] on their own, for places that have no card to put them in. */
@Composable
fun PreviewChoiceRow(
	options: List<PreviewOption>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	val haptic = rememberHapticEffect()
	Row(
		modifier = modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		options.forEachIndexed { index, option ->
			PreviewTile(
				label = option.label,
				selected = index == selectedIndex,
				enabled = enabled,
				onClick = {
					haptic(HapticEffect.CONFIRM)
					onSelected(index)
				},
				// Share the row on phones, stop growing on tablets.
				modifier = Modifier
					.weight(1f, fill = false)
					.widthIn(max = 96.dp),
			) {
				Canvas(modifier = Modifier.matchParentSize(), onDraw = option.draw)
			}
		}
	}
}

/** A pickable tile with its [label] underneath; [content] is what the tile shows. */
@Composable
internal fun PreviewTile(
	label: String,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	aspectRatio: Float = 0.85f,
	content: @Composable BoxScope.() -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	val motion = MaterialTheme.motionScheme
	// The picked tile gets a thick accent outline.
	val borderWidth by animateDpAsState(if (selected) 3.dp else 1.dp, motion.fastSpatialSpec(), label = "tileBorder")
	val borderColor by animateColorAsState(
		if (selected) cs.primary else cs.outlineVariant,
		motion.fastEffectsSpec(),
		label = "tileBorderColor",
	)
	val tileShape = RoundedCornerShape(24.dp)
	Column(
		modifier = modifier
			.alpha(if (enabled) 1f else 0.38f)
			.clickable(
				interactionSource = null,
				indication = null,
				enabled = enabled,
				role = Role.RadioButton,
				onClick = onClick,
			),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(aspectRatio)
				.clip(tileShape)
				.background(cs.surfaceContainerHighest)
				.border(borderWidth.coerceAtLeast(1.dp), borderColor, tileShape),
			content = content,
		)
		Spacer(Modifier.height(6.dp))
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
			color = if (selected) cs.primary else cs.onSurfaceVariant,
			textAlign = TextAlign.Center,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** A rounded block placed in fractions of the tile (0..1 on both axes). */
private fun DrawScope.block(color: Color, x: Float, y: Float, w: Float, h: Float, radius: Float = 0.04f) {
	drawRoundRect(
		color = color,
		topLeft = Offset(x * size.width, y * size.height),
		size = Size(w * size.width, h * size.height),
		cornerRadius = CornerRadius(radius * size.width),
	)
}

/** An arrow between two points given in fractions of the tile. */
private fun DrawScope.arrow(color: Color, x1: Float, y1: Float, x2: Float, y2: Float) {
	val start = Offset(x1 * size.width, y1 * size.height)
	val end = Offset(x2 * size.width, y2 * size.height)
	val stroke = size.width * 0.06f
	val head = size.width * 0.13f
	val dir = (end - start) / (end - start).getDistance()
	val normal = Offset(-dir.y, dir.x)
	drawLine(color, start, end, stroke, StrokeCap.Round)
	drawLine(color, end, end - dir * head + normal * head, stroke, StrokeCap.Round)
	drawLine(color, end, end - dir * head - normal * head, stroke, StrokeCap.Round)
}

/** Standard / Right-to-left / Vertical / Webtoon as pages with the reading direction; [labels] in that order. */
@Composable
fun readerModePreviewOptions(labels: List<String>): List<PreviewOption> {
	val page = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
	val accent = MaterialTheme.colorScheme.primary
	fun DrawScope.sideBySide() {
		block(page, 0.12f, 0.2f, 0.35f, 0.6f)
		block(page, 0.53f, 0.2f, 0.35f, 0.6f)
	}
	return listOf(
		PreviewOption(labels[0]) {
			sideBySide()
			arrow(accent, 0.3f, 0.5f, 0.7f, 0.5f)
		},
		PreviewOption(labels[1]) {
			sideBySide()
			arrow(accent, 0.7f, 0.5f, 0.3f, 0.5f)
		},
		PreviewOption(labels[2]) {
			block(page, 0.22f, 0.1f, 0.56f, 0.37f)
			block(page, 0.22f, 0.53f, 0.56f, 0.37f)
			arrow(accent, 0.5f, 0.3f, 0.5f, 0.7f)
		},
		PreviewOption(labels[3]) {
			// One endless strip, no gaps between pages.
			block(page, 0.27f, 0f, 0.46f, 1f, radius = 0f)
			arrow(accent, 0.5f, 0.3f, 0.5f, 0.7f)
		},
	)
}

/** Follow system / Light / Dark as mini screens; [labels] in that order. */
@Composable
fun themePreviewOptions(labels: List<String>, amoled: Boolean): List<PreviewOption> {
	val accent = MaterialTheme.colorScheme.primary
	val lightBg = Color(0xFFF5F1F6)
	val lightInk = Color(0xFF1D1B20)
	val darkBg = if (amoled) Color.Black else Color(0xFF1D1B20)
	val darkInk = Color(0xFFE6E0E9)
	fun DrawScope.screen(bg: Color, ink: Color) {
		drawRect(bg)
		block(ink.copy(alpha = 0.85f), 0.16f, 0.14f, 0.34f, 0.07f)
		block(ink.copy(alpha = 0.14f), 0.16f, 0.29f, 0.68f, 0.24f, radius = 0.07f)
		block(ink.copy(alpha = 0.4f), 0.16f, 0.61f, 0.68f, 0.05f)
		block(ink.copy(alpha = 0.4f), 0.16f, 0.71f, 0.42f, 0.05f)
		block(accent, 0.58f, 0.81f, 0.26f, 0.1f, radius = 0.06f)
	}
	return listOf(
		PreviewOption(labels[0]) {
			screen(lightBg, lightInk)
			// The dark half sits under a diagonal cut.
			val half = Path().apply {
				moveTo(size.width, 0f)
				lineTo(size.width, size.height)
				lineTo(0f, size.height)
				close()
			}
			clipPath(half) { screen(darkBg, darkInk) }
		},
		PreviewOption(labels[1]) { screen(lightBg, lightInk) },
		PreviewOption(labels[2]) { screen(darkBg, darkInk) },
	)
}

/** List / Detailed list / Grid / Cover only as mini layouts; [labels] in that order. */
@Composable
fun listModePreviewOptions(labels: List<String>): List<PreviewOption> {
	val cover = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
	val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
	val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
	return listOf(
		PreviewOption(labels[0]) {
			repeat(4) { i ->
				val y = 0.15f + i * 0.19f
				block(cover, 0.14f, y, 0.15f, 0.13f)
				block(line, 0.36f, y + 0.04f, 0.48f, 0.05f)
			}
		},
		PreviewOption(labels[1]) {
			repeat(2) { i ->
				val y = 0.14f + i * 0.4f
				block(cover, 0.14f, y, 0.26f, 0.32f)
				block(line, 0.47f, y + 0.03f, 0.38f, 0.05f)
				block(faint, 0.47f, y + 0.13f, 0.3f, 0.05f)
				block(faint, 0.47f, y + 0.23f, 0.2f, 0.05f)
			}
		},
		PreviewOption(labels[2]) {
			repeat(4) { i ->
				val x = 0.14f + (i % 2) * 0.39f
				val y = 0.12f + (i / 2) * 0.41f
				block(cover, x, y, 0.33f, 0.27f)
				block(line, x, y + 0.3f, 0.24f, 0.04f)
			}
		},
		PreviewOption(labels[3]) {
			repeat(4) { i ->
				block(cover, 0.14f + (i % 2) * 0.39f, 0.12f + (i / 2) * 0.4f, 0.33f, 0.36f)
			}
		},
	)
}

/**
 * Yugen / Kototoro / Modern as mini players on a dark picture; [labels] in that order. Each draws the arrangement of
 * that style's controls. Colours are fixed (a video is dark in either theme); only Yugen's accents follow the theme.
 */
@Composable
fun playerStylePreviewOptions(labels: List<String>): List<PreviewOption> {
	val accent = MaterialTheme.colorScheme.primary
	val container = MaterialTheme.colorScheme.primaryContainer
	val video = Color(0xFF17171D)
	val ink = Color.White
	val dim = Color.White.copy(alpha = 0.6f)
	val faint = Color.White.copy(alpha = 0.22f)
	val modernRed = Color(0xFFE50914)
	fun DrawScope.disc(color: Color, cx: Float, cy: Float, r: Float) =
		drawCircle(color = color, radius = r * size.width, center = Offset(cx * size.width, cy * size.height))
	fun DrawScope.ring(color: Color, cx: Float, cy: Float, r: Float) =
		drawCircle(
			color = color,
			radius = r * size.width,
			center = Offset(cx * size.width, cy * size.height),
			style = Stroke(width = size.width * 0.014f),
		)
	return listOf(
		// Yugen: big transport in the middle, skip pill on the right, quick toggles under the bar.
		PreviewOption(labels[0]) {
			drawRect(video)
			block(ink, 0.04f, 0.08f, 0.05f, 0.1f, radius = 0.01f)
			block(ink, 0.12f, 0.08f, 0.26f, 0.05f, radius = 0.01f)
			block(dim, 0.12f, 0.16f, 0.18f, 0.04f, radius = 0.01f)
			repeat(3) { i -> disc(dim, 0.74f + i * 0.08f, 0.13f, 0.022f) }
			disc(faint, 0.33f, 0.45f, 0.05f)
			disc(ink, 0.5f, 0.45f, 0.075f)
			disc(faint, 0.67f, 0.45f, 0.05f)
			block(container, 0.76f, 0.6f, 0.2f, 0.1f, radius = 0.03f)
			block(faint, 0.12f, 0.78f, 0.76f, 0.03f, radius = 0.015f)
			block(accent, 0.12f, 0.78f, 0.3f, 0.03f, radius = 0.015f)
			disc(accent, 0.42f, 0.795f, 0.018f)
			repeat(3) { i -> block(dim, 0.06f + i * 0.07f, 0.88f, 0.045f, 0.07f, radius = 0.01f) }
		},
		// Kototoro: plain glyphs, no pills. Title top left, three icons top right; play + thin bar + time; two rows of glyphs and text.
		PreviewOption(labels[1]) {
			drawRect(video)
			block(ink, 0.05f, 0.1f, 0.04f, 0.09f, radius = 0.01f)
			block(ink, 0.13f, 0.08f, 0.22f, 0.06f, radius = 0.01f)
			block(ink, 0.13f, 0.17f, 0.14f, 0.045f, radius = 0.01f)
			repeat(3) { i -> block(ink, 0.73f + i * 0.08f, 0.09f, 0.05f, 0.08f, radius = 0.01f) }
			block(ink, 0.05f, 0.69f, 0.03f, 0.09f, radius = 0.005f)
			block(ink, 0.095f, 0.69f, 0.03f, 0.09f, radius = 0.005f)
			block(faint, 0.17f, 0.735f, 0.62f, 0.012f, radius = 0.006f)
			block(ink, 0.17f, 0.735f, 0.33f, 0.012f, radius = 0.006f)
			disc(ink, 0.5f, 0.741f, 0.017f)
			block(ink, 0.82f, 0.725f, 0.13f, 0.035f, radius = 0.01f)
			repeat(3) { i -> block(ink, 0.05f + i * 0.08f, 0.86f, 0.05f, 0.08f, radius = 0.01f) }
			block(ink, 0.56f, 0.875f, 0.09f, 0.04f, radius = 0.01f)
			block(ink, 0.68f, 0.875f, 0.06f, 0.04f, radius = 0.01f)
			block(ink, 0.77f, 0.875f, 0.07f, 0.04f, radius = 0.01f)
			block(ink, 0.88f, 0.86f, 0.035f, 0.08f, radius = 0.01f)
			block(ink, 0.935f, 0.86f, 0.035f, 0.08f, radius = 0.01f)
		},
		// Modern: black, small centred title and an X; back 10 / bare play / forward 10; thin red bar; Audio & Subtitles.
		PreviewOption(labels[2]) {
			drawRect(Color.Black)
			block(ink, 0.4f, 0.1f, 0.2f, 0.05f, radius = 0.01f)
			block(ink, 0.92f, 0.1f, 0.03f, 0.06f, radius = 0.01f)
			ring(ink, 0.27f, 0.46f, 0.045f)
			val tri = Path().apply {
				moveTo(0.46f * size.width, 0.32f * size.height)
				lineTo(0.46f * size.width, 0.6f * size.height)
				lineTo(0.58f * size.width, 0.46f * size.height)
				close()
			}
			drawPath(tri, ink)
			ring(ink, 0.73f, 0.46f, 0.045f)
			block(faint, 0.05f, 0.74f, 0.8f, 0.012f, radius = 0.006f)
			block(modernRed, 0.05f, 0.74f, 0.1f, 0.012f, radius = 0.006f)
			disc(modernRed, 0.15f, 0.746f, 0.017f)
			block(dim, 0.88f, 0.73f, 0.07f, 0.035f, radius = 0.01f)
			block(ink, 0.43f, 0.87f, 0.04f, 0.06f, radius = 0.01f)
			block(ink, 0.49f, 0.88f, 0.2f, 0.04f, radius = 0.01f)
		},
	)
}

/** The player-style tiles as wide 16:9 cards; three do not fit across a phone, so the row scrolls sideways. */
@Composable
fun PlayerStylePreviewRow(
	options: List<PreviewOption>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val haptic = rememberHapticEffect()
	Row(
		modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		options.forEachIndexed { index, option ->
			PreviewTile(
				label = option.label,
				selected = index == selectedIndex,
				enabled = true,
				onClick = {
					haptic(HapticEffect.CONFIRM)
					onSelected(index)
				},
				modifier = Modifier.width(220.dp),
				aspectRatio = 16f / 9f,
			) {
				Canvas(modifier = Modifier.matchParentSize(), onDraw = option.draw)
			}
		}
	}
}
