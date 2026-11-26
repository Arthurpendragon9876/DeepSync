package org.koitharu.kotatsu.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.anime.AnimeEpisodeFilters.Audio

class AnimeEpisodeFiltersTest {

	@Test
	fun seasonIsReadFromTheEpisodeName() {
		assertEquals(1, AnimeEpisodeFilters.seasonOfTitle("S1 E3: Pilot"))
		assertEquals(2, AnimeEpisodeFilters.seasonOfTitle("S02E10 - Foo"))
		assertEquals(2, AnimeEpisodeFilters.seasonOfTitle("Season 2 Episode 5"))
		assertEquals(0, AnimeEpisodeFilters.seasonOfTitle("S00E01"))
		assertEquals(1, AnimeEpisodeFilters.seasonOfTitle("1x05 Title"))
		assertNull(AnimeEpisodeFilters.seasonOfTitle("Episode 5 - Hello"))
		assertNull(AnimeEpisodeFilters.seasonOfTitle("Movie 1920x1080"))
	}

	@Test
	fun seasonPrefixIsStripped() {
		assertEquals("Pilot", AnimeEpisodeFilters.stripSeasonPrefix("S1 E3: Pilot"))
		assertEquals("", AnimeEpisodeFilters.stripSeasonPrefix("S1 E3"))
		assertEquals("The End", AnimeEpisodeFilters.stripSeasonPrefix("Season 2 Episode 5 - The End"))
		assertEquals("Pilot", AnimeEpisodeFilters.stripSeasonPrefix("Pilot"))
	}

	@Test
	fun seasonPickerNeedsTwoSeasons() {
		assertEquals(emptyList<Int>(), AnimeEpisodeFilters.seasonKeys(listOf(1, 1, 1)))
		assertEquals(emptyList<Int>(), AnimeEpisodeFilters.seasonKeys(listOf(1, null)))
		assertEquals(listOf(1, 2), AnimeEpisodeFilters.seasonKeys(listOf(2, 1, 1, 2)))
		assertEquals(listOf(1, 2, AnimeEpisodeFilters.OTHER_SEASON), AnimeEpisodeFilters.seasonKeys(listOf(2, 1, null)))
	}

	@Test
	fun audioIsReadFromScanlatorAndNameTags() {
		assertEquals(Audio.DUB, AnimeEpisodeFilters.audioOfText("Dubbed", "Episode 1"))
		assertEquals(Audio.SUB, AnimeEpisodeFilters.audioOfText("Subbed", null))
		assertEquals(Audio.BOTH, AnimeEpisodeFilters.audioOfText("Sub & Dub", null))
		assertEquals(Audio.UNKNOWN, AnimeEpisodeFilters.audioOfText("Subtitles team", null))
		assertEquals(Audio.DUB, AnimeEpisodeFilters.audioOfText(null, "Episode 3 (Dub)"))
		assertEquals(Audio.UNKNOWN, AnimeEpisodeFilters.audioOfText(null, "The Dub Master"))
	}

	@Test
	fun playingTeachesTheAudio() {
		assertEquals(Audio.BOTH, AnimeEpisodeFilters.combine(Audio.SUB, true))
		assertEquals(Audio.SUB, AnimeEpisodeFilters.combine(Audio.UNKNOWN, false))
		assertEquals(Audio.SUB, AnimeEpisodeFilters.combine(Audio.BOTH, false))
		assertEquals(Audio.DUB, AnimeEpisodeFilters.combine(Audio.DUB, false))
		assertEquals(Audio.UNKNOWN, AnimeEpisodeFilters.combine(Audio.UNKNOWN, null))
	}

	@Test
	fun audioChoiceFiltersTheList() {
		val audios = listOf(Audio.SUB, Audio.DUB, Audio.SUB, Audio.UNKNOWN, Audio.BOTH)
		assertEquals(listOf(1, 3, 4), AnimeEpisodeFilters.visibleIndices(audios, "dub", -1))
		assertEquals(listOf(0, 1, 3, 4), AnimeEpisodeFilters.visibleIndices(audios, "dub", 0))
		assertEquals(listOf(0, 2, 3, 4), AnimeEpisodeFilters.visibleIndices(audios, "sub", -1))
		// never an empty list
		assertEquals(listOf(0, 1), AnimeEpisodeFilters.visibleIndices(listOf(Audio.SUB, Audio.SUB), "dub", -1))
	}
}
