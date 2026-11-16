package org.koitharu.kotatsu.metadata

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds
import org.koitharu.kotatsu.scrobbling.common.domain.TitleMatcher

/**
 * What the source says about a title, boiled down to the facts that tell same-named titles apart:
 * every name it has, its country of origin (manhwa / manhua / manga) and whether it is Boys' or Girls' Love.
 * "Love Quest" the Korean romance and "Love Quest" the BL manga share a name, nothing else.
 */
data class SourceProfile(
	val names: List<String>,
	/** KR, CN, JP when the source's tags or name say so; null when it does not make it clear. */
	val country: String?,
	/**
	 * A guess from the scripts its names use. Only a tie-breaker: trackers list foreign-language names as
	 * synonyms, so Hangul in a Japanese title's synonyms proves nothing.
	 */
	val scriptCountry: String?,
	val boysLove: Boolean,
	val girlsLove: Boolean,
	/** Whether the source published any tags at all; without them "not BL" is unknown, not a fact. */
	val hasTags: Boolean,
) {

	companion object {

		fun of(manga: Manga): SourceProfile {
			val names = (listOf(manga.title) + manga.altTitles).filter { it.isNotBlank() }.distinct()
			val tags = manga.tags.map { it.title }
			val sourceName = manga.source.name.lowercase()
			val labels = tags.map { it.trim().lowercase() }
			val country = when {
				labels.any { it == "manhwa" || it == "korean" } || "manhwa" in sourceName -> "KR"
				labels.any { it == "manhua" || it == "chinese" || it == "donghua" } || "manhua" in sourceName -> "CN"
				labels.any { it == "manga" || it == "japanese" } -> "JP"
				else -> null
			}
			val script = when {
				manga.title.any(::isHangul) -> "KR"
				manga.title.any(::isKana) -> "JP"
				else -> null
			}
			return SourceProfile(
				names = names,
				country = country,
				scriptCountry = script,
				boysLove = MetadataKinds.isBoysLove(tags),
				girlsLove = MetadataKinds.isGirlsLove(tags),
				hasTags = tags.isNotEmpty(),
			)
		}

		private fun isHangul(c: Char) = c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'

		private fun isKana(c: Char) = c in '\u3040'..'\u30FF'
	}
}

/** A tracker entry that passed the checks, with how well it fits. */
data class RankedMatch(val title: MetadataTitle, val nameScore: Float, val coverage: Float, val score: Float)

/**
 * Picks the tracker entry that really is the source's title. Names alone are not enough: two entries can
 * carry the same name. So every candidate is checked for
 *  - the best name match (romaji, English, native and synonyms against all the source's names),
 *  - how many of the source's names it also knows (a real match confirms the alternative names, a namesake doesn't),
 *  - its kind: a manhwa never matches a manga or manhua, and a BL title never matches a non-BL one.
 */
object MetadataMatcher {

	const val MIN_NAME_SCORE = 0.85f
	private const val NAME_SAME = 0.95f
	private const val MAX_NAMES = 8

	fun rank(profile: SourceProfile, candidates: List<MetadataTitle>): List<RankedMatch> {
		val names = profile.names.take(MAX_NAMES)
		if (names.isEmpty()) return emptyList()
		return candidates.mapNotNull { c ->
			val nameScore = TitleMatcher.bestScore(names, c.names)
			if (nameScore < MIN_NAME_SCORE) return@mapNotNull null
			if (conflicts(profile, c)) return@mapNotNull null
			val known = names.count { n -> c.names.any { TitleMatcher.score(n, it) >= NAME_SAME } }
			val coverage = known.toFloat() / names.size
			var score = nameScore * 0.55f + coverage * 0.30f
			// Agreement on kind is worth more than any naming nicety
			if (profile.country != null && c.country() == profile.country) score += 0.10f
			else if (profile.country == null && profile.scriptCountry != null && c.country() == profile.scriptCountry) score += 0.05f
			if (profile.boysLove && c.isBoysLove) score += 0.05f
			RankedMatch(c, nameScore, coverage, score)
		}.sortedByDescending { it.score }
	}

	fun best(profile: SourceProfile, candidates: List<MetadataTitle>): MetadataTitle? =
		rank(profile, candidates).firstOrNull()?.title

	/** True when the two cannot be the same title whatever their names say. */
	fun conflicts(profile: SourceProfile, c: MetadataTitle): Boolean {
		val country = c.country()
		if (profile.country != null && country != null && profile.country != country) return true
		val labelled = c.genres.isNotEmpty() || c.tags.isNotEmpty()
		// A BL source against a tracker entry that is clearly something else, and the other way round
		if (profile.boysLove && labelled && !c.isBoysLove) return true
		if (!profile.boysLove && profile.hasTags && c.isBoysLove) return true
		if (profile.girlsLove && labelled && !c.isGirlsLove) return true
		if (!profile.girlsLove && profile.hasTags && c.isGirlsLove) return true
		return false
	}

	/** KR / CN / JP from the tracker's own words; Taiwan and Hong Kong count as Chinese. */
	private fun MetadataTitle.country(): String? = when (kind) {
		MetadataKinds.MANHWA -> "KR"
		MetadataKinds.MANHUA -> "CN"
		MetadataKinds.MANGA, MetadataKinds.ONE_SHOT -> if (country == null || country == "JP") "JP" else null
		else -> null
	}
}
