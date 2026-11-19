package org.koitharu.kotatsu.sync.backend.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 proof key for code exchange: lets a public client (no secret in the APK) do OAuth safely. */
object Pkce {

	private val random = SecureRandom()
	private val encoder = Base64.getUrlEncoder().withoutPadding()

	/** 43 URL-safe characters of 256-bit randomness. */
	fun newVerifier(): String = randomToken(32)

	fun challenge(verifier: String): String =
		encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

	/** Unguessable `state`, to reject a redirect this app didn't start. */
	fun newState(): String = randomToken(16)

	private fun randomToken(bytes: Int): String = encoder.encodeToString(ByteArray(bytes).also { random.nextBytes(it) })
}
