package org.koitharu.kotatsu.scrobbling.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry

class TrackerLibraryMergerTest {

	private fun entry(
		service: ScrobblerService,
		id: Long,
		title: String,
		chapter: Int,
		total: Int = 0,
		status: String = "READING",
		alt: List<String> = emptyList(),
		updatedAt: Long = 0,
	) = TrackerReadingEntry(service, id, title, null, chapter, total, "", updatedAt, status, alt)

	private val al = ScrobblerService.ANILIST
	private val mal = ScrobblerService.MAL
	private val mb = ScrobblerService.MANGABAKA

	@Test
	fun sameTitleOnThreeTrackersIsOneEntry() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Berserk", 300)),
				mal to listOf(entry(mal, 2, "berserk", 280)),
				mb to listOf(entry(mb, 3, "Berserk!", 290)),
			),
		)
		assertEquals(1, merged.size)
		assertEquals(setOf(al, mal, mb), merged[0].entries.keys)
		assertEquals(300, merged[0].progress)
		assertTrue(merged[0].missingOn.isEmpty())
	}

	@Test
	fun titleOnlyOnOneTrackerIsKeptAndReportsTheOthersMissing() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Solo Title", 5)),
				mal to listOf(entry(mal, 2, "Other Title", 9)),
			),
		)
		assertEquals(2, merged.size)
		val solo = merged.first { it.title == "Solo Title" }
		assertEquals(listOf(mal), solo.missingOn)
	}

	@Test
	fun mergesThroughAlternativeNames() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Sousou no Frieren", 100, alt = listOf("Frieren: Beyond Journey's End"))),
				mal to listOf(entry(mal, 2, "Frieren: Beyond Journey's End", 90)),
			),
		)
		assertEquals(1, merged.size)
	}

	@Test
	fun twoEntriesOfOneTrackerAreNeverMerged() {
		// Same name, same tracker: two different series (say a manga and its sequel with a shared synonym)
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Naruto", 700), entry(al, 2, "Naruto", 10)),
				mal to listOf(entry(mal, 3, "Naruto", 700)),
			),
		)
		assertEquals(2, merged.size)
		assertTrue(merged.all { it.entries.size <= 2 && it.entries.values.map { e -> e.service }.toSet().size == it.entries.size })
	}

	@Test
	fun sameLocalMangaLinksTitlesWithNothingInCommon() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Totally Different", 5)),
				mal to listOf(entry(mal, 2, "Nothing Alike", 5)),
			),
			links = mapOf(al to mapOf(1L to 77L), mal to mapOf(2L to 77L)),
		)
		assertEquals(1, merged.size)
	}

	@Test
	fun furthestTrackerLeadsAndBehindOnesGetPushes() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Series", 40)),
				mal to listOf(entry(mal, 2, "Series", 55)),
				mb to listOf(entry(mb, 3, "Series", 55)),
			),
		).single()
		assertEquals(mal, merged.leader.service)
		assertTrue(merged.isOutOfSync)
		val pushes = merged.pendingPushes()
		assertEquals(1, pushes.size)
		assertEquals(al, pushes[0].target.service)
		assertEquals(55, pushes[0].chapter)
	}

	@Test
	fun tieGoesToTheHigherPriorityTracker() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				mb to listOf(entry(mb, 3, "Series", 10)),
				al to listOf(entry(al, 1, "Series", 10)),
			),
		).single()
		assertEquals(al, merged.leader.service)
		assertFalse(merged.isOutOfSync)
	}

	@Test
	fun pushNeverExceedsTargetsLengthAndSkipsFinishedEntries() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Series", 105)),
				mal to listOf(entry(mal, 2, "Series", 90, total = 100)),
				mb to listOf(entry(mb, 3, "Series", 50, total = 100, status = "COMPLETED")),
			),
		).single()
		val pushes = merged.pendingPushes()
		assertEquals(1, pushes.size)
		assertEquals(mal, pushes[0].target.service)
		assertEquals(100, pushes[0].chapter)
	}

	@Test
	fun singleTrackerEntryNeverNeedsSync() {
		val merged = TrackerLibraryMerger.merge(mapOf(al to listOf(entry(al, 1, "Only", 9)))).single()
		assertTrue(merged.pendingPushes().isEmpty())
	}

	@Test
	fun rankingPositionsDoNotCountAsTimestamps() {
		val merged = TrackerLibraryMerger.merge(
			mapOf(
				al to listOf(entry(al, 1, "Real", 1, updatedAt = 1_700_000_000_000L)),
				mb to listOf(entry(mb, 2, "Ranked", 1, updatedAt = 500L)),
			),
		)
		assertEquals("Real", merged[0].title)
		assertEquals(0L, merged.first { it.title == "Ranked" }.updatedAt)
	}
}
