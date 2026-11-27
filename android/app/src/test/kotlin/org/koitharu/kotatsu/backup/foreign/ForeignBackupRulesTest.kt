package org.koitharu.kotatsu.backup.foreign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.backup.foreign.ForeignBackupRules.App

class ForeignBackupRulesTest {

	// Expected values were computed independently of the Kotlin implementation.
	@Test
	fun `kototoro ids match the reference values`() {
		assertEquals(192253896142557889L, ForeignBackupRules.kototoroMangaId("MIHON_1234", "/manga/abc", "Title"))
		assertEquals(193207748443324085L, ForeignBackupRules.kototoroMangaId("MIHON_1234", "", "Some Title"))
		assertEquals(4686461569858974305L, ForeignBackupRules.kototoroMangaId("MIHON_1234", "", ""))
		assertEquals(9223372035839472321L, ForeignBackupRules.kototoroChapterId("MIHON_1234", "/ch/1", null))
		assertEquals(1282624263L, ForeignBackupRules.kototoroChapterId("MIHON_1234", "/ch/1", "/manga/abc"))
	}

	@Test
	fun `masked ids are never negative`() {
		assertTrue(ForeignBackupRules.deepSyncMangaId("MIHON_1", "/a") >= 0)
		assertTrue(ForeignBackupRules.kototoroPageId(1L, 0) >= 0)
	}

	@Test
	fun `only kototoro and usagi are detected`() {
		assertEquals(App.KOTOTORO, ForeignBackupRules.detectApp("org.skepsun.kototoro.nightly"))
		assertEquals(App.USAGI, ForeignBackupRules.detectApp("org.draken.usagi"))
		assertNull(ForeignBackupRules.detectApp("com.kotatsu.deepsync"))
		assertNull(ForeignBackupRules.detectApp(null))
	}

	@Test
	fun `tracker ids are renumbered both ways`() {
		assertEquals(8, ForeignBackupRules.scrobblerToForeign(App.KOTOTORO, 5)) // MangaBaka
		assertEquals(7, ForeignBackupRules.scrobblerToForeign(App.KOTOTORO, 6)) // Simkl
		assertNull(ForeignBackupRules.scrobblerToForeign(App.USAGI, 5))
		assertEquals(2, ForeignBackupRules.scrobblerToForeign(App.USAGI, 2))
		assertEquals(6, ForeignBackupRules.scrobblerFromForeign(App.KOTOTORO, 7))
		assertEquals(5, ForeignBackupRules.scrobblerFromForeign(App.KOTOTORO, 8))
		assertNull(ForeignBackupRules.scrobblerFromForeign(App.KOTOTORO, 5)) // Bangumi has no Yugen equivalent
	}

	@Test
	fun `importable sources`() {
		assertTrue(ForeignBackupRules.isImportableSource("MIHON_5"))
		assertTrue(ForeignBackupRules.isImportableSource("WEBTOONS_EN"))
		assertFalse(ForeignBackupRules.isImportableSource("JSON_abc"))
		assertFalse(ForeignBackupRules.isImportableSource("CLOUDSTREAM_x_y"))
		assertFalse(ForeignBackupRules.isImportableSource("LOCAL"))
		assertEquals(1234L, ForeignBackupRules.mihonSourceId("MIHON_1234:en"))
		assertNull(ForeignBackupRules.mihonSourceId("MANGADEX"))
	}
}
