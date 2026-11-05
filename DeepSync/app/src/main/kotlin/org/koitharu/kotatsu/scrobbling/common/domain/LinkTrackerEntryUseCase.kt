package org.koitharu.kotatsu.scrobbling.common.domain

import dagger.Reusable
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Opening a title from a tracker's own library lands on a source's copy of it. That copy has to be
 * tied to the tracker entry right away, and local progress brought up to the tracker's, otherwise
 * the two drift apart: the next chapter or episode would be tracked as if nothing had been watched.
 */
@Reusable
class LinkTrackerEntryUseCase @Inject constructor(
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	/** Links [manga] to [entry] and moves local progress up to the tracker's. Never throws. */
	suspend operator fun invoke(manga: Manga, entry: TrackerReadingEntry) {
		val scrobbler = scrobblers.firstOrNull { it.scrobblerService == entry.service && it.isEnabled } ?: return
		runCatchingCancellable {
			// An existing tracker entry is adopted as it is: its status, score and progress are not overwritten.
			scrobbler.linkManga(manga.id, entry.targetId, ScrobblingStatus.READING)
		}.onFailure { it.printStackTraceDebug() }
		if (entry.chapter <= 0) return
		runCatchingCancellable {
			val details = if (manga.chapters.isNullOrEmpty()) {
				mangaRepositoryFactory.create(manga.source).getDetails(manga)
			} else {
				manga
			}
			// With several scanlator / language groups the largest one is the full run
			val branch = details.getBranches().maxByOrNull { it.value }?.key
			val chapters = details.getChapters(branch)
			val index = findRemoteProgressIndex(chapters, entry.chapter)
			if (index >= 0) {
				historyRepository.advanceFromTracking(details, chapters, index)
			}
		}.onFailure { it.printStackTraceDebug() }
	}
}
