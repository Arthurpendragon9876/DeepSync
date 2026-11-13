package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ActivityDetailsExpressiveBinding
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.metadata.MetaOpenResult
import org.koitharu.kotatsu.metadata.MetaRef
import org.koitharu.kotatsu.metadata.MetadataLinks
import org.koitharu.kotatsu.metadata.MetadataOpener
import org.koitharu.kotatsu.metadata.MetadataRepository
import org.koitharu.kotatsu.metadata.SourceChooserActivity
import org.koitharu.kotatsu.metadata.toVirtualManga
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import javax.inject.Inject

/**
 * A tracker title's details page, shown the moment it is tapped. Cover, synopsis and every info card come from
 * the tracker, which answers fast, so nothing waits for the sources. While it is on screen the source that has
 * the chapters or episodes is searched; once found, the real details page replaces this one (same look, so it
 * reads as the page filling in) with the tracker data already remembered for it.
 */
@AndroidEntryPoint
class MetaDetailsActivity : BaseActivity<ActivityDetailsExpressiveBinding>() {

	@Inject lateinit var coil: ImageLoader
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var repository: MetadataRepository
	@Inject lateinit var opener: MetadataOpener

	private val topInset = mutableIntStateOf(0)
	private val bottomInset = mutableIntStateOf(0)

	private sealed interface SourceState {
		data object Searching : SourceState
		data class Missing(val query: String) : SourceState
	}

	private val sourceState = MutableStateFlow<SourceState>(SourceState.Searching)
	private val details = MutableStateFlow<MangaDetails?>(null)
	private val info = MutableStateFlow<TrackerInfoState>(TrackerInfoState.Loading)

	private lateinit var ref: MetaRef

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsExpressiveBinding.inflate(layoutInflater))
		val provider = runCatching { HomeMetadata.valueOf(intent.getStringExtra(EXTRA_PROVIDER).orEmpty()) }.getOrNull()
		val mode = runCatching { HomeMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }.getOrNull()
		val id = intent.getLongExtra(EXTRA_ID, 0L)
		if (provider == null || mode == null || id == 0L) {
			finish()
			return
		}
		ref = MetaRef(provider, id, mode)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		val dark = ColorUtils.calculateLuminance(getThemeColor(android.R.attr.colorBackground)) <= 0.5
		WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)
		viewBinding.swipeRefreshLayout.isEnabled = false

		// What the list already knew, so the cover and title are there on the first frame.
		val seed = MetadataTitle(
			id = id,
			title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
			names = listOfNotNull(intent.getStringExtra(EXTRA_TITLE)),
			coverUrl = intent.getStringExtra(EXTRA_COVER),
			bannerUrl = intent.getStringExtra(EXTRA_BANNER),
			genres = emptyList(),
			isAdult = false,
			score = null,
			url = "",
		)
		details.value = seed.asDetails()
		setupContent()
		load(seed)
	}

	private fun MetadataTitle.asDetails() = MangaDetails(toVirtualManga(ref.provider, ref.mode)).withMetadata(this)

	private fun load(seed: MetadataTitle) {
		lifecycleScope.launch {
			// One quick request for the full entry (names, genres, synopsis, score).
			val full = runCatching { repository.titleById(ref.provider, ref.id) }.getOrNull() ?: seed
			details.value = full.asDetails()
			// The cards and the source search run side by side; neither waits for the other.
			val cards = async { info.value = TrackerInfoState.Loaded(repository.info(ref.provider, full)) }
			val result = runCatching { opener.resolve(ref, full.names, full.title) }.getOrNull()
			when (result) {
				is MetaOpenResult.Open -> {
					// The real page starts with this tracker entry already matched, so it needs no second search.
					repository.remember(result.manga, ref.provider, full)
					AppRouter(this@MetaDetailsActivity).openDetails(result.manga)
					@Suppress("DEPRECATION")
					overridePendingTransition(0, 0)
					finish()
				}

				is MetaOpenResult.ChooseSource -> sourceState.value = SourceState.Missing(result.query)
				null -> sourceState.value = SourceState.Missing(full.title)
			}
			cards.await()
		}
	}

	private fun setupContent() {
		val finding = { Toast.makeText(this, R.string.ds_info_finding_source, Toast.LENGTH_SHORT).show() }
		val actions = DetailsExpressiveActions(
			onCoverClick = {},
			onTitleClick = {},
			onSourceClick = { finding() },
			onLocalClick = {},
			onFavoriteClick = { finding() },
			onMigrateClick = { finding() },
			onAuthorClick = {},
			onTagClick = {},
			onScrobblingMore = {},
			onScrobblingCardClick = {},
			onRelatedMore = {},
			onRelatedClick = {},
			onReadClick = {},
			onIncognitoClick = {},
			onForgetHistoryClick = {},
			onChaptersClick = {},
			onInfoGenreClick = { name -> AppRouter(this).openSearch(name) },
			onInfoMediaClick = { media -> AppRouter(this).openTrackerTitle(media, null) },
		)
		viewBinding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		viewBinding.composeView.setContent {
			org.koitharu.kotatsu.settings.compose.DropSauceTheme {
				val density = androidx.compose.ui.platform.LocalDensity.current
				val d by details.collectAsState()
				val cards by info.collectAsState()
				val state by sourceState.collectAsState()
				val empty = remember { HistoryInfo(null, null, null, false, null) }
				DetailsExpressiveScreen(
					details = d,
					tags = emptyList(),
					historyInfo = empty,
					isLoading = true,
					favouriteCount = 0,
					favouriteLabel = null,
					scrobblings = emptyList(),
					related = emptyList(),
					localSize = 0L,
					sourceTitle = stringResource(R.string.ds_info_finding_source),
					imageLoader = coil,
					coverUrl = d?.coverUrl,
					coverRefreshes = 0,
					backdropUrl = d?.backdropUrl,
					isBackdropEnabled = true,
					backdropBlurAmount = 2,
					style = settings.detailsUiMode,
					topInset = with(density) { topInset.intValue.toDp() },
					bottomContentPadding = with(density) { bottomInset.intValue.toDp() },
					onScroll = {},
					actions = actions,
					trackerInfo = cards,
					allowTracking = false,
					dockOverride = { SourceStatusPill(state) },
				)
			}
		}
	}

	@androidx.compose.runtime.Composable
	private fun SourceStatusPill(state: SourceState) {
		val missing = state as? SourceState.Missing
		Surface(
			shape = RoundedCornerShape(50),
			color = MaterialTheme.colorScheme.primaryContainer,
			shadowElevation = 4.dp,
			modifier = if (missing != null) {
				Modifier.clickable {
					startActivity(SourceChooserActivity.intent(this, missing.query, ref.mode, MetadataLinks.key(ref)))
				}
			} else {
				Modifier
			},
		) {
			Row(
				modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				if (missing == null) {
					CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
				}
				Text(
					text = stringResource(
						if (missing == null) R.string.ds_info_finding_source else R.string.ds_info_choose_source,
					),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		topInset.intValue = bars.top
		bottomInset.intValue = bars.bottom
		viewBinding.appbar.updatePadding(top = bars.top)
		return insets
	}

	companion object {

		private const val EXTRA_PROVIDER = "meta_provider"
		private const val EXTRA_ID = "meta_id"
		private const val EXTRA_MODE = "meta_mode"
		private const val EXTRA_TITLE = "meta_title"
		private const val EXTRA_COVER = "meta_cover"
		private const val EXTRA_BANNER = "meta_banner"

		fun intent(context: Context, ref: MetaRef, title: String, coverUrl: String?, bannerUrl: String?): Intent =
			Intent(context, MetaDetailsActivity::class.java)
				.putExtra(EXTRA_PROVIDER, ref.provider.name)
				.putExtra(EXTRA_ID, ref.id)
				.putExtra(EXTRA_MODE, ref.mode.name)
				.putExtra(EXTRA_TITLE, title)
				.putExtra(EXTRA_COVER, coverUrl)
				.putExtra(EXTRA_BANNER, bannerUrl)
	}
}
