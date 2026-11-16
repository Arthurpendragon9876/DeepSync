package org.koitharu.kotatsu.history.domain

import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import javax.inject.Inject

/**
 * Marks titles as unread (unwatched for anime): their reading history is removed, and every signed-in tracker
 * that has the title goes back to progress 0 and "planned", so the app and the trackers agree.
 */
class MarkAsUnreadUseCase @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	suspend operator fun invoke(manga: Collection<Manga>) = supervisorScope {
		manga.forEach { item ->
			launch {
				historyRepository.delete(item)
				scrobblers.filter { it.isEnabled }.forEach { scrobbler ->
					runCatchingCancellable { scrobbler.resetProgress(item) }
				}
			}
		}
	}
}
