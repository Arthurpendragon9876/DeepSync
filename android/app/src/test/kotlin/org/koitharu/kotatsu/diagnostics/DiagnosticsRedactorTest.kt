package org.koitharu.kotatsu.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {

	private fun assertGone(secret: String, text: String) {
		val out = DiagnosticsRedactor.redact(text)
		assertFalse("leaked '$secret' in: $out", out.contains(secret))
	}

	@Test
	fun `github tokens are removed`() {
		val token = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
		assertGone(token, "push failed with $token")
		val pat = "github_pat_11ABCDEFG0abcdefghijkl_mnopqrstuvwxyz0123456789"
		assertGone(pat, "auth: $pat")
	}

	@Test
	fun `dropbox and onedrive style bearer tokens are removed`() {
		val dropbox = "sl.B1234567890abcdefghijklmnopqrstuvwxyz"
		assertGone(dropbox, "Dropbox said $dropbox is bad")
		val graph = "EwBwA8l6BAAU7p9QDpi/YbMDTzXUkVrKLogXtlsAAfdLR2KuUtYm"
		assertGone(graph, "Authorization: Bearer $graph")
	}

	@Test
	fun `webdav credentials in the url are removed`() {
		val out = DiagnosticsRedactor.redact("PROPFIND https://alice:hunter2@dav.example.com/remote.php/dav failed")
		assertFalse(out.contains("alice"))
		assertFalse(out.contains("hunter2"))
		assertTrue(out.contains("dav.example.com"))
	}

	@Test
	fun `query strings and key value secrets are removed`() {
		assertGone("tok123456", "GET https://api.example.com/v1/me?token=tok123456&page=2")
		assertGone("s3cr3t", "client_secret=s3cr3t and password: s3cr3t")
		assertGone("sessionvalue", "Cookie: sessionvalue")
	}

	@Test
	fun `jwt style tracker tokens and emails are removed`() {
		val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.c2lnbmF0dXJl"
		assertGone(jwt, "anilist returned $jwt")
		assertGone("bob@example.com", "login for bob@example.com failed")
	}

	@Test
	fun `ordinary diagnostics text survives`() {
		val text = "Unable to resolve host example.com: HTTP 404 on /series/one-piece"
		assertTrue(DiagnosticsRedactor.redact(text) == text)
	}

	@Test
	fun `the exported report never contains a token`() {
		val token = "ghp_" + "Z9y8X7w6V5u4T3s2R1q0P9o8N7m6L5k4J3i2"
		val report = DiagnosticsReport.build(
			entries = listOf(
				SourceHealth(
					key = "MIHON_1",
					title = "Some Source",
					runtime = PluginRuntime.MIHON,
					state = HealthState.DEGRADED,
					lastKind = FailureKind.OTHER,
					lastMessage = "bad token $token at https://x.test/a?key=hidden",
				),
			),
			issues = listOf(PluginLoadIssue(PluginRuntime.CS3, "plugin.cs3", "Authorization: Bearer $token")),
			appVersion = "0.0.0",
			formatTime = { "now" },
		)
		assertFalse(report.contains(token))
		assertFalse(report.contains("hidden"))
		assertTrue(report.contains("Some Source"))
	}
}
