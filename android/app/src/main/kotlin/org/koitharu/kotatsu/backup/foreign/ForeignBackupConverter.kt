package org.koitharu.kotatsu.backup.foreign

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.koitharu.kotatsu.backup.foreign.ForeignBackupRules.App
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Rewrites a Yugen `.bk.zip` into the Kototoro or Usagi flavour of the same format, and the other way round.
 *
 * It works on the JSON of each section rather than on typed models, so it needs no knowledge of the other apps'
 * code: only the id, source and tracker rules in [ForeignBackupRules]. Sections are streamed one element at a
 * time; only a map of the chapter urls the history and bookmarks point at is kept in memory.
 */
@OptIn(ExperimentalSerializationApi::class)
object ForeignBackupConverter {

	/** What a conversion did, so the caller can tell the user how much made it across. */
	class Report(
		val converted: Int,
		val skipped: Int,
		val skippedTitles: List<String>,
	)

	class ImportResult(
		val app: App,
		val report: Report,
		/** Entry names of the sections written, for the restore that follows. */
		val sections: Set<String>,
	)

	class CategoryPlan(val id: Int, val create: Boolean)

	/** Decides which local category a foreign one lands in: an existing one with the same title, or a new id. */
	fun interface CategoryResolver {
		fun resolve(title: String, foreignId: Int): CategoryPlan
	}

	class UnsupportedBackupException(message: String) : IllegalArgumentException(message)

	private class MangaPlan(val newId: Long, val newSource: String, val url: String)

	private val json = Json {
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
	}

	// ---- Yugen -> Kototoro / Usagi -----------------------------------------------------------------------

	/**
	 * @param input a Yugen `.bk.zip`
	 * @param output where the converted zip goes (flushed, not closed)
	 * @param legacyNames Mihon source id -> Kotatsu parser name, needed for [App.USAGI] only
	 */
	fun exportTo(
		target: App,
		input: File,
		output: OutputStream,
		legacyNames: Map<Long, String> = emptyMap(),
	): Report {
		val plans = HashMap<Long, MangaPlan?>()
		val skipped = LinkedHashMap<Long, String>()
		val usedSources = LinkedHashSet<String>()

		fun planFor(manga: JsonObject): MangaPlan? {
			val oldId = manga.long("id") ?: return null
			if (plans.containsKey(oldId)) return plans[oldId]
			val source = manga.str("source")
			val url = manga.str("url")
			val sourceId = source?.let(ForeignBackupRules::mihonSourceId)
			val plan = if (source == null || url == null || sourceId == null) {
				null
			} else when (target) {
				App.KOTOTORO -> MangaPlan(
					ForeignBackupRules.kototoroMangaId(source, url, manga.str("title").orEmpty()),
					source,
					url,
				)

				App.USAGI -> legacyNames[sourceId]?.let { MangaPlan(ForeignBackupRules.kotatsuUid(it, url), it, url) }
			}
			plans[oldId] = plan
			if (plan == null) skipped[oldId] = manga.str("title").orEmpty() else usedSources += plan.newSource
			return plan
		}

		// Chapter ids are a function of the chapter url, which only the `chapters` section knows. Collect the ids
		// the history and bookmarks point at first, then look up just those urls.
		val neededChapters = HashSet<Long>()
		forEachElement(input, "history") { (it as? JsonObject)?.long("chapter_id")?.let(neededChapters::add) }
		forEachElement(input, "bookmarks") { entry ->
			(entry as? JsonObject)?.array("bookmarks")?.forEach { b ->
				(b as? JsonObject)?.long("chapter_id")?.let(neededChapters::add)
			}
		}
		val chapterUrls = HashMap<Long, String>()
		if (neededChapters.isNotEmpty()) {
			forEachElement(input, "chapters") { entry ->
				(entry as? JsonObject)?.array("chapters")?.forEach { c ->
					val chapter = c as? JsonObject ?: return@forEach
					val id = chapter.long("chapter_id") ?: return@forEach
					val url = chapter.str("url")
					if (url != null && id in neededChapters) chapterUrls[id] = url
				}
			}
		}

		fun chapterIdFor(plan: MangaPlan, oldId: Long?): Long? {
			if (oldId == null) return null
			val url = chapterUrls[oldId] ?: return oldId // unknown chapter: keep the id, progress still resumes by percent
			return when (target) {
				App.KOTOTORO -> ForeignBackupRules.kototoroChapterId(plan.newSource, url, plan.url)
				App.USAGI -> ForeignBackupRules.kotatsuUid(plan.newSource, url)
			}
		}

		fun rewriteManga(manga: JsonObject, plan: MangaPlan): JsonObject {
			val oldSource = manga.str("source")
			val tags = manga.array("tags")?.map { tag ->
				val t = tag as? JsonObject
				if (t != null && t.str("source") == oldSource) t.with("source" to JsonPrimitive(plan.newSource)) else tag
			}
			var result = manga.with("id" to JsonPrimitive(plan.newId), "source" to JsonPrimitive(plan.newSource))
			if (tags != null) result = result.with("tags" to JsonArray(tags))
			return result
		}

		val sourceIndex = firstElement(input, "index") as? JsonObject
		val zip = ZipOutputStream(output)

		// The index keeps Yugen's own app id on purpose. Kototoro treats an unknown id as a Kotatsu-style backup and
		// merges it into the library (and only restores the library sections); an id of its own would make it a
		// snapshot that replaces local state.
		zip.writeSection(
			"index",
			listOf(
				JsonObject(
					mapOf(
						"app_id" to JsonPrimitive(sourceIndex?.str("app_id") ?: DEFAULT_APP_ID),
						"app_version" to JsonPrimitive(sourceIndex?.int("app_version") ?: 1),
						"created_at" to JsonPrimitive(sourceIndex?.long("created_at") ?: System.currentTimeMillis()),
					),
				),
			),
		)
		zip.copySection(input, "categories") { it }
		zip.copySection(input, "favourites") { entry ->
			val manga = entry.obj("manga") ?: return@copySection null
			val plan = planFor(manga) ?: return@copySection null
			entry.with("manga_id" to JsonPrimitive(plan.newId), "manga" to rewriteManga(manga, plan))
		}
		zip.copySection(input, "history") { entry ->
			val manga = entry.obj("manga") ?: return@copySection null
			val plan = planFor(manga) ?: return@copySection null
			val chapter = chapterIdFor(plan, entry.long("chapter_id"))
			var row = entry.with("manga_id" to JsonPrimitive(plan.newId), "manga" to rewriteManga(manga, plan))
			if (chapter != null) row = row.with("chapter_id" to JsonPrimitive(chapter))
			row
		}
		zip.copySection(input, "bookmarks") { entry ->
			val manga = entry.obj("manga") ?: return@copySection null
			val plan = planFor(manga) ?: return@copySection null
			val rewritten = rewriteManga(manga, plan)
			val items = entry.array("bookmarks").orEmpty().mapNotNull { item ->
				val b = item as? JsonObject ?: return@mapNotNull null
				val chapter = chapterIdFor(plan, b.long("chapter_id"))
				var row = b.with("manga_id" to JsonPrimitive(plan.newId))
				if (chapter != null) {
					row = row.with("chapter_id" to JsonPrimitive(chapter))
					val page = b.int("page")
					// Kototoro derives page ids from the chapter id; Usagi's depend on the page url, so they stay.
					if (target == App.KOTOTORO && page != null && chapter != b.long("chapter_id")) {
						row = row.with("page_id" to JsonPrimitive(ForeignBackupRules.kototoroPageId(chapter, page)))
					}
				}
				row
			}
			if (target == App.USAGI) {
				// Usagi's bookmark entry wants the tags beside the manga, not inside it.
				entry.with(
					"manga" to rewritten.with("tags" to JsonArray(emptyList())),
					"tags" to (rewritten["tags"] ?: JsonArray(emptyList())),
					"bookmarks" to JsonArray(items),
				)
			} else {
				entry.with("manga" to rewritten, "bookmarks" to JsonArray(items))
			}
		}
		if (target == App.USAGI) {
			val now = System.currentTimeMillis()
			zip.writeSection(
				"sources",
				usedSources.mapIndexed { index, name ->
					JsonObject(
						mapOf(
							"source" to JsonPrimitive(name),
							"sort_key" to JsonPrimitive(index),
							"used_at" to JsonPrimitive(now),
							"added_in" to JsonPrimitive(1),
							"pinned" to JsonPrimitive(false),
							"enabled" to JsonPrimitive(true),
						),
					)
				},
			)
			zip.copySection(input, "scrobbling") { entry ->
				val plan = entry.long("manga_id")?.let { plans[it] } ?: return@copySection null
				val scrobbler = entry.int("scrobbler")?.let { ForeignBackupRules.scrobblerToForeign(target, it) }
					?: return@copySection null
				entry.with(
					"manga_id" to JsonPrimitive(plan.newId),
					"scrobbler" to JsonPrimitive(scrobbler),
					// Usagi declares these two without a default, so they must be present even when empty.
					"status" to (entry["status"] ?: JsonPrimitive(null as String?)),
					"comment" to (entry["comment"] ?: JsonPrimitive(null as String?)),
				)
			}
		}
		zip.copySection(input, "statistics") { entry ->
			val plan = entry.long("manga_id")?.let { plans[it] } ?: return@copySection null
			entry.with("manga_id" to JsonPrimitive(plan.newId))
		}
		zip.finish()
		zip.flush()

		val converted = plans.values.count { it != null }
		return Report(converted, skipped.size, skipped.values.filter { it.isNotBlank() }.take(MAX_REPORTED_TITLES))
	}

	// ---- Kototoro / Usagi -> Yugen -----------------------------------------------------------------------

	/**
	 * Writes a Yugen-shaped zip of the library sections found in a Kototoro or Usagi backup. Settings, sources and
	 * saved filters are left out on purpose: their keys belong to the other app.
	 *
	 * @throws UnsupportedBackupException when the file is not a Kototoro or Usagi backup
	 */
	fun importFrom(input: File, output: OutputStream, categories: CategoryResolver): ImportResult {
		val index = firstElement(input, "index") as? JsonObject
			?: throw UnsupportedBackupException("The file has no backup index, so it is not a Kototoro or Usagi backup.")
		val app = ForeignBackupRules.detectApp(index.str("app_id"))
			?: throw UnsupportedBackupException("The file was not written by Kototoro or Usagi (app id: ${index.str("app_id")}).")

		val plans = HashMap<Long, MangaPlan?>()
		val skipped = LinkedHashMap<Long, String>()

		fun planFor(manga: JsonObject): MangaPlan? {
			val oldId = manga.long("id") ?: return null
			if (plans.containsKey(oldId)) return plans[oldId]
			val source = manga.str("source")
			val url = manga.str("url")
			val plan = when {
				source == null || url == null || !ForeignBackupRules.isImportableSource(source) -> null
				app == App.KOTOTORO && ForeignBackupRules.mihonSourceId(source) != null ->
					MangaPlan(ForeignBackupRules.deepSyncMangaId(source, url), source, url)
				// A Kotatsu parser name: keep the id, Yugen's Kotatsu migration re-keys it after the restore.
				else -> MangaPlan(oldId, source, url)
			}
			plans[oldId] = plan
			if (plan == null) skipped[oldId] = manga.str("title").orEmpty()
			return plan
		}

		val categoryIds = HashMap<Int, Int>()
		val newCategories = ArrayList<JsonElement>()
		forEachElement(input, "categories") { entry ->
			val c = entry as? JsonObject ?: return@forEachElement
			val foreignId = c.int("category_id") ?: return@forEachElement
			val title = c.str("title") ?: return@forEachElement
			if ((c.long("deleted_at") ?: 0L) != 0L) return@forEachElement
			val plan = categories.resolve(title, foreignId)
			categoryIds[foreignId] = plan.id
			if (plan.create) newCategories += c.with("category_id" to JsonPrimitive(plan.id))
		}

		fun live(entry: JsonObject) = (entry.long("deleted_at") ?: 0L) == 0L

		val written = LinkedHashSet<String>()
		val zip = ZipOutputStream(output)
		zip.writeSection(
			"index",
			listOf(
				JsonObject(
					mapOf(
						"app_id" to JsonPrimitive(index.str("app_id")),
						"app_version" to JsonPrimitive(index.int("app_version") ?: 1),
						"created_at" to JsonPrimitive(index.long("created_at") ?: System.currentTimeMillis()),
						"format_version" to JsonPrimitive(1),
					),
				),
			),
		)
		zip.writeSection("categories", newCategories).also { written += "categories" }
		zip.copySection(input, "favourites") { entry ->
			if (!live(entry)) return@copySection null
			val manga = entry.obj("manga") ?: return@copySection null
			val plan = planFor(manga) ?: return@copySection null
			val category = entry.int("category_id")?.let { categoryIds[it] } ?: return@copySection null
			entry.with(
				"manga_id" to JsonPrimitive(plan.newId),
				"category_id" to JsonPrimitive(category),
				"manga" to manga.with("id" to JsonPrimitive(plan.newId)),
			)
		}.also { written += "favourites" }
		zip.copySection(input, "history") { entry ->
			if (!live(entry)) return@copySection null
			val manga = entry.obj("manga") ?: return@copySection null
			val plan = planFor(manga) ?: return@copySection null
			entry.with("manga_id" to JsonPrimitive(plan.newId), "manga" to manga.with("id" to JsonPrimitive(plan.newId)))
		}.also { written += "history" }
		zip.copySection(input, "bookmarks") { entry ->
			val manga = entry.obj("manga") ?: return@copySection null
			val plan = planFor(manga) ?: return@copySection null
			// Usagi keeps the tags beside the manga; Yugen reads them from inside it.
			val tags = entry["tags"] as? JsonArray
			var rewritten = manga.with("id" to JsonPrimitive(plan.newId))
			if (tags != null && tags.isNotEmpty() && manga.array("tags").orEmpty().isEmpty()) {
				rewritten = rewritten.with("tags" to tags)
			}
			val items = entry.array("bookmarks").orEmpty().mapNotNull { item ->
				(item as? JsonObject)?.with("manga_id" to JsonPrimitive(plan.newId))
			}
			entry.with("manga" to rewritten, "bookmarks" to JsonArray(items))
		}.also { written += "bookmarks" }
		zip.copySection(input, "scrobbling") { entry ->
			val plan = entry.long("manga_id")?.let { plans[it] } ?: return@copySection null
			val scrobbler = entry.int("scrobbler")?.let { ForeignBackupRules.scrobblerFromForeign(app, it) }
				?: return@copySection null
			entry.with("manga_id" to JsonPrimitive(plan.newId), "scrobbler" to JsonPrimitive(scrobbler))
		}.also { written += "scrobbling" }
		zip.copySection(input, "statistics") { entry ->
			val plan = entry.long("manga_id")?.let { plans[it] } ?: return@copySection null
			entry.with("manga_id" to JsonPrimitive(plan.newId))
		}.also { written += "statistics" }
		zip.finish()
		zip.flush()

		val converted = plans.values.count { it != null }
		val report = Report(converted, skipped.size, skipped.values.filter { it.isNotBlank() }.take(MAX_REPORTED_TITLES))
		return ImportResult(app, report, written)
	}

	// ---- helpers -----------------------------------------------------------------------------------------

	private const val DEFAULT_APP_ID = "com.kotatsu.deepsync"
	private const val MAX_REPORTED_TITLES = 20

	private fun forEachElement(file: File, name: String, block: (JsonElement) -> Unit) {
		ZipFile(file).use { zip ->
			val entry = zip.getEntry(name) ?: return
			zip.getInputStream(entry).use { stream ->
				json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).forEach(block)
			}
		}
	}

	private fun firstElement(file: File, name: String): JsonElement? {
		var first: JsonElement? = null
		ZipFile(file).use { zip ->
			val entry = zip.getEntry(name) ?: return null
			zip.getInputStream(entry).use { stream ->
				first = json.decodeToSequence(stream, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
					.firstOrNull()
			}
		}
		return first
	}

	private fun ZipOutputStream.writeSection(name: String, items: List<JsonElement>) {
		putNextEntry(ZipEntry(name))
		write("[".toByteArray())
		items.forEachIndexed { i, item ->
			if (i > 0) write(",".toByteArray())
			write(item.toString().toByteArray())
		}
		write("]".toByteArray())
		closeEntry()
	}

	/** Streams a section of [input] through [transform] into this zip; a null result drops the element. */
	private fun ZipOutputStream.copySection(input: File, name: String, transform: (JsonObject) -> JsonElement?) {
		putNextEntry(ZipEntry(name))
		write("[".toByteArray())
		var first = true
		forEachElement(input, name) { element ->
			val out = (element as? JsonObject)?.let(transform) ?: return@forEachElement
			if (!first) write(",".toByteArray())
			first = false
			write(out.toString().toByteArray())
		}
		write("]".toByteArray())
		closeEntry()
	}

	private fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)

	private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

	private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

	private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

	private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

	private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
}
