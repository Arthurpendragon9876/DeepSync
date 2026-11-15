package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Dialog
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.DialogSyncLibraryBinding
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.GridColumns
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerSynchronizer
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.SyncLibraryEntry
import org.koitharu.kotatsu.scrobbling.common.domain.model.priority
import org.koitharu.kotatsu.scrobbling.common.domain.model.shortName
import javax.inject.Inject

/**
 * The Sync Library: what every signed-in tracker holds, folded into one list. A title on several trackers
 * is one card showing each tracker's progress; a title only one tracker has is listed too, with a dash for
 * the trackers that lack it. Titles open through [TrackerLibraryDialog.Host], like the per-tracker libraries.
 */
@AndroidEntryPoint
class SyncLibraryDialog : DialogFragment() {

	@Inject
	lateinit var synchronizer: TrackerSynchronizer

	@Inject
	lateinit var settings: AppSettings

	private var binding: DialogSyncLibraryBinding? = null
	private var library: TrackerSynchronizer.MergedLibrary? = null
	private var anime = false
	private var status = "READING"

	/** The tracker whose titles are shown, null for all of them. */
	private var tracker: ScrobblerService? = null
	private var loadJob: Job? = null
	private val adapter = SyncAdapter { entry ->
		(parentFragment as? TrackerLibraryDialog.Host)
			?.onLibraryEntryClick(entry.asReadingEntry(), library?.localMangaId(entry))
		dismissAllowingStateLoss()
	}

	override fun getTheme(): Int = R.style.ThemeOverlay_Material3_Dialog_Fullscreen_Deepsync

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
		super.onCreateDialog(savedInstanceState).apply {
			window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val b = DialogSyncLibraryBinding.inflate(inflater, container, false)
		binding = b
		return b.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		val b = binding ?: return
		anime = savedInstanceState?.getBoolean(ARG_ANIME) ?: requireArguments().getBoolean(ARG_ANIME)
		tracker = savedInstanceState?.getString(STATE_TRACKER)?.let { id -> ScrobblerService.values().firstOrNull { it.name == id } }
		b.buttonClose.setOnClickListener { dismissAllowingStateLoss() }
		b.buttonSync.setOnClickListener { synchronize() }
		val grid = GridColumns.columnsFor(resources, GridColumns.referenceWidth(resources), settings.gridSize / 100f)
		b.list.layoutManager = GridLayoutManager(requireContext(), grid)
		val listBottom = b.list.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			v.updatePadding(top = bars.top + (16 * resources.displayMetrics.density).toInt())
			b.list.updatePadding(bottom = listBottom + bars.bottom)
			insets
		}
		b.list.adapter = adapter
		listOf(false to R.string.content_type_manga, true to R.string.ds_mode_anime).forEach { (isAnime, label) ->
			b.chipKind.addView(
				Chip(requireContext()).apply {
					id = View.generateViewId()
					text = getString(label)
					isCheckable = true
					isChecked = isAnime == anime
					setOnClickListener {
						if (anime != isAnime) {
							anime = isAnime
							load()
						}
					}
				},
			)
		}
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
		load()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putBoolean(ARG_ANIME, anime)
		outState.putString(STATE_TRACKER, tracker?.name)
	}

	private fun load() {
		val b = binding ?: return
		loadJob?.cancel()
		b.progress.isVisible = true
		b.textEmpty.isVisible = false
		b.textWarning.isVisible = false
		adapter.submitList(emptyList())
		loadJob = viewLifecycleOwner.lifecycleScope.launch {
			val loaded = withContext(Dispatchers.Default) { synchronizer.loadLibrary(anime) }
			val bb = binding ?: return@launch
			library = loaded
			bb.progress.isVisible = false
			val active = synchronizer.signedIn().filter { !anime || it.supportsAnime }.map { it.scrobblerService }
				.sortedBy { it.priority() }
			val trackers = active.size
			buildTrackerChips(bb, active)
			bb.textCount.text = getString(R.string.ds_sync_library_count, loaded.entries.size, trackers)
			bb.textWarning.text = loaded.unreachable.joinToString("\n") {
				getString(R.string.ds_sync_unreachable, getString(it.titleResId))
			}
			bb.textWarning.isVisible = loaded.unreachable.isNotEmpty()
			// Land on the first filter that has titles
			if (loaded.entries.none { matches(it) }) {
				STATUSES.firstOrNull { (k, _) -> loaded.entries.any { matches(it, k) } }?.let { (k, _) ->
					status = k
					for (i in 0 until bb.chipStatus.childCount) {
						(bb.chipStatus.getChildAt(i) as? Chip)?.let { it.isChecked = it.tag == k }
					}
				}
			}
			showStatus()
		}
	}

	/** "All trackers" plus one chip per signed-in tracker; hidden when there is nothing to choose between. */
	private fun buildTrackerChips(b: DialogSyncLibraryBinding, active: List<ScrobblerService>) {
		if (tracker !in active) tracker = null
		b.chipTracker.removeAllViews()
		b.scrollTracker.isVisible = active.size >= 2
		val options = listOf<ScrobblerService?>(null) + active
		options.forEach { service ->
			b.chipTracker.addView(
				Chip(requireContext()).apply {
					id = View.generateViewId()
					text = service?.let { getString(it.titleResId) } ?: getString(R.string.ds_sync_filter_all)
					isCheckable = true
					isChecked = service == tracker
					setOnClickListener {
						if (tracker != service) {
							tracker = service
							showStatus()
						}
					}
				},
			)
		}
	}

	/**
	 * With a tracker chosen only the titles that tracker has are listed, and their status is that tracker's own
	 * (a title can be "Reading" on one tracker and "Planning" on another). "Out of sync" is about the title as a whole.
	 */
	private fun matches(e: SyncLibraryEntry, key: String = status): Boolean {
		val chosen = tracker
		if (chosen != null && chosen !in e.entries) return false
		return when {
			key == KEY_OUT_OF_SYNC -> e.isOutOfSync
			chosen != null -> e.entries.getValue(chosen).status == key
			else -> e.status == key
		}
	}

	private fun showStatus() {
		val b = binding ?: return
		val all = library?.entries.orEmpty()
		for (i in 0 until b.chipStatus.childCount) {
			val chip = b.chipStatus.getChildAt(i) as? Chip ?: continue
			val key = chip.tag as? String ?: continue
			val label = STATUSES.first { it.first == key }.second
			chip.text = "${getString(label)} ${all.count { matches(it, key) }}"
		}
		val shown = all.filter { matches(it) }
		adapter.submitList(shown)
		b.textEmpty.isVisible = shown.isEmpty() && !b.progress.isVisible
	}

	private fun synchronize() {
		val b = binding ?: return
		if (!synchronizer.canSync) {
			Toast.makeText(requireContext(), R.string.ds_sync_need_two, Toast.LENGTH_LONG).show()
			return
		}
		b.buttonSync.isEnabled = false
		b.buttonSync.setText(R.string.ds_sync_running)
		viewLifecycleOwner.lifecycleScope.launch {
			val report = withContext(Dispatchers.Default) {
				runCatchingCancellable { synchronizer.synchronize(anime) }.getOrNull()
			}
			val ctx = context ?: return@launch
			binding?.buttonSync?.apply {
				isEnabled = true
				setText(R.string.ds_sync_now)
			}
			val message = when {
				report == null -> getString(R.string.ds_track_failed)
				report.failed > 0 -> getString(R.string.ds_sync_done_failed, report.failed)
				report.updated > 0 -> getString(R.string.ds_sync_done, report.updated)
				else -> getString(R.string.ds_sync_done_none)
			}
			Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
			load()
		}
	}

	override fun onDestroyView() {
		loadJob?.cancel()
		binding = null
		super.onDestroyView()
	}

	private class SyncAdapter(
		private val onClick: (SyncLibraryEntry) -> Unit,
	) : ListAdapter<SyncLibraryEntry, SyncAdapter.Holder>(Diff) {

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
			Holder(ItemMangaGridBinding.inflate(LayoutInflater.from(parent.context), parent, false))

		override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

		inner class Holder(private val b: ItemMangaGridBinding) : RecyclerView.ViewHolder(b.root) {

			private var item: SyncLibraryEntry? = null

			init {
				b.root.setOnClickListener { item?.let(onClick) }
				// Plain cover card: title under the cover, no scrim or badges
				b.viewScrim.visibility = View.GONE
				b.textViewTitleOverlay.visibility = View.GONE
				b.corners.root.visibility = View.GONE
				b.textViewTitle.maxLines = 4
			}

			fun bind(e: SyncLibraryEntry) {
				item = e
				b.imageViewCover.setImageAsync(e.coverUrl)
				val ctx = b.root.context
				val unit = if (e.isAnime) R.string.home_episode_short else R.string.home_chapter_short
				val progress = ctx.getString(unit, e.progress) + if (e.totalChapters > 0) " / ${e.totalChapters}" else ""
				// "AL 45 · MAL 40 · MB —": what each tracker has, a dash where it has nothing
				val perTracker = (e.entries.keys + e.missingOn).sortedBy { it.priority() }.joinToString(" · ") { s ->
					e.entries[s]?.let { "${s.shortName} ${it.chapter}" } ?: "${s.shortName} —"
				}
				val muted = ctx.getThemeColor(materialR.attr.colorOnSurfaceVariant)
				val trackerColor = if (e.isOutOfSync) ctx.getThemeColor(appcompatR.attr.colorError) else muted
				b.textViewTitle.text = SpannableStringBuilder(e.title).apply {
					append('\n')
					var start = length
					append(progress)
					setSpan(ForegroundColorSpan(muted), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
					append('\n')
					start = length
					append(perTracker)
					setSpan(ForegroundColorSpan(trackerColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				}
			}
		}

		private object Diff : DiffUtil.ItemCallback<SyncLibraryEntry>() {
			override fun areItemsTheSame(a: SyncLibraryEntry, b: SyncLibraryEntry) =
				a.title == b.title && a.entries.keys == b.entries.keys

			override fun areContentsTheSame(a: SyncLibraryEntry, b: SyncLibraryEntry) = a == b
		}
	}

	companion object {

		private const val TAG = "sync_library"
		private const val ARG_ANIME = "anime"
		private const val STATE_TRACKER = "tracker"
		private const val KEY_OUT_OF_SYNC = "OUT_OF_SYNC"

		private val STATUSES = listOf(
			"READING" to R.string.ds_status_reading,
			"PLANNING" to R.string.ds_status_planning,
			"COMPLETED" to R.string.ds_status_completed,
			"PAUSED" to R.string.ds_status_paused,
			"DROPPED" to R.string.ds_status_dropped,
			KEY_OUT_OF_SYNC to R.string.ds_sync_out_of_sync,
		)

		fun show(fm: FragmentManager, anime: Boolean = false) {
			if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
			SyncLibraryDialog().apply {
				arguments = Bundle().apply { putBoolean(ARG_ANIME, anime) }
			}.show(fm, TAG)
		}
	}
}
