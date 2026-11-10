package org.koitharu.kotatsu.home.ui

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.home.ui.model.HomeConfig
import org.koitharu.kotatsu.home.ui.model.HomeMetadata

/**
 * Home's entries in the three-dots menu: Filters, Metadata (providers and the source catalogue) and the banner style.
 * Counts and the metadata provider show in the titles, so nothing needs space on the screen itself.
 */
class HomeMenuProvider(
	private val context: Context,
	private val config: () -> HomeConfig,
	private val onFilters: () -> Unit,
	private val onMetadata: () -> Unit,
	private val onBanner: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_home, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		val c = config()
		menu.findItem(R.id.action_home_filters)?.title = context.getString(R.string.ds_home_filters) +
			if (c.filterCount == 0) "" else " (${c.filterCount})"
		menu.findItem(R.id.action_home_metadata)?.apply {
			title = context.getString(R.string.ds_metadata_cd, context.getString(c.metadata.titleRes))
			// The provider's own logo
			setIcon(
				when (c.metadata) {
					HomeMetadata.ANILIST -> R.drawable.ic_anilist
					HomeMetadata.MAL -> R.drawable.ic_mal
					HomeMetadata.MANGABAKA -> R.drawable.ic_mangabaka
					HomeMetadata.SOURCES -> R.drawable.ic_manga_source
				},
			)
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
		when (menuItem.itemId) {
			R.id.action_home_filters -> onFilters()
			R.id.action_home_metadata -> onMetadata()
			R.id.action_home_banner -> onBanner()
			else -> return false
		}
		return true
	}
}
