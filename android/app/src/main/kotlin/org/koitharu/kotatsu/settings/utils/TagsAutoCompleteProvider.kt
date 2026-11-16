package org.koitharu.kotatsu.settings.utils

import javax.inject.Inject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.metadata.DiscoverTags

class TagsAutoCompleteProvider @Inject constructor(
	private val db: MangaDatabase,
) : AutoCompleteProvider {

	override suspend fun getSuggestions(query: String): List<String> {
		if (query.isEmpty()) {
			return emptyList()
		}
		val set = LinkedHashSet<String>()
		// The tracker vocabulary first (genres, BL / Yaoi, Reverse Harem...), so a genre is offered even when no
		// source the user has opened ever carried it
		DiscoverTags.vocabulary.filterTo(set) { it.startsWith(query, ignoreCase = true) }
		DiscoverTags.vocabulary.filterTo(set) { it.contains(query, ignoreCase = true) }
		val tags = db.getTagsDao().findTags(query = "$query%", limit = 6)
		val known = set.mapTo(HashSet()) { it.lowercase() }
		for (tag in tags) {
			if (known.add(tag.title.lowercase())) {
				set.add(tag.title)
			}
		}
		return set.take(MAX_SUGGESTIONS)
	}

	private companion object {

		const val MAX_SUGGESTIONS = 10
	}
}
