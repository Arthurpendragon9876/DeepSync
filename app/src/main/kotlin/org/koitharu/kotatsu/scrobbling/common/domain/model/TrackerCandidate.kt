package org.koitharu.kotatsu.scrobbling.common.domain.model

/** A search result from a tracker together with every name the tracker knows it by. */
data class TrackerCandidate(
	val manga: ScrobblerManga,
	val names: List<String>,
	/** 0..1, how well [names] match what was searched for. */
	val score: Float = 0f,
	/** The name that matched best, for showing "Matched on ..." */
	val matchedName: String? = null,
)
