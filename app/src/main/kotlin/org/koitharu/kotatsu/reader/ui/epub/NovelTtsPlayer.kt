package org.koitharu.kotatsu.reader.ui.epub

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.reader.ui.tts.ReaderTts
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class TtsTab(val title: String) {
	VOICE("Voice"),
	PLAYBACK("Playback"),
	OPTIONS("Options"),
}

private val SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Listen player for the novel reader. Replaces the bottom bar while open and drives [ReaderTts]
 * (the engine, highlighting and foreground service stay DeepSync's). Layout and features follow
 * Novery's TTS player: transport with sentence + chapter skipping, progress, quick speed, and a
 * Voice / Playback / Options settings panel with a full voice list and a sleep timer.
 */
@Composable
internal fun NovelTtsPlayer(
	state: NovelChromeState,
	tts: ReaderTts,
	settings: AppSettings,
	modifier: Modifier = Modifier,
) {
	val isPlaying by tts.isPlaying.collectAsState()
	val position by tts.position.collectAsState()
	val sentenceCount by tts.sentenceCount.collectAsState()
	val sleepMs by tts.sleepRemainingMs.collectAsState()
	val sleepAtChapterEnd by tts.sleepAtChapterEnd.collectAsState()
	var showSettings by remember { mutableStateOf(false) }
	var tab by remember { mutableStateOf(TtsTab.VOICE) }
	var revision by remember { mutableIntStateOf(0) }
	// Passed to every tab so they recompose after a settings write (AppSettings is not observable state)
	val tick = revision
	val refresh: () -> Unit = { revision++ }
	DisposableEffect(Unit) { onDispose { state.setRegion("tts", null) } }

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp)
			.padding(bottom = state.bottomInset + 8.dp)
			.onGloballyPositioned { state.setRegion("tts", it.boundsInWindow()) },
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		AnimatedVisibility(
			visible = showSettings,
			enter = slideInVertically(tween(240)) { it } + fadeIn(tween(200)),
			exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150)),
		) {
			Surface(shape = RoundedCornerShape(Bar.radiusMedium), color = Bar.surfaceVariant) {
				Column(Modifier.heightIn(max = 380.dp)) {
					TtsTabRow(tab) { tab = it }
					Column(
						modifier = Modifier
							.weight(1f, fill = false)
							.verticalScroll(rememberScrollState())
							.padding(16.dp),
						verticalArrangement = Arrangement.spacedBy(14.dp),
					) {
						when (tab) {
							TtsTab.VOICE -> VoiceTab(tts, isPlaying, sentenceCount, refresh, tick)
							TtsTab.PLAYBACK -> PlaybackTab(tts, settings, refresh, tick)
							TtsTab.OPTIONS -> OptionsTab(tts, settings, sleepMs, sleepAtChapterEnd, refresh, tick)
						}
					}
				}
			}
		}
		Surface(shape = RoundedCornerShape(Bar.radius), color = Bar.surface) {
			Column(
				modifier = Modifier.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				// header
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Surface(shape = CircleShape, color = Bar.accentMuted, modifier = Modifier.size(40.dp)) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							Icon(
								painter = painterResource(R.drawable.ic_novel_headphones),
								contentDescription = null,
								tint = Bar.onAccentMuted,
								modifier = Modifier.size(20.dp),
							)
						}
					}
					Column(Modifier.weight(1f)) {
						Text(
							text = "Listening",
							style = MaterialTheme.typography.titleSmall,
							fontWeight = FontWeight.SemiBold,
							color = Bar.textPrimary,
						)
						Text(
							text = state.chapterTitle,
							style = MaterialTheme.typography.bodySmall,
							color = Bar.textSecondary,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
					IconButton(onClick = { showSettings = !showSettings }) {
						Icon(
							painter = painterResource(R.drawable.ic_tune),
							contentDescription = "Audio settings",
							tint = if (showSettings) Bar.accent else Bar.textSecondary,
						)
					}
					// Tuck the panel away to read the text; the audio keeps playing. The cross stops it.
					IconButton(onClick = { state.ttsMinimized = true }) {
						Icon(
							painter = painterResource(R.drawable.ic_expand_more),
							contentDescription = "Minimize",
							tint = Bar.textSecondary,
						)
					}
					IconButton(onClick = state.onTtsClose) {
						Icon(
							painter = painterResource(R.drawable.ic_close),
							contentDescription = "Stop",
							tint = Bar.textSecondary,
						)
					}
				}
				// progress
				val index = position?.index ?: 0
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					LinearProgressIndicator(
						progress = { if (sentenceCount > 0) ((index + 1f) / sentenceCount).coerceIn(0f, 1f) else 0f },
						modifier = Modifier
							.fillMaxWidth()
							.height(4.dp),
						color = Bar.accent,
						trackColor = Bar.border,
					)
					Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(
							text = if (sentenceCount > 0) "${index + 1} / $sentenceCount" else "",
							style = MaterialTheme.typography.labelSmall,
							color = Bar.textMuted,
						)
						Text(
							text = when {
								sleepMs > 0 -> "Sleep in ${formatClock(sleepMs)}"
								sleepAtChapterEnd -> "Stops after this chapter"
								else -> "Chapter ${state.chapterNumber} / ${state.totalChapters}"
							},
							style = MaterialTheme.typography.labelSmall,
							color = Bar.textMuted,
						)
					}
				}
				// transport
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceEvenly,
					verticalAlignment = Alignment.CenterVertically,
				) {
					TransportButton(R.drawable.ic_skip_previous, "Previous chapter") { state.onTtsChapter(-1) }
					TransportButton(R.drawable.ic_novel_prev_sentence, "Previous sentence") { tts.skip(-1) }
					Surface(
						onClick = { tts.toggle() },
						shape = CircleShape,
						color = Bar.accent,
						modifier = Modifier.size(64.dp),
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							Icon(
								painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
								contentDescription = if (isPlaying) "Pause" else "Play",
								tint = Bar.onAccent,
								modifier = Modifier.size(30.dp),
							)
						}
					}
					TransportButton(R.drawable.ic_novel_next_sentence, "Next sentence") { tts.skip(1) }
					TransportButton(R.drawable.ic_skip_next, "Next chapter") { state.onTtsChapter(1) }
				}
				// quick speed
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.horizontalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
				) {
					SPEED_PRESETS.forEach { speed ->
						val selected = abs(settings.epubTtsSpeed - speed) < 0.01f
						Surface(
							onClick = { settings.epubTtsSpeed = speed; tts.applyTuning(); refresh() },
							shape = CircleShape,
							color = if (selected) Bar.accentMuted else Bar.surfaceVariant,
						) {
							Text(
								text = "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x",
								style = MaterialTheme.typography.labelLarge,
								color = if (selected) Bar.onAccentMuted else Bar.textSecondary,
								modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun TransportButton(icon: Int, description: String, onClick: () -> Unit) {
	IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
		Icon(
			painter = painterResource(icon),
			contentDescription = description,
			tint = Bar.textPrimary,
			modifier = Modifier.size(28.dp),
		)
	}
}

@Composable
private fun TtsTabRow(selected: TtsTab, onSelect: (TtsTab) -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 12.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		TtsTab.entries.forEach { t ->
			val isSelected = t == selected
			Surface(
				onClick = { onSelect(t) },
				shape = CircleShape,
				color = if (isSelected) Bar.accentMuted else Bar.surfaceElevated,
			) {
				Text(
					text = t.title,
					style = MaterialTheme.typography.labelLarge,
					color = if (isSelected) Bar.onAccentMuted else Bar.textSecondary,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
				)
			}
		}
	}
}

@Composable
private inline fun VoiceTab(tts: ReaderTts, isPlaying: Boolean, sentenceCount: Int, noinline refresh: () -> Unit, rev: Int) {
	val context = LocalContext.current
	// The engine only lists voices once it is up, hence the keys.
	val voices = remember(isPlaying, sentenceCount, tts.selectedVoiceName()) { tts.allVoices() }
	val selected = tts.selectedVoiceName()
	if (voices.isEmpty()) {
		Text(
			text = "No voices found for this book's language yet. Start playback once, or install a voice in the system text-to-speech settings.",
			style = MaterialTheme.typography.bodySmall,
			color = Bar.textSecondary,
		)
	}
	VoiceRow("Automatic", "Best installed voice for this book", selected == null) {
		tts.selectVoiceByName(null); refresh()
	}
	voices.take(40).forEach { voice ->
		val (title, detail) = tts.describeVoice(voice)
		VoiceRow(title, detail, selected == voice.name) {
			tts.selectVoiceByName(voice.name); refresh()
		}
	}
	Surface(
		onClick = {
			runCatching {
				context.startActivity(
					Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
				)
			}
		},
		shape = RoundedCornerShape(Bar.radiusSmall),
		color = androidx.compose.ui.graphics.Color.Transparent,
		border = BorderStroke(1.dp, Bar.border),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 12.dp),
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				painter = painterResource(R.drawable.ic_settings),
				contentDescription = null,
				tint = Bar.textSecondary,
				modifier = Modifier.size(18.dp),
			)
			Spacer(Modifier.padding(horizontal = 5.dp))
			Text(
				text = "Open system TTS settings",
				style = MaterialTheme.typography.labelLarge,
				color = Bar.textSecondary,
			)
		}
	}
}

@Composable
private fun VoiceRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(Bar.radiusSmall),
		color = if (selected) Bar.accentMuted else Bar.surfaceElevated,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					color = if (selected) Bar.onAccentMuted else Bar.textPrimary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = detail,
					style = MaterialTheme.typography.bodySmall,
					color = if (selected) Bar.onAccentMuted else Bar.textMuted,
				)
			}
			if (selected) {
				Icon(
					painter = painterResource(R.drawable.ic_check),
					contentDescription = null,
					tint = Bar.onAccentMuted,
					modifier = Modifier.size(20.dp),
				)
			}
		}
	}
}

@Composable
private inline fun PlaybackTab(tts: ReaderTts, settings: AppSettings, noinline refresh: () -> Unit, rev: Int) {
	Section("Speed", "%.2fx".format(settings.epubTtsSpeed)) {
		SliderRow(
			value = settings.epubTtsSpeed,
			range = 0.5f..3f,
			onFinished = { tts.applyTuning() },
		) { settings.epubTtsSpeed = (it * 20).roundToInt() / 20f; refresh() }
	}
	Section("Pitch", "%.2f".format(settings.epubTtsPitch)) {
		SliderRow(
			value = settings.epubTtsPitch,
			range = 0.5f..2f,
			onFinished = { tts.applyTuning() },
		) { settings.epubTtsPitch = (it * 20).roundToInt() / 20f; refresh() }
	}
	Text(
		text = "Adjustments apply right away. Find a comfortable pace for long listening sessions.",
		style = MaterialTheme.typography.bodySmall,
		color = Bar.textMuted,
	)
}

@Composable
private inline fun OptionsTab(
	tts: ReaderTts,
	settings: AppSettings,
	sleepMs: Long,
	sleepAtChapterEnd: Boolean,
	noinline refresh: () -> Unit,
	rev: Int,
) {
	ToggleRow("Follow reading", settings.isEpubTtsFollowEnabled) { settings.isEpubTtsFollowEnabled = it; refresh() }
	ToggleRow("Continue to next chapter", settings.isEpubTtsAutoAdvance) { settings.isEpubTtsAutoAdvance = it; refresh() }
	Section("Sleep timer", if (sleepMs > 0) formatClock(sleepMs) else null) {
		val options = listOf("off" to "Off", "15" to "15 min", "30" to "30 min", "45" to "45 min", "60" to "60 min", "end" to "End of chapter")
		val current = when {
			sleepAtChapterEnd -> "end"
			sleepMs > 0 -> "timer"
			else -> "off"
		}
		ChipFlow(options = options, selected = current) { value ->
			when (value) {
				"off" -> tts.setSleepTimer(0)
				"end" -> tts.setSleepAtChapterEnd(true)
				else -> tts.setSleepTimer(value.toInt())
			}
			refresh()
		}
	}
}

private fun formatClock(ms: Long): String {
	val total = (ms / 1000).toInt()
	return "%d:%02d".format(total / 60, total % 60)
}

/** What is left of the listen panel while it is minimized: one round button that brings it back. */
@Composable
internal fun NovelTtsMiniButton(
	state: NovelChromeState,
	tts: ReaderTts,
	modifier: Modifier = Modifier,
) {
	val isPlaying by tts.isPlaying.collectAsState()
	DisposableEffect(Unit) { onDispose { state.setRegion("tts", null) } }
	Surface(
		onClick = { state.ttsMinimized = false },
		shape = CircleShape,
		color = if (isPlaying) Bar.accent else Bar.accentMuted,
		modifier = modifier
			.padding(end = 16.dp, bottom = state.bottomInset + 16.dp)
			.size(52.dp)
			.onGloballyPositioned { state.setRegion("tts", it.boundsInWindow()) },
	) {
		Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
			Icon(
				painter = painterResource(R.drawable.ic_novel_headphones),
				contentDescription = "Show listen panel",
				tint = if (isPlaying) Bar.onAccent else Bar.onAccentMuted,
				modifier = Modifier.size(24.dp),
			)
		}
	}
}
