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
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
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
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
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
import org.koitharu.kotatsu.home.ui.model.HomeCarousel
import org.koitharu.kotatsu.home.ui.model.HomeConfig
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomePoster
import org.koitharu.kotatsu.home.ui.model.HomeRow
import org.koitharu.kotatsu.home.ui.model.HomeRowKind
import org.koitharu.kotatsu.home.ui.model.HomeSectionState
import org.koitharu.kotatsu.home.ui.model.HomeUiState
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.main.ui.MainViewModel
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.scrobbling.common.ui.SyncLibraryDialog
import org.koitharu.kotatsu.scrobbling.common.ui.TrackerLibraryDialog

/**
 * The Home screen: its own header (YUGEN wordmark, settings, bell, search), a banner of trending titles,
 * a "Surprise me" button, then rows of posters. The wordmark opens Yugen preferences (incognito, filters,
 * metadata, Home layout with a switch for every carousel); the bell opens Updates & feed. The Manga | Novel
 * | Anime switch lives in the book button of the bottom bar.
 */
@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>(), TrackerLibraryDialog.Host {

	private val viewModel by viewModels<HomeViewModel>()
	private val mainViewModel by activityViewModels<MainViewModel>()

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

		// Header: wordmark = Yugen preferences, bell = Updates & feed
		with(binding.header) {
			headerBrand.setOnClickListener { showYugenPreferences() }
			headerSettings.setOnClickListener { router.openSettings() }
			headerBell.setOnClickListener { router.openMangaUpdates() }
			headerMode.setOnClickListener { cycleMode(it) }
			headerMode.setOnLongClickListener { showModeMenu(it); true }
			headerSearch.setOnClickListener {
				(activity as? androidx.appcompat.app.AppCompatActivity)?.onSupportNavigateUp()
			}
		}
		mainViewModel.feedCounter.observe(viewLifecycleOwner) { binding.header.headerBellDot.isVisible = it != 0 }

		bindQuickButton(binding.buttonSuggestions, R.string.suggestions, R.drawable.ic_suggestion) {
			router.openSuggestions()
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

	override fun onStart() {
		super.onStart()
		if (!isHidden) setMainAppBarHidden(true)
	}

	override fun onResume() {
		super.onResume()
		// Home draws its own header, so the activity's top bar (settings, search, overflow) steps aside
		if (!isHidden) setMainAppBarHidden(true)
	}

	/** Switching tabs hides this fragment without stopping it, so the bar comes back (or goes) here. */
	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		setMainAppBarHidden(!hidden)
	}

	/**
	 * Not onPause: opening Settings or Updates pauses Home while it is still on screen, and bringing the bar back
	 * then made the old search bar flash for a moment. onStop comes once the other screen has covered it.
	 */
	override fun onStop() {
		setMainAppBarHidden(false)
		super.onStop()
	}

	/**
	 * Hiding the bar with GONE left the page below it offset by the bar's old height (a gap above the header that
	 * showed up after coming back from another screen). A zero-height, invisible bar keeps the offset at zero.
	 */
	private fun setMainAppBarHidden(hidden: Boolean) {
		val appBar = (activity as? AppBarOwner)?.appBar ?: return
		if (hidden) {
			appBar.setExpanded(true, false)
			appBar.updateLayoutParams { height = 0 }
			appBar.visibility = View.INVISIBLE
		} else {
			appBar.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT }
			appBar.visibility = View.VISIBLE
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
		// The top inset is ours now: the activity's top bar, which used to carry it, is hidden on Home
		viewBinding?.layoutContent?.setPadding(bars.left, bars.top, bars.right, bars.bottom + 112)
		return insets.consumeAllSystemBarsInsets()
	}

	// ---- Rendering ---------------------------------------------------------------------------

	private fun render(binding: FragmentHomeBinding, state: HomeUiState) {
		val modeChanged = modeShown != null && modeShown != state.config.mode
		config = state.config
		renderConfig(binding, state.config)
		renderHero(binding, state.hero)
		renderRows(binding, state.rows)
		bindModeChip(binding, state.config.mode, animate = modeChanged)
		modeShown = state.config.mode
		if (modeChanged) playFeedReveal(binding)
	}

	private var modeShown: HomeMode? = null

	/** Manga, then Anime, then Novel, then round again. */
	private fun cycleMode(anchor: View) {
		val order = listOf(HomeMode.MANGA, HomeMode.ANIME, HomeMode.NOVEL)
		val next = order[(order.indexOf(config.mode) + 1) % order.size]
		anchor.hapticFeedback(HapticEffect.CONFIRM)
		viewModel.setMode(next)
	}

	/** Long-press on the mode chip: the three modes as a list, the current one ticked. */
	private fun showModeMenu(anchor: View) {
		anchor.hapticFeedback(HapticEffect.LONG_PRESS)
		val menu = androidx.appcompat.widget.PopupMenu(anchor.context, anchor)
		listOf(HomeMode.ANIME, HomeMode.MANGA, HomeMode.NOVEL).forEachIndexed { index, mode ->
			menu.menu.add(0, index, index, modeTitleRes(mode)).apply {
				isCheckable = true
				isChecked = mode == config.mode
			}
		}
		menu.menu.setGroupCheckable(0, true, true)
		menu.setOnMenuItemClickListener { item ->
			val picked = listOf(HomeMode.ANIME, HomeMode.MANGA, HomeMode.NOVEL)[item.itemId]
			if (picked != config.mode) {
				anchor.hapticFeedback(HapticEffect.CONFIRM)
				viewModel.setMode(picked)
			}
			true
		}
		menu.show()
	}

	private fun modeTitleRes(mode: HomeMode) = when (mode) {
		HomeMode.ANIME -> R.string.ds_mode_anime
		HomeMode.NOVEL -> R.string.ds_mode_novel
		HomeMode.MANGA -> R.string.ds_mode_manga
	}

	/** The header chip names the mode; on a change it squashes, swaps and springs back. */
	private fun bindModeChip(binding: FragmentHomeBinding, mode: HomeMode, animate: Boolean) {
		val chip = binding.header.headerMode
		val apply = {
			val name = getString(modeTitleRes(mode))
			chip.text = name
			chip.setIconResource(org.koitharu.kotatsu.main.ui.nav.modeIconRes(mode))
			chip.contentDescription = getString(R.string.ds_mode_cycle, name)
		}
		if (!animate) {
			apply()
			return
		}
		chip.animate().cancel()
		chip.animate().scaleX(0.78f).scaleY(0.78f).alpha(0.4f).setDuration(80).withEndAction {
			apply()
			chip.animate().scaleX(1f).scaleY(1f).alpha(1f)
				.setInterpolator(android.view.animation.OvershootInterpolator(2.4f))
				.setDuration(280).start()
		}.start()
	}

	/** Everything under the header fades and lifts into place, a touch later for each block. */
	private fun playFeedReveal(binding: FragmentHomeBinding) {
		val content = binding.layoutContent
		val lift = 10 * resources.displayMetrics.density
		for (i in 1 until content.childCount) {
			val v = content.getChildAt(i)
			v.animate().cancel()
			v.alpha = 0.35f
			v.translationY = lift
			v.animate().alpha(1f).translationY(0f)
				.setStartDelay(((i - 1) * 35).toLong())
				.setDuration(320)
				.setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
				.start()
		}
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
		extras.getOrNull(0)?.let { binding.buttonSuggestions.quickImage.loadHero(it, banner = false) }
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
			meta != null && viewModel.opensTrackerFirst ->
				router.openMetaDetails(meta, item.title, item.coverUrl, item.bannerUrl)
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
		b.buttonSeeAll.isVisible = row.kind == HomeRowKind.UPDATES || row.kind == HomeRowKind.TRACKER ||
			row.kind == HomeRowKind.SYNC
		b.buttonSeeAll.setOnClickListener {
			when (row.kind) {
				HomeRowKind.UPDATES -> router.openMangaUpdates()
				HomeRowKind.SYNC -> SyncLibraryDialog.show(childFragmentManager, config.mode == HomeMode.ANIME)
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
			poster.meta != null && viewModel.opensTrackerFirst ->
				router.openMetaDetails(poster.meta, poster.title, poster.coverUrl, null)
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

	// ---- Yugen preferences ---------------------------------------------------------------------

	private fun showYugenPreferences() {
		val ctx = requireContext()
		val layoutSummary = getString(
			R.string.ds_prefs_layout_summary,
			getString(config.banner.titleRes),
			HomeCarousel.values().count { config.shows(it) },
		)
		YugenPreferencesSheet.show(
			context = ctx,
			state = YugenPreferencesSheet.State(
				incognito = viewModel.isIncognito(),
				filterCount = config.filterCount,
				metadataName = getString(config.metadata.titleRes),
				layoutSummary = layoutSummary,
			),
			onIncognito = viewModel::setIncognito,
			onFilters = ::showFiltersDialog,
			onMetadata = ::showMetadataDialog,
			// Home layout lives in Settings now, with its own preview
			onLayout = { router.openHomeLayoutSettings() },
		)
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
					fromTracker = viewModel.isMetadataFromTracker(),
				),
				onProvider = viewModel::setProvider,
				onCatalogue = viewModel::setSourceCatalogue,
				onFromTracker = viewModel::setMetadataFromTracker,
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
		b.switchNsfw.isChecked = viewModel.isNsfwMode()
		// Hentai is an adult-only genre, so its chip exists only while NSFW mode is on
		fun genresFor(nsfw: Boolean) = if (nsfw) GENRES + NSFW_GENRE else GENRES
		fun addGenreChips() {
			b.chipGenres.removeAllViews()
			genresFor(b.switchNsfw.isChecked).forEach { genre -> b.chipGenres.addView(createGenreChip(ctx, genre, include, exclude)) }
		}
		addGenreChips()
		b.switchNsfw.setOnCheckedChangeListener { _, checked ->
			if (!checked) {
				// A hidden chip must not keep filtering the feed
				include -= NSFW_GENRE
				exclude -= NSFW_GENRE
			}
			addGenreChips()
		}
		buildAlertDialog(ctx) {
			setTitle(R.string.ds_home_filters_title)
			setView(b.root)
			setPositiveButton(R.string.ds_filters_apply) { _, _ ->
				viewModel.setNsfwMode(b.switchNsfw.isChecked)
				viewModel.setFilters(include, exclude, false)
			}
			setNeutralButton(R.string.ds_filters_reset) { _, _ -> viewModel.setFilters(emptySet(), emptySet(), false) }
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}

	private fun createGenreChip(
		ctx: android.content.Context,
		genre: String,
		include: MutableSet<String>,
		exclude: MutableSet<String>,
	): Chip {
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
		return chip
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

		/** AniList's adult-only genre, offered in the Home filters only while NSFW mode is on. */
		const val NSFW_GENRE = "Hentai"

		/** AniList's genre names; sources match them without regard to case. */
		val GENRES = listOf(
			"Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha",
			"Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural",
			"Thriller",
		)
	}
}
