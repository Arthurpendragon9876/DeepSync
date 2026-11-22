package org.koitharu.kotatsu.scrobbling.common.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.R as appcompatR
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.size.Size
import com.google.android.material.R as materialR
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

/**
 * One cover of a tracker library. [progress] / [total] fill the pill on the cover's top-right corner ("15/24", a check
 * mark when finished), exactly like a title in the Favourites tab (a plain count when the length is unknown); [detail] is an optional small line under the cover.
 */
data class LibraryCard<T : Any>(
	val payload: T,
	val key: String,
	val title: String,
	val coverUrl: String?,
	val progress: Int,
	val total: Int,
	val completed: Boolean = false,
	val detail: String? = null,
	val detailIsWarning: Boolean = false,
)

/** The Favourites tab's cover card (title over the cover, progress pill), fed with tracker entries. */
class LibraryCardAdapter<T : Any>(
	private val sizeResolver: ItemSizeResolver,
	private val onClick: (T) -> Unit,
) : ListAdapter<LibraryCard<T>, LibraryCardAdapter<T>.Holder>(Diff()) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
		Holder(ItemMangaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

	inner class Holder(private val b: ItemMangaGridBinding) : RecyclerView.ViewHolder(b.root) {

		private var item: LibraryCard<T>? = null

		init {
			val context = b.root.context
			b.root.setOnClickListener { item?.let { onClick(it.payload) } }
			// The overlay title adapts to the grid size (white, on the scrim), like in the Favourites grid
			sizeResolver.attachToView(b.root, b.textViewTitleOverlay, b.corners.progressView, b.corners.iconsView)
			// Title scrim: the same short fade of a dark shade of the theme accent the Favourites cards use
			val density = context.resources.displayMetrics.density
			val darkAccent = ColorUtils.blendARGB(context.getThemeColor(appcompatR.attr.colorPrimary), Color.BLACK, 0.78f)
			b.viewScrim.background = GradientDrawable(
				GradientDrawable.Orientation.BOTTOM_TOP,
				intArrayOf(
					ColorUtils.setAlphaComponent(darkAccent, 0xF2),
					ColorUtils.setAlphaComponent(darkAccent, 0xC0),
					ColorUtils.setAlphaComponent(darkAccent, 0x00),
				),
			).apply {
				val r = 16f * density
				cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
			}
			b.textViewTitle.maxLines = 1
			b.textViewTitle.textSize = 11f
		}

		fun bind(card: LibraryCard<T>) {
			item = card
			val context = b.root.context
			b.root.setTooltipCompat(card.title)
			b.textViewTitleOverlay.text = card.title
			b.textViewTitleOverlay.isVisible = true
			b.viewScrim.isVisible = true
			// The line under the cover is only for what the cover can't say (each tracker's progress)
			b.textViewTitle.isVisible = card.detail != null
			b.textViewTitle.text = card.detail
			b.textViewTitle.setTextColor(
				context.getThemeColor(if (card.detailIsWarning) appcompatR.attr.colorError else materialR.attr.colorOnSurfaceVariant),
			)
			val progress = when {
				card.completed && card.total > 0 -> ReadingProgress(1f, card.total, ProgressIndicatorMode.CHAPTERS_READ)
				card.total > 0 -> ReadingProgress(
					(card.progress.toFloat() / card.total).coerceIn(0f, 1f),
					card.total,
					ProgressIndicatorMode.CHAPTERS_READ,
				)
				else -> null
			}
			b.corners.progressView.setProgress(progress, false)
			// Length unknown: no ratio to show, so the count of what was read goes in the very same pill ("141"),
			// rather than in a separate round badge that looks nothing like the "133/349" pill next to it
			b.corners.progressView.count = if (progress == null) card.progress else 0
			b.corners.badge.isVisible = false
			// A stable size from the cell width, like the Favourites grid, so covers stay sharp
			val margin = b.root.resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
			val coverWidth = sizeResolver.cellWidth - margin * 2
			b.imageViewCover.exactImageSize = if (coverWidth > 0) Size(coverWidth, coverWidth * 18 / 13) else null
			b.imageViewCover.setImageAsync(card.coverUrl)
		}
	}

	private class Diff<T : Any> : DiffUtil.ItemCallback<LibraryCard<T>>() {

		override fun areItemsTheSame(a: LibraryCard<T>, b: LibraryCard<T>) = a.key == b.key

		override fun areContentsTheSame(a: LibraryCard<T>, b: LibraryCard<T>) = a == b
	}
}
