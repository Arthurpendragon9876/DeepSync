package org.koitharu.kotatsu.history.domain

import dagger.Reusable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koitharu.kotatsu.core.model.isAnimeSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import javax.inject.Inject

/**
 * Marks titles as completed (watched for anime): history jumps to the end of the last chapter, and every
 * signed-in tracker that has the title goes to "completed" with full progress, so the app and the trackers
 * agree (the counterpart of [MarkAsUnreadUseCase]).
 */
@Reusable
class MarkAsReadUseCase @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	suspend operator fun invoke(manga: Manga) {
		val repo = mangaRepositoryFactory.create(manga.source)
		val details = if (manga.chapters.isNullOrEmpty()) {
			repo.getDetails(manga)
		} else {
			manga
		}
		val lastChapter = details.chapters?.lastOrNull() ?: return
		// Episodes and novel chapters have no page list worth fetching (it can mean resolving a video or a
		// whole text); for them the position inside the last chapter is simply its start.
		val source = manga.source
		val page = if (source.isAnimeSource || source.isNovelSource) {
			0
		} else {
			runCatchingCancellable { maxOf(0, repo.getPages(details, lastChapter).lastIndex) }.getOrDefault(0)
		}
		historyRepository.addOrUpdate(
			manga = details,
			chapterId = lastChapter.id,
			page = page,
			scroll = 0,
			percent = 1f,
			force = true,
			// Completion is pushed to the trackers below; the usual per-chapter sync would only race it
			// (and ask "track this?" for every title in the selection)
			trackers = false,
		)
		scrobblers.filter { it.isEnabled }.forEach { scrobbler ->
			runCatchingCancellable { scrobbler.markCompleted(details, lastChapter.id) }
		}
	}

	suspend operator fun invoke(manga: Collection<Manga>) {
		when (manga.size) {
			0 -> Unit
			1 -> runCatchingCancellable { invoke(manga.first()) }
			// One title failing (offline source, removed entry) must not take the others down with it
			else -> supervisorScope {
				manga.map {
					launch {
						runCatchingCancellable { invoke(it) }
					}
				}.joinAll()
			}
		}
	}
}
