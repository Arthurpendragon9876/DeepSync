package org.koitharu.kotatsu.scrobbling.common.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.R as appcompatR
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemTrackMatchBinding
import org.koitharu.kotatsu.databinding.SheetTrackPromptBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.TrackerSynchronizer
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerMatchGroup
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Provider
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
	// A Provider: the scrobblers behind it reach back into history, which owns this class
	private val synchronizerProvider: Provider<TrackerSynchronizer>,
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
	 * "Save progress?" bottom sheet. Every signed-in tracker is searched at once and the same title found on
	 * several of them is one card, with the icon of each tracker that has it (the closest card is picked).
	 * Save links the title on all of those trackers and brings their progress together, "This device only"
	 * keeps the series local for good, No skips for now.
	 */
	private fun showSheet(activity: FragmentActivity, manga: Manga, chapterId: Long) {
		val synchronizer = synchronizerProvider.get()
		val b = SheetTrackPromptBinding.inflate(LayoutInflater.from(activity))
		val dialog = BottomSheetDialog(activity)
		dialog.setContentView(b.root)
		dialog.setOnDismissListener { isShowing = false }
		b.textMessage.text = activity.getString(R.string.ds_track_sheet_message_all, manga.title)
		// Portrait is a compact sheet; landscape (and so the player) is a two-column card that never scrolls.
		val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		val density = activity.resources.displayMetrics.density
		dialog.behavior.maxWidth = ((if (landscape) LANDSCAPE_WIDTH_DP else PORTRAIT_WIDTH_DP) * density).toInt()
		val availableDp = if (landscape) {
			LANDSCAPE_CARDS_DP
		} else {
			minOf(activity.resources.configuration.screenWidthDp, PORTRAIT_WIDTH_DP) - 28f
		}
		var selected: TrackerMatchGroup? = null
		val adapter = MatchAdapter(b.listMatches, landscape, availableDp) { group ->
			selected = group
			b.buttonSave.isEnabled = true
		}
		b.listMatches.adapter = adapter

		fun remember() {
			settings.scrobblingNeverAsk = settings.scrobblingNeverAsk + manga.id
		}
		b.buttonSave.setOnClickListener {
			selected?.let { link(activity, manga, chapterId, it) }
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

		if (synchronizer.signedIn().isEmpty()) {
			b.progress.isVisible = false
			return
		}
		activity.lifecycleScope.launch {
			val groups = runCatchingCancellable {
				withContext(Dispatchers.Default) { synchronizer.search(manga) }
			}.getOrDefault(emptyList())
			if (!dialog.isShowing) return@launch
			b.progress.isVisible = false
			if (groups.isEmpty()) {
				b.textNone.text = activity.getString(R.string.ds_track_no_matches_all)
				b.textNone.isVisible = true
			} else {
				// The closest match starts out chosen, so Save is one tap
				selected = groups.first()
				b.buttonSave.isEnabled = true
				adapter.submit(groups, groups.first().key)
				b.listMatches.isVisible = true
			}
		}
	}

	private fun link(activity: FragmentActivity, manga: Manga, chapterId: Long, group: TrackerMatchGroup) {
		// The activity scope would be cancelled with the reader; linking has to outlive the sheet
		processScope.launch(Dispatchers.Default) {
			runCatchingCancellable {
				synchronizerProvider.get().linkAndSync(manga, chapterId, group)
			}.onSuccess { linked ->
				main.post {
					val app = activity.applicationContext
					if (linked.isEmpty()) {
						Toast.makeText(app, R.string.ds_track_failed, Toast.LENGTH_LONG).show()
					} else {
						val names = linked.joinToString(", ") { app.getString(it.titleResId) }
						Toast.makeText(app, app.getString(R.string.ds_track_linked, names), Toast.LENGTH_SHORT).show()
					}
				}
			}.onFailure {
				main.post { Toast.makeText(activity.applicationContext, R.string.ds_track_failed, Toast.LENGTH_LONG).show() }
			}
		}
	}

	private class MatchAdapter(
		private val list: RecyclerView,
		private val landscape: Boolean,
		private val availableDp: Float,
		private val onSelected: (TrackerMatchGroup) -> Unit,
	) : RecyclerView.Adapter<MatchAdapter.Holder>() {

		private var items: List<TrackerMatchGroup> = emptyList()
		private var selectedKey = ""
		private val density = list.resources.displayMetrics.density
		private val gapPx = ((if (landscape) 6 else 8) * density).toInt()
		private var cardPx = (108 * density).toInt()

		fun submit(list: List<TrackerMatchGroup>, selected: String) {
			items = list
			selectedKey = selected
			sizeCards()
			notifyDataSetChanged()
		}

		/**
		 * Two cards (or one) get the full size; with three they shrink to share the row, and with more they shrink
		 * a little further so the next one peeks in as a hint to scroll. The group is centred when it is narrower
		 * than the space.
		 */
		private fun sizeCards() {
			val n = items.size
			val slots = when {
				n <= 2 -> 2f
				n == 3 -> 3f
				else -> 3.4f
			}
			val gapDp = gapPx / density
			val maxCardDp = if (landscape) 110f else 150f
			val widthDp = minOf(availableDp / slots - gapDp, maxCardDp)
			cardPx = (widthDp * density).toInt()
			val totalDp = n * (widthDp + gapDp)
			val padPx = if (totalDp < availableDp) ((availableDp - totalDp) / 2f * density).toInt() else 0
			list.setPadding(padPx, 0, padPx, 0)
		}

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val binding = ItemTrackMatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
			return Holder(binding)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], position == 0)

		inner class Holder(private val binding: ItemTrackMatchBinding) : RecyclerView.ViewHolder(binding.root) {

			fun bind(group: TrackerMatchGroup, isBest: Boolean) {
				val ctx = binding.root.context
				val density = binding.root.resources.displayMetrics.density
				val c = group.primary
				binding.card.layoutParams = RecyclerView.LayoutParams(cardPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
					marginStart = gapPx / 2
					marginEnd = gapPx / 2
				}
				// Cover: 3:4 in the sheet, a little squarer in landscape; the card has 6dp of padding all round.
				val coverWidth = cardPx - (12 * density).toInt()
				binding.imageCover.layoutParams = binding.imageCover.layoutParams.apply {
					height = (coverWidth * (if (landscape) 4.6f / 4f else 4f / 3f)).toInt()
				}
				binding.textTitle.text = listOfNotNull(c.manga.name, c.kindLabel).joinToString(" · ")
				binding.cardBadge.isVisible = isBest
				val percent = (group.score * 100f).roundToInt()
				binding.textMatch.isVisible = percent > 0
				binding.textMatch.text = "$percent%"
				binding.imageCover.setImageAsync(c.manga.cover)
				showTrackers(group, ctx, density)
				val chosen = group.key == selectedKey
				binding.card.strokeWidth = ((if (chosen) 2 else 1) * density).toInt()
				binding.card.strokeColor = ctx.getThemeColor(
					if (chosen) appcompatR.attr.colorPrimary else materialR.attr.colorOutlineVariant,
				)
				binding.root.setOnClickListener {
					selectedKey = group.key
					onSelected(group)
					notifyDataSetChanged()
				}
			}

			/** One small icon per tracker that has this title. */
			private fun showTrackers(group: TrackerMatchGroup, ctx: Context, density: Float) {
				val row = binding.layoutTrackers
				row.removeAllViews()
				val size = (14 * density).toInt()
				val gap = (4 * density).toInt()
				val tint = ColorStateList.valueOf(ctx.getThemeColor(materialR.attr.colorOnSurfaceVariant))
				for (service in group.services) {
					val icon = ImageView(ctx)
					icon.setImageResource(service.iconResId)
					icon.contentDescription = ctx.getString(R.string.ds_track_on_tracker, ctx.getString(service.titleResId))
					// MangaBaka's logo is multicoloured, the others are single-colour glyphs
					if (service != ScrobblerService.MANGABAKA) ImageViewCompat.setImageTintList(icon, tint)
					row.addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
				}
			}
		}
	}

	private companion object {

		const val DEBOUNCE_MS = 10_000L
		const val RETRY_MS = 700L
		const val MAX_ATTEMPTS = 6
		const val MAX_MATCHES = 8
		const val PORTRAIT_WIDTH_DP = 640
		const val LANDSCAPE_WIDTH_DP = 560

		/** Width of the card column in the landscape layout: 224dp minus its 10dp padding on each side. */
		const val LANDSCAPE_CARDS_DP = 204f
	}
}
