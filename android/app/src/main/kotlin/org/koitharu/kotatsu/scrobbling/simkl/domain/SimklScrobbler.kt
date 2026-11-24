package org.koitharu.kotatsu.scrobbling.simkl.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.simkl.data.SimklRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val RATING_MAX = 10f

@Singleton
class SimklScrobbler @Inject constructor(
	repository: SimklRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: MangaRepository.Factory,
) : Scrobbler(db, ScrobblerService.SIMKL, repository, mangaRepositoryFactory, RATING_MAX) {

	init {
		// Stored in the app's reading words, like MAL's; the repository converts to Simkl's own
		statuses[ScrobblingStatus.PLANNED] = "plan_to_read"
		statuses[ScrobblingStatus.READING] = "reading"
		statuses[ScrobblingStatus.RE_READING] = "reading"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "on_hold"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}
}
