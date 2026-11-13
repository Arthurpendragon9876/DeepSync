package org.koitharu.kotatsu.scrobbling.common.domain

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMatchGroup
import org.koitharu.kotatsu.scrobbling.common.domain.model.priority

/**
 * Turns the search results of several trackers into one card per title: the AniList, MAL and MangaBaka
 * results for the same series become a single [TrackerMatchGroup] listing the trackers that have it.
 */
object TrackerMatchGrouper {

	fun group(
		perService: Map<ScrobblerService, List<TrackerCandidate>>,
		perServiceLimit: Int = 6,
		maxGroups: Int = 6,
	): List<TrackerMatchGroup> {
		val groups = ArrayList<Builder>()
		for (service in perService.keys.sortedBy { it.priority() }) {
			val ranked = perService.getValue(service).sortedByDescending { it.score }.take(perServiceLimit)
			for (candidate in ranked) {
				val keys = candidate.names.mapNotNull { TrackerLibraryMerger.keyOf(it) }.toSet()
				// A tracker offers a title once, so a group that already has this tracker is a different series
				val home = groups.firstOrNull { it.accepts(service, candidate, keys) }
				if (home != null) home.add(service, candidate, keys) else groups += Builder().also { it.add(service, candidate, keys) }
			}
		}
		return groups
			.map { TrackerMatchGroup(it.candidates) }
			.sortedWith(
				compareByDescending<TrackerMatchGroup> { it.score }
					.thenByDescending { it.candidates.size }
					.thenBy { it.services.first().priority() },
			)
			.take(maxGroups)
	}

	private class Builder {

		val candidates = LinkedHashMap<ScrobblerService, TrackerCandidate>()
		private val keys = HashSet<String>()

		fun accepts(service: ScrobblerService, candidate: TrackerCandidate, candidateKeys: Set<String>): Boolean {
			if (service in candidates) return false
			if (candidateKeys.any { it in keys }) return true
			// Both are an exact hit on the title being read, so they are the same series even when
			// the two trackers happen to share no name with each other
			return candidate.score >= 1f && candidates.values.any { it.score >= 1f }
		}

		fun add(service: ScrobblerService, candidate: TrackerCandidate, candidateKeys: Set<String>) {
			candidates[service] = candidate
			keys += candidateKeys
		}
	}
}
