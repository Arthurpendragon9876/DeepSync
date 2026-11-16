package org.koitharu.kotatsu.stats.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.metadata.DISCOVER_GENRES
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/** How strongly the user leans to one genre. [score] is relative, 0..1 against their strongest genre. */
data class GenreScore(
	val genre: String,
	val score: Float,
	/** Library / history titles carrying the genre. */
	val titles: Int,
	/** Reading / watching time spent on those titles, in minutes. */
	val minutes: Long,
)

/**
 * The genres the user actually spends time on, from their library, history and reading stats. It powers
 * the "Genres you like" bars on the stats page and the picks on Explore's metadata tab.
 *
 * A title counts once for being in the library or history (a favourite is a stronger signal than a
 * glance, so it counts double), plus a bonus for the time spent on it, capped so one binge cannot bury
 * everything else.
 */
@Singleton
class GenreAffinityRepository @Inject constructor(
	private val db: MangaDatabase,
	private val favourites: FavouritesRepository,
	private val history: HistoryRepository,
) {

	/** The top [limit] genres for [mode] (all modes when null), strongest first. */
	suspend fun top(mode: HomeMode?, limit: Int = 8): List<GenreScore> {
		val library = runCatchingCancellable { favourites.getAllManga() }.getOrDefault(emptyList())
		val recent = runCatchingCancellable { history.getList(0, HISTORY_LIMIT) }.getOrDefault(emptyList())
		val minutesById = runCatchingCancellable {
			db.getStatsDao().getDurationStats(0L, emptySet()).entries.associate { it.key.id to it.value / 60_000L }
		}.getOrDefault(emptyMap())

		val weight = HashMap<String, Float>()
		val titles = HashMap<String, Int>()
		val minutes = HashMap<String, Long>()
		val favouriteIds = library.mapTo(HashSet()) { it.id }
		for (manga in (library + recent).distinctBy { it.id }) {
			if (mode != null && manga.source.contentMode != mode) continue
			val mins = minutesById[manga.id] ?: 0L
			val w = (if (manga.id in favouriteIds) 2f else 1f) + (mins / 30f).coerceAtMost(3f)
			val genres = manga.tags.mapNotNull { canonical(it.title) }.toSet()
			for (g in genres) {
				weight[g] = (weight[g] ?: 0f) + w
				titles[g] = (titles[g] ?: 0) + 1
				minutes[g] = (minutes[g] ?: 0L) + mins
			}
		}
		val best = weight.values.maxOrNull() ?: return emptyList()
		return weight.entries
			.sortedByDescending { it.value }
			.take(limit)
			.map { GenreScore(it.key, it.value / best, titles[it.key] ?: 0, minutes[it.key] ?: 0L) }
	}

	private companion object {
		const val HISTORY_LIMIT = 500

		private val byKey = DISCOVER_GENRES.associateBy { it.key() }

		/** Maps a source's tag onto the shared genre vocabulary, or null when it is not one of them. */
		fun canonical(tag: String): String? = byKey[tag.key()] ?: ALIASES[tag.key()]

		private fun String.key() = lowercase().filter { it.isLetterOrDigit() }

		private val ALIASES = mapOf(
			"scifi" to "Sci-Fi",
			"sciencefiction" to "Sci-Fi",
			"sport" to "Sports",
			"magicalgirl" to "Mahou Shoujo",
			"magicalgirls" to "Mahou Shoujo",
			"sliceoflife" to "Slice of Life",
			"psychologicalthriller" to "Psychological",
			"supernaturalpower" to "Supernatural",
		)
	}
}
