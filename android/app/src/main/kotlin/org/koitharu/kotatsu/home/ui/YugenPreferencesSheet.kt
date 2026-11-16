package org.koitharu.kotatsu.home.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor

/**
 * Yugen preferences: opened by tapping the YUGEN wordmark on Home. Holds what used to sit in Home's
 * three-dots menu (filters, metadata) plus Incognito mode and the Home layout (banner and carousels).
 */
object YugenPreferencesSheet {

	class State(
		val incognito: Boolean,
		val filterCount: Int,
		/** Name of the metadata provider in use ("AniList", "Sources"...). */
		val metadataName: String,
		/** "Panels · 9 carousels on". */
		val layoutSummary: String,
	)

	fun show(
		context: Context,
		state: State,
		onIncognito: (Boolean) -> Unit,
		onFilters: () -> Unit,
		onMetadata: () -> Unit,
		onLayout: () -> Unit,
	) {
		val ui = HomeSheetUi(context)
		val dialog = BottomSheetDialog(context)
		val column = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(28))
		}
		column.addView(
			ui.text(context.getString(R.string.ds_yugen_prefs).uppercase(), 12f, ui.onSurfaceVariant, bold = true).apply {
				letterSpacing = 0.08f
				setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
			},
		)

		fun card(
			@DrawableRes icon: Int,
			accent: Int,
			title: String,
			subtitle: String,
			subtitleColor: Int = ui.onSurfaceVariant,
			trailing: android.view.View?,
			onClick: () -> Unit,
		) {
			val tile = ImageView(context).apply {
				setImageResource(icon)
				setColorFilter(accent)
				setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
				background = GradientDrawable().apply {
					cornerRadius = ui.dp(12).toFloat()
					setColor(ColorUtils.setAlphaComponent(accent, 38))
				}
				layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginEnd = ui.dp(14) }
			}
			val row = LinearLayout(context).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.CENTER_VERTICAL
				setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
				background = GradientDrawable().apply {
					cornerRadius = ui.dp(18).toFloat()
					setColor(context.getThemeColor(materialR.attr.colorSurfaceContainerHigh))
				}
				addView(tile)
				addView(ui.titleBlock(title, subtitle, subtitleColor))
				if (trailing != null) addView(trailing)
				isClickable = true
				isFocusable = true
				setOnClickListener { onClick() }
			}
			column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
		}

		// Incognito mode: flips right here, the sheet stays open
		val incognitoSwitch = MaterialSwitch(context).apply {
			isChecked = state.incognito
			setOnCheckedChangeListener { _, on -> onIncognito(on) }
		}
		card(
			icon = R.drawable.ic_incognito,
			accent = 0xFFC084FC.toInt(),
			title = context.getString(R.string.incognito_mode),
			subtitle = context.getString(R.string.ds_prefs_incognito_desc),
			trailing = incognitoSwitch,
			onClick = { incognitoSwitch.toggle() },
		)

		card(
			icon = R.drawable.ic_home_filter,
			accent = 0xFF60A5FA.toInt(),
			title = context.getString(R.string.ds_prefs_filters),
			subtitle = context.getString(R.string.ds_prefs_filters_desc),
			trailing = if (state.filterCount > 0) {
				ui.text(context.getString(R.string.ds_prefs_filters_active, state.filterCount), 12f, ui.onSurface, bold = true).apply {
					setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
					background = GradientDrawable().apply {
						cornerRadius = ui.dp(8).toFloat()
						setColor(ColorUtils.setAlphaComponent(ui.onSurface, 30))
					}
				}
			} else {
				null
			},
			onClick = { dialog.dismiss(); onFilters() },
		)

		card(
			icon = R.drawable.ic_manga_source,
			accent = 0xFF34D399.toInt(),
			title = context.getString(R.string.ds_prefs_metadata),
			subtitle = context.getString(R.string.ds_prefs_metadata_active, state.metadataName),
			subtitleColor = 0xFF34D399.toInt(),
			trailing = ui.text(context.getString(R.string.ds_prefs_configure) + "  \u203A", 13f, ui.onSurfaceVariant),
			onClick = { dialog.dismiss(); onMetadata() },
		)

		card(
			icon = R.drawable.ic_appearance,
			accent = 0xFFFBBF24.toInt(),
			title = context.getString(R.string.ds_prefs_layout),
			subtitle = state.layoutSummary,
			subtitleColor = 0xFFFBBF24.toInt(),
			trailing = ui.text("\u203A", 20f, ui.onSurfaceVariant),
			onClick = { dialog.dismiss(); onLayout() },
		)

		dialog.setContentView(column, ViewGroup.LayoutParams(-1, -2))
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.show()
	}
}
