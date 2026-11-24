package org.koitharu.kotatsu.reader.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

/**
 * The reader bar's progress control, drawn exactly like the novel reader's capsule: a rounded rail
 * that fills with the theme's text colour, dark dots inside the filled part and a thin marker at the
 * current position. The capsule swells while a finger is on it.
 *
 * It keeps the small part of the Material slider API the bar relied on (value, valueTo, stepSize,
 * change/touch listeners, [trackSidePadding] and [trackWidth] for the scrub preview) so the rest of
 * the reader code stays as it was.
 */
class CapsuleSliderView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	interface OnChangeListener {
		fun onValueChange(slider: CapsuleSliderView, value: Float, fromUser: Boolean)
	}

	interface OnTouchListener {
		fun onStartTrackingTouch(slider: CapsuleSliderView)
		fun onStopTrackingTouch(slider: CapsuleSliderView)
	}

	private val density = resources.displayMetrics.density
	private val rail = Paint(Paint.ANTI_ALIAS_FLAG)
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
	private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
	private val dots = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()
	private val changeListeners = ArrayList<OnChangeListener>()
	private val touchListeners = ArrayList<OnTouchListener>()
	private var swell = 0f
	private var swellAnimator: ValueAnimator? = null

	val valueFrom: Float = 0f

	var valueTo: Float = 1f
		set(newValue) {
			field = max(newValue, valueFrom + 1f)
			value = value
		}

	/** 0 = smooth (any value), otherwise values snap to multiples of this. */
	var stepSize: Float = 1f

	var value: Float = 0f
		set(newValue) {
			val coerced = newValue.coerceIn(valueFrom, valueTo)
			if (field != coerced) {
				field = coerced
				invalidate()
			}
		}

	var isTracking: Boolean = false
		private set

	val trackSidePadding: Int
		get() = paddingLeft

	val trackWidth: Int
		get() = max(0, width - paddingLeft - paddingRight)

	init {
		applyColors()
		isClickable = true
	}

	fun addOnChangeListener(listener: OnChangeListener) {
		changeListeners += listener
	}

	fun addOnSliderTouchListener(listener: OnTouchListener) {
		touchListeners += listener
	}

	/** Sets [newValue] snapped to [stepSize]; not reported as a user change. */
	fun setValueRounded(newValue: Float) {
		value = snap(newValue)
	}

	override fun setEnabled(enabled: Boolean) {
		super.setEnabled(enabled)
		invalidate()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		applyColors()
	}

	override fun onDetachedFromWindow() {
		swellAnimator?.cancel()
		super.onDetachedFromWindow()
	}

	private fun applyColors() {
		// The same colours the novel bar takes from the theme: Bar.surfaceVariant / textPrimary / surface
		rail.color = context.getThemeColor(materialR.attr.colorSurfaceContainerHigh)
		fill.color = context.getThemeColor(materialR.attr.colorOnSurface)
		dots.color = context.getThemeColor(materialR.attr.colorSurfaceContainer)
		halo.color = ColorUtils.setAlphaComponent(fill.color, (0.35f * 255).roundToInt())
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val minHeight = (40 * density).roundToInt() + paddingTop + paddingBottom
		val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
			MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
			MeasureSpec.AT_MOST -> min(minHeight, MeasureSpec.getSize(heightMeasureSpec))
			else -> minHeight
		}
		val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
			MeasureSpec.UNSPECIFIED -> (120 * density).roundToInt()
			else -> MeasureSpec.getSize(widthMeasureSpec)
		}
		setMeasuredDimension(width, height)
	}

	override fun onDraw(canvas: Canvas) {
		val left = paddingLeft.toFloat()
		val width = trackWidth.toFloat()
		if (width <= 0f) return
		val centerY = paddingTop + (height - paddingTop - paddingBottom) / 2f
		val railH = (16f + 6f * swell) * density
		val top = centerY - railH / 2f
		val radius = railH / 2f
		val save = canvas.save()
		if (layoutDirection == LAYOUT_DIRECTION_RTL) {
			canvas.scale(-1f, 1f, this.width / 2f, centerY)
		}
		rail.alpha = if (isEnabled) 255 else 150
		rect.set(left, top, left + width, top + railH)
		canvas.drawRoundRect(rect, radius, radius, rail)

		val range = valueTo - valueFrom
		val fraction = if (isEnabled && range > 0f) ((value - valueFrom) / range).coerceIn(0f, 1f) else 0f
		if (fraction > 0.002f) {
			val fillW = max(width * fraction, railH)
			rect.set(left, top, left + fillW, top + railH)
			canvas.drawRoundRect(rect, radius, radius, fill)

			val dotR = 1.9f * density
			val pitch = 9f * density
			val clip = canvas.save()
			canvas.clipRect(left, top, left + fillW, top + railH)
			var x = left + 6f * density + dotR
			while (x < left + fillW) {
				canvas.drawCircle(x, centerY, dotR, dots)
				x += pitch
			}
			canvas.restoreToCount(clip)

			val markW = 3f * density
			val markX = (left + width * fraction - markW / 2f).coerceIn(left, left + width - markW)
			rect.set(markX - 2f * density, centerY - 14f * density, markX + markW + 2f * density, centerY + 14f * density)
			canvas.drawRoundRect(rect, 3.5f * density, 3.5f * density, halo)
			rect.set(markX, centerY - 12f * density, markX + markW, centerY + 12f * density)
			canvas.drawRoundRect(rect, markW / 2f, markW / 2f, fill)
		}
		canvas.restoreToCount(save)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (!isEnabled) return false
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				parent?.requestDisallowInterceptTouchEvent(true)
				isTracking = true
				animateSwell(1f)
				touchListeners.forEach { it.onStartTrackingTouch(this) }
				updateFromTouch(event.x)
			}

			MotionEvent.ACTION_MOVE -> updateFromTouch(event.x)

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if (event.actionMasked == MotionEvent.ACTION_UP) {
					updateFromTouch(event.x)
				}
				finishTracking()
			}
		}
		return true
	}

	private fun finishTracking() {
		if (!isTracking) return
		isTracking = false
		animateSwell(0f)
		touchListeners.forEach { it.onStopTrackingTouch(this) }
	}

	override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
		super.onWindowFocusChanged(hasWindowFocus)
		if (!hasWindowFocus) finishTracking()
	}

	private fun updateFromTouch(x: Float) {
		val width = trackWidth
		if (width <= 0) return
		var fraction = ((x - paddingLeft) / width).coerceIn(0f, 1f)
		if (layoutDirection == LAYOUT_DIRECTION_RTL) fraction = 1f - fraction
		dispatchUserValue(snap(valueFrom + fraction * (valueTo - valueFrom)))
	}

	private fun dispatchUserValue(newValue: Float) {
		val coerced = newValue.coerceIn(valueFrom, valueTo)
		if (coerced == value) return
		value = coerced
		changeListeners.forEach { it.onValueChange(this, coerced, true) }
	}

	private fun snap(raw: Float): Float {
		val step = stepSize
		val snapped = if (step <= 0f) raw else ((raw - valueFrom) / step).roundToInt() * step + valueFrom
		return snapped.coerceIn(valueFrom, valueTo)
	}

	private fun animateSwell(target: Float) {
		swellAnimator?.cancel()
		swellAnimator = ValueAnimator.ofFloat(swell, target).apply {
			duration = 160L
			addUpdateListener {
				swell = it.animatedValue as Float
				invalidate()
			}
			start()
		}
	}

	override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
		super.onInitializeAccessibilityNodeInfo(info)
		info.className = "android.widget.SeekBar"
		if (isEnabled) {
			info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
			info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
		}
	}

	override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
		val step = if (stepSize > 0f) stepSize else max(1f, (valueTo - valueFrom) / 20f)
		return when (action) {
			AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
				dispatchUserValue(value + step)
				true
			}

			AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
				dispatchUserValue(value - step)
				true
			}

			else -> super.performAccessibilityAction(action, arguments)
		}
	}
}
