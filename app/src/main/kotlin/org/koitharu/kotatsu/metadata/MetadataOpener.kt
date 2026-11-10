package org.koitharu.kotatsu.metadata

import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.explore.domain.ExploreRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher
import javax.inject.Inject
import javax.inject.Singleton

/** What opening a tracker title turned into. */
sealed interface MetaOpenResult {

	data class Open(val manga: Manga) : MetaOpenResult

	/** No source matched by name: the user picks the source and searches inside it. */
	data class ChooseSource(val query: String, val ref: MetaRef) : MetaOpenResult
}

/**
 * Turns a tracker title into something the app can open: the source title the user paired with it before,
 * else the best name match among the enabled sources (remembered for next time), else a source chooser.
 */
@Singleton
class MetadataOpener @Inject constructor(
	private val exploreRepository: ExploreRepository,
	private val dataRepository: MangaDataRepository,
	private val links: MetadataLinks,
	private val favouritesRepository: org.koitharu.kotatsu.favourites.domain.FavouritesRepository,
	private val historyRepository: org.koitharu.kotatsu.history.data.HistoryRepository,
) {

	/**
	 * A title the user already has - in favourites or history - under any of [names]. Checked before the
	 * sources, so something saved from a source opens from the library instead of being searched for again.
	 */
	suspend fun findInLibrary(names: List<String>, mode: org.koitharu.kotatsu.home.ui.model.HomeMode): Manga? {
		val wanted = names.filter { it.isNotBlank() }.distinct()
		if (wanted.isEmpty()) return null
		val candidates = runCatching {
			favouritesRepository.getAllManga() + historyRepository.getList(0, LIBRARY_SCAN_LIMIT)
		}.getOrDefault(emptyList())
		return candidates.asSequence()
			.distinctBy { it.id }
			.filter { it.source.contentMode == mode }
			.map { it to TitleMatcher.bestScore(wanted, listOf(it.title) + it.altTitles) }
			.filter { it.second >= LIBRARY_MATCH_SCORE }
			.maxByOrNull { it.second }
			?.first
	}


	/** A manga already in the library, or null when it is not stored any more. */
	suspend fun findStored(mangaId: Long): Manga? = dataRepository.findMangaById(mangaId, withChapters = false)

	suspend fun resolve(ref: MetaRef, names: List<String>, title: String): MetaOpenResult {
		val key = MetadataLinks.key(ref)
		links.find(key)?.let { id ->
			dataRepository.findMangaById(id, withChapters = false)?.let { return MetaOpenResult.Open(it) }
		}
		val searchNames = names.ifEmpty { listOf(title) }
		findInLibrary(searchNames, ref.mode)?.let { owned ->
			links.set(key, owned.id)
			return MetaOpenResult.Open(owned)
		}
		val found = exploreRepository.findBestSourceMatch(searchNames, ref.mode)
		return if (found != null) {
			runCatching { dataRepository.storeManga(found, replaceExisting = false) }
			links.set(key, found.id)
			MetaOpenResult.Open(found)
		} else {
			MetaOpenResult.ChooseSource(title, ref)
		}
	}

	private companion object {
		const val LIBRARY_SCAN_LIMIT = 2000
		const val LIBRARY_MATCH_SCORE = 0.85f
	}
}
