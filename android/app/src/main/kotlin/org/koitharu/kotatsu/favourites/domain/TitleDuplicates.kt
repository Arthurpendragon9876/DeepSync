package org.koitharu.kotatsu.favourites.domain

import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.parsers.model.Manga

/** Below this a normalized title is too short to say two entries are the same series. */
const val MIN_TITLE_KEY_LENGTH = 4

/**
 * The title reduced to lowercase letters and digits, so `One Piece`, `one-piece` and `One Piece!` share a key.
 * Shared by [DuplicatesUseCase] and the feed / explore duplicate collapsing.
 */
fun String.toTitleKey(): String = buildString(length) {
	for (c in this@toTitleKey) {
		if (c.isLetterOrDigit()) {
			append(c.lowercaseChar())
		}
	}
}

/**
 * Key shared by the same title coming from different sources, or null when the title is too short to trust.
 * The content mode is part of the key: a manga, a novel and an anime with the same name are different works.
 */
fun Manga.duplicateKey(): String? {
	val title = title.toTitleKey()
	return if (title.length < MIN_TITLE_KEY_LENGTH) null else source.contentMode.name + "|" + title
}

/**
 * Collapses entries that share a [keyOf] key down to the one [preferred] ranks highest (the earliest wins a tie).
 * Every survivor keeps its own position, so a chronological order is never disturbed. Entries whose key is null
 * are always kept.
 */
fun <T> List<T>.collapseDuplicates(keyOf: (T) -> String?, preferred: Comparator<T>): List<T> {
	if (size < 2) return this
	val best = HashMap<String, Int>()
	forEachIndexed { index, item ->
		val key = keyOf(item) ?: return@forEachIndexed
		val current = best[key]
		if (current == null || preferred.compare(item, this[current]) > 0) {
			best[key] = index
		}
	}
	if (best.isEmpty()) return this
	return filterIndexed { index, item ->
		val key = keyOf(item)
		key == null || best[key] == index
	}
}
