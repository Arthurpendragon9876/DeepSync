package org.koitharu.kotatsu.explore.ui.meta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.metadata.DISCOVER_GENRES
import org.koitharu.kotatsu.metadata.DISCOVER_PAGE_SIZE
import org.koitharu.kotatsu.metadata.DiscoverFilters
import org.koitharu.kotatsu.metadata.DiscoverSort
import org.koitharu.kotatsu.metadata.MetaOpenResult
import org.koitharu.kotatsu.metadata.MetaRef
import org.koitharu.kotatsu.metadata.MetadataLinks
import org.koitharu.kotatsu.metadata.MetadataOpener
import org.koitharu.kotatsu.metadata.MetadataRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.stats.domain.GenreAffinityRepository
import org.koitharu.kotatsu.stats.domain.GenreScore
import javax.inject.Inject

/** One pageable list of results: the page being shown and whether another one can be asked for. */
data class MetaListing(
	val items: List<MetadataTitle> = emptyList(),
	val page: Int = 1,
	val hasNext: Boolean = false,
	val isLoading: Boolean = false,
	val error: String? = null,
	/** False until the first load finished, so the screen can tell "empty" from "not asked yet". */
	val isLoaded: Boolean = false,
)

/** The feeds Explore's Discover page can show, picked from the pill at the top of the screen. */
enum class ExploreFeed(val label: String, val subtitle: String) {
	DISCOVER("Discover", "Picked from what you read and watch"),
	TRENDING("Trending now", "Top currently airing and hot series"),
	POPULAR("Popular all time", "Highest rated and most followed"),
	UPCOMING("Upcoming", "Announced and not yet released"),
}

/** The switches behind Explore's quick preferences sheet; each one is a real app setting. */
data class ExploreQuickPrefs(
	val discoverDefault: Boolean = true,
	val autoUpdateExtensions: Boolean = false,
	val hideAdult: Boolean = false,
	val metadataFromTracker: Boolean = true,
	val provider: HomeMetadata = HomeMetadata.SOURCES,
	/** Trackers that can serve the current mode, for the provider chips. */
	val providers: List<HomeMetadata> = emptyList(),
)

data class ExploreMetaState(
	val feed: ExploreFeed = ExploreFeed.DISCOVER,
	val isListView: Boolean = false,
	val provider: HomeMetadata = HomeMetadata.SOURCES,
	val mode: HomeMode = HomeMode.MANGA,
	val filters: DiscoverFilters = DiscoverFilters(),
	/** Search / filter results; only meaningful while [DiscoverFilters.isActive]. */
	val results: MetaListing = MetaListing(),
	/** "Picked for you": paged suggestions for [forYouGenre], or the provider's popular list. */
	val forYou: MetaListing = MetaListing(),
	val forYouGenre: String? = null,
	val topGenres: List<GenreScore> = emptyList(),
	val samples: List<MetadataTitle> = emptyList(),
	val samplesLoading: Boolean = false,
) {
	val isAvailable: Boolean
		get() = provider != HomeMetadata.SOURCES
}

/** What opening a result turned into; the fragment performs the navigation. */
sealed interface MetaTarget {
	data class Details(val ref: MetaRef, val title: String, val cover: String?, val banner: String?) : MetaTarget
	data class Library(val manga: Manga) : MetaTarget
	data class ChooseSource(val query: String, val mode: HomeMode, val key: String) : MetaTarget
}

/**
 * Drives Explore's "Metadata" tab: it searches and browses whatever tracker the app uses as its metadata
 * source (AniList, MyAnimeList or MangaBaka) in the active Anime / Manga / Novel mode. Home already shows
 * the trending rows, so this tab is for finding things: search with filters, suggestions the user can
 * page through past one page's worth, a few samples to get hooked on, and genres picked from what the
 * user actually reads and watches.
 */
@HiltViewModel
class ExploreMetadataViewModel @Inject constructor(
	private val repository: MetadataRepository,
	private val settings: AppSettings,
	private val affinity: GenreAffinityRepository,
	private val opener: MetadataOpener,
) : ViewModel() {

	private val mutableState = MutableStateFlow(
		ExploreMetaState(
			provider = settings.homeMetadata,
			mode = settings.homeMode,
			isListView = settings.isExploreListView,
		),
	)
	val state: StateFlow<ExploreMetaState> = mutableState.asStateFlow()

	val onOpen = MutableEventFlow<MetaTarget>()

	/** 0 = Sources, 1 = Discover; kept here so a rotation does not bounce back to the sources. */
	val selectedTab = MutableStateFlow(if (settings.isExploreDiscoverDefault) 1 else 0)

	private val mutableQuickPrefs = MutableStateFlow(readQuickPrefs())
	val quickPrefs: StateFlow<ExploreQuickPrefs> = mutableQuickPrefs.asStateFlow()

	/** True while a pull-to-refresh is reloading the page; the fragment shows it on the swipe layout. */
	private val mutableRefreshing = MutableStateFlow(false)
	val isRefreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

	private var resultsJob: Job? = null
	private var forYouJob: Job? = null
	private var samplesJob: Job? = null
	private var started = false

	/**
	 * Called whenever the tab is shown. The provider and the content mode are chosen elsewhere (Home
	 * settings, the nav bar's mode button), so they are re-read here and everything reloads on a change.
	 */
	fun sync() {
		val provider = settings.homeMetadata
		val mode = settings.homeMode
		val current = mutableState.value
		if (started && provider == current.provider && mode == current.mode) return
		started = true
		resultsJob?.cancel()
		forYouJob?.cancel()
		samplesJob?.cancel()
		mutableQuickPrefs.value = readQuickPrefs()
		mutableState.value = ExploreMetaState(
			feed = current.feed,
			isListView = settings.isExploreListView,
			provider = provider,
			mode = mode,
		)
		if (provider == HomeMetadata.SOURCES) return
		viewModelScope.launch {
			val top = runCatching { affinity.top(mode, limit = 10) }.getOrDefault(emptyList())
			mutableState.update { it.copy(topGenres = top) }
			val firstGenre = top.firstOrNull { g -> DISCOVER_GENRES.any { it == g.genre } }?.genre
			selectForYouGenre(firstGenre)
			loadSamples(firstGenre)
		}
	}

	/**
	 * Pull-to-refresh: reloads what the page is showing (the search / filter results, or the feed and the
	 * samples) and the user's top genres. Unlike [sync] it does not skip when nothing changed.
	 */
	fun refresh() {
		val s = mutableState.value
		if (!s.isAvailable || mutableRefreshing.value) return
		mutableRefreshing.value = true
		viewModelScope.launch {
			try {
				mutableQuickPrefs.value = readQuickPrefs()
				val top = runCatching { affinity.top(s.mode, limit = 10) }.getOrDefault(s.topGenres)
				mutableState.update { it.copy(topGenres = top) }
				if (s.filters.isActive) {
					mutableState.update { it.copy(results = MetaListing()) }
					loadResults(page = 1)
				} else {
					mutableState.update { it.copy(forYou = MetaListing()) }
					loadForYou(page = 1)
					loadSamples(s.forYouGenre)
				}
				listOfNotNull(resultsJob, forYouJob, samplesJob).joinAll()
			} finally {
				mutableRefreshing.value = false
			}
		}
	}

	// ---- search & filters ----------------------------------------------------------------------

	fun setFilters(filters: DiscoverFilters) {
		mutableState.update { it.copy(filters = filters, results = MetaListing()) }
		if (filters.isActive) loadResults(page = 1) else resultsJob?.cancel()
	}

	fun search(query: String) = setFilters(mutableState.value.filters.copy(query = query))

	fun clearFilters() = setFilters(DiscoverFilters())

	fun nextResultsPage() {
		val r = mutableState.value.results
		if (r.hasNext && !r.isLoading) loadResults(r.page + 1)
	}

	fun previousResultsPage() {
		val r = mutableState.value.results
		if (r.page > 1 && !r.isLoading) loadResults(r.page - 1)
	}

	private fun loadResults(page: Int) {
		resultsJob?.cancel()
		val snapshot = mutableState.value
		mutableState.update { it.copy(results = it.results.copy(isLoading = true, error = null, page = page)) }
		resultsJob = viewModelScope.launch {
			val listing = fetch(snapshot, snapshot.filters.copy(hideAdult = hideAdult()), page)
			mutableState.update { if (it.filters == snapshot.filters) it.copy(results = listing) else it }
		}
	}

	// ---- suggestions -----------------------------------------------------------------------------

	/** Switches the suggestions to one of the user's genres (null = no genre, the provider's popular list). */
	fun selectForYouGenre(genre: String?) {
		mutableState.update { it.copy(forYouGenre = genre, forYou = MetaListing()) }
		loadForYou(page = 1)
	}

	fun nextForYouPage() {
		val r = mutableState.value.forYou
		if (r.hasNext && !r.isLoading) loadForYou(r.page + 1)
	}

	fun previousForYouPage() {
		val r = mutableState.value.forYou
		if (r.page > 1 && !r.isLoading) loadForYou(r.page - 1)
	}

	private fun loadForYou(page: Int) {
		forYouJob?.cancel()
		val snapshot = mutableState.value
		if (!snapshot.isAvailable) return
		mutableState.update { it.copy(forYou = it.forYou.copy(isLoading = true, error = null, page = page)) }
		// Only Discover is personalised by the user's top genre; Trending / Popular / Upcoming are never narrowed
		// behind the user's back, since the genre strip that used to undo it is gone
		val genres = if (snapshot.feed == ExploreFeed.DISCOVER) setOfNotNull(snapshot.forYouGenre) else emptySet()
		val filters = when (snapshot.feed) {
			ExploreFeed.DISCOVER -> DiscoverFilters(
				include = genres,
				sort = if (snapshot.forYouGenre == null) DiscoverSort.TRENDING else DiscoverSort.POPULAR,
				hideAdult = hideAdult(),
			)

			ExploreFeed.TRENDING -> DiscoverFilters(include = genres, sort = DiscoverSort.TRENDING, hideAdult = hideAdult())
			ExploreFeed.POPULAR -> DiscoverFilters(include = genres, sort = DiscoverSort.POPULAR, hideAdult = hideAdult())
			ExploreFeed.UPCOMING -> DiscoverFilters(
				include = genres,
				sort = DiscoverSort.POPULAR,
				status = "NOT_YET_RELEASED",
				hideAdult = hideAdult(),
			)
		}
		forYouJob = viewModelScope.launch {
			val listing = fetch(snapshot, filters, page)
			mutableState.update {
				if (it.forYouGenre == snapshot.forYouGenre && it.feed == snapshot.feed) it.copy(forYou = listing) else it
			}
		}
	}

	// ---- samples ---------------------------------------------------------------------------------

	/**
	 * A few well-rated titles with a synopsis (and a trailer where the tracker has one) in the user's
	 * favourite genre, as a short hook to start something new.
	 */
	private fun loadSamples(genre: String?) {
		samplesJob?.cancel()
		val snapshot = mutableState.value
		mutableState.update { it.copy(samplesLoading = true) }
		samplesJob = viewModelScope.launch {
			val filters = DiscoverFilters(
				include = setOfNotNull(genre),
				sort = DiscoverSort.SCORE,
				hideAdult = hideAdult(),
			)
			val listing = fetch(snapshot, filters, page = 1, perPage = SAMPLES_FETCH)
			val picked = listing.items
				.filter { !it.description.isNullOrBlank() && it.coverUrl != null }
				.shuffled()
				.take(SAMPLES_SHOWN)
			mutableState.update { it.copy(samples = picked, samplesLoading = false) }
		}
	}

	fun reshuffleSamples() = loadSamples(mutableState.value.forYouGenre)

	// ---- feeds, view mode, quick preferences -----------------------------------------------------

	fun selectFeed(feed: ExploreFeed) {
		if (mutableState.value.feed == feed && mutableState.value.forYou.isLoaded && !mutableState.value.filters.isActive) return
		mutableState.update { it.copy(feed = feed, forYou = MetaListing()) }
		// A feed replaces any search or filter result the page was showing
		if (mutableState.value.filters.isActive) {
			resultsJob?.cancel()
			mutableState.update { it.copy(filters = DiscoverFilters(), results = MetaListing()) }
		}
		loadForYou(page = 1)
	}

	fun setListView(isList: Boolean) {
		settings.isExploreListView = isList
		mutableState.update { it.copy(isListView = isList) }
	}

	fun setDiscoverDefault(value: Boolean) {
		settings.isExploreDiscoverDefault = value
		mutableQuickPrefs.value = readQuickPrefs()
	}

	fun setAutoUpdateExtensions(value: Boolean) {
		settings.isAutoUpdateExtensionsEnabled = value
		mutableQuickPrefs.value = readQuickPrefs()
	}

	fun setHideAdult(value: Boolean) {
		settings.isHomeHideNsfw = value
		mutableQuickPrefs.value = readQuickPrefs()
		reload()
	}

	fun setMetadataFromTracker(value: Boolean) {
		settings.isMetadataFromTracker = value
		mutableQuickPrefs.value = readQuickPrefs()
	}

	fun setProvider(provider: HomeMetadata) {
		if (provider == HomeMetadata.SOURCES) return
		settings.homeMetadata = provider
		reload()
	}

	/** Re-reads the provider, mode and adult filter and loads everything again. */
	private fun reload() {
		started = false
		sync()
		mutableQuickPrefs.value = readQuickPrefs()
	}

	private fun readQuickPrefs(): ExploreQuickPrefs {
		val mode = settings.homeMode
		return ExploreQuickPrefs(
			discoverDefault = settings.isExploreDiscoverDefault,
			autoUpdateExtensions = settings.isAutoUpdateExtensionsEnabled,
			hideAdult = settings.isHomeHideNsfw,
			metadataFromTracker = settings.isMetadataFromTracker,
			provider = settings.homeMetadataProvider,
			providers = HomeMetadata.values().filter { it != HomeMetadata.SOURCES && repository.supports(it, mode) },
		)
	}

	// ---- opening ---------------------------------------------------------------------------------

	fun open(title: MetadataTitle) {
		val provider = settings.homeMetadata
		val mode = settings.homeMode
		val ref = MetaRef(provider, title.id, mode)
		// Info comes from the tracker: show its page now and let that page find the source.
		if (settings.isMetadataFromTracker) {
			onOpen.call(MetaTarget.Details(ref, title.title, title.coverUrl, title.bannerUrl))
			return
		}
		viewModelScope.launch {
			when (val result = opener.resolve(ref, title.names, title.title)) {
				is MetaOpenResult.Open -> onOpen.call(MetaTarget.Library(result.manga))
				is MetaOpenResult.ChooseSource ->
					onOpen.call(MetaTarget.ChooseSource(result.query, mode, MetadataLinks.key(ref)))
			}
		}
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private fun hideAdult() = settings.isNsfwContentDisabled || settings.isHomeHideNsfw

	private suspend fun fetch(
		snapshot: ExploreMetaState,
		filters: DiscoverFilters,
		page: Int,
		perPage: Int = DISCOVER_PAGE_SIZE,
	): MetaListing = try {
		val result = repository.discover(snapshot.provider, snapshot.mode, filters, page, perPage)
		MetaListing(items = result.items, page = page, hasNext = result.hasNext, isLoaded = true)
	} catch (e: kotlinx.coroutines.CancellationException) {
		throw e
	} catch (e: Exception) {
		MetaListing(page = page, error = e.message ?: e.javaClass.simpleName, isLoaded = true)
	}

	private companion object {
		const val SAMPLES_FETCH = 30
		const val SAMPLES_SHOWN = 10
	}
}
