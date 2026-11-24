package org.koitharu.kotatsu.explore.ui.model

import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.home.ui.model.StreamingKind
import org.koitharu.kotatsu.list.ui.model.ListModel

/**
 * Every mode's extension list, built together so Explore can switch between Anime / Manga / Novel
 * instantly when the global mode changes.
 */
data class ExploreSources(
	val manga: List<ListModel>,
	val novel: List<ListModel>,
	val anime: List<ListModel> = emptyList(),
	/** Streaming's other half: extensions tagged TV & Movies. [anime] holds the untagged ones. */
	val tvMovies: List<ListModel> = emptyList(),
) {

	/** The list for [mode], with Streaming split into anime and TV & Movies by [kind]. */
	operator fun get(mode: HomeMode, kind: StreamingKind): List<ListModel> =
		if (mode == HomeMode.ANIME && kind == StreamingKind.TV_MOVIES) tvMovies else get(mode)

	operator fun get(mode: HomeMode): List<ListModel> = when (mode) {
		HomeMode.ANIME -> anime
		HomeMode.NOVEL -> novel
		HomeMode.MANGA -> manga
	}

	operator fun get(isNovel: Boolean): List<ListModel> = if (isNovel) novel else manga
}
