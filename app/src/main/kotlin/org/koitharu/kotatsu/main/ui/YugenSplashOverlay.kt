package org.koitharu.kotatsu.main.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.R

/**
 * Cold-start splash: the Yugen mark with the YUGEN wordmark under it, both popping in on a spring-like
 * overshoot, then the whole overlay fades away to reveal the app.
 *
 * The Android 12+ system splash is a plain dark screen of the same colour (see values-v31/themes.xml),
 * so the hand-over from it to this overlay is seamless.
 */
object YugenSplashOverlay {

	private const val LOGO_POP_MS = 520L
	private const val WORDMARK_DELAY_MS = 170L
	private const val WORDMARK_POP_MS = 460L
	private const val HOLD_MS = 650L
	private const val FADE_OUT_MS = 240L

	fun show(activity: Activity) {
		val decor = activity.window.decorView as? ViewGroup ?: return
		val res = activity.resources
		val density = res.displayMetrics.density
		fun dp(v: Float) = (v * density).toInt()

		val logo = ImageView(activity).apply {
			setImageResource(R.drawable.ic_yugen_logo)
			contentDescription = null
			scaleType = ImageView.ScaleType.FIT_CENTER
			layoutParams = LinearLayout.LayoutParams(dp(132f), dp(118f))
			alpha = 0f
			scaleX = 0.35f
			scaleY = 0.35f
		}
		val wordmark = ImageView(activity).apply {
			setImageResource(R.drawable.ic_yugen_wordmark)
			contentDescription = null
			scaleType = ImageView.ScaleType.FIT_CENTER
			layoutParams = LinearLayout.LayoutParams(dp(176f), dp(33f)).apply { topMargin = dp(22f) }
			alpha = 0f
			scaleX = 0.7f
			scaleY = 0.7f
			translationY = dp(14f).toFloat()
		}
		val column = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			gravity = Gravity.CENTER_HORIZONTAL
			addView(logo)
			addView(wordmark)
		}
		val overlay = FrameLayout(activity).apply {
			setBackgroundColor(ContextCompat.getColor(activity, R.color.yugen_splash_background))
			isClickable = true // swallow touches while the splash is up
			// Slightly above optical centre reads as centred once the wordmark is added underneath.
			addView(
				column,
				FrameLayout.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
					Gravity.CENTER,
				),
			)
			elevation = 1000f
		}
		decor.addView(
			overlay,
			ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
		)

		logo.animate()
			.alpha(1f).scaleX(1f).scaleY(1f)
			.setDuration(LOGO_POP_MS)
			.setInterpolator(OvershootInterpolator(2.4f))
			.start()
		wordmark.animate()
			.alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
			.setStartDelay(WORDMARK_DELAY_MS)
			.setDuration(WORDMARK_POP_MS)
			.setInterpolator(OvershootInterpolator(2.0f))
			.start()

		overlay.postDelayed({
			overlay.animate()
				.alpha(0f)
				.setDuration(FADE_OUT_MS)
				.setInterpolator(DecelerateInterpolator())
				.withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
				.start()
		}, WORDMARK_DELAY_MS + WORDMARK_POP_MS + HOLD_MS)
	}
}
