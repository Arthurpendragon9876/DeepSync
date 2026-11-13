package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.databinding.DialogTrackerLinkBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.scrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.tryScrobble
import javax.inject.Inject

/**
 * The tracker window: pick a tracker, see results for every name the title has, tap one to link it.
 * Opened by the "Track this title?" prompt.
 */
@AndroidEntryPoint
class TrackerLinkDialog : DialogFragment() {

	@Inject
	lateinit var coordinator: TrackPromptCoordinator

	@Inject
	lateinit var scrobblers: Set<@JvmSuppressWildcards Scrobbler>

	private var binding: DialogTrackerLinkBinding? = null
	private var manga: Manga? = null
	private var current: Scrobbler? = null
	private var searchJob: Job? = null
	private val adapter = CandidateAdapter(::link)

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		manga = coordinator.manga(requireArguments().getLong(ARG_MANGA))
		val b = DialogTrackerLinkBinding.inflate(LayoutInflater.from(requireContext()))
		binding = b
		b.list.layoutManager = LinearLayoutManager(requireContext())
		b.list.adapter = adapter
		return buildAlertDialog(requireContext()) {
			setTitle(R.string.ds_track_dialog_title)
			setView(b.root)
			setNegativeButton(android.R.string.cancel, null)
		}
	}

	override fun onStart() {
		super.onStart()
		val b = binding ?: return
		val target = manga
		if (target == null) {
			// The process was recreated and the title is gone: nothing to link
			dismissAllowingStateLoss()
			return
		}
		val names = allNames(target)
		// Every name the title has (romaji, English, alternatives...) as a chip. "All names" searches with
		// all of them at once; tapping one searches with just that name.
		b.chipNames.addView(
			Chip(requireContext()).apply {
				text = getString(R.string.ds_track_all_names)
				setOnClickListener {
					b.editQuery.setText(target.title)
					search(typed = false)
				}
			},
		)
		names.take(MAX_NAME_CHIPS).forEach { name ->
			b.chipNames.addView(
				Chip(requireContext()).apply {
					text = name
					setOnClickListener {
						b.editQuery.setText(name)
						search(typed = true)
					}
				},
			)
		}
		b.editQuery.setText(target.title)
		scrobblers.filter { it.isEnabled }.forEachIndexed { index, scrobbler ->
			val chip = Chip(requireContext()).apply {
				id = View.generateViewId()
				text = getString(scrobbler.scrobblerService.titleResId)
				isCheckable = true
				isChecked = index == 0
				setOnClickListener {
					current = scrobbler
					search(typed = false)
				}
			}
			b.chipTrackers.addView(chip)
			if (index == 0) current = scrobbler
		}
		// Typing refines the search; leaving it alone searches with every name
		b.editQuery.doAfterTextChanged {
			if (b.editQuery.hasFocus()) {
				searchJob?.cancel()
				searchJob = viewLifecycleOwnerScope().launch {
					delay(450)
					search(typed = true)
				}
			}
		}
		search(typed = false)
	}

	override fun onDestroyView() {
		binding = null
		super.onDestroyView()
	}

	private fun viewLifecycleOwnerScope() = lifecycleScope

	private fun allNames(m: Manga): List<String> =
		(listOf(m.title) + m.altTitles).filter { it.isNotBlank() }.distinct()

	private fun search(typed: Boolean) {
		val b = binding ?: return
		val scrobbler = current ?: return
		val target = manga ?: return
		val names = if (typed) listOf(b.editQuery.text?.toString().orEmpty()) else allNames(target)
		if (names.all { it.isBlank() }) return
		searchJob?.cancel()
		b.progress.isVisible = true
		b.textEmpty.isVisible = false
		searchJob = lifecycleScope.launch {
			val type = target.source.scrobblerType
			val result = withContext(Dispatchers.Default) {
				scrobbler.searchSmart(names, type, if (typed) null else target)
			}
			val bb = binding ?: return@launch
			bb.progress.isVisible = false
			adapter.submitList(result)
			bb.textEmpty.isVisible = result.isEmpty()
		}
	}

	private fun link(candidate: TrackerCandidate) {
		val scrobbler = current ?: return
		val target = manga ?: return
		val chapterId = requireArguments().getLong(ARG_CHAPTER)
		binding?.progress?.isVisible = true
		lifecycleScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.Default) {
					// A tracker that is already ahead of local history keeps its own progress
					val canPush = scrobbler.linkManga(target.id, candidate.manga.id, ScrobblingStatus.READING)
					if (canPush) scrobbler.tryScrobble(target, chapterId)
				}
			}.onSuccess {
				val ctx = context ?: return@onSuccess
				Toast.makeText(
					ctx,
					getString(R.string.ds_track_linked, getString(scrobbler.scrobblerService.titleResId)),
					Toast.LENGTH_SHORT,
				).show()
				dismissAllowingStateLoss()
			}.onFailure {
				it.printStackTraceDebug()
				binding?.progress?.isVisible = false
				context?.let { ctx -> Toast.makeText(ctx, R.string.ds_track_failed, Toast.LENGTH_LONG).show() }
			}
		}
	}

	private class CandidateAdapter(
		private val onClick: (TrackerCandidate) -> Unit,
	) : ListAdapter<TrackerCandidate, CandidateAdapter.Holder>(Diff) {

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tracker_candidate, parent, false)
			return Holder(v)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

		inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

			private val title = view.findViewById<TextView>(R.id.text_title)
			private val subtitle = view.findViewById<TextView>(R.id.text_subtitle)
			private val badge = view.findViewById<TextView>(R.id.text_badge)
			private val cover = view.findViewById<org.koitharu.kotatsu.image.ui.CoverImageView>(R.id.image_cover)
			private var item: TrackerCandidate? = null

			init {
				view.setOnClickListener { item?.let(onClick) }
			}

			fun bind(c: TrackerCandidate) {
				item = c
				title.text = c.manga.name
				// All the names the tracker has for this title, not just the one that matched
				val others = c.names.filter { it != c.manga.name }.distinct().take(MAX_NAMES_SHOWN)
				val known = if (others.isEmpty()) {
					c.manga.altName.orEmpty()
				} else {
					subtitle.context.getString(R.string.ds_track_also_known, others.joinToString(" · "))
				}
				// Manhwa / Manga / Novel up front, so two same-named entries can be told apart at a glance
				subtitle.text = listOfNotNull(c.kindLabel, known.ifEmpty { null }).joinToString(" · ")
				subtitle.isVisible = subtitle.text.isNotEmpty()
				badge.isVisible = c.score >= 1f
				cover.setImageAsync(c.manga.cover)
			}
		}

		private object Diff : DiffUtil.ItemCallback<TrackerCandidate>() {
			override fun areItemsTheSame(a: TrackerCandidate, b: TrackerCandidate) = a.manga.id == b.manga.id
			override fun areContentsTheSame(a: TrackerCandidate, b: TrackerCandidate) = a == b
		}
	}

	companion object {

		const val TAG = "tracker_link"
		private const val MAX_NAME_CHIPS = 12
		private const val MAX_NAMES_SHOWN = 5
		private const val ARG_MANGA = "manga"
		private const val ARG_CHAPTER = "chapter"

		fun show(fm: FragmentManager, mangaId: Long, chapterId: Long) {
			if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
			TrackerLinkDialog().apply {
				arguments = Bundle().apply {
					putLong(ARG_MANGA, mangaId)
					putLong(ARG_CHAPTER, chapterId)
				}
			}.show(fm, TAG)
		}
	}
}
