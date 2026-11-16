package org.koitharu.kotatsu.home.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHomeSpotlightBinding
import org.koitharu.kotatsu.home.ui.model.HeroItem
import java.util.Locale

/**
 * The centred portrait carousel of the Spotlight banner style. It never ends: the titles repeat, so after the
 * last one the first comes round again beside it and the carousel can be swiped on forever.
 */
class HomeSpotlightAdapter(
	private val onClick: (HeroItem) -> Unit,
) : RecyclerView.Adapter<HomeSpotlightAdapter.ViewHolder>() {

	private var items: List<HeroItem> = emptyList()

	fun submit(list: List<HeroItem>) {
		items = list
		notifyDataSetChanged()
	}

	/** A position far from both ends that lands on the first title, to start the endless scroll from. */
	fun startPosition(): Int {
		val n = items.size
		return if (n > 1) (Int.MAX_VALUE / 2) - (Int.MAX_VALUE / 2) % n else 0
	}

	// One title is just one card; two or more repeat without end
	override fun getItemCount(): Int = if (items.size > 1) Int.MAX_VALUE else items.size

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
		ViewHolder(ItemHomeSpotlightBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position % items.size])

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
}
