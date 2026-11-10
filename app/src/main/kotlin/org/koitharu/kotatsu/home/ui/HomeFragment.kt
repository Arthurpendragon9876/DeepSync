package org.koitharu.kotatsu.home.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.LinearLayout
import android.graphics.Shader
import android.graphics.RenderEffect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.DialogHomeFiltersBinding
import org.koitharu.kotatsu.databinding.DialogHomeSourcesBinding
import org.koitharu.kotatsu.databinding.FragmentHomeBinding
import org.koitharu.kotatsu.databinding.ItemHomeQuickButtonBinding
import org.koitharu.kotatsu.databinding.ItemHomeRowBinding
import org.koitharu.kotatsu.home.ui.adapter.HomePosterAdapter
import org.koitharu.kotatsu.home.ui.adapter.HomeSourceAdapter
import org.koitharu.kotatsu.home.ui.adapter.SourceChoice
import org.koitharu.kotatsu.home.ui.adapter.HomeSpotlightAdapter
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.home.ui.model.HeroItem
import org.koitharu.kotatsu.home.ui.model.HomeBannerStyle
import org.koitharu.kotatsu.home.ui.model.HomeConfig
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomePoster
import org.koitharu.kotatsu.home.ui.model.HomeRow
import org.koitharu.kotatsu.home.ui.model.HomeRowKind
import org.koitharu.kotatsu.home.ui.model.HomeSectionState
import org.koitharu.kotatsu.home.ui.model.HomeUiState
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.scrobbling.common.ui.TrackerLibraryDialog

/**
 * The Home screen: a banner of trending titles (four styles, chosen in Settings > Appearance), two
 * quick buttons, then rows of posters. Chips at the top pick the sources and metadata provider and
 * open the genre / NSFW filters; the floating toggle switches between manga and novels.
 */
@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>(), TrackerLibraryDialog.Host {

	private val viewModel by viewModels<HomeViewModel>()

	private var heroItems: List<HeroItem> = emptyList()
	private var heroIndex = 0
	private var config = HomeConfig()
	private val spotlightAdapter = HomeSpotlightAdapter(::openHero)

	private class RowHolder(val binding: ItemHomeRowBinding, val adapter: HomePosterAdapter)

	private val rowHolders = LinkedHashMap<String, RowHolder>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeBinding {
		return FragmentHomeBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)

		bindQuickButton(binding.buttonUpdates, R.string.home_updates_and_feed, R.drawable.ic_feed) {
			router.openMangaUpdates()
		}
		bindQuickButton(binding.buttonRandom, R.string.home_surprise_me, R.drawable.ic_dice) {
			viewModel.openRandom()
		}

		// Panels: tapping a small panel promotes it, the big one opens details.
		val open = View.OnClickListener { currentHero()?.let(::openHero) }
		binding.hero.panelMain.setOnClickListener(open)
		binding.hero.buttonRead.setOnClickListener(open)
		binding.hero.buttonInfo.setOnClickListener(open)
		binding.hero.panelSide1.setOnClickListener { promote(1) }
		binding.hero.panelSide2.setOnClickListener { promote(2) }
		// Card and Spotlight
		with(binding.heroAlt) {
			cardRoot.setOnClickListener(open)
			// Swipe the Card banner sideways to move through the titles: left = next, right = previous
			val density = resources.displayMetrics.density
			val swipe = android.view.GestureDetector(
				requireContext(),
				object : android.view.GestureDetector.SimpleOnGestureListener() {
					override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
						if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) cardRoot.parent?.requestDisallowInterceptTouchEvent(true)
						return false
					}

					override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
						val start = e1 ?: return false
						val travelled = e2.x - start.x
						if (kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(travelled) > 56 * density) {
							promote(if (travelled < 0) 1 else -1)
							return true
						}
						return false
					}
				},
			)
			cardRoot.setOnTouchListener { _, event -> swipe.onTouchEvent(event) && event.actionMasked == android.view.MotionEvent.ACTION_UP }
			spotRecycler.adapter = spotlightAdapter
			androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(spotRecycler)
			// Padding so the first and last cards can sit in the middle
			spotRecycler.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
				if (right - left != oldRight - oldLeft) {
					val pad = ((right - left) - (264 * resources.displayMetrics.density).toInt()) / 2
					v.setPadding(pad.coerceAtLeast(0), v.paddingTop, pad.coerceAtLeast(0), v.paddingBottom)
				}
				v.post { scaleSpotlight() }
			}
			spotRecycler.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
					scaleSpotlight()
				}
			})
		}

		// Sources, Filters, Metadata and Banner live in the three-dots menu, like on the other tabs
		addMenuProvider(
			HomeMenuProvider(
				context = requireContext(),
				config = { config },
				onFilters = ::showFiltersDialog,
				onMetadata = ::showMetadataDialog,
				onBanner = ::showBannerDialog,
			),
		)

		binding.swipeRefreshLayout.setOnRefreshListener { viewModel.refresh() }
		viewModel.isLoading.observe(viewLifecycleOwner) { binding.swipeRefreshLayout.isRefreshing = it }
		viewModel.uiState.observe(viewLifecycleOwner) { render(binding, it) }
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.scrollView, this))
		viewModel.onOpenManga.observeEvent(viewLifecycleOwner) { router.openDetails(it) }
		viewModel.onChooseSource.observeEvent(viewLifecycleOwner) { request ->
			startActivity(
				org.koitharu.kotatsu.metadata.SourceChooserActivity.intent(requireContext(), request.query, request.mode, request.metaKey),
			)
		}
		viewModel.onSearchFallback.observeEvent(viewLifecycleOwner) { query ->
			Toast.makeText(requireContext(), R.string.ds_open_not_found, Toast.LENGTH_SHORT).show()
			router.openSearch(query)
		}

		// The banner turns a page every five seconds while Home is on screen (not for Spotlight,
		// which is swiped, and not when the user turned rotation off).
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				while (true) {
					delay(HERO_INTERVAL_MS)
					if (heroItems.size > 1 && config.autoRotate && config.banner != HomeBannerStyle.SPOTLIGHT) promote(1)
				}
			}
		}
	}

	override fun onDestroyView() {
		rowHolders.clear()
		heroItems = emptyList()
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		// Extra room at the bottom so the floating toggle never covers the last row
		// Room for the floating bottom bar; the Manga | Novel switch lives in the book button's menu
		viewBinding?.layoutContent?.setPadding(bars.left, 0, bars.right, bars.bottom + 112)
		return insets.consumeAllSystemBarsInsets()
	}

	// ---- Rendering ---------------------------------------------------------------------------

	private fun render(binding: FragmentHomeBinding, state: HomeUiState) {
		config = state.config
		renderConfig(binding, state.config)
		renderHero(binding, state.hero)
		renderRows(binding, state.rows)
	}

	private fun renderConfig(binding: FragmentHomeBinding, c: HomeConfig) {
		val panels = c.banner == HomeBannerStyle.PANELS
		binding.hero.heroRoot.isVisible = panels && heroVisible
		binding.heroAlt.altRoot.isVisible = !panels && heroVisible
		binding.heroAlt.cardRoot.isVisible = c.banner == HomeBannerStyle.CARD
		binding.heroAlt.spotRecycler.isVisible = c.banner == HomeBannerStyle.SPOTLIGHT
	}

	private var heroVisible = true

	private fun renderHero(binding: FragmentHomeBinding, state: HomeSectionState<HeroItem>) {
		heroVisible = state !is HomeSectionState.Hidden
		val panels = config.banner == HomeBannerStyle.PANELS
		binding.hero.heroRoot.isVisible = panels && heroVisible
		binding.heroAlt.altRoot.isVisible = !panels && heroVisible
		binding.hero.heroProgress.isVisible = panels && state is HomeSectionState.Loading
		binding.heroAlt.altProgress.isVisible = !panels && state is HomeSectionState.Loading
		if (state is HomeSectionState.Content) {
			val changed = state.items.map { it.key } != heroItems.map { it.key }
			heroItems = state.items
			if (changed) heroIndex = 0
			bindHero(binding)
			spotlightAdapter.submit(state.items)
			binding.heroAlt.spotRecycler.post {
				// A new set of titles starts the endless carousel over at the first one
				if (changed) {
					(binding.heroAlt.spotRecycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
						?.scrollToPositionWithOffset(spotlightAdapter.startPosition(), 0)
				}
				scaleSpotlight()
			}
		}
		// The quick buttons borrow cover art from titles further down the list.
		val extras = (state as? HomeSectionState.Content)?.items.orEmpty().drop(3)
		extras.getOrNull(0)?.let { binding.buttonUpdates.quickImage.loadHero(it, banner = false) }
		extras.getOrNull(1)?.let { binding.buttonRandom.quickImage.loadHero(it, banner = false) }
	}

	private fun bindHero(binding: FragmentHomeBinding) {
		val n = heroItems.size
		if (n == 0) return
		val main = heroItems[heroIndex % n]
		val badge = main.badge ?: getString(R.string.home_trending_badge, (heroIndex % n) + 1)
		val genre = main.genres.firstOrNull()?.uppercase().orEmpty()
		// The book icon / "Read" is for manga and novels; anime gets a play icon / "Watch".
		val anime = config.mode == HomeMode.ANIME
		binding.hero.buttonRead.setImageResource(if (anime) R.drawable.ic_play else R.drawable.ic_read)
		binding.hero.buttonRead.contentDescription = getString(if (anime) R.string.watch else R.string.read)
		when (config.banner) {
			HomeBannerStyle.PANELS -> {
				val hero = binding.hero
				hero.imageMain.loadHero(main, banner = false)
				hero.textBadge.text = badge
				hero.textTitle.text = main.title
				hero.textGenre.text = genre
				// Side panels are the next two titles; with fewer than 3 they hide rather than repeat a cover.
				val side1 = if (n > 1) heroItems[(heroIndex + 1) % n] else null
				val side2 = if (n > 2) heroItems[(heroIndex + 2) % n] else null
				hero.panelSide1.isVisible = side1 != null
				hero.panelSide2.isVisible = side2 != null
				side1?.let { hero.imageSide1.loadHero(it, banner = false) }
				side2?.let { hero.imageSide2.loadHero(it, banner = false) }
			}

			HomeBannerStyle.CARD -> with(binding.heroAlt) {
				imageCardBg.loadBackdrop(main)
				imageCard.loadHero(main, banner = false)
				textCardBadge.text = badge + (main.rating?.takeIf { it > 0f }?.let { "   \u2605 " + String.format(java.util.Locale.US, "%.1f", it) }.orEmpty())
				textCardTitle.text = main.title
				textCardGenre.text = main.genres.take(3).joinToString(" \u00b7 ")
				renderDots(dotsCard, n, heroIndex % n)
			}

			HomeBannerStyle.SPOTLIGHT -> Unit // the adapter shows every title
		}
	}

	/**
	 * Wide artwork for the banners. AniList banner art is used as it is; titles without any get their
	 * cover zoomed and blurred, so a portrait cover never looks stretched into a wide shape.
	 */
	private fun CoverImageView.loadBackdrop(item: HeroItem) {
		val banner = item.bannerUrl
		if (banner != null) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
			scaleX = 1f
			scaleY = 1f
			setImageAsync(banner)
		} else {
			loadHero(item, banner = false)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP))
			}
			scaleX = 1.4f
			scaleY = 1.4f
		}
	}

	/** Pager dots: the current one is wider. */
	private fun renderDots(container: LinearLayout, count: Int, index: Int) {
		val density = resources.displayMetrics.density
		fun px(dp: Int) = (dp * density).toInt()
		if (container.childCount != count) {
			container.removeAllViews()
			repeat(count) {
				container.addView(
					View(container.context).apply {
						background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_home_dot)
						layoutParams = LinearLayout.LayoutParams(px(6), px(6)).apply { marginEnd = px(5) }
					},
				)
			}
		}
		for (i in 0 until count) {
			val dot = container.getChildAt(i)
			dot.isSelected = i == index
			dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply { width = px(if (i == index) 18 else 6) }
		}
	}

	/** The card in the middle is full size; the ones peeking in from the sides shrink and fade. */
	private fun scaleSpotlight() {
		val rv = viewBinding?.heroAlt?.spotRecycler ?: return
		val half = rv.width / 2f
		if (half <= 0f) return
		for (i in 0 until rv.childCount) {
			val child = rv.getChildAt(i)
			val distance = (kotlin.math.abs((child.left + child.right) / 2f - half) / half).coerceIn(0f, 1f)
			val scale = 1f - 0.12f * distance
			child.scaleX = scale
			child.scaleY = scale
			child.alpha = 1f - 0.45f * distance
		}
	}

	/** Cover for a title from a source (needs that source's headers) or from a tracker/metadata provider. */
	private fun CoverImageView.loadHero(item: HeroItem, banner: Boolean) {
		val url = if (banner) item.bannerUrl ?: item.coverUrl else item.coverUrl
		val manga = item.manga
		if (manga != null && url == manga.coverUrl) setImageAsync(url, manga) else setImageAsync(url)
	}

	private fun currentHero(): HeroItem? = heroItems.getOrNull(heroIndex % heroItems.size.coerceAtLeast(1))

	/** Titles from a source open directly; titles that only have names are found in the sources first. */
	private fun openHero(item: HeroItem) {
		val manga = item.manga
		val meta = item.meta
		when {
			manga != null -> router.openDetails(manga)
			meta != null -> viewModel.openMeta(meta, item.names, item.title)
			else -> viewModel.openByNames(item.names.ifEmpty { listOf(item.title) })
		}
	}

	/** Moves the banner by [offset] titles, wrapping round in either direction. */
	private fun promote(offset: Int) {
		val binding = viewBinding ?: return
		val n = heroItems.size
		if (n <= 1) return
		heroIndex = ((heroIndex + offset) % n + n) % n
		bindHero(binding)
	}

	private fun renderRows(binding: FragmentHomeBinding, rows: List<HomeRow>) {
		val container = binding.rowsContainer
		val wanted = rows.map { it.id }
		// Drop rows that are gone, keep the survivors so scroll positions survive an update.
		rowHolders.keys.filter { it !in wanted }.forEach { id ->
			rowHolders.remove(id)?.let { container.removeView(it.binding.root) }
		}
		rows.forEachIndexed { index, row ->
			val holder = rowHolders.getOrPut(row.id) { createRow(container, row) }
			if (container.getChildAt(index) !== holder.binding.root) {
				container.removeView(holder.binding.root)
				container.addView(holder.binding.root, index)
			}
			bindRow(holder, row)
		}
	}

	private fun createRow(container: ViewGroup, row: HomeRow): RowHolder {
		val rowBinding = ItemHomeRowBinding.inflate(layoutInflater, container, false)
		val adapter = HomePosterAdapter(::onPosterClick)
		rowBinding.recyclerView.adapter = adapter
		rowBinding.recyclerView.itemAnimator = null
		return RowHolder(rowBinding, adapter)
	}

	private fun bindRow(holder: RowHolder, row: HomeRow) {
		val b = holder.binding
		b.textTitle.text = if (row.title != null) {
			row.title
		} else if (row.titleArgRes != 0) {
			getString(row.titleRes, getString(row.titleArgRes))
		} else {
			getString(row.titleRes)
		}
		b.progress.isVisible = row.state is HomeSectionState.Loading
		b.recyclerView.isVisible = row.state is HomeSectionState.Content
		if (row.state is HomeSectionState.Content) {
			holder.adapter.submitList(row.state.items)
		}
		// "See All" where a full screen exists: the updates feed and the tracker's whole library.
		b.buttonSeeAll.isVisible = row.kind == HomeRowKind.UPDATES || row.kind == HomeRowKind.TRACKER
		b.buttonSeeAll.setOnClickListener {
			when (row.kind) {
				HomeRowKind.UPDATES -> router.openMangaUpdates()
				HomeRowKind.TRACKER -> holder.adapter.currentList.firstOrNull()?.trackerEntry?.service
					?.let { TrackerLibraryDialog.show(childFragmentManager, it, config.mode == HomeMode.ANIME) }

				else -> Unit
			}
		}
	}

	private fun onPosterClick(poster: HomePoster) {
		val entry = poster.trackerEntry
		when {
			poster.manga != null -> router.openDetails(poster.manga)
			poster.meta != null -> viewModel.openMeta(poster.meta, poster.altTitles, poster.title)
			// Already linked to a manga in the library: go straight to it.
			poster.localMangaId != null -> viewModel.openStored(poster.localMangaId, entry, poster.altTitles)
			// Only the tracker (or AniList metadata) knows this title: find it in the sources by every name it has.
			entry != null -> viewModel.openTrackerEntry(entry)
			poster.altTitles.isNotEmpty() -> viewModel.openByNames(poster.altTitles)
		}
	}

	override fun onLibraryEntryClick(entry: TrackerReadingEntry, localMangaId: Long?) {
		if (localMangaId != null) {
			viewModel.openStored(localMangaId, entry, listOf(entry.title))
		} else {
			viewModel.openTrackerEntry(entry)
		}
	}

	// ---- Chips: sources, metadata, filters ----------------------------------------------------

	private fun showSourcesDialog() {
		viewLifecycleOwner.lifecycleScope.launch {
			val ctx = context ?: return@launch
			val sources = viewModel.sourcesForPicker()
			if (sources.isEmpty()) {
				Toast.makeText(ctx, R.string.ds_home_sources_none, Toast.LENGTH_SHORT).show()
				return@launch
			}
			// Real titles and icons: the internal name of an extension source is just an id
			val choices = sources.map { SourceChoice(it, it.getTitle(ctx)) }.sortedBy { it.title.lowercase() }
			val picked = config.sourceNames.toMutableSet()
			val b = DialogHomeSourcesBinding.inflate(layoutInflater)
			b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
			b.list.adapter = HomeSourceAdapter(choices, picked)
			buildAlertDialog(ctx) {
				setTitle(R.string.ds_home_sources_title)
				setView(b.root)
				setPositiveButton(R.string.done) { _, _ -> viewModel.setSources(picked) }
				setNeutralButton(R.string.ds_home_sources_all) { _, _ -> viewModel.setSources(emptySet()) }
				setNegativeButton(android.R.string.cancel, null)
			}.show()
		}
	}

	private fun showBannerDialog() {
		val context = requireContext()
		val styles = HomeBannerStyle.values()
		val density = resources.displayMetrics.density
		var dialog: androidx.appcompat.app.AlertDialog? = null
		val list = android.widget.LinearLayout(context).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), (8 * density).toInt())
		}
		styles.forEach { style ->
			val chosen = style == config.banner
			val card = com.google.android.material.card.MaterialCardView(context).apply {
				layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() }
				radius = 20 * density
				cardElevation = 0f
				strokeWidth = ((if (chosen) 3 else 1) * density).toInt()
				strokeColor = context.getThemeColor(
					if (chosen) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant,
				)
				isClickable = true
				isFocusable = true
			}
			val column = android.widget.LinearLayout(context).apply {
				orientation = android.widget.LinearLayout.VERTICAL
				val p = (10 * density).toInt()
				setPadding(p, p, p, p)
			}
			column.addView(BannerStylePreviewView(context, style))
			column.addView(
				android.widget.TextView(context).apply {
					text = getString(style.titleRes)
					setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
					setPadding((4 * density).toInt(), (10 * density).toInt(), 0, 0)
				},
			)
			column.addView(
				android.widget.TextView(context).apply {
					text = getString(style.descRes)
					setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
					setPadding((4 * density).toInt(), 0, 0, 0)
				},
			)
			card.addView(column)
			card.setOnClickListener {
				viewModel.setBannerStyle(style)
				dialog?.dismiss()
			}
			list.addView(card)
		}
		dialog = buildAlertDialog(context) {
			setTitle(R.string.ds_banner_style)
			setView(android.widget.ScrollView(context).apply { addView(list) })
			setNeutralButton(
				if (config.autoRotate) R.string.ds_banner_rotate_off else R.string.ds_banner_rotate_on,
			) { _, _ -> viewModel.setBannerAutoRotate(!config.autoRotate) }
			setNegativeButton(android.R.string.cancel, null)
		}.also { it.show() }
	}

	private fun showMetadataDialog() {
		val ctx = requireContext()
		val mode = config.mode
		viewLifecycleOwner.lifecycleScope.launch {
			val picked = config.sourceNames.firstOrNull()
			val pickedTitle = picked?.let { name -> viewModel.sourcesForPicker().firstOrNull { it.name == name }?.getTitle(ctx) }
			MetadataSheet.show(
				context = ctx,
				state = MetadataSheet.State(
					mode = mode,
					providers = HomeMetadata.values().filter { it != HomeMetadata.SOURCES && viewModel.supportsProvider(it, mode) },
					provider = viewModel.currentProvider(),
					catalogue = config.metadata == HomeMetadata.SOURCES,
					catalogueSourceTitle = pickedTitle,
				),
				onProvider = viewModel::setProvider,
				onCatalogue = viewModel::setSourceCatalogue,
				onPickSource = {
					startActivity(org.koitharu.kotatsu.metadata.SourceChooserActivity.pickIntent(ctx, mode))
				},
			)
		}
	}

	private fun showFiltersDialog() {
		val ctx = requireContext()
		val b = DialogHomeFiltersBinding.inflate(layoutInflater)
		val include = config.include.toMutableSet()
		val exclude = config.exclude.toMutableSet()
		b.switchNsfw.isChecked = config.hideNsfw
		GENRES.forEach { genre ->
			val chip = Chip(ctx).apply { isCheckable = false }
			fun paint() {
				val (bg, fg, prefix) = when {
					genre in include -> Triple(
						com.google.android.material.R.attr.colorPrimaryContainer,
						com.google.android.material.R.attr.colorOnPrimaryContainer,
						"+ ",
					)

					genre in exclude -> Triple(
						com.google.android.material.R.attr.colorErrorContainer,
						com.google.android.material.R.attr.colorOnErrorContainer,
						"− ",
					)

					else -> Triple(
						com.google.android.material.R.attr.colorSurfaceContainerHigh,
						com.google.android.material.R.attr.colorOnSurface,
						"",
					)
				}
				chip.text = prefix + genre
				chip.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(chip, bg))
				chip.setTextColor(MaterialColors.getColor(chip, fg))
			}
			chip.setOnClickListener {
				// none -> include -> exclude -> none
				when {
					genre in include -> {
						include -= genre
						exclude += genre
					}

					genre in exclude -> exclude -= genre
					else -> include += genre
				}
				paint()
			}
			paint()
			b.chipGenres.addView(chip)
		}
		buildAlertDialog(ctx) {
			setTitle(R.string.ds_home_filters_title)
			setView(b.root)
			setPositiveButton(R.string.ds_filters_apply) { _, _ ->
				viewModel.setFilters(include, exclude, b.switchNsfw.isChecked)
			}
			setNeutralButton(R.string.ds_filters_reset) { _, _ -> viewModel.setFilters(emptySet(), emptySet(), false) }
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun bindQuickButton(
		button: ItemHomeQuickButtonBinding,
		label: Int,
		icon: Int,
		onClick: () -> Unit,
	) {
		button.quickLabel.setText(label)
		button.quickIcon.setImageResource(icon)
		button.quickRoot.setOnClickListener { onClick() }
	}

	private companion object {

		const val HERO_INTERVAL_MS = 5_000L

		/** AniList's genre names; sources match them without regard to case. */
		val GENRES = listOf(
			"Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha",
			"Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural",
			"Thriller",
		)
	}
}
