package org.koitharu.kotatsu.reader.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

class ReaderMenuProvider(
	private val onBookmark: () -> Unit,
	private val isBookmarkAdded: () -> Boolean,
	private val onSearchBook: () -> Unit,
	private val onShowUpscalePreview: () -> Unit,
	private val isEpub: () -> Boolean,
	private val isUpscaleActive: () -> Boolean,
	private val isLandscape: Boolean,
	private val canOpenWeb: () -> Boolean = { false },
	private val onOpenWeb: () -> Unit = {},
	private val onOpenExternal: () -> Unit = {},
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_reader, menu)
		// The bookmark button is one of the user's bar buttons now ("Customise reader bottom bar and
		// top bar"), drawn by the bars themselves, so the menu no longer carries one.
		menu.findItem(R.id.action_bookmark)?.isVisible = false
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_epub_search)?.isVisible = isEpub()
		menu.findItem(R.id.action_bookmark)?.isVisible = false
		menu.findItem(R.id.action_upscale_preview)?.isVisible = isUpscaleActive()
		// Novels have their own web button in the reader bar, beside the highlighter
		menu.findItem(R.id.action_webview)?.isVisible = !isEpub() && canOpenWeb()
		menu.findItem(R.id.action_external_browser)?.isVisible = !isEpub() && canOpenWeb()
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		return when (menuItem.itemId) {
			R.id.action_epub_search -> {
				onSearchBook()
				true
			}

			R.id.action_bookmark -> {
				onBookmark()
				true
			}

			R.id.action_upscale_preview -> {
				onShowUpscalePreview()
				true
			}

			R.id.action_webview -> {
				onOpenWeb()
				true
			}

			R.id.action_external_browser -> {
				onOpenExternal()
				true
			}

			R.id.action_info -> {
				// TODO
				true
			}

			else -> false
		}
	}
}
