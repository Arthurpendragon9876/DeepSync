package org.koitharu.kotatsu.reader.ui.epub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * The pill shown while a chapter is pulled past its end (or start) in Overscroll scroll style:
 * a ring that fills as the pull grows, an arrow that turns into a check once letting go will change
 * chapter, and the title of the chapter it would open.
 */
class ChapterPullPillView(context: Context) : View(context) {

	private val density = resources.displayMetrics.density
	private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 3f * density
	}
	private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 3f * density
		strokeCap = Paint.Cap.ROUND
	}
	private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 2.2f * density
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		textSize = 15f * density
		typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
	}
	private val rect = RectF()
	private val path = Path()

	private var title: String = ""
	private var fitted: String = ""
	private var progress = 0f
	private var forward = true

	private var pillColor = 0xF0141418.toInt()
	private var textColor = 0xFFFFFFFF.toInt()
	private var accentColor = 0xFFFF5A6E.toInt()

	init {
		visibility = GONE
		isClickable = false
		isFocusable = false
	}

	fun setColors(pill: Int, text: Int, accent: Int) {
		pillColor = pill
		textColor = text
		accentColor = accent
		invalidate()
	}

	/** [forward] = the next chapter (pulling up at the end); false = the previous one. */
	fun show(forward: Boolean, title: String, progress: Float) {
		val changedTitle = this.title != title
		this.forward = forward
		this.title = title
		this.progress = progress.coerceIn(0f, 1f)
		if (changedTitle) fitted = ""
		if (visibility != VISIBLE) visibility = VISIBLE
		requestLayout()
		invalidate()
	}

	fun hide() {
		if (visibility != GONE) visibility = GONE
		progress = 0f
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val maxWidth = (MeasureSpec.getSize(widthMeasureSpec) - 32 * density).toInt().coerceAtLeast(0)
		val textMax = (maxWidth - (14 + 36 + 12 + 18) * density).coerceAtLeast(40f * density)
		fitted = TextUtils.ellipsize(title, textPaint, textMax, TextUtils.TruncateAt.END).toString()
		val textWidth = textPaint.measureText(fitted)
		val width = ((14 + 36 + 12 + 18) * density + textWidth).toInt().coerceAtMost(maxWidth)
		setMeasuredDimension(width, (56 * density).toInt())
	}

	override fun onDraw(canvas: Canvas) {
		val w = width.toFloat()
		val h = height.toFloat()
		pillPaint.color = pillColor
		rect.set(0f, 0f, w, h)
		canvas.drawRoundRect(rect, h / 2f, h / 2f, pillPaint)

		val ready = progress >= 1f
		val cx = 14f * density + 18f * density
		val cy = h / 2f
		val r = 15f * density
		ringTrackPaint.color = ColorUtils.setAlphaComponent(textColor, 70)
		canvas.drawCircle(cx, cy, r, ringTrackPaint)
		ringPaint.color = accentColor
		rect.set(cx - r, cy - r, cx + r, cy + r)
		canvas.drawArc(rect, -90f, 360f * progress, false, ringPaint)

		iconPaint.color = if (ready) accentColor else textColor
		path.reset()
		if (ready) {
			// check mark
			path.moveTo(cx - 6f * density, cy + 0.5f * density)
			path.lineTo(cx - 1.5f * density, cy + 5f * density)
			path.lineTo(cx + 6.5f * density, cy - 4.5f * density)
		} else {
			// arrow: down when pulling towards the next chapter, up for the previous one
			val dir = if (forward) 1f else -1f
			path.moveTo(cx, cy - 6f * density * dir)
			path.lineTo(cx, cy + 6f * density * dir)
			path.moveTo(cx - 4.5f * density, cy + 1.5f * density * dir)
			path.lineTo(cx, cy + 6f * density * dir)
			path.lineTo(cx + 4.5f * density, cy + 1.5f * density * dir)
		}
		canvas.drawPath(path, iconPaint)

		textPaint.color = if (ready) accentColor else textColor
		val textX = 14f * density + 36f * density + 12f * density
		val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
		canvas.drawText(fitted, textX, baseline, textPaint)
	}
}
