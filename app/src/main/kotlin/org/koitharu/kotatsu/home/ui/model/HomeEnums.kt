package org.koitharu.kotatsu.home.ui.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

/** Which kind of title Home shows. Switched by the floating toggle. */
@Keep
enum class HomeMode { MANGA, NOVEL, ANIME }

/** Where the hero and the Trending row get their titles from. */
@Keep
enum class HomeMetadata(@StringRes val titleRes: Int) {
	SOURCES(R.string.ds_metadata_sources),
	ANILIST(R.string.ds_metadata_anilist),
	MAL(R.string.ds_metadata_mal),
	MANGABAKA(R.string.ds_metadata_mangabaka),
}

/** The look of the banner at the top of Home. Chosen in Settings, Appearance. */
@Keep
enum class HomeBannerStyle(@StringRes val titleRes: Int, @StringRes val descRes: Int) {
	PANELS(R.string.ds_banner_panels, R.string.ds_banner_panels_desc),
	CARD(R.string.ds_banner_card, R.string.ds_banner_card_desc),
	SPOTLIGHT(R.string.ds_banner_spotlight, R.string.ds_banner_spotlight_desc),
}

/**
 * Everything on Home that can be switched on or off in Yugen preferences > Home layout > Manage carousels.
 * A carousel is on unless its [key] is in the hidden set kept by settings.
 */
@Keep
enum class HomeCarousel(val key: String, @StringRes val titleRes: Int, @StringRes val descRes: Int) {
	BANNER("banner", R.string.ds_carousel_banner, R.string.ds_carousel_banner_desc),
	UPDATES("updates", R.string.ds_carousel_updates, R.string.ds_carousel_updates_desc),
	SYNC("sync", R.string.ds_carousel_sync, R.string.ds_carousel_sync_desc),
	ANILIST("anilist", R.string.ds_carousel_anilist, R.string.ds_carousel_tracker_desc),
	MAL("mal", R.string.ds_carousel_mal, R.string.ds_carousel_tracker_desc),
	MANGABAKA("mangabaka", R.string.ds_carousel_mangabaka, R.string.ds_carousel_tracker_desc),
	LOCAL("local", R.string.ds_carousel_local, R.string.ds_carousel_local_desc),
	RECENT("recent", R.string.ds_carousel_recent, R.string.ds_carousel_recent_desc),
	TRENDING("trending", R.string.ds_carousel_trending, R.string.ds_carousel_trending_desc),
	POPULAR("popular", R.string.ds_carousel_popular, R.string.ds_carousel_popular_desc),

	/** Master switch for the favourites categories picked as carousels; the categories themselves are chosen separately. */
	CUSTOM("custom", R.string.ds_custom_carousel, R.string.ds_custom_carousel_desc),
	;

	companion object {

		/** The switch that governs the Home row with this id, or null for a row that has none. */
		fun forRowId(id: String): HomeCarousel? = when {
			id == "updates" -> UPDATES
			id == "tracker_sync" -> SYNC
			id == "tracker_${ScrobblerService.ANILIST.id}" -> ANILIST
			id == "tracker_${ScrobblerService.MAL.id}" -> MAL
			id == "tracker_${ScrobblerService.MANGABAKA.id}" -> MANGABAKA
			id == "local" -> LOCAL
			id == "recent" -> RECENT
			id == "trending" -> TRENDING
			id == "popular" -> POPULAR
			id.startsWith("fav_") -> CUSTOM
			else -> null
		}
	}
}
