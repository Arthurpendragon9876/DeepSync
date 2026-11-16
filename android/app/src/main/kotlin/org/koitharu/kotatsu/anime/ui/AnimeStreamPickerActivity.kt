package org.koitharu.kotatsu.anime.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeMangaRepository
import org.koitharu.kotatsu.anime.AnimeStream
import org.koitharu.kotatsu.anime.player.AnimePlaybackSession
import org.koitharu.kotatsu.anime.player.AnimePlayerLauncher
import org.koitharu.kotatsu.anime.player.AnimePlayerPrefs
import org.koitharu.kotatsu.anime.player.AnimeStreamAutoPicker
import org.koitharu.kotatsu.anime.player.AnimeStreamProbe
import org.koitharu.kotatsu.anime.player.StreamKind
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.mihon.compat.KotoInjektBridge
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

/**
 * Stream / server chooser shown in a bottom sheet BEFORE playback, so the player itself stays
 * uncluttered. Resolves the episode's streams through the source, then hands the pick to
 * [AnimePlayerLauncher]. A ticked "Remember server" auto-picks the same server next time.
 */
@AndroidEntryPoint
class AnimeStreamPickerActivity : AppCompatActivity() {

	@Inject lateinit var repositoryFactory: MangaRepository.Factory
	@Inject lateinit var history: HistoryRepository
	@Inject lateinit var injektBridge: dagger.Lazy<KotoInjektBridge>

	private lateinit var manga: Manga
	private var chapters: List<MangaChapter> = emptyList()
	private var index = 0

	private var loading by mutableStateOf(true)
	private var error by mutableStateOf<String?>(null)
	private var streams by mutableStateOf<List<AnimeStream>>(emptyList())
	private var chapterLabel by mutableStateOf("")
	private var chapterSubtitle by mutableStateOf("")
	private var rememberServer by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		val parcel = IntentCompat.getParcelableExtra(intent, AppRouter.KEY_MANGA, ParcelableManga::class.java)
		if (parcel == null) {
			finish()
			return
		}
		manga = parcel.manga
		rememberServer = AnimePlaybackSession.rememberedServer(this, manga) != null
		setContent {
			DropSauceTheme {
				StreamSheet()
			}
		}
		load(intent.getLongExtra(EXTRA_CHAPTER_ID, -1L))
	}

	private fun load(requestedChapterId: Long) {
		loading = true
		error = null
		lifecycleScope.launch {
			runCatching {
				injektBridge.get().initialize()
				val repo = repositoryFactory.create(manga.source) as? AnimeMangaRepository
					?: error("Not an anime source")
				if (chapters.isEmpty()) {
					chapters = (manga.chapters ?: repo.getDetails(manga).chapters).orEmpty()
					check(chapters.isNotEmpty()) { "This title has no episodes" }
					val target = requestedChapterId.takeIf { it != -1L }
						?: runCatching { history.getOne(manga)?.chapterId }.getOrNull()
					index = chapters.indexOfFirst { it.id == target }.takeIf { it >= 0 } ?: 0
				}
				val chapter = chapters[index]
				chapterLabel = AnimePlaybackSession.episodeLabel(chapter)
				chapterSubtitle = chapter.title?.takeIf { it.isNotBlank() && it != chapterLabel } ?: manga.title
				repo.getStreams(chapter)
			}.onSuccess { list ->
				streams = list
				loading = false
				if (list.isEmpty()) {
					error = "No playable streams for this episode"
					return@onSuccess
				}
				val saved = AnimePlaybackSession.rememberedServer(this@AnimeStreamPickerActivity, manga)
				// Auto-pick is the default (Settings > Player); a remembered server always wins.
				if (saved != null || list.size == 1 || AnimePlayerPrefs.isAutoPick(this@AnimeStreamPickerActivity)) {
					autoPick(list, saved)
				}
			}.onFailure {
				loading = false
				error = it.message ?: it::class.java.simpleName
			}
		}
	}

	/** Plays the best server for the quality setting, moving on to the next when one is dead; the list opens if none works. */
	private fun autoPick(list: List<AnimeStream>, preferTitle: String?) {
		val order = AnimeStreamAutoPicker.order(this, list, preferTitle)
		loading = true
		error = null
		lifecycleScope.launch {
			for (i in order) {
				val stream = list[i]
				val probe = stream.probe ?: AnimeStreamProbe.probe(stream).also { stream.probe = it }
				if (probe.kind == StreamKind.ERROR) continue
				val snapshot = manga.copy(chapters = chapters)
				AnimePlayerLauncher.play(this@AnimeStreamPickerActivity, snapshot, chapters, index, list, i)
				finish()
				return@launch
			}
			loading = false
			android.widget.Toast.makeText(
				this@AnimeStreamPickerActivity,
				"No server could be played automatically: pick one",
				android.widget.Toast.LENGTH_LONG,
			).show()
		}
	}

	private fun pick(which: Int) {
		val stream = streams.getOrNull(which) ?: return
		AnimePlaybackSession.setRememberedServer(this, manga, if (rememberServer) stream.video.videoTitle else null)
		loading = true
		error = null
		lifecycleScope.launch {
			val probe = stream.probe ?: AnimeStreamProbe.probe(stream).also { stream.probe = it }
			if (probe.kind == StreamKind.ERROR) {
				loading = false
				error = (probe.note ?: "This stream can't be played") + "\nPick another server."
				// A remembered server that fails must not auto-pick again next time.
				AnimePlaybackSession.setRememberedServer(this@AnimeStreamPickerActivity, manga, null)
				return@launch
			}
			val snapshot = manga.copy(chapters = chapters)
			AnimePlayerLauncher.play(this@AnimeStreamPickerActivity, snapshot, chapters, index, streams, which)
			finish()
		}
	}

	@OptIn(ExperimentalMaterial3Api::class)
	@Composable
	private fun StreamSheet() {
		val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
		ModalBottomSheet(
			onDismissRequest = { finish() },
			sheetState = state,
			containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
		) {
			Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(
						Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
							.background(MaterialTheme.colorScheme.secondaryContainer),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							painterResource(R.drawable.ic_ap_play),
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSecondaryContainer,
						)
					}
					Column(Modifier.weight(1f).padding(start = 14.dp)) {
						Text(chapterLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
						Text(
							chapterSubtitle,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
						)
					}
				}
				Spacer(Modifier.height(16.dp))
				when {
					loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
						CircularProgressIndicator()
					}
					error != null -> Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
						Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
						TextButton(onClick = { load(-1L) }) { Text("Retry") }
					}
					else -> StreamList()
				}
			}
		}
	}

	@Composable
	private fun StreamList() {
		Column(
			Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
				.background(MaterialTheme.colorScheme.surfaceContainer),
		) {
			Row(
				Modifier.fillMaxWidth().clickable { rememberServer = !rememberServer }.padding(horizontal = 16.dp, vertical = 14.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Column(Modifier.weight(1f)) {
					Text("Remember Server", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
					Text(
						"Auto-select this server quality for this anime",
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						fontSize = 13.sp,
					)
				}
				Checkbox(checked = rememberServer, onCheckedChange = { rememberServer = it })
			}
			HorizontalDivider(Modifier.padding(horizontal = 16.dp))
			LazyColumn(Modifier.height((minOf(streams.size, 6) * 76 + 8).dp)) {
				itemsIndexed(streams) { i, s ->
					val video = s.video
					Row(
						Modifier.fillMaxWidth().clickable { pick(i) }.padding(horizontal = 16.dp, vertical = 14.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Column(Modifier.weight(1f)) {
							Text(
								video.videoTitle.ifBlank { "Stream ${i + 1}" },
								fontWeight = FontWeight.SemiBold,
								fontSize = 16.sp,
							)
							val extras = buildList {
								if (video.subtitleTracks.isNotEmpty()) add("CC ${video.subtitleTracks.size}")
								if (video.audioTracks.isNotEmpty()) add("Audio ${video.audioTracks.size}")
							}
							if (extras.isNotEmpty()) {
								Text(
									extras.joinToString("  ·  "),
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									fontSize = 13.sp,
								)
							}
						}
						Icon(
							painterResource(R.drawable.ic_ap_next),
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					HorizontalDivider(Modifier.padding(horizontal = 16.dp))
				}
			}
		}
	}

	companion object {
		const val EXTRA_CHAPTER_ID = "chapter_id"
	}
}
