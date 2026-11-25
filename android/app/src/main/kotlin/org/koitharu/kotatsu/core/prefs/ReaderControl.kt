package org.koitharu.kotatsu.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

/**
 * A single button (or the slider) of the reader's bars. The user picks which ones are shown, in
 * which bar slot ([ReaderBarSlot]) and in which order — see the "Customise reader bottom bar and
 * top bar" settings screen. Every control, Chapters and Settings included, can be moved to any
 * slot; only [SLIDER] is tied to the bottom bar, between its two sides. Storage is an ordered
 * list, so the declaration order here only matters as the fallback for controls that are turned on
 * later.
 */
enum class ReaderControl(
	@StringRes val titleResId: Int,
	@DrawableRes val iconResId: Int,
	@StringRes val summaryResId: Int = 0,
) {

	PREV_CHAPTER(R.string.prev_chapter, R.drawable.ic_prev),
	NEXT_CHAPTER(R.string.next_chapter, R.drawable.ic_action_skip),
	SLIDER(R.string.pages_slider, R.drawable.ic_slider),
	PAGES_SHEET(R.string.chapters_and_pages, R.drawable.ic_grid),
	SCREEN_ROTATION(R.string.screen_orientation, R.drawable.ic_screen_rotation),
	SAVE_PAGE(R.string.save_page, R.drawable.ic_save),
	TIMER(R.string.automatic_scroll, R.drawable.ic_timer),
	WEB_VIEW(R.string.open_in_browser, R.drawable.ic_web),
	EXTERNAL_BROWSER(R.string.open_in_external_browser, R.drawable.ic_open_external),
	BOOKMARK(R.string.bookmark_add, R.drawable.ic_bookmark, R.string.highlight_in_novels),
	OPTIONS(R.string.reader_bar_settings_button, R.drawable.ic_tune);
}
