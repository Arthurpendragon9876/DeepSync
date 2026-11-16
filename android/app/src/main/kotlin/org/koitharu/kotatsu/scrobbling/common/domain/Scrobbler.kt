package org.koitharu.kotatsu.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.metadata.MetadataMatcher
import org.koitharu.kotatsu.metadata.SourceProfile
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.scrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.AutoLinkResult
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

abstract class Scrobbler(
	protected val db: MangaDatabase,
	val scrobblerService: ScrobblerService,
	private val repository: ScrobblerRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	/** Scale applied to the 0..1 rating before it reaches services that expect a wider range. */
	private val ratingMax: Float = 1f,
) {

	private val infoCache = LongSparseArray<ScrobblerMangaInfo>()
	private val misses = ConcurrentHashMap<String, Long>()
	protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

	val user: Flow<ScrobblerUser> = flow {
		repository.cachedUser?.let {
			emit(it)
		}
		runCatchingCancellable {
			repository.loadUser()
		}.onSuccess {
			emit(it)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	val isEnabled: Boolean
		get() = repository.isAuthorized

	suspend fun authorize(authCode: String): ScrobblerUser {
		repository.authorize(authCode)
		return repository.loadUser()
	}

	fun logout() {
		repository.logout()
	}

	suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		return repository.findManga(query, offset, type)
	}

	/**
	 * Links [targetId] to [mangaId] without discarding what the tracker already knows: a rating, a
	 * reading status or a chapter count set on the website always wins over anything the app can infer
	 * from local history. [fallbackStatus] is only written when the entry is brand new.
	 *
	 * @return `true` if the tracker had no progress of its own, so local progress is safe to push.
	 */
	suspend fun linkManga(mangaId: Long, targetId: Long, fallbackStatus: ScrobblingStatus): Boolean {
		// Every service invents a status when it creates an entry ("reading" on most of them), so the
		// stored status cannot tell an adopted entry from a fresh one — only createRate knows.
		val wasAlreadyTracked = repository.createRate(mangaId, targetId)
		if (wasAlreadyTracked) {
			return db.getScrobblingDao().find(scrobblerService.id, mangaId)?.chapter?.let { it <= 0 } != false
		}
		// Brand new entry, so it has no start date of its own worth keeping
		updateScrobblingInfo(mangaId, rating = 0f, fallbackStatus, comment = null, forceStartDate = true)
		return true
	}

	suspend fun scrobble(manga: Manga, chapterId: Long) {
		var chapters = manga.chapters
		if (chapters.isNullOrEmpty()) {
			chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
		}
		requireNotNull(chapters)
		val chapter = checkNotNull(chapters.findById(chapterId)) {
			"Chapter $chapterId not found in this manga"
		}
		val number = if (chapter.number > 0f) {
			chapter.number.toInt()
		} else {
			chapters = chapters.filter { x -> x.branch == chapter.branch }
			chapters.indexOf(chapter) + 1
		}
		val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
		repository.updateRate(entity.id, entity.mangaId, number)
		// Reading a chapter means it is no longer merely "planned", and no tracker flips that for us.
		// Only PLANNED is touched, so a manually set "on hold"/"dropped"/"completed" survives.
		if (isNotStarted(entity.status)) {
			updateScrobblingInfo(manga.id, entity.rating, ScrobblingStatus.READING, entity.comment)
		}
	}

	/**
	 * Takes the tracker back to "not started": progress 0 and status PLANNED. Used when a title is marked
	 * unread. Does nothing when the title is not tracked here.
	 */
	suspend fun resetProgress(manga: Manga) {
		val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
		repository.updateRate(entity.id, entity.mangaId, 0)
		updateScrobblingInfo(manga.id, entity.rating, ScrobblingStatus.PLANNED, entity.comment)
	}

	/**
	 * Sets how far along the tracker says this title is, from the progress editor. A title still marked
	 * "planned" becomes "reading" once it has progress; any other status is left as the user set it.
	 */
	suspend fun setProgress(mangaId: Long, chapter: Int) {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return
		val value = chapter.coerceAtLeast(0)
		repository.updateRate(entity.id, entity.mangaId, value)
		if (value > 0 && isNotStarted(entity.status)) {
			updateScrobblingInfo(mangaId, entity.rating, ScrobblingStatus.READING, entity.comment)
		}
	}

	/**
	 * Takes the tracker to "completed": status COMPLETED and progress at the end of the series, which is the
	 * title's own length when the tracker knows it and the last chapter's number otherwise. Used when a title is
	 * marked as completed. Does nothing when the title is not tracked here.
	 */
	suspend fun markCompleted(manga: Manga, lastChapterId: Long) {
		val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
		val chapters = manga.chapters.orEmpty()
		val last = chapters.findById(lastChapterId)
		val lastNumber = when {
			last == null -> 0
			last.number > 0f -> last.number.toInt()
			else -> chapters.filter { it.branch == last.branch }.indexOf(last) + 1
		}
		val total = runCatchingCancellable { getMangaInfo(entity.targetId).totalChapters }.getOrDefault(0)
		val progress = if (total > 0) total else lastNumber
		if (progress > 0) repository.updateRate(entity.id, entity.mangaId, progress)
		updateScrobblingInfo(manga.id, entity.rating, ScrobblingStatus.COMPLETED, entity.comment)
	}

	/** What this account is reading right now, straight from the tracker. Empty on any failure. */
	suspend fun getReadingList(limit: Int, anime: Boolean = false): List<TrackerReadingEntry> {
		if (!isEnabled) return emptyList()
		return runCatchingCancellable {
			repository.getReadingList(limit, anime)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(emptyList())
	}

	/** The whole account, every status. Empty on any failure. */
	suspend fun getLibrary(anime: Boolean = false): List<TrackerReadingEntry> =
		fetchLibrary(anime).getOrDefault(emptyList())

	/**
	 * Like [getLibrary] but says whether the tracker could be reached, so a merged library can tell
	 * "this tracker does not have the title" from "this tracker did not answer".
	 */
	suspend fun fetchLibrary(anime: Boolean = false): Result<List<TrackerReadingEntry>> {
		if (!isEnabled) return Result.success(emptyList())
		return runCatchingCancellable {
			repository.getLibrary(anime)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	/** Whether this service has an anime catalogue at all (MangaBaka does not). */
	val supportsAnime: Boolean
		get() = repository.supportsAnime

	/**
	 * Moves [entry] (as listed by the tracker itself) to [chapter]. Used by tracker-to-tracker
	 * synchronisation, where there may be no local manga behind the entry.
	 *
	 * @return `false` if this service cannot write progress that way.
	 */
	suspend fun pushProgress(entry: TrackerReadingEntry, chapter: Int): Boolean {
		if (!isEnabled) return false
		return repository.setProgress(entry, chapter)
	}

	/**
	 * Sets the progress of an already linked [mangaId] to [chapter] (a chapter or episode number, not a
	 * chapter id). Same rules as [scrobble]: a "planned" entry that gets progress becomes "reading".
	 */
	suspend fun setProgressOf(mangaId: Long, chapter: Int) {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return
		repository.updateRate(entity.id, entity.mangaId, chapter)
		if (chapter > 0 && isNotStarted(entity.status)) {
			updateScrobblingInfo(mangaId, entity.rating, ScrobblingStatus.READING, entity.comment)
		}
	}

	/**
	 * Searches with every name in [names] (romaji, English, alternative titles...) and merges the
	 * results, best match first. A series only has to be found under one of its names, and it is
	 * scored against all of them, so a library entry named in Japanese still finds its English page.
	 */
	suspend fun searchSmart(names: List<String>, type: ScrobblerMangaType, manga: Manga? = null): List<TrackerCandidate> {
		val queries = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { TitleMatcher.normalize(it) }
			.take(MAX_SEARCH_NAMES)
		val merged = LinkedHashMap<Long, TrackerCandidate>()
		for (query in queries) {
			val found = runCatchingCancellable {
				repository.findCandidates(query, type)
			}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
			for (c in found) {
				val best = c.names.maxByOrNull { n -> queries.maxOf { q -> TitleMatcher.score(q, n) } }
				val scored = c.copy(
					score = TitleMatcher.bestScore(queries, c.names),
					matchedName = best,
				)
				val old = merged[c.manga.id]
				if (old == null || old.score < scored.score) merged[c.manga.id] = scored
			}
			// An exact hit is as good as it gets, no need to spend more requests on other names.
			if (merged.values.any { it.score >= 1f }) break
		}
		if (manga == null) return merged.values.sortedByDescending { it.score }
		// With the source title known, a namesake of the wrong kind (a BL manga for a manhwa) goes last and
		// the entry that also knows the title's other names comes first.
		val profile = SourceProfile.of(manga)
		return merged.values.sortedWith(
			compareByDescending<TrackerCandidate> { !it.conflictsWith(profile) }
				.thenByDescending { it.score }
				.thenByDescending { it.coverage(profile) },
		)
	}

	/** True when [this] cannot be the source title: another country of origin, or Boys'/Girls' Love against not. */
	fun TrackerCandidate.conflictsWith(profile: SourceProfile): Boolean =
		MetadataMatcher.conflicts(profile, asMetadata())

	private fun TrackerCandidate.coverage(profile: SourceProfile): Float {
		val wanted = profile.names.take(8)
		if (wanted.isEmpty()) return 0f
		return wanted.count { n -> names.any { TitleMatcher.score(n, it) >= 0.95f } }.toFloat() / wanted.size
	}

	private fun TrackerCandidate.asMetadata() = org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle(
		id = manga.id,
		title = manga.name,
		names = names,
		coverUrl = manga.cover,
		bannerUrl = null,
		genres = labels,
		isAdult = false,
		score = null,
		url = manga.url,
		format = format,
		country = country,
	)

	/**
	 * Tracks [manga] on this service without the user opening the link sheet: searches by title (and
	 * alternative titles) and links only when a result's title matches exactly, so a wrong series is
	 * never written to the account. A miss is remembered for [MISS_TTL_MS] to spare the API.
	 */
	suspend fun autoLink(manga: Manga, isNovel: Boolean): AutoLinkResult {
		if (!isEnabled || db.getScrobblingDao().find(scrobblerService.id, manga.id) != null) {
			return AutoLinkResult.NOT_NEEDED
		}
		val missKey = "${scrobblerService.id}:${manga.id}"
		val lastMiss = misses[missKey]
		if (lastMiss != null && System.currentTimeMillis() - lastMiss < MISS_TTL_MS) {
			return AutoLinkResult.NO_MATCH
		}
		val type = manga.source.scrobblerType
		val titles = (listOf(manga.title) + manga.altTitles).filter { it.isNotBlank() }
		// Only an exact match on some name is trusted, so a wrong series is never written to the account.
		val profile = SourceProfile.of(manga)
		val match: ScrobblerManga? = searchSmart(titles, type, manga)
			.firstOrNull { it.score >= 1f && !it.conflictsWith(profile) }?.manga
		if (match == null) {
			misses[missKey] = System.currentTimeMillis()
			return AutoLinkResult.NO_MATCH
		}
		val canPushProgress = linkManga(manga.id, match.id, ScrobblingStatus.READING)
		return if (canPushProgress) AutoLinkResult.LINKED else AutoLinkResult.LINKED_TRACKER_AHEAD
	}

	suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return entity.toScrobblingInfo()
	}

	suspend fun refreshScrobblingOrNull(mangaId: Long): ScrobblingEntity? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return repository.refreshRate(entity)
	}

	fun isNotStarted(status: String?): Boolean = status == statuses[ScrobblingStatus.PLANNED]

	/**
	 * @param forceStartDate stamps today as the start date even when the status is not changing. Used
	 * for an entry the app just created, which has no date of its own to preserve.
	 */
	suspend fun updateScrobblingInfo(
		mangaId: Long,
		@FloatRange(from = 0.0, to = 1.0) rating: Float,
		status: ScrobblingStatus?,
		comment: String?,
		forceStartDate: Boolean = false,
	) {
		val entity = requireNotNull(db.getScrobblingDao().find(scrobblerService.id, mangaId)) {
			"Scrobbling info for manga $mangaId not found"
		}
		val statusString = statuses[status]
		// The start date marks the day reading began, so it is only rewritten on the way into READING
		val isStartingToRead = status == ScrobblingStatus.READING && entity.status != statusString
		repository.updateRate(
			rateId = entity.id,
			mangaId = entity.mangaId,
			rating = rating * ratingMax,
			status = statusString,
			comment = comment,
			setStartDate = forceStartDate || isStartingToRead,
		)
	}

	fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
		return db.getScrobblingDao().observe(scrobblerService.id, mangaId)
			// A signed-out service still has its old rows in the database, but nothing can be done
			// with them — every action routes through a scrobbler that is filtered out by
			// `isEnabled` and fails as "not available". Hide them instead of offering dead entries.
			.map { if (isEnabled) it?.toScrobblingInfo() else null }
	}

	fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
		return db.getScrobblingDao().observe(scrobblerService.id)
			.map { entities ->
				coroutineScope {
					entities.map {
						async {
							it.toScrobblingInfo()
						}
					}.awaitAll()
				}.filterNotNull()
			}
	}

	suspend fun unregisterScrobbling(mangaId: Long) {
		repository.unregister(mangaId)
	}

	protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		return repository.getMangaInfo(id)
	}

	private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
		val mangaInfo = infoCache.getOrElse(targetId) {
			runCatchingCancellable {
				getMangaInfo(targetId)
			}.onFailure {
				it.printStackTraceDebug()
			}.onSuccess {
				infoCache.put(targetId, it)
			}.getOrNull() ?: return null
		}
		return ScrobblingInfo(
			scrobbler = scrobblerService,
			mangaId = mangaId,
			targetId = targetId,
			status = statuses.findKeyByValue(status),
			chapter = chapter,
			totalChapters = mangaInfo.totalChapters,
			comment = comment,
			rating = rating,
			title = mangaInfo.name,
			coverUrl = mangaInfo.cover,
			description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
			externalUrl = mangaInfo.url,
		)
	}
}

private const val MISS_TTL_MS = 6 * 60 * 60 * 1000L
private const val MAX_SEARCH_NAMES = 10

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long): Boolean {
	return runCatchingCancellable {
		scrobble(manga, chapterId)
	}.onFailure {
		it.printStackTraceDebug()
	}.isSuccess
}

suspend fun Scrobbler.tryAutoLink(manga: Manga, isNovel: Boolean): AutoLinkResult {
	return runCatchingCancellable {
		autoLink(manga, isNovel)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(AutoLinkResult.NO_MATCH)
}
