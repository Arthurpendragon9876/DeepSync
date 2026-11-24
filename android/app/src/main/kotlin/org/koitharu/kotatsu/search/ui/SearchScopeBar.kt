package org.koitharu.kotatsu.search.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.metadata.MetadataRepository

/**
 * Where a search goes. There is no strip under the search field any more: the Discover | Sources choice and
 * the tracker / source options live in one preferences sheet, opened from the search bar's menu.
 *
 * Discover searches the trackers (AniList, MyAnimeList, MangaBaka) and Sources searches the installed
 * extensions. The state is [AppSettings.globalSearchTrackers] (any tracker on means Discover), and the
 * search field's [hint] always names what a search will hit.
 */
class SearchScopeBar(
	private val context: Context,
	private val settings: AppSettings,
	private val metadata: MetadataRepository,
) {

	private val density = context.resources.displayMetrics.density
	private fun dp(v: Int) = (v * density).toInt()

	/** The hint for the search field: it names what a search will actually hit. */
	fun hint(): String {
		val trackers = activeTrackers()
		return when {
			trackers.isEmpty() -> "Search sources"
			trackers.size == 1 -> "Search ${name(trackers.first())}"
			else -> "Search ${trackers.joinToString(", ") { name(it) }}"
		}
	}

	// ---- state --------------------------------------------------------------------------------------

	private fun availableTrackers(): List<HomeMetadata> =
		HomeMetadata.values().filter { it != HomeMetadata.SOURCES && metadata.supports(it, settings.homeMode) }

	private fun activeTrackers(): List<HomeMetadata> {
		val available = availableTrackers()
		return settings.globalSearchTrackers
			.mapNotNull { runCatching { HomeMetadata.valueOf(it) }.getOrNull() }
			.filter { it in available }
			.sortedBy { it.ordinal }
	}

	private fun selectDiscover() {
		val available = availableTrackers()
		if (available.isEmpty()) {
			Toast.makeText(context, "No tracker can search this mode", Toast.LENGTH_SHORT).show()
			return
		}
		if (activeTrackers().isEmpty()) {
			val pick = available.firstOrNull { it == settings.homeMetadataProvider } ?: available.first()
			settings.globalSearchTrackers = setOf(pick.name)
		}
	}

	private fun selectSources() {
		settings.globalSearchTrackers = emptySet()
	}

	// ---- preferences sheet --------------------------------------------------------------------------

	fun showPreferences() {
		val sheet = BottomSheetDialog(context)
		val root = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20), dp(8), dp(20), dp(28))
		}
		// Switching Discover / Sources changes what the rest of the sheet offers, so it is drawn again.
		fun render() {
			root.removeAllViews()
			val discover = activeTrackers().isNotEmpty()
			root.addView(title("Search preferences"))
			root.addView(label("Search in"))
			buildModeChips(root, discover, ::render)
			root.addView(label(if (discover) "Metadata to search" else "Sources to search"))
			root.addView(
				hintText(
					if (discover) {
						"Search these trackers. Opening a result finds a source for it."
					} else {
						"Choose which installed sources a search goes through."
					},
				),
			)
			if (discover) buildTrackerChips(root) else buildSourceChips(root)
		}
		render()
		sheet.setContentView(root)
		sheet.show()
	}

	private fun buildModeChips(root: LinearLayout, discover: Boolean, onChanged: () -> Unit) {
		val group = ChipGroup(context).apply {
			isSingleSelection = true
			isSelectionRequired = true
		}
		listOf("Discover" to true, "Sources" to false).forEach { (text, isDiscover) ->
			group.addView(
				Chip(context).apply {
					id = View.generateViewId()
					this.text = text
					isCheckable = true
					isChecked = isDiscover == discover
					isEnabled = !isDiscover || availableTrackers().isNotEmpty()
					setOnClickListener {
						if (isDiscover) selectDiscover() else selectSources()
						onChanged()
					}
				},
			)
		}
		root.addView(group)
	}

	private fun buildTrackerChips(root: LinearLayout) {
		val group = ChipGroup(context)
		val chips = availableTrackers().map { provider ->
			Chip(context).apply {
				text = name(provider)
				isCheckable = true
				isChecked = provider in activeTrackers()
				tag = provider
			}
		}
		chips.forEach { chip ->
			group.addView(chip)
			chip.setOnCheckedChangeListener { _, _ ->
				val picked = chips.filter { it.isChecked }.map { (it.tag as HomeMetadata).name }.toSet()
				if (picked.isEmpty()) {
					// A Discover search needs a tracker; switching to none would silently be Sources
					chip.isChecked = true
				} else {
					settings.globalSearchTrackers = picked
				}
			}
		}
		root.addView(group)
	}

	private fun buildSourceChips(root: LinearLayout) {
		val group = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
		val options = listOf(
			"All enabled sources" to { settings.isSearchPinnedOnly = false; settings.isSearchLocalOnly = false },
			"Pinned only" to { settings.isSearchPinnedOnly = true; settings.isSearchLocalOnly = false },
			"Local only" to { settings.isSearchPinnedOnly = false; settings.isSearchLocalOnly = true },
		)
		val selected = when {
			settings.isSearchLocalOnly -> 2
			settings.isSearchPinnedOnly -> 1
			else -> 0
		}
		options.forEachIndexed { index, (label, onPick) ->
			group.addView(Chip(context).apply {
				id = View.generateViewId()
				text = label
				isCheckable = true
				isChecked = index == selected
				setOnClickListener { onPick() }
			})
		}
		root.addView(group)
		root.addView(hintText("Pin sources from Explore (long-press a source) to build the Pinned list."))
	}

	// ---- small view helpers -------------------------------------------------------------------------

	private fun title(text: String) = TextView(context).apply {
		this.text = text
		textSize = 20f
		typeface = Typeface.create(typeface, Typeface.BOLD)
		setTextColor(context.getThemeColor(materialR.attr.colorOnSurface))
		setPadding(0, dp(8), 0, dp(8))
	}

	private fun label(text: String) = TextView(context).apply {
		this.text = text
		textSize = 14f
		typeface = Typeface.create(typeface, Typeface.BOLD)
		setTextColor(context.getThemeColor(materialR.attr.colorOnSurfaceVariant))
		setPadding(0, dp(8), 0, dp(4))
	}

	private fun hintText(text: String) = TextView(context).apply {
		this.text = text
		textSize = 13f
		setTextColor(context.getThemeColor(materialR.attr.colorOnSurfaceVariant))
		setPadding(0, 0, 0, dp(8))
	}

	private fun name(provider: HomeMetadata) = when (provider) {
		HomeMetadata.ANILIST -> "AniList"
		HomeMetadata.MAL -> "MyAnimeList"
		HomeMetadata.MANGABAKA -> "MangaBaka"
		HomeMetadata.SIMKL -> "Simkl"
		HomeMetadata.SOURCES -> "Sources"
	}
}
