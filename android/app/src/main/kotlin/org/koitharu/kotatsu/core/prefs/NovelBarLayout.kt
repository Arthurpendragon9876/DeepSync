package org.koitharu.kotatsu.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot.BOTTOM_END
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot.BOTTOM_START
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot.TOP_END

/**
 * A single button (or the progress slider) of the novel reader's bars. Same idea as [ReaderControl]
 * for manga: the user picks which are shown, in which [ReaderBarSlot] and in which order — see the
 * "Customise novel reader bars" settings screen. Only [SLIDER] is tied to the bottom bar, between
 * its two sides, and [OPTIONS] can never be hidden (it opens the panel this very setting is
 * reachable from).
 */
enum class NovelControl(
	@StringRes val titleResId: Int,
	@DrawableRes val iconResId: Int,
	@StringRes val summaryResId: Int = 0,
) {

	CHAPTERS(R.string.chapters, R.drawable.ic_list),
	PREV_CHAPTER(R.string.prev_chapter, R.drawable.ic_novel_chapter_prev),
	NEXT_CHAPTER(R.string.next_chapter, R.drawable.ic_novel_chapter_next),
	SLIDER(R.string.novel_bar_slider, R.drawable.ic_slider),
	WEB_VIEW(R.string.open_in_browser, R.drawable.ic_web, R.string.novel_bar_web_note),
	EXTERNAL_BROWSER(R.string.open_in_external_browser, R.drawable.ic_open_external, R.string.novel_bar_web_note),
	HIGHLIGHT(R.string.highlight, R.drawable.ic_ink_highlighter),
	SEARCH(R.string.search, R.drawable.ic_search),
	ROTATE(R.string.screen_orientation, R.drawable.ic_screen_rotation),
	AUTO_SCROLL(R.string.automatic_scroll, R.drawable.ic_timer),
	LISTEN(R.string.text_to_speech, R.drawable.ic_novel_headphones),
	OPTIONS(R.string.reader_bar_settings_button, R.drawable.ic_tune, R.string.novel_bar_options_note);

	/** Whether the control can be switched off. */
	val canBeHidden: Boolean
		get() = this != OPTIONS
}

data class NovelBarItem(
	val control: NovelControl,
	val slot: ReaderBarSlot,
	val isShown: Boolean,
)

/** Every control in the user's order; controls of the same slot keep their relative order. */
typealias NovelBarLayout = List<NovelBarItem>

object NovelBars {

	/** The bars as they ship: Chapters and Previous | slider | Next and Settings, plus the web and highlight buttons on top. */
	val DEFAULT: NovelBarLayout = listOf(
		NovelBarItem(NovelControl.CHAPTERS, BOTTOM_START, true),
		NovelBarItem(NovelControl.PREV_CHAPTER, BOTTOM_START, true),
		NovelBarItem(NovelControl.SLIDER, BOTTOM_START, true),
		NovelBarItem(NovelControl.NEXT_CHAPTER, BOTTOM_END, true),
		NovelBarItem(NovelControl.OPTIONS, BOTTOM_END, true),
		NovelBarItem(NovelControl.WEB_VIEW, TOP_END, true),
		NovelBarItem(NovelControl.EXTERNAL_BROWSER, TOP_END, true),
		NovelBarItem(NovelControl.HIGHLIGHT, TOP_END, true),
		NovelBarItem(NovelControl.SEARCH, TOP_END, false),
		NovelBarItem(NovelControl.ROTATE, BOTTOM_END, false),
		NovelBarItem(NovelControl.AUTO_SCROLL, BOTTOM_END, false),
		NovelBarItem(NovelControl.LISTEN, BOTTOM_END, false),
	)

	/** Where a control that no stored layout knows yet goes, hidden. */
	private val DEFAULT_SLOT: ReaderBarSlot = BOTTOM_END

	fun serialize(layout: NovelBarLayout): String = layout.joinToString(",") { (control, slot, isShown) ->
		(if (isShown) "" else "-") + control.name + "@" + slot.name
	}

	/**
	 * Parses [serialize]'s output. Unknown names are dropped, a control without a slot lands in
	 * [DEFAULT_SLOT], and controls added by a later version are appended hidden.
	 */
	fun parse(
		value: String,
		findControl: (String) -> NovelControl?,
		findSlot: (String) -> ReaderBarSlot?,
	): NovelBarLayout {
		val items = value.split(',')
			.mapNotNull { token ->
				val name = token.trim()
				val control = findControl(name.removePrefix("-").substringBefore('@')) ?: return@mapNotNull null
				val slot = name.substringAfter('@', "").let(findSlot) ?: DEFAULT_SLOT
				NovelBarItem(control, slot, isShown = !name.startsWith('-') || !control.canBeHidden)
			}
			.distinctBy { it.control }
		val known = items.map { it.control }.toSet()
		val missing = DEFAULT.filterNot { it.control in known }.map { it.copy(isShown = !it.control.canBeHidden) }
		return items + missing
	}

}

/** The shown controls of one slot, in order. The slider belongs to no slot. */
fun NovelBarLayout.shownIn(slot: ReaderBarSlot): List<NovelControl> =
	filter { it.slot == slot && it.isShown && it.control != NovelControl.SLIDER }.map { it.control }

val NovelBarLayout.isSliderShown: Boolean
	get() = any { it.control == NovelControl.SLIDER && it.isShown }
