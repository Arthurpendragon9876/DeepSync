package org.koitharu.kotatsu.metadata

import org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds

/**
 * A tag people filter by that is not one of AniList's genres (BL, Yaoi, Reverse Harem, Isekai...).
 *
 * @param label what the filter chip says, and the term kept in [DiscoverFilters.include] / [DiscoverFilters.exclude]
 * @param anilist the AniList tag it filters on
 * @param aliases other names trackers and sources use for the same thing, so MAL, MangaBaka and the
 * suggestions blacklist (which compare names) still recognise it
 * @param suggest plain names offered when typing into the suggestions' excluded genres
 */
class DiscoverTag(
	val label: String,
	val anilist: String,
	val aliases: Set<String> = emptySet(),
	val suggest: List<String> = listOf(label),
) {
	/** Every name this tag goes by, lower case with straight apostrophes, for comparing. */
	val names: Set<String> = (aliases + label + anilist + suggest).mapTo(HashSet()) { DiscoverTags.clean(it) }
}

object DiscoverTags {

	val all = listOf(
		DiscoverTag(
			label = "Boys' Love (BL / Yaoi)",
			anilist = "Boys' Love",
			aliases = MetadataKinds.bl,
			suggest = listOf("Boys' Love", "BL", "Yaoi", "Shounen Ai"),
		),
		DiscoverTag(
			label = "Girls' Love (GL / Yuri)",
			anilist = "Girls' Love",
			aliases = MetadataKinds.gl,
			suggest = listOf("Girls' Love", "GL", "Yuri", "Shoujo Ai"),
		),
		DiscoverTag("Reverse Harem", "Male Harem", setOf("reverse harem", "male harem")),
		DiscoverTag("Harem", "Female Harem", setOf("harem", "female harem", "mixed gender harem")),
		DiscoverTag("Isekai", "Isekai"),
		DiscoverTag("Reincarnation", "Reincarnation"),
		DiscoverTag("Villainess", "Villainess"),
		DiscoverTag("Time Loop", "Time Loop"),
		DiscoverTag("Love Triangle", "Love Triangle"),
		DiscoverTag("Tragedy", "Tragedy"),
		DiscoverTag("Gore", "Gore"),
		DiscoverTag("Survival", "Survival"),
		DiscoverTag("Super Power", "Super Power"),
		DiscoverTag("Magic", "Magic"),
		DiscoverTag("Martial Arts", "Martial Arts"),
		DiscoverTag("School", "School"),
		DiscoverTag("Military", "Military"),
		DiscoverTag("Video Games", "Video Games"),
		DiscoverTag("Shounen", "Shounen"),
		DiscoverTag("Shoujo", "Shoujo"),
		DiscoverTag("Seinen", "Seinen"),
		DiscoverTag("Josei", "Josei"),
	)

	private val byLabel = all.associateBy { it.label }

	fun find(label: String): DiscoverTag? = byLabel[label]

	/** Whether a title carrying these genres / tags matches [term], a genre or a tag label from the filter. */
	fun matches(term: String, genres: List<String>, tags: List<String>): Boolean {
		val names = find(term)?.names ?: setOf(clean(term))
		return (genres.asSequence() + tags.asSequence()).any { clean(it) in names }
	}

	/** Every name that means the same as [word], or just [word] when it is not a known tag. */
	fun synonymsOf(word: String): Set<String> {
		val key = clean(word)
		return all.firstOrNull { key in it.names }?.names ?: setOf(key)
	}

	/** Every genre and tag name the excluded-genres field can complete, genres first. */
	val vocabulary: List<String> = DISCOVER_GENRES + all.flatMap { it.suggest }

	internal fun clean(name: String) = name.trim().lowercase().replace('\u2019', '\'')
}
