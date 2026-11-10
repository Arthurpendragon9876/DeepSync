package org.koitharu.kotatsu.scrobbling.common.domain.model

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Which kind of entry the scrobbler search should return. Trackers keep prose and comics in the same
 * catalogue, so linking a novel used to mean scrolling past every manga with a similar title.
 *
 * Most services filter this server-side; MAL has no such query parameter and filters its own pages,
 * and Shikimori keeps novels on a separate endpoint entirely, so it ignores this and returns manga.
 */
enum class ScrobblerMangaType(@StringRes val titleResId: Int) {

	MANGA(R.string.content_type_manga),
	NOVEL(R.string.content_type_novel),
	ANIME(R.string.ds_mode_anime),
	;

	val isNovel: Boolean
		get() = this == NOVEL

	val isAnime: Boolean
		get() = this == ANIME
}

/** Which tracker catalogue a source's titles belong to. */
val MangaSource.scrobblerType: ScrobblerMangaType
	get() = when (contentMode) {
		HomeMode.ANIME -> ScrobblerMangaType.ANIME
		HomeMode.NOVEL -> ScrobblerMangaType.NOVEL
		HomeMode.MANGA -> ScrobblerMangaType.MANGA
	}
