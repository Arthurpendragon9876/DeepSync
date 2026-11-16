package org.koitharu.kotatsu.anime.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.preference.PreferenceManager

/**
 * Settings > Player. Stored in the same default SharedPreferences file as the rest of the app settings,
 * so the Compose settings screen can bind to the keys with rememberBooleanPref / rememberStringPref.
 */
object AnimePlayerPrefs {

	/** Skip the YouTube-style watch page: episodes open straight in fullscreen landscape. */
	const val KEY_DIRECT_LANDSCAPE = "anime_player_direct_landscape"

	/** Pick the stream (server / quality) automatically instead of asking for every episode. */
	const val KEY_AUTO_PICK = "anime_player_auto_pick"

	/** One of [QUALITY_VALUES]. */
	const val KEY_PREFERRED_QUALITY = "anime_player_preferred_quality"

	const val KEY_AUTOPLAY_NEXT = "anime_player_autoplay_next"

	/** Length of the skip pill ("+85 s"), in seconds. */
	const val KEY_SKIP_SECONDS = "anime_player_skip_seconds"

	// Subtitle look, applied to the player's subtitle view; each holds one of the keys in [SUBTITLE_FONTS] etc.
	const val KEY_SUB_FONT = "anime_player_sub_font"
	const val KEY_SUB_SIZE = "anime_player_sub_size"
	const val KEY_SUB_COLOR = "anime_player_sub_color"
	const val KEY_SUB_EDGE = "anime_player_sub_edge"
	const val KEY_SUB_BACKGROUND = "anime_player_sub_background"
	const val KEY_SUB_POSITION = "anime_player_sub_position"

	const val QUALITY_BEST = "best"
	const val QUALITY_LOWEST = "lowest"
	val QUALITY_VALUES = listOf(QUALITY_BEST, "1080", "720", "480", "360", QUALITY_LOWEST)
	val SKIP_VALUES = listOf("30", "60", "85", "90")

	/** (key, label) pairs. */
	val SUBTITLE_FONTS = listOf(
		"sans" to "Sans-serif", "sans_medium" to "Sans-serif medium", "bold" to "Bold", "serif" to "Serif",
		"mono" to "Monospace", "casual" to "Casual", "cursive" to "Cursive",
	)
	val SUBTITLE_SIZES = listOf("small" to "Small", "normal" to "Normal", "large" to "Large", "huge" to "Huge")
	val SUBTITLE_COLORS = listOf(
		"white" to "White", "yellow" to "Yellow", "cyan" to "Cyan", "green" to "Green", "pink" to "Pink",
	)
	val SUBTITLE_EDGES = listOf("outline" to "Outline", "shadow" to "Drop shadow", "raised" to "Raised", "none" to "None")
	val SUBTITLE_BACKGROUNDS = listOf("none" to "None", "translucent" to "Translucent box", "solid" to "Black box")
	val SUBTITLE_POSITIONS = listOf("low" to "Low", "normal" to "Normal", "high" to "High")

	fun typefaceOf(font: String): Typeface = when (font) {
		"sans_medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
		"bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
		"serif" -> Typeface.SERIF
		"mono" -> Typeface.MONOSPACE
		"casual" -> Typeface.create("casual", Typeface.NORMAL)
		"cursive" -> Typeface.create("cursive", Typeface.NORMAL)
		else -> Typeface.SANS_SERIF
	}

	/** Relative to the player's default subtitle size. */
	fun sizeScale(size: String): Float = when (size) {
		"small" -> 0.95f
		"large" -> 1.45f
		"huge" -> 1.8f
		else -> 1.15f
	}

	fun colorOf(color: String): Int = when (color) {
		"yellow" -> Color.rgb(255, 235, 59)
		"cyan" -> Color.rgb(128, 222, 234)
		"green" -> Color.rgb(165, 214, 167)
		"pink" -> Color.rgb(244, 143, 177)
		else -> Color.WHITE
	}

	fun backgroundOf(background: String): Int = when (background) {
		"translucent" -> Color.argb(160, 0, 0, 0)
		"solid" -> Color.BLACK
		else -> Color.TRANSPARENT
	}

	fun bottomPadding(position: String): Float = when (position) {
		"low" -> 0.03f
		"high" -> 0.14f
		else -> 0.07f
	}

	private fun choice(context: Context, key: String, options: List<Pair<String, String>>, default: String): String =
		prefs(context).getString(key, null)?.takeIf { v -> options.any { it.first == v } } ?: default

	fun subtitleFont(context: Context) = choice(context, KEY_SUB_FONT, SUBTITLE_FONTS, "sans")
	fun subtitleSize(context: Context) = choice(context, KEY_SUB_SIZE, SUBTITLE_SIZES, "normal")
	fun subtitleColor(context: Context) = choice(context, KEY_SUB_COLOR, SUBTITLE_COLORS, "white")
	fun subtitleEdge(context: Context) = choice(context, KEY_SUB_EDGE, SUBTITLE_EDGES, "outline")
	fun subtitleBackground(context: Context) = choice(context, KEY_SUB_BACKGROUND, SUBTITLE_BACKGROUNDS, "none")
	fun subtitlePosition(context: Context) = choice(context, KEY_SUB_POSITION, SUBTITLE_POSITIONS, "normal")

	/** Styles the player's subtitle view from the saved choices (embedded styles are off, so these always win). */
	fun applySubtitleStyle(context: Context, view: SubtitleView) {
		val edge = when (subtitleEdge(context)) {
			"shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
			"raised" -> CaptionStyleCompat.EDGE_TYPE_RAISED
			"none" -> CaptionStyleCompat.EDGE_TYPE_NONE
			else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
		}
		view.setApplyEmbeddedStyles(false)
		view.setApplyEmbeddedFontSizes(false)
		view.setStyle(
			CaptionStyleCompat(
				colorOf(subtitleColor(context)),
				backgroundOf(subtitleBackground(context)),
				Color.TRANSPARENT,
				edge,
				Color.BLACK,
				typefaceOf(subtitleFont(context)),
			),
		)
		view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizeScale(subtitleSize(context)))
		view.setBottomPaddingFraction(bottomPadding(subtitlePosition(context)))
	}

	private fun prefs(context: Context): SharedPreferences =
		PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

	fun isDirectLandscape(context: Context): Boolean = prefs(context).getBoolean(KEY_DIRECT_LANDSCAPE, false)

	/** Default ON: streams are picked for the user unless they turn this off. */
	fun isAutoPick(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_PICK, true)

	fun preferredQuality(context: Context): String =
		prefs(context).getString(KEY_PREFERRED_QUALITY, QUALITY_BEST)?.takeIf { it in QUALITY_VALUES } ?: QUALITY_BEST

	fun isAutoPlayNext(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOPLAY_NEXT, true)

	fun skipSeconds(context: Context): Int =
		prefs(context).getString(KEY_SKIP_SECONDS, "85")?.toIntOrNull()?.takeIf { it > 0 } ?: 85
}
