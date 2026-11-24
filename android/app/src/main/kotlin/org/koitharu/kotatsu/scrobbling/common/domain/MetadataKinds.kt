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
	const val MOVIE = "MOVIE"
	const val TV = "TV"
	const val ANIME = "ANIME"

	val bl = setOf("boys love", "boys' love", "boyslove", "bl", "yaoi", "shounen ai", "shonen ai", "danmei")
	val gl = setOf("girls love", "girls' love", "girlslove", "gl", "yuri", "shoujo ai", "shojo ai", "baihe")

	/** [format] is the tracker's label, [country] the ISO country of origin; either may be null. */
	fun kindOf(format: String?, country: String?): String? {
		val f = format?.trim()?.uppercase()?.replace(' ', '_')?.replace('-', '_')
		when (f) {
			"MANHWA" -> return MANHWA
			"MANHUA" -> return MANHUA
			"NOVEL", "LIGHT_NOVEL", "WEB_NOVEL" -> return NOVEL
			"ONE_SHOT" -> return ONE_SHOT
			// Watchable titles: decided before the country, or a Korean drama would read as a manhwa
			"MOVIE" -> return MOVIE
			"TV", "TV_SERIES", "TV_SHOW", "SHOW" -> return TV
			"ANIME", "OVA", "ONA", "SPECIAL", "TV_SPECIAL", "MUSIC_VIDEO" -> return ANIME
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

	private val countryNames = mapOf(
		"united kingdom" to "GB", "uk" to "GB", "great britain" to "GB", "england" to "GB", "scotland" to "GB",
		"united states" to "US", "usa" to "US", "america" to "US", "japan" to "JP", "south korea" to "KR",
		"korea" to "KR", "china" to "CN", "taiwan" to "TW", "hong kong" to "HK", "india" to "IN", "france" to "FR",
		"germany" to "DE", "spain" to "ES", "italy" to "IT", "canada" to "CA", "australia" to "AU",
		"thailand" to "TH", "turkey" to "TR", "russia" to "RU", "brazil" to "BR", "mexico" to "MX",
		"philippines" to "PH", "indonesia" to "ID", "ireland" to "IE", "sweden" to "SE", "denmark" to "DK",
		"norway" to "NO", "new zealand" to "NZ", "malta" to "MT", "greece" to "GR", "poland" to "PL",
		"netherlands" to "NL", "belgium" to "BE", "argentina" to "AR", "south africa" to "ZA", "egypt" to "EG",
	)

	/** ISO-3166 alpha-2 from "United Kingdom", "UK", "GB"...; null when it is not a country. */
	fun isoCountry(label: String?): String? {
		val raw = label?.trim().orEmpty()
		if (raw.isEmpty()) return null
		// A bare two-letter code only counts when written as one ("IN", not the word "in")
		if (raw.length == 2 && raw == raw.uppercase() && raw.all { it.isLetter() }) {
			return if (raw == "UK") "GB" else raw
		}
		return countryNames[raw.lowercase()]
	}

	/** "GB" -> "United Kingdom" */
	fun countryName(iso: String): String =
		java.util.Locale("", iso).getDisplayCountry(java.util.Locale.ENGLISH).ifBlank { iso }

	fun isBoysLove(labels: Collection<String>) = labels.any { clean(it) in bl }

	fun isGirlsLove(labels: Collection<String>) = labels.any { clean(it) in gl }

	private fun clean(label: String) = label.trim().lowercase().replace('’', '\'')
}
