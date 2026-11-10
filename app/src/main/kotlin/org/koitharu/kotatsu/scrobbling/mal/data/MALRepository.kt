package org.koitharu.kotatsu.scrobbling.mal.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import java.security.SecureRandom
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val REDIRECT_URI = "kotatsu://mal-auth"
private const val BASE_WEB_URL = "https://myanimelist.net"
private const val LIST_FIELDS = "list_status,num_chapters,media_type,alternative_titles"
private const val BASE_API_URL = "https://api.myanimelist.net/v2"
private const val MANGA_PAGE_SIZE = 20
/**
 * MAL numbers anime and manga separately (anime 1 and manga 1 are different titles), but the link
 * tables hold a single id per title. Anime ids are therefore stored shifted by this offset.
 */
private const val ANIME_ID_OFFSET = 1_000_000_000L
private const val ANIME_LIST_FIELDS = "list_status,num_episodes,media_type,alternative_titles"
private fun Long.isAnimeId() = this >= ANIME_ID_OFFSET
private fun Long.realId() = if (isAnimeId()) this - ANIME_ID_OFFSET else this
private fun Long.segment() = if (isAnimeId()) "anime" else "manga"
private fun Long.asAnimeId() = this + ANIME_ID_OFFSET
private fun String.toAnimeStatus() = when (this) {
	"reading" -> "watching"
	"plan_to_read" -> "plan_to_watch"
	else -> this
}
private fun String.toReadingStatus() = when (this) {
	"watching" -> "reading"
	"plan_to_watch" -> "plan_to_read"
	else -> this
}
private val NOVEL_MEDIA_TYPES = setOf("novel", "light_novel")

@Singleton
class MALRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.MAL) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MAL) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.mal_clientId)
	private val codeVerifier: String by lazy(::generateCodeVerifier)

	override val oauthUrl: String
		get() = "$BASE_WEB_URL/v1/oauth2/authorize?" +
			"response_type=code" +
			"&client_id=$clientId" +
			"&redirect_uri=$REDIRECT_URI" +
			"&code_challenge=$codeVerifier" +
			"&code_challenge_method=plain"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() {
			return storage.user
		}

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
		if (code != null) {
			body.add("client_id", clientId)
			body.add("grant_type", "authorization_code")
			body.add("code", code)
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code_verifier", codeVerifier)
		}
		val request = Request.Builder()
			.post(body.build())
			.url("${BASE_WEB_URL}/v1/oauth2/token")

		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.getString("refresh_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder()
			.get()
			.url("${BASE_API_URL}/users/@me")
		val response = okHttp.newCall(request.build()).await().parseJson()
		return MALUser(response).also { storage.user = it }
	}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.MAL.id, mangaId)
	}

	/**
	 * MAL's search has no media type parameter, so the type is filtered out of each page here. That
	 * makes a page yield fewer than [MANGA_PAGE_SIZE] items, which the caller's offset-based paging
	 * absorbs by re-requesting the overlap and de-duplicating.
	 */
	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(if (type.isAnime) "anime" else "manga")
			.addQueryParameter("offset", offset.toString())
			.addQueryParameter("limit", MANGA_PAGE_SIZE.toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", "media_type,main_picture")
			// WARNING! MAL API throws a 400 when the query is over 64 characters
			.addQueryParameter("q", query.take(64))
			.build()
		val request = Request.Builder().url(url).get().build()
		val response = okHttp.newCall(request).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		val data = response.getJSONArray("data")
		return data.mapJSONNotNull { jo ->
			val mediaType = jo.optJSONObject("node")?.getStringOrNull("media_type")
			// unknown media_type counts as a comic so nothing silently disappears from both tabs
			if (!type.isAnime && (mediaType in NOVEL_MEDIA_TYPES) != type.isNovel) return@mapJSONNotNull null
			jsonToManga(jo, query, type.isAnime)
		}
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(id.segment())
			.addPathSegment(id.realId().toString())
			.addQueryParameter("fields", if (id.isAnimeId()) "synopsis,num_episodes" else "synopsis,num_chapters")
			.build()
		val request = Request.Builder().url(url)
		val response = okHttp.newCall(request.build()).await().parseJson()
		return ScrobblerMangaInfo(response, id.isAnimeId())
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		// MAL has no create endpoint, only a blind PUT that would zero the score and force "reading",
		// so an entry already on the website has to be picked up before it gets written over.
		findExistingRate(scrobblerMangaId)?.let {
			saveRate(it, mangaId, scrobblerMangaId)
			return true
		}
		val body = FormBody.Builder()
			.add("status", if (scrobblerMangaId.isAnimeId()) "watching" else "reading")
			.add("score", "0")
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(scrobblerMangaId.segment())
			.addPathSegment(scrobblerMangaId.realId().toString())
			.addPathSegment("my_list_status")
			.addQueryParameter("fields", "synopsis")
			.build()
		val request = Request.Builder()
			.url(url)
			.put(body.build())
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId, scrobblerMangaId)
		return false
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(entity.targetId.segment())
			.addPathSegment(entity.targetId.realId().toString())
			.addQueryParameter("fields", "my_list_status")
			.build()
		val response = okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
		return saveRate(response.getJSONObject("my_list_status"), entity.mangaId, entity.targetId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val body = FormBody.Builder()
			.add(if (rateId.toLong().isAnimeId()) "num_watched_episodes" else "num_chapters_read", chapter.toString())
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(rateId.toLong().segment())
			.addPathSegment(rateId.toLong().realId().toString())
			.addPathSegment("my_list_status")
			.build()
		val request = Request.Builder()
			.url(url)
			.put(body.build())
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId, rateId.toLong())
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	) {
		val body = FormBody.Builder()
			.add("status", status.toString().let { st -> if (rateId.toLong().isAnimeId()) st.toAnimeStatus() else st })
			.add("score", rating.toInt().toString())
			.add("comments", comment.orEmpty())
		if (setStartDate) {
			body.add("start_date", LocalDate.now().toString())
		}
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(rateId.toLong().segment())
			.addPathSegment(rateId.toLong().realId().toString())
			.addPathSegment("my_list_status")
			.build()
		val request = Request.Builder()
			.url(url)
			.put(body.build())
			.build()
		val response = okHttp.newCall(request).await().parseJson()
		saveRate(response, mangaId, rateId.toLong())
	}

	/** `null` when the series is not on the user's list yet, in which case MAL omits `my_list_status`. */
	private suspend fun findExistingRate(scrobblerMangaId: Long): JSONObject? {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment(scrobblerMangaId.segment())
			.addPathSegment(scrobblerMangaId.realId().toString())
			.addQueryParameter("fields", "my_list_status")
			.build()
		val response = okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
		return response.optJSONObject("my_list_status")
	}

	private suspend fun saveRate(
		json: JSONObject,
		mangaId: Long,
		scrobblerMangaId: Long,
	): ScrobblingEntity {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.MAL.id,
			id = scrobblerMangaId.toInt(),
			mangaId = mangaId,
			targetId = scrobblerMangaId,
			// MAL omits empty fields from my_list_status, so every field must be optional
			// Anime lists say "watching" / "plan_to_watch"; the rest of the app only knows the reading words
			status = json.getStringOrNull("status").orEmpty().toReadingStatus(),
			chapter = if (scrobblerMangaId.isAnimeId()) json.optInt("num_episodes_watched") else json.optInt("num_chapters_read"),
			comment = json.getStringOrNull("comments"),
			rating = (json.optDouble("score", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun getReadingList(limit: Int, anime: Boolean): List<TrackerReadingEntry> {
		val url = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("users")
			.addPathSegment("@me")
			.addPathSegment(if (anime) "animelist" else "mangalist")
			.addQueryParameter("status", if (anime) "watching" else "reading")
			.addQueryParameter("sort", "list_updated_at")
			.addQueryParameter("limit", limit.coerceIn(1, 100).toString())
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", if (anime) ANIME_LIST_FIELDS else LIST_FIELDS)
			.build()
		val response = okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		return response.getJSONArray("data").mapJSONNotNull { toEntry(it, anime) }.take(limit)
	}

	/** Every status on the account, following MAL's `paging.next` links until the list ends. */
	override suspend fun getLibrary(anime: Boolean): List<TrackerReadingEntry> {
		val result = ArrayList<TrackerReadingEntry>()
		var next: okhttp3.HttpUrl? = BASE_API_URL.toHttpUrl().newBuilder()
			.addPathSegment("users")
			.addPathSegment("@me")
			.addPathSegment(if (anime) "animelist" else "mangalist")
			.addQueryParameter("sort", "list_updated_at")
			.addQueryParameter("limit", "100")
			.addQueryParameter("nsfw", "true")
			.addQueryParameter("fields", if (anime) ANIME_LIST_FIELDS else LIST_FIELDS)
			.build()
		var pages = 0
		while (next != null && pages < 30) {
			val response = okHttp.newCall(Request.Builder().url(next).get().build()).await().parseJson()
			check(response.has("data")) { "Invalid response: \"$response\"" }
			result += response.getJSONArray("data").mapJSONNotNull { toEntry(it, anime) }
			next = response.optJSONObject("paging")?.getStringOrNull("next")?.toHttpUrlOrNull()
			pages++
		}
		return result
	}

	private fun toEntry(jo: JSONObject, anime: Boolean): TrackerReadingEntry {
		val node = jo.getJSONObject("node")
		val status = jo.optJSONObject("list_status")
		val alt = node.optJSONObject("alternative_titles")
		val names = ArrayList<String>()
		names += node.getString("title")
		alt?.getStringOrNull("en")?.takeIf { it.isNotBlank() }?.let { names += it }
		alt?.getStringOrNull("ja")?.takeIf { it.isNotBlank() }?.let { names += it }
		alt?.optJSONArray("synonyms")?.let { arr ->
			for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { names += it }
		}
		val mediaType = node.optString("media_type")
		return TrackerReadingEntry(
			service = ScrobblerService.MAL,
			targetId = if (anime) node.getLong("id").asAnimeId() else node.getLong("id"),
			title = node.getString("title"),
			coverUrl = node.optJSONObject("main_picture")?.getStringOrNull("large"),
			chapter = (if (anime) status?.optInt("num_episodes_watched") else status?.optInt("num_chapters_read")) ?: 0,
			totalChapters = node.optInt(if (anime) "num_episodes" else "num_chapters"),
			url = "$BASE_WEB_URL/${if (anime) "anime" else "manga"}/${node.getLong("id")}",
			updatedAt = status?.getStringOrNull("updated_at")?.let {
				runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
			} ?: 0L,
			status = when (status?.optString("status")) {
				"reading", "watching" -> "READING"
				"plan_to_read", "plan_to_watch" -> "PLANNING"
				"completed" -> "COMPLETED"
				"on_hold" -> "PAUSED"
				"dropped" -> "DROPPED"
				else -> "READING"
			},
			altTitles = names.distinct(),
			isNovel = !anime && (mediaType == "novel" || mediaType == "light_novel"),
			isAnime = anime,
		)
	}

	/**
	 * Rankings, the current season and search from MAL's public catalogue, as [MetadataTitle]s. MAL has no
	 * "trending" and no recent-manga list, so [kind] maps to the closest ranking it does have; null means
	 * MAL has nothing for that combination. The client id header makes this work without signing in.
	 */
	suspend fun metaList(
		kind: String,
		novel: Boolean,
		anime: Boolean,
		limit: Int,
		hideAdult: Boolean,
		search: String? = null,
	): List<MetadataTitle> {
		val segment = if (anime) "anime" else "manga"
		val base = when {
			search != null -> "$BASE_API_URL/$segment".toHttpUrl().newBuilder().addQueryParameter("q", search)
			anime && kind == "RECENT" -> {
				val now = java.time.LocalDate.now()
				val season = when (now.monthValue) { in 1..3 -> "winter"; in 4..6 -> "spring"; in 7..9 -> "summer"; else -> "fall" }
				"$BASE_API_URL/anime/season/${now.year}/$season".toHttpUrl().newBuilder().addQueryParameter("sort", "anime_num_list_users")
			}
			kind == "RECENT" -> return emptyList()
			else -> {
				val type = when {
					anime -> if (kind == "TRENDING") "airing" else "bypopularity"
					novel -> "novels"
					else -> if (kind == "TRENDING") "favorite" else "bypopularity"
				}
				"$BASE_API_URL/$segment/ranking".toHttpUrl().newBuilder().addQueryParameter("ranking_type", type)
			}
		}
		val url = base
			.addQueryParameter("limit", limit.coerceAtMost(100).toString())
			.addQueryParameter("nsfw", (!hideAdult).toString())
			.addQueryParameter("fields", "alternative_titles,synopsis,genres,mean,nsfw,media_type")
			.build()
		val request = Request.Builder().url(url).header("X-MAL-CLIENT-ID", clientId).get().build()
		val response = okHttp.newCall(request).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		return response.getJSONArray("data").mapJSONNotNull { entry ->
			val node = entry.optJSONObject("node") ?: return@mapJSONNotNull null
			val mediaType = node.getStringOrNull("media_type").orEmpty()
			// manga rankings mix novels in; keep each tab to its own kind
			if (!anime && (mediaType in NOVEL_MEDIA_TYPES) != novel) return@mapJSONNotNull null
			val alt = node.optJSONObject("alternative_titles")
			val synonyms = alt?.optJSONArray("synonyms")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
			val id = node.getLong("id")
			val title = node.getString("title")
			MetadataTitle(
				id = id,
				title = title,
				names = (listOf(title, alt?.getStringOrNull("en"), alt?.getStringOrNull("ja")) + synonyms)
					.filterNotNull().filter { it.isNotBlank() }.distinct(),
				coverUrl = node.optJSONObject("main_picture")?.let { it.getStringOrNull("large") ?: it.getStringOrNull("medium") },
				bannerUrl = null,
				genres = node.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.getStringOrNull("name") } }.orEmpty(),
				isAdult = node.optString("nsfw") == "black",
				score = node.optDouble("mean").takeIf { !it.isNaN() && it > 0 }?.toFloat(),
				url = "$BASE_WEB_URL/$segment/$id",
				description = node.getStringOrNull("synopsis"),
			)
		}
	}

	private fun jsonToManga(json: JSONObject, sourceTitle: String, anime: Boolean = false): ScrobblerManga {
		val node = json.getJSONObject("node")
		val title = node.getString("title")
		return ScrobblerManga(
			id = if (anime) node.getLong("id").asAnimeId() else node.getLong("id"),
			name = title,
			altName = null,
			cover = node.optJSONObject("main_picture")?.getStringOrNull("large"),
			url = "$BASE_WEB_URL/${if (anime) "anime" else "manga"}/${node.getLong("id")}",
			isBestMatch = org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher.sameAny(
				listOf(sourceTitle),
				listOfNotNull(title, node.optJSONObject("alternative_titles")?.getStringOrNull("en")),
			),
		)
	}

	private fun ScrobblerMangaInfo(json: JSONObject, anime: Boolean = false) = ScrobblerMangaInfo(
		id = if (anime) json.getLong("id").asAnimeId() else json.getLong("id"),
		name = json.getString("title"),
		cover = json.optJSONObject("main_picture")?.getStringOrNull("large").orEmpty(),
		url = "$BASE_WEB_URL/${if (anime) "anime" else "manga"}/${json.getLong("id")}",
		descriptionHtml = json.getStringOrNull("synopsis").orEmpty(),
		totalChapters = json.optInt(if (anime) "num_episodes" else "num_chapters"),
	)

	@Suppress("FunctionName")
	private fun MALUser(json: JSONObject) = ScrobblerUser(
		id = json.getLong("id"),
		nickname = json.getString("name"),
		avatar = json.getStringOrNull("picture"),
		service = ScrobblerService.MAL,
	)

	private fun generateCodeVerifier(): String {
		val codeVerifier = ByteArray(50)
		SecureRandom().nextBytes(codeVerifier)
		return Base64.encodeToString(codeVerifier, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
	}
}
