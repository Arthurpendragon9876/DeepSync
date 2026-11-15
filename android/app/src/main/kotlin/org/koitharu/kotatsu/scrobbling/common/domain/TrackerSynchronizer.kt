package org.koitharu.kotatsu.scrobbling.common.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.SyncLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMatchGroup
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.priority
import org.koitharu.kotatsu.scrobbling.common.domain.model.scrobblerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that spans more than one tracker:
 *
 *  - the Sync Library, one merged list of what every signed-in tracker holds ([loadLibrary]);
 *  - synchronising their progress, where the tracker that is furthest along wins ([synchronize]);
 *  - searching all trackers for a title at once ([search]) and linking the chosen match on every
 *    tracker that has it ([linkAndSync]).
 *
 * Progress only ever moves forward, so running a synchronisation twice, or from two devices, is harmless.
 */
@Singleton
class TrackerSynchronizer @Inject constructor(
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val db: MangaDatabase,
	private val settings: AppSettings,
) {

	/** The merged library plus what could not be loaded. */
	class MergedLibrary(
		val entries: List<SyncLibraryEntry>,
		/** Signed-in trackers that did not answer, so their titles are missing from [entries]. */
		val unreachable: List<ScrobblerService>,
		/** Tracker -> (the tracker's id of a series -> local manga id). */
		val links: Map<ScrobblerService, Map<Long, Long>>,
	) {

		/** The local manga a merged title is already linked to on any tracker, if there is one. */
		fun localMangaId(entry: SyncLibraryEntry): Long? =
			entry.entries.values.firstNotNullOfOrNull { links[it.service]?.get(it.targetId) }
	}

	class SyncReport(
		/** Titles that exist on at least two trackers. */
		val checked: Int = 0,
		/** Progress values written to a tracker. */
		val updated: Int = 0,
		/** Writes that failed, mostly rate limits or a lost connection. */
		val failed: Int = 0,
		val unreachable: List<ScrobblerService> = emptyList(),
		/** Fewer than two trackers are signed in, so there was nothing to synchronise. */
		val notEnoughTrackers: Boolean = false,
	)

	private val mutex = Mutex()
	private val running = MutableStateFlow(false)

	val isRunning: StateFlow<Boolean> = running.asStateFlow()

	/** Signed-in trackers, best first. */
	fun signedIn(): List<Scrobbler> = scrobblers.filter { it.isEnabled }.sortedBy { it.scrobblerService.priority() }

	/** Synchronising needs two trackers to compare. */
	val canSync: Boolean
		get() = scrobblers.count { it.isEnabled } >= 2

	// ---- Sync Library ---------------------------------------------------------------------------

	/** Every signed-in tracker's whole library, merged. Trackers without anime are left out of the anime library. */
	suspend fun loadLibrary(anime: Boolean): MergedLibrary = coroutineScope {
		val active = signedIn().filter { !anime || it.supportsAnime }
		val fetched = active.map { scrobbler ->
			async(Dispatchers.Default) { scrobbler to scrobbler.fetchLibrary(anime) }
		}.awaitAll()
		val lists = LinkedHashMap<ScrobblerService, List<TrackerReadingEntry>>()
		val unreachable = ArrayList<ScrobblerService>()
		for ((scrobbler, result) in fetched) {
			val list = result.getOrNull()
			if (list != null) lists[scrobbler.scrobblerService] = list else unreachable += scrobbler.scrobblerService
		}
		val links = loadLinks(lists.keys)
		MergedLibrary(
			entries = TrackerLibraryMerger.merge(lists, links, lists.keys, anime),
			unreachable = unreachable,
			links = links,
		)
	}

	/**
	 * What the user is reading right now on every tracker, merged. The light version for Home: it only asks
	 * for reading lists, so [SyncLibraryEntry.missingOn] is not filled in.
	 */
	suspend fun loadReading(anime: Boolean, novel: Boolean, limit: Int): MergedLibrary = coroutineScope {
		val active = signedIn().filter { !anime || it.supportsAnime }
		val lists = LinkedHashMap<ScrobblerService, List<TrackerReadingEntry>>()
		active.map { scrobbler ->
			async(Dispatchers.Default) { scrobbler.scrobblerService to scrobbler.getReadingList(limit * 2, anime) }
		}.awaitAll().forEach { (service, list) ->
			val kind = list.filter { if (anime) it.isAnime else !it.isAnime && it.isNovel == novel }
			if (kind.isNotEmpty()) lists[service] = kind
		}
		val links = loadLinks(lists.keys)
		MergedLibrary(
			entries = TrackerLibraryMerger.merge(lists, links, emptySet(), anime).take(limit),
			unreachable = emptyList(),
			links = links,
		)
	}

	private suspend fun loadLinks(services: Collection<ScrobblerService>): Map<ScrobblerService, Map<Long, Long>> =
		services.associateWith { service ->
			runCatchingCancellable {
				db.getScrobblingDao().observe(service.id).first().associate { it.targetId to it.mangaId }
			}.getOrDefault(emptyMap())
		}

	// ---- Synchronising --------------------------------------------------------------------------

	/**
	 * Brings every title that is on two or more trackers to the furthest progress any of them has.
	 * Finished entries are not touched and progress never goes down. A tracker that cannot write progress
	 * this way (Kitsu, Shikimori) is read from but not written to.
	 *
	 * @param anime `null` does both manga and anime.
	 */
	suspend fun synchronize(anime: Boolean? = null): SyncReport = mutex.withLock {
		if (!canSync) return@withLock SyncReport(notEnoughTrackers = true)
		running.value = true
		try {
			var checked = 0
			var updated = 0
			var failed = 0
			val unreachable = LinkedHashSet<ScrobblerService>()
			for (mode in anime?.let { listOf(it) } ?: listOf(false, true)) {
				val library = loadLibrary(mode)
				unreachable += library.unreachable
				for (title in library.entries) {
					if (title.entries.size < 2) continue
					checked++
					for (push in title.pendingPushes()) {
						val service = push.target.service
						val scrobbler = scrobblers.firstOrNull { it.scrobblerService == service } ?: continue
						val outcome = runCatchingCancellable { scrobbler.pushProgress(push.target, push.chapter) }
							.onFailure { it.printStackTraceDebug() }
						when {
							outcome.isFailure -> failed++
							outcome.getOrDefault(false) -> {
								updated++
								// A title already linked in the app keeps its stored progress in step
								library.links[service]?.get(push.target.targetId)?.let { mangaId ->
									runCatchingCancellable { scrobbler.refreshScrobblingOrNull(mangaId) }
										.onFailure { it.printStackTraceDebug() }
								}
								// Trackers rate-limit bursts of writes
								delay(WRITE_GAP_MS)
							}
						}
					}
				}
			}
			settings.trackerLastSync = System.currentTimeMillis()
			SyncReport(checked, updated, failed, unreachable.toList())
		} finally {
			running.value = false
		}
	}

	// ---- Saving progress for a new title --------------------------------------------------------

	/** Searches every signed-in tracker for [manga] at once and folds the results into one card per title. */
	suspend fun search(manga: Manga): List<TrackerMatchGroup> = coroutineScope {
		val names = (listOf(manga.title) + manga.altTitles).filter { it.isNotBlank() }.distinct()
		val type = manga.source.scrobblerType
		val results = signedIn().filter { !type.isAnime || it.supportsAnime }.map { scrobbler ->
			async(Dispatchers.Default) {
				val found = runCatchingCancellable { scrobbler.searchSmart(names, type, manga) }
					.onFailure { it.printStackTraceDebug() }
					.getOrDefault(emptyList())
				scrobbler.scrobblerService to found
			}
		}.awaitAll().filter { (_, found) -> found.isNotEmpty() }.toMap()
		TrackerMatchGrouper.group(results)
	}

	/**
	 * Links [manga] on every tracker of [group], puts the chapter just opened on the ones that had no
	 * progress, then brings all of them up to the furthest. Linking an entry that already exists adopts it
	 * as it is, so a tracker that is ahead keeps its progress and the others catch up to it.
	 *
	 * @return the trackers the title is now linked on; trackers that failed are left out
	 */
	suspend fun linkAndSync(manga: Manga, chapterId: Long, group: TrackerMatchGroup): List<ScrobblerService> {
		val linked = ArrayList<Scrobbler>()
		val withoutProgress = ArrayList<Scrobbler>()
		for ((service, candidate) in group.candidates) {
			val scrobbler = scrobblers.firstOrNull { it.scrobblerService == service && it.isEnabled } ?: continue
			val canPush = runCatchingCancellable {
				scrobbler.linkManga(manga.id, candidate.manga.id, ScrobblingStatus.READING)
			}.onFailure { it.printStackTraceDebug() }.getOrNull() ?: continue
			linked += scrobbler
			if (canPush) withoutProgress += scrobbler
		}
		withoutProgress.forEach { it.tryScrobble(manga, chapterId) }
		val progress = linked.associateWith { scrobbler ->
			runCatchingCancellable { db.getScrobblingDao().find(scrobbler.scrobblerService.id, manga.id)?.chapter }
				.getOrNull() ?: 0
		}
		val furthest = progress.values.maxOrNull() ?: 0
		if (furthest > 0) {
			for ((scrobbler, chapter) in progress) {
				if (chapter >= furthest) continue
				runCatchingCancellable { scrobbler.setProgressOf(manga.id, furthest) }
					.onFailure { it.printStackTraceDebug() }
			}
		}
		return linked.sortedBy { it.scrobblerService.priority() }.map { it.scrobblerService }
	}

	private companion object {

		const val WRITE_GAP_MS = 250L
	}
}
