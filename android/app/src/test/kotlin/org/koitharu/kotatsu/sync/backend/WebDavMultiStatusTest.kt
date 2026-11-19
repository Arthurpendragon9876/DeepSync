package org.koitharu.kotatsu.sync.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sync.backend.webdav.WebDavMultiStatus
import java.time.Instant

class WebDavMultiStatusTest {

	private val sample = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
 <d:response>
  <d:href>/remote.php/dav/files/me/DeepSync/</d:href>
  <d:propstat>
   <d:prop>
    <d:getlastmodified>Wed, 18 Nov 2026 10:00:00 GMT</d:getlastmodified>
    <d:resourcetype><d:collection/></d:resourcetype>
    <d:getetag>&quot;abc&quot;</d:getetag>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
  <d:propstat>
   <d:prop><d:getcontentlength/></d:prop>
   <d:status>HTTP/1.1 404 Not Found</d:status>
  </d:propstat>
 </d:response>
 <d:response>
  <d:href>/remote.php/dav/files/me/DeepSync/replica_ab12.json.gz</d:href>
  <d:propstat>
   <d:prop>
    <d:getlastmodified>Wed, 18 Nov 2026 10:05:00 GMT</d:getlastmodified>
    <d:getcontentlength>1234</d:getcontentlength>
    <d:resourcetype/>
    <d:getetag>"5f3e"</d:getetag>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>"""

	@Test
	fun `parses folder and file entries`() {
		val entries = WebDavMultiStatus.parse(sample.toByteArray())
		assertEquals(2, entries.size)

		val folder = entries[0]
		assertEquals("DeepSync", folder.name)
		assertTrue(folder.isCollection)

		val file = entries[1]
		assertEquals("replica_ab12.json.gz", file.name)
		assertFalse(file.isCollection)
		assertEquals("5f3e", file.etag)
		assertEquals("5f3e", file.checksum)
		assertEquals(1234L, file.size)
		assertEquals(Instant.parse("2026-11-18T10:05:00Z").toEpochMilli(), file.modifiedAtMs)
	}

	@Test
	fun `checksum falls back to size and time without an etag`() {
		val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/x/cover_1</d:href><d:propstat><d:prop>
			<d:getcontentlength>7</d:getcontentlength><d:resourcetype/></d:prop>
			<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
		val entry = WebDavMultiStatus.parse(xml.toByteArray()).single()
		assertNull(entry.etag)
		assertEquals("7-0", entry.checksum)
	}

	@Test
	fun `href handling`() {
		assertEquals("replica_a b.json", WebDavMultiStatus.nameOf("/dav/DeepSync/replica_a%20b.json"))
		assertEquals("DeepSync", WebDavMultiStatus.nameOf("https://h/dav/DeepSync/"))
		assertEquals("a+b", WebDavMultiStatus.nameOf("/dav/a+b"))
	}

	@Test
	fun `etag cleanup`() {
		assertEquals("abc", WebDavMultiStatus.cleanEtag("W/\"abc\""))
		assertEquals("abc", WebDavMultiStatus.cleanEtag("\"abc\""))
		assertNull(WebDavMultiStatus.cleanEtag("\"\""))
	}

	@Test(expected = Exception::class)
	fun `rejects documents with a DTD`() {
		WebDavMultiStatus.parse("""<!DOCTYPE x [<!ENTITY e "boom">]><d:multistatus xmlns:d="DAV:">&e;</d:multistatus>""".toByteArray())
	}
}
