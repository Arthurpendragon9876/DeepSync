package org.koitharu.kotatsu.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/** Side table for per-episode data that [MangaChapter] has no field for (the thumbnail). */
object AnimeEpisodeMeta {
	val previews = ConcurrentHashMap<Long, String>()
}

/** A resolved, playable stream plus the HTTP client that must be used to fetch it. */
class AnimeStream(
	val video: Video,
	val client: OkHttpClient?,
) {
	/** Filled in lazily by [org.koitharu.kotatsu.anime.player.AnimeStreamProbe]. */
	@Volatile
	var probe: org.koitharu.kotatsu.anime.player.StreamProbe? = null
}

@OptIn(InternalParsersApi::class)
class AnimeMangaRepository(
	override val source: AnimeMangaSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

	private val anime = source.catalogueSource

	private class PaginationState {
		var currentPage = 1
		var lastOffset = -1
	}

	private val paginationStates = ConcurrentHashMap<String, PaginationState>()

	override val sortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (source.supportsLatest) add(SortOrder.UPDATED)
		add(SortOrder.RELEVANCE)
	}.let { EnumSet.copyOf(it) }

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
		withContext(Dispatchers.IO) {
			val key = "$order|${filter?.query}"
			val state = paginationStates.getOrPut(key) { PaginationState() }
			val page = synchronized(state) {
				if (offset == 0) {
					state.currentPage = 1
					state.lastOffset = 0
				} else if (offset > state.lastOffset) {
					state.lastOffset = offset
					state.currentPage += 1
				}
				state.currentPage
			}
			val query = filter?.query?.trim().orEmpty()
			val result = when {
				query.isNotEmpty() -> anime.getSearchAnime(page, query, AnimeFilterList())
				order == SortOrder.UPDATED && source.supportsLatest -> anime.getLatestUpdates(page)
				else -> anime.getPopularAnime(page)
			}
			result.animes.map { it.toManga() }
		}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val seed = manga.toSAnime()
		val details = runCatching { anime.getAnimeDetails(seed) }.getOrDefault(seed)
		if (details.title.isBlank()) details.title = manga.title
		if (details.thumbnail_url.isNullOrBlank()) details.thumbnail_url = manga.coverUrl
		details.url = manga.url
		// Extensions list newest first; DeepSync expects oldest first.
		val episodes = anime.getEpisodeList(details).asReversed()
		details.toManga(
			chapters = episodes.mapIndexed { index, ep -> ep.toChapter(index + 1f) },
			publicUrl = (anime as? AnimeHttpSource)?.runCatching { getAnimeUrl(details) }?.getOrNull().orEmpty(),
		)
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	/** Anime has no pages; playback goes through [getStreams]. */
	override suspend fun getPagesImpl(manga: Manga, chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getChapterUrl(manga: Manga, chapter: MangaChapter): String? =
		(anime as? AnimeHttpSource)?.runCatching { getEpisodeUrl(chapter.toSEpisode()) }?.getOrNull()

	/** Resolves every playable stream of [chapter]. Handles both legacy and hoster-based sources. */
	suspend fun getStreams(chapter: MangaChapter): List<AnimeStream> = withContext(Dispatchers.IO) {
		val episode = chapter.toSEpisode()
		val http = anime as? AnimeHttpSource
		val legacy = runCatching { anime.getVideoList(episode) }.getOrDefault(emptyList())
		val raw = legacy.ifEmpty {
			anime.getHosterList(episode).flatMap { hoster ->
				runCatching { anime.getVideoList(hoster) }.getOrDefault(emptyList())
			}
		}
		raw.mapNotNull { v ->
			val resolved = if (v.videoUrl.isBlank() || v.videoUrl == "null") {
				runCatching { http?.resolveVideo(v) }.getOrNull()
			} else {
				v
			}
			resolved?.takeIf { it.videoUrl.isNotBlank() && it.videoUrl != "null" }
		}.map { AnimeStream(it, http?.client) }
	}

	// --- mapping --------------------------------------------------------------------------------

	private fun SAnime.toManga(
		chapters: List<MangaChapter> = emptyList(),
		publicUrl: String = "",
	): Manga {
		val genres = getGenres().orEmpty()
		val adult = genres.any { it.trim().lowercase() in ADULT_GENRES }
		val cover = thumbnail_url
		return Manga(
			id = stableId("manga", url),
			title = title,
			altTitles = emptySet(),
			url = url,
			publicUrl = publicUrl,
			rating = RATING_UNKNOWN,
			contentRating = if (adult) ContentRating.ADULT else null,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = genres.mapTo(LinkedHashSet()) { MangaTag(key = it.lowercase(), title = it, source = source) },
			state = when (status) {
				SAnime.ONGOING -> MangaState.ONGOING
				SAnime.COMPLETED, SAnime.PUBLISHING_FINISHED -> MangaState.FINISHED
				SAnime.CANCELLED -> MangaState.ABANDONED
				SAnime.ON_HIATUS -> MangaState.PAUSED
				SAnime.LICENSED -> MangaState.RESTRICTED
				else -> null
			},
			authors = buildSet {
				author?.takeIf { it.isNotBlank() }?.let(::add)
				artist?.takeIf { it.isNotBlank() }?.let(::add)
			},
			description = description,
			chapters = chapters.takeIf { it.isNotEmpty() },
			source = source,
		)
	}

	private fun SEpisode.toChapter(fallbackNumber: Float): MangaChapter {
		val scan = scanlator?.trim()?.takeIf { it.isNotEmpty() }
		val chapterId = stableId("episode", url)
		preview_url?.takeIf { it.isNotBlank() }?.let { AnimeEpisodeMeta.previews[chapterId] = it }
		return MangaChapter(
			id = chapterId,
			title = name.takeIf { it.isNotBlank() },
			number = if (episode_number >= 0f) episode_number else fallbackNumber,
			volume = 0,
			url = url,
			scanlator = scan,
			uploadDate = date_upload,
			branch = scan,
			source = source,
		)
	}

	private fun Manga.toSAnime(): SAnime = SAnime.create().also {
		it.url = url
		it.title = title
		it.thumbnail_url = coverUrl
		it.description = description
		it.author = authors.firstOrNull()
		it.genre = tags.joinToString(", ") { t -> t.title }
		it.initialized = description != null || chapters != null
	}

	private fun MangaChapter.toSEpisode(): SEpisode = SEpisode.create().also {
		it.url = url
		it.name = title ?: "Episode $number"
		it.episode_number = number
		it.date_upload = uploadDate
		it.scanlator = scanlator ?: branch
	}

	private fun stableId(type: String, value: String): Long =
		"${source.name}|$type|$value".hashCode().toLong() and Long.MAX_VALUE

	private companion object {
		val ADULT_GENRES = setOf("adult", "hentai", "18+", "nsfw", "mature", "ecchi")
	}
}
