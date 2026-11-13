package org.koitharu.kotatsu.scrobbling.anilist.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toIntUp
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val REDIRECT_URI = "kotatsu://anilist-auth"
private const val BASE_URL = "https://anilist.co/api/v2/"
private const val ENDPOINT = "https://graphql.anilist.co"
private const val MANGA_PAGE_SIZE = 10
private const val REQUEST_QUERY = "query"
private const val REQUEST_MUTATION = "mutation"
private const val KEY_SCORE_FORMAT = "score_format"
private const val MAX_LIBRARY_CHUNKS = 30
private const val META_FIELDS = """
  id
  title { userPreferred romaji english native }
  synonyms
  coverImage { extraLarge large }
  bannerImage
  description(asHtml: false)
  genres
  tags { name }
  isAdult
  averageScore
  siteUrl
  format
  countryOfOrigin
  startDate { year }
"""
private fun collectionFields(anime: Boolean) = """
  lists {
    entries {
      progress
      updatedAt
      status
      media {
        id
        title { userPreferred romaji english native }
        synonyms
        coverImage { large }
        siteUrl
        ${if (anime) "chapters: episodes" else "chapters"}
        format
      }
    }
  }
"""

/** A title from AniList's public catalogue, used for Home's hero and Trending when AniList is the metadata source. */
data class MetadataTitle(
	val id: Long,
	val title: String,
	/** Romaji, English, native and synonyms. */
	val names: List<String>,
	val coverUrl: String?,
	val bannerUrl: String?,
	val genres: List<String>,
	val isAdult: Boolean,
	/** 0..10 */
	val score: Float?,
	val url: String,
	val description: String? = null,
	/** The tracker's own format label (AniList MANGA/ONE_SHOT/NOVEL, MAL manga/manhwa/manhua, MangaBaka type). */
	val format: String? = null,
	/** ISO country of origin when the tracker says it (JP manga, KR manhwa, CN manhua, TW manhua). */
	val country: String? = null,
	/** Tags / themes beyond genres, e.g. "Boys' Love", used to tell same-named titles apart. */
	val tags: List<String> = emptyList(),
	val year: Int? = null,
) {

	/** Normalised kind: MANHWA, MANHUA, MANGA, NOVEL, ONE_SHOT or null when unknown. */
	val kind: String?
		get() = MetadataKinds.kindOf(format, country)

	val isBoysLove: Boolean
		get() = MetadataKinds.isBoysLove(genres + tags)

	val isGirlsLove: Boolean
		get() = MetadataKinds.isGirlsLove(genres + tags)
}

@OptIn(InternalParsersApi::class)
@Singleton
class AniListRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.ANILIST) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.ANILIST) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.anilist_clientId)
	private val clientSecret = context.getString(R.string.anilist_clientSecret)

	override val oauthUrl: String
		get() = "${BASE_URL}oauth/authorize?client_id=$clientId&" +
			"redirect_uri=${REDIRECT_URI}&response_type=code"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	private val shrinkRegex = Regex("\\t+")

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		body.add("client_id", clientId)
		body.add("client_secret", clientSecret)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code", code)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken))
		}
		val request = Request.Builder()
			.post(body.build())
			.url("${BASE_URL}oauth/token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			AniChartUser {
				user {
					id
					name
					avatar {
						medium
					}
					mediaListOptions {
						scoreFormat
					}
				}
			}
		""",
		)
		val jo = response.getJSONObject("data").getJSONObject("AniChartUser").getJSONObject("user")
		storage[KEY_SCORE_FORMAT] = jo.getJSONObject("mediaListOptions").getString("scoreFormat")
		return AniListUser(jo).also { storage.user = it }
	}

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.ANILIST.id, mangaId)
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		return searchMedia(query, offset, type).mapJSON { ScrobblerManga(it, query) }
	}

	/** Every name AniList has for each result (romaji, English, native, synonyms), ranked against [query]. */
	override suspend fun findCandidates(query: String, type: ScrobblerMangaType): List<TrackerCandidate> {
		return searchMedia(query, 0, type).mapJSON { json ->
			val names = namesOf(json)
			val best = names.maxByOrNull { TitleMatcher.score(query, it) }
			TrackerCandidate(
				manga = ScrobblerManga(json, query),
				names = names,
				score = TitleMatcher.bestScore(listOf(query), names),
				matchedName = best,
				format = json.getStringOrNull("format"),
				country = json.getStringOrNull("countryOfOrigin"),
				labels = labelsOf(json),
			)
		}
	}

	private suspend fun searchMedia(query: String, offset: Int, type: ScrobblerMangaType): JSONArray {
		val page = (offset / MANGA_PAGE_SIZE.toFloat()).toIntUp() + 1
		// AniList files prose as the NOVEL format under the MANGA type; anime is its own media type
		val mediaType = if (type.isAnime) "ANIME" else "MANGA"
		val formatFilter = when {
			type.isAnime -> ""
			type.isNovel -> ", format: NOVEL"
			else -> ", format_not: NOVEL"
		}
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Page(page: $page, perPage: ${MANGA_PAGE_SIZE}) {
				media(type: $mediaType$formatFilter, sort: SEARCH_MATCH, search: ${JSONObject.quote(query)}) {
					id
					title {
						userPreferred
						romaji
						english
						native
					}
					synonyms
					coverImage {
						medium
					}
					siteUrl
					format
					countryOfOrigin
					genres
					tags { name }
				}
			}
		""",
		)
		return response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
	}

	private fun labelsOf(json: JSONObject): List<String> {
		val out = ArrayList<String>()
		json.optJSONArray("genres")?.let { for (i in 0 until it.length()) out += it.optString(i) }
		json.optJSONArray("tags")?.let { for (i in 0 until it.length()) it.optJSONObject(i)?.getStringOrNull("name")?.let(out::add) }
		return out.filter { it.isNotBlank() }
	}

	private fun namesOf(json: JSONObject): List<String> {
		val names = ArrayList<String>()
		val title = json.optJSONObject("title")
		if (title != null) {
			listOf("userPreferred", "romaji", "english", "native").forEach { key ->
				title.getStringOrNull(key)?.let { names += it }
			}
		}
		val synonyms = json.optJSONArray("synonyms")
		if (synonyms != null) {
			for (i in 0 until synonyms.length()) synonyms.optString(i).takeIf { it.isNotBlank() }?.let { names += it }
		}
		return names.distinct()
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		// A create defaults the entry to CURRENT, so an entry already on the list is adopted first.
		// mediaListEntry is simply null when the authorised user has not listed this media.
		val existing = doRequest(
			REQUEST_QUERY,
			"""
				Media(id: $scrobblerMangaId) {
					mediaListEntry {
						id
						mediaId
						status
						notes
						score
						progress
					}
				}
			""",
		).getJSONObject("data").getJSONObject("Media").optJSONObject("mediaListEntry")
		if (existing != null) {
			saveRate(existing, mangaId)
			return true
		}
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(mediaId: $scrobblerMangaId) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
		return false
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity {
		val response = doRequest(
			REQUEST_QUERY,
			"""
				MediaList(id: ${entity.id}) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		return saveRate(response.getJSONObject("data").getJSONObject("MediaList"), entity.mangaId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, progress: $chapter) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	) {
		val scoreRaw = (rating * 100f).roundToInt()
		val statusString = status?.let { ", status: $it" }.orEmpty()
		val notesString = comment?.let { ", notes: ${JSONObject.quote(it)}" }.orEmpty()
		val startedAtString = if (setStartDate) {
			val today = LocalDate.now()
			", startedAt: { year: ${today.year}, month: ${today.monthValue}, day: ${today.dayOfMonth} }"
		} else {
			""
		}
		val response = doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(id: $rateId, scoreRaw: $scoreRaw$statusString$notesString$startedAtString) {
					id
					mediaId
					status
					notes
					score
					progress
				}
			""",
		)
		saveRate(response.getJSONObject("data").getJSONObject("SaveMediaListEntry"), mangaId)
	}

	override suspend fun setProgress(entry: TrackerReadingEntry, chapter: Int): Boolean {
		// Saving by media id both creates and updates, and leaves score, notes and dates alone
		val promote = if (entry.status == "PLANNING") ", status: CURRENT" else ""
		doRequest(
			REQUEST_MUTATION,
			"""
				SaveMediaListEntry(mediaId: ${entry.targetId}, progress: $chapter$promote) {
					id
					progress
				}
			""",
		)
		return true
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Media(id: $id) {
				id
				title {
					userPreferred
				}
				coverImage {
					large
				}
				description
				siteUrl
				chapters
				episodes
			}
			""",
		)
		return ScrobblerMangaInfo(response.getJSONObject("data").getJSONObject("Media"))
	}

	override suspend fun getReadingList(limit: Int, anime: Boolean): List<TrackerReadingEntry> {
		val userId = (storage.user ?: loadUser()).id
		val perChunk = limit.coerceIn(1, 100)
		val response = doRequest(
			REQUEST_QUERY,
			"MediaListCollection(userId: $userId, type: ${if (anime) "ANIME" else "MANGA"}, status: CURRENT, sort: UPDATED_TIME_DESC, chunk: 1, perChunk: $perChunk) { ${collectionFields(anime)} }",
		)
		val lists = response.getJSONObject("data").getJSONObject("MediaListCollection").getJSONArray("lists")
		return parseEntries(lists, anime).sortedByDescending { it.updatedAt }.take(limit)
	}

	/** Every list on the account, all statuses, fetched in chunks of 100 until AniList has no more. */
	override suspend fun getLibrary(anime: Boolean): List<TrackerReadingEntry> {
		val userId = (storage.user ?: loadUser()).id
		val byId = LinkedHashMap<Long, TrackerReadingEntry>()
		var chunk = 1
		while (chunk <= MAX_LIBRARY_CHUNKS) {
			val response = doRequest(
				REQUEST_QUERY,
				"MediaListCollection(userId: $userId, type: ${if (anime) "ANIME" else "MANGA"}, sort: UPDATED_TIME_DESC, chunk: $chunk, perChunk: 100) { ${collectionFields(anime)} hasNextChunk }",
			)
			val collection = response.getJSONObject("data").getJSONObject("MediaListCollection")
			parseEntries(collection.getJSONArray("lists"), anime).forEach { byId[it.targetId] = it }
			if (!collection.optBoolean("hasNextChunk")) break
			chunk++
		}
		return byId.values.sortedByDescending { it.updatedAt }
	}

	private fun parseEntries(lists: JSONArray, anime: Boolean): List<TrackerReadingEntry> {
		val result = ArrayList<TrackerReadingEntry>()
		for (i in 0 until lists.length()) {
			val entries = lists.getJSONObject(i).getJSONArray("entries")
			for (j in 0 until entries.length()) {
				val entry = entries.getJSONObject(j)
				val media = entry.getJSONObject("media")
				val names = namesOf(media)
				result += TrackerReadingEntry(
					service = ScrobblerService.ANILIST,
					targetId = media.getLong("id"),
					title = media.getJSONObject("title").getString("userPreferred"),
					coverUrl = media.getJSONObject("coverImage").getStringOrNull("large"),
					chapter = entry.optInt("progress"),
					totalChapters = media.optInt("chapters"),
					url = media.getString("siteUrl"),
					// AniList reports seconds
					updatedAt = entry.optLong("updatedAt") * 1000L,
					status = when (entry.optString("status")) {
						"CURRENT", "REPEATING" -> "READING"
						"PLANNING" -> "PLANNING"
						"COMPLETED" -> "COMPLETED"
						"PAUSED" -> "PAUSED"
						"DROPPED" -> "DROPPED"
						else -> "READING"
					},
					altTitles = names,
					isNovel = !anime && media.optString("format") == "NOVEL",
					isAnime = anime,
				)
			}
		}
		return result
	}

	/** Titles matching [query] in AniList's catalogue, best match first. */
	suspend fun search(query: String, novel: Boolean, anime: Boolean, limit: Int, hideAdult: Boolean): List<MetadataTitle> =
		getTrending(limit, novel, emptySet(), emptySet(), hideAdult, anime, sort = "SEARCH_MATCH", search = query)

	/**
	 * What is trending on AniList right now. Genre filters are applied by AniList; "include" asks for
	 * titles that have every listed genre, which is re-checked here because the API treats the list
	 * loosely.
	 */
	suspend fun getTrending(
		limit: Int,
		novel: Boolean,
		include: Set<String>,
		exclude: Set<String>,
		hideAdult: Boolean,
		anime: Boolean = false,
		sort: String = "TRENDING_DESC",
		status: String? = null,
		search: String? = null,
	): List<MetadataTitle> {
		val formatFilter = when {
			anime -> ""
			novel -> ", format: NOVEL"
			else -> ", format_not: NOVEL"
		}
		val genreIn = if (include.isEmpty()) "" else ", genre_in: [${include.joinToString { JSONObject.quote(it) }}]"
		val genreNot = if (exclude.isEmpty()) "" else ", genre_not_in: [${exclude.joinToString { JSONObject.quote(it) }}]"
		val adult = if (hideAdult) ", isAdult: false" else ""
		val statusArg = if (status == null) "" else ", status: $status"
		val searchArg = if (search == null) "" else ", search: ${JSONObject.quote(search)}"
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Page(page: 1, perPage: $limit) {
				media(type: ${if (anime) "ANIME" else "MANGA"}$formatFilter, sort: $sort$statusArg$searchArg$genreIn$genreNot$adult) {
					id
					title { userPreferred romaji english native }
					synonyms
					$META_FIELDS
				}
			}
		""",
		)
		val media = response.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
		return media.mapJSON { json -> metadataOf(json) }
			.filter { t -> include.all { g -> t.genres.any { it.equals(g, ignoreCase = true) } } }
	}

	/**
	 * One exact AniList entry by id. Used when the title is already tracked here, so the cover and info
	 * come from the entry the user actually linked rather than from whichever same-named title ranks first.
	 */
	suspend fun metadataById(id: Long): MetadataTitle? {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Media(id: $id) {
				$META_FIELDS
			}
			""",
		)
		val json = response.getJSONObject("data").optJSONObject("Media") ?: return null
		return metadataOf(json)
	}

	/**
	 * The full AniList page of one entry: stats, titles, tags with their percentages, relations, characters,
	 * staff and recommendations. One request, so the details screen can fill every card at once.
	 */
	suspend fun infoById(id: Long): MetadataInfo? {
		val response = doRequest(
			REQUEST_QUERY,
			"""
			Media(id: $id) {
				id
				title { userPreferred romaji english native }
				synonyms
				description(asHtml: false)
				status
				format
				countryOfOrigin
				chapters
				volumes
				episodes
				duration
				season
				seasonYear
				startDate { year }
				source
				averageScore
				popularity
				siteUrl
				genres
				tags { name rank isMediaSpoiler }
				studios(isMain: true) { nodes { name } }
				nextAiringEpisode { episode airingAt }
				relations {
					edges {
						relationType
						node { id type format title { userPreferred } coverImage { large } bannerImage }
					}
				}
				characters(sort: [ROLE, FAVOURITES_DESC], perPage: 25) {
					edges {
						role
						node { id name { userPreferred } image { large } favourites }
					}
				}
				staff(sort: RELEVANCE, perPage: 25) {
					edges {
						role
						node { id name { userPreferred } image { large } }
					}
				}
				recommendations(sort: RATING_DESC, perPage: 20) {
					nodes {
						mediaRecommendation {
							id type format averageScore
							title { userPreferred }
							coverImage { large }
						}
					}
				}
			}
			""",
		)
		val json = response.getJSONObject("data").optJSONObject("Media") ?: return null
		val title = json.getJSONObject("title")
		fun strings(key: String) = json.optJSONArray(key)?.let { arr ->
			(0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
		}.orEmpty()
		fun JSONObject.mediaOf(relation: String?) = InfoMedia(
			id = getLong("id"),
			title = optJSONObject("title")?.getStringOrNull("userPreferred").orEmpty(),
			coverUrl = optJSONObject("coverImage")?.getStringOrNull("large"),
			bannerUrl = getStringOrNull("bannerImage"),
			type = getStringOrNull("type"),
			format = getStringOrNull("format"),
			relation = relation,
			score = optInt("averageScore").takeIf { it > 0 }?.let { it / 10f },
		)
		fun JSONObject.personOf(role: String?) = InfoPerson(
			id = getLong("id"),
			name = optJSONObject("name")?.getStringOrNull("userPreferred").orEmpty(),
			imageUrl = optJSONObject("image")?.getStringOrNull("large"),
			role = role,
			favourites = optInt("favourites").takeIf { it > 0 },
		)
		val tags = json.optJSONArray("tags")?.mapJSON { t ->
			if (t.optBoolean("isMediaSpoiler")) null else t.getStringOrNull("name")?.let { InfoTag(it, t.optInt("rank")) }
		}?.filterNotNull().orEmpty().sortedByDescending { it.rank }
		val relations = json.optJSONObject("relations")?.optJSONArray("edges")?.mapJSON { e ->
			e.optJSONObject("node")?.mediaOf(e.getStringOrNull("relationType"))
		}?.filterNotNull().orEmpty()
		val characters = json.optJSONObject("characters")?.optJSONArray("edges")?.mapJSON { e ->
			e.optJSONObject("node")?.personOf(e.getStringOrNull("role"))
		}?.filterNotNull().orEmpty()
		val staff = json.optJSONObject("staff")?.optJSONArray("edges")?.mapJSON { e ->
			e.optJSONObject("node")?.personOf(e.getStringOrNull("role"))
		}?.filterNotNull().orEmpty()
		val recs = json.optJSONObject("recommendations")?.optJSONArray("nodes")?.mapJSON { n ->
			n.optJSONObject("mediaRecommendation")?.mediaOf(null)
		}?.filterNotNull().orEmpty()
		val studios = json.optJSONObject("studios")?.optJSONArray("nodes")?.mapJSON { it.getStringOrNull("name") }
			?.filterNotNull().orEmpty()
		val airing = json.optJSONObject("nextAiringEpisode")
		return MetadataInfo(
			id = json.getLong("id"),
			title = title.getStringOrNull("userPreferred").orEmpty(),
			romaji = title.getStringOrNull("romaji"),
			english = title.getStringOrNull("english"),
			native = title.getStringOrNull("native"),
			synonyms = strings("synonyms"),
			description = json.getStringOrNull("description"),
			status = json.getStringOrNull("status"),
			format = json.getStringOrNull("format"),
			country = json.getStringOrNull("countryOfOrigin"),
			chapters = json.optInt("chapters").takeIf { it > 0 },
			volumes = json.optInt("volumes").takeIf { it > 0 },
			episodes = json.optInt("episodes").takeIf { it > 0 },
			duration = json.optInt("duration").takeIf { it > 0 },
			season = json.getStringOrNull("season"),
			year = json.optInt("seasonYear").takeIf { it > 0 }
				?: json.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 },
			source = json.getStringOrNull("source"),
			score = json.optInt("averageScore").takeIf { it > 0 }?.let { it / 10f },
			popularity = json.optInt("popularity").takeIf { it > 0 },
			studios = studios,
			genres = strings("genres"),
			tags = tags,
			relations = relations,
			characters = characters,
			staff = staff,
			recommendations = recs,
			nextEpisode = airing?.optInt("episode")?.takeIf { it > 0 },
			nextAiringAt = airing?.optLong("airingAt")?.takeIf { it > 0 },
			url = json.getString("siteUrl"),
		)
	}

	private fun metadataOf(json: JSONObject): MetadataTitle {
		val genres = json.optJSONArray("genres")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
		val tags = json.optJSONArray("tags")?.let { arr ->
			(0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.getStringOrNull("name") }
		}.orEmpty()
		val cover = json.optJSONObject("coverImage")
		return MetadataTitle(
			id = json.getLong("id"),
			title = json.getJSONObject("title").getString("userPreferred"),
			names = namesOf(json),
			coverUrl = cover?.getStringOrNull("extraLarge") ?: cover?.getStringOrNull("large"),
			bannerUrl = json.getStringOrNull("bannerImage"),
			description = json.getStringOrNull("description"),
			genres = genres,
			isAdult = json.optBoolean("isAdult"),
			score = json.optInt("averageScore").takeIf { it > 0 }?.let { it / 10f },
			url = json.getString("siteUrl"),
			format = json.getStringOrNull("format"),
			country = json.getStringOrNull("countryOfOrigin"),
			tags = tags,
			year = json.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 },
		)
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long): ScrobblingEntity {
		val scoreFormat = ScoreFormat.of(storage[KEY_SCORE_FORMAT])
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.ANILIST.id,
			id = json.getInt("id"),
			mangaId = mangaId,
			targetId = json.getLong("mediaId"),
			status = json.getString("status"),
			chapter = json.getInt("progress"),
			comment = json.getString("notes"),
			rating = scoreFormat.normalize(json.getDouble("score").toFloat()),
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	private fun ScrobblerManga(json: JSONObject, sourceTitle: String): ScrobblerManga {
		val title = json.getJSONObject("title")
		return ScrobblerManga(
			id = json.getLong("id"),
			name = title.getString("userPreferred"),
			altName = title.getStringOrNull("native"),
			cover = json.getJSONObject("coverImage").getString("medium"),
			url = json.getString("siteUrl"),
			// Romaji, English, native and every synonym count, not just two of them
			isBestMatch = TitleMatcher.sameAny(listOf(sourceTitle), namesOf(json)),
		)
	}

	private fun ScrobblerMangaInfo(json: JSONObject) = ScrobblerMangaInfo(
		id = json.getLong("id"),
		name = json.getJSONObject("title").getString("userPreferred"),
		cover = json.getJSONObject("coverImage").getString("large"),
		url = json.getString("siteUrl"),
		descriptionHtml = json.getString("description"),
		totalChapters = maxOf(json.optInt("chapters"), json.optInt("episodes")),
	)

	@Suppress("FunctionName")
	private fun AniListUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("name"),
		avatar = json.getJSONObject("avatar").getStringOrNull("medium"),
		service = ScrobblerService.ANILIST,
	)

	private suspend fun doRequest(type: String, payload: String): JSONObject {
		val body = JSONObject()
		body.put("query", "$type { ${payload.shrink()} }")
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.post(requestBody)
			.url(ENDPOINT)
		val json = okHttp.newCall(request.build()).await().parseJson()
		json.optJSONArray("errors")?.let {
			if (it.length() != 0) {
				throw GraphQLException(it)
			}
		}
		return json
	}

	private fun String.shrink() = replace(shrinkRegex, " ")
}
