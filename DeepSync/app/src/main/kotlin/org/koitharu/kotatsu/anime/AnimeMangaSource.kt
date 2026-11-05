package org.koitharu.kotatsu.anime

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * An Aniyomi anime source exposed through the same [MangaSource] plumbing as Mihon manga sources
 * and LNReader novel plugins, so it shows up in Home, Explore, favourites and history. Its
 * "chapters" are episodes.
 */
class AnimeMangaSource(
	val catalogueSource: AnimeCatalogueSource,
	val pkgName: String,
	val extensionName: String,
	val nsfw: Boolean,
) : MangaSource {

	override val name: String
		get() = "ANIME_${catalogueSource.id}"

	val displayName: String
		get() = catalogueSource.name

	val language: String
		get() = catalogueSource.lang

	val sourceId: Long
		get() = catalogueSource.id

	val supportsLatest: Boolean
		get() = catalogueSource.supportsLatest

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		return other.name == name
	}

	override fun hashCode(): Int = sourceId.hashCode()

	override fun toString(): String = "AnimeMangaSource(id=$sourceId, name=$displayName, lang=$language)"
}
