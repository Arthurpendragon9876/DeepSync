package org.koitharu.kotatsu.scrobbling.simkl.data

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Registered with Simkl for this app; must match the redirect URL on simkl.com/settings/developer exactly. */
private const val REDIRECT_URI = "com.kotatsu.deepsync://oauth/simkl"
private const val API_URL = "https://api.simkl.com"
private const val WEB_URL = "https://simkl.com"
private const val POSTER_URL = "https://simkl.in/posters"
private const val FANART_URL = "https://simkl.in/fanart"
private const val MAX_EPISODES_PER_PUSH = 200
private val JSON_TYPE = "application/json".toMediaType()

/**
 * Simkl: the metadata source for TV & Movies (public lists need only the client id; search and details need the signed-in user, AUTH V2) and an account
 * for tracking anime, shows and movies (OAuth 2.0 with PKCE, no client secret).
 *
 * Simkl numbers every title once whatever its type, so ids are Simkl ids. Access tokens last 7 days and are
 * refreshed by SimklInterceptor. The list calls are limited to 1 POST/s and 500 requests a day for a free account.
 *
 * Statuses are stored in the app's reading words (reading, plan_to_read, on_hold) like MAL's.
 */
@Singleton
class SimklRepository @Inject constructor(
	@ApplicationContext context: Context,
	@ScrobblerType(ScrobblerService.SIMKL) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.SIMKL) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.simkl_clientId)
	private val codeVerifier: String by lazy(::generateCodeVerifier)

	/** The account's whole list, fetched once and reused for a minute so a burst of updates costs one request. */
	@Volatile private var libraryCache: Pair<Long, List<JSONObject>>? = null

	/** Which Simkl catalogue (tv, anime, movies) an id was found in; the web page and the list calls depend on it. */
	private val kinds = ConcurrentHashMap<Long, String>()

	// ---- account ---------------------------------------------------------------------------------

	override val oauthUrl: String
		// AUTH V2: the registration made at simkl.com/settings/developer is an OAuth 2.0 app, which only works on
		// /oauth2/authorize (the old /oauth/authorize answers "unauthorized_client"). PKCE with S256 is mandatory.
		get() = "$WEB_URL/oauth2/authorize".toHttpUrl().newBuilder()
			.addQueryParameter("response_type", "code")
			.addQueryParameter("client_id", clientId)
			.addQueryParameter("redirect_uri", REDIRECT_URI)
			.addQueryParameter("scope", "media:read media:write")
			.addQueryParameter("code_challenge", codeVerifier.toS256Challenge())
			.addQueryParameter("code_challenge_method", "S256")
			.build()
			.toString()

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun authorize(code: String?) {
		// Access tokens last 7 days; the refresh token (180 days, sliding) is used by SimklInterceptor on a 401
		checkNotNull(code) { "Not authorized" }
		val body = FormBody.Builder()
			.add("grant_type", "authorization_code")
			.add("client_id", clientId)
			.add("code", code)
			.add("redirect_uri", REDIRECT_URI)
			.add("code_verifier", codeVerifier)
			.build()
		val request = Request.Builder().url("$API_URL/oauth2/token").post(body).build()
		val response = okHttp.newCall(request).await().parseJson()
		storage.accessToken = response.getString("access_token")
		storage.refreshToken = response.optString("refresh_token").takeIf { it.isNotBlank() }
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder().url("$API_URL/users/settings").post("{}".toRequestBody(JSON_TYPE)).build()
		val response = okHttp.newCall(request).await().parseJson()
		val user = response.optJSONObject("user")
		val account = response.optJSONObject("account")
		return ScrobblerUser(
			id = account?.optLong("id", 0L) ?: 0L,
			nickname = user?.optString("name").orEmpty().ifBlank { "Simkl" },
			avatar = user?.optString("avatar")?.takeIf { it.isNotBlank() && it != "null" },
			service = ScrobblerService.SIMKL,
		).also { storage.user = it }
	}

	override fun logout() {
		storage.clear()
		libraryCache = null
	}

	override suspend fun unregister(mangaId: Long) {
		db.getScrobblingDao().delete(ScrobblerService.SIMKL.id, mangaId)
	}

	// ---- catalogue for linking ---------------------------------------------------------------------

	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		// Simkl's search is a single page
		if (offset > 0) return emptyList()
		return findCandidates(query, type).sortedByDescending { it.score }.map { it.manga }
	}

	/**
	 * Shows, anime and movies side by side, each with what tells namesakes apart: first release year, whether it is a
	 * movie / a series / an anime, the country, and every name Simkl has (English, romaji, also-known-as). "The Odyssey"
	 * alone matches a 1997 miniseries and a 2026 film; without these the wrong one gets linked.
	 */
	override suspend fun findCandidates(query: String, type: ScrobblerMangaType): List<TrackerCandidate> {
		// Simkl knows anime, shows and movies only
		if (!type.isAnime) return emptyList()
		val hits = coroutineScope {
			listOf("tv", "anime", "movies").map { kind ->
				async { runCatching { searchType(kind, query, 10) }.getOrDefault(emptyList()).map { kind to it } }
			}.awaitAll()
		}.flatten().distinctBy { (_, title) -> title.id }
		return hits.map { (kind, title) ->
			kinds[title.id] = kind
			val score = TitleMatcher.bestScore(listOf(query), title.names)
			TrackerCandidate(
				manga = ScrobblerManga(
					id = title.id,
					name = title.title,
					altName = title.names.firstOrNull { n -> n != title.title },
					cover = title.coverUrl,
					url = title.url,
					isBestMatch = score >= 1f,
					details = detailsOf(kind, title),
				),
				names = title.names,
				score = score,
				matchedName = title.names.maxByOrNull { TitleMatcher.score(query, it) },
				format = candidateFormat(kind, title),
				country = title.country,
				labels = title.genres,
				year = title.year,
			)
		}
	}

	/** MOVIE, TV or ANIME: what the matcher compares, whichever catalogue the entry came from. */
	private fun candidateFormat(kind: String, title: MetadataTitle): String = when {
		kind == "movies" || title.format.equals("MOVIE", ignoreCase = true) -> MetadataKinds.MOVIE
		kind == "anime" -> MetadataKinds.ANIME
		else -> MetadataKinds.TV
	}

	/** "2026 · Movie · United Kingdom" */
	private fun detailsOf(kind: String, title: MetadataTitle): String? {
		val label = when (candidateFormat(kind, title)) {
			MetadataKinds.MOVIE -> if (kind == "anime") "Anime movie" else "Movie"
			MetadataKinds.ANIME -> title.format?.takeIf { it !in setOf("TV", "ANIME") }
				?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Anime"
			else -> "TV show"
		}
		val country = title.country?.let(MetadataKinds::countryName)
		return listOfNotNull(title.year?.toString(), label, country).joinToString(" · ")
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val entry = fullEntry(id)
		val year = entry?.optInt("year", 0)?.takeIf { it > 0 }
		return ScrobblerMangaInfo(
			id = id,
			// The year says which "The Odyssey" is linked, in the tracking sheet as well as in the picker
			name = entry?.optString("title").orEmpty().let { if (year != null && it.isNotEmpty()) "$it ($year)" else it },
			cover = entry?.optString("poster")?.takeIf { it.isNotBlank() && it != "null" }?.let { "$POSTER_URL/${it}_m.jpg" }.orEmpty(),
			url = "$WEB_URL/${kinds[id] ?: "tv"}/$id",
			descriptionHtml = entry?.optString("overview").orEmpty(),
			totalChapters = entry?.optInt("total_episodes", 0) ?: 0,
		)
	}

	/** The full entry of one title; the endpoint depends on the kind, which a bare id does not say. */
	private suspend fun fullEntry(id: Long): JSONObject? {
		for (kind in listOf("tv", "anime", "movies")) {
			val url = "$API_URL/$kind/$id".toHttpUrl().newBuilder().addQueryParameter("extended", "full").build()
			val json = runCatching { okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson() }.getOrNull()
			if (json != null && json.optString("title").isNotBlank()) {
				kinds[id] = kind
				return json
			}
		}
		return null
	}

	/** Whether [id] is a movie. Searches remember it; otherwise the entry is looked up once. */
	private suspend fun isMovie(id: Long): Boolean {
		if (kinds[id] == null) fullEntry(id)
		return kinds[id] == "movies"
	}

	// ---- the account's list ----------------------------------------------------------------------

	private suspend fun library(force: Boolean = false): List<JSONObject> {
		libraryCache?.let { (at, list) -> if (!force && System.currentTimeMillis() - at < 60_000L) return list }
		val result = ArrayList<JSONObject>()
		for (kind in listOf("anime", "shows")) {
			val url = "$API_URL/sync/all-items/$kind/".toHttpUrl().newBuilder().addQueryParameter("extended", "full").build()
			val json = runCatching { okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson() }.getOrNull()
				?: continue
			val array = json.optJSONArray(kind) ?: continue
			// Remember which list it came from: Simkl's anime and shows both land in Streaming, in different halves
			for (i in 0 until array.length()) array.optJSONObject(i)?.let { it.put("_kind", kind); result.add(it) }
		}
		libraryCache = System.currentTimeMillis() to result
		return result
	}

	private fun JSONObject.simklId(): Long =
		optJSONObject("show")?.optJSONObject("ids")?.optLong("simkl", 0L) ?: 0L

	private suspend fun findExisting(id: Long, force: Boolean = false): JSONObject? =
		library(force).firstOrNull { it.simklId() == id }

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		// An entry already on the account is adopted as it is, never overwritten
		findExisting(scrobblerMangaId, force = true)?.let {
			saveRate(it, mangaId, scrobblerMangaId)
			return true
		}
		post("/sync/add-to-list", listBody(scrobblerMangaId, "watching"))
		libraryCache = null
		saveEntity(mangaId, scrobblerMangaId, "reading", 0, null, 0f)
		return false
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity {
		val json = findExisting(entity.targetId, force = true) ?: return entity
		return saveRate(json, entity.mangaId, entity.targetId)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val id = rateId.toLong()
		pushEpisodes(id, chapter)
		libraryCache = null
		val json = findExisting(id, force = true)
		if (json != null) saveRate(json, mangaId, id) else saveEntity(mangaId, id, "reading", chapter, null, 0f)
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	) {
		val id = rateId.toLong()
		if (status != null) post("/sync/add-to-list", listBody(id, status.toSimklStatus()))
		val score = rating.toInt().coerceIn(0, 10)
		if (score > 0) {
			val item = JSONObject().put("ids", JSONObject().put("simkl", id)).put("rating", score)
			post("/sync/ratings", JSONObject().put(if (isMovie(id)) "movies" else "shows", JSONArray().put(item)))
		}
		libraryCache = null
		val json = findExisting(id, force = true)
		if (json != null) {
			saveRate(json, mangaId, id)
		} else {
			saveEntity(mangaId, id, status ?: "reading", 0, comment, rating / 10f)
		}
	}

	override suspend fun setProgress(entry: TrackerReadingEntry, chapter: Int): Boolean {
		pushEpisodes(entry.targetId, chapter)
		if (entry.status == "PLANNING") post("/sync/add-to-list", listBody(entry.targetId, "watching"))
		libraryCache = null
		return true
	}

	/** Marks episodes up to [chapter] as watched, sending only the ones the account does not have yet. */
	private suspend fun pushEpisodes(id: Long, chapter: Int) {
		if (isMovie(id)) {
			// A movie has no episodes: watching it is a single mark
			if (chapter >= 1) {
				val item = JSONObject().put("ids", JSONObject().put("simkl", id))
				post("/sync/history", JSONObject().put("movies", JSONArray().put(item)))
			}
			return
		}
		val watched = findExisting(id)?.optInt("watched_episodes_count", 0) ?: 0
		if (chapter <= watched) return
		val episodes = JSONArray()
		for (n in (watched + 1)..chapter.coerceAtMost(watched + MAX_EPISODES_PER_PUSH)) {
			episodes.put(JSONObject().put("number", n))
		}
		val season = JSONObject().put("number", 1).put("episodes", episodes)
		val item = JSONObject().put("ids", JSONObject().put("simkl", id)).put("seasons", JSONArray().put(season))
		post("/sync/history", JSONObject().put("shows", JSONArray().put(item)))
	}

	private suspend fun listBody(id: Long, status: String): JSONObject {
		val movie = isMovie(id)
		// Movies have no "watching" or "hold": they are planned, completed or dropped
		val to = if (movie && (status == "watching" || status == "hold")) "plantowatch" else status
		val item = JSONObject().put("to", to).put("ids", JSONObject().put("simkl", id))
		return JSONObject().put(if (movie) "movies" else "shows", JSONArray().put(item))
	}

	private suspend fun post(path: String, body: JSONObject) {
		val request = Request.Builder().url(API_URL + path).post(body.toString().toRequestBody(JSON_TYPE)).build()
		okHttp.newCall(request).await().close()
	}

	private suspend fun saveRate(json: JSONObject, mangaId: Long, id: Long): ScrobblingEntity = saveEntity(
		mangaId = mangaId,
		id = id,
		status = json.optString("status").toReadingStatus(),
		chapter = json.optInt("watched_episodes_count", 0),
		comment = null,
		rating = (json.optDouble("user_rating", 0.0).toFloat() / 10f).coerceIn(0f, 1f),
	)

	private suspend fun saveEntity(
		mangaId: Long,
		id: Long,
		status: String,
		chapter: Int,
		comment: String?,
		rating: Float,
	): ScrobblingEntity {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.SIMKL.id,
			id = id.toInt(),
			mangaId = mangaId,
			targetId = id,
			status = status,
			chapter = chapter,
			comment = comment,
			rating = rating,
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	override suspend fun getReadingList(limit: Int, anime: Boolean): List<TrackerReadingEntry> {
		if (!anime) return emptyList()
		return library().filter { it.optString("status") == "watching" }
			.sortedByDescending { it.optString("last_watched_at") }
			.take(limit)
			.mapNotNull(::toEntry)
	}

	override suspend fun getLibrary(anime: Boolean): List<TrackerReadingEntry> =
		if (anime) library(force = true).mapNotNull(::toEntry).filterNot { it.isTvShow } else emptyList()

	override suspend fun getTvLibrary(): List<TrackerReadingEntry> =
		library(force = true).mapNotNull(::toEntry).filter { it.isTvShow }

	private fun toEntry(json: JSONObject): TrackerReadingEntry? {
		val show = json.optJSONObject("show") ?: return null
		val id = json.simklId().takeIf { it > 0 } ?: return null
		val title = show.optString("title").takeIf { it.isNotBlank() } ?: return null
		return TrackerReadingEntry(
			service = ScrobblerService.SIMKL,
			targetId = id,
			title = title,
			coverUrl = show.optString("poster").takeIf { it.isNotBlank() && it != "null" }?.let { "$POSTER_URL/${it}_m.jpg" },
			chapter = json.optInt("watched_episodes_count", 0),
			totalChapters = json.optInt("total_episodes_count", 0),
			url = "$WEB_URL/tv/$id",
			updatedAt = 0L,
			status = when (json.optString("status")) {
				"plantowatch" -> "PLANNING"
				"completed" -> "COMPLETED"
				"hold" -> "PAUSED"
				"dropped" -> "DROPPED"
				else -> "READING"
			},
			altTitles = listOfNotNull(show.optString("en_title").takeIf { it.isNotBlank() }),
			isAnime = true,
			isTvShow = json.optString("_kind") == "shows",
		)
	}

	private fun String.toReadingStatus() = when (this) {
		"watching" -> "reading"
		"plantowatch" -> "plan_to_read"
		"hold" -> "on_hold"
		else -> this.ifEmpty { "reading" }
	}

	private fun String.toSimklStatus() = when (this) {
		"reading" -> "watching"
		"plan_to_read" -> "plantowatch"
		"on_hold" -> "hold"
		else -> this
	}

	private fun generateCodeVerifier(): String {
		val bytes = ByteArray(50)
		SecureRandom().nextBytes(bytes)
		return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
	}

	private fun String.toS256Challenge(): String = Base64.encodeToString(
		MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.US_ASCII)),
		Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
	)

	// ---- metadata (public, no sign-in needed) ------------------------------------------------------

	/** One exact entry by id, for a details page opened from a Simkl title. */
	suspend fun metadataById(id: Long): MetadataTitle? {
		for (kind in listOf("tv", "anime", "movies")) {
			val url = "$API_URL/$kind/$id".toHttpUrl().newBuilder()
				.addQueryParameter("client_id", clientId)
				.addQueryParameter("extended", "full")
				.build()
			val json = runCatching { okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson() }.getOrNull()
				?: continue
			if (json.optString("title").isBlank()) continue
			return titleOf(json, kind, id)
		}
		return null
	}

	/**
	 * Trending, popular or recent titles, or the results of [search]. With [tv] the list holds TV shows and movies
	 * side by side; otherwise [anime] picks anime. Anything else (manga, novels) is not on Simkl.
	 */
	suspend fun metaList(
		kind: String,
		tv: Boolean,
		anime: Boolean,
		limit: Int,
		search: String? = null,
	): List<MetadataTitle> {
		val types = when {
			tv -> listOf("tv", "movies")
			anime -> listOf("anime")
			else -> return emptyList()
		}
		val perType = if (types.size > 1) (limit + 1) / 2 + 2 else limit
		val lists = types.map { type ->
			runCatching {
				if (search != null) searchType(type, search, perType) else trendingType(type, kind, perType)
			}.getOrDefault(emptyList())
		}
		// Shows and movies take turns, so neither crowds the other out of a short row
		val out = ArrayList<MetadataTitle>(limit)
		var i = 0
		while (out.size < limit && lists.any { i < it.size }) {
			lists.forEach { list -> if (i < list.size && out.size < limit) out += list[i] }
			i++
		}
		return out.distinctBy { it.id }
	}

	private suspend fun trendingType(type: String, kind: String, limit: Int): List<MetadataTitle> {
		val interval = when (kind) {
			"POPULAR" -> "month"
			"RECENT" -> "today"
			else -> "week"
		}
		val url = "$API_URL/$type/trending".toHttpUrl().newBuilder()
			.addQueryParameter("client_id", clientId)
			.addQueryParameter("extended", "overview,metadata,tmdb,genres")
			.addQueryParameter("interval", interval)
			.build()
		return titlesOf(call(url), type, limit)
	}

	private suspend fun searchType(type: String, query: String, limit: Int): List<MetadataTitle> {
		val path = if (type == "movies") "movie" else type
		val url = "$API_URL/search/$path".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("client_id", clientId)
			.addQueryParameter("extended", "full")
			.addQueryParameter("limit", limit.coerceAtMost(50).toString())
			.build()
		return titlesOf(call(url), type, limit)
	}

	private suspend fun call(url: okhttp3.HttpUrl): JSONArray {
		val request = Request.Builder().url(url).get().build()
		return okHttp.newCall(request).await().parseJsonArray()
	}

	private fun titlesOf(array: JSONArray, type: String, limit: Int): List<MetadataTitle> {
		val out = ArrayList<MetadataTitle>()
		for (index in 0 until array.length()) {
			if (out.size >= limit) break
			val item = array.optJSONObject(index) ?: continue
			titleOf(item, type)?.let(out::add)
		}
		return out
	}

	private fun titleOf(item: JSONObject, type: String, fallbackId: Long = 0L): MetadataTitle? {
		val ids = item.optJSONObject("ids")
		val id = (ids?.optLong("simkl_id", 0L)?.takeIf { it > 0 } ?: ids?.optLong("simkl", 0L)?.takeIf { it > 0 }
			?: item.optLong("id", 0L).takeIf { it > 0 } ?: fallbackId.takeIf { it > 0 }) ?: return null
		val title = item.optString("title").takeIf { it.isNotBlank() } ?: return null
		val poster = item.optString("poster").takeIf { it.isNotBlank() && it != "null" }
		val fanart = item.optString("fanart").takeIf { it.isNotBlank() && it != "null" }
		val genres = item.optJSONArray("genres")?.let { g ->
			(0 until g.length()).mapNotNull { i ->
				// Either plain names or objects with a name, depending on the endpoint
				g.optString(i).takeIf { it.isNotBlank() && !it.startsWith("{") } ?: g.optJSONObject(i)?.optString("name")
			}.filter { it.isNotBlank() }
		}.orEmpty()
		val ratings = item.optJSONObject("ratings")
		val score = ratings?.optJSONObject("simkl")?.optDouble("rating")?.takeIf { !it.isNaN() && it > 0 }
			?: ratings?.optJSONObject("imdb")?.optDouble("rating")?.takeIf { !it.isNaN() && it > 0 }
		val year = item.optInt("year", 0).takeIf { it > 0 }
			?: item.optString("release_date").take(4).toIntOrNull()
		val path = item.optString("url").takeIf { it.startsWith("/") }
		return MetadataTitle(
			id = id,
			title = title,
			names = namesOf(item, title),
			coverUrl = poster?.let { "$POSTER_URL/${it}_m.jpg" },
			bannerUrl = fanart?.let { "$FANART_URL/${it}_medium.jpg" },
			genres = genres,
			isAdult = false,
			score = score?.toFloat(),
			url = if (path != null) WEB_URL + path else "$WEB_URL/$type/$id",
			description = item.optString("overview").takeIf { it.isNotBlank() },
			format = when (type) {
				"movies" -> "MOVIE"
				"tv" -> "TV"
				else -> item.optString("anime_type").takeIf { it.isNotBlank() }?.uppercase() ?: "ANIME"
			},
			year = year,
			country = MetadataKinds.isoCountry(item.optString("country").takeIf { it != "null" }),
		)
	}

	/**
	 * Every name Simkl has for an entry: the title, English and romaji titles, and the also-known-as list. The
	 * endpoints disagree on the keys, and "aka" is either plain strings or {lang, name} objects, so all are read.
	 */
	private fun namesOf(item: JSONObject, title: String): List<String> {
		val out = LinkedHashSet<String>()
		out += title
		for (key in listOf("en_title", "title_en", "title_romaji", "romaji_title", "original_title", "title_original")) {
			item.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(out::add)
		}
		for (key in listOf("aka", "alt_titles", "alternative_titles", "title_aka")) {
			val array = item.optJSONArray(key) ?: continue
			for (i in 0 until array.length()) {
				val name = array.optJSONObject(i)?.optString("name") ?: array.optString(i)
				if (!name.isNullOrBlank() && name != "null" && !name.startsWith("{")) out += name
			}
		}
		return out.toList()
	}
}
