package org.koitharu.kotatsu.anime.player

import android.content.Context
import org.koitharu.kotatsu.anime.AnimeStream
import kotlin.math.abs

/**
 * Decides which stream to play without asking. Returns every stream index, best candidate first, so the
 * caller can fall through to the next one when a server turns out to be dead.
 *
 * Order: the server named in [preferTitle] (the remembered one, or the one the previous episode used) when
 * this episode offers it, then the quality set in Settings > Player, then the extension's own order.
 */
object AnimeStreamAutoPicker {

	private val HEIGHT_P = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)
	private val SIZE_WXH = Regex("""\d{3,4}\s*[x×]\s*(\d{3,4})""", RegexOption.IGNORE_CASE)

	/** Vertical resolution parsed from the stream's title ("1080p", "1920x1080"), or null when it names none. */
	fun heightOf(stream: AnimeStream): Int? {
		val title = stream.video.videoTitle
		return HEIGHT_P.find(title)?.groupValues?.get(1)?.toIntOrNull()
			?: SIZE_WXH.find(title)?.groupValues?.get(1)?.toIntOrNull()
	}

	fun order(context: Context, streams: List<AnimeStream>, preferTitle: String?): List<Int> =
		order(streams, preferTitle, AnimePlayerPrefs.preferredQuality(context))

	fun order(streams: List<AnimeStream>, preferTitle: String?, quality: String): List<Int> {
		if (streams.isEmpty()) return emptyList()
		val indices = streams.indices.toList()
		val heights = streams.map { heightOf(it) }
		val ranked: List<Int> = when (quality) {
			AnimePlayerPrefs.QUALITY_LOWEST ->
				// unknown heights go last so "lowest" never lands on an unlabeled server by accident
				indices.sortedWith(compareBy<Int> { heights[it] == null }.thenBy { heights[it] ?: Int.MAX_VALUE })
			AnimePlayerPrefs.QUALITY_BEST ->
				indices.sortedWith(compareBy<Int> { heights[it] == null }.thenByDescending { heights[it] ?: 0 })
			else -> {
				val target = quality.toIntOrNull() ?: 1080
				// exact match first, then the nearest lower resolutions, then higher ones, unknown last
				indices.sortedWith(
					compareBy<Int> { heights[it] == null }
						.thenBy { i -> heights[i]?.let { h -> if (h == target) 0 else if (h < target) 1 else 2 } ?: 3 }
						.thenBy { i -> heights[i]?.let { h -> abs(h - target) } ?: 0 },
				)
			}
		}
		val preferred = preferTitle?.let { t -> indices.firstOrNull { streams[it].video.videoTitle == t } }
		return if (preferred == null) ranked else listOf(preferred) + ranked.filter { it != preferred }
	}
}
