package org.koitharu.kotatsu.explore.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.databinding.ItemExploreSourcesPageBinding

/**
 * The single page of Explore. Which kind it shows (Anime / Manga / Novel) follows the mode button in
 * the nav bar, so switching mode just re-emits into this one list.
 */
class ExploreSourcesPagerAdapter(
	private val onPageCreated: (recyclerView: RecyclerView, position: Int) -> Unit,
) : RecyclerView.Adapter<ExploreSourcesPagerAdapter.PageHolder>() {

	override fun getItemCount() = 1

	override fun getItemViewType(position: Int) = position

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
		val binding = ItemExploreSourcesPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		onPageCreated(binding.recyclerView, viewType)
		return PageHolder(binding)
	}

	override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit

	class PageHolder(binding: ItemExploreSourcesPageBinding) : RecyclerView.ViewHolder(binding.root)
}
