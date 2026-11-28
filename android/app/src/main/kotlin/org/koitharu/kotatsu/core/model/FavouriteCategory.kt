package org.koitharu.kotatsu.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag
import org.koitharu.kotatsu.favourites.domain.CategoryUpdateInterval
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.time.Instant

@Parcelize
data class FavouriteCategory(
	val id: Long,
	val title: String,
	val sortKey: Int,
	val order: ListSortOrder,
	val createdAt: Instant,
	val isTrackingEnabled: Boolean,
	val isNewChaptersDownloadEnabled: Boolean,
	val isVisibleInLibrary: Boolean,
	/** Category this one is nested under; null = top level. */
	val parentId: Long? = null,
	/** How often the background check looks at this category; null = use the global schedule. */
	val updateInterval: CategoryUpdateInterval? = null,
	/** What the category is for; null = auto (follows its titles). Resolve with the repository. */
	val mediaTag: CategoryMediaTag? = null,
) : Parcelable, ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is FavouriteCategory && id == other.id
	}

	override fun getChangePayload(previousState: ListModel): Any? {
		if (previousState !is FavouriteCategory) {
			return null
		}
		return if (
			isTrackingEnabled != previousState.isTrackingEnabled ||
			isNewChaptersDownloadEnabled != previousState.isNewChaptersDownloadEnabled ||
			isVisibleInLibrary != previousState.isVisibleInLibrary ||
			updateInterval != previousState.updateInterval ||
			mediaTag != previousState.mediaTag
		) {
			ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
		} else {
			null
		}
	}
}
