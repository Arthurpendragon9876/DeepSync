package org.koitharu.kotatsu.home.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR
import com.google.android.material.materialswitch.MaterialSwitch
import org.koitharu.kotatsu.core.util.ext.getThemeColor

/** The small pieces the Yugen preferences and Home layout sheets are built from, so both look alike. */
internal class HomeSheetUi(val context: Context) {

	private val density = context.resources.displayMetrics.density
	val primary = context.getThemeColor(appcompatR.attr.colorPrimary)
	val onSurface = context.getThemeColor(materialR.attr.colorOnSurface)
	val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)

	fun dp(v: Int) = (v * density).toInt()

	fun text(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
		text = value
		setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
		setTextColor(color)
		if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
	}

	fun sectionTitle(value: CharSequence) = text(value.toString().uppercase(), 12f, onSurfaceVariant, bold = true).apply {
		letterSpacing = 0.08f
		setPadding(dp(24), dp(20), dp(24), dp(4))
	}

	fun hint(value: CharSequence) = text(value, 12f, onSurfaceVariant).apply {
		setPadding(dp(24), 0, dp(24), dp(4))
	}

	/** Title and one-line description stacked, taking the space left over in a row. */
	fun titleBlock(title: CharSequence, subtitle: CharSequence?, subtitleColor: Int = onSurfaceVariant) =
		LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
			addView(text(title, 16f, onSurface))
			if (subtitle != null) addView(text(subtitle, 12f, subtitleColor).apply { setPadding(0, dp(2), 0, 0) })
		}

	/** A row with a switch on the right. The whole row toggles it; [onChange] gets the new state. */
	fun switchRow(title: CharSequence, subtitle: CharSequence?, checked: Boolean, onChange: (Boolean) -> Unit): View {
		val toggle = MaterialSwitch(context).apply {
			isChecked = checked
			setOnCheckedChangeListener { _, on -> onChange(on) }
		}
		return LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(24), dp(10), dp(24), dp(10))
			addView(titleBlock(title, subtitle))
			addView(toggle)
			isClickable = true
			isFocusable = true
			setOnClickListener { toggle.toggle() }
		}
	}

	/** A row that opens something. [trailing] is a short label on the right ("Configure ›"). */
	fun navRow(title: CharSequence, subtitle: CharSequence?, trailing: CharSequence?, onClick: () -> Unit): View =
		LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(24), dp(12), dp(24), dp(12))
			addView(titleBlock(title, subtitle))
			if (trailing != null) addView(text(trailing, 14f, onSurfaceVariant))
			isClickable = true
			isFocusable = true
			setOnClickListener { onClick() }
		}
}
