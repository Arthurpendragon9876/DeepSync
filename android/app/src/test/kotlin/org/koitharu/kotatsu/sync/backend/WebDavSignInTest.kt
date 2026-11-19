package org.koitharu.kotatsu.sync.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sync.backend.webdav.WebDavSignIn
import org.koitharu.kotatsu.sync.backend.webdav.WebDavSignInException

class WebDavSignInTest {

	@Test
	fun `adds https and trims the trailing slash`() {
		assertEquals("https://cloud.example.com/dav", WebDavSignIn.normalize("  cloud.example.com/dav/ "))
		assertEquals("https://cloud.example.com", WebDavSignIn.normalize("https://cloud.example.com/"))
	}

	@Test
	fun `plain http only inside the local network`() {
		assertEquals("http://192.168.1.20:5005", WebDavSignIn.normalize("http://192.168.1.20:5005"))
		assertEquals("http://nas.local/dav", WebDavSignIn.normalize("http://nas.local/dav"))
		assertThrowsWebDav { WebDavSignIn.normalize("http://cloud.example.com") }
		assertThrowsWebDav { WebDavSignIn.normalize("http://8.8.8.8/dav") }
	}

	@Test
	fun `rejects empty and malformed addresses`() {
		assertThrowsWebDav { WebDavSignIn.normalize("   ") }
		assertThrowsWebDav { WebDavSignIn.normalize("https://") }
	}

	@Test
	fun `local host detection`() {
		assertTrue(WebDavSignIn.isLocalHost("localhost"))
		assertTrue(WebDavSignIn.isLocalHost("10.0.0.5"))
		assertTrue(WebDavSignIn.isLocalHost("172.20.1.1"))
		assertFalse(WebDavSignIn.isLocalHost("172.32.1.1"))
		assertFalse(WebDavSignIn.isLocalHost("example.com"))
		assertFalse(WebDavSignIn.isLocalHost("1.2.3"))
	}

	private fun assertThrowsWebDav(block: () -> Unit) {
		try {
			block()
		} catch (_: WebDavSignInException) {
			return
		}
		throw AssertionError("expected WebDavSignInException")
	}
}
