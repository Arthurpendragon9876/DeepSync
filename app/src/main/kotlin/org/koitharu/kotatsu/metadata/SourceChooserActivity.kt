package org.koitharu.kotatsu.metadata

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.R as appcompatR
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeMangaSource
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.domain.ExploreRepository
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher
import javax.inject.Inject

/**
 * The source sheet, laid out like Zangetsu's: a family tab per kind of extension, a "Search extensions"
 * field, and one row per source with its icon and a tick on the current one. It has two jobs:
 *  - [Mode.PICK_CATALOGUE]: choose which source's catalogue Home shows (Sources are metadata too);
 *  - [Mode.SEARCH_IN]: choose a source, then search inside it and open (and remember) the match. Used by the
 *    Alternative button and by tracker titles no source matched by name.
 * It is a bottom sheet over a transparent activity, never a full page.
 */
@AndroidEntryPoint
class SourceChooserActivity : AppCompatActivity() {

	enum class Mode { PICK_CATALOGUE, SEARCH_IN }

	@Inject lateinit var sourcesRepository: MangaSourcesRepository
	@Inject lateinit var exploreRepository: ExploreRepository
	@Inject lateinit var dataRepository: MangaDataRepository
	@Inject lateinit var links: MetadataLinks
	@Inject lateinit var metadataRepository: MetadataRepository
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var migrateUseCase: org.koitharu.kotatsu.alternatives.domain.MigrateUseCase

	private val chooserMode by lazy {
		runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_CHOOSER_MODE).orEmpty()) }.getOrDefault(Mode.SEARCH_IN)
	}
	private val query by lazy { intent.getStringExtra(EXTRA_QUERY).orEmpty() }
	private val contentMode by lazy {
		runCatching { HomeMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrDefault(HomeMode.MANGA)
	}
	private val metaKey by lazy {
		intent.getStringExtra(EXTRA_META_KEY)
			?: intent.getLongExtra(EXTRA_MANGA_ID, 0L).takeIf { it != 0L }?.let { metadataRepository.metaKeyForManga(it) }
	}

	private lateinit var dialog: BottomSheetDialog
	private val density get() = resources.displayMetrics.density
	private fun dp(v: Int) = (v * density).toInt()
	private val primary by lazy { getThemeColor(appcompatR.attr.colorPrimary) }
	private val onSurface by lazy { getThemeColor(materialR.attr.colorOnSurface) }
	private val onSurfaceVariant by lazy { getThemeColor(materialR.attr.colorOnSurfaceVariant) }
	private val container by lazy { getThemeColor(materialR.attr.colorSurfaceContainerHigh) }

	private var sources: List<MangaSource> = emptyList()
	private var family: String? = null
	private var filter: String = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		dialog = BottomSheetDialog(this)
		dialog.setOnDismissListener { finish() }
		lifecycleScope.launch {
			sources = sourcesRepository.getEnabledSources().filter { it.contentMode == contentMode }
			showSources()
			dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
			dialog.behavior.skipCollapsed = true
			dialog.show()
		}
	}

	// ---- building blocks -----------------------------------------------------------------------

	private fun text(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
		text = value
		setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
		setTextColor(color)
		if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
	}

	private fun familyOf(source: MangaSource): String = when (source.unwrap()) {
		is MihonMangaSource -> "Mihon"
		is LnMangaSource -> "LN Reader"
		is AnimeMangaSource -> "Aniyomi"
		else -> getString(R.string.ds_family_builtin)
	}

	private fun field(hint: String, value: String, onSearch: (() -> Unit)?, onChange: (String) -> Unit) = EditText(this).apply {
		setHint(hint)
		setText(value)
		setSingleLine()
		setTextColor(onSurface)
		setHintTextColor(onSurfaceVariant)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
		setPadding(dp(20), dp(14), dp(20), dp(14))
		background = android.graphics.drawable.GradientDrawable().apply {
			cornerRadius = dp(24).toFloat()
			setColor(container)
		}
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
			setMargins(dp(20), dp(8), dp(20), dp(8))
		}
		if (onSearch != null) {
			imeOptions = EditorInfo.IME_ACTION_SEARCH
			setOnEditorActionListener { _, id, _ ->
				if (id == EditorInfo.IME_ACTION_SEARCH) onSearch().let { true } else false
			}
		}
		addTextChangedListener(
			object : TextWatcher {
				override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
				override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
				override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
			},
		)
	}

	private fun sheetRoot(): LinearLayout = LinearLayout(this).apply {
		orientation = LinearLayout.VERTICAL
		setPadding(0, dp(8), 0, dp(16))
	}

	private fun show(content: LinearLayout) {
		dialog.setContentView(content)
	}

	// ---- step 1: the list of sources -----------------------------------------------------------

	private fun showSources() {
		val root = sheetRoot()
		root.addView(text(getString(R.string.ds_sources_sheet_title), 24f, onSurface, bold = true).apply {
			setPadding(dp(24), dp(12), dp(24), dp(4))
		})
		if (chooserMode == Mode.SEARCH_IN && query.isNotBlank()) {
			root.addView(text(query, 14f, onSurfaceVariant).apply { setPadding(dp(24), 0, dp(24), dp(4)); maxLines = 1 })
		}
		val families = sources.map(::familyOf).distinct().sorted()
		val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

		fun fill() {
			list.removeAllViews()
			if (chooserMode == Mode.PICK_CATALOGUE && filter.isBlank() && family == null) {
				list.addView(sourceRow(null, getString(R.string.ds_all_sources), null, settings.getHomeSources(contentMode).isEmpty()) {
					settings.setHomeSources(contentMode, emptySet())
					dialog.dismiss()
				})
			}
			val shown = sources
				.filter { family == null || familyOf(it) == family }
				.filter { filter.isBlank() || it.getTitle(this).contains(filter, ignoreCase = true) }
				.sortedBy { it.getTitle(this).lowercase() }
			if (shown.isEmpty()) {
				list.addView(text(getString(R.string.ds_no_sources_for_mode), 15f, onSurfaceVariant).apply {
					setPadding(dp(24), dp(24), dp(24), dp(24))
				})
			}
			shown.forEach { source ->
				val picked = chooserMode == Mode.PICK_CATALOGUE && settings.getHomeSources(contentMode) == setOf(source.name)
				list.addView(sourceRow(source, source.getTitle(this), familyOf(source), picked) { onSourceTapped(source) })
			}
		}

		if (families.size > 1) {
			root.addView(
				HorizontalScrollView(this).apply {
					isHorizontalScrollBarEnabled = false
					addView(
						LinearLayout(this@SourceChooserActivity).apply {
							orientation = LinearLayout.HORIZONTAL
							setPadding(dp(20), dp(8), dp(20), dp(4))
							fun chip(label: String, value: String?) = Chip(this@SourceChooserActivity).apply {
								text = label
								isCheckable = true
								isChecked = family == value
								layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
								setOnClickListener {
									family = value
									showSources()
								}
							}
							addView(chip(getString(R.string.ds_all_sources), null))
							families.forEach { addView(chip(it, it)) }
						},
					)
				},
			)
		}
		root.addView(field(getString(R.string.ds_search_extensions), filter, null) { value ->
			filter = value
			fill()
		})
		fill()
		root.addView(ScrollView(this).apply {
			addView(list)
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
		})
		show(root)
	}

	private fun sourceRow(source: MangaSource?, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit): View =
		MaterialCardView(this).apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				setMargins(dp(20), dp(4), dp(20), dp(4))
			}
			radius = dp(26).toFloat()
			cardElevation = 0f
			strokeWidth = 0
			setCardBackgroundColor(if (selected) ColorUtils.setAlphaComponent(primary, 50) else container)
			isClickable = true
			isFocusable = true
			setOnClickListener { onClick() }
			addView(
				LinearLayout(this@SourceChooserActivity).apply {
					orientation = LinearLayout.HORIZONTAL
					gravity = Gravity.CENTER_VERTICAL
					setPadding(dp(18), dp(14), dp(18), dp(14))
					if (source != null) {
						addView(
							CoverImageView(this@SourceChooserActivity).apply {
								layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
								setImageAsync(source.faviconUri().toString())
							},
						)
					}
					addView(
						LinearLayout(this@SourceChooserActivity).apply {
							orientation = LinearLayout.VERTICAL
							layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
								marginStart = if (source != null) dp(16) else 0
							}
							addView(text(title, 18f, if (selected) primary else onSurface, bold = true))
							if (subtitle != null) addView(text(subtitle, 13f, onSurfaceVariant))
						},
					)
					if (selected) {
						addView(ImageView(this@SourceChooserActivity).apply {
							setImageResource(R.drawable.ic_check)
							setColorFilter(primary)
						})
					}
				},
			)
		}

	private fun onSourceTapped(source: MangaSource) {
		when (chooserMode) {
			Mode.PICK_CATALOGUE -> {
				settings.setHomeSources(contentMode, setOf(source.name))
				dialog.dismiss()
			}

			// Sources match short titles far better than "Title: long subtitle", so start with the part before it
			Mode.SEARCH_IN -> showResults(source, query.substringBefore(':').trim().ifEmpty { query })
		}
	}

	// ---- step 2 (SEARCH_IN): results inside the chosen source ------------------------------------

	private fun showResults(source: MangaSource, initial: String) {
		var queryText = initial
		val root = sheetRoot()
		root.addView(
			LinearLayout(this).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				setPadding(dp(12), dp(8), dp(24), 0)
				addView(ImageView(this@SourceChooserActivity).apply {
					setImageResource(R.drawable.ic_arrow_back)
					setColorFilter(onSurface)
					setPadding(dp(12), dp(12), dp(12), dp(12))
					setOnClickListener { showSources() }
				})
				addView(text(getString(R.string.ds_search_in_source, source.getTitle(this@SourceChooserActivity)), 20f, onSurface, bold = true))
			},
		)
		val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		fun search() {
			list.removeAllViews()
			list.addView(ProgressBar(this).apply {
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
			})
			lifecycleScope.launch {
				val results = exploreRepository.searchInSource(source, queryText.trim())
				list.removeAllViews()
				if (results.isEmpty()) {
					list.addView(text(getString(R.string.nothing_found), 15f, onSurfaceVariant).apply { setPadding(dp(24), dp(24), dp(24), dp(24)) })
				}
				results.forEach { list.addView(resultRow(it)) }
			}
		}
		root.addView(field(getString(R.string.search), queryText, ::search) { queryText = it })
		root.addView(ScrollView(this).apply {
			addView(list)
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
		})
		show(root)
		search()
	}

	private fun resultRow(manga: Manga): View {
		val best = TitleMatcher.bestScore(listOf(query), listOf(manga.title) + manga.altTitles) >= 0.85f
		return MaterialCardView(this).apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				setMargins(dp(20), dp(4), dp(20), dp(4))
			}
			radius = dp(22).toFloat()
			cardElevation = 0f
			strokeWidth = 0
			setCardBackgroundColor(container)
			isClickable = true
			isFocusable = true
			setOnClickListener { choose(manga) }
			addView(
				LinearLayout(this@SourceChooserActivity).apply {
					orientation = LinearLayout.HORIZONTAL
					gravity = Gravity.CENTER_VERTICAL
					setPadding(dp(10), dp(10), dp(16), dp(10))
					addView(CoverImageView(this@SourceChooserActivity).apply {
						layoutParams = LinearLayout.LayoutParams(dp(58), dp(84))
						scaleType = ImageView.ScaleType.CENTER_CROP
						setImageAsync(manga.coverUrl)
					})
					addView(
						LinearLayout(this@SourceChooserActivity).apply {
							orientation = LinearLayout.VERTICAL
							layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
							addView(text(manga.title, 16f, onSurface, bold = true).apply { maxLines = 2 })
							if (best) addView(text(getString(R.string.ds_track_best_match), 13f, primary))
						},
					)
				},
			)
		}
	}

	private fun choose(manga: Manga) {
		val fromId = intent.getLongExtra(EXTRA_MANGA_ID, 0L)
		if (fromId == 0L) {
			finishChoice(manga)
			return
		}
		// Coming from a title (the button beside Favourite): picking a result replaces it, progress included.
		lifecycleScope.launch {
			val old = dataRepository.findMangaById(fromId, withChapters = true)
			if (old == null || old.id == manga.id) {
				finishChoice(manga)
				return@launch
			}
			org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog(this@SourceChooserActivity, isCentered = true) {
				setIcon(R.drawable.ic_swap)
				setTitle(R.string.manga_migration)
				setMessage(
					getString(
						R.string.migrate_confirmation,
						old.title,
						old.source.getTitle(this@SourceChooserActivity),
						manga.title,
						manga.source.getTitle(this@SourceChooserActivity),
					),
				)
				setNegativeButton(android.R.string.cancel, null)
				setPositiveButton(R.string.migrate) { _, _ -> migrate(old, manga) }
			}.show()
		}
	}

	private fun migrate(old: Manga, target: Manga) {
		lifecycleScope.launch {
			runCatching { migrateUseCase(old, target, migrateProgress = true) }
				.onSuccess { finishChoice(target) }
				.onFailure {
					android.widget.Toast.makeText(
						this@SourceChooserActivity,
						it.message ?: getString(R.string.error_occurred),
						android.widget.Toast.LENGTH_LONG,
					).show()
				}
		}
	}

	private fun finishChoice(manga: Manga) {
		lifecycleScope.launch {
			// Stored so the title can be opened (and re-opened from the remembered pairing) by id.
			runCatching { dataRepository.storeManga(manga, replaceExisting = false) }
			metaKey?.let { links.set(it, manga.id) }
			AppRouter(this@SourceChooserActivity).openDetails(manga)
			dialog.dismiss()
		}
	}

	companion object {

		private const val EXTRA_QUERY = "query"
		private const val EXTRA_MODE = "mode"
		private const val EXTRA_META_KEY = "meta_key"
		private const val EXTRA_MANGA_ID = "manga_id"
		private const val EXTRA_CHOOSER_MODE = "chooser_mode"

		/** Pick a source, then search inside it for [query]. */
		fun intent(context: Context, query: String, mode: HomeMode, metaKey: String?, mangaId: Long = 0L): Intent =
			Intent(context, SourceChooserActivity::class.java)
				.putExtra(EXTRA_CHOOSER_MODE, Mode.SEARCH_IN.name)
				.putExtra(EXTRA_QUERY, query)
				.putExtra(EXTRA_MODE, mode.name)
				.putExtra(EXTRA_META_KEY, metaKey)
				.putExtra(EXTRA_MANGA_ID, mangaId)

		/** Pick which source's catalogue Home shows for [mode]. */
		fun pickIntent(context: Context, mode: HomeMode): Intent =
			Intent(context, SourceChooserActivity::class.java)
				.putExtra(EXTRA_CHOOSER_MODE, Mode.PICK_CATALOGUE.name)
				.putExtra(EXTRA_MODE, mode.name)
	}
}
