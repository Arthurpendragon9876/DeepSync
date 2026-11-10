package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.GridColumns
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.databinding.DialogTrackerLibraryBinding
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import javax.inject.Inject

/**
 * The whole account of one tracker, every status, opened from "See all" on Home. Reading is shown
 * first; the other statuses are a tap away. Titles open through [Host], which knows how to find them.
 */
@AndroidEntryPoint
class TrackerLibraryDialog : DialogFragment() {

	interface Host {
		fun onLibraryEntryClick(entry: TrackerReadingEntry, localMangaId: Long?)
	}

	@Inject
	lateinit var scrobblers: Set<@JvmSuppressWildcards Scrobbler>

	@Inject
	lateinit var db: MangaDatabase

	@Inject
	lateinit var settings: AppSettings

	private var binding: DialogTrackerLibraryBinding? = null
	private var entries: List<TrackerReadingEntry> = emptyList()
	private var links: Map<Long, Long> = emptyMap()
	private var status = "READING"
	private val adapter = LibraryAdapter { entry ->
		(parentFragment as? Host)?.onLibraryEntryClick(entry, links[entry.targetId])
		dismissAllowingStateLoss()
	}

	override fun getTheme(): Int = R.style.ThemeOverlay_Material3_Dialog_Fullscreen_Deepsync

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
		super.onCreateDialog(savedInstanceState).apply {
			window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val b = DialogTrackerLibraryBinding.inflate(inflater, container, false)
		binding = b
		return b.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		val b = binding ?: return
		val service = ScrobblerService.values().firstOrNull { it.name == requireArguments().getString(ARG_SERVICE) }
		val anime = requireArguments().getBoolean(ARG_ANIME)
		val scrobbler = scrobblers.firstOrNull { it.scrobblerService == service }
		if (service == null || scrobbler == null) {
			dismissAllowingStateLoss()
			return
		}
		b.textTitle.text = getString(R.string.ds_library_title, getString(service.titleResId)) +
			if (anime) " · " + getString(R.string.ds_mode_anime) else ""
		b.buttonClose.setOnClickListener { dismissAllowingStateLoss() }
		// Same cover grid as the Favourites tab, at the user's grid size
		val grid = GridColumns.columnsFor(resources, GridColumns.referenceWidth(resources), settings.gridSize / 100f)
		b.list.layoutManager = GridLayoutManager(requireContext(), grid)
		// Keep the header clear of the status bar and the list clear of the gesture bar
		val listBottom = b.list.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			v.updatePadding(top = bars.top + (16 * resources.displayMetrics.density).toInt())
			b.list.updatePadding(bottom = listBottom + bars.bottom)
			insets
		}
		b.list.adapter = adapter
		STATUSES.forEach { (key, label) ->
			b.chipStatus.addView(
				Chip(requireContext()).apply {
					id = View.generateViewId()
					text = getString(label)
					isCheckable = true
					isChecked = key == status
					tag = key
					setOnClickListener {
						status = key
						showStatus()
					}
				},
			)
		}
		lifecycleScope.launch {
			val loaded = withContext(Dispatchers.Default) { scrobbler.getLibrary(anime) }
			links = withContext(Dispatchers.Default) {
				runCatching { db.getScrobblingDao().observe(service.id).first().associate { it.targetId to it.mangaId } }
					.getOrDefault(emptyMap())
			}
			entries = loaded
			binding?.progress?.isVisible = false
			binding?.textCount?.text = getString(R.string.ds_library_count, loaded.size)
			// Reading may be empty while something else is not: land on the first status with titles
			if (loaded.none { it.status == status }) {
				STATUSES.firstOrNull { (k, _) -> loaded.any { it.status == k } }?.let { (k, _) ->
					status = k
					for (i in 0 until (binding?.chipStatus?.childCount ?: 0)) {
						(binding?.chipStatus?.getChildAt(i) as? Chip)?.let { it.isChecked = it.tag == k }
					}
				}
			}
			showStatus()
		}
	}

	private fun showStatus() {
		val b = binding ?: return
		// Chip labels carry their counts once the list is known
		for (i in 0 until b.chipStatus.childCount) {
			val chip = b.chipStatus.getChildAt(i) as? Chip ?: continue
			val key = chip.tag as? String ?: continue
			val label = STATUSES.first { it.first == key }.second
			chip.text = "${getString(label)} ${entries.count { it.status == key }}"
		}
		val shown = entries.filter { it.status == status }
		adapter.submitList(shown)
		b.textEmpty.isVisible = shown.isEmpty() && !b.progress.isVisible
	}

	override fun onDestroyView() {
		binding = null
		super.onDestroyView()
	}

	private class LibraryAdapter(
		private val onClick: (TrackerReadingEntry) -> Unit,
	) : ListAdapter<TrackerReadingEntry, LibraryAdapter.Holder>(Diff) {

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
			Holder(ItemMangaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

		override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

		inner class Holder(private val b: ItemMangaGridBinding) : RecyclerView.ViewHolder(b.root) {

			private var item: TrackerReadingEntry? = null

			init {
				b.root.setOnClickListener { item?.let(onClick) }
				// Plain cover card: title under the cover, no scrim or badges
				b.viewScrim.visibility = View.GONE
				b.textViewTitleOverlay.visibility = View.GONE
				b.corners.root.visibility = View.GONE
				b.textViewTitle.maxLines = 3
			}

			fun bind(e: TrackerReadingEntry) {
				item = e
				b.imageViewCover.setImageAsync(e.coverUrl)
				val ctx = b.root.context
				val progress = when {
					e.totalChapters > 0 -> "${ctx.getString(if (e.isAnime) R.string.home_episode_short else R.string.home_chapter_short, e.chapter)} / ${e.totalChapters}"
					else -> ctx.getString(if (e.isAnime) R.string.home_episode_short else R.string.home_chapter_short, e.chapter)
				}
				b.textViewTitle.text = SpannableStringBuilder(e.title).apply {
					append('\n')
					val start = length
					append(progress)
					setSpan(
						ForegroundColorSpan(ctx.getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)),
						start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
					)
				}
			}
		}

		private object Diff : DiffUtil.ItemCallback<TrackerReadingEntry>() {
			override fun areItemsTheSame(a: TrackerReadingEntry, b: TrackerReadingEntry) =
				a.service == b.service && a.targetId == b.targetId

			override fun areContentsTheSame(a: TrackerReadingEntry, b: TrackerReadingEntry) = a == b
		}
	}

	companion object {

		private const val TAG = "tracker_library"
		private const val ARG_SERVICE = "service"
		private const val ARG_ANIME = "anime"

		private val STATUSES = listOf(
			"READING" to R.string.ds_status_reading,
			"PLANNING" to R.string.ds_status_planning,
			"COMPLETED" to R.string.ds_status_completed,
			"PAUSED" to R.string.ds_status_paused,
			"DROPPED" to R.string.ds_status_dropped,
		)

		fun show(fm: FragmentManager, service: ScrobblerService, anime: Boolean = false) {
			if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
			TrackerLibraryDialog().apply {
				arguments = Bundle().apply {
					putString(ARG_SERVICE, service.name)
					putBoolean(ARG_ANIME, anime)
				}
			}.show(fm, TAG)
		}
	}
}
