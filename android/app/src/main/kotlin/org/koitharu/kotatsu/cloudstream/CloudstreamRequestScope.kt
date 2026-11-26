package org.koitharu.kotatsu.cloudstream

import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request

/**
 * The Cloudstream provider a plugin call runs for, carried across the plugin's threads, and the request headers a
 * provider's requests get when the plugin leaves them out (User-Agent, its site as Referer, an Origin for POSTs),
 * which is what the official app's requests carry.
 */
internal object CloudstreamRequestScope {

	private val currentApi = ThreadLocal<MainAPI?>()

	/** The User-Agent for requests without one; Cloudstream's own by default. */
	@Volatile
	var userAgent: String? = null

	/** Runs [block] on the IO dispatcher as [api], failing with a timeout after [timeoutMs]. */
	suspend fun <T> call(api: MainAPI, timeoutMs: Long, block: suspend () -> T): T =
		withContext(currentApi.asContextElement(api) + Dispatchers.IO) {
			withTimeout(timeoutMs) { block() }
		}

	/** Like [call], but a timeout is not an error: [block] is simply abandoned and null is returned. */
	suspend fun <T> callOrNull(api: MainAPI, timeoutMs: Long, block: suspend () -> T): T? =
		kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
			withContext(currentApi.asContextElement(api) + Dispatchers.IO) { block() }
		}

	private fun withApiHeaders(request: Request, api: MainAPI): Request.Builder {
		val referer = request.header(REFERER)?.takeIf { it.isNotBlank() } ?: (api.mainUrl.trimEnd('/') + "/")
		return request.newBuilder().apply {
			val agent = userAgent
			if (request.header(USER_AGENT).isNullOrBlank() && !agent.isNullOrBlank()) header(USER_AGENT, agent)
			if (request.header(REFERER).isNullOrBlank()) header(REFERER, referer)
			if (request.method.uppercase() !in setOf("GET", "HEAD") && request.header(ORIGIN).isNullOrBlank()) {
				referer.toHttpUrlOrNull()?.let { header(ORIGIN, "${it.scheme}://${it.host}") }
			}
		}
	}

	/** Applies the provider's default headers to requests made inside [call]. */
	fun interceptor(): Interceptor = Interceptor { chain ->
		val api = currentApi.get()
		chain.proceed(if (api == null) chain.request() else withApiHeaders(chain.request(), api).build())
	}

	private const val USER_AGENT = "User-Agent"
	private const val REFERER = "Referer"
	private const val ORIGIN = "Origin"
}
