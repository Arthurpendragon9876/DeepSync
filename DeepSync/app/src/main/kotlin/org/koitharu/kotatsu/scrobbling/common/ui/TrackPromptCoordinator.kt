package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.R as appcompatR
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemTrackMatchBinding
import org.koitharu.kotatsu.databinding.SheetTrackPromptBinding
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.scrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.tryScrobble
import javax.inject.Provider
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks "Track this title?" whenever a chapter of a title that no signed-in tracker knows is opened.
 *
 * History saving has no screen of its own, so this keeps track of whichever activity is on top
 * and shows the question there. "Yes" opens [TrackerLinkDialog], the tracker window.
 */
@Singleton
class TrackPromptCoordinator @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
	// A Provider: the scrobblers reach back into history, which owns this class
	private val scrobblersProvider: Provider<Set<@JvmSuppressWildcards Scrobbler>>,
) {

	private val main = Handler(Looper.getMainLooper())
	private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private var top: WeakReference<FragmentActivity>? = null
	private var isShowing = false
	private val pending = ConcurrentHashMap<Long, Manga>()
	private val recent = ConcurrentHashMap<String, Long>()

	init {
		(context.applicationContext as Application).registerActivityLifecycleCallbacks(
			object : Application.ActivityLifecycleCallbacks {
				override fun onActivityResumed(activity: Activity) {
					if (activity is FragmentActivity) top = WeakReference(activity)
				}

				override fun onActivityPaused(activity: Activity) {
					if (top?.get() === activity) top = null
				}

				override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
				override fun onActivityStarted(activity: Activity) = Unit
				override fun onActivityStopped(activity: Activity) = Unit
				override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
				override fun onActivityDestroyed(activity: Activity) = Unit
			},
		)
	}

	/** The manga a prompt or the tracker window was opened for. */
	fun manga(id: Long): Manga? = pending[id]

	fun request(manga: Manga, chapterId: Long) {
		val key = "${manga.id}:$chapterId"
		val now = System.currentTimeMillis()
		// The same chapter reported twice in a moment is one tap, not two
		if (recent[key]?.let { now - it < DEBOUNCE_MS } == true) return
		recent[key] = now
		pending[manga.id] = manga
		main.post { show(manga, chapterId, attempt = 0) }
	}

	private fun show(manga: Manga, chapterId: Long, attempt: Int) {
		val activity = top?.get()
		// The reader may still be starting up, so wait for it to come to the front
		if (activity == null || activity.isFinishing || activity.supportFragmentManager.isStateSaved) {
			if (attempt < MAX_ATTEMPTS) main.postDelayed({ show(manga, chapterId, attempt + 1) }, RETRY_MS)
			return
		}
		if (isShowing || activity.supportFragmentManager.findFragmentByTag(TrackerLinkDialog.TAG) != null) return
		isShowing = true
		showSheet(activity, manga, chapterId)
	}

	/**
	 * "Save progress?" bottom sheet: the closest matches on the tracker with their covers (best one picked),
	 * Save to link it, "This device only" to keep the series local for good, No to skip for now.
	 */
	private fun showSheet(activity: FragmentActivity, manga: Manga, chapterId: Long) {
		val scrobbler = scrobblersProvider.get().firstOrNull { it.isEnabled }
		val b = SheetTrackPromptBinding.inflate(LayoutInflater.from(activity))
		val dialog = BottomSheetDialog(activity)
		dialog.setContentView(b.root)
		dialog.setOnDismissListener { isShowing = false }
		val trackerName = scrobbler?.let { activity.getString(it.scrobblerService.titleResId) }.orEmpty()
		b.textMessage.text = activity.getString(R.string.ds_track_sheet_message, manga.title, trackerName)
		var selected: TrackerCandidate? = null
		val adapter = MatchAdapter { candidate ->
			selected = candidate
			b.buttonSave.isEnabled = true
		}
		b.listMatches.adapter = adapter

		fun remember() {
			settings.scrobblingNeverAsk = settings.scrobblingNeverAsk + manga.id
		}
		b.buttonSave.setOnClickListener {
			val target = selected
			if (scrobbler != null && target != null) link(activity, scrobbler, manga, chapterId, target)
			dialog.dismiss()
		}
		b.buttonDevice.setOnClickListener {
			remember()
			dialog.dismiss()
		}
		b.buttonNo.setOnClickListener {
			if (b.switchRemember.isChecked) remember()
			dialog.dismiss()
		}
		b.buttonSearch.setOnClickListener {
			dialog.dismiss()
			TrackerLinkDialog.show(activity.supportFragmentManager, manga.id, chapterId)
		}
		dialog.behavior.skipCollapsed = true
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.show()

		if (scrobbler == null) {
			b.progress.isVisible = false
			return
		}
		activity.lifecycleScope.launch {
			val names = (listOf(manga.title) + manga.altTitles).filter { it.isNotBlank() }.distinct()
			val type = manga.source.scrobblerType
			val matches = runCatchingCancellable {
				withContext(Dispatchers.Default) { scrobbler.searchSmart(names, type) }
			}.getOrDefault(emptyList()).sortedByDescending { it.score }.take(MAX_MATCHES)
			if (!dialog.isShowing) return@launch
			b.progress.isVisible = false
			if (matches.isEmpty()) {
				b.textNone.text = activity.getString(R.string.ds_track_no_matches, trackerName)
				b.textNone.isVisible = true
			} else {
				// The closest match starts out chosen, so Save is one tap
				selected = matches.first()
				b.buttonSave.isEnabled = true
				adapter.submit(matches, matches.first().manga.id)
				b.listMatches.isVisible = true
			}
		}
	}

	private fun link(
		activity: FragmentActivity,
		scrobbler: Scrobbler,
		manga: Manga,
		chapterId: Long,
		candidate: TrackerCandidate,
	) {
		// The activity scope would be cancelled with the reader; linking has to outlive the sheet
		processScope.launch(Dispatchers.Default) {
			runCatchingCancellable {
				val canPush = scrobbler.linkManga(manga.id, candidate.manga.id, ScrobblingStatus.READING)
				if (canPush) scrobbler.tryScrobble(manga, chapterId)
			}.onSuccess {
				main.post {
					Toast.makeText(
						activity.applicationContext,
						activity.getString(R.string.ds_track_linked, activity.getString(scrobbler.scrobblerService.titleResId)),
						Toast.LENGTH_SHORT,
					).show()
				}
			}.onFailure {
				main.post { Toast.makeText(activity.applicationContext, R.string.ds_track_failed, Toast.LENGTH_LONG).show() }
			}
		}
	}

	private class MatchAdapter(
		private val onSelected: (TrackerCandidate) -> Unit,
	) : RecyclerView.Adapter<MatchAdapter.Holder>() {

		private var items: List<TrackerCandidate> = emptyList()
		private var selectedId = -1L

		fun submit(list: List<TrackerCandidate>, selected: Long) {
			items = list
			selectedId = selected
			notifyDataSetChanged()
		}

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
			Holder(ItemTrackMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

		override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position == 0)

		inner class Holder(private val binding: ItemTrackMatchBinding) : RecyclerView.ViewHolder(binding.root) {

			fun bind(c: TrackerCandidate, isBest: Boolean) {
				binding.textTitle.text = c.manga.name
				binding.textBadge.visibility = if (isBest) View.VISIBLE else View.INVISIBLE
				binding.imageCover.setImageAsync(c.manga.cover)
				val chosen = c.manga.id == selectedId
				binding.card.strokeWidth = (if (chosen) 3 else 1) * binding.root.resources.displayMetrics.density.toInt()
				binding.card.strokeColor = binding.root.context.getThemeColor(
					if (chosen) appcompatR.attr.colorPrimary else materialR.attr.colorOutlineVariant,
				)
				binding.root.setOnClickListener {
					selectedId = c.manga.id
					onSelected(c)
					notifyDataSetChanged()
				}
			}
		}
	}

	private companion object {

		const val DEBOUNCE_MS = 10_000L
		const val RETRY_MS = 700L
		const val MAX_ATTEMPTS = 6
		const val MAX_MATCHES = 8
	}
}
