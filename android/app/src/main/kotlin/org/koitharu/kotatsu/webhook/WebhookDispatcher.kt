package org.koitharu.kotatsu.webhook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends opt-in Discord and generic JSON webhooks. Off by default; [dispatch] never blocks the caller
 * and never throws, so a dead webhook cannot slow down or break a sync, backup or library update.
 */
@Singleton
class WebhookDispatcher @Inject constructor(
	@BaseHttpClient private val baseHttpClient: dagger.Lazy<OkHttpClient>,
	private val settings: AppSettings,
) {

	sealed interface TestResult {
		data object Sent : TestResult
		data object NoTarget : TestResult
		class Failed(val detail: String) : TestResult
	}

	private enum class Kind { DISCORD, JSON }

	private class Target(val kind: Kind, val url: String)

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val lastSent = ConcurrentHashMap<WebhookEventType, Long>()

	// No interceptors: the app's manga-source headers and cookies must never go to a webhook, and
	// redirects stay off so a webhook URL cannot bounce the request somewhere else.
	private val client by lazy {
		baseHttpClient.get().newBuilder().apply {
			interceptors().clear()
			networkInterceptors().clear()
			cache(null)
			followRedirects(false)
			followSslRedirects(false)
			connectTimeout(10, TimeUnit.SECONDS)
			readTimeout(15, TimeUnit.SECONDS)
			writeTimeout(15, TimeUnit.SECONDS)
			callTimeout(30, TimeUnit.SECONDS)
		}.build()
	}

	/** Fire and forget. Does nothing unless webhooks are on, this event type is on and a URL is set. */
	fun dispatch(event: WebhookEvent) {
		if (!settings.isWebhooksEnabled) return
		event.type.settingKey?.let { if (!settings.isWebhookEventEnabled(it)) return }
		if (isThrottled(event.type)) return
		val targets = targets()
		if (targets.isEmpty()) return
		scope.launch {
			for (target in targets) {
				try {
					send(target, event)
				} catch (e: Exception) {
					e.printStackTraceDebug()
				}
			}
		}
	}

	/** Sends a test message to every configured URL, ignoring the on/off switches. */
	suspend fun sendTest(): TestResult {
		val targets = targets()
		if (targets.isEmpty()) return TestResult.NoTarget
		for (target in targets) {
			try {
				send(target, WebhookEvent.test())
			} catch (e: Exception) {
				// The class name only: an exception message may contain the URL, which holds the secret.
				return TestResult.Failed(if (e is IOException && e.message?.startsWith("HTTP ") == true) e.message.orEmpty() else e.javaClass.simpleName)
			}
		}
		return TestResult.Sent
	}

	private fun targets(): List<Target> = buildList {
		settings.webhookDiscordUrl?.takeIf { WebhookPayloads.isValidUrl(it, requireHttps = true) }?.let { add(Target(Kind.DISCORD, it)) }
		settings.webhookJsonUrl?.takeIf { WebhookPayloads.isValidUrl(it, requireHttps = false) }?.let { add(Target(Kind.JSON, it)) }
	}

	/** Sync runs whenever the app goes to the background: one message per half hour is plenty. */
	private fun isThrottled(type: WebhookEventType): Boolean {
		if (type != WebhookEventType.SYNC_COMPLETED && type != WebhookEventType.SYNC_FAILED) return false
		val now = System.currentTimeMillis()
		val previous = lastSent[type]
		if (previous != null && now - previous < THROTTLE_MS) return true
		lastSent[type] = now
		return false
	}

	private suspend fun send(target: Target, event: WebhookEvent) = withContext(Dispatchers.IO) {
		val body = when (target.kind) {
			Kind.DISCORD -> WebhookPayloads.discord(event)
			Kind.JSON -> WebhookPayloads.json(event, System.currentTimeMillis())
		}
		val request = Request.Builder()
			.url(target.url)
			.header("User-Agent", "DeepSync-Webhook")
			.post(body.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
		}
	}

	private companion object {

		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		val THROTTLE_MS = TimeUnit.MINUTES.toMillis(30)
	}
}
