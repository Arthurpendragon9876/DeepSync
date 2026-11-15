package org.koitharu.kotatsu.scrobbling.common.domain.model

/**
 * One title from the "currently reading" list held by a tracker account itself (not a local link).
 * The Home screen shows these as its "Continue on AniList / MyAnimeList / MangaBaka" rows.
 */
data class TrackerReadingEntry(
	val service: ScrobblerService,
	/** The tracker's own id of the series; matches [org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity.targetId]. */
	val targetId: Long,
	val title: String,
	val coverUrl: String?,
	/** Last chapter read, as recorded on the tracker. */
	val chapter: Int,
	/** Total chapters as published by the tracker, or 0 if unknown. */
	val totalChapters: Int,
	val url: String,
	/** Epoch millis of the last update on the tracker, 0 if the tracker doesn't say. */
	val updatedAt: Long,
	/** READING, PLANNING, COMPLETED, PAUSED or DROPPED. */
	val status: String = "READING",
	/** Romaji, English, native and synonyms, for finding the title in sources. */
	val altTitles: List<String> = emptyList(),
	/** A light novel rather than a manga. */
	val isNovel: Boolean = false,
	/** An anime: [chapter] and [totalChapters] then count episodes. */
	val isAnime: Boolean = false,
)

enum class AutoLinkResult {
	/** Auto-linking is off, or this manga already has a link on that tracker. */
	NOT_NEEDED,

	/** No confident match was found; nothing was written to the tracker. */
	NO_MATCH,

	/** Linked. The tracker had no progress of its own, so local progress may be pushed. */
	LINKED,

	/** Linked to an entry that already existed and is ahead of (or independent from) local progress. */
	LINKED_TRACKER_AHEAD,
}
