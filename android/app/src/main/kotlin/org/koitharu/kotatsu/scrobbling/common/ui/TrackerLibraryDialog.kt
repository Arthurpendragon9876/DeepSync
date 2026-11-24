package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.databinding.DialogLibraryBinding
import org.koitharu.kotatsu.list.ui.GridColumns
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import javax.inject.Inject

/**
 * The whole account of one tracker, every status, opened from "See all" on Home. Reading is shown
 * first; the other statuses are a tap away. Titles open through [Host], which knows how to find them.
 *
 * Laid out like the Favourites tab (see dialog_library.xml): the search bar searches this library only.
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

	private var binding: DialogLibraryBinding? = null
	private var entries: List<TrackerReadingEntry> = emptyList()
	private var links: Map<Long, Long> = emptyMap()
	private var status = DEFAULT_STATUS
	private var query = ""
	private var anime = false

	/** Simkl's second tab: its shows, as opposed to its anime. */
	private var tv = false
	private var loadJob: Job? = null
	private var adapter: LibraryCardAdapter<TrackerReadingEntry>? = null

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
		val service = ScrobblerService.values().firstOrNull { it.name == requireArguments().getString(ARG_SERVICE) }
		anime = savedInstanceState?.getBoolean(ARG_ANIME) ?: requireArguments().getBoolean(ARG_ANIME)
		tv = savedInstanceState?.getBoolean(ARG_TV) ?: requireArguments().getBoolean(ARG_TV)
		query = savedInstanceState?.getString(STATE_QUERY).orEmpty()
		val scrobbler = scrobblers.firstOrNull { it.scrobblerService == service }
		if (service == null || scrobbler == null) {
			dismissAllowingStateLoss()
			return
		}
		b.buttonClose.setOnClickListener { dismissAllowingStateLoss() }
		b.editSearch.hint = getString(R.string.ds_library_search, getString(service.titleResId))
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
		// Same cover grid as the Favourites tab, at the user's grid size
		val grid = GridColumns.columnsFor(resources, GridColumns.referenceWidth(resources), settings.gridSize / 100f)
		b.list.layoutManager = GridLayoutManager(requireContext(), grid)
		// Keep the header clear of the status bar and the list clear of the gesture bar
		val listBottom = b.list.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
			b.list.updatePadding(bottom = listBottom + bars.bottom)
			insets
		}
		val cards = LibraryCardAdapter<TrackerReadingEntry>(
			DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		) { entry ->
			(parentFragment as? Host)?.onLibraryEntryClick(entry, links[entry.targetId])
			dismissAllowingStateLoss()
		}
		adapter = cards
		b.list.adapter = cards
		if (service == ScrobblerService.SIMKL) {
			// Simkl has no manga: its tabs are the two halves of Streaming
			anime = true
			b.tabsRow.isVisible = true
			b.tabs.isVisible = true
			b.tabs.addTab(b.tabs.newTab().setText(R.string.ds_mode_anime), !tv)
			b.tabs.addTab(b.tabs.newTab().setText(R.string.ds_mode_tv_movies), tv)
			b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
				override fun onTabSelected(tab: TabLayout.Tab) {
					val isTv = tab.position == 1
					if (tv != isTv) {
						tv = isTv
						load(scrobbler, service)
					}
				}

				override fun onTabUnselected(tab: TabLayout.Tab) = Unit

				override fun onTabReselected(tab: TabLayout.Tab) = Unit
			})
		} else if (scrobbler.supportsAnime) {
			// Manga | Anime tabs, only for a tracker that has both
			b.tabsRow.isVisible = true
			b.tabs.isVisible = true
			b.tabs.addTab(b.tabs.newTab().setText(R.string.content_type_manga), !anime)
			b.tabs.addTab(b.tabs.newTab().setText(R.string.ds_mode_anime), anime)
			b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
				override fun onTabSelected(tab: TabLayout.Tab) {
					val isAnime = tab.position == 1
					if (anime != isAnime) {
						anime = isAnime
						load(scrobbler, service)
					}
				}

				override fun onTabUnselected(tab: TabLayout.Tab) = Unit

				override fun onTabReselected(tab: TabLayout.Tab) = Unit
			})
		} else {
			anime = false
		}
		b.buttonFilter.setOnClickListener { showFilters() }
		load(scrobbler, service)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putBoolean(ARG_ANIME, anime)
		outState.putBoolean(ARG_TV, tv)
		outState.putString(STATE_QUERY, query)
	}

	private fun load(scrobbler: Scrobbler, service: ScrobblerService) {
		val b = binding ?: return
		loadJob?.cancel()
		b.progress.isVisible = true
		b.textEmpty.isVisible = false
		adapter?.submitList(emptyList())
		loadJob = viewLifecycleOwner.lifecycleScope.launch {
			val loaded = withContext(Dispatchers.Default) { if (tv) scrobbler.getTvLibrary() else scrobbler.getLibrary(anime) }
			links = withContext(Dispatchers.Default) {
				runCatching { db.getScrobblingDao().observe(service.id).first().associate { it.targetId to it.mangaId } }
					.getOrDefault(emptyMap())
			}
			entries = loaded
			val bb = binding ?: return@launch
			bb.progress.isVisible = false
			// Reading may be empty while something else is not: land on the first status with titles
			if (loaded.none { it.status == status }) {
				STATUSES.firstOrNull { (k, _) -> loaded.any { it.status == k } }?.let { (k, _) ->
					status = k
				}
			}
			showStatus()
		}
	}

	private fun matches(e: TrackerReadingEntry, key: String): Boolean {
		if (e.status != key) return false
		if (query.isEmpty()) return true
		return e.title.contains(query, ignoreCase = true) || e.altTitles.any { it.contains(query, ignoreCase = true) }
	}

	/** The filter sheet; its labels carry their counts, which the search narrows too. */
	private fun showFilters() {
		LibraryFilterSheet.show(
			context = requireContext(),
			statuses = STATUSES.map { it.first },
			statusLabel = { key ->
				val label = STATUSES.first { it.first == key }.second
				"${getString(label)} ${entries.count { matches(it, key) }}"
			},
			currentStatus = { status },
			onStatus = {
				status = it
				showStatus()
			},
		)
	}

	private fun showStatus() {
		val b = binding ?: return
		b.filterDot.isVisible = status != DEFAULT_STATUS
		val shown = entries.filter { matches(it, status) }.map { e ->
			LibraryCard(
				payload = e,
				key = "${e.service.name}:${e.targetId}",
				title = e.title,
				coverUrl = e.coverUrl,
				progress = e.chapter,
				total = e.totalChapters,
				completed = e.status == "COMPLETED",
			)
		}
		adapter?.submitList(shown)
		b.textEmpty.isVisible = shown.isEmpty() && !b.progress.isVisible
	}

	override fun onDestroyView() {
		loadJob?.cancel()
		adapter = null
		binding = null
		super.onDestroyView()
	}

	companion object {

		private const val TAG = "tracker_library"
		private const val ARG_SERVICE = "service"
		private const val ARG_ANIME = "anime"
		private const val ARG_TV = "tv"
		private const val STATE_QUERY = "query"
		private const val DEFAULT_STATUS = "READING"

		private val STATUSES = listOf(
			"READING" to R.string.ds_status_reading,
			"PLANNING" to R.string.ds_status_planning,
			"COMPLETED" to R.string.ds_status_completed,
			"PAUSED" to R.string.ds_status_paused,
			"DROPPED" to R.string.ds_status_dropped,
		)

		fun show(fm: FragmentManager, service: ScrobblerService, anime: Boolean = false, tv: Boolean = false) {
			if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
			TrackerLibraryDialog().apply {
				arguments = Bundle().apply {
					putString(ARG_SERVICE, service.name)
					putBoolean(ARG_ANIME, anime)
					putBoolean(ARG_TV, tv)
				}
			}.show(fm, TAG)
		}
	}
}
