package org.koitharu.kotatsu.scrobbling.mangabaka.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.metadata.MetaList
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val REDIRECT_URI = "kotatsu://mangabaka-auth"
private const val BASE_WEB_URL = "https://mangabaka.org"
private const val BASE_API_URL = "https://api.mangabaka.org/v1"
private const val OAUTH_URL = "$BASE_WEB_URL/auth/oauth2"
private const val SCOPES = "library.read library.write offline_access openid"
private const val MANGA_PAGE_SIZE = 20
private const val RATING_MAX = 100
private const val MAX_PAGE_LIMIT = 50
private const val TRENDING_POOL = 50

// MangaBaka's popularity is a rank (1 = best): ascending is "most popular first".
private const val SORT_POPULAR = "popularity_asc"
private const val SORT_NEWEST = "created_at_desc"
private val SMALL_WORDS = setOf("of", "the", "and")

@Singleton
class MangaBakaRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MANGABAKA) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.mangabaka_clientId)

	// unlike MAL, MangaBaka requires a conformant S256 challenge
	private val codeVerifier: String by lazy(::generateCodeVerifier)

	override val oauthUrl: String
		get() = "$OAUTH_URL/authorize".toHttpUrl().newBuilder()
			.addQueryParameter("client_id", clientId)
			.addQueryParameter("response_type", "code")
			.addQueryParameter("redirect_uri", REDIRECT_URI)
			.addQueryParameter("scope", SCOPES)
			.addQueryParameter("code_challenge", codeVerifier.toS256Challenge())
			.addQueryParameter("code_challenge_method", "S256")
			.build()
			.toString()

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
			.add("client_id", clientId)
			.add("redirect_uri", REDIRECT_URI)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("code", code)
			body.add("code_verifier", codeVerifier)
			body.add("code_challenge_method", "S256")
			body.add("scope", SCOPES)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken) { "Not authorized" })
		}
		val request = Request.Builder()
			.post(body.build())
			.url("$OAUTH_URL/token")
		val response = okHttp.newCall(request.build()).await().parseJson()
		storage.accessToken = response.getString("access_token")
		// rotation is optional: keep the old refresh token when the server doesn't issue a new one
		storage.refreshToken = response.getStringOrNull("refresh_token") ?: storage.refreshToken
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder()
			.get()
			.url("$BASE_API_URL/my/profile")
		val response = okHttp.newCall(request.build()).await().parseJson().getJSONObject("data")
		val id = response.getString("id")
		return ScrobblerUser(
			// MangaBaka user ids are opaque strings, ScrobblerStorage persists a Long
			id = id.hashCode().toLong(),
			nickname = response.getStringOrNull("nickname")
				?: response.getStringOrNull("preferred_username")
				?: id,
			avatar = response.getStringOrNull("avatar") ?: response.getStringOrNull("picture"),
			service = ScrobblerService.MANGABAKA,
		).also { storage.user = it }
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun unregister(mangaId: Long) {
		return db.getScrobblingDao().delete(ScrobblerService.MANGABAKA.id, mangaId)
	}

	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		// MangaBaka catalogues comics and novels only
		if (type.isAnime) return emptyList()
		val url = "$BASE_API_URL/series/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			// Mihon hardcodes type_not=novel; DropSauce reads novels too, so the tab decides
			.addQueryParameter(if (type.isNovel) "type" else "type_not", "novel")
			.addQueryParameter("limit", MANGA_PAGE_SIZE.toString())
			.addQueryParameter("page", (offset / MANGA_PAGE_SIZE + 1).toString())
			.build()
		val request = Request.Builder().url(url).get().build()
		val response = okHttp.newCall(request).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		return response.getJSONArray("data").mapJSONNotNull { jsonToManga(it, query) }
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val request = Request.Builder().url("$BASE_API_URL/series/$id").get()
		val json = okHttp.newCall(request.build()).await().parseJson().getJSONObject("data")
		return ScrobblerMangaInfo(
			id = json.getLong("id"),
			name = json.title(),
			cover = json.coverUrl().orEmpty(),
			url = "$BASE_WEB_URL/${json.getLong("id")}",
			descriptionHtml = json.getStringOrNull("description").orEmpty(),
			totalChapters = json.optInt("total_chapters"),
		)
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		// The POST forces state=reading, so an entry already in the library is taken as-is instead
		libraryEntry(scrobblerMangaId)?.let {
			saveLibraryEntry(mangaId, scrobblerMangaId, it, comment = null)
			return true
		}
		val body = JSONObject().put("state", "reading").toString().toJsonBody()
		val request = Request.Builder()
			.url("$BASE_API_URL/my/library/$scrobblerMangaId")
			.post(body)
			.build()
		okHttp.newCall(request).await().parseJson()
		// the create response carries no library data, so read it back
		fetchRate(mangaId, scrobblerMangaId, comment = null)
		return false
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity {
		return fetchRate(entity.mangaId, entity.targetId, entity.comment)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val current = requireEntity(mangaId)
		// no date here: this fires on every chapter read and would keep resetting the start date
		pushRate(mangaId, rateId.toLong(), current.status, chapter, current.rating, current.comment, false)
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	) {
		val current = requireEntity(mangaId)
		pushRate(
			mangaId = mangaId,
			targetId = rateId.toLong(),
			status = status ?: current.status,
			chapter = current.chapter,
			rating = rating / RATING_MAX,
			comment = comment,
			setStartDate = setStartDate,
		)
	}

	// MangaBaka has no anime
	override val supportsAnime: Boolean = false

	override suspend fun setProgress(entry: TrackerReadingEntry, chapter: Int): Boolean {
		// Only the two keys we own are sent: MangaBaka nulls out any key present in the PUT body, so
		// rating, dates and privacy stay as the user set them. "rereading" is reported as READING by
		// the library list and is written back as plain "reading".
		val state = when (entry.status) {
			"COMPLETED" -> "completed"
			"PAUSED" -> "paused"
			"DROPPED" -> "dropped"
			else -> "reading"
		}
		val json = JSONObject()
			.put("state", state)
			.put("progress_chapter", chapter)
		val request = Request.Builder()
			.url("$BASE_API_URL/my/library/${entry.targetId}")
			.put(json.toString().toJsonBody())
			.build()
		okHttp.newCall(request).await().parseJson()
		return true
	}

	private suspend fun requireEntity(mangaId: Long) = requireNotNull(
		db.getScrobblingDao().find(ScrobblerService.MANGABAKA.id, mangaId),
	) { "Scrobbling info for manga $mangaId not found" }

	/**
	 * Only the fields we own are sent: MangaBaka nulls out any key present in the PUT body, so
	 * omitting `is_private`, `finish_date` — and `start_date` unless [setStartDate] — keeps what the
	 * user set on the site.
	 */
	private suspend fun pushRate(
		mangaId: Long,
		targetId: Long,
		status: String?,
		chapter: Int,
		rating: Float,
		comment: String?,
		setStartDate: Boolean,
	): ScrobblingEntity {
		val json = JSONObject()
			.put("state", status ?: "reading")
			.put("progress_chapter", if (chapter > 0) chapter else JSONObject.NULL)
			.put("rating", (rating * RATING_MAX).toInt().coerceIn(0, RATING_MAX).takeIf { it > 0 } ?: JSONObject.NULL)
		if (setStartDate) {
			json.put("start_date", LocalDate.now().toString())
		}
		val body = json.toString().toJsonBody()
		val request = Request.Builder()
			.url("$BASE_API_URL/my/library/$targetId")
			.put(body)
			.build()
		okHttp.newCall(request).await().parseJson()
		return saveRate(mangaId, targetId, status, chapter, rating, comment)
	}

	private suspend fun fetchRate(mangaId: Long, targetId: Long, comment: String?): ScrobblingEntity {
		val json = checkNotNull(libraryEntry(targetId)) { "Series $targetId is not in the library" }
		return saveLibraryEntry(mangaId, targetId, json, comment)
	}

	/** `null` when the series is not in the user's library yet. */
	private suspend fun libraryEntry(targetId: Long): JSONObject? {
		val request = Request.Builder().url("$BASE_API_URL/my/library/$targetId").get().build()
		val response = okHttp.newCall(request).await()
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		return response.parseJson().getJSONObject("data")
	}

	private suspend fun saveLibraryEntry(
		mangaId: Long,
		targetId: Long,
		json: JSONObject,
		comment: String?,
	) = saveRate(
		mangaId = mangaId,
		targetId = targetId,
		status = json.getStringOrNull("state"),
		chapter = json.optDouble("progress_chapter", 0.0).takeUnless { it.isNaN() }?.toInt() ?: 0,
		rating = (json.optInt("rating", 0).toFloat() / RATING_MAX).coerceIn(0f, 1f),
		comment = comment,
	)

	private suspend fun saveRate(
		mangaId: Long,
		targetId: Long,
		status: String?,
		chapter: Int,
		rating: Float,
		comment: String?,
	): ScrobblingEntity {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.MANGABAKA.id,
			id = targetId.toInt(),
			mangaId = mangaId,
			targetId = targetId,
			status = status,
			chapter = chapter,
			// MangaBaka has no notes field, comments stay local
			comment = comment,
			rating = rating.coerceIn(0f, 1f),
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	/**
	 * `/my/library` returns every state in one list (rows carry `state`, `progress_chapter` and the
	 * series under a capitalised `Series` key), so "reading" is filtered here.
	 */
	override suspend fun getReadingList(limit: Int, anime: Boolean): List<TrackerReadingEntry> =
		// MangaBaka has no anime
		if (anime) emptyList() else
		fetchLibrary().filter { it.status == "READING" }.take(limit)

	override suspend fun getLibrary(anime: Boolean): List<TrackerReadingEntry> =
		if (anime) emptyList() else fetchLibrary()

	private suspend fun fetchLibrary(): List<TrackerReadingEntry> {
		val request = Request.Builder().url("$BASE_API_URL/my/library").get().build()
		val rows = okHttp.newCall(request).await().parseJson().getJSONArray("data")
		val result = ArrayList<TrackerReadingEntry>()
		for (i in 0 until rows.length()) {
			val row = rows.optJSONObject(i) ?: continue
			val series = row.optJSONObject("Series") ?: row.optJSONObject("series") ?: continue
			val id = series.optLong("id", row.optLong("series_id", 0L))
			if (id == 0L) continue
			val names = listOfNotNull(
				series.getStringOrNull("title"),
				series.getStringOrNull("romanized_title"),
				series.getStringOrNull("native_title"),
			).distinct()
			result += TrackerReadingEntry(
				service = ScrobblerService.MANGABAKA,
				targetId = id,
				title = series.title(),
				coverUrl = series.coverUrl(),
				chapter = row.optDouble("progress_chapter", 0.0).takeUnless { it.isNaN() }?.toInt() ?: 0,
				totalChapters = series.optInt("total_chapters"),
				url = "$BASE_WEB_URL/$id",
				// the list has no activity timestamp we can rely on; keep the server's order
				updatedAt = (rows.length() - i).toLong(),
				status = when (row.getStringOrNull("state")) {
					"reading", "rereading" -> "READING"
					"completed" -> "COMPLETED"
					"paused", "on_hold" -> "PAUSED"
					"dropped" -> "DROPPED"
					else -> "PLANNING"
				},
				altTitles = names,
			)
		}
		return result
	}

	/**
	 * MangaBaka's catalogue as [MetadataTitle]s: a search when [search] is given, otherwise the chart [kind].
	 * MangaBaka covers comics and novels only, so there is nothing for anime.
	 *
	 * `popularity` on MangaBaka is a RANK (1 = most popular), so "most popular" is `popularity_asc`; the old
	 * `popularity_desc` returned the least popular series first, which is where the "unknown title" rows came from.
	 */
	suspend fun metaList(
		novel: Boolean,
		limit: Int,
		hideAdult: Boolean,
		search: String? = null,
		page: Int = 1,
		kind: MetaList = MetaList.POPULAR,
	): List<MetadataTitle> {
		// Trending is derived from the rank movement over 7 days, so it needs a pool to re-order.
		val fetch = if (search == null && kind == MetaList.TRENDING) maxOf(limit * 3, TRENDING_POOL) else limit
		val url = "$BASE_API_URL/series/search".toHttpUrl().newBuilder().apply {
			if (search != null) {
				addQueryParameter("q", search)
			} else {
				addQueryParameter(
					"sort_by",
					when (kind) {
						MetaList.POPULAR, MetaList.TRENDING -> SORT_POPULAR
						MetaList.RECENT -> SORT_NEWEST
					},
				)
			}
			addQueryParameter(if (novel) "type" else "type_not", "novel")
			addQueryParameter("limit", fetch.coerceAtMost(MAX_PAGE_LIMIT).toString())
			if (page > 1) addQueryParameter("page", page.toString())
		}.build()
		val response = okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
		check(response.has("data")) { "Invalid response: \"$response\"" }
		val data = response.getJSONArray("data")
		val items = ArrayList<Pair<MetadataTitle, Int>>()
		for (i in 0 until data.length()) {
			val json = data.optJSONObject(i) ?: continue
			val title = runCatching { metadataOf(json) }.getOrNull() ?: continue
			// a row with no real title or no cover is a stub; it would show as "unknown title" with a blank card
			if (search == null && (title.title.isPlaceholderTitle() || title.coverUrl == null)) continue
			if (hideAdult && title.isAdult) continue
			items += title to json.rankGain()
		}
		val ordered = if (search == null && kind == MetaList.TRENDING) {
			// biggest climb in the last 7 days first; stable, so rows without history keep the popularity order
			items.sortedByDescending { it.second }
		} else {
			items
		}
		return ordered.map { it.first }.take(limit)
	}

	/** One exact MangaBaka series by id, for titles already tracked there. */
	suspend fun metadataById(id: Long): MetadataTitle? {
		val response = okHttp.newCall(Request.Builder().url("$BASE_API_URL/series/$id").get().build()).await().parseJson()
		// the single-series endpoint wraps the series in "data"; tolerate a bare object too
		val json = response.optJSONObject("data") ?: response.takeIf { it.has("id") } ?: return null
		return metadataOf(json)
	}

	private fun metadataOf(json: JSONObject): MetadataTitle {
		val id = json.getLong("id")
		val title = json.title()
		val rating = json.getStringOrNull("content_rating").orEmpty()
		val adult = rating == "pornographic" || rating == "erotica"
		// tags_v2 carries is_genre / is_spoiler; the flat genres / tags arrays are deprecated and snake_case
		val tagsV2 = json.optJSONArray("tags_v2").objects()
		val genres = tagsV2.filter { it.optBoolean("is_genre") }.mapNotNull { it.getStringOrNull("name") }
			.ifEmpty { json.optJSONArray("genres").strings().map { it.prettyGenre() } }
		val tags = tagsV2.filter { !it.optBoolean("is_genre") && !it.optBoolean("is_spoiler") }
			.mapNotNull { it.getStringOrNull("name") }
			.ifEmpty { json.optJSONArray("tags").strings() }
		val allTitles = json.titleList()
		val start = json.optJSONObject("published")?.getStringOrNull("start_date")
		return MetadataTitle(
			id = id,
			title = title,
			names = (listOf(title) + allTitles).filter { it.isNotBlank() && !it.isPlaceholderTitle() }.distinct(),
			coverUrl = json.coverUrl(),
			bannerUrl = null,
			genres = genres.distinct(),
			isAdult = adult,
			// MangaBaka rates 0-100
			score = json.optDouble("rating").takeIf { !it.isNaN() && it > 0 }?.let { (it / 10.0).toFloat() },
			url = "$BASE_WEB_URL/$id",
			description = json.getStringOrNull("description"),
			// MangaBaka types: manga, manhwa, manhua, novel, oel, other
			format = json.getStringOrNull("type"),
			country = json.getStringOrNull("original_language")?.toCountry(),
			tags = tags.distinct(),
			year = start?.take(4)?.toIntOrNull() ?: json.optInt("year").takeIf { it > 0 },
			status = json.getStringOrNull("status")?.takeIf { it != "unknown" }?.uppercase()?.let {
				if (it == "UPCOMING") "NOT_YET_RELEASED" else it
			},
			// total_chapters / final_volume are strings on the API
			chapters = json.optString("total_chapters").toFloatOrNull()?.toInt()?.takeIf { it > 0 },
			volumes = json.optString("final_volume").toFloatOrNull()?.toInt()?.takeIf { it > 0 },
			authors = (json.optJSONArray("authors").strings() + json.optJSONArray("artists").strings()).distinct(),
		)
	}

	/** How far up the rank list the series moved in 7 days (rank 1 is best, so a bigger old rank means a climb). */
	private fun JSONObject.rankGain(): Int {
		val global = optJSONObject("popularity")?.optJSONObject("global") ?: return 0
		val now = global.optInt("current", 0).takeIf { it > 0 } ?: return 0
		val before = global.optJSONObject("history")?.optInt("1w", 0)?.takeIf { it > 0 } ?: return 0
		return before - now
	}

	private fun JSONArray?.objects(): List<JSONObject> =
		this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

	private fun JSONArray?.strings(): List<String> =
		this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()

	/** "slice_of_life" -> "Slice of Life", "sci-fi" -> "Sci-Fi". */
	private fun String.prettyGenre(): String = split('_', ' ').joinToString(" ") { w ->
		w.split('-').joinToString("-") { p -> if (p in SMALL_WORDS) p else p.replaceFirstChar(Char::uppercase) }
	}.replaceFirstChar(Char::uppercase)

	private fun String.toCountry(): String? = when {
		this == "ja" -> "JP"
		this == "ko" -> "KR"
		this.startsWith("zh") && (endsWith("TW") || endsWith("HK")) -> "TW"
		this.startsWith("zh") || this.startsWith("yue") -> "CN"
		else -> null
	}

	private fun String.isPlaceholderTitle() = startsWith("unknown title", ignoreCase = true)

	/** Every real title in the new `titles` array (it replaces title / native_title / romanized_title). */
	private fun JSONObject.titleList(): List<String> {
		val fromArray = optJSONArray("titles").objects().mapNotNull { it.getStringOrNull("title") }
		val legacy = listOfNotNull(
			getStringOrNull("title"),
			getStringOrNull("romanized_title"),
			getStringOrNull("native_title"),
		)
		val secondary = optJSONObject("secondary_titles")?.let { o ->
			o.keys().asSequence().flatMap { k -> o.optJSONArray(k).objects().mapNotNull { it.getStringOrNull("title") } }.toList()
		}.orEmpty()
		return (fromArray + legacy + secondary).filter { it.isNotBlank() && !it.isPlaceholderTitle() }.distinct()
	}

	private fun jsonToManga(json: JSONObject, sourceTitle: String): ScrobblerManga {
		val title = json.title()
		return ScrobblerManga(
			id = json.getLong("id"),
			name = title,
			altName = json.getStringOrNull("native_title"),
			cover = json.coverUrl(),
			url = "$BASE_WEB_URL/${json.getLong("id")}",
			isBestMatch = org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher.sameAny(
				listOf(sourceTitle),
				listOfNotNull(title, json.getStringOrNull("romanized_title"), json.getStringOrNull("native_title")),
			),
		)
	}

	/**
	 * The best display title: the English primary title, then any English one, then a romanization, then whatever
	 * exists. The API's own placeholder ("unknown title (please report)") is never shown while a real name exists.
	 */
	private fun JSONObject.title(): String {
		val list = optJSONArray("titles").objects().filter { o ->
			o.getStringOrNull("title")?.let { it.isNotBlank() && !it.isPlaceholderTitle() } == true
		}
		fun JSONObject.isEn() = getStringOrNull("language") == "en"
		fun JSONObject.primary() = optBoolean("is_primary")
		fun JSONObject.official() = optJSONArray("traits").strings().contains("official")
		fun JSONObject.latin() = getStringOrNull("language")?.endsWith("Latn") == true
		val picked = list.firstOrNull { it.isEn() && it.primary() }
			?: list.firstOrNull { it.isEn() && it.official() }
			?: list.firstOrNull { it.isEn() }
			?: list.firstOrNull { it.latin() && it.primary() }
			?: list.firstOrNull { it.latin() }
			?: list.firstOrNull { it.primary() }
			?: list.firstOrNull()
		return picked?.getStringOrNull("title")
			?: titleList().firstOrNull()
			?: getStringOrNull("title")
			?: "#${getLong("id")}"
	}

	private fun JSONObject.coverUrl(): String? = optJSONObject("cover")?.let { c ->
		c.optJSONObject("x250")?.getStringOrNull("x1")
			?: c.optJSONObject("x350")?.getStringOrNull("x1")
			?: c.optJSONObject("raw")?.getStringOrNull("url")
	}

	private fun String.toJsonBody() = toRequestBody("application/json".toMediaType())

	private fun generateCodeVerifier(): String {
		val bytes = ByteArray(50)
		SecureRandom().nextBytes(bytes)
		return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
	}

	private fun String.toS256Challenge(): String = Base64.encodeToString(
		MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.US_ASCII)),
		Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
	)
}
