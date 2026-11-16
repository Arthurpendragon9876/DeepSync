package org.koitharu.kotatsu.scrobbling.common.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate

class TrackerMatchGrouperTest {

	private val al = ScrobblerService.ANILIST
	private val mal = ScrobblerService.MAL
	private val mb = ScrobblerService.MANGABAKA

	private fun candidate(id: Long, name: String, score: Float, vararg alt: String) = TrackerCandidate(
		manga = ScrobblerManga(id, name, null, null, "", false),
		names = listOf(name) + alt,
		score = score,
	)

	@Test
	fun sameTitleAcrossTrackersIsOneCard() {
		val groups = TrackerMatchGrouper.group(
			mapOf(
				al to listOf(candidate(1, "Berserk", 1f)),
				mal to listOf(candidate(2, "Berserk", 1f)),
				mb to listOf(candidate(3, "Berserk", 1f)),
			),
		)
		assertEquals(1, groups.size)
		assertEquals(setOf(al, mal, mb), groups[0].candidates.keys)
	}

	@Test
	fun titleOnlyOneTrackerFoundStaysAlone() {
		val groups = TrackerMatchGrouper.group(
			mapOf(
				al to listOf(candidate(1, "Rare Title", 0.8f)),
				mal to listOf(candidate(2, "Unrelated", 0.3f)),
			),
		)
		assertEquals(2, groups.size)
		assertEquals(listOf(al), groups[0].services)
	}

	@Test
	fun oneTrackerNeverFillsTwoSlotsOfACard() {
		val groups = TrackerMatchGrouper.group(
			mapOf(
				al to listOf(candidate(1, "Berserk", 1f), candidate(4, "Berserk", 0.9f)),
				mal to listOf(candidate(2, "Berserk", 1f)),
			),
		)
		assertEquals(2, groups.size)
		assertEquals(setOf(al, mal), groups[0].candidates.keys)
		assertEquals(setOf(al), groups[1].candidates.keys)
	}

	@Test
	fun matchesThroughSynonymsAndExactHits() {
		val viaSynonym = TrackerMatchGrouper.group(
			mapOf(
				al to listOf(candidate(1, "Sousou no Frieren", 0.9f, "Frieren")),
				mal to listOf(candidate(2, "Frieren", 0.9f)),
			),
		)
		assertEquals(1, viaSynonym.size)
		// No shared name, but both are an exact hit on what the user is reading
		val viaExact = TrackerMatchGrouper.group(
			mapOf(
				al to listOf(candidate(1, "Name A", 1f)),
				mal to listOf(candidate(2, "Name B", 1f)),
			),
		)
		assertEquals(1, viaExact.size)
	}

	@Test
	fun bestScoreFirstAndLimited() {
		val many = (1..10).map { candidate(it.toLong(), "Title $it", it / 10f) }
		val groups = TrackerMatchGrouper.group(mapOf(al to many), perServiceLimit = 10, maxGroups = 4)
		assertEquals(4, groups.size)
		assertEquals(1f, groups[0].score, 0f)
	}
}
