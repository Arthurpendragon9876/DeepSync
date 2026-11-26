package org.koitharu.kotatsu.anime

/**
 * Decides whether an anime-family extension (Aniyomi APK or Cloudstream plugin) is a TV & Movies extension
 * rather than an anime one. Pure Kotlin so it can be unit tested without Android.
 *
 * - Cloudstream plugins declare what each provider serves (`TvType`), so that is used as-is and the name is
 *   only a fallback for providers that declare nothing useful.
 * - Aniyomi extensions carry no content type, so a score is built from the extension name, package, source
 *   names and source hosts. A weak word such as "stream" can only add to the score, never decide on its own.
 */
object StreamingClassifier {

	/** Bump when the rules change; installed extensions that were tagged automatically are judged again. */
	const val VERSION = 2

	/** What the extension tells us about itself. */
	class Signals(
		val label: String,
		val pkg: String,
		val sourceNames: List<String> = emptyList(),
		/** Hosts of the sources' base urls, e.g. `example.com`. */
		val hosts: List<String> = emptyList(),
		/** Cloudstream only: for each provider, the names of its `TvType`s. Null for Aniyomi extensions. */
		val providerTypes: List<Set<String>>? = null,
	)

	/** `TvType` names Cloudstream files under Anime. */
	private val ANIME_TYPES = setOf("Anime", "AnimeMovie", "OVA")

	/** `TvType` names Cloudstream files under movies and TV. Torrent, NSFW, Others and Music say nothing. */
	private val VIDEO_TYPES = setOf("Movie", "TvSeries", "Cartoon", "AsianDrama", "Documentary", "Live")

	/** Anime and its derivatives: any of these keeps an extension out of TV & Movies. */
	private val ANIME_WORDS = Regex("anim|donghua|hentai|toon|(^|[^a-z])ani|ani($|[^a-z])")

	/** Words that all but name a movie or TV site (worth 3: enough alone). Matched anywhere in the text. */
	private val STRONG_STEMS = listOf(
		"movie", "cinema", "film", "flix", "drama", "dorama", "pelis", "peliculas", "cuevana", "gnula",
		"rezka", "putlocker", "soap2day", "dopebox", "vidsrc", "lookmovie", "goojara", "serien", "series",
	)

	/** Short words that must stand alone to count, so "tv" does not match inside other words (worth 2). */
	private val MEDIUM_TOKENS = setOf("tv", "serie", "show", "shows", "dizi", "kino", "telenovela", "novela")

	/** Hints that only back up a stronger word (worth 1). */
	private val WEAK_STEMS = listOf("stream", "watch")

	private const val STRONG = 3
	private const val MEDIUM = 2
	private const val WEAK = 1
	private const val THRESHOLD = 3

	private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
	private val NON_ALNUM = Regex("[^a-z0-9]+")

	/** True when the extension should start out tagged TV & Movies. */
	fun isTvMovies(signals: Signals): Boolean {
		signals.providerTypes?.let { types -> fromProviderTypes(types)?.let { return it } }
		return scoreByText(signals)
	}

	/**
	 * Cloudstream: movies and TV win when more of the plugin's providers are video-only than anime. A provider
	 * that lists any anime type counts as anime, and a tie stays anime. Null when no provider says either way.
	 */
	fun fromProviderTypes(providers: List<Set<String>>): Boolean? {
		val anime = providers.count { types -> types.any { it in ANIME_TYPES } }
		val video = providers.count { types -> types.none { it in ANIME_TYPES } && types.any { it in VIDEO_TYPES } }
		if (anime == 0 && video == 0) return null
		return video > anime
	}

	/**
	 * What the first version did: a Cloudstream plugin with no anime provider, or a name containing movie, cinema
	 * or stream. Kept so a tag the user changed by hand before [VERSION] 2 is not taken for an automatic one.
	 */
	fun legacyIsTvMovies(signals: Signals): Boolean {
		signals.providerTypes?.let { types -> return types.none { set -> set.any { it in ANIME_TYPES } } }
		val label = signals.label.substringAfter(':').trim().lowercase()
		val tail = signals.pkg.substringAfterLast('.').lowercase()
		if (ANIME_WORDS.containsMatchIn(label) || ANIME_WORDS.containsMatchIn(tail)) return false
		val old = Regex("movie|cinema|stream")
		return old.containsMatchIn(label) || old.containsMatchIn(tail)
	}

	private fun scoreByText(signals: Signals): Boolean {
		val fields = listOf(
			signals.label.substringAfter(':').trim(),
			signals.pkg.substringAfterLast('.'),
			signals.sourceNames.joinToString(" "),
			signals.hosts.joinToString(" "),
		).filter { it.isNotBlank() }
		// Anime wins over everything: "Anime Movies" is still an anime site.
		if (fields.any { ANIME_WORDS.containsMatchIn(it.lowercase()) }) return false
		var total = 0
		var decisive = false
		for (field in fields) {
			val points = pointsFor(field)
			total += points
			if (points >= MEDIUM) decisive = true
		}
		return decisive && total >= THRESHOLD
	}

	private fun pointsFor(field: String): Int {
		val text = field.lowercase()
		val tokens = field.replace(CAMEL_BOUNDARY, "$1 $2").lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }
		return when {
			STRONG_STEMS.any { it in text } -> STRONG
			tokens.any { it in MEDIUM_TOKENS } -> MEDIUM
			WEAK_STEMS.any { it in text } -> WEAK
			else -> 0
		}
	}

	/** `https://www.example.com/path` to `example.com`; null when [url] has no host. */
	fun hostOf(url: String): String? = url.substringAfter("://", "")
		.substringBefore('/').substringBefore(':').removePrefix("www.")
		.takeIf { it.contains('.') }
}
