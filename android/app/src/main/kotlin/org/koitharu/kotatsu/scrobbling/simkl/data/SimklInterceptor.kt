package org.koitharu.kotatsu.scrobbling.simkl.data

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val JSON = "application/json"
private const val APP_NAME = "deepsync"
private const val TOKEN_URL = "https://api.simkl.com/oauth2/token"

/**
 * Simkl AUTH V2: every call carries the client id, app name and version (as query parameters, plus the client id
 * header) and the user's bearer token on the account ones. The access token lives 7 days, so a 401 first tries the
 * refresh token once and only then asks the user to sign in again.
 */
class SimklInterceptor(private val storage: ScrobblerStorage, private val clientId: String) : Interceptor {

	private val refreshLock = Any()

	override fun intercept(chain: Interceptor.Chain): Response {
		val source = chain.request()
		val isAuthRequest = source.url.pathSegments.any { it == "oauth" || it == "oauth2" }
		val tokenUsed = storage.accessToken
		val response = chain.proceed(decorate(source, isAuthRequest, tokenUsed))
		if (isAuthRequest || tokenUsed == null || response.code != HttpURLConnection.HTTP_UNAUTHORIZED) return response

		val fresh = refreshAccessToken(tokenUsed)
		if (fresh == null) {
			response.close()
			throw ScrobblerAuthRequiredException(ScrobblerService.SIMKL)
		}
		response.close()
		return chain.proceed(decorate(source, false, fresh))
	}

	private fun decorate(source: Request, isAuthRequest: Boolean, token: String?): Request {
		val url = source.url.newBuilder()
			.setQueryParameter("client_id", clientId)
			.setQueryParameter("app-name", APP_NAME)
			.setQueryParameter("app-version", BuildConfig.VERSION_NAME)
			.build()
		val request = source.newBuilder().url(url)
		request.header(CommonHeaders.ACCEPT, JSON)
		if (source.body?.contentType() == null) request.header(CommonHeaders.CONTENT_TYPE, JSON)
		request.header("simkl-api-key", clientId)
		if (!isAuthRequest && token != null) request.header(CommonHeaders.AUTHORIZATION, "Bearer $token")
		return request.build()
	}

	/** Swaps the refresh token for a new access token; null when there is none or Simkl refuses it. */
	private fun refreshAccessToken(staleToken: String): String? = synchronized(refreshLock) {
		// Another call may have refreshed while this one waited
		storage.accessToken?.takeIf { it != staleToken }?.let { return it }
		val refresh = storage.refreshToken ?: return null
		runCatching {
			val body = FormBody.Builder()
				.add("grant_type", "refresh_token")
				.add("client_id", clientId)
				.add("refresh_token", refresh)
				.build()
			val request = Request.Builder()
				.url("$TOKEN_URL?client_id=$clientId&app-name=$APP_NAME&app-version=${BuildConfig.VERSION_NAME}")
				.header(CommonHeaders.ACCEPT, JSON)
				.post(body)
				.build()
			OkHttpClient().newCall(request).execute().use { r ->
				if (!r.isSuccessful) return@runCatching null
				val json = JSONObject(r.body?.string().orEmpty())
				val token = json.getString("access_token")
				storage.accessToken = token
				// The refresh token does not rotate, but keep whatever comes back
				json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { storage.refreshToken = it }
				token
			}
		}.getOrNull()
	}
}
