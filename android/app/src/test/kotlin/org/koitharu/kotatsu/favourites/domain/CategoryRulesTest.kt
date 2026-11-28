package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag.ANIME
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag.ANY
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag.MANGA
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag.NOVEL
import org.koitharu.kotatsu.favourites.domain.CategoryMediaTag.TV_MOVIES

class CategoryRulesTest {

	private data class Cat(val id: Long, val parent: Long? = null)

	private fun order(vararg cats: Cat) =
		CategoryRules.hierarchy(cats.toList(), { it.id }, { it.parent }).map { it.item.id to it.depth }

	@Test
	fun `children follow their parent in the users order`() {
		val result = order(Cat(1), Cat(2, parent = 3), Cat(3), Cat(4, parent = 1), Cat(5, parent = 3))
		assertEquals(listOf(1L to 0, 4L to 1, 3L to 0, 2L to 1, 5L to 1), result)
	}

	@Test
	fun `a missing, self or nested parent never hides a category`() {
		val result = order(Cat(1), Cat(2, parent = 99), Cat(3, parent = 3), Cat(4, parent = 1), Cat(5, parent = 4))
		assertEquals(setOf(1L, 2L, 3L, 4L, 5L), result.map { it.first }.toSet())
		assertEquals(5, result.size)
		assertEquals(0, result.first { it.first == 5L }.second)
		assertEquals(1, result.first { it.first == 4L }.second)
	}

	@Test
	fun `a parent cycle still shows both categories`() {
		val result = order(Cat(1, parent = 2), Cat(2, parent = 1))
		assertEquals(setOf(1L, 2L), result.map { it.first }.toSet())
		assertEquals(listOf(0, 0), result.map { it.second })
	}

	@Test
	fun `parent candidates are top level, not self, and none for a category with children`() {
		val all = listOf(Cat(1), Cat(2, parent = 1), Cat(3), Cat(4))
		fun candidates(self: Long?) =
			CategoryRules.parentCandidates(all, self, { it.id }, { it.parent }).map { it.id }
		assertEquals(listOf(1L, 3L, 4L), candidates(null))
		assertEquals(listOf(1L, 4L), candidates(3))
		assertEquals(emptyList<Long>(), candidates(1))
		assertEquals(listOf(1L, 3L, 4L), candidates(2))
	}

	@Test
	fun `display title joins parent and child`() {
		assertEquals("Manga › Seinen", CategoryRules.displayTitle("Seinen", "Manga"))
		assertEquals("Seinen", CategoryRules.displayTitle("Seinen", null))
		assertEquals("Seinen", CategoryRules.displayTitle("Seinen", " "))
	}

	@Test
	fun `auto tag follows the titles, an explicit tag wins`() {
		assertEquals(MANGA, CategoryRules.resolveTag(null, listOf(MANGA, MANGA)))
		assertEquals(ANY, CategoryRules.resolveTag(null, listOf(MANGA, NOVEL)))
		assertEquals(ANY, CategoryRules.resolveTag(null, emptyList()))
		assertEquals(ANIME, CategoryRules.resolveTag(null, listOf(ANIME, ANIME)))
		assertEquals(ANY, CategoryRules.resolveTag(null, listOf(ANIME, TV_MOVIES)))
		assertEquals(NOVEL, CategoryRules.resolveTag(NOVEL, listOf(MANGA)))
		assertEquals(ANY, CategoryRules.resolveTag(ANY, listOf(MANGA)))
	}

	@Test
	fun `a tagged category is only offered for matching titles`() {
		assertTrue(CategoryRules.accepts(ANY, setOf(MANGA)))
		assertTrue(CategoryRules.accepts(MANGA, setOf(MANGA)))
		assertFalse(CategoryRules.accepts(MANGA, setOf(ANIME)))
		assertTrue(CategoryRules.accepts(MANGA, setOf(ANIME, MANGA)))
		assertTrue(CategoryRules.accepts(MANGA, emptySet()))
	}

	@Test
	fun `update interval gates checks`() {
		val day = 24L * 60 * 60 * 1000
		assertTrue(CategoryRules.isDue(null, 0L, 1L))
		assertFalse(CategoryRules.isDue(CategoryUpdateInterval.DAILY, 1000L, 1000L + day - 1))
		assertTrue(CategoryRules.isDue(CategoryUpdateInterval.DAILY, 1000L, 1000L + day))
		assertFalse(CategoryRules.isDue(CategoryUpdateInterval.WEEKLY, 0L, 6 * day))
		assertTrue(CategoryRules.isDue(CategoryUpdateInterval.WEEKLY, 0L, 7 * day))
		assertFalse(CategoryRules.isDue(CategoryUpdateInterval.PAUSED, 0L, Long.MAX_VALUE))
	}

	@Test
	fun `keys parse and unknown values mean unset`() {
		assertEquals(CategoryUpdateInterval.WEEKLY, CategoryUpdateInterval.of("weekly"))
		assertNull(CategoryUpdateInterval.of("hourly"))
		assertNull(CategoryUpdateInterval.of(null))
		assertEquals(TV_MOVIES, CategoryMediaTag.of("TV_MOVIES"))
		assertNull(CategoryMediaTag.of("COMICS"))
	}
}
