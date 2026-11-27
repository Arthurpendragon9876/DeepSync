package org.koitharu.kotatsu.backup.foreign

/**
 * Identity, source and tracker rules shared by [ForeignBackupConverter]. Deliberately free of Android and
 * serialization dependencies so they can be unit-tested on the JVM.
 *
 * The three apps are Kotatsu forks, so a `.bk.zip` has the same layout in all of them (one JSON array per
 * section). What differs, and what this file captures, is how each app *identifies* a title:
 *
 *  - Yugen/DeepSync: `MIHON_<sourceId>` sources, manga id = hash of `source|manga|url` ([deepSyncMangaId]).
 *  - Kototoro: the same `MIHON_<sourceId>` names, but a different id function ([kototoroMangaId]).
 *  - Usagi: Kotatsu parser names (`MANGADEX`, ...) and Kotatsu's own uid ([kotatsuUid]).
 */
object ForeignBackupRules {

	enum class App { KOTOTORO, USAGI }

	private val KOTOTORO_APP_IDS = setOf(
		"org.skepsun.kototoro",
		"org.skepsun.kototoro.debug",
		"org.skepsun.kototoro.nightly",
	)
	private val USAGI_APP_IDS = setOf(
		"org.draken.usagi",
		"org.draken.usagi.debug",
	)

	const val MIHON_PREFIX = "MIHON_"

	/** Which supported app wrote a backup, from the `app_id` of its index entry. */
	fun detectApp(appId: String?): App? = when (appId) {
		null -> null
		in KOTOTORO_APP_IDS -> App.KOTOTORO
		in USAGI_APP_IDS -> App.USAGI
		else -> null
	}

	/** `MIHON_123456` -> 123456; null for anything that is not a Mihon source name. */
	fun mihonSourceId(sourceName: String): Long? {
		if (!sourceName.startsWith(MIHON_PREFIX)) return null
		return sourceName.removePrefix(MIHON_PREFIX).substringBefore(':').toLongOrNull()
	}

	// ---- ids ----------------------------------------------------------------------------------------------

	/** Mirrors `mihonMangaId` in `MihonDataConverters.kt` (kept here so it can be tested without Android). */
	fun deepSyncMangaId(sourceName: String, url: String): Long =
		"$sourceName|manga|$url".hashCode().toLong() and Long.MAX_VALUE

	/** Mirrors Kototoro's `MihonModelRules.contentId`. */
	fun kototoroMangaId(sourceName: String, url: String, title: String): Long {
		val identity = url.ifBlank { title.ifBlank { "unknown" } }
		var hash = 1125899906842597L
		for (character in "$sourceName|manga|$identity") hash = 31 * hash + character.code
		return hash and Long.MAX_VALUE
	}

	/** Mirrors Kototoro's `MihonModelRules.chapterId`; `parentUrl` is the manga url the live adapter passes. */
	fun kototoroChapterId(sourceName: String, url: String, parentUrl: String?): Long {
		val identity = if (parentUrl == null) "$sourceName|chapter|$url" else "$sourceName|chapter|$parentUrl|$url"
		return identity.hashCode().toLong() and Long.MAX_VALUE
	}

	/** Mirrors Kototoro's `MihonModelRules.pageId` for a page of the chapter with the given (Kototoro) id. */
	fun kototoroPageId(chapterId: Long, index: Int): Long =
		"$chapterId|page|$index".hashCode().toLong() and Long.MAX_VALUE

	/**
	 * Kotatsu's `generateUid`: seeded polynomial hash over the source name, then the url. Not part of the
	 * uploaded sources (it lives in the parsers library), so this follows the published algorithm. The id is only
	 * a primary key in the target database; the embedded source + url stay authoritative.
	 */
	fun kotatsuUid(sourceName: String, url: String): Long {
		var h = 1125899906842597L
		for (c in sourceName) h = 31 * h + c.code
		for (c in url) h = 31 * h + c.code
		return h
	}

	// ---- trackers -----------------------------------------------------------------------------------------

	// Yugen/DeepSync ids: SHIKIMORI 1, ANILIST 2, MAL 3, KITSU 4, MANGABAKA 5, SIMKL 6.
	// Kototoro adds BANGUMI 5 and MANGAUPDATES 6, which moves SIMKL to 7 and MANGABAKA to 8.
	// Usagi only has 1..4.

	/** Yugen tracker id -> the id the other app uses, or null when it has no such tracker. */
	fun scrobblerToForeign(app: App, id: Int): Int? = when (id) {
		1, 2, 3, 4 -> id
		5 -> if (app == App.KOTOTORO) 8 else null
		6 -> if (app == App.KOTOTORO) 7 else null
		else -> null
	}

	/** Other app's tracker id -> Yugen's, or null when Yugen has no such tracker. */
	fun scrobblerFromForeign(app: App, id: Int): Int? = when (id) {
		1, 2, 3, 4 -> id
		7 -> if (app == App.KOTOTORO) 6 else null
		8 -> if (app == App.KOTOTORO) 5 else null
		else -> null
	}

	// ---- sources ------------------------------------------------------------------------------------------

	private val UNUSABLE_PREFIXES = listOf(
		"JSON_", "CLOUDSTREAM_", "ANIYOMI_", "ANIME_", "TSUNDOKU_", "JAR_", "EXTERNAL_", "content:", "LOCAL", "UNKNOWN", "TEST",
	)

	/**
	 * Whether a title from a Kototoro/Usagi backup can live in Yugen. `MIHON_` titles are re-keyed to Yugen's ids;
	 * everything else that looks like a Kotatsu parser name is kept as is, because the restore that follows runs
	 * Yugen's Kotatsu migration, which moves such titles onto the matching Mihon extension. Sources that only the
	 * other app can run (JSON/Cloudstream/local/native plugins) are skipped rather than restored as dead entries.
	 */
	fun isImportableSource(sourceName: String): Boolean =
		sourceName.isNotBlank() && UNUSABLE_PREFIXES.none { sourceName.startsWith(it) }
}
