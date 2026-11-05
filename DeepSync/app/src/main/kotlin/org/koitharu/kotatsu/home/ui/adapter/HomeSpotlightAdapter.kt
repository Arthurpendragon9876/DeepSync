package org.koitharu.kotatsu.home.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHomeSpotlightBinding
import org.koitharu.kotatsu.home.ui.model.HeroItem
import java.util.Locale

/** The centred portrait carousel of the Spotlight banner style. */
class HomeSpotlightAdapter(
	private val onClick: (HeroItem) -> Unit,
) : ListAdapter<HeroItem, HomeSpotlightAdapter.ViewHolder>(Diff) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
		ViewHolder(ItemHomeSpotlightBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

	inner class ViewHolder(private val binding: ItemHomeSpotlightBinding) : RecyclerView.ViewHolder(binding.root) {

		private var current: HeroItem? = null

		init {
			binding.root.setOnClickListener { current?.let(onClick) }
		}

		fun bind(item: HeroItem) {
			current = item
			binding.textTitle.text = item.title
			val rating = item.rating?.takeIf { it > 0f }
			binding.textRating.isVisible = rating != null
			binding.textRating.text = rating?.let { String.format(Locale.US, "\u2605 %.1f", it) }.orEmpty()
			// A short synopsis when there is one, otherwise the genres
			binding.textInfo.text = item.description?.take(120)
				?: item.genres.take(3).joinToString(" \u00b7 ").ifEmpty {
					binding.root.context.getString(R.string.ds_spotlight_open)
				}
			val manga = item.manga
			if (manga != null && item.coverUrl == manga.coverUrl) {
				binding.imageCover.setImageAsync(item.coverUrl, manga)
			} else {
				binding.imageCover.setImageAsync(item.coverUrl)
			}
		}
	}

	private object Diff : DiffUtil.ItemCallback<HeroItem>() {
		override fun areItemsTheSame(a: HeroItem, b: HeroItem) = a.key == b.key
		override fun areContentsTheSame(a: HeroItem, b: HeroItem) = a == b
	}
}
