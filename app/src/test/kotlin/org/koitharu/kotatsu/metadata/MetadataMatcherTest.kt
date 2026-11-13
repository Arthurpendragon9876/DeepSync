package org.koitharu.kotatsu.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.anilist.data.MetadataTitle
import org.koitharu.kotatsu.scrobbling.common.domain.MetadataKinds

class MetadataMatcherTest {

	private fun title(
		id: Long,
		names: List<String>,
		format: String? = "MANGA",
		country: String? = null,
		genres: List<String> = emptyList(),
		tags: List<String> = emptyList(),
	) = MetadataTitle(
		id = id, title = names.first(), names = names, coverUrl = null, bannerUrl = null,
		genres = genres, isAdult = false, score = null, url = "", format = format, country = country, tags = tags,
	)

	private fun profile(
		names: List<String>,
		country: String? = null,
		boysLove: Boolean = false,
		hasTags: Boolean = true,
	) = SourceProfile(names, country, scriptCountry = null, boysLove = boysLove, girlsLove = false, hasTags = hasTags)

	private val manhwa = title(1, listOf("Love Quest", "러브 퀘스트"), country = "KR", genres = listOf("Romance"))
	private val blManga = title(2, listOf("Love Quest"), country = "JP", tags = listOf("Boys' Love"))

	@Test
	fun `a manhwa is not matched to a same-named BL manga`() {
		val source = profile(listOf("Love Quest", "러브 퀘스트"), country = "KR")
		assertEquals(1L, MetadataMatcher.best(source, listOf(blManga, manhwa))?.id)
		assertEquals(1L, MetadataMatcher.best(source, listOf(manhwa, blManga))?.id)
	}

	@Test
	fun `a BL source picks the BL entry even when the other is listed first`() {
		val source = profile(listOf("Love Quest"), boysLove = true)
		assertEquals(2L, MetadataMatcher.best(source, listOf(manhwa, blManga))?.id)
	}

	@Test
	fun `nothing matches when only the wrong kind exists`() {
		val source = profile(listOf("Love Quest"), country = "KR")
		assertNull(MetadataMatcher.best(source, listOf(blManga)))
	}

	@Test
	fun `the entry that knows more of the alternative names wins`() {
		val source = profile(listOf("Overgeared", "Pager Gyeol Geol", "templar ryu"))
		val thin = title(10, listOf("Overgeared"), country = "KR")
		val full = title(11, listOf("Overgeared", "Pager Gyeol Geol", "Templar Ryu"), country = "KR")
		assertEquals(11L, MetadataMatcher.best(source, listOf(thin, full))?.id)
	}

	@Test
	fun `a source without tags does not rule out a BL entry`() {
		val source = profile(listOf("Love Quest"), hasTags = false)
		assertEquals(2L, MetadataMatcher.best(source, listOf(blManga))?.id)
	}

	@Test
	fun `kinds are normalised across trackers`() {
		assertEquals(MetadataKinds.MANHWA, MetadataKinds.kindOf("manhwa", null))
		assertEquals(MetadataKinds.MANHWA, MetadataKinds.kindOf("MANGA", "KR"))
		assertEquals(MetadataKinds.MANHUA, MetadataKinds.kindOf("MANGA", "TW"))
		assertEquals(MetadataKinds.NOVEL, MetadataKinds.kindOf("light_novel", null))
		assertTrue(MetadataKinds.isBoysLove(listOf("Romance", "Boys Love")))
	}
}
