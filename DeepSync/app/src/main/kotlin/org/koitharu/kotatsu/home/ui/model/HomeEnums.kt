package org.koitharu.kotatsu.home.ui.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/** Which kind of title Home shows. Switched by the floating toggle. */
@Keep
enum class HomeMode { MANGA, NOVEL, ANIME }

/** Where the hero and the Trending row get their titles from. */
@Keep
enum class HomeMetadata(@StringRes val titleRes: Int) {
	SOURCES(R.string.ds_metadata_sources),
	ANILIST(R.string.ds_metadata_anilist),
}

/** The look of the banner at the top of Home. Chosen in Settings, Appearance. */
@Keep
enum class HomeBannerStyle(@StringRes val titleRes: Int, @StringRes val descRes: Int) {
	PANELS(R.string.ds_banner_panels, R.string.ds_banner_panels_desc),
	CARD(R.string.ds_banner_card, R.string.ds_banner_card_desc),
	SPOTLIGHT(R.string.ds_banner_spotlight, R.string.ds_banner_spotlight_desc),
}
