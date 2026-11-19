package org.koitharu.kotatsu.sync.backend

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.sync.backend.dropbox.DropboxApi
import java.time.Instant

class DropboxEntryTest {

	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `decodes a list_folder entry`() {
		val entry = json.decodeFromString<DropboxApi.Entry>(
			"""{".tag":"file","id":"id:abc","name":"replica_x.json.gz","path_lower":"/replica_x.json.gz",
			"content_hash":"deadbeef","server_modified":"2026-11-18T10:05:00Z","size":10}""",
		)
		assertEquals("id:abc", entry.id)
		assertEquals("deadbeef", entry.contentHash)
		assertEquals(Instant.parse("2026-11-18T10:05:00Z").toEpochMilli(), entry.modifiedAtMs)
	}

	@Test
	fun `unparsable time reads as unknown`() {
		assertEquals(0L, DropboxApi.Entry(serverModified = "yesterday").modifiedAtMs)
		assertEquals(0L, DropboxApi.Entry().modifiedAtMs)
	}
}
