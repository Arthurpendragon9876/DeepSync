package org.koitharu.kotatsu.scrobbling.common.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.SheetLibraryFilterBinding

/**
 * The filters of a tracker library, behind the funnel icon next to its search bar (like Explore's filter
 * icon) instead of as rows of chips that eat into the cover grid. Choosing applies at once; the labels
 * are asked for again after every choice so their counts follow it.
 */
internal object LibraryFilterSheet {

	/** One tracker choice; a null [key] is "all trackers". */
	class Choice(val key: String?, val label: String)

	fun show(
		context: Context,
		statuses: List<String>,
		statusLabel: (String) -> String,
		currentStatus: () -> String,
		onStatus: (String) -> Unit,
		trackers: List<Choice> = emptyList(),
		currentTracker: () -> String? = { null },
		onTracker: (String?) -> Unit = {},
	) {
		val dialog = BottomSheetDialog(context)
		val inflater = LayoutInflater.from(dialog.context)
		val b = SheetLibraryFilterBinding.inflate(inflater)

		fun refresh() {
			b.chipStatus.forEachChip { chip ->
				val key = chip.tag as String
				chip.text = statusLabel(key)
				chip.isChecked = key == currentStatus()
			}
			b.chipTracker.forEachChip { chip ->
				chip.isChecked = (chip.tag as String?) == currentTracker()
			}
		}

		for (key in statuses) {
			b.chipStatus.addView(
				newChip(inflater, b.chipStatus).apply {
					tag = key
					setOnClickListener {
						onStatus(key)
						refresh()
					}
				},
			)
		}
		// Choosing a tracker only makes sense with two or more to choose between
		val hasTrackers = trackers.size >= 2
		b.labelTracker.isVisible = hasTrackers
		b.chipTracker.isVisible = hasTrackers
		if (hasTrackers) {
			for (choice in trackers) {
				b.chipTracker.addView(
					newChip(inflater, b.chipTracker).apply {
						tag = choice.key
						text = choice.label
						setOnClickListener {
							onTracker(choice.key)
							refresh()
						}
					},
				)
			}
		}
		refresh()
		dialog.setContentView(b.root)
		// Landscape phones are too short to show the whole sheet collapsed
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.show()
	}

	private fun newChip(inflater: LayoutInflater, parent: ViewGroup): Chip =
		inflater.inflate(R.layout.item_library_chip, parent, false) as Chip

	private inline fun ChipGroup.forEachChip(action: (Chip) -> Unit) {
		for (i in 0 until childCount) {
			(getChildAt(i) as? Chip)?.let(action)
		}
	}
}
