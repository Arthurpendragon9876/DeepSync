package org.koitharu.kotatsu.sync.backend.dropbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.domain.SyncApiException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Minimal Dropbox HTTP API v2 client for an "App folder" app: OAuth (PKCE) plus the file calls sync needs. */
@Singleton
class DropboxApi @Inject constructor(
	@BaseHttpClient private val baseHttpClient: dagger.Lazy<OkHttpClient>,
) {

	private val httpClient by lazy {
		baseHttpClient.get().newBuilder().apply {
			interceptors().clear()
			networkInterceptors().clear()
			cache(null)
			connectTimeout(20, TimeUnit.SECONDS)
			readTimeout(60, TimeUnit.SECONDS)
			writeTimeout(60, TimeUnit.SECONDS)
			callTimeout(0, TimeUnit.SECONDS)
		}.build()
	}

	private val json = Json { ignoreUnknownKeys = true }

	@Serializable
	class Tokens(
		@SerialName("access_token") val accessToken: String,
		@SerialName("refresh_token") val refreshToken: String? = null,
		@SerialName("expires_in") val expiresIn: Long = 14_400,
	)

	@Serializable
	class Account(
		@SerialName("email") val email: String? = null,
		@SerialName("name") val name: Name? = null,
		@SerialName("profile_photo_url") val photoUrl: String? = null,
	) {

		@Serializable
		class Name(@SerialName("display_name") val displayName: String? = null)
	}

	@Serializable
	class Entry(
		@SerialName(".tag") val tag: String = "file",
		@SerialName("id") val id: String = "",
		@SerialName("name") val name: String = "",
		@SerialName("content_hash") val contentHash: String? = null,
		@SerialName("server_modified") val serverModified: String? = null,
	) {

		val modifiedAtMs: Long
			get() = serverModified?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
	}

	@Serializable
	private class Listing(
		@SerialName("entries") val entries: List<Entry> = emptyList(),
		@SerialName("cursor") val cursor: String = "",
		@SerialName("has_more") val hasMore: Boolean = false,
	)

	// ---- OAuth ----

	suspend fun exchangeCode(appKey: String, code: String, verifier: String, redirectUri: String): Tokens {
		val body = FormBody.Builder()
			.add("code", code)
			.add("grant_type", "authorization_code")
			.add("client_id", appKey)
			.add("redirect_uri", redirectUri)
			.add("code_verifier", verifier)
			.build()
		return execute(Request.Builder().url(TOKEN_URL).post(body).build()) { it.parse<Tokens>() }
	}

	suspend fun refresh(appKey: String, refreshToken: String): Tokens {
		val body = FormBody.Builder()
			.add("grant_type", "refresh_token")
			.add("refresh_token", refreshToken)
			.add("client_id", appKey)
			.build()
		return execute(Request.Builder().url(TOKEN_URL).post(body).build()) { it.parse<Tokens>() }
	}

	/** Invalidates the token (and the refresh token it came from). Best effort. */
	suspend fun revoke(token: String) {
		runCatching {
			execute(rpc(token, "$API/auth/token/revoke", null)) { }
		}
	}

	// ---- account & files ----

	suspend fun account(token: String): Account =
		execute(rpc(token, "$API/users/get_current_account", null)) { it.parse<Account>() }

	/** Everything in the app folder (it holds nothing but sync files). */
	suspend fun list(token: String): List<Entry> {
		val result = ArrayList<Entry>()
		var page = executeList(
			rpc(
				token,
				"$API/files/list_folder",
				buildJsonObject {
					put("path", "")
					put("recursive", false)
					put("limit", 2000)
				}.toString(),
			),
		)
		result += page.entries
		while (page.hasMore) {
			page = executeList(
				rpc(token, "$API/files/list_folder/continue", buildJsonObject { put("cursor", page.cursor) }.toString()),
			)
			result += page.entries
		}
		return result.filter { it.tag == "file" }
	}

	private suspend fun executeList(request: Request): Listing = execute(request) { it.parse<Listing>() }

	suspend fun download(token: String, id: String): ByteArray {
		val request = Request.Builder()
			.url("$CONTENT/files/download")
			.header("Authorization", "Bearer $token")
			.header("Dropbox-API-Arg", buildJsonObject { put("path", id) }.toString())
			.post(ByteArray(0).toRequestBody(null))
			.build()
		return execute(request) { it.body.bytes() }
	}

	/** Creates or overwrites `/name` in the app folder. Only this device's own files are ever overwritten. */
	suspend fun upload(token: String, name: String, bytes: ByteArray): Entry {
		val arg = buildJsonObject {
			put("path", "/$name")
			put("mode", "overwrite")
			put("autorename", false)
			put("mute", true)
		}.toString()
		val request = Request.Builder()
			.url("$CONTENT/files/upload")
			.header("Authorization", "Bearer $token")
			.header("Dropbox-API-Arg", arg)
			.post(bytes.toRequestBody(OCTET))
			.build()
		return execute(request) { it.parse<Entry>() }
	}

	suspend fun delete(token: String, id: String) {
		try {
			execute(rpc(token, "$API/files/delete_v2", buildJsonObject { put("path", id) }.toString())) { }
		} catch (e: SyncApiException) {
			if (!(e.code == 409 && e.message.orEmpty().contains("not_found"))) throw e // already gone
		}
	}

	// ---- plumbing ----

	private fun rpc(token: String, url: String, jsonBody: String?): Request = Request.Builder()
		.url(url)
		.header("Authorization", "Bearer $token")
		// Endpoints without arguments must be sent with no body and no content type.
		.post(if (jsonBody == null) ByteArray(0).toRequestBody(null) else jsonBody.toRequestBody(JSON))
		.build()

	/** Transient failures (network, 5xx) and short rate limits are retried; the rest surface with the HTTP code. */
	private suspend fun <T> execute(request: Request, block: (Response) -> T): T {
		var attempt = 0
		while (true) {
			val wait: Long
			val failure: IOException = try {
				return withContext(Dispatchers.IO) {
					httpClient.newCall(request).execute().use { response ->
						if (!response.isSuccessful) throw response.toError()
						block(response)
					}
				}
			} catch (e: RateLimited) {
				if (e.retryAfterMs > MAX_RATE_LIMIT_WAIT_MS) throw SyncApiException(429, e.message ?: "Rate limited")
				wait = e.retryAfterMs
				e
			} catch (e: SyncApiException) {
				if (e.code < 500) throw e
				wait = RETRY_DELAY_MS shl attempt
				e
			} catch (e: IOException) {
				wait = RETRY_DELAY_MS shl attempt
				e
			}
			if (++attempt >= MAX_ATTEMPTS) throw failure
			delay(wait)
		}
	}

	private class RateLimited(message: String, val retryAfterMs: Long) : IOException(message)

	private fun Response.toError(): IOException {
		val details = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
		val text = "Dropbox API error $code: $details"
		if (code == 429) {
			val seconds = header("Retry-After")?.toLongOrNull() ?: 5L
			return RateLimited(text, seconds * 1000)
		}
		return SyncApiException(code, text)
	}

	private inline fun <reified T> Response.parse(): T = json.decodeFromString(body.string())

	private companion object {

		const val API = "https://api.dropboxapi.com/2"
		const val CONTENT = "https://content.dropboxapi.com/2"
		const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
		const val MAX_ATTEMPTS = 3
		const val RETRY_DELAY_MS = 1500L
		const val MAX_RATE_LIMIT_WAIT_MS = 60_000L
		val JSON = "application/json; charset=utf-8".toMediaType()
		val OCTET = "application/octet-stream".toMediaType()
	}
}
