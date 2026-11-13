package org.koitharu.kotatsu.core.ui.pulse

import android.animation.ValueAnimator
import android.app.Activity
import android.os.SystemClock
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.AbsSeekBar
import android.widget.EditText
import android.webkit.WebView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.NestedScrollView
import com.google.android.material.slider.Slider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max

private const val MIN_HOLD_MS = 70L
private const val MIN_SCALE = 0.86f
private const val MAX_SCALE = 0.985f
private const val DEPTH_DP = 4f

/**
 * App-wide press feedback for the View side. Instead of the circular ripple, the tapped control
 * dips and springs back with a small overshoot (a pulse). It hooks each activity's window once and
 * only *observes* touches, so no screen needs its own listener and nothing is consumed.
 *
 * Ripples themselves are switched off in the theme (`selectableItemBackground*`, button
 * `rippleColor`), which leaves this as the only press feedback.
 */
class PulseTouchCallbacks @Inject constructor() : DefaultActivityLifecycleCallbacks {

	override fun onActivityStarted(activity: Activity) {
		val window = activity.window ?: return
		if (window.callback is PulseWindowCallback) return
		window.callback = PulseWindowCallback(window.callback ?: return, activity)
	}
}

private class PulseWindowCallback(
	private val d: Window.Callback,
	private val activity: Activity,
) : Window.Callback {

	private val slop = ViewConfiguration.get(activity).scaledTouchSlop
	private val density = activity.resources.displayMetrics.density
	private val loc = IntArray(2)
	private var target: View? = null
	private var downX = 0f
	private var downY = 0f
	private var downTime = 0L

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		try {
			observe(event)
		} catch (_: Throwable) {
			// press feedback is decoration; never let it break a touch
		}
		return d.dispatchTouchEvent(event)
	}

	private fun observe(event: MotionEvent) {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				release()
				if (ValueAnimator.getDurationScale() == 0f) return
				val decor = activity.window?.decorView ?: return
				val hit = find(decor, event.rawX, event.rawY) ?: return
				if (hit.getTag(R.id.selection_spring) != null) return
				target = hit
				downX = event.rawX
				downY = event.rawY
				downTime = SystemClock.uptimeMillis()
				springOf(hit).press(hit, density)
			}

			MotionEvent.ACTION_MOVE -> {
				if (target != null && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) release()
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
		}
	}

	private fun release() {
		val view = target ?: return
		target = null
		val wait = (MIN_HOLD_MS - (SystemClock.uptimeMillis() - downTime)).coerceAtLeast(0L)
		if (wait == 0L) springOf(view).release() else view.postDelayed({ springOf(view).release() }, wait)
	}

	private fun springOf(view: View): PulseSpring =
		view.getTag(R.id.pulse_spring) as? PulseSpring ?: PulseSpring(view).also { view.setTag(R.id.pulse_spring, it) }

	/** Deepest enabled, clickable view under the finger, or null. Sliders, text fields and scrollers are left alone. */
	private fun find(v: View, x: Float, y: Float): View? {
		if (v.visibility != View.VISIBLE || v.alpha == 0f) return null
		v.getLocationOnScreen(loc)
		val w = v.width * v.scaleX
		val h = v.height * v.scaleY
		if (x < loc[0] || y < loc[1] || x > loc[0] + w || y > loc[1] + h) return null
		if (v is AbsSeekBar || v is Slider || v is EditText || v is WebView) return null
		if (v is ViewGroup) {
			for (i in v.childCount - 1 downTo 0) {
				val hit = find(v.getChildAt(i), x, y)
				if (hit != null) return hit
			}
		}
		if (v is RecyclerView || v is NestedScrollView || v.id == android.R.id.content) return null
		if (!(v.isClickable || v.isLongClickable) || !v.isEnabled) return null
		// A full-screen scrim or overlay is not a button
		val screen = v.resources.displayMetrics
		if (v.width * v.height > 0.6f * screen.widthPixels * screen.heightPixels) return null
		return v
	}

	// ---- plain delegation ----------------------------------------------------------------------

	override fun dispatchKeyEvent(event: KeyEvent): Boolean = d.dispatchKeyEvent(event)
	override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = d.dispatchKeyShortcutEvent(event)
	override fun dispatchTrackballEvent(event: MotionEvent): Boolean = d.dispatchTrackballEvent(event)
	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = d.dispatchGenericMotionEvent(event)
	override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
		d.dispatchPopulateAccessibilityEvent(event)

	override fun onCreatePanelView(featureId: Int): View? = d.onCreatePanelView(featureId)
	override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = d.onCreatePanelMenu(featureId, menu)
	override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = d.onPreparePanel(featureId, view, menu)
	override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = d.onMenuOpened(featureId, menu)
	override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = d.onMenuItemSelected(featureId, item)
	override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams?) = d.onWindowAttributesChanged(attrs)
	override fun onContentChanged() = d.onContentChanged()
	override fun onWindowFocusChanged(hasFocus: Boolean) = d.onWindowFocusChanged(hasFocus)
	override fun onAttachedToWindow() = d.onAttachedToWindow()
	override fun onDetachedFromWindow() = d.onDetachedFromWindow()
	override fun onPanelClosed(featureId: Int, menu: Menu) = d.onPanelClosed(featureId, menu)
	override fun onSearchRequested(): Boolean = d.onSearchRequested()
	override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = d.onSearchRequested(searchEvent)
	override fun onWindowStartingActionMode(callback: ActionMode.Callback?): ActionMode? =
		d.onWindowStartingActionMode(callback)

	override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
		d.onWindowStartingActionMode(callback, type)

	override fun onActionModeStarted(mode: ActionMode?) = d.onActionModeStarted(mode)
	override fun onActionModeFinished(mode: ActionMode?) = d.onActionModeFinished(mode)
	override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
		d.onProvideKeyboardShortcuts(data, menu, deviceId)

	override fun onPointerCaptureChanged(hasCapture: Boolean) = d.onPointerCaptureChanged(hasCapture)
}

/** One spring per view: quick dip on press, soft overshoot on release. Interruptible, so rapid taps flow together. */
private class PulseSpring(private val view: View) : FloatPropertyCompat<View>("pulseScale") {

	private val animation = SpringAnimation(view, this).apply {
		spring = SpringForce(1f)
		minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
	}

	fun press(v: View, density: Float) {
		val longSide = max(v.width, v.height).coerceAtLeast(1)
		val pressed = (1f - 2f * DEPTH_DP * density / longSide).coerceIn(MIN_SCALE, MAX_SCALE)
		// pivot in the middle so the control shrinks into itself
		v.pivotX = v.width / 2f
		v.pivotY = v.height / 2f
		animation.spring.setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY).setStiffness(3800f)
		animation.animateToFinalPosition(pressed)
	}

	fun release() {
		animation.spring.setDampingRatio(0.42f).setStiffness(520f)
		animation.animateToFinalPosition(1f)
	}

	override fun getValue(v: View): Float = v.scaleX

	override fun setValue(v: View, value: Float) {
		v.scaleX = value
		v.scaleY = value
	}
}
