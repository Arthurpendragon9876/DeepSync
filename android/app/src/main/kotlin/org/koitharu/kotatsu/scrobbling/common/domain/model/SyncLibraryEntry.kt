package org.koitharu.kotatsu.scrobbling.common.domain.model

/** Which tracker wins a tie, and the order trackers are listed in wherever several are shown together. */
val TRACKER_PRIORITY: List<ScrobblerService> = listOf(
	ScrobblerService.ANILIST,
	ScrobblerService.MAL,
	ScrobblerService.MANGABAKA,
	ScrobblerService.KITSU,
	ScrobblerService.SHIKIMORI,
)

fun ScrobblerService.priority(): Int = TRACKER_PRIORITY.indexOf(this).let { if (it < 0) TRACKER_PRIORITY.size else it }

/** Short label for dense lists, e.g. "AL 45 · MAL 40". */
val ScrobblerService.shortName: String
	get() = when (this) {
		ScrobblerService.ANILIST -> "AL"
		ScrobblerService.MAL -> "MAL"
		ScrobblerService.MANGABAKA -> "MB"
		ScrobblerService.KITSU -> "Kitsu"
		ScrobblerService.SHIKIMORI -> "Shiki"
	}

/** Move [target] forward to [chapter]. */
data class ProgressPush(
	val target: TrackerReadingEntry,
	val chapter: Int,
)

/**
 * One title in the Sync Library: the same series as it appears on every signed-in tracker, folded into a
 * single entry. A title that only one tracker has is still here, with the others in [missingOn].
 */
data class SyncLibraryEntry(
	val title: String,
	val coverUrl: String?,
	/** Every name any tracker knows the title by, for finding it in the sources. */
	val altTitles: List<String>,
	val isAnime: Boolean,
	val isNovel: Boolean,
	/** The tracker's own entry for this title, one per tracker that has it, best tracker first. */
	val entries: Map<ScrobblerService, TrackerReadingEntry>,
	/** Signed-in trackers that cover this kind of title but do not have it. */
	val missingOn: List<ScrobblerService>,
	/** READING, PLANNING, COMPLETED, PAUSED or DROPPED, taken from the tracker that is furthest along. */
	val status: String,
	/** The furthest progress on any tracker. */
	val progress: Int,
	val totalChapters: Int,
	/** Epoch millis of the last real activity on any tracker, 0 when none of them says. */
	val updatedAt: Long,
) {

	/** The tracker entry that is furthest along (the highest priority tracker on a tie). */
	val leader: TrackerReadingEntry
		get() = entries.values.first { it.chapter == progress }

	/**
	 * What synchronising would write: every tracker that is behind the furthest one, except finished
	 * entries, and never past the title's own length on that tracker.
	 */
	fun pendingPushes(): List<ProgressPush> {
		if (entries.size < 2 || progress <= 0) return emptyList()
		return entries.values.mapNotNull { e ->
			if (e.status == STATUS_COMPLETED) return@mapNotNull null
			val goal = if (e.totalChapters > 0) minOf(progress, e.totalChapters) else progress
			if (goal > e.chapter) ProgressPush(e, goal) else null
		}
	}

	val isOutOfSync: Boolean
		get() = pendingPushes().isNotEmpty()

	/** The entry to open this title from: the furthest one, but carrying every name the title has. */
	fun asReadingEntry(): TrackerReadingEntry = leader.copy(
		title = title,
		coverUrl = coverUrl ?: leader.coverUrl,
		altTitles = altTitles,
		totalChapters = totalChapters,
		isNovel = isNovel,
		isAnime = isAnime,
	)

	companion object {

		const val STATUS_COMPLETED = "COMPLETED"
	}
}

/**
 * The same title as found by each tracker's search: what the "Save progress?" sheet shows as one card.
 * Saving it links the title on every tracker in [candidates].
 */
data class TrackerMatchGroup(
	val candidates: Map<ScrobblerService, TrackerCandidate>,
) {

	init {
		require(candidates.isNotEmpty()) { "A match needs at least one tracker" }
	}

	/** The trackers that have this title, best tracker first. */
	val services: List<ScrobblerService>
		get() = candidates.keys.sortedBy { it.priority() }

	/** The candidate whose title and cover represent the group. */
	val primary: TrackerCandidate
		get() = candidates.getValue(services.first())

	/** How well the group matches the title being read, 0..1. */
	val score: Float
		get() = candidates.values.maxOf { it.score }

	val key: String
		get() = services.joinToString("|") { "${it.id}:${candidates.getValue(it).manga.id}" }
}
