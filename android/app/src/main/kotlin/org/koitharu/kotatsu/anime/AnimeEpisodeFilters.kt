package org.koitharu.kotatsu.anime

import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.util.concurrent.ConcurrentHashMap

/**
 * What the watch page needs to filter an episode list by season and by Sub / Dub. Extensions have no fields
 * for either, so both are read from what they do give: the episode name ("S2 E5: Title", "Season 2 Episode 5",
 * "2x05") for the season, the scanlator ("Sub", "Dubbed", "Sub & Dub") for the audio.
 */
object AnimeEpisodeFilters {

	enum class Audio { SUB, DUB, BOTH, UNKNOWN }

	/** Season key of episodes that name no season while others in the same entry do. */
	const val OTHER_SEASON = -1

	private val DUB_WORD = Regex("""\bdub(s|bed)?\b""", RegexOption.IGNORE_CASE)
	private val SUB_WORD = Regex(
		"""\b(sub(s|bed)?|hard\s*-?\s*sub|soft\s*-?\s*sub|[hs]sub)\b""",
		RegexOption.IGNORE_CASE,
	)

	/** "(Dub)" or "[Sub]" in an episode name; a bare word there is too likely to be part of a title. */
	private val TITLE_TAG = Regex("""[\[(]\s*(dub(?:bed)?|sub(?:bed)?)\s*[\])]""", RegexOption.IGNORE_CASE)

	private const val SEASON_EP = """(?:season|s)\s*\.?\s*0*(\d{1,2})\s*[,._|:\-–]*\s*(?:episode|ep|e)\s*\.?\s*0*\d+"""
	private val SEASON_EPISODE = Regex("""\b$SEASON_EP""", RegexOption.IGNORE_CASE)
	private val SEASON_X = Regex("""\b(\d{1,2})x0*\d{1,3}\b""", RegexOption.IGNORE_CASE)
	private val SEASON_PREFIX = Regex("""^\s*$SEASON_EP(?:\s*[:.\-–|]+\s*|\s+|$)""", RegexOption.IGNORE_CASE)

	// --- audio -----------------------------------------------------------------------------------

	/** What an episode's scanlator and name say about its audio. */
	fun audioOfText(scanlator: String?, title: String?): Audio {
		val tag = scanlator.orEmpty()
		var dub = DUB_WORD.containsMatchIn(tag)
		var sub = SUB_WORD.containsMatchIn(tag)
		TITLE_TAG.findAll(title.orEmpty()).forEach {
			if (it.groupValues[1].lowercase().startsWith("dub")) dub = true else sub = true
		}
		return when {
			dub && sub -> Audio.BOTH
			dub -> Audio.DUB
			sub -> Audio.SUB
			else -> Audio.UNKNOWN
		}
	}

	/**
	 * The text's verdict, corrected by what the episode's streams showed when it was played ([learned]: true =
	 * a dub was on offer, false = streams were labelled and none was a dub, null = never seen).
	 */
	fun combine(text: Audio, learned: Boolean?): Audio = when (learned) {
		true -> if (text == Audio.DUB || text == Audio.BOTH) text else Audio.BOTH
		false -> if (text == Audio.DUB || text == Audio.SUB) text else Audio.SUB
		null -> text
	}

	/** What the text says per episode; the list is rebuilt often, so the regexes run once per episode. */
	private val textAudio = ConcurrentHashMap<Long, Audio>()

	fun audioOf(chapter: MangaChapter): Audio {
		val text = textAudio.getOrPut(chapter.id) { audioOfText(chapter.scanlator ?: chapter.branch, chapter.title) }
		return combine(text, AnimeEpisodeMeta.dubSeen[chapter.id])
	}

	/**
	 * Indices of the episodes to list for [preset] ("sub" / "dub"): Dub drops episodes known to have no dub,
	 * Sub drops dub-only duplicates. Episodes that say nothing stay, so an extension with no audio information
	 * is listed in full, as is [keepIndex] (the episode that is playing). Never empty.
	 */
	fun visibleIndices(audios: List<Audio>, preset: String, keepIndex: Int): List<Int> {
		val dub = preset == "dub"
		val keep = audios.indices.filter { i ->
			i == keepIndex || when (audios[i]) {
				Audio.DUB -> dub
				Audio.SUB -> !dub
				else -> true
			}
		}
		return keep.ifEmpty { audios.indices.toList() }
	}

	// --- seasons ---------------------------------------------------------------------------------

	/** Season number named by an episode name, or null. 0 is a specials season. */
	fun seasonOfTitle(title: String?): Int? {
		if (title.isNullOrBlank()) return null
		return (SEASON_EPISODE.find(title) ?: SEASON_X.find(title))?.groupValues?.get(1)?.toIntOrNull()
	}

	fun seasonOf(chapter: MangaChapter): Int? = seasonOfTitle(chapter.title)

	/** The episode name without a leading "S1 E3:" (the season picker already says it); may come out blank. */
	fun stripSeasonPrefix(title: String): String = title.replaceFirst(SEASON_PREFIX, "").trim()

	/**
	 * The seasons to offer: every named one in order, plus [OTHER_SEASON] when some episodes name none. Empty
	 * unless at least two seasons are named, which is how an entry with a single season gets no picker.
	 */
	fun seasonKeys(seasons: List<Int?>): List<Int> {
		val named = seasons.filterNotNull().distinct().sorted()
		if (named.size < 2) return emptyList()
		return if (seasons.any { it == null }) named + OTHER_SEASON else named
	}

	fun keyOf(season: Int?): Int = season ?: OTHER_SEASON

	fun seasonLabel(key: Int): String = when (key) {
		OTHER_SEASON -> "Other"
		0 -> "Specials"
		else -> "Season $key"
	}
}
