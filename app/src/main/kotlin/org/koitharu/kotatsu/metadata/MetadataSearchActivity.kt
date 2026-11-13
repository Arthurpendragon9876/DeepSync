package org.koitharu.kotatsu.metadata

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.R as appcompatR
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.home.ui.model.HomeMetadata
import org.koitharu.kotatsu.home.ui.model.HomeMode
import org.koitharu.kotatsu.image.ui.CoverImageView
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import javax.inject.Inject

/**
 * Searches one tracker's catalogue (AniList, MyAnimeList or MangaBaka) for titles of the current mode and
 * shows them as a poster grid in a bottom sheet over Home, the way Zangetsu's search looks. Home's search bar
 * opens it when a tracker is the metadata source, so Home searches that tracker and nothing else.
 */
@AndroidEntryPoint
class MetadataSearchActivity : AppCompatActivity() {

	@Inject lateinit var metadataRepository: MetadataRepository
	@Inject lateinit var opener: MetadataOpener
	@Inject lateinit var settings: AppSettings

	private val mode by lazy { settings.homeMode }
	private val lockProvider by lazy { intent.getBooleanExtra(EXTRA_LOCK, false) }
	private lateinit var dialog: BottomSheetDialog
	private val density get() = resources.displayMetrics.density
	private fun dp(v: Int) = (v * density).toInt()
	private val primary by lazy { getThemeColor(appcompatR.attr.colorPrimary) }
	private val onSurface by lazy { getThemeColor(materialR.attr.colorOnSurface) }
	private val onSurfaceVariant by lazy { getThemeColor(materialR.attr.colorOnSurfaceVariant) }
	private val container by lazy { getThemeColor(materialR.attr.colorSurfaceContainerHigh) }

	private var provider: HomeMetadata? = null
	private var results: List<MetadataTitle> = emptyList()
	private val adapter = PosterAdapter()
	private lateinit var status: TextView
	private lateinit var progress: ProgressBar
	private lateinit var heading: TextView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val providers = HomeMetadata.values().filter { it != HomeMetadata.SOURCES && metadataRepository.supports(it, mode) }
		provider = settings.homeMetadataProvider.takeIf { it in providers } ?: providers.firstOrNull()
		var queryText = intent.getStringExtra(EXTRA_QUERY).orEmpty()
		val initialQuery = queryText

		val modeName = getString(
			when (mode) {
				HomeMode.ANIME -> R.string.ds_mode_anime
				HomeMode.NOVEL -> R.string.ds_mode_novel
				HomeMode.MANGA -> R.string.ds_mode_manga
			},
		)
		val root = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(8), 0, dp(8))
		}
		heading = TextView(this).apply {
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
			setTextColor(onSurface)
			typeface = Typeface.create(typeface, Typeface.BOLD)
			setPadding(dp(24), dp(12), dp(24), dp(2))
		}
		root.addView(heading)
		root.addView(TextView(this).apply {
			text = modeName
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
			setTextColor(primary)
			setPadding(dp(24), 0, dp(24), dp(8))
		})
		val field = EditText(this).apply {
			setHint(R.string.search)
			setText(initialQuery)
			setSingleLine()
			setTextColor(onSurface)
			setHintTextColor(onSurfaceVariant)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
			setPadding(dp(20), dp(14), dp(20), dp(14))
			imeOptions = EditorInfo.IME_ACTION_SEARCH
			background = android.graphics.drawable.GradientDrawable().apply {
				cornerRadius = dp(24).toFloat()
				setColor(container)
			}
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				setMargins(dp(20), 0, dp(20), dp(8))
			}
			setOnEditorActionListener { v, id, _ ->
				if (id == EditorInfo.IME_ACTION_SEARCH) {
					queryText = v.text.toString()
					search(queryText)
					true
				} else {
					false
				}
			}
		}
		root.addView(field)
		if (!lockProvider && providers.size > 1) {
			val chips = LinearLayout(this).apply {
				orientation = LinearLayout.HORIZONTAL
				setPadding(dp(20), 0, dp(20), dp(8))
			}
			providers.forEach { p ->
				chips.addView(Chip(this).apply {
					setText(p.titleRes)
					isCheckable = true
					isChecked = p == provider
					layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
					setOnClickListener {
						provider = p
						for (i in 0 until chips.childCount) (chips.getChildAt(i) as Chip).isChecked = chips.getChildAt(i) === this
						search(queryText)
					}
				})
			}
			root.addView(chips)
		}
		progress = ProgressBar(this).apply {
			visibility = View.GONE
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
		}
		root.addView(progress)
		status = TextView(this).apply {
			setTextColor(onSurfaceVariant)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
			gravity = Gravity.CENTER
			visibility = View.GONE
			setPadding(dp(24), dp(24), dp(24), dp(24))
		}
		root.addView(status)
		root.addView(
			RecyclerView(this).apply {
				layoutManager = GridLayoutManager(this@MetadataSearchActivity, 3)
				adapter = this@MetadataSearchActivity.adapter
				clipToPadding = false
				setPadding(dp(14), dp(4), dp(14), dp(8))
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(460))
			},
		)

		dialog = BottomSheetDialog(this)
		dialog.setContentView(root)
		dialog.setOnDismissListener { finish() }
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.behavior.skipCollapsed = true
		dialog.show()
		search(queryText)
	}

	private fun search(query: String) {
		val p = provider
		heading.text = p?.let { getString(it.titleRes) } ?: getString(R.string.search)
		if (p == null) {
			showStatus(getString(R.string.ds_no_tracker_for_mode))
			return
		}
		if (query.isBlank()) return
		progress.visibility = View.VISIBLE
		status.visibility = View.GONE
		adapter.submit(emptyList())
		lifecycleScope.launch {
			runCatching {
				metadataRepository.search(p, query.trim(), mode, hideAdult = settings.isNsfwContentDisabled)
			}.onSuccess {
				progress.visibility = View.GONE
				results = it
				adapter.submit(it)
				if (it.isEmpty()) showStatus(getString(R.string.nothing_found))
			}.onFailure {
				progress.visibility = View.GONE
				showStatus(getString(R.string.ds_tracker_search_failed))
			}
		}
	}

	private fun showStatus(message: String) {
		status.text = message
		status.visibility = View.VISIBLE
	}

	private fun open(title: MetadataTitle) {
		val p = provider ?: return
		val ref = MetaRef(p, title.id, mode)
		// Info comes from the tracker: show its page now and let that page find the source.
		if (settings.isMetadataFromTracker) {
			AppRouter(this).openMetaDetails(ref, title.title, title.coverUrl, title.bannerUrl)
			dialog.dismiss()
			return
		}
		lifecycleScope.launch {
			when (val result = opener.resolve(ref, title.names, title.title)) {
				is MetaOpenResult.Open -> AppRouter(this@MetadataSearchActivity).openDetails(result.manga)
				is MetaOpenResult.ChooseSource -> startActivity(
					SourceChooserActivity.intent(this@MetadataSearchActivity, result.query, mode, MetadataLinks.key(ref)),
				)
			}
			dialog.dismiss()
		}
	}

	private inner class PosterAdapter : RecyclerView.Adapter<PosterAdapter.Holder>() {

		private var items: List<MetadataTitle> = emptyList()

		fun submit(list: List<MetadataTitle>) {
			items = list
			notifyDataSetChanged()
		}

		override fun getItemCount() = items.size

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val cover = CoverImageView(parent.context).apply {
				scaleType = ImageView.ScaleType.CENTER_CROP
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
			}
			val title = TextView(parent.context).apply {
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
				setTextColor(onSurface)
				maxLines = 2
				minLines = 2
				setPadding(dp(6), dp(6), dp(6), dp(6))
			}
			val card = MaterialCardView(parent.context).apply {
				layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
					setMargins(dp(5), dp(5), dp(5), dp(5))
				}
				radius = dp(18).toFloat()
				cardElevation = 0f
				strokeWidth = 0
				setCardBackgroundColor(ColorUtils.setAlphaComponent(container, 255))
				isClickable = true
				isFocusable = true
				addView(LinearLayout(parent.context).apply {
					orientation = LinearLayout.VERTICAL
					addView(cover)
					addView(title)
				})
			}
			return Holder(card, cover, title)
		}

		override fun onBindViewHolder(holder: Holder, position: Int) {
			val item = items[position]
			holder.title.text = item.title
			holder.cover.setImageAsync(item.coverUrl)
			holder.card.setOnClickListener { open(item) }
		}

		inner class Holder(val card: MaterialCardView, val cover: CoverImageView, val title: TextView) : RecyclerView.ViewHolder(card)
	}

	companion object {

		private const val EXTRA_QUERY = "query"
		private const val EXTRA_LOCK = "lock_provider"

		fun intent(context: Context, query: String, lockProvider: Boolean = false): Intent =
			Intent(context, MetadataSearchActivity::class.java)
				.putExtra(EXTRA_QUERY, query)
				.putExtra(EXTRA_LOCK, lockProvider)
	}
}
