package org.koitharu.kotatsu.backup.foreign

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.backup.foreign.ForeignBackupConverter.CategoryPlan
import org.koitharu.kotatsu.backup.foreign.ForeignBackupRules.App
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ForeignBackupConverterTest {

	private fun manga(id: Long, source: String, url: String, title: String, tags: String = "[]") =
		"""{"id":$id,"title":"$title","url":"$url","public_url":"https://x$url","cover_url":"c","source":"$source","tags":$tags}"""

	private fun zipOf(entries: Map<String, String>): File {
		val file = File.createTempFile("foreign", ".bk.zip")
		file.deleteOnExit()
		ZipOutputStream(file.outputStream()).use { zip ->
			entries.forEach { (name, text) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(text.toByteArray())
				zip.closeEntry()
			}
		}
		return file
	}

	private fun read(bytes: ByteArray): Map<String, JsonArray> {
		val file = File.createTempFile("converted", ".zip")
		file.deleteOnExit()
		file.writeBytes(bytes)
		return ZipFile(file).use { zip ->
			zip.entries().asSequence().associate {
				it.name to Json.parseToJsonElement(zip.getInputStream(it).readBytes().decodeToString()).jsonArray
			}
		}
	}

	private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.longOrNull
	private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.contentOrNull

	private val alpha = manga(111, "MIHON_1234", "/manga/a", "Alpha", """[{"id":5,"title":"Action","key":"action","source":"MIHON_1234"}]""")
	private val anime = manga(222, "ANIME_9", "/anime/b", "Bravo")

	private val yugen
		get() = zipOf(
			linkedMapOf(
				"index" to """[{"app_id":"com.kotatsu.deepsync","app_version":11,"created_at":5,"format_version":1}]""",
				"categories" to """[{"category_id":1,"created_at":1,"sort_key":0,"title":"Reading"}]""",
				"favourites" to """[{"manga_id":111,"category_id":1,"created_at":2,"manga":$alpha},{"manga_id":222,"category_id":1,"created_at":2,"manga":$anime}]""",
				"history" to """[{"manga_id":111,"created_at":1,"updated_at":9,"chapter_id":9001,"page":3,"scroll":0.0,"percent":0.5,"manga":$alpha}]""",
				"bookmarks" to """[{"manga":$alpha,"bookmarks":[{"manga_id":111,"page_id":42,"chapter_id":9001,"page":3,"scroll":0,"image_url":"i","created_at":1,"percent":0.1}]}]""",
				"scrobbling" to """[{"scrobbler":2,"id":1,"manga_id":111,"target_id":555,"status":null,"chapter":3,"comment":null,"rating":0.0},{"scrobbler":6,"id":2,"manga_id":111,"target_id":9,"status":null,"chapter":1,"comment":null,"rating":0.0}]""",
				"statistics" to """[{"manga_id":111,"started_at":1,"duration":60,"pages":5},{"manga_id":222,"started_at":1,"duration":60,"pages":5}]""",
				"chapters" to """[{"manga":$alpha,"chapters":[{"chapter_id":9001,"manga_id":111,"name":"c1","number":1.0,"url":"/ch/1","source":"MIHON_1234","index":0}]}]""",
			),
		)

	@Test
	fun `export to kototoro re-keys mihon titles and skips the rest`() {
		val out = ByteArrayOutputStream()
		val report = ForeignBackupConverter.exportTo(App.KOTOTORO, yugen, out)
		val sections = read(out.toByteArray())
		val mangaId = ForeignBackupRules.kototoroMangaId("MIHON_1234", "/manga/a", "Alpha")
		val chapterId = ForeignBackupRules.kototoroChapterId("MIHON_1234", "/ch/1", "/manga/a")

		assertEquals(1, report.converted)
		assertEquals(1, report.skipped)
		assertEquals(listOf("index", "categories", "favourites", "history", "bookmarks", "statistics"), sections.keys.toList())
		assertEquals("com.kotatsu.deepsync", sections.getValue("index")[0].jsonObject.str("app_id"))
		assertEquals(1, sections.getValue("favourites").size)
		assertEquals(mangaId, sections.getValue("favourites")[0].jsonObject.long("manga_id"))
		assertEquals(chapterId, sections.getValue("history")[0].jsonObject.long("chapter_id"))
		assertEquals(1, sections.getValue("statistics").size)
	}

	@Test
	fun `export to usagi renames sources and moves bookmark tags`() {
		val out = ByteArrayOutputStream()
		val report = ForeignBackupConverter.exportTo(App.USAGI, yugen, out, mapOf(1234L to "MANGADEX"))
		val sections = read(out.toByteArray())
		val bookmark = sections.getValue("bookmarks")[0].jsonObject

		assertEquals(1, report.converted)
		assertEquals("MANGADEX", sections.getValue("favourites")[0].jsonObject.getValue("manga").jsonObject.str("source"))
		assertEquals(1, bookmark.getValue("tags").jsonArray.size)
		assertEquals(0, bookmark.getValue("manga").jsonObject.getValue("tags").jsonArray.size)
		assertEquals("MANGADEX", sections.getValue("sources")[0].jsonObject.str("source"))
		// Simkl (6) has no Usagi counterpart; AniList (2) stays.
		assertEquals(listOf(2L), sections.getValue("scrobbling").map { it.jsonObject.long("scrobbler") })
	}

	@Test
	fun `import from kototoro remaps ids, categories and trackers`() {
		val k1 = manga(7001, "MIHON_1234", "/manga/a", "Alpha")
		val k2 = manga(7002, "JSON_abc", "/j", "Json")
		val kototoro = zipOf(
			linkedMapOf(
				"index" to """[{"app_id":"org.skepsun.kototoro","app_version":300,"created_at":7}]""",
				"categories" to """[{"category_id":3,"created_at":1,"sort_key":0,"title":"Reading"},{"category_id":4,"created_at":1,"sort_key":1,"title":"Shelf"}]""",
				"favourites" to """[{"manga_id":7001,"category_id":3,"created_at":2,"manga":$k1},{"manga_id":7001,"category_id":4,"created_at":2,"manga":$k1},{"manga_id":7002,"category_id":3,"created_at":2,"manga":$k2}]""",
				"history" to """[{"manga_id":7001,"created_at":1,"updated_at":9,"chapter_id":1,"page":0,"scroll":0.0,"deleted_at":5,"manga":$k1}]""",
				"scrobbling" to """[{"scrobbler":7,"id":1,"manga_id":7001,"target_id":1,"status":null,"chapter":0,"comment":null,"rating":0.0},{"scrobbler":5,"id":2,"manga_id":7001,"target_id":1,"status":null,"chapter":0,"comment":null,"rating":0.0}]""",
			),
		)
		val out = ByteArrayOutputStream()
		val result = ForeignBackupConverter.importFrom(kototoro, out) { title, _ ->
			if (title == "Reading") CategoryPlan(1, create = false) else CategoryPlan(50, create = true)
		}
		val sections = read(out.toByteArray())
		val yugenId = ForeignBackupRules.deepSyncMangaId("MIHON_1234", "/manga/a")

		assertEquals(App.KOTOTORO, result.app)
		assertEquals(1, result.report.skipped)
		assertEquals(listOf(50L), sections.getValue("categories").map { it.jsonObject.long("category_id") })
		assertEquals(listOf(1L, 50L), sections.getValue("favourites").map { it.jsonObject.long("category_id") })
		assertEquals(yugenId, sections.getValue("favourites")[0].jsonObject.long("manga_id"))
		assertTrue(sections.getValue("history").isEmpty()) // the only history row was deleted
		assertEquals(listOf(6L), sections.getValue("scrobbling").map { it.jsonObject.long("scrobbler") })
	}

	@Test(expected = ForeignBackupConverter.UnsupportedBackupException::class)
	fun `a yugen backup is not a foreign backup`() {
		ForeignBackupConverter.importFrom(yugen, ByteArrayOutputStream()) { _, _ -> CategoryPlan(1, create = false) }
	}
}
