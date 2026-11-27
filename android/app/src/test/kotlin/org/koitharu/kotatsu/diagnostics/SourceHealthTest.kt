package org.koitharu.kotatsu.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SourceHealthTest {

	// Named like the real ones so the classifier's name matching is exercised without Android classes
	private class CloudFlareProtectedException(message: String) : IOException(message)
	private class HttpStatusException(message: String) : IOException(message)

	private val book = SourceHealthBook(clock = { 1_000L })

	private fun SourceHealthBook.fail(error: Throwable, online: Boolean = true) =
		onFailure("MIHON_1", "Source", PluginRuntime.MIHON, error, online)

	private fun SourceHealthBook.state() = snapshot().single().state

	@Test
	fun `offline failures leave an unseen source unknown`() {
		assertFalse(book.fail(UnknownHostException("no network"), online = false))
		assertTrue(book.snapshot().isEmpty())
	}

	@Test
	fun `offline failures never move a known source or its streak`() {
		book.fail(UnknownHostException("a"))
		book.fail(UnknownHostException("b"))
		repeat(5) { book.fail(UnknownHostException("offline"), online = false) }
		val entry = book.snapshot().single()
		assertEquals(HealthState.DEGRADED, entry.state)
		assertEquals(2, entry.endpointFailures)
	}

	@Test
	fun `a single timeout is degraded`() {
		book.fail(SocketTimeoutException("timeout"))
		assertEquals(HealthState.DEGRADED, book.state())
		assertEquals(FailureKind.TIMEOUT, book.snapshot().single().lastKind)
	}

	@Test
	fun `three consecutive dns or 404 failures are suspected dead`() {
		book.fail(UnknownHostException("dns"))
		book.fail(HttpStatusException("HTTP 404"))
		assertEquals(HealthState.DEGRADED, book.state())
		book.fail(UnknownHostException("dns"))
		assertEquals(HealthState.SUSPECTED_DEAD, book.state())
		assertEquals(3, book.snapshot().single().endpointFailures)
	}

	@Test
	fun `a timeout in between breaks the endpoint streak`() {
		book.fail(UnknownHostException("dns"))
		book.fail(UnknownHostException("dns"))
		book.fail(SocketTimeoutException("timeout"))
		book.fail(UnknownHostException("dns"))
		assertEquals(HealthState.DEGRADED, book.state())
		assertEquals(1, book.snapshot().single().endpointFailures)
	}

	@Test
	fun `a success recovers a suspected dead source`() {
		repeat(3) { book.fail(UnknownHostException("dns")) }
		assertEquals(HealthState.SUSPECTED_DEAD, book.state())
		book.onSuccess("MIHON_1", "Source", PluginRuntime.MIHON)
		assertEquals(HealthState.HEALTHY, book.state())
		assertEquals(0, book.snapshot().single().endpointFailures)
	}

	@Test
	fun `cloudflare and plugin errors are degraded and never count toward dead`() {
		repeat(4) { book.fail(CloudFlareProtectedException("challenge")) }
		assertEquals(HealthState.DEGRADED, book.state())
		assertEquals(FailureKind.CLOUDFLARE_AUTH, book.snapshot().single().lastKind)
		book.fail(NoClassDefFoundError("eu/kanade/Foo"))
		assertEquals(FailureKind.PLUGIN_LOAD, book.snapshot().single().lastKind)
		assertEquals(HealthState.DEGRADED, book.state())
	}

	@Test
	fun `the cause chain is searched and a non 404 status is not an endpoint failure`() {
		assertEquals(
			FailureKind.ENDPOINT,
			FailureClassifier.classify(RuntimeException("wrapped", UnknownHostException("dns"))),
		)
		assertEquals(FailureKind.OTHER, FailureClassifier.classify(HttpStatusException("HTTP 500")))
	}

	@Test
	fun `recorded messages are redacted`() {
		book.fail(IOException("failed https://example.com/api?access_token=abc123secret&x=1"))
		val message = book.snapshot().single().lastMessage.orEmpty()
		assertFalse(message.contains("abc123secret"))
		assertFalse(message.contains("access_token"))
	}
}
