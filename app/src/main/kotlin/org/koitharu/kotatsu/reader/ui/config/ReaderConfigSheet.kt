package org.koitharu.kotatsu.reader.ui.config

import android.os.Bundle
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import android.content.res.Configuration
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.databinding.SheetReaderConfigBinding
import org.koitharu.kotatsu.reader.ui.epub.EpubBundledFonts
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref
import javax.inject.Inject
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import org.koitharu.kotatsu.local.data.isEpub
import androidx.compose.ui.BiasAlignment
import androidx.compose.animation.core.animateFloatAsState
import org.koitharu.kotatsu.core.ui.util.syncedSliderState
import androidx.compose.material3.SheetValue

/** Base body text size the EPUB reader scales its percentages against (see EpubReaderFragment). */
private const val EPUB_BASE_TEXT_SIZE_DP = 16f

@AndroidEntryPoint
class ReaderConfigSheet : BaseAdaptiveSheet<SheetReaderConfigBinding>() {

    private val viewModel by activityViewModels<ReaderViewModel>()

    @Inject
    lateinit var orientationHelper: ScreenOrientationHelper

    @Inject
    lateinit var mangaRepositoryFactory: MangaRepository.Factory

    @Inject
    lateinit var coil: ImageLoader

    private lateinit var mode: ReaderMode
	@Inject
	lateinit var settings: AppSettings

	private var customFontUiRevision by mutableIntStateOf(0)
	private val epubFontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) importEpubFont(uri)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		mode = arguments?.getInt(AppRouter.KEY_READER_MODE)
			?.let { ReaderMode.valueOf(it) }
			?: ReaderMode.STANDARD
	}

	override fun onStart() {
		// When the reader is immersive, this sheet's own window must hide the system bars too —
		// otherwise showing it makes the bars reappear over the activity, which resizes the reader
		// (visible as an abrupt jump). FLAG_NOT_FOCUSABLE while showing defers the focus grab until
		// after the bars are hidden so they never flash in.
		val window = dialog?.window
		val activityInsets = activity?.window?.decorView?.let(ViewCompat::getRootWindowInsets)
		val barsHidden = activityInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
		if (window != null && barsHidden) {
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
			WindowInsetsControllerCompat(window, window.decorView).apply {
				systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
				hide(WindowInsetsCompat.Type.systemBars())
			}
			super.onStart()
			window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
		} else {
			super.onStart()
		}
	}

	private fun importEpubFont(uri: Uri) {
		lifecycleScope.launch {
			val name = withContext(Dispatchers.IO) { copyCompatibleFont(uri) }
			if (name == null) {
				Toast.makeText(requireContext(), R.string.epub_font_invalid, Toast.LENGTH_SHORT).show()
				return@launch
			}
			settings.epubCustomFontName = name
			settings.epubCustomFontRevision++
			settings.epubFontFamily = EPUB_FONT_CUSTOM
			customFontUiRevision++
		}
	}

	private fun copyCompatibleFont(uri: Uri): String? {
		val context = requireContext()
		val resolver = context.contentResolver
		val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		}?.takeIf(String::isNotBlank) ?: getString(R.string.epub_font_custom)
		val temporary = File(context.cacheDir, "epub_font_import")
		return try {
			resolver.openInputStream(uri)?.use { input -> temporary.outputStream().use(input::copyTo) } ?: return null
			Typeface.createFromFile(temporary)
			temporary.copyTo(File(context.filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE), overwrite = true)
			displayName
		} catch (_: Exception) {
			null
		} finally {
			temporary.delete()
		}
	}

	private fun removeEpubCustomFont() {
		File(requireContext().filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE).delete()
		settings.epubCustomFontName = ""
		settings.epubCustomFontRevision++
		if (settings.epubFontFamily == EPUB_FONT_CUSTOM) settings.epubFontFamily = "serif"
		customFontUiRevision++
	}

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetReaderConfigBinding {
        return SheetReaderConfigBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(
        binding: SheetReaderConfigBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeView.setContent {
            DropSauceTheme {
                ReaderConfigContent()
            }
        }
        // Expand synchronously, before the enter animation starts — a post{} here caused a second
        // settle (the sheet visibly lifted off and dropped back) because the expanded offset was
        // computed one frame after the animation had already begun.
        expandToContent()
    }

    private fun expandToContent() {
        val b = behavior ?: return
        if (b is AdaptiveSheetBehavior.Bottom) {
            if (isLandscape()) {
                b.isFitToContents = false
                val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (sheet != null) {
                    sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                b.isFitToContents = true
            }
        } else if (b is AdaptiveSheetBehavior.Side) {
            val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: dialog?.findViewById<View>(com.google.android.material.R.id.m3_side_sheet)
            if (sheet != null) {
                val displayWidth = resources.displayMetrics.widthPixels
                sheet.layoutParams.width = (displayWidth * 0.5).toInt()
                sheet.requestLayout()
            }
        }
        b.state = AdaptiveSheetBehavior.STATE_EXPANDED
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        return insets
    }

    @Composable
    private fun ReaderConfigContent() {
        val context = LocalContext.current
        var currentMode by remember { mutableStateOf(mode) }

        // 0: Reading, 1: More, 2: Extra
        val pagerState = rememberPagerState(pageCount = { 3 })
        val scope = rememberCoroutineScope()

        var isDoubleOnLandscape by remember { mutableStateOf(settings.isReaderDoubleOnLandscape) }
        var isDoubleOnFoldable by remember { mutableStateOf(settings.isReaderDoubleOnFoldable) }
        var sensitivity by remember { mutableFloatStateOf(settings.readerDoublePagesSensitivity * 100f) }

        // Preference-backed switches. The same keys as the full reader settings, so both stay in sync.
        var readerCrop by rememberStringSetPref(AppSettings.KEY_READER_CROP, emptySet())
        var webtoonGaps by rememberBooleanPref(AppSettings.KEY_WEBTOON_GAPS, false)
        var keepScreenOn by rememberBooleanPref(AppSettings.KEY_READER_SCREEN_ON, true)
        var pullGesture by rememberBooleanPref(AppSettings.KEY_WEBTOON_PULL_GESTURE, false)
        var longPress by rememberBooleanPref(AppSettings.KEY_READER_LONG_PRESS, true)
        var enhancedColors by rememberBooleanPref(AppSettings.KEY_32BIT_COLOR, false)
        var upscale by rememberBooleanPref(AppSettings.KEY_READER_UPSCALE, false)
        var optimize by rememberBooleanPref(AppSettings.KEY_READER_OPTIMIZE, false)
        var rotateWide by rememberBooleanPref(AppSettings.KEY_READER_ROTATE_WIDE, false)

        // StateFlow observations from ViewModel and OrientationHelper flow
        val isBookmarkAdded by viewModel.isBookmarkAdded.collectAsState()
        val isAutoRotationEnabled by orientationHelper.observeAutoOrientation().collectAsState(initial = false)

        val callback = remember { findParentCallback(Callback::class.java) }

        // Capture the nav bar inset once: the reader toggles system bars while the sheet is open,
        // and a live navigationBarsPadding() resize makes the shown sheet jump.
        val navBarPadding = remember {
            dialog?.window?.decorView?.let { decor ->
                ViewCompat.getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            } ?: 0
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp + with(LocalDensity.current) { navBarPadding.toDp() }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The pager is pinned to the height of the Reading page so swiping between tabs never
                // resizes the sheet; the other two pages scroll inside it.
                val density = LocalDensity.current
                var pagerHeightPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (pagerHeightPx > 0) {
                                Modifier.height(with(density) { pagerHeightPx.toDp() })
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        when (page) {
                            0 -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .onSizeChanged { pagerHeightPx = it.height },
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ReadModeSection(
                                    selectedMode = currentMode,
                                    onModeSelected = { newMode ->
                                        if (newMode != currentMode) {
                                            callback?.onReaderModeChanged(newMode)
                                            currentMode = newMode
                                        }
                                    },
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        QsActionCard(
                                            icon = R.drawable.ic_timer,
                                            title = stringResource(R.string.automatic_scroll),
                                            subtitle = stringResource(R.string.qs_automatic_scroll_sub),
                                            onClick = {
                                                callback?.onScrollTimerClick(false)
                                                dismissAllowingStateLoss()
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        QsActionCard(
                                            icon = R.drawable.ic_save,
                                            title = stringResource(R.string.save_page),
                                            subtitle = stringResource(R.string.qs_save_page_sub),
                                            onClick = {
                                                callback?.onSavePageClick()
                                                dismissAllowingStateLoss()
                                            },
                                            trailingIcon = R.drawable.ic_save_alt,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        QsActionCard(
                                            icon = if (isAutoRotationEnabled) R.drawable.ic_screen_rotation else R.drawable.ic_screen_rotation_lock,
                                            title = stringResource(R.string.rotate_screen),
                                            subtitle = stringResource(
                                                if (isAutoRotationEnabled) R.string.qs_rotation_auto_sub else R.string.qs_rotation_locked_sub,
                                            ),
                                            onClick = { orientationHelper.toggleScreenOrientation() },
                                            modifier = Modifier.weight(1f),
                                        )
                                        QsActionCard(
                                            icon = R.drawable.ic_appearance,
                                            title = stringResource(R.string.color_correction),
                                            subtitle = stringResource(R.string.qs_color_sub),
                                            onClick = {
                                                val pageModel = viewModel.getCurrentPage()
                                                val manga = viewModel.getMangaOrNull()
                                                if (pageModel != null && manga != null) {
                                                    router.openColorFilterConfig(manga, pageModel)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    QsBannerCard(
                                        icon = R.drawable.ic_settings,
                                        title = stringResource(R.string.qs_advanced_title),
                                        subtitle = stringResource(R.string.qs_advanced_sub),
                                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                                    )
                                }
                                Spacer(modifier = Modifier.height(70.dp))
                            }

                            1 -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                QsSectionLabel(stringResource(R.string.qs_section_general))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    QsToggleRow(
                                        icon = R.drawable.ic_crop,
                                        title = stringResource(R.string.qs_crop_borders),
                                        subtitle = stringResource(R.string.qs_crop_borders_sub),
                                        checked = readerCrop.isNotEmpty(),
                                        onCheckedChange = { readerCrop = if (it) setOf("1", "2") else emptySet() },
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_gesture_vertical,
                                        title = stringResource(R.string.qs_spaced_pages),
                                        subtitle = stringResource(R.string.qs_spaced_pages_sub),
                                        checked = webtoonGaps,
                                        onCheckedChange = { webtoonGaps = it },
                                        enabled = currentMode == ReaderMode.WEBTOON,
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_tune,
                                        title = stringResource(R.string.keep_screen_on),
                                        subtitle = stringResource(R.string.qs_keep_screen_on_sub),
                                        checked = keepScreenOn,
                                        onCheckedChange = { keepScreenOn = it },
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_swap,
                                        title = stringResource(R.string.qs_overscroll),
                                        subtitle = stringResource(R.string.qs_overscroll_sub),
                                        checked = pullGesture,
                                        onCheckedChange = { pullGesture = it },
                                        enabled = currentMode == ReaderMode.WEBTOON,
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_tap,
                                        title = stringResource(R.string.qs_long_press),
                                        subtitle = stringResource(R.string.qs_long_press_sub),
                                        checked = longPress,
                                        onCheckedChange = { longPress = it },
                                    )
                                }
                                DoublePageConfigSection(
                                    isModeStandardOrReversed = currentMode == ReaderMode.STANDARD || currentMode == ReaderMode.REVERSED,
                                    isDoubleOnLandscape = isDoubleOnLandscape,
                                    onDoubleOnLandscapeChange = { enabled ->
                                        settings.isReaderDoubleOnLandscape = enabled
                                        isDoubleOnLandscape = enabled
                                        callback?.onDoubleModeChanged(enabled)
                                    },
                                    isDoubleOnFoldable = isDoubleOnFoldable,
                                    onDoubleOnFoldableChange = { enabled ->
                                        settings.isReaderDoubleOnFoldable = enabled
                                        isDoubleOnFoldable = enabled
                                        callback?.onDoubleModeChanged(settings.isReaderDoubleOnLandscape)
                                    },
                                    sensitivity = sensitivity,
                                    onSensitivityChange = { value ->
                                        settings.readerDoublePagesSensitivity = value / 100f
                                        sensitivity = value
                                    },
                                )
                                QsSectionLabel(stringResource(R.string.qs_section_actions))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    QsActionRow(
                                        icon = if (isBookmarkAdded) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark,
                                        title = stringResource(if (isBookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add),
                                        onClick = { viewModel.toggleBookmark() },
                                    )
                                    QsActionRow(
                                        icon = R.drawable.ic_share,
                                        title = stringResource(R.string.share_page),
                                        onClick = {
                                            callback?.onSharePageClick()
                                            dismissAllowingStateLoss()
                                        },
                                    )
                                    QsActionRow(
                                        icon = R.drawable.ic_settings,
                                        title = stringResource(R.string.qs_all_settings),
                                        onClick = {
                                            router.openReaderSettings()
                                            dismissAllowingStateLoss()
                                        },
                                    )
                                }
                                Spacer(modifier = Modifier.height(70.dp))
                            }

                            else -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                QsSectionLabel(stringResource(R.string.qs_section_image))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    QsToggleRow(
                                        icon = R.drawable.ic_appearance,
                                        title = stringResource(R.string.enhanced_colors),
                                        subtitle = stringResource(R.string.qs_32bit_sub),
                                        checked = enhancedColors,
                                        onCheckedChange = { enhancedColors = it },
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_bolt,
                                        title = stringResource(R.string.qs_upscale),
                                        subtitle = stringResource(R.string.qs_upscale_sub),
                                        checked = upscale,
                                        onCheckedChange = { upscale = it },
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_data_saver,
                                        title = stringResource(R.string.reader_optimize),
                                        subtitle = stringResource(R.string.qs_memory_sub),
                                        checked = optimize,
                                        onCheckedChange = { optimize = it },
                                    )
                                    QsToggleRow(
                                        icon = R.drawable.ic_rotate_90_degrees_cw,
                                        title = stringResource(R.string.qs_rotate_wide),
                                        subtitle = stringResource(R.string.qs_rotate_wide_sub),
                                        checked = rotateWide,
                                        onCheckedChange = { rotateWide = it },
                                    )
                                }
                                Spacer(modifier = Modifier.height(70.dp))
                            }
                        }
                    }
                }
            }

            // 2. Bottom Floating Capsule Tab Switcher
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                BottomPillTabBar(
                    currentPage = pagerState.currentPage,
                    onTabClick = { index ->
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun BottomPillTabBar(
        currentPage: Int,
        onTabClick: (Int) -> Unit,
        tabs: List<Triple<String, Int, Int>> = listOf(
            Triple(stringResource(R.string.epub_reading_tab), R.drawable.ic_book_page, 0),
            Triple(stringResource(R.string.more), R.drawable.ic_grid, 1),
            Triple(stringResource(R.string.qs_extra_tab), R.drawable.ic_tune, 2),
        ),
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .widthIn(max = if (tabs.size > 2) 336.dp else 280.dp)
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val targetBias = if (tabs.size == 1) 0f else currentPage * 2f / (tabs.size - 1) - 1f
                    val animatedBias by animateFloatAsState(
                        targetValue = targetBias,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "pill_bias",
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / tabs.size)
                            .padding(4.dp)
                            .align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { (title, iconRes, index) ->
                            val isSelected = currentPage == index
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "pill_tab_content_color",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { onTabClick(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = title,
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Floor for the More page's tool row: below it the cards would clip their two-line labels. */
    private val TOOL_ROW_MIN_HEIGHT = 96.dp

    /** Reading page: the actions used mid-chapter, as a 2x2 grid that fills the height it is given. */
    @Composable
    private fun ReadingToolsSection(
        isBookmarkAdded: Boolean,
        onScrollTimerClick: () -> Unit,
        onSaveClick: () -> Unit,
        onBookmarkClick: () -> Unit,
        onSettingsClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolGridCard(
                    icon = R.drawable.ic_timer,
                    label = stringResource(R.string.automatic_scroll),
                    onClick = onScrollTimerClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                ToolGridCard(
                    icon = R.drawable.ic_save,
                    label = stringResource(R.string.save_page),
                    onClick = onSaveClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            // Same pair as before the reorganisation: wide Settings card, round icon-only bookmark.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolWideCard(
                    icon = R.drawable.ic_settings,
                    title = stringResource(R.string.settings),
                    subtitle = stringResource(R.string.reader_settings_summary),
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                ToolGridCard(
                    icon = if (isBookmarkAdded) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark,
                    label = null,
                    checked = isBookmarkAdded,
                    onClick = onBookmarkClick,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    shape = CircleShape,
                    iconSize = 42.dp,
                )
            }
        }
    }

    /** More page: one row of the less frequent actions, above the two-page card. */
    @Composable
    private fun MoreToolsRow(
        isAutoRotationEnabled: Boolean,
        onShareClick: () -> Unit,
        onOrientationClick: () -> Unit,
        onColorFilterClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val itemModifier = Modifier
                .weight(1f)
                .fillMaxHeight()
            ToolPillCard(
                icon = R.drawable.ic_share,
                label = stringResource(R.string.share_page),
                onClick = onShareClick,
                modifier = itemModifier,
            )
            ToolPillCard(
                icon = if (isAutoRotationEnabled) R.drawable.ic_screen_rotation_lock else R.drawable.ic_screen_rotation,
                label = stringResource(if (isAutoRotationEnabled) R.string.lock_screen_rotation else R.string.rotate_screen),
                onClick = onOrientationClick,
                modifier = itemModifier,
            )
            ToolPillCard(
                icon = R.drawable.ic_appearance,
                label = stringResource(R.string.color_correction),
                onClick = onColorFilterClick,
                modifier = itemModifier,
            )
        }
    }

    @Composable
    private fun ToolWideCard(
        icon: Int,
        title: String,
        subtitle: String,
        checked: Boolean = false,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val containerColor = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        val iconColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = iconColor,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(-90f),
                    tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }


    @Composable
    private fun ToolGridCard(
        icon: Int,
        label: String?,
        checked: Boolean = false,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
        iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    ) {
        val containerColor = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        val iconColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onClick,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    tint = iconColor,
                )
                if (label != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun ToolPillCard(
        icon: Int,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(48.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = label,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

	private companion object {
		const val EPUB_FONT_CUSTOM = "custom"
		const val EPUB_THEME_CUSTOM = "custom"
		const val EPUB_THEME_SYSTEM = "system"
		const val EPUB_COLOR_BACKGROUND = "background"
		const val EPUB_COLOR_TEXT = "text"
		const val EPUB_COLOR_HIGHLIGHT = "highlight"
		val EPUB_COLOR_PRESETS = listOf(
			0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), 0xFF9E9E9E.toInt(), 0xFF424242.toInt(), 0xFF121212.toInt(), 0xFF6D4C41.toInt(),
			0xFFE53935.toInt(), 0xFFF4511E.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFFC0CA33.toInt(), 0xFF43A047.toInt(),
			0xFF00A86B.toInt(), 0xFF00897B.toInt(), 0xFF00ACC1.toInt(), 0xFF039BE5.toInt(), 0xFF1E88E5.toInt(), 0xFF3949AB.toInt(),
			0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFFE91E63.toInt(), 0xFFFF7043.toInt(), 0xFFC99A00.toInt(),
		)
		val EPUB_FONT_MIME_TYPES = arrayOf(
			"font/ttf",
			"font/otf",
			"application/x-font-ttf",
			"application/x-font-opentype",
			"application/octet-stream",
		)
	}

    interface Callback {

        fun onReaderModeChanged(mode: ReaderMode)

        fun onDoubleModeChanged(isEnabled: Boolean)

        fun onSavePageClick()

        fun onSharePageClick()

        fun onScrollTimerClick(isLongClick: Boolean)

        fun onTextToSpeechClick()

        fun onBookmarkClick()

    }
}
