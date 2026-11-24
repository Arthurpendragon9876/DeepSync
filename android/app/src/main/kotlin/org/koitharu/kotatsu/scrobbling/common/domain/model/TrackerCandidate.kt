package org.koitharu.kotatsu.scrobbling.common.domain.model

/** A search result from a tracker together with every name the tracker knows it by. */
data class TrackerCandidate(
	val manga: ScrobblerManga,
	val names: List<String>,
	/** 0..1, how well [names] match what was searched for. */
	val score: Float = 0f,
	/** The name that matched best, for showing "Matched on ..." */
	val matchedName: String? = null,
	/** The tracker's format label (MANGA, MANHWA, ONE_SHOT...), when it gives one. */
	val format: String? = null,
	/** ISO country of origin, when the tracker gives one. */
	val country: String? = null,
	/** Genres and tags, used to tell a Boys' Love namesake from the series itself. */
	val labels: List<String> = emptyList(),
	/** First release year, when the tracker gives one (TV & Movies, anime). */
	val year: Int? = null,
) {

	/** "Manhwa", "Manga", "Novel"... for showing next to the title; null when unknown. */
	val kindLabel: String?
		get() = when (val kind = org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds.kindOf(format, country)) {
			org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds.TV -> "TV"
			else -> kind?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
		}
}
