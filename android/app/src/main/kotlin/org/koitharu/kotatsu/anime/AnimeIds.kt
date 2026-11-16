package org.koitharu.kotatsu.anime

/**
 * Identities of anime entries and episodes. [AnimeMangaRepository] and the Anikku backup restore both
 * use these, so a restored library entry is the very row the live source later refreshes (instead of
 * a duplicate with different chapter ids, which would lose watch progress on the first refresh).
 */
fun animeMangaId(sourceName: String, url: String): Long = animeStableId(sourceName, "manga", url)

fun animeEpisodeId(sourceName: String, url: String): Long = animeStableId(sourceName, "episode", url)

private fun animeStableId(sourceName: String, type: String, value: String): Long =
	"$sourceName|$type|$value".hashCode().toLong() and Long.MAX_VALUE
