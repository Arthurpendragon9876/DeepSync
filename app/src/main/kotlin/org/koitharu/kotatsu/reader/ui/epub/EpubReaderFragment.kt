package org.koitharu.kotatsu.reader.ui.epub

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Layout
import android.text.NoCopySpan
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UpdateAppearance
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.bookmarks.domain.epubHighlight
import org.koitharu.kotatsu.bookmarks.domain.epubHighlightUrl
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.lnreader.model.absoluteUrl
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getDisplayIcon
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.isNightMode
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.databinding.FragmentReaderEpubBinding
import org.koitharu.kotatsu.databinding.ItemErrorStateBinding
import org.koitharu.kotatsu.databinding.SheetEpubDictionaryBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.tts.ReaderTts
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.Closeable
import java.io.File
import java.net.URI
import java.text.BreakIterator
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.ceil
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Native EPUB flow. Chapters are virtualized by RecyclerView in vertical mode and converted to a
 * flat, chapter-spanning ViewPager2 page list in paged mode. Both modes navigate with the same
 * chapter/character locator, so changing modes never reloads a chapter or loses the position.
 */
@AndroidEntryPoint
class EpubReaderFragment : BaseReaderFragment<FragmentReaderEpubBinding>() {

	@Inject
	lateinit var settings: AppSettings
	@Inject
	lateinit var bookmarksRepository: BookmarksRepository
	@Inject
	@BaseHttpClient
	lateinit var httpClient: OkHttpClient
	@Inject
	lateinit var imageLoader: ImageLoader

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	@Inject
	lateinit var tts: ReaderTts

	private var chapters: List<NativeChapter> = emptyList()
	private val chapterDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val chapterDividerDecoration = object : RecyclerView.ItemDecoration() {
		override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
			chapterDividerPaint.strokeWidth = resources.displayMetrics.density
			for (index in 0 until parent.childCount) {
				val child = parent.getChildAt(index)
				if (parent.getChildAdapterPosition(child) !in 0 until chapters.lastIndex) continue
				val y = child.bottom + child.translationY
				canvas.drawLine(0f, y, parent.width.toFloat(), y, chapterDividerPaint)
			}
		}
	}

	private val isOverscrollStyle: Boolean
		get() = settings.epubScrollStyle == "overscroll"

	private val transitionCardHeightPx: Int get() = (TRANSITION_CARD_HEIGHT_DP * resources.displayMetrics.density).toInt()
	private val transitionMarginPx: Int get() = (TRANSITION_MARGIN_DP * resources.displayMetrics.density).toInt()

	/** Space under a chapter that holds the transition card (and, in Overscroll, the rest of a screen). */
	private fun transitionBlockPx() = transitionCardHeightPx + transitionMarginPx * 2

	private fun transitionOffsetPx(parentHeight: Int): Int =
		if (isOverscrollStyle) maxOf(parentHeight, transitionBlockPx()) else transitionBlockPx()

	private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val cardLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
	private val cardTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
	private val cardIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val cardRect = RectF()
	private val cardPath = Path()
	private val cardTitleLayouts = HashMap<String, StaticLayout>()
	private var cardLayoutsKey = ""

	/**
	 * Replaces the thin line between chapters: a "Finished / Next chapter" card. In Continuous it is simply
	 * what you scroll past; in Overscroll it is what you reach at the end of a chapter before pulling on.
	 */
	private val chapterTransitionDecoration = object : RecyclerView.ItemDecoration() {
		override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
			if (parent.getChildAdapterPosition(view) in 0 until chapters.lastIndex) {
				outRect.bottom = transitionOffsetPx(parent.height)
			}
		}

		override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
			val density = resources.displayMetrics.density
			fun dp(v: Float) = v * density
			val fg = foregroundColor
			val accent = requireContext().getThemeColor(appcompatR.attr.colorPrimary, Color.GRAY)
			val key = "${parent.width}:$fg:${readerTypeface.hashCode()}"
			if (key != cardLayoutsKey) {
				cardLayoutsKey = key
				cardTitleLayouts.clear()
			}
			cardLabelPaint.apply {
				color = ColorUtils.setAlphaComponent(fg, 170)
				textSize = dp(11f)
				typeface = Typeface.create(readerTypeface, Typeface.BOLD)
				letterSpacing = 0.06f
			}
			cardTitlePaint.apply {
				color = fg
				textSize = dp(18f)
				typeface = Typeface.create(readerTypeface, Typeface.BOLD)
			}
			val outerLeft = dp(16f)
			val outerRight = parent.width - dp(16f)
			val inner = dp(16f)
			val panelH = dp(92f)
			val gapH = dp(40f)
			for (index in 0 until parent.childCount) {
				val child = parent.getChildAt(index)
				val position = parent.getChildAdapterPosition(child)
				if (position !in 0 until chapters.lastIndex) continue
				val top = child.bottom + child.translationY + transitionMarginPx
				// outer container
				cardRect.set(outerLeft, top, outerRight, top + transitionCardHeightPx)
				cardFillPaint.color = ColorUtils.setAlphaComponent(fg, 14)
				canvas.drawRoundRect(cardRect, dp(28f), dp(28f), cardFillPaint)
				cardStrokePaint.apply { color = ColorUtils.setAlphaComponent(fg, 36); strokeWidth = density }
				canvas.drawRoundRect(cardRect, dp(28f), dp(28f), cardStrokePaint)
				val p1Top = top + inner
				drawTransitionPanel(
					canvas, outerLeft + inner, p1Top, outerRight - inner, p1Top + panelH,
					label = getString(R.string.epub_transition_finished),
					title = chapterTitle(position),
					cacheKey = "f$position",
					highlighted = false, check = true, fg = fg, accent = accent,
				)
				// chevrons between the panels
				val midX = parent.width / 2f
				val midY = p1Top + panelH + gapH / 2f
				cardIconPaint.apply { color = ColorUtils.setAlphaComponent(fg, 150); strokeWidth = dp(2f) }
				for (i in 0..1) {
					val y = midY - dp(7f) + i * dp(8f)
					cardPath.reset()
					cardPath.moveTo(midX - dp(7f), y - dp(3f))
					cardPath.lineTo(midX, y + dp(3f))
					cardPath.lineTo(midX + dp(7f), y - dp(3f))
					canvas.drawPath(cardPath, cardIconPaint)
				}
				val p2Top = p1Top + panelH + gapH
				drawTransitionPanel(
					canvas, outerLeft + inner, p2Top, outerRight - inner, p2Top + panelH,
					label = getString(R.string.epub_transition_next),
					title = chapterTitle(position + 1),
					cacheKey = "n${position + 1}",
					highlighted = true, check = false, fg = fg, accent = accent,
				)
			}
		}
	}

	private fun chapterTitle(index: Int): String =
		chapters.getOrNull(index)?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.epub_chapter_header_label, index + 1)

	private fun drawTransitionPanel(
		canvas: Canvas,
		left: Float,
		top: Float,
		right: Float,
		bottom: Float,
		label: String,
		title: String,
		cacheKey: String,
		highlighted: Boolean,
		check: Boolean,
		fg: Int,
		accent: Int,
	) {
		val density = resources.displayMetrics.density
		fun dp(v: Float) = v * density
		cardRect.set(left, top, right, bottom)
		cardFillPaint.color = if (highlighted) ColorUtils.setAlphaComponent(accent, 40) else ColorUtils.setAlphaComponent(fg, 14)
		canvas.drawRoundRect(cardRect, dp(22f), dp(22f), cardFillPaint)
		cardStrokePaint.apply {
			color = if (highlighted) ColorUtils.setAlphaComponent(accent, 150) else ColorUtils.setAlphaComponent(fg, 40)
			strokeWidth = dp(1.5f)
		}
		canvas.drawRoundRect(cardRect, dp(22f), dp(22f), cardStrokePaint)
		// round icon on the left
		val cx = left + dp(16f) + dp(24f)
		val cy = (top + bottom) / 2f
		cardFillPaint.color = if (highlighted) ColorUtils.setAlphaComponent(accent, 70) else ColorUtils.setAlphaComponent(fg, 24)
		canvas.drawCircle(cx, cy, dp(24f), cardFillPaint)
		cardIconPaint.apply { color = if (highlighted) accent else ColorUtils.setAlphaComponent(fg, 220); strokeWidth = dp(2.2f) }
		cardPath.reset()
		if (check) {
			cardPath.moveTo(cx - dp(8f), cy + dp(0.5f))
			cardPath.lineTo(cx - dp(2.5f), cy + dp(6f))
			cardPath.lineTo(cx + dp(8f), cy - dp(5f))
		} else {
			cardPath.moveTo(cx - dp(7f), cy - dp(3f))
			cardPath.lineTo(cx, cy + dp(4f))
			cardPath.lineTo(cx + dp(7f), cy - dp(3f))
		}
		canvas.drawPath(cardPath, cardIconPaint)
		// label + title
		val textLeft = cx + dp(24f) + dp(16f)
		val textWidth = (right - dp(16f) - textLeft).toInt().coerceAtLeast(1)
		cardLabelPaint.color = if (highlighted) accent else ColorUtils.setAlphaComponent(fg, 170)
		val labelBaseline = top + dp(24f)
		canvas.drawText(label.uppercase(), textLeft, labelBaseline, cardLabelPaint)
		val layout = cardTitleLayouts.getOrPut("$cacheKey:$textWidth") {
			StaticLayout.Builder.obtain(title, 0, title.length, cardTitlePaint, textWidth)
				.setMaxLines(2)
				.setEllipsize(TextUtils.TruncateAt.END)
				.build()
		}
		canvas.save()
		canvas.translate(textLeft, labelBaseline + dp(8f))
		layout.draw(canvas)
		canvas.restore()
	}

	// ---- Overscroll scroll style: pull past the end of a chapter to open the next one ----
	private var chapterPill: ChapterPullPillView? = null
	private var pullPx = 0f
	private var pullDirection = 0
	private var pullArmed = false

	/** Called by the layout manager with the scroll it refused at a chapter edge. */
	private fun onChapterEdgePull(manager: LinearLayoutManager, blocked: Int, applied: Int) {
		val recycler = verticalView ?: return
		if (blocked == 0) {
			// moving back inside the chapter eases the pull off again
			if (pullPx > 0f && applied != 0) {
				pullPx = (pullPx - kotlin.math.abs(applied)).coerceAtLeast(0f)
				updatePill(manager)
			}
			return
		}
		if (recycler.scrollState != RecyclerView.SCROLL_STATE_DRAGGING) return
		val direction = if (blocked > 0) 1 else -1
		if (direction != pullDirection) {
			pullPx = 0f
			pullArmed = false
			pullDirection = direction
		}
		pullPx += kotlin.math.abs(blocked) * 0.55f
		updatePill(manager)
	}

	private fun pullThresholdPx() = PULL_THRESHOLD_DP * resources.displayMetrics.density

	private fun updatePill(manager: LinearLayoutManager) {
		val pill = chapterPill ?: return
		val current = manager.findFirstVisibleItemPosition()
		val target = current + pullDirection
		if (pullDirection == 0 || pullPx <= 0f || target !in chapters.indices) {
			pill.hide()
			pullArmed = false
			return
		}
		val progress = (pullPx / pullThresholdPx()).coerceIn(0f, 1f)
		if (progress >= 1f && !pullArmed) {
			pullArmed = true
			pill.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
		} else if (progress < 1f) {
			pullArmed = false
		}
		pill.show(forward = pullDirection > 0, title = chapterTitle(target), progress = progress)
		(pill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
			val gravity = (if (pullDirection > 0) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
			if (lp.gravity != gravity) {
				lp.gravity = gravity
				pill.layoutParams = lp
			}
		}
	}

	/** The finger let go (or the gesture ended): change chapter if the pull got far enough. */
	private fun finishChapterPull() {
		val recycler = verticalView
		val manager = recycler?.layoutManager as? LinearLayoutManager
		val direction = pullDirection
		val armed = pullArmed
		pullPx = 0f
		pullDirection = 0
		pullArmed = false
		chapterPill?.hide()
		if (recycler == null || manager == null || !armed || direction == 0 || !isOverscrollStyle) return
		val target = manager.findFirstVisibleItemPosition() + direction
		if (target !in chapters.indices) return
		recycler.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
		manager.scrollToPositionWithOffset(target, 0)
	}

	/** How far [dy] may scroll in Overscroll style without leaving the chapter being read. */
	private fun overscrollAllowedDy(manager: LinearLayoutManager, dy: Int): Int {
		val current = manager.findFirstVisibleItemPosition()
		if (current !in chapters.indices) return dy
		val view = manager.findViewByPosition(current) ?: return dy
		return if (dy > 0) {
			if (current >= chapters.lastIndex) {
				dy
			} else {
				val offset = transitionOffsetPx(manager.height)
				val cardBottom = manager.getDecoratedBottom(view) - offset + transitionBlockPx()
				val remaining = cardBottom - (manager.height - manager.paddingBottom)
				dy.coerceAtMost(remaining.coerceAtLeast(0))
			}
		} else {
			if (current <= 0) dy else dy.coerceAtLeast((manager.getDecoratedTop(view) - manager.paddingTop).coerceAtMost(0))
		}
	}

	/** Rebuilds the scroll view in place after the scroll style changed. */
	private fun rebuildVerticalForStyle() {
		if (chapters.isEmpty() || isPagedMode) return
		val container = viewBinding?.readerContainer ?: return
		if (verticalView == null) return
		val locator = currentLocator()
		finishChapterPull()
		container.removeAllViews()
		verticalView = null
		chapterPill = null
		renderMode(locator)
	}

	// ---- Custom background picture (with optional blur) behind the text ----
	private var backgroundImageView: ImageView? = null
	private var backgroundJob: Job? = null

	private val customBackgroundFile: File?
		get() = settings.epubBackgroundImage.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }

	private val hasCustomBackground: Boolean get() = customBackgroundFile != null

	/** What views behind the text are painted with: nothing, when a picture shows through instead. */
	private val paintBackground: Int get() = if (hasCustomBackground) Color.TRANSPARENT else backgroundColor

	private fun applyCustomBackground() {
		val image = backgroundImageView ?: return
		backgroundJob?.cancel()
		val file = customBackgroundFile
		if (file == null) {
			image.setImageDrawable(null)
			image.isVisible = false
			refreshVisibleStyles()
			return
		}
		val blur = settings.epubBackgroundBlur
		val metrics = resources.displayMetrics
		backgroundJob = viewLifecycleOwner.lifecycleScope.launch {
			val bitmap = withContext(Dispatchers.IO) {
				runCatching {
					val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
					BitmapFactory.decodeFile(file.path, bounds)
					// Below Android 12 there is no render-effect blur: decode small and let the view scale
					// it back up, which softens it in proportion to the blur setting.
					val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
						1f
					} else {
						1f / (1f + blur / 2f)
					}
					var sample = 1
					while (bounds.outWidth / (sample * 2) >= metrics.widthPixels * target &&
						bounds.outHeight / (sample * 2) >= metrics.heightPixels * target
					) sample *= 2
					BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
				}.getOrNull()
			} ?: return@launch
			image.setImageBitmap(bitmap)
			image.scaleType = ImageView.ScaleType.CENTER_CROP
			image.isVisible = true
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				image.setRenderEffect(
					if (blur > 0) {
						android.graphics.RenderEffect.createBlurEffect(
							blur * 1.5f, blur * 1.5f, android.graphics.Shader.TileMode.CLAMP,
						)
					} else {
						null
					},
				)
				// the blurred edge fades to transparent, so draw slightly larger than the screen
				val scale = if (blur > 0) 1f + blur * 0.012f else 1f
				image.scaleX = scale
				image.scaleY = scale
			}
			refreshVisibleStyles()
		}
	}
	private val headerLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
	private val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
	private val headerCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

	/** Height reserved above each chapter for its header block (scroll mode only). */
	private val chapterHeaderHeightPx: Int
		get() = if (settings.isEpubChapterHeaderEnabled) (CHAPTER_HEADER_HEIGHT_DP * resources.displayMetrics.density).toInt() else 0

	private val chapterHeaderDecoration = object : RecyclerView.ItemDecoration() {
		override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
			if (parent.getChildAdapterPosition(view) in chapters.indices) outRect.top = chapterHeaderHeightPx
		}

		override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
			val height = chapterHeaderHeightPx
			if (height <= 0) return
			val density = resources.displayMetrics.density
			val color = foregroundColor
			val typeface = readerTypeface
			headerLabelPaint.apply { this.color = color; alpha = 160; textSize = 14f * density; this.typeface = typeface }
			headerTitlePaint.apply {
				this.color = color
				textSize = 30f * density
				this.typeface = Typeface.create(typeface, Typeface.BOLD)
			}
			headerCountPaint.apply { this.color = color; alpha = 120; textSize = 12f * density; this.typeface = typeface }
			val centerX = parent.width / 2f
			for (index in 0 until parent.childCount) {
				val child = parent.getChildAt(index)
				val position = parent.getChildAdapterPosition(child)
				if (position !in chapters.indices) continue
				val top = child.top + child.translationY - height
				val chapter = chapters[position]
				val label = getString(R.string.epub_chapter_header_label, position + 1)
				val title = chapter.title.ifBlank { label }
				val maxWidth = parent.width - 48f * density
				val fitted = TextUtils.ellipsize(title, headerTitlePaint, maxWidth, TextUtils.TruncateAt.END).toString()
				val count = getString(R.string.epub_chapter_header_count, position + 1, chapters.size)
				headerLabelPaint.textAlign = Paint.Align.CENTER
				headerTitlePaint.textAlign = Paint.Align.CENTER
				headerCountPaint.textAlign = Paint.Align.CENTER
				canvas.drawText(label, centerX, top + 36f * density, headerLabelPaint)
				canvas.drawText(fitted, centerX, top + 76f * density, headerTitlePaint)
				canvas.drawText(count, centerX, top + 102f * density, headerCountPaint)
			}
		}
	}
	private var chapterContent: ChapterContent? = null
	private val loadingChapters = HashSet<Int>()
	private var verticalView: RecyclerView? = null
	private var pagerView: ViewPager2? = null
	private var pages: List<NativePage> = emptyList()
	private var paginationKey: String? = null
	private var pageRange: IntRange? = null
	private var extendingPages = false
	private var lastLocator = Locator(0, 0)
	private var loading = false
	private var restoring = false
	private var progressScheduled = false
	private var renderGeneration = 0
	private var reflowLocator: Locator? = null
	private var colorAnimator: ValueAnimator? = null
	private var scrollTopClipPx = 0
	private var pagedTopBarClearancePx = 0
	private var highlights: List<Bookmark> = emptyList()
	private var highlightsJob: Job? = null
	private var highlightMangaId = 0L
	private var cachedCustomTypeface: Typeface? = null
	private var cachedCustomTypefaceStamp = Long.MIN_VALUE
	private var ttsHighlightHost: TextView? = null
	private var isTtsPickMode = false
	private val ttsHighlightSpan = HighlightColorSpan(0)

	private val rebuildRunnable = Runnable {
		val locator = reflowLocator
		reflowLocator = null
		if (chapters.isNotEmpty()) refreshReader(locator ?: currentLocator())
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentReaderEpubBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentReaderEpubBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.root.post(::updateTopClearances)
		backgroundImageView = ImageView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			isVisible = false
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		}
		binding.root.addView(backgroundImageView, 0)
		applyCustomBackground()
		settings.observeAsFlow(AppSettings.KEY_EPUB_BACKGROUND_IMAGE) { epubBackgroundImage }
			.observe(viewLifecycleOwner) { applyCustomBackground() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_BACKGROUND_BLUR) { epubBackgroundBlur }
			.observe(viewLifecycleOwner) { applyCustomBackground() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_THEME) { epubTheme }
			.observe(viewLifecycleOwner) {
				animateColors()
				refreshHighlightColors()
			}
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_BACKGROUND_COLOR) { epubCustomBackgroundColor }
			.observe(viewLifecycleOwner) { if (settings.epubTheme == EPUB_THEME_CUSTOM) animateColors() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_TEXT_COLOR) { epubCustomTextColor }
			.observe(viewLifecycleOwner) { if (settings.epubTheme == EPUB_THEME_CUSTOM) animateColors() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_HIGHLIGHT_COLOR) { epubCustomHighlightColor }
			.observe(viewLifecycleOwner) { if (settings.epubTheme == EPUB_THEME_CUSTOM) refreshHighlightColors() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_SIZE) { epubFontSize }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_FAMILY) { epubFontFamily }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_FONT_REVISION) { epubCustomFontRevision }
			.observe(viewLifecycleOwner) {
				cachedCustomTypeface = null
				cachedCustomTypefaceStamp = Long.MIN_VALUE
				if (settings.epubFontFamily == EPUB_FONT_CUSTOM) scheduleReflow()
			}
		settings.observeAsFlow(AppSettings.KEY_EPUB_LINE_HEIGHT) { epubLineHeight }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CHAPTER_HEADER) { isEpubChapterHeaderEnabled }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_LETTER_SPACING) { epubLetterSpacing }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_PARAGRAPH_INDENT) { epubParagraphIndent }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_HYPHENATION) { isEpubHyphenationEnabled }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_MAX_TEXT_WIDTH) { epubMaxTextWidth }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_PARAGRAPH_SPACING) { epubParagraphSpacing }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_HORIZONTAL_PADDING) { epubHorizontalPadding }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_VERTICAL_PADDING) { epubVerticalPadding }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_TEXT_ALIGN) { epubTextAlign }
			.observe(viewLifecycleOwner) { refreshAlignment() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_PUBLISHER_STYLE) { isEpubPublisherStyleEnabled }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_BIONIC_READING) { isEpubBionicReadingEnabled }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_READING_MODE) { epubReadingMode }
			.observe(viewLifecycleOwner) {
				binding.root.requestApplyInsets()
				switchReadingMode()
			}
		settings.observeAsFlow(AppSettings.KEY_EPUB_RTL) { isEpubRtl }
			.observe(viewLifecycleOwner) { switchReadingMode() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_SCROLL_STYLE) { epubScrollStyle }
			.observe(viewLifecycleOwner) { rebuildVerticalForStyle() }
		tts.position.observe(viewLifecycleOwner) { onTtsPositionChanged(it) }
		tts.chapterFinished.observe(viewLifecycleOwner) { onTtsChapterFinished(it) }
	}

	/** Starts speaking the current chapter from wherever the reader is right now. */
	fun startTts() {
		val locator = currentLocator().clamped()
		startTtsAt(locator.chapter, locator.offset)
	}

	/** While the TTS panel is open, a tap picks the paragraph to read from. */
	fun setTtsPickMode(value: Boolean) {
		isTtsPickMode = value
	}

	private fun startTtsAt(chapter: Int, offset: Int) {
		loadChapterForTts(chapter) { text ->
			tts.setChapter(chapter, text)
			tts.play(paragraphStart(text, offset))
		}
	}

	private fun startTtsAtTap(textView: TextView, event: MotionEvent) {
		val location = textView.tag as? TextLocation ?: return
		val offset = offsetAt(textView, event) ?: return
		startTtsAt(location.chapter, location.baseOffset + offset)
	}

	/** Previous/next chapter from the TTS player; keeps speaking from the start of the target. */
	fun ttsSkipChapter(delta: Int) {
		val current = tts.position.value?.chapter ?: currentLocator().clamped().chapter
		val target = current + delta
		if (target !in chapters.indices) return
		loadChapterForTts(target) { text ->
			goTo(Locator(target, 0), smooth = true)
			tts.setChapter(target, text)
			tts.play(0)
		}
	}

	private fun onTtsChapterFinished(chapter: Int) {
		if (tts.consumeSleepAtChapterEnd() || !settings.isEpubTtsAutoAdvance) {
			return
		}
		val next = chapter + 1
		if (next > chapters.lastIndex) {
			return
		}
		loadChapterForTts(next) { text ->
			goTo(Locator(next, 0), smooth = true)
			tts.setChapter(next, text)
			tts.play(0)
		}
	}

	private fun loadChapterForTts(index: Int, onLoaded: (String) -> Unit) {
		if (chapters.getOrNull(index) == null) {
			return
		}
		viewLifecycleOwner.lifecycleScope.launch {
			val text = withContext(Dispatchers.Default) {
				ensureChapterLoaded(index)
				chapters.getOrNull(index)?.content?.toString()
			}
			if (!text.isNullOrEmpty()) onLoaded(text)
		}
	}

	private fun onTtsPositionChanged(position: ReaderTts.Position?) {
		(ttsHighlightHost?.text as? Spannable)?.removeSpan(ttsHighlightSpan)
		ttsHighlightHost = null
		if (position == null) {
			return
		}
		if (bringTtsPositionIntoView(position)) {
			applyTtsHighlight(position)
		} else {
			// The page it lives on still has to be bound and laid out before it can be painted on.
			viewBinding?.root?.post { applyTtsHighlight(position) }
		}
	}

	/**
	 * Puts the spoken sentence on screen. Returns false when that needed a page turn or a jump, so
	 * the caller knows the target view does not exist yet.
	 */
	private fun bringTtsPositionIntoView(position: ReaderTts.Position): Boolean {
		val pager = pagerView ?: return true
		val target = pages.indexOfFirst {
			it.chapter == position.chapter && position.start in it.start until it.end
		}
		if (target < 0) {
			// Outside the loaded page window - the locator jump rebuilds it around the sentence.
			goTo(Locator(position.chapter, position.start), smooth = true)
			return false
		}
		if (target == pager.currentItem) {
			return true
		}
		pager.setCurrentItem(target, isAnimationEnabled())
		return false
	}

	private fun applyTtsHighlight(position: ReaderTts.Position) {
		ttsHighlightSpan.color = highlightColor
		val host = textViewAt(position.chapter, position.start) ?: return
		val location = host.tag as? TextLocation ?: return
		val spannable = host.text as? Spannable ?: return
		val start = (position.start - location.baseOffset).coerceIn(0, spannable.length)
		val end = (position.end - location.baseOffset).coerceIn(start, spannable.length)
		if (start != end) {
			spannable.setSpan(ttsHighlightSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			ttsHighlightHost = host
		}
		followTtsLine(host, start)
	}

	/**
	 * In paged mode the pager keeps neighbouring pages bound off screen, so the match has to be the
	 * page actually being shown - otherwise the highlight lands on a page nobody is looking at.
	 */
	private fun textViewAt(chapter: Int, offset: Int): TextView? {
		val recycler = verticalView ?: (pagerView?.getChildAt(0) as? RecyclerView) ?: return null
		val visiblePage = pagerView?.currentItem
		for (index in 0 until recycler.childCount) {
			val textView = recycler.getChildAt(index) as? TextView ?: continue
			if (visiblePage != null && recycler.getChildAdapterPosition(textView) != visiblePage) continue
			val location = textView.tag as? TextLocation ?: continue
			if (location.chapter == chapter && offset - location.baseOffset in 0 until textView.text.length) {
				return textView
			}
		}
		return null
	}

	/** Keeps the spoken line on screen in scroll mode without scrolling on every single sentence. */
	private fun followTtsLine(host: TextView, offsetInView: Int) {
		if (!settings.isEpubTtsFollowEnabled) return
		val recycler = verticalView ?: return
		val layout = host.layout ?: return
		val line = layout.getLineForOffset(offsetInView)
		val top = host.top + host.paddingTop + layout.getLineTop(line)
		val bottom = host.top + host.paddingTop + layout.getLineBottom(line)
		val visibleTop = recycler.paddingTop
		val visibleBottom = recycler.height - recycler.paddingBottom
		if (top >= visibleTop && bottom <= visibleBottom) {
			return
		}
		recycler.smoothScrollBy(0, top - visibleTop - (visibleBottom - visibleTop) / 3)
	}

	/** Rounds a tapped offset down to the start of its paragraph, which is what a tap means here. */
	private fun paragraphStart(text: String, offset: Int): Int {
		val at = offset.coerceIn(0, text.length - 1)
		return text.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
	}

	override fun onDestroyView() {
		ttsHighlightHost = null
		viewBinding?.root?.removeCallbacks(rebuildRunnable)
		highlightsJob?.cancel()
		highlightsJob = null
		highlights = emptyList()
		highlightMangaId = 0L
		colorAnimator?.cancel()
		colorAnimator = null
		cachedCustomTypeface = null
		cachedCustomTypefaceStamp = Long.MIN_VALUE
		verticalView = null
		pagerView = null
		pages = emptyList()
		paginationKey = null
		pageRange = null
		reflowLocator = null
		loadingChapters.clear()
		runCatching { chapterContent?.close() }
		chapterContent = null
		// The chapters are only readable through the source closed above; keeping them would make a new
		// view skip preparing the book and load every chapter it hadn't cached yet as blank.
		chapters = emptyList()
		super.onDestroyView()
	}

	override fun onCreateAdapter(): BaseReaderAdapter<*>? = null

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val density = resources.displayMetrics.density
		val scrollClearance = activity?.findViewById<View>(R.id.infoBar)?.height
			?.takeIf { it > 0 }
			?: (SCROLL_INFO_BAR_HEIGHT_DP * density).toInt()
		updateScrollTopClearance(scrollClearance)
		v.updatePadding(0, 0, 0, 0)
		v.post(::updateTopClearances)
		return insets
	}

	private fun updateTopClearances() {
		val density = resources.displayMetrics.density
		activity?.findViewById<View>(R.id.infoBar)?.height?.takeIf { it > 0 }?.let { height ->
			updateScrollTopClearance(height)
		}
		val topBarBottom = activity?.findViewById<View>(R.id.appbar_top)?.bottom ?: 0
		val clearance = topBarBottom.takeIf { it > 0 }
			?: (PAGED_TOP_BAR_FALLBACK_DP * density).toInt()
		if (clearance != pagedTopBarClearancePx) {
			pagedTopBarClearancePx = clearance
			if (isPagedMode && pagerView != null) scheduleReflow()
		}
	}

	private fun updateScrollTopClearance(height: Int) {
		if (height == scrollTopClipPx) return
		val recycler = verticalView
		val manager = recycler?.layoutManager as? LinearLayoutManager
		val first = manager?.findFirstVisibleItemPosition()?.takeIf { it >= 0 }
		val childTop = first?.let(manager::findViewByPosition)?.top
		scrollTopClipPx = height
		recycler?.setPadding(0, height, 0, 0)
		if (first != null && childTop != null) {
			manager.scrollToPositionWithOffset(first, childTop - height)
		}
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {
		val manga = viewModel.getMangaOrNull() ?: return
		val state = pendingState ?: viewModel.getCurrentState() ?: return
		observeHighlights(manga)
		val mangaChapters = manga.chapters.orEmpty()
		if (mangaChapters.isEmpty()) return
		// A local book opens straight from disk, but a novel chapter is a network fetch — the indicator
		// covers the gap. `finally` also clears it on the early returns below.
		setChapterLoading(true)
		try {
			if (chapters.isEmpty() && !loading) {
				loading = true
				try {
					val prepared = withContext(Dispatchers.IO) { prepareBook(manga, mangaChapters) }
					chapters = prepared.chapters
					chapterContent = prepared.content
				} finally {
					loading = false
				}
			}
			if (chapters.isEmpty()) return
			val chapter = chapters.indexOfFirst { it.id == state.chapterId }
				.takeIf { it >= 0 } ?: return
			withContext(Dispatchers.IO) { ensureChaptersLoaded(chapter.preloadRange()) }
			val offset = ReaderState.decodeEpubOffset(state.scroll)
				?: (chapters[chapter].text.length.toLong() * state.scroll.coerceIn(0, 1000) / 1000).toInt()
			renderMode(
				Locator(chapter, offset),
				state.page.takeIf { isPagedMode },
				centered = state.page == ReaderState.EPUB_PAGE_CENTERED,
			)
		} finally {
			setChapterLoading(false)
		}
	}

	private fun setChapterLoading(value: Boolean) {
		viewBinding?.loadingIndicator?.isVisible = value
	}

	private fun observeHighlights(manga: Manga) {
		if (highlightMangaId == manga.id) return
		highlightMangaId = manga.id
		highlightsJob?.cancel()
		highlightsJob = viewLifecycleOwner.lifecycleScope.launch {
			bookmarksRepository.observeBookmarks(manga).collect { bookmarks ->
				highlights = bookmarks.filter { it.epubHighlight != null }
				verticalView?.adapter?.notifyDataSetChanged()
				pagerView?.adapter?.notifyDataSetChanged()
			}
		}
	}

	private fun prepareBook(manga: Manga, source: List<MangaChapter>): PreparedBook {
		val items = source.map { chapter ->
			NativeChapter(
				id = chapter.id,
				title = chapter.title.orEmpty(),
				url = chapter.url,
			)
		}
		val archives = HashMap<File, ZipFile>()
		try {
			items.mapNotNull { it.url.toUri().takeIf { u -> u.isZipUri() }?.let { u -> File(u.schemeSpecificPart) } }
				.distinct()
				.forEach { archives[it] = ZipFile(it) }
		} catch (error: Throwable) {
			archives.values.forEach(ZipFile::close)
			throw error
		}
		val repository = if (manga.source.isNovelSource) {
			mangaRepositoryFactory.create(manga.source)
		} else {
			null
		}
		return PreparedBook(items, HybridContentSource(archives, repository, source))
	}

	private fun ensureChaptersLoaded(range: IntRange) {
		range.forEach(::ensureChapterLoaded)
	}

	private fun ensureChapterLoaded(index: Int) {
		val chapter = chapters.getOrNull(index) ?: return
		if (chapter.content != null) return
		synchronized(chapter) {
			if (chapter.content != null) return
			// Leave content null on failure so the next bind retries. Caching a blank chapter would
			// permanently blank it, for a remote source where one flaky request is expected and for a
			// download whose archive was momentarily unreadable alike.
			val source = chapterContent ?: return
			val result = runCatching { source.loadHtml(chapter.url) }
			val raw = result.getOrNull()
			if (raw.isNullOrBlank()) {
				chapter.error = result.exceptionOrNull()
				chapter.failedAttempts++
				return
			}
			chapter.error = null
			chapter.content = parseChapter(chapter, raw)
		}
	}

	private fun parseChapter(chapter: NativeChapter, raw: String): Spanned {
		val document = Jsoup.parse(raw)
		document.select("script,style,noscript").remove()
		document.select("svg").forEach { svg ->
			val image = svg.selectFirst("image") ?: return@forEach
			val source = image.attr("href").ifBlank { image.attr("xlink:href") }
			if (source.isNotBlank()) svg.replaceWith(image.clone().tagName("img").attr("src", source))
		}
		val parsed = HtmlCompat.fromHtml(
			document.body().html(),
			HtmlCompat.FROM_HTML_MODE_LEGACY,
			Html.ImageGetter { source -> loadEpubImage(chapter, source) },
			null,
		).trimmed().withPlainSpaces()
		return SpannedString(parsed).takeIf { it.isNotEmpty() } ?: EMPTY_CHAPTER_TEXT
	}

	/**
	 * Justified text overflowed its line whenever the line held a no-break space (`&nbsp;`, all over
	 * web novels): Android's justifier budgets the extra width for plain spaces only, but the text
	 * engine then widens no-break spaces too, pushing the last letters past the edge. Same length, so
	 * offsets (highlights, progress, TTS) are untouched.
	 */
	private fun CharSequence.withPlainSpaces(): Spanned {
		val plain = SpannableString(toString().replace(' ', ' '))
		if (this is Spanned) TextUtils.copySpansFrom(this, 0, length, null, plain, 0)
		return plain
	}

	private fun loadEpubImage(chapter: NativeChapter, source: String): Drawable? = runCatching {
		// ByteArray for an embedded epub resource, absolute url for a remote one - coil takes either.
		val data = chapterContent?.imageData(chapter.url, source) ?: return null
		val drawable = (data as? ByteArray)
			?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
			?.let { BitmapDrawable(resources, it) }
			?: runBlocking {
				// Tag the request with the source so it goes out with that source's headers/client:
				// illustrations on Referer-checking hosts 403 on a bare request.
				imageLoader.execute(
					ImageRequest.Builder(requireContext())
						.data(data)
						.mangaSourceExtra(viewModel.getMangaOrNull()?.source)
						.build(),
				).getDrawableOrThrow()
			}
		val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
		val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
		val metrics = resources.displayMetrics
		val maxWidth = (metrics.widthPixels - 2 * effectiveHorizontalPadding * metrics.density).toInt().coerceAtLeast(1)
		val maxHeight = (metrics.heightPixels * MAX_IMAGE_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
		val scale = minOf(1f, maxWidth / width.toFloat(), maxHeight / height.toFloat())
		drawable.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
		drawable
	}.getOrNull()

	private fun Spanned.trimmed(): CharSequence {
		var start = 0
		var end = length
		while (start < end && this[start].isWhitespace()) start++
		while (end > start && this[end - 1].isWhitespace()) end--
		return subSequence(start, end)
	}

	private fun Int.preloadRange(): IntRange =
		(this - PRELOAD_RADIUS).coerceAtLeast(0)..(this + PRELOAD_RADIUS).coerceAtMost(chapters.lastIndex)

	private fun preloadAround(center: Int) {
		center.preloadRange().forEach { index ->
			val chapter = chapters[index]
			// Past the cap a failed chapter waits on its Try again button instead of retrying forever
			if (chapter.content != null || chapter.failedAttempts >= MAX_AUTO_RETRIES || !loadingChapters.add(index)) {
				return@forEach
			}
			viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
				try {
					ensureChapterLoaded(index)
					// A failed load stays blank and the rebind below retries it at once; pace that so a
					// dead connection doesn't become a tight request loop.
					if (chapters.getOrNull(index)?.content == null) delay(LOAD_RETRY_DELAY_MS)
				} finally {
					withContext(Dispatchers.Main) {
						loadingChapters.remove(index)
						verticalView?.adapter?.notifyItemChanged(index)
					}
				}
			}
		}
	}

	private fun scheduleReflow() {
		// settings observers fire once on subscribe, before the book is loaded; a reflow queued
		// then would later render from an empty view and jump to the first page
		if (chapters.isEmpty()) return
		val root = viewBinding?.root ?: return
		if (reflowLocator == null) reflowLocator = currentLocator()
		root.removeCallbacks(rebuildRunnable)
		refreshVisibleStyles()
		root.postDelayed(rebuildRunnable, REPAGINATE_DELAY_MS)
	}

	private fun refreshVisibleStyles() {
		viewBinding?.readerContainer?.setBackgroundColor(paintBackground)
		verticalView?.apply {
			setBackgroundColor(paintBackground)
			adapter?.notifyDataSetChanged()
		}
		pagerView?.adapter?.notifyDataSetChanged()
	}

	private fun animateColors() {
		val container = viewBinding?.readerContainer ?: return
		val targetBackground = backgroundColor
		val targetForeground = foregroundColor
		val startBackground = (container.background as? ColorDrawable)?.color ?: targetBackground
		val startForeground = firstVisibleTextView()?.currentTextColor ?: targetForeground
		colorAnimator?.cancel()
		colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = COLOR_ANIMATION_MS
			addUpdateListener { animator ->
				val fraction = animator.animatedFraction
				applyVisibleColors(
					ColorUtils.blendARGB(startBackground, targetBackground, fraction),
					ColorUtils.blendARGB(startForeground, targetForeground, fraction),
				)
			}
			start()
		}
	}

	private fun applyVisibleColors(background: Int, foreground: Int) {
		chapterDividerPaint.color = foreground
		viewBinding?.root?.setBackgroundColor(background)
		val paint = if (hasCustomBackground) Color.TRANSPARENT else background
		viewBinding?.readerContainer?.setBackgroundColor(paint)
		fun recolor(recycler: RecyclerView?) {
			recycler?.setBackgroundColor(paint)
			if (recycler != null) for (index in 0 until recycler.childCount) {
				val child = recycler.getChildAt(index)
				child.setBackgroundColor(paint)
				(child as? TextView ?: child.findViewById<TextView>(R.id.textView_error))?.setTextColor(foreground)
			}
		}
		recolor(verticalView)
		verticalView?.invalidateItemDecorations()
		recolor(pagerView?.getChildAt(0) as? RecyclerView)
	}

	private fun firstVisibleTextView(): TextView? =
		(verticalView?.getChildAt(0) as? TextView)
			?: ((pagerView?.getChildAt(0) as? RecyclerView)?.getChildAt(0) as? TextView)

	private fun refreshHighlightColors() {
		fun recolor(recycler: RecyclerView?) {
			if (recycler != null) for (index in 0 until recycler.childCount) {
				val textView = recycler.getChildAt(index) as? TextView ?: continue
				val text = textView.text as? Spanned ?: continue
				text.getSpans(0, text.length, HighlightColorSpan::class.java).forEach { it.color = highlightColor }
				textView.invalidate()
			}
		}
		recolor(verticalView)
		recolor(pagerView?.getChildAt(0) as? RecyclerView)
	}

	private fun refreshAlignment() {
		fun update(recycler: RecyclerView?) {
			if (recycler != null) for (index in 0 until recycler.childCount) {
				(recycler.getChildAt(index) as? TextView)?.let(::applyTextAlignment)
			}
		}
		update(verticalView)
		update(pagerView?.getChildAt(0) as? RecyclerView)
	}

	private fun switchReadingMode() {
		if (chapters.isEmpty()) return
		val locator = currentLocator()
		viewBinding?.root?.removeCallbacks(rebuildRunnable)
		reflowLocator = null
		paginationKey = null
		refreshReader(locator)
	}

	private fun refreshReader(locator: Locator) {
		if (isPagedMode) {
			paginationKey = null
			renderPaged(viewBinding?.readerContainer ?: return, locator)
		} else if (verticalView != null) {
			verticalView?.setBackgroundColor(paintBackground)
			verticalView?.adapter?.notifyDataSetChanged()
			goTo(locator)
		} else {
			renderMode(locator)
		}
	}

	/** [centered] only affects scroll mode: a page in paged mode is fixed, so it just lands on it. */
	private fun renderMode(locator: Locator, pageInChapter: Int? = null, centered: Boolean = false) {
		val container = viewBinding?.readerContainer ?: return
		if (container.width == 0 || container.height == 0) {
			container.post { renderMode(locator, pageInChapter, centered) }
			return
		}
		val alreadyThere = currentLocator() == locator.clamped()
		lastLocator = locator.clamped()
		if (!isPagedMode && verticalView != null) {
			// re-emissions of the same state must not re-snap the scroll position
			if (!alreadyThere) goTo(lastLocator, centered = centered)
			return
		}
		if (isPagedMode && pagerView != null && pages.any {
				it.chapter == lastLocator.chapter && lastLocator.offset in it.start until it.end
			}
		) {
			goTo(lastLocator)
			return
		}
		restoring = true
		renderGeneration++
		if (isPagedMode) {
			renderPaged(container, lastLocator, pageInChapter)
		} else {
			container.removeAllViews()
			verticalView = null
			pagerView = null
			renderVertical(container, lastLocator, centered)
		}
	}

	private fun renderVertical(container: FrameLayout, locator: Locator, centered: Boolean) {
		viewBinding?.readerContainer?.isVerticalReadingMode = true
		chapterDividerPaint.color = foregroundColor
		val recycler = RecyclerView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			layoutManager = object : LinearLayoutManager(context) {
				override fun onRequestChildFocus(
					parent: RecyclerView,
					state: RecyclerView.State,
					child: View,
					focused: View?,
				): Boolean = true

				// Once tapped, a selectable chapter view keeps focus and asks to bring its invisible
				// cursor on screen after every rebind, which parks that cursor at the chapter start, so
				// taps yanked the reader back to it. Paged mode never did: ViewPager2 refuses these too.
				// Only a real selection may scroll, so a dragged handle can still reach past the edge.
				override fun requestChildRectangleOnScreen(
					parent: RecyclerView,
					child: View,
					rect: Rect,
					immediate: Boolean,
					focusedChildVisible: Boolean,
				): Boolean = (child as? TextView)?.hasSelection() == true &&
					super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)

				// Overscroll style: stop at the end / start of the chapter and report what was refused, so
				// pulling on turns into a chapter change instead of running into the next chapter.
				override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
					if (!isOverscrollStyle || childCount == 0 || dy == 0) {
						return super.scrollVerticallyBy(dy, recycler, state)
					}
					val allowed = overscrollAllowedDy(this, dy)
					val consumed = if (allowed != 0) super.scrollVerticallyBy(allowed, recycler, state) else 0
					onChapterEdgePull(this, dy - allowed, allowed)
					return consumed
				}
			}
			adapter = ChapterAdapter()
			itemAnimator = null
			overScrollMode = View.OVER_SCROLL_NEVER
			isClickable = false
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
			setBackgroundColor(paintBackground)
			clipToPadding = true
			setPadding(0, scrollTopClipPx, 0, 0)
			addItemDecoration(chapterTransitionDecoration)
			addItemDecoration(chapterHeaderDecoration)
			addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = scheduleProgress()

				override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
					if (newState != RecyclerView.SCROLL_STATE_DRAGGING) finishChapterPull()
				}
			})
		}
		verticalView = recycler
		container.addView(recycler)
		val pill = ChapterPullPillView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
			).apply {
				val d = resources.displayMetrics.density
				bottomMargin = (96 * d).toInt()
				topMargin = scrollTopClipPx + (24 * d).toInt()
			}
			setColors(
				pill = ColorUtils.setAlphaComponent(
					ColorUtils.blendARGB(backgroundColor, foregroundColor, 0.12f), 245,
				),
				text = foregroundColor,
				accent = requireContext().getThemeColor(appcompatR.attr.colorPrimary, Color.GRAY),
			)
		}
		chapterPill = pill
		container.addView(pill)
		positionVertical(locator, centered)
	}

	private fun renderPaged(container: FrameLayout, locator: Locator, pageInChapter: Int? = null) {
		val generation = ++renderGeneration
		val key = "${container.width}:${container.height}:$effectiveFontSize:${readerTypeface.hashCode()}:" +
			"${settings.epubCustomFontRevision}:" +
			"$effectiveLineHeight:$effectiveParagraphSpacing:$effectiveHorizontalPadding:$effectiveVerticalPadding:" +
			"$effectiveTextAlign:${settings.epubReadingMode}:${settings.isEpubRtl}:${settings.isEpubPublisherStyleEnabled}:" +
			"${settings.isEpubBionicReadingEnabled}"
		container.setBackgroundColor(paintBackground)
		if (pages.isNotEmpty() && paginationKey == key && pageRange?.contains(locator.chapter) == true) {
			renderPagedReady(container, locator, pageInChapter)
			return
		}
		val range = (locator.chapter - PAGE_LOOKAHEAD).coerceAtLeast(0)..
			(locator.chapter + PAGE_LOOKAHEAD).coerceAtMost(chapters.lastIndex)
		viewLifecycleOwner.lifecycleScope.launch {
			// Load on IO before laying out on Default: remote text sources fetch here, and a network
			// call must never run on a CPU dispatcher.
			withContext(Dispatchers.IO) { ensureChaptersLoaded(range) }
			val newPages = withContext(Dispatchers.Default) { paginate(container.width, container.height, range) }
			if (generation != renderGeneration || !isPagedMode || viewBinding?.readerContainer !== container) return@launch
			pages = newPages
			paginationKey = key
			pageRange = range
			renderPagedReady(container, locator, pageInChapter)
		}
	}

	private fun renderPagedReady(container: FrameLayout, locator: Locator, pageInChapter: Int? = null) {
		viewBinding?.readerContainer?.isVerticalReadingMode = false
		restoring = true
		container.removeAllViews()
		verticalView = null
		pagerView = null
		val pager = ViewPager2(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			orientation = ViewPager2.ORIENTATION_HORIZONTAL
			layoutDirection = if (isRtlPagedMode) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
			adapter = PageAdapter()
			overScrollMode = View.OVER_SCROLL_NEVER
			isClickable = false
			isFocusable = false
			registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
				if (!restoring) {
					notifyProgress()
					extendPageWindow(position)
				}
			}
			})
		}
		(pager.getChildAt(0) as? RecyclerView)?.apply {
			isClickable = false
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		}
		pagerView = pager
		container.addView(pager)
		val locatorTarget = pages.indexOfFirst {
			it.chapter == locator.chapter && locator.offset >= it.start && locator.offset < it.end
		}.takeIf { it >= 0 } ?: pages.indexOfLast { it.chapter <= locator.chapter }.coerceAtLeast(0)
		val firstChapterPage = pages.indexOfFirst { it.chapter == locator.chapter }
		val chapterPageCount = pages.count { it.chapter == locator.chapter }
		val savedTarget = pageInChapter
			?.takeIf { firstChapterPage >= 0 && it in 0 until chapterPageCount }
			?.let { firstChapterPage + it }
			?.takeIf { index ->
				val savedPage = pages[index]
				val tolerance = chapters[locator.chapter].text.length / 1000 + 1
				locator.offset in savedPage.start until savedPage.end ||
					kotlin.math.abs(locator.offset - savedPage.start) <= tolerance
			}
		val target = savedTarget ?: locatorTarget
		pager.setCurrentItem(target, false)
		pager.post {
			restoring = false
			notifyProgress()
			extendPageWindow(pager.currentItem)
		}
	}

	private fun extendPageWindow(position: Int) {
		if (extendingPages) return
		val range = pageRange ?: return
		val chapter = pages.getOrNull(position)?.chapter ?: return
		val prepend = chapter == range.first && range.first > 0
		val append = chapter == range.last && range.last < chapters.lastIndex
		if (!prepend && !append) return
		val target = if (prepend) range.first - 1 else range.last + 1
		val pager = pagerView ?: return
		val generation = renderGeneration
		extendingPages = true
		viewLifecycleOwner.lifecycleScope.launch {
			withContext(Dispatchers.IO) { ensureChaptersLoaded(target..target) }
			val added = withContext(Dispatchers.Default) { paginate(pager.width, pager.height, target..target) }
			if (generation == renderGeneration && pagerView === pager) {
				val adapter = pager.adapter ?: run { extendingPages = false; return@launch }
				if (prepend) {
					restoring = true
					pages = added + pages
					pageRange = target..range.last
					adapter.notifyItemRangeInserted(0, added.size)
					pager.setCurrentItem(position + added.size, false)
					pager.post { restoring = false; notifyProgress() }
				} else {
					val start = pages.size
					pages = pages + added
					pageRange = range.first..target
					adapter.notifyItemRangeInserted(start, added.size)
				}
			}
			extendingPages = false
		}
	}

	private fun paginate(viewWidth: Int, viewHeight: Int, range: IntRange): List<NativePage> {
		// Callers load [range] on IO first - see renderPaged/extendPageWindow.
		val density = resources.displayMetrics.density
		val horizontal = horizontalPaddingPx(viewWidth).coerceAtLeast(1)
		val verticalTop = verticalTopPaddingPx
		val verticalBottom = verticalBottomPaddingPx
		val width = (viewWidth - horizontal * 2).coerceAtLeast(1)
		val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
			color = foregroundColor
			textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics) *
				effectiveFontSize / 100f
			typeface = readerTypeface
			letterSpacing = effectiveLetterSpacingEm
		}
		// A page fragment gets its own font padding when rendered. Reserving one line prevents the
		// final baseline from being clipped when the vertical margins leave a tight viewport.
		val bottomSafety = ceil(paint.fontSpacing * effectiveLineHeight / 100f).toInt()
		val height = (viewHeight - verticalTop - verticalBottom - bottomSafety).coerceAtLeast(1)
		val result = ArrayList<NativePage>()
		range.forEach { chapterIndex ->
			val chapterText = styledChapterText(chapterIndex)
			val layout = StaticLayout.Builder.obtain(chapterText, 0, chapterText.length, paint, width)
				.setAlignment(textAlignment)
				.setTextDirection(if (isRtlPagedMode) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
				.setIncludePad(true)
				.setLineSpacing(0f, effectiveLineHeight / 100f)
				.setHyphenationFrequency(
					if (effectiveHyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE,
				)
				.setBreakStrategy(if (effectiveHyphenation) Layout.BREAK_STRATEGY_HIGH_QUALITY else Layout.BREAK_STRATEGY_SIMPLE)
				.apply {
					if (effectiveTextAlign == "justify") {
						enableInterWordJustificationCompat()
					}
				}.build()
			var start = 0
			while (start < chapterText.length) {
				val firstLine = layout.getLineForOffset(start)
				val bottom = (layout.getLineTop(firstLine) + height).coerceAtMost(layout.height)
				val lastLine = layout.getLineForVertical((bottom - 1).coerceAtLeast(0))
				val end = layout.getLineEnd(lastLine).coerceAtLeast(start + 1).coerceAtMost(chapterText.length)
				result += NativePage(chapterIndex, start, end)
				start = end
			}
		}
		return result.ifEmpty {
			val chapter = range.first.coerceIn(chapters.indices)
			listOf(NativePage(chapter, 0, chapters[chapter].text.length))
		}
	}

	@SuppressLint("WrongConstant")
	private fun StaticLayout.Builder.enableInterWordJustificationCompat() {
		// StaticLayout supports this value from API 26, but its current annotation only names API 29 constants.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setJustificationMode(1)
	}

	private fun createTextView(parent: ViewGroup, paged: Boolean): TextView = EpubSelectableTextView(parent.context).apply {
		layoutParams = if (paged) ViewGroup.LayoutParams(-1, -1) else ViewGroup.LayoutParams(-1, -2)
		applyTextStyle(this, paged)
		setTextIsSelectable(true)
		setTag(R.id.tag_epub_selectable_text, true)
		customSelectionActionModeCallback = TextSelectionCallback(this)
		installHighlightTapHandler(this)
	}

	private fun styledChapterText(chapterIndex: Int): Spanned {
		val chapter = chapters[chapterIndex]
		val text = SpannableString(chapter.text)
		text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
		text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach(text::removeSpan)
		if (!settings.isEpubPublisherStyleEnabled) {
			text.getSpans(0, text.length, AlignmentSpan::class.java).forEach(text::removeSpan)
			text.getSpans(0, text.length, AbsoluteSizeSpan::class.java).forEach(text::removeSpan)
			text.getSpans(0, text.length, RelativeSizeSpan::class.java).forEach(text::removeSpan)
			text.getSpans(0, text.length, TypefaceSpan::class.java).forEach(text::removeSpan)
		}
		applyParagraphSpacing(text)
		applyParagraphIndent(text)
		if (settings.isEpubBionicReadingEnabled) applyBionicReading(text)
		highlights.forEach { bookmark ->
			if (bookmark.chapterId != chapter.id) return@forEach
			val highlight = bookmark.epubHighlight ?: return@forEach
			val start = highlight.start.coerceIn(0, text.length)
			val end = highlight.end.coerceIn(start, text.length)
			if (start == end) return@forEach
			text.setSpan(HighlightColorSpan(highlightColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			text.setSpan(HighlightMarker(bookmark.pageId), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		return text
	}

	/**
	 * Bionic reading: bold the leading letters of every word so the eye can skim on the word shapes.
	 * The prefix length comes from [bionicPrefixLength], the table text-vide uses at its default
	 * fixation point, so the result matches other readers rather than a guessed percentage.
	 */
	private fun applyBionicReading(text: Spannable) {
		// ponytail: re-derived on every bind, like the paragraph-spacing scan above. Cache the spanned
		// copy on NativeChapter if long chapters ever stutter while scrolling.
		BIONIC_WORD.findAll(text).forEach { match ->
			val word = match.value
			// Scripts without word spacing have no leading letters to fix on - the whole run reads as
			// one "word" and would come out almost entirely bold.
			if (BIONIC_UNSPACED_SCRIPT.containsMatchIn(word)) return@forEach
			val prefix = bionicPrefixLength(word.length)
			if (prefix == 0) return@forEach
			val start = match.range.first
			text.setSpan(StyleSpan(Typeface.BOLD), start, start + prefix, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
	}

	/** First-line indent on every non-empty paragraph (a paragraph = text up to the next '\n'). */
	private fun applyParagraphIndent(text: Spannable) {
		val indentPx = (effectiveParagraphIndentEm * textSizePx()).toInt()
		if (indentPx <= 0) return
		var start = 0
		while (start < text.length) {
			var end = text.indexOf('\n', start)
			end = if (end < 0) text.length else end + 1
			if (text[start] != '\n' && end > start) {
				text.setSpan(LeadingMarginSpan.Standard(indentPx, 0), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			start = end
		}
	}

	/** Current body text size in pixels (same formula applyTextStyle/paginate use). */
	private fun textSizePx(): Float =
		TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics) * effectiveFontSize / 100f

	/** Horizontal padding, widened so the text column never exceeds the chosen max width. */
	private fun horizontalPaddingPx(viewWidth: Int): Int {
		val base = (effectiveHorizontalPadding * resources.displayMetrics.density).toInt()
		val maxWidthDp = effectiveMaxTextWidth
		if (maxWidthDp <= 0 || viewWidth <= 0) return base
		val maxPx = (maxWidthDp * resources.displayMetrics.density).toInt()
		return maxOf(base, (viewWidth - maxPx) / 2)
	}

	private fun currentViewWidth(): Int =
		verticalView?.width?.takeIf { it > 0 }
			?: pagerView?.width?.takeIf { it > 0 }
			?: resources.displayMetrics.widthPixels

	private fun applyParagraphSpacing(text: Spannable) {
		val extra = (effectiveParagraphSpacing * resources.displayMetrics.density).toInt()
		if (extra == 0) return
		var index = text.indexOf('\n')
		while (index >= 0) {
			var end = index + 1
			while (end < text.length && text[end] == '\n') end++
			if (end - index > 1) {
				text.setSpan(ParagraphSpacingSpan(extra), end - 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			index = text.indexOf('\n', end)
		}
	}

	private fun applyTextStyle(textView: TextView, paged: Boolean) = with(textView) {
		setTextColor(foregroundColor)
		setBackgroundColor(paintBackground)
		textSize = 16f * effectiveFontSize / 100f
		typeface = readerTypeface
		setLineSpacing(0f, effectiveLineHeight / 100f)
		letterSpacing = effectiveLetterSpacingEm
		hyphenationFrequency = if (effectiveHyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE
		breakStrategy = if (effectiveHyphenation) Layout.BREAK_STRATEGY_HIGH_QUALITY else Layout.BREAK_STRATEGY_SIMPLE
		includeFontPadding = true
		applyTextAlignment(this)
		val density = resources.displayMetrics.density
		val h = horizontalPaddingPx(currentViewWidth())
		val top = if (paged) verticalTopPaddingPx else (20 * density).toInt()
		val bottom = if (paged) verticalBottomPaddingPx else (36 * density).toInt()
		setPadding(h, top, h, bottom)
	}

	private fun applyTextAlignment(textView: TextView) = with(textView) {
		layoutDirection = View.LAYOUT_DIRECTION_LTR
		textDirection = if (isRtlPagedMode) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
		gravity = Gravity.TOP or when (effectiveTextAlign) {
			"center" -> Gravity.CENTER_HORIZONTAL
			"right", "end" -> Gravity.END
			"justify" -> if (isRtlPagedMode) Gravity.END else Gravity.START
			else -> Gravity.START
		}
		textAlignment = when (effectiveTextAlign) {
			"center" -> View.TEXT_ALIGNMENT_CENTER
			"right", "end" -> View.TEXT_ALIGNMENT_GRAVITY
			"justify" -> if (isRtlPagedMode) View.TEXT_ALIGNMENT_TEXT_START else View.TEXT_ALIGNMENT_VIEW_START
			else -> View.TEXT_ALIGNMENT_VIEW_START
		}
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			justificationMode = if (effectiveTextAlign == "justify") {
				Layout.JUSTIFICATION_MODE_INTER_WORD
			} else {
				Layout.JUSTIFICATION_MODE_NONE
			}
		}
	}

	private inner class TextSelectionCallback(
		private val textView: TextView,
	) : ActionMode.Callback {

		override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
			textView.parent?.requestDisallowInterceptTouchEvent(true)
			pagerView?.isUserInputEnabled = false
			menu.add(Menu.NONE, ACTION_DICTIONARY, Menu.NONE, R.string.dictionary)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			menu.add(Menu.NONE, ACTION_HIGHLIGHT, Menu.NONE, R.string.highlight)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			menu.add(Menu.NONE, ACTION_REMOVE_HIGHLIGHT, Menu.NONE, R.string.remove_highlight_action)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			return updateSelectionActions(menu)
		}

		override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean = updateSelectionActions(menu)

		override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
			val selection = selectedText(textView) ?: return false
			return when (item.itemId) {
				ACTION_DICTIONARY -> {
					showDictionary(selection.text)
					mode?.finish()
					true
				}

				ACTION_HIGHLIGHT -> {
					addHighlight(selection)
					mode?.finish()
					true
				}

				ACTION_REMOVE_HIGHLIGHT -> {
					selectedHighlight(selection)?.let { removeHighlight(it.pageId) } ?: return false
					mode?.finish()
					true
				}

				else -> false
			}
		}

		override fun onDestroyActionMode(mode: ActionMode?) {
			textView.parent?.requestDisallowInterceptTouchEvent(false)
			pagerView?.isUserInputEnabled = true
		}

		private fun updateSelectionActions(menu: Menu): Boolean {
			val selection = selectedText(textView)
			val highlight = selection?.let(::selectedHighlight)
			menu.findItem(ACTION_DICTIONARY)?.isVisible = selection?.text?.matches(WORD_PATTERN) == true
			menu.findItem(ACTION_HIGHLIGHT)?.isVisible = selection != null && highlight == null
			menu.findItem(ACTION_REMOVE_HIGHLIGHT)?.isVisible = highlight != null
			return true
		}
	}

	private fun selectedText(textView: TextView): SelectedText? {
		val location = textView.tag as? TextLocation ?: return null
		val value = textView.text
		var start = minOf(textView.selectionStart, textView.selectionEnd).coerceAtLeast(0)
		var end = maxOf(textView.selectionStart, textView.selectionEnd).coerceAtMost(value.length)
		while (start < end && value[start].isWhitespace()) start++
		while (end > start && value[end - 1].isWhitespace()) end--
		if (start == end) return null
		return SelectedText(
			chapter = location.chapter,
			start = location.baseOffset + start,
			end = location.baseOffset + end,
			text = value.subSequence(start, end).toString(),
		)
	}

	private fun addHighlight(selection: SelectedText, showToast: Boolean = true) {
		val manga = viewModel.getMangaOrNull() ?: return
		val chapter = chapters.getOrNull(selection.chapter) ?: return
		if (highlights.any {
				it.chapterId == chapter.id && it.epubHighlight?.let { h ->
					h.start == selection.start && h.end == selection.end
				} == true
			}) return
		val bookmark = Bookmark(
			manga = manga,
			pageId = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE,
			chapterId = chapter.id,
			page = selection.start,
			scroll = (selection.start.toLong() * 1000 / chapter.text.length.coerceAtLeast(1)).toInt(),
			imageUrl = epubHighlightUrl(selection.end, selection.text),
			createdAt = Instant.now(),
			percent = selection.start / chapter.text.length.coerceAtLeast(1).toFloat(),
		)
		viewLifecycleOwner.lifecycleScope.launch {
			withContext(Dispatchers.IO) { bookmarksRepository.addBookmark(bookmark) }
			if (showToast) Toast.makeText(requireContext(), R.string.highlight_added, Toast.LENGTH_SHORT).show()
		}
	}

	/** Highlight pick mode (armed from the reader dock): the tapped sentence becomes a highlight. */
	private fun highlightSentenceAt(textView: TextView, event: MotionEvent) {
		val location = textView.tag as? TextLocation ?: return
		val offset = offsetAt(textView, event) ?: return
		val text = chapters.getOrNull(location.chapter)?.text?.toString() ?: return
		val at = (location.baseOffset + offset).coerceIn(0, text.lastIndex)
		// Sentences never cross a paragraph, so break within the tapped paragraph only.
		val paragraphStart = paragraphStart(text, at)
		val paragraphEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
		if (paragraphStart >= paragraphEnd) return // tapped the blank line between paragraphs
		val sentences = BreakIterator.getSentenceInstance()
		sentences.setText(text.substring(paragraphStart, paragraphEnd))
		var end = sentences.following(at - paragraphStart)
		if (end == BreakIterator.DONE) end = paragraphEnd - paragraphStart
		var start = sentences.previous().coerceAtLeast(0) + paragraphStart
		end += paragraphStart
		while (start < end && text[start].isWhitespace()) start++
		while (end > start && text[end - 1].isWhitespace()) end--
		if (start == end) return
		viewModel.isHighlightPicking.value = false
		val selection = SelectedText(location.chapter, start, end, text.substring(start, end))
		// the sentence lighting up is the feedback - no toast
		if (selectedHighlight(selection) == null) addHighlight(selection, showToast = false)
	}

	private fun selectedHighlight(selection: SelectedText): Bookmark? {
		val chapterId = chapters.getOrNull(selection.chapter)?.id ?: return null
		return highlights.firstOrNull { bookmark ->
			bookmark.chapterId == chapterId && bookmark.epubHighlight?.let { highlight ->
				selection.start < highlight.end && selection.end > highlight.start
			} == true
		}
	}

	private fun removeHighlight(bookmarkId: Long) {
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			bookmarksRepository.removeBookmarks(setOf(bookmarkId))
		}
	}

	private fun installHighlightTapHandler(textView: TextView) {
		var pressedLink: URLSpan? = null
		val detector = GestureDetector(textView.context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
				val marker = findHighlightAt(textView, e) ?: return false
				showRemoveHighlight(marker.bookmarkId)
				return true
			}
		})
		textView.setOnTouchListener { _, event ->
			if (isTtsPickMode) {
				// Swallow the tap so it doesn't also turn the page or toggle the UI; RecyclerView
				// still intercepts drags, so scrolling to find a paragraph keeps working.
				if (event.actionMasked == MotionEvent.ACTION_UP) startTtsAtTap(textView, event)
				return@setOnTouchListener true
			}
			if (viewModel.isHighlightPicking.value) {
				if (event.actionMasked == MotionEvent.ACTION_UP) highlightSentenceAt(textView, event)
				return@setOnTouchListener true
			}
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					pressedLink = findSpanAt(textView, event, URLSpan::class.java)
					if (pressedLink != null) {
						textView.parent?.requestDisallowInterceptTouchEvent(true)
						return@setOnTouchListener true
					}
				}
				MotionEvent.ACTION_UP -> pressedLink?.let { link ->
					pressedLink = null
					textView.parent?.requestDisallowInterceptTouchEvent(false)
					openLink(textView, link.url)
					return@setOnTouchListener true
				}
				MotionEvent.ACTION_CANCEL -> {
					pressedLink = null
					textView.parent?.requestDisallowInterceptTouchEvent(false)
				}
			}
			detector.onTouchEvent(event)
			pressedLink != null
		}
	}

	private fun openLink(textView: TextView, href: String) {
		val location = textView.tag as? TextLocation ?: return
		val current = chapters.getOrNull(location.chapter) ?: return
		val target = resolveChapterLink(current.url, href, chapters.map { it.url })
		if (target != null) {
			goTo(Locator(target, 0), smooth = true)
		} else {
			chapterContent?.resolveExternalLink(current.url, href)?.let(router::openExternalBrowser)
		}
	}

	private fun findHighlightAt(textView: TextView, event: MotionEvent): HighlightMarker? =
		findSpanAt(textView, event, HighlightMarker::class.java)

	private fun <T> findSpanAt(textView: TextView, event: MotionEvent, type: Class<T>): T? {
		val text = textView.text as? Spanned ?: return null
		val offset = offsetAt(textView, event) ?: return null
		return text.getSpans(offset, offset + 1, type).firstOrNull()
	}

	/** Character offset under the touch, in the text of [textView] itself. */
	private fun offsetAt(textView: TextView, event: MotionEvent): Int? {
		val text = textView.text
		if (text.isNullOrEmpty()) return null
		val layout = textView.layout ?: return null
		val x = (event.x - textView.totalPaddingLeft + textView.scrollX).toInt()
		val y = (event.y - textView.totalPaddingTop + textView.scrollY).toInt()
		if (x !in 0..layout.width || y !in 0..layout.height) return null
		val line = layout.getLineForVertical(y)
		return layout.getOffsetForHorizontal(line, x.toFloat()).coerceIn(0, text.length - 1)
	}

	private fun showRemoveHighlight(bookmarkId: Long) {
		val bookmark = highlights.firstOrNull { it.pageId == bookmarkId } ?: return
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.remove_highlight)
			.setMessage(bookmark.epubHighlight?.text)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.remove) { _, _ ->
				removeHighlight(bookmarkId)
			}
			.show()
	}

	private fun showDictionary(word: String) {
		val binding = SheetEpubDictionaryBinding.inflate(layoutInflater)
		val dialog = BottomSheetDialog(requireContext())
		binding.textViewWord.text = word
		binding.buttonClose.setOnClickListener { dialog.dismiss() }
		dialog.setContentView(binding.root)
		dialog.show()
		viewLifecycleOwner.lifecycleScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { loadDictionary(word) }
			}
			if (!dialog.isShowing) return@launch
			binding.progressBar.isVisible = false
			binding.scrollViewContent.isVisible = true
			val entry = result.getOrNull()
			binding.textViewContent.text = when {
				result.isFailure -> getString(R.string.dictionary_error)
				entry == null -> getString(R.string.dictionary_not_found)
				else -> formatDictionaryEntry(entry)
			}
			binding.textViewPhonetic.text = entry?.phonetic.orEmpty()
			binding.textViewPhonetic.isVisible = !entry?.phonetic.isNullOrBlank()
			binding.textViewSource.isVisible = entry != null
		}
	}

	private fun loadDictionary(word: String): DictionaryEntry? {
		val url = DICTIONARY_URL.toHttpUrl().newBuilder().addPathSegment(word).build()
		val request = Request.Builder().url(url).build()
		val body = httpClient.newCall(request).execute().use { response ->
			if (response.code == 404) return null
			check(response.isSuccessful) { "Dictionary request failed: ${response.code}" }
			response.body.string()
		}
		return parseDictionaryEntry(body)
	}

	private fun parseDictionaryEntry(body: String): DictionaryEntry? {
		val entry = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
		val meanings = entry["meanings"]?.jsonArray.orEmpty().mapNotNull { meaningElement ->
			val meaning = meaningElement.jsonObject
			val definitions = meaning["definitions"]?.jsonArray.orEmpty().mapNotNull { definitionElement ->
				val definition = definitionElement.jsonObject
				val text = definition["definition"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				DictionaryDefinition(text, definition["example"]?.jsonPrimitive?.contentOrNull)
			}.take(MAX_DEFINITIONS_PER_MEANING)
			if (definitions.isEmpty()) return@mapNotNull null
			DictionaryMeaning(
				partOfSpeech = meaning["partOfSpeech"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				definitions = definitions,
				synonyms = meaning["synonyms"]?.jsonArray.orEmpty()
					.mapNotNull { it.jsonPrimitive.contentOrNull }.take(MAX_SYNONYMS),
			)
		}.take(MAX_MEANINGS)
		if (meanings.isEmpty()) return null
		return DictionaryEntry(
			phonetic = entry["phonetic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
			meanings = meanings,
		)
	}

	private fun formatDictionaryEntry(entry: DictionaryEntry): CharSequence {
		val text = SpannableStringBuilder()
		entry.meanings.forEachIndexed { meaningIndex, meaning ->
			if (meaningIndex > 0) text.append("\n\n")
			val headingStart = text.length
			text.append(meaning.partOfSpeech.ifBlank { getString(R.string.dictionary) })
			text.setSpan(StyleSpan(Typeface.BOLD), headingStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			meaning.definitions.forEachIndexed { index, definition ->
				text.append("\n${index + 1}. ${definition.text}")
				definition.example?.takeIf(String::isNotBlank)?.let {
					text.append("\n   ${getString(R.string.dictionary_example)}: “$it”")
				}
			}
			if (meaning.synonyms.isNotEmpty()) {
				text.append("\n${getString(R.string.dictionary_synonyms)}: ${meaning.synonyms.joinToString()}")
			}
		}
		return text
	}

	private fun currentLocator(): Locator {
		// mid-restore the views are not at the target position yet; trust the target instead
		// so idle/pause saves and scheduled reflows never capture a transient page 0
		if (restoring) return lastLocator.clamped()
		pagerView?.let { pager ->
			return pages.getOrNull(pager.currentItem)?.let { Locator(it.chapter, it.start) } ?: lastLocator
		}
		verticalView?.let { recycler ->
			val manager = recycler.layoutManager as? LinearLayoutManager ?: return lastLocator
			val index = manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return lastLocator
			val child = manager.findViewByPosition(index) ?: return Locator(index, 0)
			// A failed chapter's error card has no text to point into: its start is where we are
			if (child !is TextView) return Locator(index, 0)
			val layout = child.layout ?: return lastLocator
			val visibleOffset = (recycler.paddingTop - child.top).coerceIn(0, layout.height)
			val charOffset = layout.getLineStart(layout.getLineForVertical(visibleOffset))
			return Locator(index, charOffset).clamped()
		}
		return lastLocator.clamped()
	}

	private fun scheduleProgress() {
		if (restoring || progressScheduled) return
		progressScheduled = true
		viewBinding?.root?.postDelayed({
			progressScheduled = false
			notifyProgress()
		}, PROGRESS_INTERVAL_MS)
	}

	private fun notifyProgress() {
		if (chapters.isEmpty()) return
		val locator = currentLocator().clamped()
		lastLocator = locator
		val chapter = chapters[locator.chapter]
		val chapterPm = (locator.offset.toLong() * 1000 / chapter.text.length.coerceAtLeast(1)).toInt()
		val globalPage = pagerView?.currentItem ?: 0
		val firstChapterPage = if (pagerView != null) pages.indexOfFirst { it.chapter == locator.chapter }.coerceAtLeast(0) else 0
		val page = globalPage - firstChapterPage
		val pageCount = if (pagerView != null) pages.count { it.chapter == locator.chapter } else 0
		// ~1000 characters a minute (200 words/min) for the "time left" shown in the top bar
		val minutesLeft = if (chapter.content == null) -1 else kotlin.math.ceil((chapter.text.length - locator.offset).coerceAtLeast(0) / 1000.0).toInt()
		viewModel.epubChapterProgress.value = chapterPm.coerceIn(0, 1000) to minutesLeft
		viewModel.onEpubProgressChanged(chapter.id, locator.offset, chapterPm, page, pageCount)
	}

	override fun getCurrentState(): ReaderState? {
		if (chapters.isEmpty()) return null
		val locator = currentLocator().clamped()
		val chapter = chapters[locator.chapter]
		val firstChapterPage = pagerView?.let { pages.indexOfFirst { page -> page.chapter == locator.chapter } } ?: 0
		val page = pagerView?.currentItem?.minus(firstChapterPage)?.coerceAtLeast(0) ?: 0
		return ReaderState(
			chapter.id,
			page,
			ReaderState.encodeEpubOffset(locator.offset),
		)
	}

	override fun switchPageBy(delta: Int) {
		pagerView?.let {
			it.setCurrentItem((it.currentItem + delta).coerceIn(0, pages.lastIndex), isAnimationEnabled())
			return
		}
		verticalView?.smoothScrollBy(0, (verticalView?.height ?: 0) * 9 / 10 * delta)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		pagerView?.let {
			val chapter = pages.getOrNull(it.currentItem)?.chapter ?: return
			val first = pages.indexOfFirst { page -> page.chapter == chapter }.coerceAtLeast(0)
			val count = pages.count { page -> page.chapter == chapter }
			it.setCurrentItem(first + position.coerceIn(0, count - 1), smooth && isAnimationEnabled())
			return
		}
		val chapter = currentLocator().chapter
		val offset = (chapters[chapter].text.length.toLong() * position.coerceIn(0, 1000) / 1000).toInt()
		goTo(Locator(chapter, offset), smooth)
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		val recycler = verticalView ?: return false
		if (!recycler.canScrollVertically(delta)) return false
		if (smooth) recycler.smoothScrollBy(0, delta) else recycler.scrollBy(0, delta)
		return true
	}

	private fun goTo(locator: Locator, smooth: Boolean = false, centered: Boolean = false) {
		lastLocator = locator.clamped()
		if (pagerView != null) {
			val page = pages.indexOfFirst { it.chapter == lastLocator.chapter && lastLocator.offset in it.start until it.end }
			if (page >= 0) {
				pagerView?.setCurrentItem(page, smooth && isAnimationEnabled())
			} else {
				renderMode(lastLocator)
			}
		} else {
			positionVertical(lastLocator, centered)
		}
	}

	private fun positionVertical(locator: Locator, centered: Boolean = false) {
		val target = locator.clamped()
		val recycler = verticalView ?: return
		val manager = recycler.layoutManager as LinearLayoutManager
		lastLocator = target
		restoring = true
		recycler.doOnNextLayout {
			val textView = manager.findViewByPosition(target.chapter) as? TextView
			val layout = textView?.layout
			if (layout != null) {
				val length = textView.text.length
				val offset = target.offset.coerceIn(0, length)
				val top = layout.getLineTop(layout.getLineForOffset(offset))
				if (centered) {
					// Centre the whole highlight starting here; a highlight taller than the screen keeps its
					// start at the top. Not loaded yet (fresh open race) → centres just its first line.
					val chapterId = chapters.getOrNull(target.chapter)?.id
					val end = highlights.firstNotNullOfOrNull { bookmark ->
						bookmark.epubHighlight?.takeIf { bookmark.chapterId == chapterId && it.start == offset }?.end
					} ?: (offset + 1)
					val bottom = layout.getLineBottom(layout.getLineForOffset((end - 1).coerceIn(offset, length)))
					val viewport = recycler.height - recycler.paddingTop
					val margin = ((viewport - (bottom - top)) / 2).coerceAtLeast(0)
					recycler.scrollBy(0, chapterHeaderHeightPx + textView.totalPaddingTop + top - margin)
				} else {
					// offset 0 keeps the chapter header in view; otherwise skip past it
					recycler.scrollBy(0, if (top > 0) top + chapterHeaderHeightPx else 0)
				}
			}
			restoring = false
			notifyProgress()
		}
		manager.scrollToPositionWithOffset(target.chapter, 0)
	}

	override fun onZoomIn() {
		viewBinding?.readerContainer?.zoomIn()
	}

	override fun onZoomOut() {
		viewBinding?.readerContainer?.zoomOut()
	}

	fun showBookSearch() {
		val input = TextInputEditText(requireContext()).apply { setSingleLine() }
		val field = TextInputLayout(requireContext()).apply {
			hint = getString(R.string.epub_search_hint)
			boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
			setStartIconDrawable(R.drawable.ic_search)
			addView(input)
		}
		val container = FrameLayout(requireContext()).apply {
			val margin = (24 * resources.displayMetrics.density).toInt()
			setPadding(margin, 8, margin, 0)
			addView(field, FrameLayout.LayoutParams(-1, -2))
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.epub_search_book).setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.search) { _, _ -> searchBook(input.text.toString().trim()) }.show()
	}

	/**
	 * Remote sources search only what is already loaded: fetching every chapter of a 2000-chapter web
	 * novel to grep it would hammer the site and take minutes.
	 */
	private fun searchBook(query: String) {
		if (query.isEmpty() || chapters.isEmpty()) return
		val isRemote = (chapterContent as? HybridContentSource)?.hasRemote == true
		Toast.makeText(requireContext(), R.string.loading_, Toast.LENGTH_SHORT).show()
		viewLifecycleOwner.lifecycleScope.launch {
			val results = withContext(Dispatchers.IO) {
				if (!isRemote) ensureChaptersLoaded(chapters.indices)
				chapters.mapIndexedNotNull { index, chapter ->
					val match = chapter.text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return@mapIndexedNotNull null
					val start = (match - 45).coerceAtLeast(0)
					val end = (match + query.length + 70).coerceAtMost(chapter.text.length)
					SearchResult(index, chapter.title, chapter.text.substring(start, end).replace(Regex("\\s+"), " ").trim(), match)
				}.take(MAX_SEARCH_RESULTS)
			}
			if (results.isEmpty()) {
				Toast.makeText(requireContext(), R.string.epub_no_search_results, Toast.LENGTH_SHORT).show()
				return@launch
			}
			val adapter = object : ArrayAdapter<SearchResult>(requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, results) {
				override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
					val row = super.getView(position, convertView, parent)
					val result = getItem(position) ?: return row
					row.findViewById<TextView>(android.R.id.text1).text = result.title.ifEmpty { getString(R.string.epub_untitled_chapter) }
					row.findViewById<TextView>(android.R.id.text2).apply { text = result.snippet; maxLines = 2 }
					return row
				}
			}
			val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.search_results)
				.setAdapter(adapter) { _, index -> goTo(Locator(results[index].chapter, results[index].offset)) }
				.setNegativeButton(android.R.string.cancel, null).show()
			dialog.listView.apply {
				divider = ColorDrawable(requireContext().getThemeColor(materialR.attr.colorOutlineVariant, Color.GRAY))
				dividerHeight = resources.displayMetrics.density.toInt().coerceAtLeast(1)
			}
		}
	}

	private val isPagedMode get() = settings.epubReadingMode != EPUB_MODE_SCROLL

	/** Reading direction. Independent of [isPagedMode], so scroll mode can be RTL too. */
	private val isRtlPagedMode get() = settings.isEpubRtl
	private val effectiveTextAlign get() = when {
		settings.isEpubPublisherStyleEnabled -> if (isRtlPagedMode) "right" else "left"
		isRtlPagedMode && settings.epubTextAlign == "left" -> "right"
		else -> settings.epubTextAlign
	}
	private val effectiveLetterSpacingEm get() =
		if (settings.isEpubPublisherStyleEnabled) 0f else settings.epubLetterSpacing / 100f
	private val effectiveParagraphIndentEm get() =
		if (settings.isEpubPublisherStyleEnabled) 0f else settings.epubParagraphIndent / 2f
	private val effectiveHyphenation get() =
		!settings.isEpubPublisherStyleEnabled && settings.isEpubHyphenationEnabled
	private val effectiveMaxTextWidth get() =
		if (settings.isEpubPublisherStyleEnabled) 0 else settings.epubMaxTextWidth
	private val effectiveFontSize get() = if (settings.isEpubPublisherStyleEnabled) 100 else settings.epubFontSize
	private val effectiveLineHeight get() = if (settings.isEpubPublisherStyleEnabled) 120 else settings.epubLineHeight
	private val effectiveParagraphSpacing get() =
		if (settings.isEpubPublisherStyleEnabled) 0 else settings.epubParagraphSpacing
	private val effectiveHorizontalPadding get() =
		if (settings.isEpubPublisherStyleEnabled) PUBLISHER_HORIZONTAL_PADDING_DP else settings.epubHorizontalPadding
	private val effectiveVerticalPadding get() =
		if (settings.isEpubPublisherStyleEnabled) VERTICAL_MARGIN_MAX else settings.epubVerticalPadding
	private val verticalMarginFraction get() = (effectiveVerticalPadding / VERTICAL_MARGIN_MAX.toFloat()).coerceIn(0f, 1f)
	private val verticalTopPaddingPx get(): Int {
		val maximum = pagedTopBarClearancePx +
			(PAGED_TOP_EXTRA_MARGIN_DP * resources.displayMetrics.density).toInt()
		return scrollTopClipPx + ((maximum - scrollTopClipPx) * verticalMarginFraction).toInt()
	}
	private val verticalBottomPaddingPx get() =
		(MAX_BOTTOM_MARGIN_DP * verticalMarginFraction * resources.displayMetrics.density).toInt()
	private val backgroundColor: Int get() {
		if (settings.epubTheme == EPUB_THEME_CUSTOM) {
			return ColorUtils.setAlphaComponent(settings.epubCustomBackgroundColor, 255)
		}
		fixedThemePalette(settings.epubTheme)?.let { return it.first }
		val dark = when (settings.epubTheme) {
			"white", "light" -> false
			"gray", "dark" -> true
			"black" -> return Color.BLACK
			else -> resources.isNightMode
		}
		return ContextThemeWrapper(requireContext(), if (dark) materialR.style.ThemeOverlay_Material3_Dark else materialR.style.ThemeOverlay_Material3_Light)
			.getThemeColor(android.R.attr.colorBackground, if (dark) Color.BLACK else Color.WHITE)
	}
	private val foregroundColor get() = if (settings.epubTheme == EPUB_THEME_CUSTOM) {
		ColorUtils.setAlphaComponent(settings.epubCustomTextColor, 255)
	} else if (fixedThemePalette(settings.epubTheme) != null) {
		fixedThemePalette(settings.epubTheme)!!.second
	} else if (ColorUtils.calculateLuminance(backgroundColor) > .5) {
		0xFF1B1B1F.toInt()
	} else {
		0xFFE4E4E8.toInt()
	}
	private val highlightColor get() = if (settings.epubTheme == EPUB_THEME_CUSTOM) {
		ColorUtils.setAlphaComponent(settings.epubCustomHighlightColor, HIGHLIGHT_ALPHA)
	} else {
		DEFAULT_HIGHLIGHT_COLOR
	}
	private val readerTypeface get() = when {
		settings.isEpubPublisherStyleEnabled -> Typeface.SERIF
		settings.epubFontFamily == EPUB_FONT_CUSTOM -> customReaderTypeface()
		settings.epubFontFamily.startsWith(EpubBundledFonts.PREFIX) ->
			EpubBundledFonts.typeface(requireContext(), settings.epubFontFamily) ?: Typeface.SERIF
		else -> Typeface.create(settings.epubFontFamily.substringBefore(',').trim().trim('\'', '"'), Typeface.NORMAL)
	}

	private fun customReaderTypeface(): Typeface {
		val file = File(requireContext().filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE)
		val stamp = if (file.isFile) file.lastModified() xor file.length() else Long.MIN_VALUE
		if (stamp != cachedCustomTypefaceStamp) {
			cachedCustomTypefaceStamp = stamp
			cachedCustomTypeface = file.takeIf(File::isFile)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
		}
		return cachedCustomTypeface ?: Typeface.SERIF
	}
	private val textAlignment get() = when (effectiveTextAlign) {
		"center" -> Layout.Alignment.ALIGN_CENTER
		"right", "end" -> if (isRtlPagedMode) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
		"justify" -> Layout.Alignment.ALIGN_NORMAL
		else -> Layout.Alignment.ALIGN_NORMAL
	}

	private inner class ChapterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
		override fun getItemViewType(position: Int) = if (chapters[position].isFailed) VIEW_TYPE_ERROR else VIEW_TYPE_TEXT
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = if (viewType == VIEW_TYPE_ERROR) {
			ErrorHolder(parent)
		} else {
			TextHolder(createTextView(parent, false))
		}
		override fun getItemCount() = chapters.size
		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			when (holder) {
				is ErrorHolder -> holder.bind(position)
				is TextHolder -> {
					applyTextStyle(holder.text, false)
					holder.text.tag = TextLocation(position, 0)
					holder.text.text = styledChapterText(position)
				}
			}
			preloadAround(position)
		}
	}

	private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
		override fun getItemViewType(position: Int) =
			if (chapters[pages[position].chapter].isFailed) VIEW_TYPE_ERROR else VIEW_TYPE_TEXT
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = if (viewType == VIEW_TYPE_ERROR) {
			ErrorHolder(parent)
		} else {
			TextHolder(createTextView(parent, true))
		}
		override fun getItemCount() = pages.size
		override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
			val page = pages[position]
			if (holder is ErrorHolder) {
				holder.bind(page.chapter)
				return
			}
			holder as TextHolder
			val chapterText = styledChapterText(page.chapter)
			val visibleStart = (page.start until page.end).firstOrNull { !chapterText[it].isWhitespace() } ?: page.end
			applyTextStyle(holder.text, true)
			holder.text.tag = TextLocation(page.chapter, visibleStart)
			holder.text.text = chapterText.subSequence(visibleStart, page.end)
		}
	}

	private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)

	/** A chapter that failed to load, in the same message + Try again form as a failed manga page. */
	private inner class ErrorHolder(
		parent: ViewGroup,
		private val binding: ItemErrorStateBinding = ItemErrorStateBinding.inflate(layoutInflater, parent, false),
	) : RecyclerView.ViewHolder(binding.root) {

		private var chapterIndex = -1

		init {
			binding.buttonRetry.setOnClickListener { button ->
				button.isEnabled = false
				retryChapter(chapterIndex)
			}
		}

		fun bind(index: Int) {
			chapterIndex = index
			val error = chapters[index].error
			binding.root.setBackgroundColor(paintBackground)
			with(binding.textViewError) {
				text = error?.getDisplayMessage(resources) ?: getString(R.string.chapter_no_content)
				setTextColor(foregroundColor)
				setCompoundDrawablesWithIntrinsicBounds(0, error?.getDisplayIcon() ?: R.drawable.ic_error_large, 0, 0)
			}
			with(binding.buttonRetry) {
				isEnabled = true
				setText((error?.let(ExceptionResolver::getResolveStringId) ?: 0).ifZero { R.string.try_again })
			}
		}
	}

	/**
	 * Solves what the error asks for first (a captcha, a sign-in), then loads the chapter again with
	 * a fresh set of automatic retries, like a manga page's Try again.
	 */
	private fun retryChapter(index: Int) {
		val chapter = chapters.getOrNull(index) ?: return
		viewLifecycleOwner.lifecycleScope.launch {
			chapter.error?.takeIf(ExceptionResolver::canResolve)?.let { exceptionResolver.resolve(it) }
			chapter.failedAttempts = 0
			setChapterLoading(true)
			try {
				withContext(Dispatchers.IO) { ensureChapterLoaded(index) }
			} finally {
				setChapterLoading(false)
			}
			if (isPagedMode) {
				// The chapter was laid out as a single error page; paginate its real text now
				refreshReader(Locator(index, 0))
			} else {
				verticalView?.adapter?.notifyItemChanged(index)
			}
		}
	}
	private class HighlightColorSpan(var color: Int) : CharacterStyle(), UpdateAppearance {
		override fun updateDrawState(tp: TextPaint) {
			tp.bgColor = color
		}
	}

	private class EpubSelectableTextView(context: Context) : AppCompatTextView(
		ContextThemeWrapper(context, R.style.ThemeOverlay_Kotatsu_EpubSelectableText),
	) {
		private val selectionBackgroundColor = highlightColor
		private var selectionBackgroundSpan: SelectionBackgroundSpan? = null
		private var suppressDoubleTap = false
		private var justifiedSource: Layout? = null
		private var justifiedLayout: Layout? = null
		private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onDoubleTap(e: MotionEvent): Boolean {
				suppressDoubleTap = true
				return true
			}
		})

		init {
			setHighlightColor(Color.TRANSPARENT)
		}

		override fun onAttachedToWindow() {
			super.onAttachedToWindow()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				setTextSelectHandleLeft(createSelectionHandle())
				setTextSelectHandleRight(createSelectionHandle())
				setTextSelectHandle(createSelectionHandle())
			}
		}

		override fun onDraw(canvas: Canvas) {
			val justified = compatJustifiedLayout() ?: return super.onDraw(canvas)
			// What TextView.onDraw does for this view (top gravity, no drawables, never scrolled), minus
			// drawing its own unjustified layout. Selection is our LineBackgroundSpan, so it draws too.
			paint.color = currentTextColor
			paint.drawableState = drawableState
			canvas.withClip(compoundPaddingLeft, 0, width - compoundPaddingRight, height - extendedPaddingBottom) {
				withTranslation(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat()) { justified.draw(this) }
			}
		}

		/**
		 * Android 8-14 never justify selectable text. Its DynamicLayout gives the justification mode to
		 * the line breaker - which then lets lines run up to a third of a space too long, expecting them
		 * to be squeezed back when drawn - but not to the drawing. So lines came out ragged, and the
		 * overlong ones lost their last letters past the edge. Draw a StaticLayout with the very same line
		 * breaks instead: it does justify. Android 15 fixed DynamicLayout itself.
		 */
		private fun compatJustifiedLayout(): Layout? {
			if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
			if (justificationMode != Layout.JUSTIFICATION_MODE_INTER_WORD) return null
			// TextView swaps in a new layout on every text, width or style change, so identity is the key.
			val source = layout ?: return null
			if (source !== justifiedSource) {
				justifiedSource = source
				justifiedLayout = StaticLayout.Builder.obtain(source.text, 0, source.text.length, paint, source.width)
					.setAlignment(source.alignment)
					.setTextDirection(
						when {
							Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> textDirectionHeuristic
							textDirection == View.TEXT_DIRECTION_RTL -> TextDirectionHeuristics.RTL
							else -> TextDirectionHeuristics.FIRSTSTRONG_LTR
						},
					)
					.setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
					.setIncludePad(includeFontPadding)
					.setBreakStrategy(breakStrategy)
					.setHyphenationFrequency(hyphenationFrequency)
					.setJustificationMode(justificationMode)
					.apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUseLineSpacingFromFallbacks(isFallbackLineSpacing) }
					.build()
			}
			return justifiedLayout
		}

		override fun onTouchEvent(event: MotionEvent): Boolean {
			doubleTapDetector.onTouchEvent(event)
			if (suppressDoubleTap) {
				if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
					suppressDoubleTap = false
				}
				return true
			}
			return super.onTouchEvent(event)
		}

		private fun createSelectionHandle(): Drawable {
			val size = (14 * resources.displayMetrics.density).toInt()
			return GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(ColorUtils.setAlphaComponent(selectionBackgroundColor, 255))
				setSize(size, size)
			}
		}

		override fun onSelectionChanged(selStart: Int, selEnd: Int) {
			super.onSelectionChanged(selStart, selEnd)
			val spannable = text as? Spannable ?: return
			selectionBackgroundSpan?.let(spannable::removeSpan)
			selectionBackgroundSpan = null
			if (selStart < 0 || selEnd < 0 || selStart == selEnd) return
			val span = SelectionBackgroundSpan(this, selectionBackgroundColor, selStart, selEnd)
			selectionBackgroundSpan = span
			spannable.setSpan(
				span,
				minOf(selStart, selEnd),
				maxOf(selStart, selEnd),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
		}

		fun correctedHorizontal(offset: Int, line: Int): Float {
			val textLayout = layout ?: return 0f
			val horizontal = textLayout.getPrimaryHorizontal(offset)
			if (Build.VERSION.SDK_INT < 26 || justificationMode != Layout.JUSTIFICATION_MODE_INTER_WORD) return horizontal
			val lineStart = textLayout.getLineStart(line)
			val lineEnd = textLayout.getLineEnd(line)
			if (lineEnd >= text.length || text[lineEnd - 1] == '\n') return horizontal
			var visibleEnd = lineEnd
			while (visibleEnd > lineStart && text[visibleEnd - 1].isWhitespace()) visibleEnd--
			val spaces = (lineStart until visibleEnd).count { text[it] == ' ' }
			if (spaces == 0) return horizontal
			val extraPerSpace = (textLayout.width - paint.measureText(text, lineStart, visibleEnd)) / spaces
			val spacesBefore = (lineStart until offset.coerceAtMost(visibleEnd)).count { text[it] == ' ' }
			return horizontal + textLayout.getParagraphDirection(line) * extraPerSpace * spacesBefore
		}

		fun selectionEndHorizontal(offset: Int, line: Int): Float {
			val textLayout = layout ?: return 0f
			if (offset < textLayout.getLineEnd(line)) return correctedHorizontal(offset, line)
			return if (textLayout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
				textLayout.getLineLeft(line)
			} else {
				textLayout.getLineRight(line)
			}
		}
	}

	private class SelectionBackgroundSpan(
		private val textView: EpubSelectableTextView,
		color: Int,
		start: Int,
		end: Int,
	) : LineBackgroundSpan, NoCopySpan {
		private val selectionStart = minOf(start, end)
		private val selectionEnd = maxOf(start, end)
		private val backgroundPaint = Paint().apply { this.color = color }

		override fun drawBackground(
			canvas: Canvas,
			paint: Paint,
			left: Int,
			right: Int,
			top: Int,
			baseline: Int,
			bottom: Int,
			text: CharSequence,
			start: Int,
			end: Int,
			lineNumber: Int,
		) {
			val rangeStart = maxOf(start, selectionStart)
			val rangeEnd = minOf(end, selectionEnd)
			if (rangeStart >= rangeEnd) return
			val x1 = textView.correctedHorizontal(rangeStart, lineNumber)
			val x2 = textView.selectionEndHorizontal(rangeEnd, lineNumber)
			val metrics = paint.fontMetrics
			canvas.drawRect(
				minOf(x1, x2),
				baseline + metrics.ascent,
				maxOf(x1, x2),
				baseline + metrics.descent,
				backgroundPaint,
			)
		}
	}

	private class HighlightMarker(val bookmarkId: Long) : CharacterStyle() {
		override fun updateDrawState(tp: TextPaint) = Unit
	}
	private class ParagraphSpacingSpan(private val extra: Int) : LineHeightSpan {
		override fun chooseHeight(
			text: CharSequence,
			start: Int,
			end: Int,
			spanstartv: Int,
			v: Int,
			fm: Paint.FontMetricsInt,
		) {
			// Negative extra shrinks the blank line the source markup already puts between paragraphs,
			// clamped at collapsing it entirely — past that the line would take negative height.
			val delta = if (extra < 0) -minOf(-extra, fm.descent - fm.ascent) else extra
			fm.descent += delta
			fm.bottom += delta
		}
	}
	private class NativeChapter(
		val id: Long,
		val title: String,
		/** Local EPUB: `file:/path/book.epub#OEBPS/ch01.xhtml`. Remote text source: the chapter path. */
		val url: String,
	) {
		@Volatile
		var content: Spanned? = null
		val text: Spanned get() = content ?: EMPTY_CHAPTER_TEXT

		/** Why the last load failed; null with [failedAttempts] > 0 means the source sent no text. */
		@Volatile
		var error: Throwable? = null

		@Volatile
		var failedAttempts = 0

		val isFailed: Boolean get() = content == null && failedAttempts > 0
	}

	/**
	 * Where a chapter's raw HTML and its inline images come from. Local EPUBs read them out of a zip;
	 * remote text sources fetch them. Everything else in this reader — pagination, locators, highlights,
	 * search, progress — only ever touches [NativeChapter.text], so it stays content-agnostic.
	 */
	private interface ChapterContent : Closeable {
		/** Blocking. Throws on failure; a failed load must not be cached. */
		fun loadHtml(url: String): String

		/** [ByteArray] for an embedded resource, absolute-url [String] for a remote one, or null. */
		fun imageData(chapterUrl: String, source: String): Any?

		fun resolveExternalLink(chapterUrl: String, href: String): String?
	}

	private class HybridContentSource(
		private val archives: Map<File, ZipFile>,
		private val repository: MangaRepository?,
		chapters: List<MangaChapter>,
	) : ChapterContent {

		val hasRemote: Boolean get() = repository != null
		private val lock = Any()
		private val byUrl = chapters.associateBy { it.url }

		override fun loadHtml(url: String): String {
			val uri = url.toUri()
			if (uri.isZipUri()) {
				val entryName = uri.fragment.orEmpty()
				return synchronized(lock) {
					// Throw rather than return "" on a miss: an empty string reads as a legitimately
					// empty chapter and gets cached, so one unreadable archive blanked the download for
					// good. Failing lets the next bind retry.
					val zip = checkNotNull(archives[File(uri.schemeSpecificPart)]) { "Archive not open: $url" }
					val entry = zip.getEntry(entryName) ?: zip.getEntry(entryName.removePrefix("/"))
					checkNotNull(entry) { "Missing entry $entryName in ${uri.schemeSpecificPart}" }
					zip.getInputStream(entry).bufferedReader().use { reader -> reader.readText() }
				}
			}
			if (repository == null) return ""
			return runBlocking {
				val chapter = byUrl[url] ?: return@runBlocking ""
				repository.getChapterHtml(chapter).orEmpty()
			}
		}

		override fun imageData(chapterUrl: String, source: String): Any? {
			// A downloaded novel keeps its illustrations as absolute urls; let coil fetch those. Inline
			// data: images too - resolving one against the site turned it into a 404.
			if (source.startsWith("http://", true) || source.startsWith("https://", true) ||
				source.startsWith("data:", true)
			) {
				return source
			}
			val uri = chapterUrl.toUri()
			if (uri.isZipUri()) {
				val entryName = resolveEpubEntry(uri.fragment.orEmpty(), source)
				return synchronized(lock) {
					val archive = archives[File(uri.schemeSpecificPart)] ?: return null
					val entry = archive.getEntry(entryName)
						?: archive.getEntry(entryName.removePrefix("/"))
						?: return null
					archive.getInputStream(entry).use { it.readBytes() }
				}
			}
			if (repository == null) return null
			return when (val novelSource = repository.source.unwrap()) {
				is LnMangaSource -> novelSource.absoluteUrl(source)
				is MihonMangaSource -> (novelSource.catalogueSource as? HttpSource)
					?.let { resolveAgainst(it.baseUrl, source) }
				else -> null
			}
		}

		override fun resolveExternalLink(chapterUrl: String, href: String): String? {
			val uri = chapterUrl.toUri()
			if (uri.isZipUri()) {
				return href.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
			}
			if (repository == null) return null
			return when (val novelSource = repository.source.unwrap()) {
				is LnMangaSource -> novelSource.absoluteUrl(href)
				is MihonMangaSource -> (novelSource.catalogueSource as? HttpSource)
					?.let { resolveAgainst(it.baseUrl, href) }
				else -> null
			}
		}

		override fun close() {
			synchronized(lock) { archives.values.forEach(ZipFile::close) }
		}

		private fun resolveAgainst(baseUrl: String, value: String): String = when {
			value.startsWith("http://") || value.startsWith("https://") -> value
			value.startsWith("//") -> "https:$value"
			else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
		}

		private fun resolveEpubEntry(chapterEntry: String, source: String): String {
			val cleanSource = source.substringBefore('#').substringBefore('?').replace(" ", "%20")
			return URI("/${chapterEntry.replace('\\', '/')}").resolve(cleanSource).normalize().path.removePrefix("/")
		}
	}

	private data class PreparedBook(val chapters: List<NativeChapter>, val content: ChapterContent)
	private data class NativePage(val chapter: Int, val start: Int, val end: Int)
	private data class SearchResult(val chapter: Int, val title: String, val snippet: String, val offset: Int)
	private data class Locator(val chapter: Int, val offset: Int)
	private data class TextLocation(val chapter: Int, val baseOffset: Int)
	private data class SelectedText(val chapter: Int, val start: Int, val end: Int, val text: String)
	private data class DictionaryEntry(val phonetic: String, val meanings: List<DictionaryMeaning>)
	private data class DictionaryMeaning(
		val partOfSpeech: String,
		val definitions: List<DictionaryDefinition>,
		val synonyms: List<String>,
	)
	private data class DictionaryDefinition(val text: String, val example: String?)
	private fun Locator.clamped(): Locator {
		if (chapters.isEmpty()) return Locator(0, 0)
		val c = chapter.coerceIn(chapters.indices)
		return Locator(c, offset.coerceIn(0, chapters[c].text.length.coerceAtLeast(1) - 1))
	}

	companion object {
		private const val EPUB_MODE_SCROLL = "scroll"
		private const val EPUB_THEME_CUSTOM = "custom"
		private const val EPUB_FONT_CUSTOM = "custom"
		private const val CHAPTER_HEADER_HEIGHT_DP = 132
		private const val TRANSITION_CARD_HEIGHT_DP = 292
		private const val TRANSITION_MARGIN_DP = 24
		private const val PULL_THRESHOLD_DP = 96

		/** Fixed (background, text) pairs for the extra reading themes; null for the classic ones. */
		private fun fixedThemePalette(theme: String): Pair<Int, Int>? = when (theme) {
			"sepia" -> 0xFFF4ECD8.toInt() to 0xFF433422.toInt()
			"dusk" -> 0xFF1C2030.toInt() to 0xFFC9D1E8.toInt()
			"forest" -> 0xFF14211A.toInt() to 0xFFC4D6C8.toInt()
			else -> null
		}
		private const val MAX_SEARCH_RESULTS = 100
		private const val PROGRESS_INTERVAL_MS = 50L
		private const val LOAD_RETRY_DELAY_MS = 3000L
		private const val MAX_AUTO_RETRIES = 3
		private const val VIEW_TYPE_TEXT = 0
		private const val VIEW_TYPE_ERROR = 1
		private const val PAGE_LOOKAHEAD = 1
		private const val PRELOAD_RADIUS = 2
		private const val SCROLL_INFO_BAR_HEIGHT_DP = 24
		private const val PAGED_TOP_BAR_FALLBACK_DP = 80
		private const val PAGED_TOP_EXTRA_MARGIN_DP = 16
		private const val REPAGINATE_DELAY_MS = 180L
		private const val COLOR_ANIMATION_MS = 180L
		private const val VERTICAL_MARGIN_MAX = 112
		private const val PUBLISHER_HORIZONTAL_PADDING_DP = 20
		private const val MAX_BOTTOM_MARGIN_DP = 96
		private const val MAX_IMAGE_HEIGHT_FRACTION = 0.75f
		private const val ACTION_DICTIONARY = 0x455001
		private const val ACTION_HIGHLIGHT = 0x455002
		private const val ACTION_REMOVE_HIGHLIGHT = 0x455003
		private const val HIGHLIGHT_ALPHA = 0x66
		private const val DEFAULT_HIGHLIGHT_COLOR = 0x66FFD54F
		private const val DICTIONARY_URL = "https://api.dictionaryapi.dev/api/v2/entries/en"
		private const val MAX_MEANINGS = 4
		private const val MAX_DEFINITIONS_PER_MEANING = 3
		private const val MAX_SYNONYMS = 8

		/** Runs of letters/digits containing at least one letter - text-vide's word pattern. */
		private val BIONIC_WORD = Regex("[\\p{L}\\p{Nd}]*\\p{L}[\\p{L}\\p{Nd}]*")
		// ponytail: literal code-point ranges, not \p{IsHan}-style script names - the ICU on older
		// Android (API 28 and below) rejects those and blows up the whole class in <clinit>.
		private val BIONIC_UNSPACED_SCRIPT = Regex(
			"[\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF" +
				"\u1100-\u11FF\uAC00-\uD7AF\u0E00-\u0EFF\u1780-\u17FF]",
		)

		private val WORD_PATTERN = Regex("[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*")
		private val EMPTY_CHAPTER_TEXT = SpannedString("\u2014")
	}
}

/**
 * How many leading letters of a word bionic reading bolds: the word length minus the index of the
 * first boundary it fits into, or minus the table size when it is longer than every boundary.
 * The table is text-vide's, at the default fixation point, so output matches other readers.
 */
internal fun bionicPrefixLength(wordLength: Int): Int {
	val bounds = intArrayOf(0, 4, 12, 17, 24, 29, 35, 42, 48)
	val boundary = bounds.indexOfFirst { wordLength <= it }
	val prefix = if (boundary < 0) wordLength - bounds.size else wordLength - boundary
	return prefix.coerceIn(0, wordLength)
}

internal fun resolveChapterLink(currentUrl: String, href: String, chapterUrls: List<String>): Int? {
	if (href.isBlank()) return null
	if (href.startsWith("#")) return chapterUrls.indexOf(currentUrl).takeIf { it >= 0 }
	// An epub chapter is addressed as `file+zip:///path/book.epub#OEBPS/ch1.xhtml`. Both halves come
	// straight off disk, so they may contain spaces or non-ascii that java.net.URI rejects — match on
	// plain strings, and only within the same archive so a link can't jump into another book.
	val scheme = currentUrl.substringBefore(':').lowercase()
	if (scheme == URI_SCHEME_ZIP || scheme == "file") {
		val archive = currentUrl.substringBeforeLast('#')
		val currentEntry = normalizeEpubEntry(currentUrl.substringAfterLast('#', ""))
		val rawTarget = href.substringBefore('#').substringBefore('?')
		val targetEntries = setOf(
			EpubParser.resolveHref(currentEntry.substringBeforeLast('/', ""), rawTarget),
			EpubParser.resolveHref("", rawTarget),
		)
		return chapterUrls.indexOfFirst {
			it.substringBeforeLast('#') == archive &&
				normalizeEpubEntry(it.substringAfterLast('#', "")) in targetEntries
		}.takeIf { it >= 0 }
	}
	val target = runCatching { URI(currentUrl).resolve(href).normalize().toString().substringBefore('#') }.getOrNull()
		?: return null
	return chapterUrls.indexOfFirst { url ->
		runCatching { URI(url).normalize().toString().substringBefore('#') == target }.getOrDefault(false)
	}.takeIf { it >= 0 }
}

private fun normalizeEpubEntry(value: String): String =
	java.net.URLDecoder.decode(value.trimStart('/'), Charsets.UTF_8).replace('\\', '/')
