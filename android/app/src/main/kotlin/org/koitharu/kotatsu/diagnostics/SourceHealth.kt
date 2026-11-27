package org.koitharu.kotatsu.diagnostics

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class HealthState { UNKNOWN, HEALTHY, DEGRADED, SUSPECTED_DEAD }

/** Why a request failed. Being offline is deliberately not a kind: it is never recorded as a failure. */
enum class FailureKind { PLUGIN_LOAD, CLOUDFLARE_AUTH, TIMEOUT, ENDPOINT, OTHER }

enum class PluginRuntime { CS3, ANIYOMI, MIHON, LNREADER, BUILT_IN }

data class SourceHealth(
	val key: String,
	val title: String,
	val runtime: PluginRuntime,
	val state: HealthState = HealthState.UNKNOWN,
	/** Consecutive DNS / 404 / 410 failures; reset by any success or any other kind of failure. */
	val endpointFailures: Int = 0,
	val lastKind: FailureKind? = null,
	/** Already redacted. */
	val lastMessage: String? = null,
	val lastSuccessAt: Long = 0L,
	val lastFailureAt: Long = 0L,
)

/** A plugin that did not load, as reported by its runtime. */
data class PluginLoadIssue(
	val runtime: PluginRuntime,
	val name: String,
	val message: String,
)

object FailureClassifier {

	private const val MAX_DEPTH = 8
	private val deadStatus = Regex("""\b(404|410)\b""")

	/** Looks through the cause chain, outermost first, and returns the first recognizable kind. */
	fun classify(error: Throwable): FailureKind {
		var current: Throwable? = error
		var depth = 0
		while (current != null && depth < MAX_DEPTH) {
			classifyOne(current)?.let { return it }
			current = current.cause?.takeIf { it !== current }
			depth++
		}
		return FailureKind.OTHER
	}

	private fun classifyOne(t: Throwable): FailureKind? {
		val name = t.javaClass.simpleName
		return when {
			t is LinkageError || t is ClassNotFoundException ||
				name.contains("QuickJs", ignoreCase = true) ||
				name.contains("ExtensionLoad", ignoreCase = true) -> FailureKind.PLUGIN_LOAD

			name.contains("CloudFlare", ignoreCase = true) ||
				name.contains("AuthRequired", ignoreCase = true) ||
				name.contains("Captcha", ignoreCase = true) -> FailureKind.CLOUDFLARE_AUTH

			t is UnknownHostException -> FailureKind.ENDPOINT

			(name.contains("HttpStatus") || name == "HttpException") &&
				deadStatus.containsMatchIn(t.message.orEmpty()) -> FailureKind.ENDPOINT

			t is SocketTimeoutException || name.contains("Timeout", ignoreCase = true) ||
				(t is InterruptedIOException && t.message.orEmpty().contains("timeout", ignoreCase = true)) ->
				FailureKind.TIMEOUT

			else -> null
		}
	}
}

/**
 * The health state machine, with no Android dependencies.
 *
 *  - Offline never counts: nothing is recorded, so an unseen source stays UNKNOWN and a known one keeps its state.
 *  - A success makes the source HEALTHY and clears its streak.
 *  - A DNS / 404 / 410 failure counts toward a streak; [deadThreshold] in a row make it SUSPECTED_DEAD, fewer is DEGRADED.
 *  - Any other failure (timeout, Cloudflare / auth, plugin load error, unknown) makes it DEGRADED and breaks the streak.
 */
class SourceHealthBook(
	private val deadThreshold: Int = DEAD_THRESHOLD,
	private val clock: () -> Long = System::currentTimeMillis,
) {

	private val entries = LinkedHashMap<String, SourceHealth>()

	/** Returns true when the visible state changed (not merely a timestamp). */
	@Synchronized
	fun onSuccess(key: String, title: String, runtime: PluginRuntime): Boolean {
		val previous = entries[key]
		val next = (previous ?: SourceHealth(key, title, runtime)).copy(
			title = title,
			runtime = runtime,
			state = HealthState.HEALTHY,
			endpointFailures = 0,
			lastSuccessAt = clock(),
		)
		entries[key] = next
		return previous == null || previous.state != next.state || previous.endpointFailures != 0
	}

	@Synchronized
	fun onFailure(key: String, title: String, runtime: PluginRuntime, error: Throwable, isOnline: Boolean): Boolean {
		if (!isOnline) {
			return false
		}
		val kind = FailureClassifier.classify(error)
		val previous = entries[key] ?: SourceHealth(key, title, runtime)
		val streak = if (kind == FailureKind.ENDPOINT) previous.endpointFailures + 1 else 0
		val state = if (kind == FailureKind.ENDPOINT && streak >= deadThreshold) {
			HealthState.SUSPECTED_DEAD
		} else {
			HealthState.DEGRADED
		}
		val message = DiagnosticsRedactor.redact(error.message ?: error.javaClass.simpleName).take(MAX_MESSAGE)
		val next = previous.copy(
			title = title,
			runtime = runtime,
			state = state,
			endpointFailures = streak,
			lastKind = kind,
			lastMessage = message,
			lastFailureAt = clock(),
		)
		entries[key] = next
		return previous.state != next.state || previous.lastKind != next.lastKind ||
			previous.endpointFailures != next.endpointFailures
	}

	@Synchronized
	fun snapshot(): List<SourceHealth> = entries.values.toList()

	@Synchronized
	fun clear() = entries.clear()

	companion object {

		const val DEAD_THRESHOLD = 3
		private const val MAX_MESSAGE = 200
	}
}

/** Plain-text report for sharing: only health states and redacted messages, never settings, tokens or URLs with queries. */
object DiagnosticsReport {

	fun build(
		entries: List<SourceHealth>,
		issues: List<PluginLoadIssue>,
		appVersion: String,
		formatTime: (Long) -> String,
	): String = buildString {
		appendLine("DeepSync diagnostics")
		appendLine("App version: $appVersion")
		appendLine()
		appendLine("Plugin load errors: ${issues.size}")
		for (issue in issues) {
			appendLine("- [${issue.runtime}] ${DiagnosticsRedactor.redact(issue.name)}: ${DiagnosticsRedactor.redact(issue.message).take(300)}")
		}
		appendLine()
		appendLine("Sources seen this session: ${entries.size}")
		for (e in entries.sortedByDescending { it.state.ordinal }) {
			append("- [${e.runtime}] ${DiagnosticsRedactor.redact(e.title)}: ${e.state}")
			e.lastKind?.let { append(", last failure $it") }
			if (e.endpointFailures > 0) append(", ${e.endpointFailures} endpoint failures in a row")
			if (e.lastSuccessAt > 0) append(", last ok ${formatTime(e.lastSuccessAt)}")
			if (e.lastFailureAt > 0) append(", last failed ${formatTime(e.lastFailureAt)}")
			e.lastMessage?.let { append("\n    ").append(DiagnosticsRedactor.redact(it)) }
			appendLine()
		}
	}
}
