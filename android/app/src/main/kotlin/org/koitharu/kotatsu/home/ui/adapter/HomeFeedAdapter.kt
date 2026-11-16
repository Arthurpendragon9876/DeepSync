package org.koitharu.kotatsu.home.ui.adapter

import org.koitharu.kotatsu.core.model.isAnimeSource
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHomeFeedBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem

/** Horizontal row of compact tracker-update cards for the Home screen. */
class HomeFeedAdapter(
	private val onItemClick: (Manga) -> Unit,
) : ListAdapter<FeedItem, HomeFeedAdapter.ViewHolder>(Diff) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemHomeFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

	inner class ViewHolder(private val binding: ItemHomeFeedBinding) : RecyclerView.ViewHolder(binding.root) {

		private var current: FeedItem? = null

		init {
			binding.root.setOnClickListener { current?.let { onItemClick(it.manga) } }
		}

		fun bind(item: FeedItem) {
			current = item
			binding.textViewTitle.text = item.title
			binding.imageViewCover.setImageAsync(item.imageUrl, item.manga)
			binding.textViewChapter.text = item.chapters.lastOrNull()?.name.orEmpty()
			val newCount = item.chapters.count { it.isNew }
			binding.chipNew.isVisible = item.isNew && newCount > 0
			if (binding.chipNew.isVisible) {
				binding.chipNew.text = binding.root.context.getString(
					if (item.manga.source.isAnimeSource) R.string.home_new_episodes else R.string.home_new_chapters,
					newCount,
				)
			}
		}
	}

	private object Diff : DiffUtil.ItemCallback<FeedItem>() {
		override fun areItemsTheSame(old: FeedItem, new: FeedItem) = old.id == new.id
		override fun areContentsTheSame(old: FeedItem, new: FeedItem) = old == new
	}
}
