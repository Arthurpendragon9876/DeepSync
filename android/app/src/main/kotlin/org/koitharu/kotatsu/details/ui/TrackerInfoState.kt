package org.koitharu.kotatsu.details.ui

import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataInfo

/** What the tracker info cards on the details page are doing. */
sealed interface TrackerInfoState {

	/** Nothing to show: covers and info come from the source, or the tracker does not know this title. */
	data object Hidden : TrackerInfoState

	/** The tracker is being asked; the cards show placeholders. */
	data object Loading : TrackerInfoState

	data class Loaded(val info: MetadataInfo) : TrackerInfoState
}
