package org.koitharu.kotatsu.metadata

import dagger.Lazy
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataInfo
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataPage
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.mal.data.MALRepository
import org.koitharu.kotatsu.scrobbling.mangabaka.data.MangaBakaRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The rows Home builds from the metadata source. */
enum class MetaList { TRENDING, POPULAR, RECENT }

/** Results per page on Explore's metadata tab. */
const val DISCOVER_PAGE_SIZE = 24

/** How Explore's metadata tab orders its results. */
enum class DiscoverSort(val label: String, val anilist: String, val list: MetaList) {
	TRENDING("Trending", "TRENDING_DESC", MetaList.TRENDING),
	POPULAR("Popular", "POPULARITY_DESC", MetaList.POPULAR),
	SCORE("Top rated", "SCORE_DESC", MetaList.POPULAR),
	NEWEST("Newest", "START_DATE_DESC", MetaList.RECENT),
	TITLE("Title A-Z", "TITLE_ROMAJI", MetaList.POPULAR),
}

/**
 * What the metadata tab asks the provider for. [format] is a [DiscoverFormats] key; [status] is an AniList
 * status (RELEASING, FINISHED, NOT_YET_RELEASED, HIATUS, CANCELLED) and is only applied where the provider
 * can filter on it.
 */
data class DiscoverFilters(
	val query: String = "",
	val include: Set<String> = emptySet(),
	val exclude: Set<String> = emptySet(),
	val sort: DiscoverSort = DiscoverSort.POPULAR,
	val status: String? = null,
	val format: String? = null,
	val year: Int? = null,
	/** WINTER, SPRING, SUMMER or FALL; anime on AniList only. */
	val season: String? = null,
	val hideAdult: Boolean = true,
) {
	val isActive: Boolean
		get() = query.isNotBlank() || include.isNotEmpty() || exclude.isNotEmpty() || status != null ||
			format != null || year != null || season != null
}

/** The format choices per content mode, as (key, label); a key maps onto each provider's own filters. */
object DiscoverFormats {
	val manga = listOf("MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "ONE_SHOT" to "One-shot")
	val novel = emptyList<Pair<String, String>>()
	val anime = listOf("TV" to "TV", "MOVIE" to "Movie", "OVA" to "OVA", "ONA" to "ONA", "SPECIAL" to "Special")

	fun of(mode: HomeMode) = when (mode) {
		HomeMode.MANGA -> manga
		HomeMode.NOVEL -> novel
		HomeMode.ANIME -> anime
	}
}

/** AniList's fixed genre list, the vocabulary the genre filter and the "genres you like" matching use. */
val DISCOVER_GENRES = listOf(
	"Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
	"Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
)

/**
 * A tracker's catalogue used as the app's metadata source: Home's banner and rows, searching from Home, and
 * the details shown for a title. Sources stay the place episodes and chapters come from.
 */
@Singleton
class MetadataRepository @Inject constructor(
	// Lazy: each tracker repository holds an OkHttpClient derived from the base client, whose provider asserts it is
	// not built on the main thread (debug). Injecting them eagerly made any Activity/ViewModel that merely held this
	// class build the client during main-thread injection. They are resolved on first use, inside the suspend calls.
	private val aniListLazy: Lazy<AniListRepository>,
	private val malLazy: Lazy<MALRepository>,
	private val mangaBakaLazy: Lazy<MangaBakaRepository>,
	private val settings: AppSettings,
	private val db: MangaDatabase,
) {

	private val aniList get() = aniListLazy.get()
	private val mal get() = malLazy.get()
	private val mangaBaka get() = mangaBakaLazy.get()

	private val matches = ConcurrentHashMap<String, MetadataTitle>()
	private val keysByManga = ConcurrentHashMap<Long, String>()
	private val misses = ConcurrentHashMap.newKeySet<String>()
	private val infos = ConcurrentHashMap<String, MetadataInfo>()

	/** Whether [provider] can serve [mode]; otherwise callers fall back to the sources. */
	fun supports(provider: HomeMetadata, mode: HomeMode): Boolean = when (provider) {
		HomeMetadata.SOURCES -> false
		HomeMetadata.ANILIST, HomeMetadata.MAL -> true
		HomeMetadata.MANGABAKA -> mode != HomeMode.ANIME
	}

	/** True when Home / search / details should be driven by a tracker for [mode]. */
	fun isActive(mode: HomeMode): Boolean = supports(settings.homeMetadata, mode)

	suspend fun list(
		provider: HomeMetadata,
		kind: MetaList,
		mode: HomeMode,
		limit: Int,
		include: Set<String> = emptySet(),
		exclude: Set<String> = emptySet(),
		hideAdult: Boolean = true,
	): List<MetadataTitle> {
		val novel = mode == HomeMode.NOVEL
		val anime = mode == HomeMode.ANIME
		val raw = when (provider) {
			HomeMetadata.SOURCES -> emptyList()
			HomeMetadata.ANILIST -> aniList.getTrending(
				limit = limit,
				novel = novel,
				include = include,
				exclude = exclude,
				hideAdult = hideAdult,
				anime = anime,
				sort = when (kind) {
					MetaList.TRENDING -> "TRENDING_DESC"
					MetaList.POPULAR -> "POPULARITY_DESC"
					MetaList.RECENT -> "START_DATE_DESC"
				},
				status = if (kind == MetaList.RECENT) "RELEASING" else null,
			)

			HomeMetadata.MAL -> mal.metaList(kind.name, novel, anime, limit, hideAdult)
			HomeMetadata.MANGABAKA -> if (anime) emptyList() else mangaBaka.metaList(novel, limit, hideAdult)
		}
		return raw.filter { t ->
			include.all { g -> t.genres.any { it.equals(g, ignoreCase = true) } } &&
				exclude.none { g -> t.genres.any { it.equals(g, ignoreCase = true) } }
		}
	}

	suspend fun search(provider: HomeMetadata, query: String, mode: HomeMode, limit: Int = 24, hideAdult: Boolean = true): List<MetadataTitle> {
		val novel = mode == HomeMode.NOVEL
		val anime = mode == HomeMode.ANIME
		return when (provider) {
			HomeMetadata.SOURCES -> emptyList()
			HomeMetadata.ANILIST -> aniList.search(query, novel, anime, limit, hideAdult)
			HomeMetadata.MAL -> mal.metaList("", novel, anime, limit, hideAdult, search = query)
			HomeMetadata.MANGABAKA -> if (anime) emptyList() else mangaBaka.metaList(novel, limit, hideAdult, search = query)
		}
	}

	/**
	 * One page of the metadata tab: search, filters and sort against [provider], with a flag saying whether
	 * a next page exists. AniList filters everything server side; MAL and MangaBaka cannot filter by genre,
	 * year or format in their list calls, so those are applied to what a page returns.
	 */
	suspend fun discover(
		provider: HomeMetadata,
		mode: HomeMode,
		filters: DiscoverFilters,
		page: Int,
		perPage: Int = DISCOVER_PAGE_SIZE,
	): MetadataPage {
		val novel = mode == HomeMode.NOVEL
		val anime = mode == HomeMode.ANIME
		val query = filters.query.trim().takeIf { it.isNotEmpty() }
		return when (provider) {
			HomeMetadata.SOURCES -> MetadataPage(emptyList(), false)
			HomeMetadata.ANILIST -> aniList.discover(
				page = page,
				perPage = perPage,
				novel = novel,
				anime = anime,
				sort = filters.sort.anilist,
				include = filters.include.filterNot { DiscoverTags.find(it) != null }.toSet(),
				exclude = filters.exclude.filterNot { DiscoverTags.find(it) != null }.toSet(),
				includeTags = filters.include.mapNotNull { DiscoverTags.find(it)?.anilist }.toSet(),
				excludeTags = filters.exclude.mapNotNull { DiscoverTags.find(it)?.anilist }.toSet(),
				status = filters.status,
				format = when (filters.format) {
					"MANHWA", "MANHUA", "MANGA" -> if (novel) null else "MANGA"
					else -> filters.format
				},
				country = when (filters.format) {
					"MANHWA" -> "KR"
					"MANHUA" -> "CN"
					"MANGA" -> "JP"
					else -> null
				},
				year = filters.year,
				season = filters.season.takeIf { anime },
				search = query,
				hideAdult = filters.hideAdult,
			)

			HomeMetadata.MAL -> {
				val raw = mal.metaList(
					kind = filters.sort.list.name,
					novel = novel,
					anime = anime,
					limit = perPage,
					hideAdult = filters.hideAdult,
					search = query,
					offset = (page - 1) * perPage,
				)
				MetadataPage(raw.filterLocally(filters), hasNext = raw.size >= perPage)
			}

			HomeMetadata.MANGABAKA -> {
				if (anime) return MetadataPage(emptyList(), false)
				val raw = mangaBaka.metaList(novel, perPage, filters.hideAdult, search = query, page = page)
				MetadataPage(raw.filterLocally(filters), hasNext = raw.size >= perPage)
			}
		}
	}

	private fun List<MetadataTitle>.filterLocally(f: DiscoverFilters): List<MetadataTitle> = filter { t ->
		f.include.all { g -> DiscoverTags.matches(g, t.genres, t.tags) } &&
			f.exclude.none { g -> DiscoverTags.matches(g, t.genres, t.tags) } &&
			(f.year == null || t.year == f.year) &&
			(f.format == null || t.kind == f.format || t.format.equals(f.format, ignoreCase = true))
	}

	/**
	 * The tracker's entry for a title opened from a source, or null when the user chose "Source" for covers
	 * and info, or the tracker does not know the title (web novels are rarely on AniList), so those keep what
	 * their source says. Looked up once per title and remembered.
	 *
	 * A title that is tracked uses the entry it is tracked as, on the tracker it is tracked on, so its cover
	 * and info always agree with the list entry. Otherwise the chosen tracker is searched by every name the
	 * source has and the candidates are ranked by [MetadataMatcher], which also checks kind (manhwa, manhua,
	 * manga) and Boys' Love so a namesake cannot win.
	 */
	suspend fun matchFor(manga: Manga): MetadataTitle? {
		if (!settings.isMetadataFromTracker) return null
		val mode = manga.source.contentMode
		trackedMatch(manga, mode)?.let { return it }
		val provider = settings.homeMetadataProvider
		if (!supports(provider, mode)) return null
		val key = "${provider.name}:${manga.id}"
		matches[key]?.let { return it }
		if (key in misses) return null
		val profile = SourceProfile.of(manga)
		if (profile.names.isEmpty()) return null
		// Search by the first name, then by the other names: a namesake can crowd the right entry out of the
		// first page, and a different spelling often surfaces it.
		val pool = LinkedHashMap<Long, MetadataTitle>()
		for (name in profile.names.take(SEARCH_NAMES)) {
			runCatching { search(provider, name, mode, limit = 10, hideAdult = false) }
				.getOrDefault(emptyList())
				.forEach { pool.putIfAbsent(it.id, it) }
			if (MetadataMatcher.best(profile, pool.values.toList()) != null && pool.size >= 3) break
		}
		val found = MetadataMatcher.best(profile, pool.values.toList())
		if (found == null) {
			misses.add(key)
		} else {
			matches[key] = found
			keysByManga[manga.id] = MetadataLinks.key(MetaRef(provider, found.id, mode))
		}
		return found
	}

	/**
	 * The entry [manga] is tracked as. The tracker chosen for metadata goes first, then the others, so a
	 * title tracked on MAL while AniList is chosen still gets MAL's cover instead of a guessed AniList one.
	 */
	private suspend fun trackedMatch(manga: Manga, mode: HomeMode): MetadataTitle? {
		val rows = runCatching { db.getScrobblingDao().findAll(manga.id) }.getOrDefault(emptyList())
		if (rows.isEmpty()) return null
		val chosen = settings.homeMetadataProvider
		val order = (listOf(chosen) + listOf(HomeMetadata.ANILIST, HomeMetadata.MAL, HomeMetadata.MANGABAKA)).distinct()
		for (provider in order) {
			if (!supports(provider, mode)) continue
			val service = provider.service() ?: continue
			val row = rows.firstOrNull { it.scrobbler == service.id } ?: continue
			val key = "TRACKED:${provider.name}:${row.targetId}"
			val title = matches[key] ?: runCatching {
				when (provider) {
					HomeMetadata.ANILIST -> aniList.metadataById(row.targetId)
					HomeMetadata.MAL -> mal.metadataById(row.targetId)
					HomeMetadata.MANGABAKA -> mangaBaka.metadataById(row.targetId)
					HomeMetadata.SOURCES -> null
				}
			}.getOrNull()?.also { matches[key] = it } ?: continue
			keysByManga[manga.id] = MetadataLinks.key(MetaRef(provider, title.id, mode))
			return title
		}
		return null
	}

	private fun HomeMetadata.service(): ScrobblerService? = when (this) {
		HomeMetadata.ANILIST -> ScrobblerService.ANILIST
		HomeMetadata.MAL -> ScrobblerService.MAL
		HomeMetadata.MANGABAKA -> ScrobblerService.MANGABAKA
		HomeMetadata.SOURCES -> null
	}

	/** One tracker entry by id, for a details page opened from a tracker title before any source was found. */
	suspend fun titleById(provider: HomeMetadata, id: Long): MetadataTitle? = when (provider) {
		HomeMetadata.ANILIST -> aniList.metadataById(id)
		HomeMetadata.MAL -> mal.metadataById(id)
		HomeMetadata.MANGABAKA -> mangaBaka.metadataById(id)
		HomeMetadata.SOURCES -> null
	}

	/**
	 * Remembers that [manga] is [title] on [provider], so its details page - opened straight from a tracker
	 * title before any source was searched - already has the tracker's cover and info when it starts.
	 */
	fun remember(manga: Manga, provider: HomeMetadata, title: MetadataTitle) {
		val mode = manga.source.contentMode
		matches["${provider.name}:${manga.id}"] = title
		keysByManga[manga.id] = MetadataLinks.key(MetaRef(provider, title.id, mode))
	}

	/** Info already fetched for [id] on [provider], or null. Lets a page draw its cards without waiting. */
	fun cachedInfo(provider: HomeMetadata, id: Long): MetadataInfo? = infos["${provider.name}:$id"]

	/**
	 * The AniList-style page of a tracker title. AniList gives everything (stats, tags with percentages,
	 * relations, characters, staff, recommendations); MAL and MangaBaka only give what their catalogue entry
	 * holds. Fetched once and remembered.
	 */
	suspend fun info(provider: HomeMetadata, title: MetadataTitle): MetadataInfo {
		val key = "${provider.name}:${title.id}"
		infos[key]?.let { return it }
		val full = if (provider == HomeMetadata.ANILIST) {
			runCatchingCancellable { aniList.infoById(title.id) }.getOrNull()
		} else {
			null
		}
		val result = full ?: MetadataInfo.fromTitle(title)
		// A failed AniList request is not remembered, so the next open tries again.
		if (full != null || provider != HomeMetadata.ANILIST) infos[key] = result
		return result
	}

	/** The tracker page of a title opened from a source, or null when the tracker does not know it. */
	suspend fun infoFor(manga: Manga): MetadataInfo? {
		val match = matchFor(manga) ?: return null
		val provider = keysByManga[manga.id]?.substringBefore(':')
			?.let { name -> runCatching { HomeMetadata.valueOf(name) }.getOrNull() }
			?: return null
		return info(provider, match)
	}

	/** The link key of the tracker title a details screen matched for [mangaId], once it has looked it up. */
	fun metaKeyForManga(mangaId: Long): String? = keysByManga[mangaId]

	private companion object {
		const val SEARCH_NAMES = 3
	}
}

/** Stand-in source for tracker results shown in lists made for sources (search results). Never fetched from. */
class MetadataMangaSource(val provider: HomeMetadata) : MangaSource {
	override val name: String = "meta_${provider.name.lowercase()}"
}

/** What a tracker result in a list points at. Recovered from the virtual manga's url. */
data class MetaRef(val provider: HomeMetadata, val id: Long, val mode: HomeMode)

/** A tracker result as a [Manga] that list models can show; open it with [metaRef], never as a real title. */
fun MetadataTitle.toVirtualManga(provider: HomeMetadata, mode: HomeMode): Manga {
	val source = MetadataMangaSource(provider)
	return Manga(
		id = ("meta:${provider.name}:$id:${mode.name}").hashCode().toLong() and Long.MAX_VALUE,
		title = title,
		altTitles = names.filter { it != title }.toSet(),
		url = "${provider.name}:$id:${mode.name}",
		publicUrl = url,
		rating = score?.let { (it / 10f).coerceIn(0f, 1f) } ?: -1f,
		contentRating = null,
		coverUrl = coverUrl,
		tags = genres.mapTo(LinkedHashSet()) { MangaTag(title = it, key = it, source = source) },
		state = null,
		authors = emptySet(),
		largeCoverUrl = bannerUrl,
		description = description,
		chapters = null,
		source = source,
	)
}

fun Manga.metaRef(): MetaRef? {
	if (source !is MetadataMangaSource) return null
	val parts = url.split(':')
	if (parts.size < 3) return null
	return MetaRef(
		provider = runCatching { HomeMetadata.valueOf(parts[0]) }.getOrNull() ?: return null,
		id = parts[1].toLongOrNull() ?: return null,
		mode = runCatching { HomeMode.valueOf(parts[2]) }.getOrNull() ?: return null,
	)
}
