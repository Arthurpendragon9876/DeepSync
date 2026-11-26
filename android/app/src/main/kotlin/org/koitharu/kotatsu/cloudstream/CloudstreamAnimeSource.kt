@file:OptIn(Prerelease::class)

package org.koitharu.kotatsu.cloudstream

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.Headers
import rx.Observable
import java.util.concurrent.ConcurrentHashMap

/**
 * One provider of a Cloudstream plugin, seen as an Aniyomi anime source.
 *
 * That is what lets the whole app treat it like any other anime source: [org.koitharu.kotatsu.anime.AnimeExtensionManager]
 * wraps it in an `AnimeMangaSource`, and Explore, Home, favourites, history and the player need nothing new.
 *
 * - Without a search query the provider's home sections are listed (all of them, or the one picked in the
 *   "Section" filter). With one, its search is used.
 * - Entries are Cloudstream result URLs; episodes carry Cloudstream's episode `data` as their URL, which is what
 *   `loadLinks` is called with when an episode is played.
 */
class CloudstreamAnimeSource(
	val api: MainAPI,
	val pkg: String,
	@Suppress("unused") private val runtime: CloudstreamRuntime,
) : AnimeCatalogueSource {

	override val id: Long = "cloudstream|$pkg|${api.name}".hashCode().toLong() and Long.MAX_VALUE

	override val name: String
		get() = api.name

	override val lang: String
		get() = api.lang.takeIf { it.isNotBlank() } ?: "all"

	override val supportsLatest: Boolean
		get() = false

	/** The provider's site, for the in-app browser and the favicon. */
	val baseUrl: String
		get() = api.mainUrl

	private val loadCache = ConcurrentHashMap<String, Pair<Long, LoadResponse>>()
	private val terminalPages = ConcurrentHashMap<String, Int>()

	// --- listing ---------------------------------------------------------------------------------

	override suspend fun getPopularAnime(page: Int): AnimesPage = loadHome(page, section = null)

	override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
		val text = query.trim()
		if (text.isEmpty()) {
			// "Section" filter: Select state 0 is "All sections"
			val section = (filters.firstOrNull { it is SectionFilter } as? SectionFilter)?.state?.minus(1)
			return loadHome(page, section?.takeIf { it >= 0 })
		}
		val key = text.lowercase()
		if (page > (terminalPages["s|$key"] ?: Int.MAX_VALUE)) return AnimesPage(emptyList(), false)
		val result = CloudstreamRequestScope.call(api, REQUEST_TIMEOUT_MS) { api.search(text, page) }
			?: error("${api.name}: search returned nothing")
		if (!result.hasNext) terminalPages["s|$key"] = page
		return AnimesPage(result.items.map { it.toSAnime() }, result.hasNext)
	}

	override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

	override fun getFilterList(): AnimeFilterList {
		val sections = api.mainPage.map { it.name }.filter { it.isNotBlank() }
		return if (sections.size > 1) AnimeFilterList(SectionFilter(sections)) else AnimeFilterList()
	}

	/** Which home section to list; the first entry is "All sections". */
	private class SectionFilter(sections: List<String>) :
		AnimeFilter.Select<String>("Section", (listOf("All sections") + sections).toTypedArray())

	private suspend fun loadHome(page: Int, section: Int?): AnimesPage {
		val sections = api.mainPage
		if (sections.isEmpty()) return AnimesPage(emptyList(), false)
		val requests = section?.let { listOfNotNull(sections.getOrNull(it)) } ?: sections
		val results: List<Result<HomeEntry?>> = if (api.sequentialMainPage || requests.size == 1) {
			requests.mapIndexed { index, data ->
				if (index > 0) delay(api.sequentialMainPageDelay)
				runCatching { loadSection(data, page) }
			}
		} else {
			coroutineScope {
				requests.map { data -> async { runCatching { loadSection(data, page) } } }.awaitAll()
			}
		}
		val entries = results.mapNotNull { it.getOrNull() }
		// One section failing is not worth losing the others; if all fail the error is the user's answer.
		if (entries.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
		val animes = entries.flatMap { it.items }.distinctBy { it.url }.map { it.toSAnime() }
		return AnimesPage(animes, entries.any { it.hasNext })
	}

	private class HomeEntry(val items: List<SearchResponse>, val hasNext: Boolean)

	private suspend fun loadSection(data: MainPageData, page: Int): HomeEntry? {
		val key = "h|${data.name}|${data.data}"
		if (page > (terminalPages[key] ?: Int.MAX_VALUE)) return null
		val request = MainPageRequest(data.name, data.data, data.horizontalImages)
		val response = CloudstreamRequestScope.call(api, REQUEST_TIMEOUT_MS) { api.getMainPage(page, request) }
			?: return null
		if (!response.hasNext) terminalPages[key] = page
		return HomeEntry(response.items.flatMap { it.list }, response.hasNext)
	}

	private fun SearchResponse.toSAnime(): SAnime = SAnime.create().also {
		it.url = url
		it.title = name
		it.thumbnail_url = posterUrl
		it.initialized = false
	}

	// --- details and episodes --------------------------------------------------------------------

	override suspend fun getAnimeDetails(anime: SAnime): SAnime {
		val response = load(anime.url)
		return SAnime.create().also {
			it.url = anime.url
			it.title = response.name.ifBlank { anime.title }
			it.thumbnail_url = response.posterUrl ?: anime.thumbnail_url
			it.background_url = response.backgroundPosterUrl
			it.description = response.plot
			it.genre = response.tags?.joinToString(", ")
			it.status = when ((response as? EpisodeResponse)?.showStatus) {
				ShowStatus.Ongoing -> SAnime.ONGOING
				ShowStatus.Completed -> SAnime.COMPLETED
				null -> SAnime.UNKNOWN
			}
			it.initialized = true
		}
	}

	/** Newest first, as Aniyomi extensions return them; the repository turns the list around. */
	override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
		mapEpisodes(load(anime.url)).asReversed()

	private suspend fun load(url: String): LoadResponse {
		val cached = loadCache[url]
		if (cached != null && System.currentTimeMillis() - cached.first < LOAD_CACHE_TTL_MS) return cached.second
		if (url.isBlank() || url == "[]" || url == "about:blank") error("${api.name}: invalid address")
		val response = CloudstreamRequestScope.call(api, REQUEST_TIMEOUT_MS) { api.load(api.fixUrl(url)) }
			?: error("${api.name}: could not load $url")
		response.tags = response.tags?.filter { it.isNotBlank() }
		loadCache[url] = System.currentTimeMillis() to response
		if (loadCache.size > LOAD_CACHE_SIZE) {
			loadCache.entries.minByOrNull { it.value.first }?.let { loadCache.remove(it.key) }
		}
		return response
	}

	private fun mapEpisodes(response: LoadResponse): List<SEpisode> {
		val single = when (response) {
			is MovieLoadResponse -> response.dataUrl
			is LiveStreamLoadResponse -> response.dataUrl
			else -> null
		}
		if (single != null) {
			if (single.isBlank()) return emptyList()
			return listOf(
				SEpisode.create().also {
					it.url = single
					it.name = response.name
					it.episode_number = 1f
				},
			)
		}
		val groups: List<Pair<DubStatus, List<Episode>>> = when (response) {
			is TvSeriesLoadResponse -> listOf(
				DubStatus.None to response.episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })),
			)
			is AnimeLoadResponse -> response.episodes.entries.map { it.key to it.value }
			else -> emptyList()
		}
		val seasons = groups.flatMap { it.second }.mapNotNull { it.season }.toSet()
		return groups.flatMap { (dub, episodes) ->
			episodes.filter { it.data.isNotBlank() }.mapIndexed { index, episode ->
				val number = episode.episode ?: (index + 1)
				SEpisode.create().also {
					it.url = episode.data
					it.name = episodeTitle(episode, number, showSeason = seasons.size > 1)
					it.episode_number = number.toFloat()
					it.date_upload = episode.date ?: 0L
					it.scanlator = dub.takeUnless { d -> d == DubStatus.None }?.name
					it.summary = episode.description
					it.preview_url = episode.posterUrl
				}
			}
		}.distinctBy { it.url }
	}

	private fun episodeTitle(episode: Episode, number: Int, showSeason: Boolean): String {
		val title = episode.name?.takeIf { it.isNotBlank() }
		val season = episode.season
		return when {
			showSeason && season != null && title != null -> "S$season E$number: $title"
			showSeason && season != null -> "S$season E$number"
			title != null -> title
			else -> "Episode $number"
		}
	}

	// --- playback --------------------------------------------------------------------------------

	/** Cloudstream resolves links itself; there are no hosters to list. */
	override suspend fun getHosterList(episode: SEpisode): List<Hoster> = emptyList()

	override suspend fun getVideoList(episode: SEpisode): List<Video> {
		val links = LinkedHashMap<String, ExtractorLink>()
		val subtitles = LinkedHashMap<String, SubtitleFile>()
		var failure: Throwable? = null
		try {
			// A timeout is no failure: providers that are slow still hand over what they found so far.
			CloudstreamRequestScope.callOrNull(api, LOAD_LINKS_TIMEOUT_MS) {
				api.loadLinks(
					episode.url,
					false,
					{ subtitle ->
						if (subtitle.url.isNotBlank()) synchronized(subtitles) { subtitles.putIfAbsent(subtitle.url, subtitle) }
					},
					{ link ->
						if (link.url.isNotBlank() && link.url != "null") {
							synchronized(links) { links.putIfAbsent(link.url, link) }
						}
					},
				)
			}
		} catch (cancellation: kotlinx.coroutines.CancellationException) {
			throw cancellation
		} catch (error: Throwable) {
			failure = error
		}
		val tracks = synchronized(subtitles) {
			subtitles.values.map { Track(url = it.url, lang = it.lang.ifBlank { "Subtitles" }) }
		}
		val playable = synchronized(links) {
			links.values.filter { it.type in PLAYABLE_TYPES }
		}
		if (playable.isEmpty()) {
			// A direct media address stands in when the provider resolves no links.
			if (episode.url.isDirectMediaUrl()) {
				return listOf(Video(videoUrl = episode.url, videoTitle = api.name, subtitleTracks = tracks))
			}
			failure?.let { throw it }
			return emptyList()
		}
		return playable.map { it.toVideo(tracks) }
	}

	private fun ExtractorLink.toVideo(tracks: List<Track>): Video {
		val headers = Headers.Builder()
		getAllHeaders().forEach { (key, value) -> runCatching { headers.set(key, value) } }
		if (headers["User-Agent"] == null) headers.set("User-Agent", USER_AGENT)
		val label = buildString {
			append(name.ifBlank { api.name })
			if (quality > 0 && !contains(quality.toString())) append(' ').append(quality).append('p')
		}
		return Video(
			videoUrl = url,
			videoTitle = label,
			resolution = quality.takeIf { it > 0 },
			headers = headers.build(),
			subtitleTracks = tracks,
		)
	}

	private fun String.isDirectMediaUrl(): Boolean {
		if (!startsWith("http://") && !startsWith("https://")) return false
		val lower = lowercase()
		return DIRECT_EXTENSIONS.any { lower.contains(it) }
	}

	// --- unused Rx entry points of the Aniyomi interface -----------------------------------------

	@Deprecated("Use the non-RxJava API instead")
	override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

	@Deprecated("Use the non-RxJava API instead")
	override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> =
		throw UnsupportedOperationException()

	@Deprecated("Use the non-RxJava API instead")
	override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> = throw UnsupportedOperationException()

	private companion object {

		const val REQUEST_TIMEOUT_MS = 60_000L
		const val LOAD_LINKS_TIMEOUT_MS = 60_000L
		const val LOAD_CACHE_TTL_MS = 3 * 60_000L
		const val LOAD_CACHE_SIZE = 32

		val PLAYABLE_TYPES = setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)
		val DIRECT_EXTENSIONS = listOf(".m3u8", ".mp4", ".mkv", ".webm", ".mpd")
	}
}
