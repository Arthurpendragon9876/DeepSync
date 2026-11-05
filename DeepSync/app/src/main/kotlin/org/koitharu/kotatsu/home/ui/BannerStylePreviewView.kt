package org.koitharu.kotatsu.home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.appcompat.R as appcompatR
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as materialR
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.home.ui.model.HomeBannerStyle

/**
 * A small drawing of what a banner style looks like at the top of Home, used by the style picker so the
 * choice is made by looking rather than by reading.
 */
class BannerStylePreviewView(context: Context, private val style: HomeBannerStyle) : View(context) {

	private val density = resources.displayMetrics.density
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	private val surface = context.getThemeColor(materialR.attr.colorSurfaceContainerHighest, 0xFF2B2930.toInt())
	private val accent = context.getThemeColor(appcompatR.attr.colorPrimary, 0xFF6750A4.toInt())
	private val onSurface = context.getThemeColor(materialR.attr.colorOnSurfaceVariant, 0xFFCAC4D0.toInt())

	private fun dp(v: Float) = v * density

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val w = MeasureSpec.getSize(widthMeasureSpec)
		setMeasuredDimension(w, dp(124f).toInt())
	}

	private fun fill(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
		paint.color = color
		rect.set(l, t, r, b)
		canvas?.drawRoundRect(rect, radius, radius, paint)
	}

	private var canvas: Canvas? = null

	override fun onDraw(canvas: Canvas) {
		this.canvas = canvas
		val w = width.toFloat()
		val h = height.toFloat()
		fill(0f, 0f, w, h, dp(16f), surface)
		val line = ColorUtils.setAlphaComponent(onSurface, 150)
		val strong = ColorUtils.setAlphaComponent(accent, 210)
		val soft = ColorUtils.setAlphaComponent(accent, 90)
		val pad = dp(12f)
		when (style) {
			HomeBannerStyle.PANELS -> {
				// one large panel and two small ones stacked beside it
				val split = w * 0.62f
				fill(pad, pad, split, h - pad, dp(14f), strong)
				fill(split + dp(8f), pad, w - pad, h / 2f - dp(4f), dp(12f), soft)
				fill(split + dp(8f), h / 2f + dp(4f), w - pad, h - pad, dp(12f), soft)
				fill(pad + dp(10f), h - pad - dp(34f), pad + dp(80f), h - pad - dp(26f), dp(4f), 0xCCFFFFFF.toInt())
				fill(pad + dp(10f), h - pad - dp(20f), pad + dp(44f), h - pad - dp(8f), dp(6f), 0xFFFFFFFF.toInt())
			}

			HomeBannerStyle.CARD -> {
				// a wide backdrop with the portrait cover overlapping its corner
				fill(pad, pad, w - pad, h * 0.66f, dp(14f), soft)
				fill(pad + dp(12f), h * 0.30f, pad + dp(62f), h - pad, dp(10f), strong)
				fill(pad + dp(74f), h * 0.74f, pad + dp(150f), h * 0.74f + dp(8f), dp(4f), line)
				fill(pad + dp(74f), h * 0.74f + dp(14f), pad + dp(116f), h * 0.74f + dp(20f), dp(3f), line)
				fill(w / 2f - dp(14f), h - pad + dp(2f) - dp(5f), w / 2f + dp(14f), h - pad + dp(2f), dp(3f), line)
			}

			HomeBannerStyle.SPOTLIGHT -> {
				// a centred portrait with its neighbours peeking in, title and synopsis pill below
				val cw = w * 0.30f
				val cx = w / 2f
				fill(cx - cw / 2f, pad, cx + cw / 2f, h - dp(40f), dp(14f), strong)
				fill(cx - cw / 2f - dp(12f) - cw * 0.8f, pad + dp(10f), cx - cw / 2f - dp(12f), h - dp(48f), dp(12f), soft)
				fill(cx + cw / 2f + dp(12f), pad + dp(10f), cx + cw / 2f + dp(12f) + cw * 0.8f, h - dp(48f), dp(12f), soft)
				fill(cx - cw / 2f, h - dp(32f), cx + cw / 2f - dp(22f), h - dp(26f), dp(3f), line)
				fill(cx - cw / 2f, h - dp(20f), cx + cw / 2f, h - pad + dp(2f), dp(8f), ColorUtils.setAlphaComponent(onSurface, 70))
			}
		}
		this.canvas = null
	}
}
