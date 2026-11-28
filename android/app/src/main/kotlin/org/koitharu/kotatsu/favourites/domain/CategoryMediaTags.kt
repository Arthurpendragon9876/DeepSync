package org.koitharu.kotatsu.favourites.domain

import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.model.isTvMoviesSource
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Which kind of title a source produces, in the terms category tags use. Streaming is one app mode, but it has
 * two kinds of source (anime, TV & Movies), and a category can be limited to either.
 */
fun MangaSource.mediaTag(): CategoryMediaTag = when (contentMode) {
	HomeMode.MANGA -> CategoryMediaTag.MANGA
	HomeMode.NOVEL -> CategoryMediaTag.NOVEL
	HomeMode.ANIME -> if (isTvMoviesSource) CategoryMediaTag.TV_MOVIES else CategoryMediaTag.ANIME
}
