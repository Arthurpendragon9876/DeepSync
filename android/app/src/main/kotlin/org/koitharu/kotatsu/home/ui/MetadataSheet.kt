package org.koitharu.kotatsu.home.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.R as appcompatR
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode

/**
 * Home's metadata sheet, laid out like Zangetsu's: the tracker that supplies info (one tick), then a
 * "Source catalogue" switch that makes Home list what the installed sources offer instead. With the switch
 * on, a row opens the source picker to choose which source's catalogue Home shows.
 * Sources are metadata here, not a separate setting.
 */
object MetadataSheet {

	class State(
		val mode: HomeMode,
		val providers: List<HomeMetadata>,
		val provider: HomeMetadata,
		val catalogue: Boolean,
		val catalogueSourceTitle: String?,
		/** Covers and info on a title's page come from the tracker (true) or from the source (false). */
		val fromTracker: Boolean = true,
	)

	fun show(
		context: Context,
		state: State,
		onProvider: (HomeMetadata) -> Unit,
		onCatalogue: (Boolean) -> Unit,
		onPickSource: () -> Unit,
		onFromTracker: (Boolean) -> Unit = {},
	) {
		val dialog = BottomSheetDialog(context)
		val density = context.resources.displayMetrics.density
		fun dp(v: Int) = (v * density).toInt()
		val primary = context.getThemeColor(appcompatR.attr.colorPrimary)
		val onSurface = context.getThemeColor(materialR.attr.colorOnSurface)
		val onSurfaceVariant = context.getThemeColor(materialR.attr.colorOnSurfaceVariant)

		val column = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(8), 0, dp(24))
		}

		fun text(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
			text = value
			setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
			setTextColor(color)
			if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
		}

		val modeName = context.getString(
			when (state.mode) {
				HomeMode.ANIME -> R.string.ds_mode_anime
				HomeMode.NOVEL -> R.string.ds_mode_novel
				HomeMode.MANGA -> R.string.ds_mode_manga
			},
		)
		column.addView(
			text(context.getString(R.string.ds_metadata_sheet_title, modeName), 22f, onSurface, bold = true).apply {
				setPadding(dp(24), dp(12), dp(24), 0)
			},
		)
		column.addView(
			text(context.getString(R.string.ds_metadata_sheet_subtitle, modeName.lowercase()), 14f, onSurfaceVariant).apply {
				setPadding(dp(24), dp(2), dp(24), dp(12))
			},
		)

		fun row(
			iconRes: Int,
			title: String,
			subtitle: String?,
			selected: Boolean,
			trailing: View? = null,
			onClick: () -> Unit,
		): View = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(24), dp(14), dp(24), dp(14))
			if (selected) setBackgroundColor(ColorUtils.setAlphaComponent(primary, 40))
			isClickable = true
			isFocusable = true
			setOnClickListener { onClick() }
			addView(
				ImageView(context).apply {
					setImageResource(iconRes)
					layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
				},
			)
			addView(
				LinearLayout(context).apply {
					orientation = LinearLayout.VERTICAL
					layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) }
					addView(text(title, 17f, if (selected) primary else onSurface, bold = true))
					if (subtitle != null) addView(text(subtitle, 14f, onSurfaceVariant))
				},
			)
			when {
				trailing != null -> addView(trailing)
				selected -> addView(
					ImageView(context).apply {
						setImageResource(R.drawable.ic_check)
						setColorFilter(primary)
					},
				)
			}
		}

		state.providers.forEach { provider ->
			column.addView(
				row(
					iconRes = when (provider) {
						HomeMetadata.ANILIST -> R.drawable.ic_anilist
						HomeMetadata.MAL -> R.drawable.ic_mal
						else -> R.drawable.ic_mangabaka
					},
					title = context.getString(provider.titleRes),
					subtitle = context.getString(
						when (provider) {
							HomeMetadata.ANILIST -> R.string.ds_meta_anilist_desc
							HomeMetadata.MAL -> R.string.ds_meta_mal_desc
							else -> R.string.ds_meta_mangabaka_desc
						},
					),
					// While the source catalogue is on, no tracker is the one in use, so none is highlighted
					selected = !state.catalogue && provider == state.provider,
				) {
					onProvider(provider)
					dialog.dismiss()
				},
			)
		}

		// Covers and info: the tracker (AniList / MAL / MangaBaka, whichever the title is tracked on) or the source
		val fromTrackerToggle = MaterialSwitch(context).apply { isChecked = state.fromTracker }
		column.addView(
			row(
				iconRes = R.drawable.ic_images,
				title = context.getString(R.string.ds_cover_from_tracker),
				subtitle = context.getString(R.string.ds_cover_from_tracker_desc),
				selected = false,
				trailing = fromTrackerToggle,
			) {
				fromTrackerToggle.toggle()
			},
		)
		fromTrackerToggle.setOnCheckedChangeListener { _, checked ->
			onFromTracker(checked)
			dialog.dismiss()
		}

		column.addView(
			View(context).apply {
				setBackgroundColor(ColorUtils.setAlphaComponent(onSurfaceVariant, 50))
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
					setMargins(dp(24), dp(8), dp(24), dp(8))
				}
			},
		)

		val toggle = MaterialSwitch(context).apply { isChecked = state.catalogue }
		column.addView(
			row(
				iconRes = R.drawable.ic_manga_source,
				title = context.getString(R.string.ds_source_catalogue),
				subtitle = context.getString(R.string.ds_source_catalogue_desc),
				selected = false,
				trailing = toggle,
			) {
				toggle.toggle()
			},
		)
		toggle.setOnCheckedChangeListener { _, checked ->
			onCatalogue(checked)
			dialog.dismiss()
		}
		if (state.catalogue) {
			column.addView(
				row(
					iconRes = R.drawable.ic_chevron_right,
					title = context.getString(R.string.ds_catalogue_source),
					subtitle = state.catalogueSourceTitle ?: context.getString(R.string.ds_all_sources),
					selected = false,
				) {
					dialog.dismiss()
					onPickSource()
				},
			)
		}

		dialog.setContentView(ScrollView(context).apply { addView(column) })
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.show()
	}
}
