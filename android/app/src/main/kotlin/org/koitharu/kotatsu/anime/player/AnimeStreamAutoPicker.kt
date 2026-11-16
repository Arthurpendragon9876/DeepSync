package org.koitharu.kotatsu.anime.player

import android.content.Context
import org.koitharu.kotatsu.anime.AnimeStream
import kotlin.math.abs

/**
 * Decides which stream to play without asking. Returns every stream index, best candidate first, so the
 * caller can fall through to the next one when a server turns out to be dead.
 *
 * Order: the server named in [preferTitle] (the remembered one, or the one the previous episode used) when
 * this episode offers it, then streams that match the Sub / Dub preset, then the quality set in
 * Settings > Player, then the extension's own order.
 *
 * Sub / Dub: many extensions list Sub and Dub as separate streams instead of one stream with two audio
 * tracks. The preset therefore also ranks streams ([variantOf]); when a stream does carry both audios the
 * player switches track inside it (AnimePlayerActivity.applyAudioPreset). Everything here is string work on
 * already-fetched titles, so it costs nothing and never touches the network.
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

	enum class Variant { SUB, DUB, DUAL, RAW, UNKNOWN }

	private val DUAL_RE = Regex(
		"""dual[\s-]*audio|multi[\s-]*audio|\bdual\b|\bsub\s*[/&+]\s*dub|\bdub\s*[/&+]\s*sub""",
		RegexOption.IGNORE_CASE,
	)
	private val DUB_RE = Regex(
		"""\b(dub(s|bed)?|(english|eng|en)\s+(audio|dub))\b""",
		RegexOption.IGNORE_CASE,
	)
	private val SUB_RE = Regex(
		"""\b(sub(s|bed|titled?)?|hard\s*-?\s*sub|soft\s*-?\s*sub|[hs]sub|jpn|japanese)\b""",
		RegexOption.IGNORE_CASE,
	)
	private val RAW_RE = Regex("""\braw\b""", RegexOption.IGNORE_CASE)
	private val ENGLISH = setOf("en", "eng", "english")
	private val JAPANESE = setOf("ja", "jp", "jpn", "japanese")

	private fun isLang(lang: String?, set: Set<String>): Boolean {
		val l = lang?.trim()?.lowercase() ?: return false
		return l in set || set.any { l.startsWith("${it}-") || l.startsWith("${it}_") }
	}

	/** What audio a stream offers, from its title and its separate audio tracks. Cheap: no network, no probing. */
	fun variantOf(stream: AnimeStream): Variant {
		val title = stream.video.videoTitle
		if (DUAL_RE.containsMatchIn(title)) return Variant.DUAL
		val dub = DUB_RE.containsMatchIn(title)
		val sub = SUB_RE.containsMatchIn(title)
		if (dub && sub) return Variant.DUAL
		if (dub) return Variant.DUB
		if (sub) return Variant.SUB
		val langs = stream.video.audioTracks.map { it.lang }
		val hasEn = langs.any { isLang(it, ENGLISH) }
		val hasJa = langs.any { isLang(it, JAPANESE) }
		return when {
			hasEn && hasJa -> Variant.DUAL
			hasEn -> Variant.DUB
			hasJa -> Variant.SUB
			RAW_RE.containsMatchIn(title) -> Variant.RAW
			else -> Variant.UNKNOWN
		}
	}

	/** 0 = exactly what the preset asks for, 1 = can switch inside the stream, 2 = unlabeled, 3 = the other one. */
	fun variantScore(stream: AnimeStream, preset: String): Int = when (variantOf(stream)) {
		Variant.DUAL -> 1
		Variant.UNKNOWN -> 2
		Variant.DUB -> if (preset == "dub") 0 else 3
		Variant.SUB -> if (preset == "dub") 3 else 0
		Variant.RAW -> if (preset == "dub") 3 else 1
	}

	fun order(context: Context, streams: List<AnimeStream>, preferTitle: String?, preferStrict: Boolean = false): List<Int> =
		order(
			streams, preferTitle, AnimePlayerPrefs.preferredQuality(context),
			AnimePlaybackSession.audioPreset(context), preferStrict,
		)

	/**
	 * @param preset "sub" / "dub", or null to ignore the audio variant entirely.
	 * @param preferStrict the [preferTitle] is an explicit "Remember server" pick, so it wins even when its
	 * variant is the opposite of [preset]; otherwise a conflicting one only stays first when nothing matches.
	 */
	fun order(
		streams: List<AnimeStream>,
		preferTitle: String?,
		quality: String,
		preset: String? = null,
		preferStrict: Boolean = false,
	): List<Int> {
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
		// sortedBy is stable, so quality order is kept inside each variant group
		val scores = preset?.let { p -> streams.map { variantScore(it, p) } }
		val byVariant = if (scores == null) ranked else ranked.sortedBy { scores[it] }
		var preferred = preferTitle?.let { t -> indices.firstOrNull { streams[it].video.videoTitle == t } }
		if (preferred != null && scores != null && !preferStrict && scores[preferred] == 3 && scores.any { it < 3 }) {
			preferred = null
		}
		return if (preferred == null) byVariant else listOf(preferred) + byVariant.filter { it != preferred }
	}
}
