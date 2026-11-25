package org.koitharu.kotatsu.home.ui.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService

/** Which kind of title Home shows. Switched by the floating toggle. */
@Keep
enum class HomeMode { MANGA, NOVEL, ANIME }

/**
 * What the Streaming mode ([HomeMode.ANIME]) is showing right now: anime, or TV shows and movies.
 * Streaming is one mode with one library; this only picks which extensions Explore and Home draw from.
 * An extension is a TV & Movies one when it is tagged so in the extension manager, otherwise it is anime.
 */
@Keep
enum class StreamingKind(@StringRes val titleRes: Int) {
	ANIME(R.string.ds_mode_anime),
	TV_MOVIES(R.string.ds_mode_tv_movies),
}

/** Where the hero and the Trending row get their titles from. */
@Keep
enum class HomeMetadata(@StringRes val titleRes: Int) {
	SOURCES(R.string.ds_metadata_sources),
	ANILIST(R.string.ds_metadata_anilist),
	MAL(R.string.ds_metadata_mal),
	MANGABAKA(R.string.ds_metadata_mangabaka),
	/** TV shows, movies and anime; the metadata for the TV & Movies half of Streaming. */
	SIMKL(R.string.ds_metadata_simkl),
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
	SIMKL("simkl", R.string.ds_carousel_simkl, R.string.ds_carousel_tracker_desc),
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
			id == "tracker_${ScrobblerService.SIMKL.id}" -> SIMKL
			id == "local" -> LOCAL
			id == "recent" -> RECENT
			id == "trending" -> TRENDING
			id == "popular" -> POPULAR
			id.startsWith("fav_") -> CUSTOM
			else -> null
		}
	}
}

/**
 * The parts of Settings that can be overridden for each mode (Manga, Novel, Streaming) in
 * Settings > Override global defaults. While an area is overridden, each mode keeps its own copy of that area's
 * settings; a mode that has not been edited yet keeps following the shared value.
 * [key] is the switch's preference key ([HOME] keeps the key "Custom homepage for each mode" always used).
 */
@Keep
enum class ModeOverrideArea(val key: String, @StringRes val titleRes: Int, @StringRes val descRes: Int) {
	HOME("home_per_mode", R.string.ds_override_home, R.string.ds_override_home_desc),
	EXPLORE("override_explore", R.string.ds_override_explore, R.string.ds_override_explore_desc),
	SERVICES("override_services", R.string.ds_override_services, R.string.ds_override_services_desc),
	FAVOURITES("override_favourites", R.string.ds_override_favourites, R.string.ds_override_favourites_desc),
	UPDATES("override_updates", R.string.ds_override_updates, R.string.ds_override_updates_desc),
	HISTORY("override_history", R.string.ds_override_history, R.string.ds_override_history_desc),
	SUGGESTIONS("override_suggestions", R.string.ds_override_suggestions, R.string.ds_override_suggestions_desc),
}

/** Where the Suggestions page gets its titles from. */
@Keep
enum class SuggestionsSource(@StringRes val titleRes: Int) {
	/** The installed sources' own catalogues, ranked by the tags of what was read or watched. */
	SOURCES(R.string.ds_suggestions_source_sources),

	/** AniList's recommendations for what was read or watched, found again in the installed sources. */
	ANILIST(R.string.ds_suggestions_source_anilist),
}
