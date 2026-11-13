package org.koitharu.kotatsu.scrobbling.common.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.SyncLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.priority

/**
 * Folds the libraries of several trackers into one list, the "Sync Library". A title that is on
 * AniList, MAL and MangaBaka becomes one entry; a title only one of them has stays an entry of its own.
 *
 * Two entries are the same title when they are linked to the same local manga, or when some name of one
 * equals some name of the other once case, accents and punctuation are ignored (see [TitleMatcher]).
 * Primary titles are compared before alternative names, so a series is not pulled into the wrong group
 * by a loose synonym when its real title has a match. Two entries of the same tracker are never merged:
 * a tracker lists a series once, so those are two different series that merely share a name.
 */
object TrackerLibraryMerger {

	/** Below this an updatedAt is a ranking position (MangaBaka), not a point in time. */
	private const val MIN_REAL_TIMESTAMP = 1_000_000_000_000L

	/**
	 * @param perService each reachable tracker's library
	 * @param links tracker -> (tracker's id of the series -> local manga id), from the app's own link table
	 * @param supported the trackers that were asked, used to say which ones lack a title; trackers outside
	 * it are never reported as missing (pass an empty set to leave [SyncLibraryEntry.missingOn] empty)
	 */
	fun merge(
		perService: Map<ScrobblerService, List<TrackerReadingEntry>>,
		links: Map<ScrobblerService, Map<Long, Long>> = emptyMap(),
		supported: Set<ScrobblerService> = perService.keys,
		anime: Boolean = false,
	): List<SyncLibraryEntry> {
		val nodes = ArrayList<TrackerReadingEntry>()
		for (service in perService.keys.sortedBy { it.priority() }) {
			nodes += perService.getValue(service)
		}
		val n = nodes.size
		if (n == 0) return emptyList()

		val parent = IntArray(n) { it }
		val owners = Array(n) { hashSetOf(nodes[it].service) }

		fun find(x: Int): Int {
			var root = x
			while (parent[root] != root) root = parent[root]
			var cur = x
			while (parent[cur] != root) {
				val next = parent[cur]
				parent[cur] = root
				cur = next
			}
			return root
		}

		fun union(a: Int, b: Int) {
			val ra = find(a)
			val rb = find(b)
			if (ra == rb) return
			// One tracker never lists a series twice, so these are two different series
			if (owners[ra].any { it in owners[rb] }) return
			parent[rb] = ra
			owners[ra].addAll(owners[rb])
		}

		// 1. The same local manga on two trackers: the strongest evidence there is
		val byLocal = HashMap<Long, Int>()
		for (i in 0 until n) {
			val e = nodes[i]
			val local = links[e.service]?.get(e.targetId) ?: continue
			val first = byLocal[local]
			if (first == null) byLocal[local] = i else union(first, i)
		}

		// 2. Primary titles, then 3. every other name
		fun matchBy(namesOf: (TrackerReadingEntry) -> List<String>) {
			val index = HashMap<String, Int>()
			for (i in 0 until n) {
				for (key in namesOf(nodes[i]).mapNotNull { keyOf(it) }.distinct()) {
					val other = index[key]
					if (other == null) index[key] = i else union(other, i)
				}
			}
		}
		matchBy { listOf(it.title) }
		matchBy { listOf(it.title) + it.altTitles }

		val groups = LinkedHashMap<Int, MutableList<TrackerReadingEntry>>()
		for (i in 0 until n) {
			groups.getOrPut(find(i)) { ArrayList() }.add(nodes[i])
		}
		return groups.values
			.map { build(it, supported, anime) }
			.sortedWith(compareByDescending<SyncLibraryEntry> { it.updatedAt }.thenBy { it.title.lowercase() })
	}

	private fun build(members: List<TrackerReadingEntry>, supported: Set<ScrobblerService>, anime: Boolean): SyncLibraryEntry {
		// members arrive best tracker first, so the first one holding the maximum is the tie winner
		val top = members.maxOf { it.chapter }
		val leader = members.first { it.chapter == top }
		val main = members.first().title
		return SyncLibraryEntry(
			title = main,
			coverUrl = members.firstNotNullOfOrNull { it.coverUrl?.takeIf { url -> url.isNotBlank() } },
			altTitles = members.flatMap { listOf(it.title) + it.altTitles }
				.filter { it.isNotBlank() && it != main }
				.distinct(),
			isAnime = anime,
			isNovel = members.any { it.isNovel },
			entries = members.associateBy { it.service },
			missingOn = (supported - members.map { it.service }.toSet()).sortedBy { it.priority() },
			status = leader.status,
			progress = top,
			totalChapters = members.maxOf { it.totalChapters },
			updatedAt = members.maxOf { it.updatedAt.takeIf { t -> t >= MIN_REAL_TIMESTAMP } ?: 0L },
		)
	}

	/** The comparison form of a name, or null when it is too short to say anything. */
	internal fun keyOf(name: String): String? =
		TitleMatcher.normalize(name).replace(" ", "").takeIf { it.length >= 2 }
}
