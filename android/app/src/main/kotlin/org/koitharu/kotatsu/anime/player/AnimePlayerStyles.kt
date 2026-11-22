package org.koitharu.kotatsu.anime.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koitharu.kotatsu.R
import kotlin.math.roundToInt

/** Snapshot of what the control layouts draw; built by the activity on every recomposition. */
internal class PlayerChrome(
	val animeTitle: String,
	val episodeTitle: String,
	val episodeChip: String,
	val qualityChip: String,
	val isPlaying: Boolean,
	val position: Long,
	val duration: Long,
	val speed: Float,
	val aspectLabel: String,
	val hasPrevious: Boolean,
	val hasNext: Boolean,
	val canPickStream: Boolean,
	val canExitFullscreen: Boolean,
	val skipSeconds: Int,
)

/** What the control layouts press. Every layout shares these, so behaviour never differs between styles. */
internal class PlayerChromeActions(
	val back: () -> Unit,
	val togglePlay: () -> Unit,
	val seekTo: (Long) -> Unit,
	val seekBy: (Long) -> Unit,
	val previous: () -> Unit,
	val next: () -> Unit,
	val subtitles: () -> Unit,
	val audio: () -> Unit,
	val quality: () -> Unit,
	val more: () -> Unit,
	val speed: () -> Unit,
	val episodes: () -> Unit,
	val aspect: () -> Unit,
	val lock: () -> Unit,
	val rotate: () -> Unit,
	val enterFullscreen: () -> Unit,
	val exitFullscreen: () -> Unit,
)

private val ModernRed = Color(0xFFE50914)
private val TrackOff = Color(0x66FFFFFF)

/** Same small margin on both sides (the cut-out is ignored on purpose), system bars only top and bottom. */
@Composable
internal fun Modifier.fullscreenInsets(): Modifier =
	windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)).padding(horizontal = 20.dp)

private fun clock(ms: Long): String {
	val total = (ms / 1000).coerceAtLeast(0)
	val h = total / 3600
	val m = (total % 3600) / 60
	val s = total % 60
	return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun speedText(speed: Float) = "%.2fx".format(speed)

// --- Kototoro ------------------------------------------------------------------------------------
// Plain white glyphs on a faint gradient, no button backgrounds: title / subtitle top left, subtitles, audio and
// more top right; play, a thin seek bar and "0:13 / 0:24" in one row; then prev / next / episodes on the left and
// speed, Intro, Outro, lock, rotate on the right.

@Composable
internal fun KototoroControls(c: PlayerChrome, a: PlayerChromeActions) {
	Box(Modifier.fillMaxSize()) {
		Row(
			Modifier.fillMaxWidth().align(Alignment.TopCenter)
				.background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
				.fullscreenInsets().padding(top = 4.dp, bottom = 12.dp),
			verticalAlignment = Alignment.Top,
		) {
			PlainGlyph(R.drawable.ic_ap_back, "Back", 44.dp, onClick = a.back)
			Column(Modifier.weight(1f).padding(start = 6.dp, top = 2.dp)) {
				Text(c.animeTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(c.episodeChip, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			PlainGlyph(R.drawable.ic_ap_subtitles, "Subtitles", 44.dp, onClick = a.subtitles)
			PlainGlyph(R.drawable.ic_ap_music, "Audio track", 44.dp, onClick = a.audio)
			PlainGlyph(R.drawable.ic_ap_more, "More", 44.dp, onClick = a.more)
		}
		Column(
			Modifier.fillMaxWidth().align(Alignment.BottomCenter)
				.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
				.fullscreenInsets().padding(top = 12.dp, bottom = 4.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				PlainGlyph(if (c.isPlaying) R.drawable.ic_ap_pause else R.drawable.ic_ap_play, "Play / pause", 44.dp, onClick = a.togglePlay)
				ThinSeekBar(c, a, Color.White, trackHeight = 2.dp, thumb = 14.dp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
				Text("${clock(c.position)} / ${clock(c.duration)}", color = Color.White, fontSize = 14.sp, maxLines = 1)
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				PlainGlyph(R.drawable.ic_ap_prev, "Previous episode", 44.dp, enabled = c.hasPrevious, onClick = a.previous)
				PlainGlyph(R.drawable.ic_ap_next, "Next episode", 44.dp, enabled = c.hasNext, onClick = a.next)
				PlainGlyph(R.drawable.ic_ap_grid, "Episodes", 44.dp, onClick = a.episodes)
				Spacer(Modifier.weight(1f))
				TextAction(speedText(c.speed), a.speed)
				// Intro skips ahead by the skip length; Outro jumps to the next episode (or the very end of the last one).
				TextAction("Intro") { a.seekBy(c.skipSeconds * 1000L) }
				TextAction("Outro") { if (c.hasNext) a.next() else a.seekTo(c.duration) }
				PlainGlyph(R.drawable.ic_ap_lock, "Lock", 44.dp, onClick = a.lock)
				PlainGlyph(R.drawable.ic_ap_rotate, "Rotate", 44.dp, onClick = a.rotate)
			}
		}
	}
}

/** Kototoro on the small watch-page video: title strip, then play, bar, time and fullscreen. */
@Composable
internal fun KototoroCompactControls(c: PlayerChrome, a: PlayerChromeActions) {
	Box(Modifier.fillMaxSize()) {
		Row(
			Modifier.fillMaxWidth().align(Alignment.TopCenter)
				.background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PlainGlyph(R.drawable.ic_ap_back, "Back", 36.dp, onClick = a.back)
			Text(c.episodeChip, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
			PlainGlyph(R.drawable.ic_ap_subtitles, "Subtitles", 36.dp, onClick = a.subtitles)
			PlainGlyph(R.drawable.ic_ap_more, "More", 36.dp, onClick = a.more)
		}
		Row(
			Modifier.fillMaxWidth().align(Alignment.BottomCenter)
				.background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
				.padding(horizontal = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PlainGlyph(if (c.isPlaying) R.drawable.ic_ap_pause else R.drawable.ic_ap_play, "Play / pause", 36.dp, onClick = a.togglePlay)
			ThinSeekBar(c, a, Color.White, trackHeight = 2.dp, thumb = 12.dp, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
			Text("${clock(c.position)} / ${clock(c.duration)}", color = Color.White, fontSize = 11.sp, maxLines = 1)
			PlainGlyph(R.drawable.ic_ap_fullscreen, "Fullscreen", 36.dp, onClick = a.enterFullscreen)
		}
	}
}

// --- Modern -------------------------------------------------------------------------------------
// Dimmed picture, centred small title and a close X; back 10 / big bare play / forward 10 in the middle; a thin red
// bar with a red dot and the time left; captioned actions under it, "Audio & Subtitles" in the middle.

@Composable
internal fun ModernControls(c: PlayerChrome, a: PlayerChromeActions) {
	Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
		Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).fullscreenInsets().padding(top = 4.dp)) {
			Text(
				listOf(c.animeTitle, c.episodeChip).filter { it.isNotBlank() }.joinToString(" · "),
				color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
			)
			PlainGlyph(R.drawable.ic_ap_close, "Close", 44.dp, onClick = a.back, modifier = Modifier.align(Alignment.CenterEnd))
		}
		Row(
			Modifier.align(Alignment.Center),
			horizontalArrangement = Arrangement.spacedBy(72.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Skip10(R.drawable.ic_ap_replay, "Back 10 seconds", 48.dp) { a.seekBy(-10_000L) }
			PlainGlyph(if (c.isPlaying) R.drawable.ic_ap_pause else R.drawable.ic_ap_play, "Play / pause", 80.dp, iconScale = 0.85f, onClick = a.togglePlay)
			Skip10(R.drawable.ic_ap_forward, "Forward 10 seconds", 48.dp) { a.seekBy(10_000L) }
		}
		Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).fullscreenInsets().padding(bottom = 4.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				ThinSeekBar(c, a, ModernRed, trackHeight = 2.dp, thumb = 14.dp, modifier = Modifier.weight(1f))
				Text(
					clock((c.duration - c.position).coerceAtLeast(0L)),
					color = Color.White, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp),
				)
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
				Captioned(R.drawable.ic_ap_speed, "Speed (${speedText(c.speed)})", a.speed)
				Captioned(R.drawable.ic_ap_episodes, "Episodes", a.episodes)
				Captioned(R.drawable.ic_ap_subtitles, "Audio & Subtitles", a.subtitles)
				if (c.hasNext) Captioned(R.drawable.ic_ap_next, "Next Ep.", a.next)
				Captioned(R.drawable.ic_ap_more, "More", a.more)
			}
		}
	}
}

@Composable
internal fun ModernCompactControls(c: PlayerChrome, a: PlayerChromeActions) {
	Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
		Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(
				listOf(c.animeTitle, c.episodeChip).filter { it.isNotBlank() }.joinToString(" · "),
				color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f).padding(start = 8.dp),
			)
			PlainGlyph(R.drawable.ic_ap_more, "More", 36.dp, onClick = a.more)
		}
		Row(
			Modifier.align(Alignment.Center),
			horizontalArrangement = Arrangement.spacedBy(28.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Skip10(R.drawable.ic_ap_replay, "Back 10 seconds", 30.dp) { a.seekBy(-10_000L) }
			PlainGlyph(if (c.isPlaying) R.drawable.ic_ap_pause else R.drawable.ic_ap_play, "Play / pause", 48.dp, iconScale = 0.85f, onClick = a.togglePlay)
			Skip10(R.drawable.ic_ap_forward, "Forward 10 seconds", 30.dp) { a.seekBy(10_000L) }
		}
		Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			ThinSeekBar(c, a, ModernRed, trackHeight = 2.dp, thumb = 12.dp, modifier = Modifier.weight(1f))
			Text(clock((c.duration - c.position).coerceAtLeast(0L)), color = Color.White, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
			PlainGlyph(R.drawable.ic_ap_fullscreen, "Fullscreen", 36.dp, onClick = a.enterFullscreen)
		}
	}
}

// --- shared pieces -------------------------------------------------------------------------------

/** [ThinTrack] driven by the player position. */
@Composable
private fun ThinSeekBar(
	c: PlayerChrome,
	a: PlayerChromeActions,
	color: Color,
	trackHeight: Dp,
	thumb: Dp,
	modifier: Modifier = Modifier,
) {
	ThinTrack(
		fraction = if (c.duration > 0) (c.position.toFloat() / c.duration).coerceIn(0f, 1f) else 0f,
		enabled = c.duration > 0,
		color = color,
		trackHeight = trackHeight,
		thumb = thumb,
		modifier = modifier,
		onSeek = { a.seekTo((it * c.duration).toLong()) },
	)
}

/**
 * A thin seek bar: a [trackHeight] line with a small round thumb. Tapping seeks straight there; dragging moves the
 * thumb and only seeks on release so the video is not scrubbed on every pixel. [onSeek] gets a 0..1 fraction.
 */
@Composable
internal fun ThinTrack(
	fraction: Float,
	enabled: Boolean,
	color: Color,
	trackHeight: Dp,
	thumb: Dp,
	modifier: Modifier = Modifier,
	onSeek: (Float) -> Unit,
) {
	var dragging by remember { mutableStateOf<Float?>(null) }
	var innerWidth by remember { mutableIntStateOf(0) }
	val seek by rememberUpdatedState(onSeek)
	val thumbPx = with(LocalDensity.current) { thumb.toPx() }
	val shown = dragging ?: fraction
	Box(
		modifier
			.height(32.dp)
			.pointerInput(enabled) {
				if (!enabled) return@pointerInput
				detectTapGestures { offset -> seek((offset.x / size.width).coerceIn(0f, 1f)) }
			}
			.pointerInput(enabled) {
				if (!enabled) return@pointerInput
				detectHorizontalDragGestures(
					onDragStart = { offset -> dragging = (offset.x / size.width).coerceIn(0f, 1f) },
					onHorizontalDrag = { change, _ ->
						change.consume()
						dragging = (change.position.x / size.width).coerceIn(0f, 1f)
					},
					onDragEnd = {
						dragging?.let { seek(it) }
						dragging = null
					},
					onDragCancel = { dragging = null },
				)
			},
		contentAlignment = Alignment.CenterStart,
	) {
		Box(
			Modifier.fillMaxWidth().padding(horizontal = thumb / 2).onSizeChanged { innerWidth = it.width },
			contentAlignment = Alignment.CenterStart,
		) {
			Box(Modifier.fillMaxWidth().height(trackHeight).clip(CircleShape).background(TrackOff))
			Box(Modifier.fillMaxWidth(shown).height(trackHeight).clip(CircleShape).background(color))
			Box(
				Modifier
					.offset { IntOffset((shown * innerWidth - thumbPx / 2).roundToInt(), 0) }
					.size(thumb).clip(CircleShape).background(color),
			)
		}
	}
}

/** A bare white glyph with a generous touch target and no background. */
@Composable
private fun PlainGlyph(
	icon: Int,
	description: String,
	size: Dp,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	iconScale: Float = 0.55f,
	onClick: () -> Unit,
) {
	Box(
		modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painterResource(icon), description,
			tint = if (enabled) Color.White else Color(0x55FFFFFF),
			modifier = Modifier.size(size * iconScale),
		)
	}
}

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
	Text(
		text, color = Color.White, fontSize = 16.sp, maxLines = 1,
		modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
	)
}

/** Replay / forward arrow with a small "10" in the middle, like a streaming app's seek buttons. */
@Composable
private fun Skip10(icon: Int, description: String, size: Dp, onClick: () -> Unit) {
	Box(Modifier.size(size * 1.3f).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
		Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size(size))
		Text("10", color = Color.White, fontSize = (size.value * 0.25f).sp, fontWeight = FontWeight.Bold)
	}
}

@Composable
private fun Captioned(icon: Int, label: String, onClick: () -> Unit) {
	Column(
		Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Icon(painterResource(icon), label, tint = Color.White, modifier = Modifier.size(22.dp))
		Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
	}
}
