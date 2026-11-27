package org.koitharu.kotatsu.favourites.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleDuplicatesTest {

	private data class Row(val key: String?, val unread: Boolean, val time: Int)

	private val preferred = compareBy<Row>({ it.unread }, { it.time })

	@Test
	fun `punctuation and case do not change the title key`() {
		assertEquals("onepiece", "One Piece".toTitleKey())
		assertEquals("onepiece", "one-piece!".toTitleKey())
	}

	@Test
	fun `same key keeps the newest row and the order of the rest`() {
		val rows = listOf(Row("a", false, 9), Row("b", false, 8), Row("a", false, 5), Row("c", false, 1))
		val result = rows.collapseDuplicates({ it.key }, preferred)
		assertEquals(listOf(Row("a", false, 9), Row("b", false, 8), Row("c", false, 1)), result)
	}

	@Test
	fun `an unread row beats a newer read one`() {
		val rows = listOf(Row("a", false, 9), Row("a", true, 5))
		assertEquals(listOf(Row("a", true, 5)), rows.collapseDuplicates({ it.key }, preferred))
	}

	@Test
	fun `rows without a key are never collapsed`() {
		val rows = listOf(Row(null, false, 3), Row(null, false, 2))
		assertEquals(rows, rows.collapseDuplicates({ it.key }, preferred))
	}

	@Test
	fun `ties keep the earliest row`() {
		val rows = listOf(Row("a", false, 1), Row("a", false, 1))
		assertEquals(listOf(rows[0]), rows.collapseDuplicates({ it.key }, preferred))
	}
}
