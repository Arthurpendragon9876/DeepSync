package org.koitharu.kotatsu.home.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.domain.ExploreRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.home.ui.model.HeroItem
import org.koitharu.kotatsu.home.ui.model.HomeBannerStyle
import org.koitharu.kotatsu.home.ui.model.HomeConfig
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.home.ui.model.HomePoster
import org.koitharu.kotatsu.home.ui.model.HomeRow
import org.koitharu.kotatsu.home.ui.model.HomeRowKind
import org.koitharu.kotatsu.home.ui.model.HomeSectionState
import org.koitharu.kotatsu.home.ui.model.HomeUiState
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.anilist.data.AniListRepository
import org.koitharu.kotatsu.scrobbling.common.domain.LinkTrackerEntryUseCase
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import org.koitharu.kotatsu.tracker.work.TrackWorker
import javax.inject.Inject

/**
 * Drives the Home screen:
 *  - hero: the first few trending titles, shown as the tilted-panel collage
 *  - "Continue on AniList / MyAnimeList / MangaBaka": what each signed-in account is reading
 *  - "Continue reading" from local history, only when no tracker is signed in
 *  - new chapters from the local tracker, Recently released, Trending, Popular
 *
 * Every block loads independently, so a slow source never holds up the rest.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
	private val settings: AppSettings,
	private val db: MangaDatabase,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val trackingRepository: TrackingRepository,
	private val trackScheduler: TrackWorker.Scheduler,
	private val mangaListMapper: MangaListMapper,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val suggestionsScheduler: SuggestionsWorker.Scheduler,
	private val exploreRepository: ExploreRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val aniListRepository: AniListRepository,
	private val linkTrackerEntry: LinkTrackerEntryUseCase,
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val onOpenManga = MutableEventFlow<Manga>()

	/** A title that no source could match by name: open the global search for it instead. */
	val onSearchFallback = MutableEventFlow<String>()

	private val config: StateFlow<HomeConfig> = homeConfigFlow()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, readConfig())

	/** Bumped by pull-to-refresh so every remote block re-queries. */
	private val refreshTick = MutableStateFlow(0)

	val uiState: StateFlow<HomeUiState> = combine(
		updatesRow(),
		trackerRows(),
		localContinueRow(),
		trendingFlow(),
		listRow("recent", HomeRowKind.RECENT, R.string.home_recently_released) {
			exploreRepository.getRecentlyReleased(ROW_SIZE, query = config.value.query)
		},
		listRow("popular", HomeRowKind.POPULAR, R.string.home_popular) {
			exploreRepository.getPopular(ROW_SIZE, query = config.value.query)
		},
		config,
		favouriteRows(),
	) { values ->
		@Suppress("UNCHECKED_CAST")
		val updates = values[0] as HomeRow
		@Suppress("UNCHECKED_CAST")
		val tracker = values[1] as List<HomeRow>
		val local = values[2] as HomeRow
		@Suppress("UNCHECKED_CAST")
		val trending = values[3] as HomeSectionState<HeroItem>
		val recent = values[4] as HomeRow
		val popular = values[5] as HomeRow
		val cfg = values[6] as HomeConfig
		@Suppress("UNCHECKED_CAST")
		val favourites = values[7] as List<HomeRow>

		val hero: HomeSectionState<HeroItem> = when (trending) {
			is HomeSectionState.Content -> HomeSectionState.Content(trending.items.take(HERO_SIZE))
			else -> trending
		}
		val trendingRow = HomeRow(
			id = "trending",
			kind = HomeRowKind.TRENDING,
			titleRes = R.string.trending,
			state = when (trending) {
				// The hero already shows the first few; the row carries on from there.
				is HomeSectionState.Content -> trending.items.drop(HERO_SIZE).ifEmpty { trending.items }
					.map { it.toPoster() }
					.let { HomeSectionState.Content(it) }

				HomeSectionState.Loading -> HomeSectionState.Loading
				else -> HomeSectionState.Hidden
			},
		)
		val hasTrackerContent = tracker.any { it.state is HomeSectionState.Content }
		HomeUiState(
			config = cfg,
			hero = hero,
			rows = buildList {
				add(updates)
				addAll(tracker)
				addAll(favourites)
				if (!hasTrackerContent) add(local)
				add(recent)
				add(trendingRow)
				add(popular)
			}.filter { it.state !is HomeSectionState.Hidden },
		)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, HomeUiState())

	// ---- Settings the user can change on Home -------------------------------------------------

	fun setMode(mode: HomeMode) {
		if (settings.homeMode != mode) settings.homeMode = mode
	}

	fun setBannerStyle(style: HomeBannerStyle) {
		settings.homeBannerStyle = style
	}

	fun setBannerAutoRotate(enabled: Boolean) {
		settings.isHomeBannerAutoRotate = enabled
	}

	fun setMetadata(metadata: HomeMetadata) {
		settings.homeMetadata = metadata
	}

	fun setSources(names: Set<String>) {
		settings.setHomeSources(settings.homeMode, names)
	}

	fun setFilters(include: Set<String>, exclude: Set<String>, hideNsfw: Boolean) {
		settings.homeGenresInclude = include
		settings.homeGenresExclude = exclude
		settings.isHomeHideNsfw = hideNsfw
	}

	/** Sources for the picker, in the mode Home is showing. */
	suspend fun sourcesForPicker() = exploreRepository.getEnabledSources(config.value.mode)

	/**
	 * Opens a title that only has names (an AniList entry or trending title): finds it in the sources
	 * by any of them, and falls back to the global search if nothing is close enough.
	 */
	/**
	 * Opens a title from a tracker's own list: finds it in the sources, then ties that copy to the
	 * tracker entry and brings local progress up to the tracker's before showing it.
	 */
	fun openTrackerEntry(entry: TrackerReadingEntry) {
		launchLoadingJob(Dispatchers.Default) {
			val names = (listOf(entry.title) + entry.altTitles).distinct()
			val mode = when {
				entry.isAnime -> HomeMode.ANIME
				entry.isNovel -> HomeMode.NOVEL
				else -> HomeMode.MANGA
			}
			val found = exploreRepository.findBestSourceMatch(names, mode)
			if (found == null) {
				onSearchFallback.call(names.first())
			} else {
				linkTrackerEntry(found, entry)
				onOpenManga.call(found)
			}
		}
	}

	fun openByNames(names: List<String>) {
		launchLoadingJob(Dispatchers.Default) {
			val found = exploreRepository.findBestSourceMatch(names, config.value.mode)
			if (found != null) {
				onOpenManga.call(found)
			} else {
				onSearchFallback.call(names.first())
			}
		}
	}

	private fun readConfig(): HomeConfig {
		val mode = settings.homeMode
		return HomeConfig(
			mode = mode,
			metadata = settings.homeMetadata,
			sourceNames = settings.getHomeSources(mode),
			include = settings.homeGenresInclude,
			exclude = settings.homeGenresExclude,
			hideNsfw = settings.isHomeHideNsfw,
			banner = settings.homeBannerStyle,
			autoRotate = settings.isHomeBannerAutoRotate,
		)
	}

	private fun homeConfigFlow(): Flow<HomeConfig> {
		fun <T> pref(key: String, read: AppSettings.() -> T): Flow<Any?> =
			settings.observeAsFlow(key, read).map { it }
		return combine(
			listOf(
				pref(AppSettings.KEY_HOME_MODE) { homeMode },
				pref(AppSettings.KEY_HOME_METADATA) { homeMetadata },
				pref(AppSettings.KEY_HOME_SOURCES_MANGA) { getHomeSources(HomeMode.MANGA) },
				pref(AppSettings.KEY_HOME_SOURCES_NOVEL) { getHomeSources(HomeMode.NOVEL) },
				pref(AppSettings.KEY_HOME_SOURCES_ANIME) { getHomeSources(HomeMode.ANIME) },
				pref(AppSettings.KEY_HOME_GENRES_INCLUDE) { homeGenresInclude },
				pref(AppSettings.KEY_HOME_GENRES_EXCLUDE) { homeGenresExclude },
				pref(AppSettings.KEY_HOME_HIDE_NSFW) { isHomeHideNsfw },
				pref(AppSettings.KEY_HOME_BANNER_STYLE) { homeBannerStyle },
				pref(AppSettings.KEY_HOME_BANNER_ROTATE) { isHomeBannerAutoRotate },
			),
		) { readConfig() }.distinctUntilChanged()
	}

	/** Pull-to-refresh: re-run the local tracker and suggestions, then reload every remote block. */
	fun refresh() {
		launchLoadingJob(Dispatchers.Default) {
			if (settings.isTrackerEnabled) {
				trackScheduler.startNow()
			}
			if (settings.isSuggestionsEnabled) {
				suggestionsScheduler.runNow()
			}
			refreshTick.value += 1
		}
	}

	fun openRandom() {
		launchJob(Dispatchers.Default) {
			onOpenManga.call(exploreRepository.findRandomManga(tagsLimit = 8, mode = config.value.mode))
		}
	}

	// ---- New chapters from the local tracker -------------------------------------------------

	private fun updatesRow(): Flow<HomeRow> =
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled }
			.flatMapLatest { enabled ->
				if (!enabled) {
					flowOf(emptyRow("updates", HomeRowKind.UPDATES, R.string.home_new_chapters_row))
				} else {
					val options = if (settings.isNsfwContentDisabled) setOf(ListFilterOption.SFW) else emptySet()
					combine(
						trackingRepository.observeTrackingLog(FEED_LIMIT, options),
						// Re-map when reading progress changes so the counts stay truthful.
						historyRepository.observeAll(),
						modeFlow(),
					) { log, _, mode ->
						val items = log.map { mangaListMapper.toFeedItem(it) }
							.filter { it.isNew && it.manga.source.contentMode == mode }
							.distinctBy { it.manga.id }
						HomeRow(
							id = "updates",
							kind = HomeRowKind.UPDATES,
							titleRes = if (mode == HomeMode.ANIME) R.string.home_new_episodes_row else R.string.home_new_chapters_row,
							state = if (items.isEmpty()) {
								HomeSectionState.Hidden
							} else {
								HomeSectionState.Content(items.map { it.toPoster() })
							},
						)
					}.catch { emit(emptyRow("updates", HomeRowKind.UPDATES, R.string.home_new_chapters_row)) }
				}
			}

	// ---- Continue on AniList / MAL / MangaBaka ----------------------------------------------

	private fun trackerRows(): Flow<List<HomeRow>> {
		val ordered = scrobblers.sortedBy { TRACKER_ORDER.indexOf(it.scrobblerService).let { i -> if (i < 0) 99 else i } }
		if (ordered.isEmpty()) return flowOf(emptyList())
		return combine(ordered.map(::trackerRow)) { rows -> rows.toList() }
	}

	private fun trackerRow(scrobbler: Scrobbler): Flow<HomeRow> {
		val service = scrobbler.scrobblerService
		val id = "tracker_${service.id}"
		return combine(refreshTick, modeFlow()) { _, mode -> mode }.flatMapLatest { mode ->
			flow {
				if (!scrobbler.isEnabled) {
					emit(trackerRowOf(id, service, HomeSectionState.Hidden))
					return@flow
				}
				emit(trackerRowOf(id, service, HomeSectionState.Loading))
				// Each mode lists its own kind from the tracker: anime, manga or light novels
				val anime = mode == HomeMode.ANIME
				val entries = scrobbler.getReadingList(TRACKER_LIST_SIZE * 2, anime)
					.filter { if (anime) it.isAnime else !it.isAnime && it.isNovel == (mode == HomeMode.NOVEL) }
					.take(TRACKER_LIST_SIZE)
				if (entries.isEmpty()) {
					emit(trackerRowOf(id, service, HomeSectionState.Hidden))
				} else {
					// Entries already linked to a local manga open its details page directly.
					emitAll(
						db.getScrobblingDao().observe(service.id).map { links ->
							val byTarget = links.associate { it.targetId to it.mangaId }
							trackerRowOf(
								id,
								service,
								HomeSectionState.Content(entries.map { it.toPoster(byTarget[it.targetId]) }),
							)
						},
					)
				}
			}
		}
	}

	private fun trackerRowOf(id: String, service: ScrobblerService, state: HomeSectionState<HomePoster>) = HomeRow(
		id = id,
		kind = HomeRowKind.TRACKER,
		titleRes = R.string.home_continue_on,
		titleArgRes = service.titleResId,
		state = state,
	)

	// ---- Local history, used when no tracker is signed in ----------------------------------

	private fun localContinueRow(): Flow<HomeRow> = combine(
		historyRepository.observeAll(LOCAL_CONTINUE_SIZE * 2),
		modeFlow(),
	) { all, mode -> all.filter { it.source.contentMode == mode }.take(LOCAL_CONTINUE_SIZE) }
		.map { list ->
			HomeRow(
				id = "local",
				kind = HomeRowKind.LOCAL,
				titleRes = if (config.value.mode == HomeMode.ANIME) R.string.home_continue_watching else R.string.home_continue_reading,
				state = if (list.isEmpty()) {
					HomeSectionState.Hidden
				} else {
					HomeSectionState.Content(list.map { it.toPoster() })
				},
			)
		}
		.catch { emit(emptyRow("local", HomeRowKind.LOCAL, R.string.home_continue_reading)) }

	// ---- Source lists ------------------------------------------------------------------------

	private fun reloadTrigger(): Flow<Unit> = combine(
		// Re-query when the set of enabled sources changes (e.g. extensions finish loading)...
		sourcesRepository.observeEnabledSources().map { sources -> sources.map { it.name } }.distinctUntilChanged(),
		// ...when the user pulls to refresh...
		refreshTick,
		// ...toggles the NSFW filter...
		settings.observeAsFlow(AppSettings.KEY_CONTENT_FILTER) { isNsfwContentDisabled },
		// ...or changes mode, sources, metadata or genre filters on Home.
		config,
	) { _, _, _, _ -> }

	/**
	 * One carousel per favourites category the user picked in Settings > Appearance, newest first.
	 * Updates live as the category changes.
	 */
	private fun favouriteRows(): Flow<List<HomeRow>> = combine(
		settings.observeAsFlow(AppSettings.KEY_HOME_FAV_CATEGORIES) { homeFavCategories },
		modeFlow(),
	) { ids, mode -> ids to mode }.flatMapLatest { (ids, mode) ->
		if (ids.isEmpty()) {
			flowOf(emptyList())
		} else {
			favouritesRepository.observeCategories().flatMapLatest { categories ->
				val chosen = categories.filter { it.id in ids }
				if (chosen.isEmpty()) {
					flowOf(emptyList())
				} else {
					combine(
						chosen.map { category ->
							favouritesRepository.observeAll(category.id, ListSortOrder.NEWEST, emptySet(), FAV_ROW_SIZE * 2)
								.map { list ->
									val items = list.filter { it.source.contentMode == mode }
										.take(FAV_ROW_SIZE)
									HomeRow(
										id = "fav_${category.id}",
										kind = HomeRowKind.FAVOURITES,
										titleRes = 0,
										title = category.title,
										state = if (items.isEmpty()) {
											HomeSectionState.Hidden
										} else {
											HomeSectionState.Content(items.map { it.toPoster() })
										},
									)
								}
						},
					) { rows -> rows.toList() }
				}
			}
		}
	}.catch { emit(emptyList()) }

	private fun modeFlow(): Flow<HomeMode> = config.map { it.mode }.distinctUntilChanged()

	private fun trendingFlow(): Flow<HomeSectionState<HeroItem>> = reloadTrigger().flatMapLatest {
		flow {
			emit(HomeSectionState.Loading)
			val cfg = config.value
			val fromAniList = if (cfg.metadata == HomeMetadata.ANILIST) loadAniListTrending(cfg) else emptyList()
			var raw: List<Manga> = emptyList()
			val list = fromAniList.ifEmpty {
				raw = exploreRepository.getTrending(TRENDING_SIZE, query = cfg.query)
				raw.map { it.toHero() }
			}
			emit(if (list.isEmpty()) HomeSectionState.Hidden else HomeSectionState.Content(list))
			// Source lists carry no synopsis or genres, which left Spotlight's text pill empty. Show the
			// banner straight away, then fill those in from each title's own page and publish it again.
			if (fromAniList.isEmpty() && raw.isNotEmpty()) {
				val detailed = withDetails(raw, DETAILED_HEROES)
				if (detailed.any { it.description != null || it.genres.isNotEmpty() }) {
					emit(HomeSectionState.Content(detailed + raw.drop(DETAILED_HEROES).map { it.toHero() }))
				}
			}
		}.catch { emit(HomeSectionState.Hidden) }
	}

	/** AniList's trending list with the Home filters applied. Empty on any failure so Home falls back to the sources. */
	private suspend fun loadAniListTrending(cfg: HomeConfig): List<HeroItem> = runCatching {
		aniListRepository.getTrending(
			limit = TRENDING_SIZE,
			novel = cfg.isNovel,
			include = cfg.include,
			exclude = cfg.exclude,
			hideAdult = settings.isNsfwContentDisabled || cfg.hideNsfw,
			anime = cfg.mode == HomeMode.ANIME,
		).mapIndexed { index, t ->
			HeroItem(
				key = "a${t.id}",
				title = t.title,
				coverUrl = t.coverUrl,
				bannerUrl = t.bannerUrl,
				genres = t.genres,
				badge = "#${index + 1} TRENDING ON ANILIST",
				names = t.names,
				rating = t.score,
				description = t.description?.cleanSynopsis(),
			)
		}
	}.getOrDefault(emptyList())

	private fun listRow(
		id: String,
		kind: HomeRowKind,
		titleRes: Int,
		load: suspend () -> List<Manga>,
	): Flow<HomeRow> = reloadTrigger().flatMapLatest {
		flow {
			emit(HomeRow(id, kind, titleRes, state = HomeSectionState.Loading))
			val list = load()
			emit(
				HomeRow(
					id, kind, titleRes,
					state = if (list.isEmpty()) {
						HomeSectionState.Hidden
					} else {
						HomeSectionState.Content(list.map { it.toPoster() })
					},
				),
			)
		}.catch { emit(emptyRow(id, kind, titleRes)) }
	}.onStart { emit(HomeRow(id, kind, titleRes, state = HomeSectionState.Loading)) }

	// ---- Mapping -----------------------------------------------------------------------------

	private fun emptyRow(id: String, kind: HomeRowKind, titleRes: Int) =
		HomeRow(id, kind, titleRes, state = HomeSectionState.Hidden)

	private fun Manga.toPoster() = HomePoster(
		key = "m$id",
		title = title,
		coverUrl = coverUrl,
		manga = this,
		rating = rating.takeIf { it >= 0f }?.times(10f),
	)

	/** Plain, single-line synopsis: markup and line breaks removed. */
	private fun String.cleanSynopsis(): String? =
		androidx.core.text.HtmlCompat.fromHtml(this, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
			.toString().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }

	/** The first [count] of [list] as hero items with synopsis, genres and rating taken from their detail pages. */
	private suspend fun withDetails(list: List<Manga>, count: Int): List<HeroItem> = kotlinx.coroutines.coroutineScope {
		list.take(count).map { manga ->
			async(Dispatchers.IO) {
				val base = manga.toHero()
				if (base.description != null && base.genres.isNotEmpty()) return@async base
				val details = kotlinx.coroutines.withTimeoutOrNull(DETAILS_TIMEOUT_MS) {
					runCatching { mangaRepositoryFactory.create(manga.source).getDetails(manga) }.getOrNull()
				} ?: return@async base
				base.copy(
					description = base.description ?: details.description?.cleanSynopsis(),
					genres = base.genres.ifEmpty { details.tags.map { it.title }.take(3) },
					rating = base.rating ?: details.rating.takeIf { it >= 0f }?.times(10f),
				)
			}
		}.map { it.await() }
	}

	private fun Manga.toHero() = HeroItem(
		key = "m$id",
		title = title,
		coverUrl = coverUrl,
		bannerUrl = null,
		genres = tags.map { it.title }.take(3),
		manga = this,
		names = listOf(title) + altTitles,
		rating = rating.takeIf { it >= 0f }?.times(10f),
		description = description?.cleanSynopsis(),
	)

	private fun HeroItem.toPoster() = HomePoster(
		key = key,
		title = title,
		coverUrl = coverUrl,
		manga = manga,
		rating = rating,
		altTitles = if (manga == null) names else emptyList(),
	)

	private fun FeedItem.toPoster() = HomePoster(
		key = "f$id",
		title = title,
		coverUrl = imageUrl,
		manga = manga,
		newCount = chapters.count { it.isNew },
	)

	private fun TrackerReadingEntry.toPoster(localMangaId: Long?) = HomePoster(
		key = "t${service.id}_$targetId",
		title = title,
		coverUrl = coverUrl,
		trackerEntry = this,
		localMangaId = localMangaId,
		chapter = chapter,
		altTitles = (listOf(title) + altTitles).distinct(),
	)

	companion object {

		private const val HERO_SIZE = 6
		private const val DETAILED_HEROES = 6
		private const val DETAILS_TIMEOUT_MS = 6000L
		private const val TRENDING_SIZE = 18
		private const val ROW_SIZE = 15
		private const val FAV_ROW_SIZE = 20
		private const val FEED_LIMIT = 15
		private const val LOCAL_CONTINUE_SIZE = 15
		private const val TRACKER_LIST_SIZE = 20

		private val TRACKER_ORDER = listOf(
			ScrobblerService.ANILIST,
			ScrobblerService.MAL,
			ScrobblerService.MANGABAKA,
			ScrobblerService.KITSU,
			ScrobblerService.SHIKIMORI,
		)
	}
}
