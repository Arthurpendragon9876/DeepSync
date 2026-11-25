package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.provider.Settings
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import com.google.android.material.button.MaterialButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.core.content.res.use
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import kotlin.math.roundToInt
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderBarSlot
import org.koitharu.kotatsu.core.prefs.ReaderControl
import org.koitharu.kotatsu.core.ui.util.applyTonalSegmentBackground
import org.koitharu.kotatsu.core.ui.util.applyTonalSegmentSpacing
import org.koitharu.kotatsu.core.ui.util.hasTonalSegmentBackground
import org.koitharu.kotatsu.core.ui.util.tonalIconSize
import org.koitharu.kotatsu.core.ui.util.tonalSegmentSize
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.hasVisibleChildren
import org.koitharu.kotatsu.core.util.ext.isRtl
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.LayoutReaderActionsBinding
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_BOOKMARKS
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet.Companion.TAB_PAGES
import org.koitharu.kotatsu.reader.ui.ReaderControlDelegate.OnInteractionListener
import javax.inject.Inject
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

@AndroidEntryPoint
class ReaderActionsView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr),
	View.OnClickListener,
	SharedPreferences.OnSharedPreferenceChangeListener,
	CapsuleSliderView.OnChangeListener,
	CapsuleSliderView.OnTouchListener, View.OnLongClickListener {

	@Inject
	lateinit var settings: AppSettings

	private val binding = LayoutReaderActionsBinding.inflate(LayoutInflater.from(context), this)

	/**
	 * Which part of the bars' layout this view draws. Every instance inflates all the buttons and
	 * shows only the controls of its own slots, so one layout preference drives the bottom bar and
	 * both sides of the top bar.
	 */
	private val area: Area = context.obtainStyledAttributes(attrs, R.styleable.ReaderActionsView).use {
		Area.entries.getOrElse(it.getInt(R.styleable.ReaderActionsView_readerBarArea, 0)) { Area.BOTTOM }
	}
	private val rotationObserver = object : ContentObserver(handler) {
		override fun onChange(selfChange: Boolean) {
			post {
				updateRotationButton()
			}
		}
	}
	private var isSliderChanged = false
	private var isSliderTracking = false
	private var lastSliderStep = Float.NaN
	private var scrubPreview: PageScrubPreview? = null

	var isSliderEnabled: Boolean
		get() = binding.slider.isEnabled
		set(value) {
			binding.slider.isEnabled = value
		}

	var isNextEnabled: Boolean
		get() = binding.buttonNext.isEnabled
		set(value) {
			binding.buttonNext.isEnabled = value
		}

	var isPrevEnabled: Boolean
		get() = binding.buttonPrev.isEnabled
		set(value) {
			binding.buttonPrev.isEnabled = value
		}

	var isBookmarkAdded: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				updateBookmarkButton()
			}
		}

	// Novels: the bookmark slot becomes "highlight", which arms a tap-a-sentence pick
	var isHighlightMode: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				updateBookmarkButton()
			}
		}

	var isHighlightPicking: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				updateBookmarkButton()
			}
		}

	/** Quick settings panel is open: the Settings button turns into a close button. */
	var isSettingsOpen: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				updateOptionsButton(animate = isAttachedToWindow)
			}
		}

	private var isTimerActive = false

	/**
	 * Whether the chapter can be opened on its source's website (an online manga, not a local one
	 * or a novel). The customise screen's previews have no reader behind them, so it defaults on.
	 */
	var isWebAvailable: Boolean = true
		set(value) {
			if (field != value) {
				field = value
				updateControlsVisibility()
			}
		}

	var listener: OnInteractionListener? = null

	// EPUB: the slider is a smooth in-chapter scrollbar - no tick stops, no label, live seeking
	var isSliderSmooth: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				if (!value) {
					binding.slider.value = binding.slider.value.roundToInt().toFloat()
				}
				binding.slider.stepSize = if (value) 0f else 1f
			}
		}

	/**
	 * Whether the current chapter can back the slider with page thumbnails. When it can, the
	 * thumbnail carries the page number itself, so Material's floating value label would only be a
	 * second chip fighting for the same spot above the thumb.
	 */
	var isScrubPreviewEnabled: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				if (!value) {
					scrubPreview?.dismiss()
				}
			}
		}

	init {
		orientation = HORIZONTAL
		gravity = Gravity.CENTER_VERTICAL
		binding.buttonNext.initAction()
		binding.buttonPrev.initAction()
		binding.buttonSave.initAction()
		binding.buttonOptions.initAction()
		binding.buttonScreenRotation.initAction()
		binding.buttonPagesThumbs.initAction()
		binding.buttonTimer.initAction()
		binding.buttonBookmark.initAction()
		binding.buttonWebView.initAction()
		binding.buttonExternalBrowser.initAction()
		binding.slider.addOnChangeListener(this)
		binding.slider.addOnSliderTouchListener(this)
		updateControlsVisibility()
		updatePagesSheetButton()
		updateRotationButton()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		// Re-inflated or re-attached top-bar groups must not depend on the one-off pass made when the
		// layout preference last changed.
		if (area.isTopSide) applyTopSideLook()
		settings.subscribe(this)
		context.contentResolver.registerContentObserver(
			Settings.System.CONTENT_URI, true, rotationObserver,
		)
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		super.onLayout(changed, l, t, r, b)
		// Self-heal: if a top-bar button lost its tonal segment (the buttons are MaterialButtons, which
		// can replace a custom background), put it back instead of leaving bare icons on the page.
		if (area.isTopSide && !isLookRefreshPending && !isTopSideLookIntact()) {
			isLookRefreshPending = true
			post {
				isLookRefreshPending = false
				if (isAttachedToWindow) applyTopSideLook()
			}
		}
	}

	private var isLookRefreshPending = false

	private fun isTopSideLookIntact(): Boolean = children
		.filterIsInstance<FrameLayout>()
		.filter { it.isVisible }
		.all { it.hasTonalSegmentBackground() }

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (!hasWindowFocus) {
			// A drag interrupted by a call/notification never gets its onStopTrackingTouch.
			scrubPreview?.dismiss()
		}
	}

	override fun onDetachedFromWindow() {
		settings.unsubscribe(this)
		context.contentResolver.unregisterContentObserver(rotationObserver)
		scrubPreview?.dismiss()
		super.onDetachedFromWindow()
	}

	override fun onClick(v: View) {
		v.hapticFeedback(HapticEffect.LIGHT_CLICK)
		when (v.id) {
			R.id.button_prev -> listener?.switchChapterBy(-1)
			R.id.button_next -> listener?.switchChapterBy(1)
			R.id.button_save -> listener?.onSavePageClick()
			// The dock button is a toggle: tap starts/stops autoscroll, long-press opens the panel.
			R.id.button_timer -> listener?.onScrollTimerClick(isLongClick = true)
			R.id.button_pages_thumbs -> AppRouter.from(this)?.showChapterPagesSheet()
			R.id.button_screen_rotation -> listener?.toggleScreenOrientation()
			R.id.button_options -> listener?.openMenu()
			R.id.button_bookmark -> listener?.onBookmarkClick()
			R.id.button_web_view -> listener?.onOpenWebClick(external = false)
			R.id.button_external_browser -> listener?.onOpenWebClick(external = true)
		}
	}

	override fun onLongClick(v: View): Boolean = when (v.id) {
		R.id.button_bookmark -> AppRouter.from(this)
			?.showChapterPagesSheet(ChaptersPagesSheet.TAB_BOOKMARKS)

		R.id.button_timer -> listener?.onScrollTimerClick(isLongClick = false)
		R.id.button_web_view -> listener?.onOpenWebClick(external = true)
		R.id.button_options -> AppRouter.from(this)?.openReaderSettings()
		else -> null
	} != null

	override fun onValueChange(slider: CapsuleSliderView, value: Float, fromUser: Boolean) {
		if (fromUser) {
			if (isSliderSmooth) {
				listener?.switchPageTo(value.toInt())
			} else if (isSliderTracking) {
				isSliderChanged = true
				// A single light tick per page the thumb crosses, so scrubbing feels like
				// stepping through pages. No heavier grab/release cues — keep it subtle.
				if (value != lastSliderStep) {
					lastSliderStep = value
					slider.hapticFeedback(HapticEffect.LIGHT_TICK)
				}
				showScrubPreview(value.toInt())
			} else {
				listener?.switchPageTo(value.toInt())
			}
		}
	}

	override fun onStartTrackingTouch(slider: CapsuleSliderView) {
		if (!isSliderTracking) {
			isSliderChanged = false
			isSliderTracking = true
			lastSliderStep = slider.value
		}
		showScrubPreview(slider.value.toInt())
	}

	override fun onStopTrackingTouch(slider: CapsuleSliderView) {
		isSliderTracking = false
		scrubPreview?.hide()
		if (isSliderChanged) {
			listener?.switchPageTo(slider.value.toInt())
		}
	}

	private fun showScrubPreview(index: Int) {
		if (!isScrubPreviewEnabled) {
			return
		}
		val page = listener?.getPageAt(index) ?: return
		val preview = scrubPreview ?: PageScrubPreview(binding.slider).also { scrubPreview = it }
		preview.show(page)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_READER_BAR_LAYOUT -> updateControlsVisibility()
			AppSettings.KEY_PAGES_TAB,
			AppSettings.KEY_DETAILS_TAB,
			AppSettings.KEY_DETAILS_LAST_TAB -> updatePagesSheetButton()
		}
	}

	fun setSliderValue(value: Int, max: Int) {
		binding.slider.valueTo = max.toFloat()
		// epub smooth mode seeks live while dragging - don't fight the user's thumb
		if (!(isSliderSmooth && isSliderTracking)) {
			binding.slider.setValueRounded(value.toFloat())
		}
	}

	fun setSliderReversed(reversed: Boolean) {
		binding.slider.isRtl = reversed != isRtl
	}

	fun setTimerActive(isActive: Boolean) {
		isTimerActive = isActive
		binding.buttonTimer.iconTint = if (isActive) {
			ColorStateList.valueOf(context.getThemeColor(appcompatR.attr.colorPrimary))
		} else {
			idleIconTint()
		}
	}

	/** The resting icon colour: what the toolbar's menu actions use on the top bar. */
	private fun idleIconTint(): ColorStateList = ColorStateList.valueOf(
		context.getThemeColor(if (area.isTopSide) R.attr.colorTopBarIcon else materialR.attr.colorOnSurfaceVariant),
	)

	private fun updateControlsVisibility() {
		val layout = settings.readerBarLayout
		val shown = layout.filter { it.isShown && it.control != ReaderControl.SLIDER }
		// The controls of this view's own slots, left to right. A null slot is the page slider,
		// which sits between the two sides of the bottom bar.
		val ordered = ArrayList<ReaderControl>()
		var hasSlider = false
		for (slot in area.slots) {
			if (slot == null) {
				hasSlider = layout.any { it.control == ReaderControl.SLIDER && it.isShown }
			} else {
				shown.filter { it.slot == slot }.mapTo(ordered) { it.control }
			}
		}
		binding.buttonPrev.isVisible = ReaderControl.PREV_CHAPTER in ordered
		binding.buttonNext.isVisible = ReaderControl.NEXT_CHAPTER in ordered
		binding.buttonPagesThumbs.isVisible = ReaderControl.PAGES_SHEET in ordered
		binding.buttonScreenRotation.isVisible = ReaderControl.SCREEN_ROTATION in ordered
		binding.buttonSave.isVisible = ReaderControl.SAVE_PAGE in ordered
		binding.buttonTimer.isVisible = ReaderControl.TIMER in ordered
		binding.buttonBookmark.isVisible = ReaderControl.BOOKMARK in ordered
		binding.buttonWebView.isVisible = ReaderControl.WEB_VIEW in ordered && isWebAvailable
		binding.buttonExternalBrowser.isVisible = ReaderControl.EXTERNAL_BROWSER in ordered && isWebAvailable
		binding.buttonOptions.isVisible = ReaderControl.OPTIONS in ordered
		binding.slider.isVisible = hasSlider
		// bringChildToFront moves a child to the end of the child list, so walking the ordered
		// list left to right leaves the children in exactly that order. Nothing is pinned: every
		// control sits wherever the user put it.
		for (slot in area.slots) {
			if (slot == null) {
				if (hasSlider) bringChildToFront(binding.slider)
			} else {
				shown.filter { it.slot == slot }.forEach { bringChildToFront(it.control.view()) }
			}
		}
		adjustLayoutParams()
		if (area.isTopSide) applyTopSideLook()
		// The rotation icon is only set while its button is visible, and it may just have become so.
		updateRotationButton()
		// A top-bar side with nothing on it must not leave its padding in the toolbar.
		if (area.isTopSide) {
			visibility = if (ordered.isEmpty()) GONE else VISIBLE
		}
	}

	/** The direct child of this LinearLayout that hosts the given control. */
	private fun ReaderControl.view(): View = when (this) {
		ReaderControl.PREV_CHAPTER -> binding.buttonPrev.slot()
		ReaderControl.NEXT_CHAPTER -> binding.buttonNext.slot()
		ReaderControl.SLIDER -> binding.slider
		ReaderControl.PAGES_SHEET -> binding.buttonPagesThumbs.slot()
		ReaderControl.SCREEN_ROTATION -> binding.buttonScreenRotation.slot()
		ReaderControl.SAVE_PAGE -> binding.buttonSave.slot()
		ReaderControl.TIMER -> binding.buttonTimer.slot()
		ReaderControl.WEB_VIEW -> binding.buttonWebView.slot()
		ReaderControl.EXTERNAL_BROWSER -> binding.buttonExternalBrowser.slot()
		ReaderControl.BOOKMARK -> binding.buttonBookmark.slot()
		ReaderControl.OPTIONS -> binding.buttonOptions.slot()
	}

	private fun View.slot(): View = parent as View

	private fun updateOptionsButton(animate: Boolean) {
		val button = binding.buttonOptions
		val open = isSettingsOpen
		val render = {
			button.setIconResource(if (open) R.drawable.ic_close else R.drawable.ic_tune)
			button.iconTint = if (open) {
				ColorStateList.valueOf(context.getThemeColor(appcompatR.attr.colorPrimary))
			} else {
				idleIconTint()
			}
			button.setContentDescriptionAndTooltip(if (open) R.string.close else R.string.options)
		}
		button.animate().cancel()
		if (!animate) {
			render()
			button.scaleX = 1f
			button.scaleY = 1f
			button.rotation = if (open) 90f else 0f
			return
		}
		// Shrink, swap the icon, grow back with a quarter turn: tune turns into a close cross
		button.animate().scaleX(0f).scaleY(0f).setDuration(90L).withEndAction {
			render()
			button.animate().scaleX(1f).scaleY(1f).rotation(if (open) 90f else 0f).setDuration(160L).start()
		}.start()
	}

	private fun updatePagesSheetButton() {
		val button = binding.buttonPagesThumbs
		val tab = settings.defaultDetailsTab
		button.setIconResource(when (tab) {
			TAB_PAGES -> R.drawable.ic_grid
			TAB_BOOKMARKS -> R.drawable.ic_bookmark
			else -> R.drawable.ic_list
		})
		button.setContentDescriptionAndTooltip(
			when (tab) {
				TAB_PAGES -> R.string.pages
				TAB_BOOKMARKS -> R.string.bookmarks
				else -> R.string.chapters
			},
		)
	}

	private fun updateBookmarkButton() {
		val button = binding.buttonBookmark
		if (isHighlightMode) {
			button.setIconResource(
				if (isHighlightPicking) R.drawable.ic_ink_highlighter_filled else R.drawable.ic_ink_highlighter,
			)
			button.setContentDescriptionAndTooltip(R.string.highlight)
			return
		}
		button.setIconResource(
			if (isBookmarkAdded) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark,
		)
		button.setContentDescriptionAndTooltip(
			if (isBookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add,
		)
	}

	/**
	 * On the top bar the visible buttons form one connected group that looks like the toolbar's menu
	 * actions did: a tonal segment each (outer corners round, inner ones small), as tall as the back
	 * button, with the same gap and icon colour. Bottom-bar buttons keep the floating toolbar look.
	 */
	private fun applyTopSideLook() {
		val segments = children.filterIsInstance<FrameLayout>().filter { it.isVisible }.toList()
		val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
		applyTonalSegmentSpacing()
		val tint = idleIconTint()
		val iconSize = context.tonalIconSize()
		segments.forEachIndexed { index, frame ->
			val isFirst = index == 0
			val isLast = index == segments.lastIndex
			val (width, height) = context.tonalSegmentSize(isOnlySegment = isFirst && isLast)
			frame.updateLayoutParams<LayoutParams> {
				this.width = width
				this.height = height
				weight = 0f
				marginEnd = 0
			}
			val button = frame.getChildAt(0) as? MaterialButton ?: return@forEachIndexed
			button.updateLayoutParams<FrameLayout.LayoutParams> {
				this.width = width
				this.height = height
			}
			button.minWidth = 0
			button.minHeight = 0
			button.setPadding(0, 0, 0, 0)
			button.iconPadding = 0
			button.iconSize = iconSize
			// The fill lives on the frame around the button (a MaterialButton's transparent background
			// tint would hide it there); the button keeps only a shape-bounded ripple as its foreground.
			button.rippleColor = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
			frame.applyTonalSegmentBackground(
				button = button,
				roundLeft = if (isRtl) isLast else isFirst,
				roundRight = if (isRtl) isFirst else isLast,
			)
		}
		// Resting colour for every button; the timer and settings buttons keep their active state.
		listOf(
			binding.buttonPrev, binding.buttonNext, binding.buttonSave, binding.buttonScreenRotation,
			binding.buttonPagesThumbs, binding.buttonBookmark, binding.buttonWebView, binding.buttonExternalBrowser,
		).forEach { it.iconTint = tint }
		setTimerActive(isTimerActive)
		updateOptionsButton(animate = false)
	}

	private fun adjustLayoutParams() {
		val isSliderVisible = binding.slider.isVisible
		// With no slider the buttons share the row's width, except on a top-bar side, which only
		// takes the room its buttons need so the title keeps the rest of the toolbar.
		val spread = !isSliderVisible && !area.isTopSide
		repeat(childCount) { i ->
			val child = getChildAt(i)
			if (child is FrameLayout) {
				child.isVisible = child.hasVisibleChildren
				child.updateLayoutParams<LayoutParams> {
					width = if (spread) 0 else LayoutParams.WRAP_CONTENT
					weight = if (spread) 1f else 0f
					if (area.isTopSide) marginEnd = 0
				}
			}
		}
	}

	private fun updateRotationButton() {
		val button = binding.buttonScreenRotation
		when {
			!button.isVisible -> return
			isAutoRotationEnabled() -> {
				button.setContentDescriptionAndTooltip(R.string.lock_screen_rotation)
				button.setIconResource(R.drawable.ic_screen_rotation_lock)
			}

			else -> {
				button.setContentDescriptionAndTooltip(R.string.rotate_screen)
				button.setIconResource(R.drawable.ic_screen_rotation)
			}
		}
	}

	private fun Button.initAction() {
		setOnClickListener(this@ReaderActionsView)
		setOnLongClickListener(this@ReaderActionsView)
		setTooltipCompat(contentDescription)
	}

	private fun isAutoRotationEnabled(): Boolean = Settings.System.getInt(
		context.contentResolver,
		Settings.System.ACCELEROMETER_ROTATION,
		0,
	) == 1

	/** What a [ReaderActionsView] draws; null in [slots] stands for the page slider. */
	private enum class Area(val slots: List<ReaderBarSlot?>) {

		BOTTOM(listOf(ReaderBarSlot.BOTTOM_START, null, ReaderBarSlot.BOTTOM_END)),
		TOP_START(listOf(ReaderBarSlot.TOP_START)),
		TOP_END(listOf(ReaderBarSlot.TOP_END)),
		ALL(listOf(ReaderBarSlot.TOP_START, ReaderBarSlot.BOTTOM_START, null, ReaderBarSlot.BOTTOM_END, ReaderBarSlot.TOP_END));

		val isTopSide: Boolean
			get() = this == TOP_START || this == TOP_END
	}
}
