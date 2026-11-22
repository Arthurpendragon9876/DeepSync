package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.databinding.DialogLibraryBinding
import org.koitharu.kotatsu.list.ui.GridColumns
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
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
 *
 * Laid out like the Favourites tab (see dialog_library.xml): the search bar searches this library only.
 */
@AndroidEntryPoint
class SyncLibraryDialog : DialogFragment() {

	@Inject
	lateinit var synchronizer: TrackerSynchronizer

	@Inject
	lateinit var settings: AppSettings

	private var binding: DialogLibraryBinding? = null
	private var library: TrackerSynchronizer.MergedLibrary? = null
	private var anime = false
	private var status = DEFAULT_STATUS
	private var query = ""

	/** The tracker whose titles are shown, null for all of them. */
	private var tracker: ScrobblerService? = null

	/** Signed-in trackers that have this kind of library, best first; the tracker choices of the filter sheet. */
	private var activeTrackers: List<ScrobblerService> = emptyList()
	private var loadJob: Job? = null
	private var adapter: LibraryCardAdapter<SyncLibraryEntry>? = null

	override fun getTheme(): Int = R.style.ThemeOverlay_Material3_Dialog_Fullscreen_Deepsync

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
		super.onCreateDialog(savedInstanceState).apply {
			window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val b = DialogLibraryBinding.inflate(inflater, container, false)
		binding = b
		return b.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		val b = binding ?: return
		anime = savedInstanceState?.getBoolean(ARG_ANIME) ?: requireArguments().getBoolean(ARG_ANIME)
		tracker = savedInstanceState?.getString(STATE_TRACKER)?.let { id -> ScrobblerService.values().firstOrNull { it.name == id } }
		query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
		b.buttonClose.setOnClickListener { dismissAllowingStateLoss() }
		b.buttonSync.isVisible = true
		b.buttonSync.setOnClickListener { synchronize() }
		b.editSearch.hint = getString(R.string.ds_library_search, getString(R.string.ds_sync_library))
		b.editSearch.setText(query)
		b.buttonClear.isVisible = query.isNotEmpty()
		b.buttonClear.setOnClickListener { b.editSearch.text = null }
		b.editSearch.doAfterTextChanged {
			query = it?.toString().orEmpty().trim()
			binding?.buttonClear?.isVisible = it?.isNotEmpty() == true
			showStatus()
		}
		b.editSearch.setOnEditorActionListener { v, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_SEARCH) {
				v.clearFocus()
				true
			} else {
				false
			}
		}
		val grid = GridColumns.columnsFor(resources, GridColumns.referenceWidth(resources), settings.gridSize / 100f)
		b.list.layoutManager = GridLayoutManager(requireContext(), grid)
		val listBottom = b.list.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
			b.list.updatePadding(bottom = listBottom + bars.bottom)
			insets
		}
		val cards = LibraryCardAdapter<SyncLibraryEntry>(
			DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		) { entry ->
			(parentFragment as? TrackerLibraryDialog.Host)
				?.onLibraryEntryClick(entry.asReadingEntry(), library?.localMangaId(entry))
			dismissAllowingStateLoss()
		}
		adapter = cards
		b.list.adapter = cards
		// Manga | Anime tabs, like the Favourites category tabs
		b.tabsRow.isVisible = true
		b.tabs.isVisible = true
		b.tabs.addTab(b.tabs.newTab().setText(R.string.content_type_manga), !anime)
		b.tabs.addTab(b.tabs.newTab().setText(R.string.ds_mode_anime), anime)
		b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
			override fun onTabSelected(tab: TabLayout.Tab) {
				val isAnime = tab.position == 1
				if (anime != isAnime) {
					anime = isAnime
					load()
				}
			}

			override fun onTabUnselected(tab: TabLayout.Tab) = Unit

			override fun onTabReselected(tab: TabLayout.Tab) = Unit
		})
		b.buttonFilter.setOnClickListener { showFilters() }
		load()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putBoolean(ARG_ANIME, anime)
		outState.putString(STATE_TRACKER, tracker?.name)
		outState.putString(STATE_QUERY, query)
	}

	private fun load() {
		val b = binding ?: return
		loadJob?.cancel()
		b.progress.isVisible = true
		b.textEmpty.isVisible = false
		b.textWarning.isVisible = false
		adapter?.submitList(emptyList())
		loadJob = viewLifecycleOwner.lifecycleScope.launch {
			val loaded = withContext(Dispatchers.Default) { synchronizer.loadLibrary(anime) }
			val bb = binding ?: return@launch
			library = loaded
			bb.progress.isVisible = false
			val active = synchronizer.signedIn().filter { !anime || it.supportsAnime }.map { it.scrobblerService }
				.sortedBy { it.priority() }
			activeTrackers = active
			if (tracker !in active) tracker = null
			bb.textWarning.text = loaded.unreachable.joinToString("\n") {
				getString(R.string.ds_sync_unreachable, getString(it.titleResId))
			}
			bb.textWarning.isVisible = loaded.unreachable.isNotEmpty()
			// Land on the first filter that has titles
			if (loaded.entries.none { matches(it, status, searching = false) }) {
				STATUSES.firstOrNull { (k, _) -> loaded.entries.any { matches(it, k, searching = false) } }?.let { (k, _) ->
					status = k
				}
			}
			showStatus()
		}
	}

	/**
	 * The filter sheet: the status, and "All trackers" plus one choice per signed-in tracker (only offered with
	 * two or more). Its labels carry their counts, which follow the search and the tracker chosen.
	 */
	private fun showFilters() {
		LibraryFilterSheet.show(
			context = requireContext(),
			statuses = STATUSES.map { it.first },
			statusLabel = { key ->
				val label = STATUSES.first { it.first == key }.second
				"${getString(label)} ${library?.entries.orEmpty().count { matches(it, key) }}"
			},
			currentStatus = { status },
			onStatus = {
				status = it
				showStatus()
			},
			trackers = listOf(LibraryFilterSheet.Choice(null, getString(R.string.ds_sync_filter_all))) +
				activeTrackers.map { LibraryFilterSheet.Choice(it.name, getString(it.titleResId)) },
			currentTracker = { tracker?.name },
			onTracker = { key ->
				tracker = key?.let { id -> activeTrackers.firstOrNull { it.name == id } }
				showStatus()
			},
		)
	}

	/**
	 * With a tracker chosen only the titles that tracker has are listed, and their status is that tracker's own
	 * (a title can be "Reading" on one tracker and "Planning" on another). "Out of sync" is about the title as a whole.
	 * The search box narrows every status, so the chips' counts follow it.
	 */
	private fun matches(e: SyncLibraryEntry, key: String = status, searching: Boolean = true): Boolean {
		if (searching && query.isNotEmpty() && !e.matchesQuery(query)) return false
		val chosen = tracker
		if (chosen != null && chosen !in e.entries) return false
		return when {
			key == KEY_OUT_OF_SYNC -> e.isOutOfSync
			chosen != null -> e.entries.getValue(chosen).status == key
			else -> e.status == key
		}
	}

	private fun SyncLibraryEntry.matchesQuery(q: String): Boolean =
		title.contains(q, ignoreCase = true) || altTitles.any { it.contains(q, ignoreCase = true) }

	private fun showStatus() {
		val b = binding ?: return
		val all = library?.entries.orEmpty()
		b.filterDot.isVisible = status != DEFAULT_STATUS || tracker != null
		val shown = all.filter { matches(it) }.map { it.toCard() }
		adapter?.submitList(shown)
		b.textEmpty.isVisible = shown.isEmpty() && !b.progress.isVisible
	}

	private fun SyncLibraryEntry.toCard(): LibraryCard<SyncLibraryEntry> {
		// "AL 45 · MAL 40 · MB —": what each tracker has, a dash where it has nothing
		val perTracker = (entries.keys + missingOn).sortedBy { it.priority() }.joinToString(" · ") { s ->
			entries[s]?.let { "${s.shortName} ${it.chapter}" } ?: "${s.shortName} —"
		}
		return LibraryCard(
			payload = this,
			key = title + entries.keys.joinToString { it.name },
			title = title,
			coverUrl = coverUrl,
			progress = progress,
			total = totalChapters,
			completed = status == "COMPLETED",
			detail = perTracker,
			detailIsWarning = isOutOfSync,
		)
	}

	private fun synchronize() {
		val b = binding ?: return
		if (!synchronizer.canSync) {
			Toast.makeText(requireContext(), R.string.ds_sync_need_two, Toast.LENGTH_LONG).show()
			return
		}
		b.buttonSync.isEnabled = false
		b.progress.isVisible = true
		viewLifecycleOwner.lifecycleScope.launch {
			val report = withContext(Dispatchers.Default) {
				runCatchingCancellable { synchronizer.synchronize(anime) }.getOrNull()
			}
			val ctx = context ?: return@launch
			binding?.buttonSync?.isEnabled = true
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
		adapter = null
		binding = null
		super.onDestroyView()
	}

	companion object {

		private const val TAG = "sync_library"
		private const val ARG_ANIME = "anime"
		private const val STATE_TRACKER = "tracker"
		private const val STATE_QUERY = "query"
		private const val KEY_OUT_OF_SYNC = "OUT_OF_SYNC"
		private const val DEFAULT_STATUS = "READING"

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
