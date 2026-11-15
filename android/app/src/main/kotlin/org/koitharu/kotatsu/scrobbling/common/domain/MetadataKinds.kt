package org.koitharu.kotatsu.scrobbling.common.domain

/**
 * Normalises the "what kind of title is this" labels trackers use, so a Korean manhwa and a Japanese
 * Boys' Love manga that share a name can be told apart whichever tracker is asked.
 */
object MetadataKinds {

	const val MANGA = "MANGA"
	const val MANHWA = "MANHWA"
	const val MANHUA = "MANHUA"
	const val NOVEL = "NOVEL"
	const val ONE_SHOT = "ONE_SHOT"

	private val bl = setOf("boys love", "boys' love", "boyslove", "bl", "yaoi", "shounen ai", "shonen ai", "danmei")
	private val gl = setOf("girls love", "girls' love", "girlslove", "gl", "yuri", "shoujo ai", "shojo ai", "baihe")

	/** [format] is the tracker's label, [country] the ISO country of origin; either may be null. */
	fun kindOf(format: String?, country: String?): String? {
		val f = format?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_')
		when (f) {
			"MANHWA" -> return MANHWA
			"MANHUA" -> return MANHUA
			"NOVEL", "LIGHT_NOVEL", "WEB_NOVEL" -> return NOVEL
			"ONE_SHOT" -> return ONE_SHOT
		}
		return when (country?.trim()?.uppercase()) {
			"KR" -> MANHWA
			"CN", "TW", "HK" -> MANHUA
			"JP" -> MANGA
			else -> when (f) {
				"MANGA", "DOUJINSHI" -> MANGA
				else -> null
			}
		}
	}

	fun isBoysLove(labels: Collection<String>) = labels.any { clean(it) in bl }

	fun isGirlsLove(labels: Collection<String>) = labels.any { clean(it) in gl }

	private fun clean(label: String) = label.trim().lowercase().replace('’', '\'')
}
