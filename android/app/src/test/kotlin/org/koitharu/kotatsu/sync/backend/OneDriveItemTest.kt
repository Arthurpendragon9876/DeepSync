package org.koitharu.kotatsu.sync.backend

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sync.backend.onedrive.OneDriveApi
import java.time.Instant

class OneDriveItemTest {

	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `decodes a children entry and prefers the content hash`() {
		val item = json.decodeFromString<OneDriveApi.Item>(
			"""{"id":"ABC!123","name":"replica_x.json.gz","cTag":"c:{1},2","eTag":"{1},3","size":10,
			"lastModifiedDateTime":"2026-11-18T10:05:00Z",
			"file":{"mimeType":"application/octet-stream","hashes":{"sha1Hash":"AAAA","quickXorHash":"BBBB"}}}""",
		)
		assertEquals("ABC!123", item.id)
		assertEquals("AAAA", item.checksum)
		assertEquals(Instant.parse("2026-11-18T10:05:00Z").toEpochMilli(), item.modifiedAtMs)
	}

	@Test
	fun `checksum falls back through quickXor, cTag and eTag`() {
		fun item(body: String) = json.decodeFromString<OneDriveApi.Item>(body)
		assertEquals("BBBB", item("""{"file":{"hashes":{"quickXorHash":"BBBB"}},"cTag":"c"}""").checksum)
		assertEquals("c", item("""{"file":{},"cTag":"c","eTag":"e"}""").checksum)
		assertEquals("e", item("""{"file":{},"eTag":"e"}""").checksum)
		assertNull(item("""{"file":{}}""").checksum)
	}

	@Test
	fun `a folder entry has no file facet`() {
		assertNull(json.decodeFromString<OneDriveApi.Item>("""{"id":"1","name":"sub","folder":{"childCount":0}}""").file)
	}

	@Test
	fun `upload chunks are a multiple of 320 KiB as Graph requires`() {
		assertEquals(0, OneDriveApi.CHUNK_SIZE % (320 * 1024))
		assertTrue(OneDriveApi.CHUNK_SIZE < OneDriveApi.SIMPLE_UPLOAD_LIMIT)
	}
}
