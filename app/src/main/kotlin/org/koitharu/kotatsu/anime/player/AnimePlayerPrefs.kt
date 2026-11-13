package org.koitharu.kotatsu.anime.player

import android.content.Context
import android.content.SharedPreferences
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

	const val QUALITY_BEST = "best"
	const val QUALITY_LOWEST = "lowest"
	val QUALITY_VALUES = listOf(QUALITY_BEST, "1080", "720", "480", "360", QUALITY_LOWEST)
	val SKIP_VALUES = listOf("30", "60", "85", "90")

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
