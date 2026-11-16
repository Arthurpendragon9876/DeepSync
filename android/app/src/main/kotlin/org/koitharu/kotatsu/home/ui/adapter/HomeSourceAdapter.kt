package org.koitharu.kotatsu.home.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.databinding.ItemHomeSourceBinding
import org.koitharu.kotatsu.parsers.model.MangaSource

/** A source as the user knows it: its real title and icon, not the internal id. */
data class SourceChoice(val source: MangaSource, val title: String)

/** Checkable list of sources for the Home source picker. [picked] holds source names and is edited in place. */
class HomeSourceAdapter(
	private val choices: List<SourceChoice>,
	private val picked: MutableSet<String>,
) : RecyclerView.Adapter<HomeSourceAdapter.Holder>() {

	override fun getItemCount() = choices.size

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
		Holder(ItemHomeSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(choices[position])

	inner class Holder(private val binding: ItemHomeSourceBinding) : RecyclerView.ViewHolder(binding.root) {

		private var current: SourceChoice? = null

		init {
			binding.root.setOnClickListener {
				val c = current ?: return@setOnClickListener
				if (!picked.add(c.source.name)) picked.remove(c.source.name)
				binding.check.isChecked = c.source.name in picked
			}
		}

		fun bind(c: SourceChoice) {
			current = c
			binding.textTitle.text = c.title
			binding.check.isChecked = c.source.name in picked
			binding.imageIcon.setImageAsync(c.source.faviconUri().toString())
		}
	}
}
