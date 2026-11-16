package org.koitharu.kotatsu.explore.domain

import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** What Home asks the sources for: which kind of title, which sources, which genres, how adult. */
data class HomeQuery(
	val mode: HomeMode = HomeMode.MANGA,
	/** Empty means every enabled source of the right kind. */
	val sourceNames: Set<String> = emptySet(),
	val include: Set<String> = emptySet(),
	val exclude: Set<String> = emptySet(),
	val hideNsfw: Boolean = false,
)

class ExploreRepository @Inject constructor(
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	/** A random title; with [mode] set, only from the enabled sources of that kind (Manga, Novel or Anime). */
	suspend fun findRandomManga(tagsLimit: Int, mode: HomeMode? = null): Manga {
		val tagsBlacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, 0.4f)
		val tags = historyRepository.getPopularTags(tagsLimit).mapNotNull {
			if (it in tagsBlacklist) null else it.title
		}
		val sources = sourcesRepository.getEnabledSources().filter { mode == null || it.contentMode == mode }.shuffled()
		check(sources.isNotEmpty()) { "No sources available" }
		// Try every source in turn (a few rounds at most) rather than five random picks of possibly the same
		// broken one: anime and novel hosts are slower and fail more often than manga ones.
		for (i in 0 until minOf(sources.size * 2, 8)) {
			val list = getList(sources[i % sources.size], tags, tagsBlacklist)
				.filterNot { settings.isSuggestionsExcludeNsfw && it.isNsfw() }
			val manga = list.randomOrNull() ?: continue
			// Details take several requests for anime and novels; the details screen loads them again anyway,
			// so a title whose details time out is still a fine answer.
			val details = withTimeoutOrNull(RANDOM_DETAILS_TIMEOUT_MS) {
				runCatchingCancellable {
					mangaRepositoryFactory.create(manga.source).getDetails(manga)
				}.getOrNull()
			} ?: return manga
			if ((settings.isSuggestionsExcludeNsfw && details.isNsfw()) || details in tagsBlacklist) {
				continue
			}
			return details
		}
		throw NoSuchElementException("Nothing found to pick from. Check your connection and sources.")
	}

	suspend fun findRandomManga(source: MangaSource, tagsLimit: Int): Manga {
		val tagsBlacklist = TagsBlacklist(settings.suggestionsTagsBlacklist, 0.4f)
		val skipNsfw = settings.isSuggestionsExcludeNsfw && !source.isNsfw()
		val tags = historyRepository.getPopularTags(tagsLimit).mapNotNull {
			if (it in tagsBlacklist) null else it.title
		}
		for (i in 0..4) {
			val list = getList(source, tags, tagsBlacklist)
			val manga = list.randomOrNull() ?: continue
			val details = runCatchingCancellable {
				mangaRepositoryFactory.create(manga.source).getDetails(manga)
			}.getOrNull() ?: continue
			if ((skipNsfw && details.isNsfw()) || details in tagsBlacklist) {
				continue
			}
			return details
		}
		throw NoSuchElementException()
	}

	private suspend fun getList(
		source: MangaSource,
		tags: List<String>,
		blacklist: TagsBlacklist,
	): List<Manga> = runCatchingCancellable {
		val repository = mangaRepositoryFactory.create(source)
		val order = repository.sortOrders.random()
		val availableTags = repository.getFilterOptions().availableTags
		val tag = tags.firstNotNullOfOrNull { title ->
			availableTags.find { x -> x.title.almostEquals(title, 0.4f) }
		}
		val list = repository.getList(
			offset = 0,
			order = order,
			filter = MangaListFilter(tags = setOfNotNull(tag)),
		).asArrayList()
		if (settings.isSuggestionsExcludeNsfw) {
			list.removeAll { it.isNsfw() }
		}
		if (blacklist.isNotEmpty()) {
			list.removeAll { manga -> manga in blacklist }
		}
		list.shuffle()
		list
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(emptyList())

	/** What's hot right now: the shortest-window popularity sort a source offers, else all-time popularity. */
	suspend fun getTrending(limit: Int, sourcesLimit: Int = 4, query: HomeQuery = HomeQuery()): List<Manga> =
		getSorted(TRENDING_ORDERS, limit, sourcesLimit, query)

	/** All-time popular titles. */
	suspend fun getPopular(limit: Int, sourcesLimit: Int = 4, query: HomeQuery = HomeQuery()): List<Manga> =
		getSorted(listOf(SortOrder.POPULARITY, SortOrder.RATING), limit, sourcesLimit, query)

	/** Latest chapter releases. */
	suspend fun getRecentlyReleased(limit: Int, sourcesLimit: Int = 4, query: HomeQuery = HomeQuery()): List<Manga> =
		getSorted(listOf(SortOrder.UPDATED, SortOrder.NEWEST), limit, sourcesLimit, query)

	/** The enabled sources of one kind, for the Home source picker (which shows their real titles and icons). */
	suspend fun getEnabledSources(mode: HomeMode): List<MangaSource> =
		sourcesRepository.getEnabledSources().filter { it.contentMode == mode }

	/** Names of the enabled sources of one kind. */
	suspend fun getEnabledSourceNames(mode: HomeMode): List<String> =
		sourcesRepository.getEnabledSources().filter { it.contentMode == mode }.map { it.name }.sorted()

	/**
	 * Lists from a few of the enabled sources using the first of [preferred] each source supports,
	 * interleaved so one source can't dominate the row. Sources that fail or are slow are skipped;
	 * the result is empty only if every one of them failed.
	 *
	 * [query] narrows it to one kind of title, the sources the user picked and their genre and NSFW filters.
	 * Excluded genres are removed from what the source returns. Included genres are asked of the source when it
	 * has a matching tag, and checked on the result when the source reports tags.
	 */
	suspend fun getSorted(
		preferred: List<SortOrder>,
		limit: Int,
		sourcesLimit: Int = 4,
		query: HomeQuery = HomeQuery(),
	): List<Manga> = coroutineScope {
		val enabled = sourcesRepository.getEnabledSources().filter { it.contentMode == query.mode }
		val picked = if (query.sourceNames.isEmpty()) emptyList() else enabled.filter { it.name in query.sourceNames }
		// A saved pick that matches nothing enabled (source removed, renamed, picked in another mode) used
		// to leave every Home row empty. Treat it as "all sources" instead.
		val sources = if (picked.isEmpty()) enabled.shuffled().take(sourcesLimit) else picked.take(MAX_PICKED_SOURCES)
		if (sources.isEmpty()) return@coroutineScope emptyList()
		val skipNsfw = settings.isNsfwContentDisabled || query.hideNsfw
		val perSource = sources.map { source ->
			async {
				withTimeoutOrNull(LIST_TIMEOUT_MS) {
					runCatchingCancellable {
						val repository = mangaRepositoryFactory.create(source)
						val order = preferred.firstOrNull { it in repository.sortOrders }
							?: repository.defaultSortOrder
						val tag = if (query.include.isEmpty()) {
							null
						} else {
							runCatchingCancellable { repository.getFilterOptions().availableTags }.getOrNull()
								?.let { tags -> query.include.firstNotNullOfOrNull { g -> tags.find { it.title.equals(g, true) } } }
						}
						repository.getList(offset = 0, order = order, filter = MangaListFilter(tags = setOfNotNull(tag)))
							.filterNot { skipNsfw && it.isNsfw() }
							.filter { it.matchesGenres(query) }
					}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
				}.orEmpty()
			}
		}.awaitAll()
		val merged = ArrayList<Manga>(limit)
		var index = 0
		while (merged.size < limit && perSource.any { index < it.size }) {
			perSource.forEach { list -> list.getOrNull(index)?.let(merged::add) }
			index++
		}
		merged.distinctBy { it.id }.take(limit)
	}

	/** Titles only count as excluded or missing a genre when the source actually reported tags. */
	private fun Manga.matchesGenres(query: HomeQuery): Boolean {
		val names = tags.map { it.title }
		if (names.isEmpty()) return true
		if (query.exclude.any { g -> names.any { it.equals(g, true) } }) return false
		return query.include.all { g -> names.any { it.equals(g, true) } }
	}

	/**
	 * Looks a title up in the sources under every name it has (romaji, English, synonyms) and returns the
	 * closest match, or null when nothing is close enough. Used when a tracker entry or an AniList
	 * trending title is tapped, since those only know the title, not a source page.
	 */
	/** Searches one source for [query]; empty on failure or timeout. Used when the user picks the source themselves. */
	suspend fun searchInSource(source: MangaSource, query: String): List<Manga> =
		withTimeoutOrNull(SEARCH_TIMEOUT_MS * 2) {
			runCatchingCancellable {
				val repository = mangaRepositoryFactory.create(source)
				val order = if (SortOrder.RELEVANCE in repository.sortOrders) SortOrder.RELEVANCE else repository.defaultSortOrder
				repository.getList(offset = 0, order = order, filter = MangaListFilter(query = query))
			}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
		}.orEmpty()

	suspend fun findBestSourceMatch(names: List<String>, mode: HomeMode): Manga? = coroutineScope {
		val queries = names.map { it.trim() }.filter { it.isNotEmpty() }
			.distinctBy { TitleMatcher.normalize(it) }.take(MAX_SEARCH_NAMES)
		if (queries.isEmpty()) return@coroutineScope null
		val sources = sourcesRepository.getEnabledSources().filter { it.contentMode == mode }.take(MAX_SEARCH_SOURCES)
		val found = sources.flatMap { source ->
			queries.map { q ->
				async {
					withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
						runCatchingCancellable {
							val repository = mangaRepositoryFactory.create(source)
							val order = if (SortOrder.RELEVANCE in repository.sortOrders) {
								SortOrder.RELEVANCE
							} else {
								repository.defaultSortOrder
							}
							repository.getList(offset = 0, order = order, filter = MangaListFilter(query = q))
						}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
					}.orEmpty()
				}
			}
		}.awaitAll().flatten()
		found.map { it to TitleMatcher.bestScore(queries, listOf(it.title) + it.altTitles) }
			.filter { it.second >= MATCH_THRESHOLD }
			.maxByOrNull { it.second }
			?.first
	}

	private companion object {

		const val RANDOM_DETAILS_TIMEOUT_MS = 8000L
		private const val LIST_TIMEOUT_MS = 12_000L
		const val SEARCH_TIMEOUT_MS = 9_000L
		const val MAX_PICKED_SOURCES = 6
		const val MAX_SEARCH_SOURCES = 6
		const val MAX_SEARCH_NAMES = 5
		const val MATCH_THRESHOLD = 0.85f

		val TRENDING_ORDERS = listOf(
			SortOrder.POPULARITY_TODAY,
			SortOrder.POPULARITY_WEEK,
			SortOrder.POPULARITY_HOUR,
			SortOrder.POPULARITY_MONTH,
			SortOrder.POPULARITY,
		)
	}
}
