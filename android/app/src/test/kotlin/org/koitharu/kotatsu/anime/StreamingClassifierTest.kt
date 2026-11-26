package org.koitharu.kotatsu.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.anime.StreamingClassifier.Signals

class StreamingClassifierTest {

	private fun aniyomi(label: String, hosts: List<String> = emptyList()) = Signals(
		label = label,
		pkg = "eu.kanade.tachiyomi.animeextension.all.${label.lowercase().replace(" ", "")}",
		sourceNames = listOf(label),
		hosts = hosts,
	)

	@Test
	fun movieAndTvNamesAreTagged() {
		assertTrue(StreamingClassifier.isTvMovies(aniyomi("MoviesMod")))
		assertTrue(StreamingClassifier.isTvMovies(aniyomi("Cinemana")))
		assertTrue(StreamingClassifier.isTvMovies(aniyomi("Pelisplus")))
		assertTrue(StreamingClassifier.isTvMovies(aniyomi("DramaCool")))
		assertTrue(StreamingClassifier.isTvMovies(aniyomi("Xyz TV")))
		assertTrue(StreamingClassifier.isTvMovies(aniyomi("Foo", hosts = listOf("foomovies.to"))))
	}

	@Test
	fun animeWordsAlwaysWin() {
		assertFalse(StreamingClassifier.isTvMovies(aniyomi("AnimeStream")))
		assertFalse(StreamingClassifier.isTvMovies(aniyomi("Anime Movies")))
		assertFalse(StreamingClassifier.isTvMovies(aniyomi("HiAnime")))
		assertFalse(StreamingClassifier.isTvMovies(aniyomi("Foo Movies", hosts = listOf("aniwatch.to"))))
	}

	@Test
	fun streamAloneIsNotEnough() {
		assertFalse(StreamingClassifier.isTvMovies(aniyomi("Foo Stream")))
		assertFalse(StreamingClassifier.isTvMovies(aniyomi("Foo Stream", hosts = listOf("foostream.cc"))))
	}

	@Test
	fun cloudstreamUsesDeclaredTypes() {
		assertEquals(true, StreamingClassifier.fromProviderTypes(listOf(setOf("Movie", "TvSeries"))))
		assertEquals(false, StreamingClassifier.fromProviderTypes(listOf(setOf("Anime", "OVA"))))
		// a provider listing both counts as anime, and a tie stays anime
		assertEquals(false, StreamingClassifier.fromProviderTypes(listOf(setOf("Anime", "Movie"))))
		assertEquals(false, StreamingClassifier.fromProviderTypes(listOf(setOf("Movie"), setOf("Anime"))))
		assertEquals(true, StreamingClassifier.fromProviderTypes(listOf(setOf("Movie"), setOf("TvSeries"), setOf("Anime"))))
		assertNull(StreamingClassifier.fromProviderTypes(listOf(setOf("Others"), setOf("Torrent"))))
	}

	@Test
	fun cloudstreamTypesBeatTheName() {
		val animeNamedMovies = Signals("Anime Movies", "cloudstream.x", providerTypes = listOf(setOf("Movie")))
		assertTrue(StreamingClassifier.isTvMovies(animeNamedMovies))
		val noTypes = Signals("MoviesMod", "cloudstream.moviesmod", providerTypes = listOf(setOf("Others")))
		assertTrue(StreamingClassifier.isTvMovies(noTypes))
	}

	@Test
	fun legacyRulesAreKept() {
		assertTrue(StreamingClassifier.legacyIsTvMovies(aniyomi("Foo Stream")))
		assertFalse(StreamingClassifier.legacyIsTvMovies(aniyomi("AnimeStream")))
		assertTrue(StreamingClassifier.legacyIsTvMovies(Signals("X", "cloudstream.x", providerTypes = listOf(setOf("Movie")))))
		assertFalse(StreamingClassifier.legacyIsTvMovies(Signals("X", "cloudstream.x", providerTypes = listOf(setOf("Anime")))))
	}

	@Test
	fun hostIsExtracted() {
		assertEquals("example.com", StreamingClassifier.hostOf("https://www.example.com/path"))
		assertEquals("a.b", StreamingClassifier.hostOf("http://a.b:8080/x"))
		assertNull(StreamingClassifier.hostOf("not a url"))
	}
}
