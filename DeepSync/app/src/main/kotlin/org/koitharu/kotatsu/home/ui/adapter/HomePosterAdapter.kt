package org.koitharu.kotatsu.home.ui.adapter

import org.koitharu.kotatsu.core.model.isAnimeSource
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHomePosterBinding
import org.koitharu.kotatsu.home.ui.model.HomePoster
import java.util.Locale

/** A horizontal row of posters, shared by every row on Home. */
class HomePosterAdapter(
	private val onClick: (HomePoster) -> Unit,
) : ListAdapter<HomePoster, HomePosterAdapter.ViewHolder>(Diff) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
		val binding = ItemHomePosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return ViewHolder(binding)
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

	inner class ViewHolder(private val binding: ItemHomePosterBinding) : RecyclerView.ViewHolder(binding.root) {

		private var current: HomePoster? = null

		init {
			binding.root.setOnClickListener { current?.let(onClick) }
		}

		fun bind(item: HomePoster) {
			current = item
			val context = binding.root.context
			binding.textTitle.text = item.title
			// Source covers need that source's headers; tracker covers are plain public URLs.
			if (item.manga != null) {
				binding.imageCover.setImageAsync(item.coverUrl, item.manga)
			} else {
				binding.imageCover.setImageAsync(item.coverUrl)
			}
			binding.textChapter.isVisible = item.chapter > 0
			if (item.chapter > 0) {
				binding.textChapter.text = context.getString(
					if ((item.manga?.source?.isAnimeSource == true || item.trackerEntry?.isAnime == true)) R.string.home_episode_short else R.string.home_chapter_short,
					item.chapter,
				)
			}
			binding.textNew.isVisible = item.newCount > 0
			if (item.newCount > 0) {
				binding.textNew.text = context.getString(
					if ((item.manga?.source?.isAnimeSource == true || item.trackerEntry?.isAnime == true)) R.string.home_new_episodes else R.string.home_new_chapters,
					item.newCount,
				)
			}
			val rating = item.rating
			binding.textRating.isVisible = rating != null && rating > 0f
			if (rating != null && rating > 0f) {
				binding.textRating.text = String.format(Locale.US, "★ %.1f", rating)
			}
		}
	}

	private object Diff : DiffUtil.ItemCallback<HomePoster>() {
		override fun areItemsTheSame(old: HomePoster, new: HomePoster) = old.key == new.key
		override fun areContentsTheSame(old: HomePoster, new: HomePoster) = old == new
	}
}
