package org.koitharu.kotatsu.core.prefs

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot.BOTTOM_END
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot.BOTTOM_START
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot.TOP_END

/**
 * The four places a [ReaderControl] can sit: the left and right side of the top bar and of the
 * bottom bar. The bottom bar's page slider lives between its two sides.
 */
enum class ReaderBarSlot(@StringRes val titleResId: Int) {

	TOP_START(R.string.reader_bar_top_start),
	TOP_END(R.string.reader_bar_top_end),
	BOTTOM_START(R.string.reader_bar_bottom_start),
	BOTTOM_END(R.string.reader_bar_bottom_end),
}

/**
 * One control of the reader's bars: which [slot] it sits in and whether it is [isShown]. A hidden
 * control keeps its place, so turning it back on puts it where it was.
 */
data class ReaderBarItem(
	val control: ReaderControl,
	val slot: ReaderBarSlot,
	val isShown: Boolean,
)

/**
 * Every control in the user's order. Controls of the same slot keep their relative order here;
 * the order between slots does not matter.
 */
typealias ReaderBarLayout = List<ReaderBarItem>

object ReaderBars {

	/** The bars as they ship: Chapters and Previous | slider | Next and Settings, plus Bookmark on top. */
	val DEFAULT: ReaderBarLayout = listOf(
		ReaderBarItem(ReaderControl.PAGES_SHEET, BOTTOM_START, true),
		ReaderBarItem(ReaderControl.PREV_CHAPTER, BOTTOM_START, true),
		ReaderBarItem(ReaderControl.SLIDER, BOTTOM_START, true),
		ReaderBarItem(ReaderControl.NEXT_CHAPTER, BOTTOM_END, true),
		ReaderBarItem(ReaderControl.OPTIONS, BOTTOM_END, true),
		ReaderBarItem(ReaderControl.BOOKMARK, TOP_END, true),
		ReaderBarItem(ReaderControl.SCREEN_ROTATION, BOTTOM_END, false),
		ReaderBarItem(ReaderControl.SAVE_PAGE, BOTTOM_END, false),
		ReaderBarItem(ReaderControl.TIMER, BOTTOM_END, false),
	)

	/** Where a control that no stored layout knows yet goes, hidden. */
	val DEFAULT_SLOT: ReaderBarSlot = BOTTOM_END

	fun serialize(layout: ReaderBarLayout): String = layout.joinToString(",") { (control, slot, isShown) ->
		(if (isShown) "" else "-") + control.name + "@" + slot.name
	}

	/**
	 * Parses [serialize]'s output. Unknown names are dropped, a control without a slot lands in
	 * [DEFAULT_SLOT], and controls added by a later version are appended hidden.
	 */
	fun parse(value: String, findControl: (String) -> ReaderControl?, findSlot: (String) -> ReaderBarSlot?): ReaderBarLayout {
		val items = value.split(',')
			.mapNotNull { token ->
				val name = token.trim()
				val control = findControl(name.removePrefix("-").substringBefore('@')) ?: return@mapNotNull null
				val slot = name.substringAfter('@', "").let(findSlot) ?: DEFAULT_SLOT
				ReaderBarItem(control, slot, isShown = !name.startsWith('-'))
			}
			.distinctBy { it.control }
		return withMissingControls(items)
	}

	fun withMissingControls(items: ReaderBarLayout): ReaderBarLayout {
		val known = items.map { it.control }.toSet()
		return items + ReaderControl.entries.filterNot { it in known }.map { ReaderBarItem(it, DEFAULT_SLOT, false) }
	}

	/**
	 * Turns an old single-list bottom bar (shown or hidden controls in one order, the slider among
	 * them) into slots: what was left of the slider is now the left side, the rest the right side.
	 * A bar saved before Settings was a control of its own had Chapters pinned first and Settings
	 * pinned last, so that look is kept instead of hiding the Settings button. Bookmark used to
	 * live in the top bar whenever it was off in the bottom bar, so it stays there.
	 */
	fun fromSingleList(old: List<Pair<ReaderControl, Boolean>>): ReaderBarLayout {
		var list = old
		if (list.none { it.first == ReaderControl.OPTIONS }) {
			val pages = list.filter { it.first == ReaderControl.PAGES_SHEET }
			list = pages + list.filter { it.first != ReaderControl.PAGES_SHEET } + (ReaderControl.OPTIONS to true)
		}
		val sliderIndex = list.indexOfFirst { it.first == ReaderControl.SLIDER }
		val items = list.mapIndexed { index, (control, isShown) ->
			val slot = if (sliderIndex < 0 || index <= sliderIndex) BOTTOM_START else BOTTOM_END
			if (control == ReaderControl.BOOKMARK && !isShown) {
				ReaderBarItem(control, TOP_END, true)
			} else {
				ReaderBarItem(control, slot, isShown)
			}
		}
		return withMissingControls(items).let { full ->
			// BOOKMARK was absent from a very old list: still keep the top bar bookmark it always had.
			if (list.none { it.first == ReaderControl.BOOKMARK }) {
				full.map { if (it.control == ReaderControl.BOOKMARK) it.copy(slot = TOP_END, isShown = true) else it }
			} else {
				full
			}
		}
	}
}
