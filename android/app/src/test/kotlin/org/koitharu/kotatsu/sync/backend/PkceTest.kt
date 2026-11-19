package org.koitharu.kotatsu.sync.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sync.backend.auth.Pkce

class PkceTest {

	@Test
	fun `challenge matches the RFC 7636 test vector`() {
		assertEquals(
			"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
			Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
		)
	}

	@Test
	fun `verifier is long enough, url safe and unique`() {
		val a = Pkce.newVerifier()
		val b = Pkce.newVerifier()
		assertEquals(43, a.length) // the RFC allows 43..128
		assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
		assertNotEquals(a, b)
	}

	@Test
	fun `state is unique`() {
		assertNotEquals(Pkce.newState(), Pkce.newState())
	}
}
