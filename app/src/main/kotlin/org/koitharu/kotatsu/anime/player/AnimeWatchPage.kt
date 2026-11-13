package org.koitharu.kotatsu.anime.player

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeEpisodeMeta
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.util.Locale

/** Everything the watch page needs, so the activity can stay in charge of the actual playback. */
internal class WatchPageData(
	val manga: Manga,
	val chapters: List<MangaChapter>,
	/** Index into [chapters] of the episode that is loaded or playing, -1 while waiting for a pick. */
	val selectedIndex: Int,
	val isPlaying: Boolean,
	/** The episode in history, for the watched ticks. */
	val historyChapterId: Long?,
	val historyPercent: Float,
	val loading: Boolean,
	val error: String?,
	/** "07:24 · Episode 7" or "General anime note": what a new note will be tagged with (read lazily: it ticks). */
	val noteTag: () -> String,
	val notes: List<AnimeNote>,
	val audioPreset: String,
)

internal class WatchPageActions(
	val onSelectEpisode: (Int) -> Unit,
	val onRetry: () -> Unit,
	val onAddNote: (String) -> Unit,
	val onDeleteNote: (AnimeNote) -> Unit,
	val onAudioPreset: (String) -> Unit,
	val onNoteClick: (AnimeNote) -> Unit,
	val onSpeed: () -> Unit,
	val onSubtitles: () -> Unit,
	val onAudio: () -> Unit,
)

/** What sits under the video: title, details card, notes and the episode list, in one scrolling column. */
@Composable
internal fun WatchPage(
	data: WatchPageData,
	actions: WatchPageActions,
	imageLoader: ImageLoader,
	modifier: Modifier = Modifier,
) {
	var ascending by rememberSaveable { mutableStateOf(true) }
	val chapters = data.chapters
	val shown = remember(chapters, ascending) { if (ascending) chapters else chapters.asReversed() }
	val historyIdx = remember(chapters, data.historyChapterId) {
		chapters.indexOfFirst { it.id == data.historyChapterId }
	}
	val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

	LazyColumn(
		modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navBar + 24.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		item(key = "title") { TitleBlock(data) }
		// Speed, subtitles and audio are one tap away under the title once an episode is loaded.
		if (data.selectedIndex >= 0) {
			item(key = "controls") { QuickControls(actions) }
		}
		item(key = "info") { InfoCard(data.manga, chapters.size) }
		item(key = "notes") { NotesBlock(data, actions) }
		item(key = "header") {
			EpisodesHeader(
				count = chapters.size,
				waiting = data.selectedIndex < 0,
				ascending = ascending,
				preset = data.audioPreset,
				onSort = { ascending = !ascending },
				onPreset = actions.onAudioPreset,
			)
		}
		if (data.loading && chapters.isEmpty()) {
			item(key = "loading") {
				Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
			}
		}
		data.error?.let { message ->
			if (chapters.isEmpty()) {
				item(key = "error") {
					Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
						Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
						TextButton(onClick = actions.onRetry) { Text("Retry") }
					}
				}
			}
		}
		items(shown, key = { it.id }) { chapter ->
			val position = chapters.indexOf(chapter)
			EpisodeCard(
				chapter = chapter,
				manga = data.manga,
				imageLoader = imageLoader,
				selected = position == data.selectedIndex,
				playing = position == data.selectedIndex && data.isPlaying,
				watched = historyIdx >= 0 && (position < historyIdx || (position == historyIdx && data.historyPercent >= 0.9f)),
				onClick = { actions.onSelectEpisode(position) },
			)
		}
	}
}

@Composable
private fun TitleBlock(data: WatchPageData) {
	val chapter = data.chapters.getOrNull(data.selectedIndex)
	val heading = if (chapter == null) {
		"Episode Selection Pending"
	} else {
		val label = AnimePlaybackSession.episodeLabel(chapter)
		chapter.title?.takeIf { it.isNotBlank() && it != label }?.let { "$label - $it" } ?: label
	}
	Column {
		Text(
			heading,
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(
				data.manga.title,
				color = MaterialTheme.colorScheme.primary,
				fontSize = 14.sp,
				fontWeight = FontWeight.Medium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f, fill = false),
			)
			Text(
				"  •  " + if (chapter == null) "Select an episode to begin playback" else if (data.isPlaying) "Now playing" else "Paused",
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontSize = 12.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** Some sources start the synopsis with "★★★★☆ 7.28"; that is a rating, so it moves to the rating chip. */
private val RATING_PREFIX = Regex("""^[★☆]{1,5}\s*(\d+(?:[.,]\d+)?)\s*""")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoCard(manga: Manga, episodeCount: Int) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	val plain = remember(manga.description) {
		manga.description?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty()
	}
	val match = remember(plain) { RATING_PREFIX.find(plain) }
	val synopsis = remember(plain, match) { if (match != null) plain.removeRange(match.range).trim() else plain }
	val ratingText = if (manga.hasRating) {
		String.format(Locale.ROOT, "%.1f", manga.rating * 5f)
	} else {
		match?.groupValues?.get(1)?.replace(',', '.')
	}
	val tags = remember(manga.tags) { manga.tags.map { it.title }.take(10) }
	val amber = Color(0xFFFFB300)
	Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			// One wrapping row: rating, episode count and genres share the space instead of each owning a row.
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
				if (ratingText != null) {
					Row(
						Modifier.clip(RoundedCornerShape(50)).background(amber.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(painterResource(R.drawable.ic_star_small), null, tint = amber, modifier = Modifier.size(14.dp))
						Text(
							ratingText,
							color = amber,
							fontWeight = FontWeight.Bold,
							fontSize = 12.sp,
							modifier = Modifier.padding(start = 4.dp),
						)
					}
				}
				if (episodeCount > 0) SmallTag("$episodeCount Eps")
				tags.forEach { tag ->
					Text(
						tag,
						fontSize = 11.sp,
						color = MaterialTheme.colorScheme.onSecondaryContainer,
						modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)
							.padding(horizontal = 10.dp, vertical = 3.dp),
					)
				}
			}
			if (synopsis.isNotEmpty()) {
				Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
				Column(Modifier.clickable { expanded = !expanded }) {
					Text(
						synopsis,
						fontSize = 13.sp,
						lineHeight = 18.sp,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = if (expanded) Int.MAX_VALUE else 3,
						overflow = TextOverflow.Ellipsis,
					)
					Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
						Text(
							if (expanded) "Show less" else "Read more",
							color = MaterialTheme.colorScheme.primary,
							fontSize = 12.sp,
							fontWeight = FontWeight.SemiBold,
						)
						Icon(
							painterResource(R.drawable.ic_ap_expand), null,
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
						)
					}
				}
			}
		}
	}
}

/** Playback speed, subtitles and audio as chips on the page, so they need no trip into the video overlay. */
@Composable
private fun QuickControls(actions: WatchPageActions) {
	Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		listOf(
			Triple(R.drawable.ic_ap_speed, "Speed", actions.onSpeed),
			Triple(R.drawable.ic_ap_subtitles, "Subtitles", actions.onSubtitles),
			Triple(R.drawable.ic_ap_audio, "Audio", actions.onAudio),
		).forEach { (icon, label, onClick) ->
			Row(
				Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
					.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
				Text(
					label,
					fontSize = 13.sp,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
					maxLines = 1,
					modifier = Modifier.padding(start = 6.dp),
				)
			}
		}
	}
}

@Composable
private fun SmallTag(text: String) {
	Text(
		text,
		fontSize = 11.sp,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
			.padding(horizontal = 8.dp, vertical = 3.dp),
	)
}

@Composable
private fun NotesBlock(data: WatchPageData, actions: WatchPageActions) {
	var open by rememberSaveable { mutableStateOf(false) }
	var adding by rememberSaveable { mutableStateOf(false) }
	var text by rememberSaveable { mutableStateOf("") }
	val ctx = LocalContext.current
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Surface(
			onClick = { open = !open },
			shape = RoundedCornerShape(16.dp),
			color = MaterialTheme.colorScheme.surfaceContainer,
			modifier = Modifier.fillMaxWidth(),
		) {
			Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
				Icon(painterResource(R.drawable.ic_ap_edit), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
				Text("Notes & Annotations", fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
				Text(
					"${data.notes.size}",
					fontSize = 11.sp,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
					modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
						.padding(horizontal = 8.dp, vertical = 2.dp),
				)
				Spacer(Modifier.weight(1f))
				Icon(
					painterResource(R.drawable.ic_ap_expand), null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f),
				)
			}
		}
		AnimatedVisibility(open) {
			Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
				Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							"MY NOTES",
							fontSize = 11.sp,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.weight(1f),
						)
						if (!adding) {
							Row(
								Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
									.clickable { adding = true }.padding(horizontal = 10.dp, vertical = 6.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								Icon(painterResource(R.drawable.ic_add), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
								Text("Write note", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
							}
						}
					}
					if (adding) {
						Column(
							Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(
									"Tag: ${data.noteTag()}",
									fontSize = 11.sp,
									color = MaterialTheme.colorScheme.primary,
									modifier = Modifier.weight(1f),
								)
								Icon(
									painterResource(R.drawable.ic_ap_close), "Cancel",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.size(18.dp).clickable { adding = false; text = "" },
								)
							}
							Box(
								Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(8.dp))
									.background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(10.dp),
							) {
								if (text.isEmpty()) {
									Text("Write your note, theory or scene timestamp here...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
								}
								BasicTextField(
									value = text,
									onValueChange = { text = it },
									textStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
									modifier = Modifier.fillMaxSize(),
								)
							}
							Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
								TextButton(onClick = { adding = false; text = "" }) { Text("Cancel") }
								TextButton(
									enabled = text.isNotBlank(),
									onClick = { actions.onAddNote(text.trim()); text = ""; adding = false },
								) { Text("Save note") }
							}
						}
					}
					if (data.notes.isEmpty() && !adding) {
						Text(
							"No notes yet. Tap \"Write note\" to add one.",
							fontSize = 12.sp,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textAlign = TextAlign.Center,
							modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
						)
					}
					data.notes.forEach { note ->
						Column(
							Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
								.clickable(enabled = note.positionMs >= 0) { actions.onNoteClick(note) }.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(6.dp),
						) {
							Row(verticalAlignment = Alignment.CenterVertically) {
								Text(
									if (note.positionMs >= 0) "@ ${formatClock(note.positionMs)}" else "General",
									fontSize = 10.sp,
									color = MaterialTheme.colorScheme.onPrimaryContainer,
									modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer)
										.padding(horizontal = 6.dp, vertical = 2.dp),
								)
								Text(note.episode, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
								Spacer(Modifier.weight(1f))
								Text(
									DateUtils.formatDateTime(ctx, note.createdAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
									fontSize = 10.sp,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
								Icon(
									painterResource(R.drawable.ic_ap_delete), "Delete note",
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.padding(start = 8.dp).size(16.dp).clickable { actions.onDeleteNote(note) },
								)
							}
							Text(note.text, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun EpisodesHeader(
	count: Int,
	waiting: Boolean,
	ascending: Boolean,
	preset: String,
	onSort: () -> Unit,
	onPreset: (String) -> Unit,
) {
	// One row: count, hint, then the sort and Sub/Dub controls as compact buttons (Sub/Dub no longer owns a row).
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
			Text("$count Episodes", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
			if (waiting && count > 0) {
				Text(
					"Select below",
					fontSize = 11.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
					modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer)
						.padding(horizontal = 8.dp, vertical = 2.dp),
				)
			}
		}
		HeaderButton(onClick = onSort) {
			Icon(
				painterResource(R.drawable.ic_ap_sort),
				if (ascending) "Oldest first" else "Newest first",
				modifier = Modifier.size(20.dp).rotate(if (ascending) 0f else 180f),
				tint = MaterialTheme.colorScheme.onSurface,
			)
		}
		// Sub / Dub is a playback preset applied when an episode starts, not a filter of this list.
		var menu by remember { mutableStateOf(false) }
		Box {
			HeaderButton(onClick = { menu = true }) {
				Icon(painterResource(R.drawable.ic_ap_language), null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
				Text(
					if (preset == "dub") "Dub" else "Sub",
					fontSize = 13.sp,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(start = 6.dp),
				)
			}
			DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
				listOf("sub" to "Sub", "dub" to "Dub").forEach { (key, label) ->
					DropdownMenuItem(
						text = { Text(label) },
						onClick = { menu = false; onPreset(key) },
						trailingIcon = {
							if (preset == key) Icon(painterResource(R.drawable.ic_ap_check), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
						},
					)
				}
			}
		}
	}
}

@Composable
private fun HeaderButton(onClick: () -> Unit, content: @Composable () -> Unit) {
	Row(
		Modifier.height(40.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
			.clickable(onClick = onClick).padding(horizontal = 11.dp),
		verticalAlignment = Alignment.CenterVertically,
	) { content() }
}

@Composable
private fun EpisodeCard(
	chapter: MangaChapter,
	manga: Manga,
	imageLoader: ImageLoader,
	selected: Boolean,
	playing: Boolean,
	watched: Boolean,
	onClick: () -> Unit,
) {
	val ctx = LocalContext.current
	val thumb = AnimeEpisodeMeta.previews[chapter.id] ?: manga.coverUrl
	val request = remember(thumb, manga.source) {
		ImageRequest.Builder(ctx).data(thumb).crossfade(true).mangaSourceExtra(manga.source).build()
	}
	val label = AnimePlaybackSession.episodeLabel(chapter)
	val title = chapter.title?.takeIf { it.isNotBlank() && it != label } ?: manga.title
	val primary = MaterialTheme.colorScheme.primary
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(18.dp),
		color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer,
		border = if (selected) BorderStroke(1.dp, primary.copy(alpha = 0.7f)) else null,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(12.dp))) {
				AsyncImage(
					model = request,
					imageLoader = imageLoader,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
				)
				Box(
					Modifier.matchParentSize().background(if (selected) Color(0x99000000) else Color(0x44000000)),
					contentAlignment = Alignment.Center,
				) {
					Box(
						Modifier.size(28.dp).clip(CircleShape).background(if (selected) primary else Color(0x99000000)),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							painterResource(R.drawable.ic_ap_play), null,
							tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
							modifier = Modifier.size(18.dp),
						)
					}
				}
			}
			Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
				Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primary, maxLines = 1)
				Text(
					title,
					fontSize = 13.sp,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(top = 2.dp),
				)
				if (chapter.uploadDate > 0L) {
					Text(
						DateUtils.formatDateTime(ctx, chapter.uploadDate, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR),
						fontSize = 11.sp,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(top = 3.dp),
					)
				}
			}
			when {
				selected -> Box(Modifier.size(22.dp).clip(CircleShape).background(primary), contentAlignment = Alignment.Center) {
					Icon(
						painterResource(if (playing) R.drawable.ic_ap_play else R.drawable.ic_ap_pause), null,
						tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp),
					)
				}
				watched -> Icon(painterResource(R.drawable.ic_ap_check), "Watched", tint = primary.copy(alpha = 0.75f), modifier = Modifier.size(22.dp))
				else -> Box(Modifier.size(20.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
			}
		}
	}
}

/** Video-area overlay while no episode is picked: blurred cover, a pulsing icon and the prompt. */
@Composable
internal fun WaitingOverlay(manga: Manga, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
	val ctx = LocalContext.current
	val request = remember(manga.coverUrl, manga.source) {
		ImageRequest.Builder(ctx).data(manga.coverUrl).crossfade(true).mangaSourceExtra(manga.source).build()
	}
	val pulse = rememberInfiniteTransition(label = "waiting")
	val glow by pulse.animateFloat(
		initialValue = 0.55f, targetValue = 1f,
		animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow",
	)
	val ring by pulse.animateFloat(
		initialValue = 1f, targetValue = 1.6f,
		animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ring",
	)
	val accent = MaterialTheme.colorScheme.primary
	// clipToBounds: the blurred, enlarged cover would otherwise bleed over the status bar above the video
	Box(modifier.clipToBounds().background(Color.Black)) {
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize().scale(1.15f).blur(16.dp).alpha(0.7f),
		)
		Box(
			Modifier.fillMaxSize().background(
				Brush.verticalGradient(0f to Color(0x99000000), 0.5f to Color(0x66000000), 1f to Color(0xCC000000)),
			),
		)
		Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
			Box(contentAlignment = Alignment.Center) {
				Box(Modifier.size(56.dp).scale(ring).alpha((1.7f - ring).coerceIn(0f, 0.5f)).clip(CircleShape).background(accent))
				Box(
					Modifier.size(56.dp).clip(CircleShape).background(Color(0xB3000000)).border(1.dp, accent.copy(alpha = 0.6f), CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(painterResource(R.drawable.ic_ap_play), null, tint = accent, modifier = Modifier.size(30.dp).alpha(glow))
				}
			}
			Text(
				"Waiting for Episode to be picked",
				color = Color.White,
				fontSize = 15.sp,
				fontWeight = FontWeight.Medium,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 12.dp),
			)
			Text(
				"Choose an episode below to begin watching",
				color = Color(0xCCFFFFFF),
				fontSize = 12.sp,
				textAlign = TextAlign.Center,
				modifier = Modifier.padding(top = 2.dp),
			)
		}
	}
}

internal fun formatClock(ms: Long): String {
	val total = (ms / 1000).coerceAtLeast(0)
	val h = total / 3600
	val m = (total % 3600) / 60
	val s = total % 60
	return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
