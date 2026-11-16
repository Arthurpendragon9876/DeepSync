package org.koitharu.kotatsu.suggestions.domain

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.almostEquals

class TagsBlacklist(
	private val tags: Set<String>,
	private val threshold: Float,
) {

	fun isNotEmpty() = tags.isNotEmpty()

	operator fun contains(manga: Manga): Boolean {
		if (tags.isEmpty()) {
			return false
		}
		for (mangaTag in manga.tags) {
			for (tagTitle in tags) {
				if (matches(mangaTag.title, tagTitle)) {
					return true
				}
			}
		}
		return false
	}

	operator fun contains(tag: MangaTag): Boolean = tags.any { matches(tag.title, it) }

	/** Short names (BL, GL) are compared exactly: fuzzy matching would take them for unrelated tags. */
	private fun matches(title: String, blocked: String): Boolean =
		if (blocked.length <= SHORT_NAME) title.equals(blocked, ignoreCase = true) else title.almostEquals(blocked, threshold)

	private companion object {

		const val SHORT_NAME = 3
	}
}
