package org.koitharu.kotatsu.history.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveredChapterIndexTest {

	@Test
	fun `a new episode does not move someone who finished the last one forward`() {
		// 6 of 6 watched, episode 7 just came out and the saved episode id no longer resolves
		assertEquals(5, recoveredChapterIndex(percent = 1f, savedCount = 6, currentSize = 7))
	}

	@Test
	fun `position is kept when several chapters arrive at once`() {
		assertEquals(3, recoveredChapterIndex(percent = 4 / 10f, savedCount = 10, currentSize = 15))
	}

	@Test
	fun `float noise does not round up to the next chapter`() {
		assertEquals(5, recoveredChapterIndex(percent = 6 / 7f, savedCount = 7, currentSize = 7))
	}

	@Test
	fun `a percent re-based on the new list by the progress update still resolves to the same chapter`() {
		// ProgressUpdateUseCase stores 6 of 7 while the saved count may still say 6
		assertEquals(5, recoveredChapterIndex(percent = 6 / 7f, savedCount = 6, currentSize = 7))
	}

	@Test
	fun `rows saved without a count fall back to the current size`() {
		assertEquals(4, recoveredChapterIndex(percent = 0.5f, savedCount = 0, currentSize = 9))
	}

	@Test
	fun `the result always stays inside the list`() {
		assertEquals(2, recoveredChapterIndex(percent = 1f, savedCount = 10, currentSize = 3))
		assertEquals(0, recoveredChapterIndex(percent = 0f, savedCount = 5, currentSize = 5))
	}
}
