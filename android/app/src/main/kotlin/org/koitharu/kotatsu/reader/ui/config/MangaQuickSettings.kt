package org.koitharu.kotatsu.reader.ui.config

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
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref
import kotlin.math.roundToInt
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.BiasAlignment
import androidx.compose.animation.core.animateFloatAsState
import org.koitharu.kotatsu.core.ui.util.syncedSliderState
import androidx.compose.material3.SheetValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The manga reader's quick settings, shown inline above the bottom bar (like the novel reader's
 * panel) instead of in a modal sheet: the bar stays visible, its Settings button turns into a close
 * button, and the panel slides in and out. Being a plain panel, nothing fights the pages for
 * vertical drags any more, so the More and Extra pages scroll back to the top properly.
 */
@Composable
internal fun MangaQuickSettings(
    visible: Boolean,
    mode: ReaderMode,
    settings: AppSettings,
    viewModel: ReaderViewModel,
    orientationHelper: ScreenOrientationHelper,
    callback: ReaderConfigSheet.Callback?,
    router: AppRouter,
    onDismiss: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(260)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
        ) {
            MangaQuickSettingsContent(mode, settings, viewModel, orientationHelper, callback, router, onDismiss)
        }
    }
}

@Composable
private fun MangaQuickSettingsContent(
    mode: ReaderMode,
    settings: AppSettings,
    viewModel: ReaderViewModel,
    orientationHelper: ScreenOrientationHelper,
    callback: ReaderConfigSheet.Callback?,
    router: AppRouter,
    onDismiss: () -> Unit,
) {
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
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
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    QsActionCard(
                                        icon = R.drawable.ic_timer,
                                        title = stringResource(R.string.automatic_scroll),
                                        subtitle = stringResource(R.string.qs_automatic_scroll_sub),
                                        onClick = {
                                            callback?.onScrollTimerClick(false)
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    QsActionCard(
                                        icon = R.drawable.ic_save,
                                        title = stringResource(R.string.save_page),
                                        subtitle = stringResource(R.string.qs_save_page_sub),
                                        onClick = {
                                            callback?.onSavePageClick()
                                            onDismiss()
                                        },
                                        trailingIcon = R.drawable.ic_save_alt,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                                        onDismiss()
                                    },
                                )
                                QsActionRow(
                                    icon = R.drawable.ic_settings,
                                    title = stringResource(R.string.qs_all_settings),
                                    onClick = {
                                        router.openReaderSettings()
                                        onDismiss()
                                    },
                                )
                            }
                            Spacer(modifier = Modifier.height(70.dp))
                        }

                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            MangaPillTabBar(
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
private fun MangaPillTabBar(
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
