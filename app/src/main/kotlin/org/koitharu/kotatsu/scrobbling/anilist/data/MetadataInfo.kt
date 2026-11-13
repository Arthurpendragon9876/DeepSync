package org.koitharu.kotatsu.scrobbling.anilist.data

/** One tag with AniList's relevance percentage (0 when the tracker gives none). */
data class InfoTag(val name: String, val rank: Int)

/** A title linked to another one: relation (prequel, sequel, adaptation...) or recommendation. */
data class InfoMedia(
	val id: Long,
	val title: String,
	val coverUrl: String?,
	val bannerUrl: String?,
	/** AniList type: ANIME or MANGA. */
	val type: String?,
	val format: String?,
	/** PREQUEL, SEQUEL, ADAPTATION... Null for recommendations. */
	val relation: String? = null,
	val score: Float? = null,
)

data class InfoPerson(
	val id: Long,
	val name: String,
	val imageUrl: String?,
	/** MAIN / SUPPORTING for characters, "Story (chs 1-92)" for staff. */
	val role: String?,
	val favourites: Int? = null,
)

/**
 * Everything AniList's own title page shows, in one object, so the details screen can lay it out the way a
 * tracker client does. Trackers that cannot supply a field leave it empty and its card is not drawn.
 */
data class MetadataInfo(
	val id: Long,
	val title: String,
	val romaji: String?,
	val english: String?,
	val native: String?,
	val synonyms: List<String>,
	val description: String?,
	val status: String?,
	val format: String?,
	val country: String?,
	val chapters: Int?,
	val volumes: Int?,
	val episodes: Int?,
	val duration: Int?,
	val season: String?,
	val year: Int?,
	val source: String?,
	val score: Float?,
	val popularity: Int?,
	val studios: List<String>,
	val genres: List<String>,
	val tags: List<InfoTag>,
	val relations: List<InfoMedia>,
	val characters: List<InfoPerson>,
	val staff: List<InfoPerson>,
	val recommendations: List<InfoMedia>,
	val nextEpisode: Int?,
	val nextAiringAt: Long?,
	val url: String,
) {

	val prequel: InfoMedia? get() = relations.firstOrNull { it.relation == "PREQUEL" }
	val sequel: InfoMedia? get() = relations.firstOrNull { it.relation == "SEQUEL" }

	companion object {

		/** The little a plain catalogue entry (MAL, MangaBaka) can give; AniList fills the rest. */
		fun fromTitle(t: MetadataTitle) = MetadataInfo(
			id = t.id,
			title = t.title,
			romaji = null,
			english = null,
			native = null,
			synonyms = t.names.filter { it != t.title },
			description = t.description,
			status = null,
			format = t.format,
			country = t.country,
			chapters = null,
			volumes = null,
			episodes = null,
			duration = null,
			season = null,
			year = t.year,
			source = null,
			score = t.score,
			popularity = null,
			studios = emptyList(),
			genres = t.genres,
			tags = t.tags.map { InfoTag(it, 0) },
			relations = emptyList(),
			characters = emptyList(),
			staff = emptyList(),
			recommendations = emptyList(),
			nextEpisode = null,
			nextAiringAt = null,
			url = t.url,
		)
	}
}
