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
		menu.findItem(R.id.action_bookmark)?.isVisible = !isLandscape && !isEpub()
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_epub_search)?.isVisible = isEpub()
		// The old three-dot button is gone (quick settings live in the bottom bar): the top bar now
		// carries a bookmark toggle instead.
		menu.findItem(R.id.action_bookmark)?.let { item ->
			val added = isBookmarkAdded()
			item.isVisible = !isLandscape && !isEpub()
			item.setIcon(if (added) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark)
			item.setTitle(if (added) R.string.bookmark_remove else R.string.bookmark_add)
		}
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
