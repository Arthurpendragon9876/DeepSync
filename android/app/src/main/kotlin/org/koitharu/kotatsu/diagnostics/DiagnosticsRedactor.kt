package org.koitharu.kotatsu.diagnostics

/**
 * Strips anything that could be a credential from text that ends up in the diagnostics screen or its exported
 * report: sync tokens (GitHub, Dropbox, OneDrive, WebDAV), tracker tokens (AniList, MAL, ...), cookies and
 * query strings. Applied when a failure is recorded and again when a report is built.
 */
object DiagnosticsRedactor {

	const val MASK = "[redacted]"

	private val urlUserInfo = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^/\s@]+@""")
	private val urlQuery = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://[^\s?#"'<>]*)[?#][^\s"'<>]*""")
	private val bearer = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]{8,}""")
	private val keyValue = Regex(
		"""(?i)\b(access[_-]?token|refresh[_-]?token|id[_-]?token|token|api[_-]?key|apikey|client[_-]?secret|secret|""" +
			"""password|passwd|pwd|authorization|cookie|set-cookie)\s*[=:]\s*[^\s&;,"']+""",
	)
	private val githubToken = Regex("""\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})""")
	private val dropboxToken = Regex("""\bsl\.[A-Za-z0-9_-]{20,}""")
	private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.?[A-Za-z0-9_-]*""")
	private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
	private val longOpaque = Regex("""\b[A-Za-z0-9_-]{32,}\b""")

	fun redact(text: String): String {
		var result = text
		result = urlUserInfo.replace(result) { "${it.groupValues[1]}$MASK@" }
		result = urlQuery.replace(result) { "${it.groupValues[1]}?$MASK" }
		result = bearer.replace(result) { "${it.groupValues[1]} $MASK" }
		result = keyValue.replace(result) { "${it.groupValues[1]}=$MASK" }
		result = githubToken.replace(result, MASK)
		result = dropboxToken.replace(result, MASK)
		result = jwt.replace(result, MASK)
		result = email.replace(result, MASK)
		result = longOpaque.replace(result, MASK)
		return result
	}
}
