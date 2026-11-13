package org.koitharu.kotatsu.home.ui.model

import androidx.annotation.StringRes
import org.koitharu.kotatsu.explore.domain.HomeQuery
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry

/** What one block of the Home screen should currently show. */
sealed interface HomeSectionState<out T> {

	/** Still loading: the block keeps its place and shows a progress bar so the page doesn't jump. */
	data object Loading : HomeSectionState<Nothing>

	/** Nothing to show and nothing worth saying: the block is simply not displayed. */
	data object Hidden : HomeSectionState<Nothing>

	data class Content<T>(val items: List<T>) : HomeSectionState<T>
}

/** One poster in a horizontal row. Either a [manga] from a source or a tracker entry. */
data class HomePoster(
	val key: String,
	val title: String,
	val coverUrl: String?,
	/** Set for titles that come from a source: opens its details page. */
	val manga: Manga? = null,
	/** Set for titles that come from a tracker account. */
	val trackerEntry: TrackerReadingEntry? = null,
	/** The local manga this tracker entry is already linked to, if any. */
	val localMangaId: Long? = null,
	/** Chapter chip on the cover ("Ch 58"), 0 for none. */
	val chapter: Int = 0,
	/** "3 new" chip for the updates row, 0 for none. */
	val newCount: Int = 0,
	/** 0..10 star rating, or null when the source doesn't know. */
	val rating: Float? = null,
	/** Every name the title has. When set and [manga] is null, tapping looks it up in the sources by these. */
	val altTitles: List<String> = emptyList(),
	/** Set when the poster is a tracker title (AniList, MyAnimeList, MangaBaka) rather than a source's. */
	val meta: org.koitharu.kotatsu.metadata.MetaRef? = null,
)

enum class HomeRowKind { UPDATES, TRACKER, SYNC, LOCAL, FAVOURITES, RECENT, TRENDING, POPULAR }

data class HomeRow(
	val id: String,
	val kind: HomeRowKind,
	@StringRes val titleRes: Int,
	/** Optional format argument for [titleRes] (the tracker name in "Continue on %1$s"). */
	@StringRes val titleArgRes: Int = 0,
	val state: HomeSectionState<HomePoster>,
	/** Literal title for rows that are named by the user (a favourites category); overrides [titleRes]. */
	val title: String? = null,
)

/** One featured title in the banner. From a source ([manga] set) or from AniList metadata ([names] set). */
data class HeroItem(
	val key: String,
	val title: String,
	val coverUrl: String?,
	/** Wide artwork when the metadata provider has it; the cover is used otherwise. */
	val bannerUrl: String?,
	val genres: List<String>,
	/** "#1 TRENDING ON ANILIST" style label; null for the default one. */
	val badge: String? = null,
	val manga: Manga? = null,
	val names: List<String> = emptyList(),
	val rating: Float? = null,
	/** Short synopsis for the Spotlight card; may be null. */
	val description: String? = null,
	/** Set when the banner entry is a tracker title. */
	val meta: org.koitharu.kotatsu.metadata.MetaRef? = null,
)

/** Everything the user can set about Home, read from settings. */
data class HomeConfig(
	val mode: HomeMode = HomeMode.MANGA,
	val metadata: HomeMetadata = HomeMetadata.SOURCES,
	val sourceNames: Set<String> = emptySet(),
	val include: Set<String> = emptySet(),
	val exclude: Set<String> = emptySet(),
	val hideNsfw: Boolean = false,
	val banner: HomeBannerStyle = HomeBannerStyle.PANELS,
	val autoRotate: Boolean = true,
	/** [HomeCarousel.key]s the user switched off. */
	val hiddenCarousels: Set<String> = emptySet(),
) {

	fun shows(carousel: HomeCarousel): Boolean = carousel.key !in hiddenCarousels

	val isNovel: Boolean get() = mode == HomeMode.NOVEL

	val filterCount: Int get() = include.size + exclude.size + if (hideNsfw) 1 else 0

	val query: HomeQuery
		get() = HomeQuery(mode, sourceNames, include, exclude, hideNsfw)
}

data class HomeUiState(
	val config: HomeConfig = HomeConfig(),
	/** Titles for the banner (first is featured; Panels shows the next two beside it). */
	val hero: HomeSectionState<HeroItem> = HomeSectionState.Loading,
	val rows: List<HomeRow> = emptyList(),
)
