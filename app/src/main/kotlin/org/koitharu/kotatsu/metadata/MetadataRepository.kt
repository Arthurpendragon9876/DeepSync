package org.koitharu.kotatsu.metadata

import dagger.Lazy
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher
import org.koitharu.kotatsu.scrobbling.mal.data.MALRepository
import org.koitharu.kotatsu.scrobbling.mangabaka.data.MangaBakaRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The rows Home builds from the metadata source. */
enum class MetaList { TRENDING, POPULAR, RECENT }

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
) {

	private val aniList get() = aniListLazy.get()
	private val mal get() = malLazy.get()
	private val mangaBaka get() = mangaBakaLazy.get()

	private val matches = ConcurrentHashMap<String, MetadataTitle>()
	private val keysByManga = ConcurrentHashMap<Long, String>()
	private val misses = ConcurrentHashMap.newKeySet<String>()

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
	 * The tracker's entry for a title opened from a source, when the chosen metadata source is a tracker and
	 * knows it. Null for titles it cannot match (web novels are rarely on AniList), so those keep what their
	 * source says. Looked up once per title and remembered.
	 */
	suspend fun matchFor(manga: Manga): MetadataTitle? {
		val provider = settings.homeMetadata
		val mode = manga.source.contentMode
		if (!supports(provider, mode)) return null
		val key = "${provider.name}:${manga.id}"
		matches[key]?.let { return it }
		if (key in misses) return null
		val names = (listOf(manga.title) + manga.altTitles).filter { it.isNotBlank() }.distinct()
		val found = runCatching {
			search(provider, names.first(), mode, limit = 8, hideAdult = false)
		}.getOrDefault(emptyList()).firstOrNull { TitleMatcher.bestScore(names, it.names) >= MATCH_SCORE }
		if (found == null) {
			misses.add(key)
		} else {
			matches[key] = found
			keysByManga[manga.id] = MetadataLinks.key(MetaRef(provider, found.id, mode))
		}
		return found
	}

	/** The link key of the tracker title a details screen matched for [mangaId], once it has looked it up. */
	fun metaKeyForManga(mangaId: Long): String? = keysByManga[mangaId]

	private companion object {
		const val MATCH_SCORE = 0.85f
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
