package org.koitharu.kotatsu.sync.backend.onedrive

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

/**
 * Minimal Microsoft Graph client for a personal OneDrive "App folder" (`Apps/<app name>`): OAuth 2 with
 * PKCE plus the file calls sync needs. Small files go up in one request; anything over 4 MB uses an
 * upload session in 3.2 MB chunks (Graph requires multiples of 320 KiB).
 */
@Singleton
class OneDriveApi @Inject constructor(
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
		@SerialName("expires_in") val expiresIn: Long = 3600,
	)

	@Serializable
	class Me(
		@SerialName("displayName") val displayName: String? = null,
		@SerialName("mail") val mail: String? = null,
		@SerialName("userPrincipalName") val userPrincipalName: String? = null,
	)

	@Serializable
	class Item(
		@SerialName("id") val id: String = "",
		@SerialName("name") val name: String = "",
		@SerialName("cTag") val cTag: String? = null,
		@SerialName("eTag") val eTag: String? = null,
		@SerialName("lastModifiedDateTime") val lastModified: String? = null,
		@SerialName("file") val file: FileFacet? = null,
	) {

		@Serializable
		class FileFacet(@SerialName("hashes") val hashes: Hashes? = null)

		@Serializable
		class Hashes(
			@SerialName("sha1Hash") val sha1: String? = null,
			@SerialName("quickXorHash") val quickXor: String? = null,
		)

		/** Content-based when OneDrive reports a hash, else the content tag: changes only when the bytes do. */
		val checksum: String? get() = file?.hashes?.sha1 ?: file?.hashes?.quickXor ?: cTag ?: eTag

		val modifiedAtMs: Long
			get() = lastModified?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
	}

	@Serializable
	private class Children(
		@SerialName("value") val value: List<Item> = emptyList(),
		@SerialName("@odata.nextLink") val nextLink: String? = null,
	)

	@Serializable
	private class UploadSession(@SerialName("uploadUrl") val uploadUrl: String)

	// ---- OAuth (personal accounts) ----

	suspend fun exchangeCode(clientId: String, code: String, verifier: String, redirectUri: String, scope: String): Tokens {
		val body = FormBody.Builder()
			.add("client_id", clientId)
			.add("grant_type", "authorization_code")
			.add("code", code)
			.add("redirect_uri", redirectUri)
			.add("code_verifier", verifier)
			.add("scope", scope)
			.build()
		return execute(Request.Builder().url(TOKEN_URL).post(body).build()) { it.parse<Tokens>() }
	}

	/** Microsoft rotates refresh tokens: the answer's new one replaces the old. */
	suspend fun refresh(clientId: String, refreshToken: String, scope: String): Tokens {
		val body = FormBody.Builder()
			.add("client_id", clientId)
			.add("grant_type", "refresh_token")
			.add("refresh_token", refreshToken)
			.add("scope", scope)
			.build()
		return execute(Request.Builder().url(TOKEN_URL).post(body).build()) { it.parse<Tokens>() }
	}

	// ---- account & files ----

	suspend fun me(token: String): Me = execute(get(token, "$GRAPH/me")) { it.parse<Me>() }

	/** Everything in the app folder (it holds nothing but sync files). The folder is created on first use. */
	suspend fun list(token: String): List<Item> {
		val result = ArrayList<Item>()
		var url: String? = "$GRAPH/me/drive/special/approot/children?\$select=id,name,cTag,eTag,lastModifiedDateTime,file&\$top=200"
		while (url != null) {
			val page = execute(get(token, url)) { it.parse<Children>() }
			result += page.value.filter { it.file != null }
			url = page.nextLink
		}
		return result
	}

	/** The redirect to the download URL is followed by OkHttp, which drops our token on the other host. */
	suspend fun download(token: String, id: String): ByteArray =
		execute(get(token, "$GRAPH/me/drive/items/$id/content")) { it.body.bytes() }

	/** Creates or replaces `name` in the app folder. */
	suspend fun upload(token: String, name: String, bytes: ByteArray): Item =
		if (bytes.size <= SIMPLE_UPLOAD_LIMIT) {
			val request = authorized(token, "$GRAPH/me/drive/special/approot:/$name:/content")
				.put(bytes.toRequestBody(OCTET))
				.build()
			execute(request) { it.parse<Item>() }
		} else {
			uploadInChunks(token, name, bytes)
		}

	suspend fun delete(token: String, id: String) {
		try {
			execute(authorized(token, "$GRAPH/me/drive/items/$id").delete().build()) { }
		} catch (e: SyncApiException) {
			if (e.code != 404) throw e // already gone
		}
	}

	private suspend fun uploadInChunks(token: String, name: String, bytes: ByteArray): Item {
		val body = buildJsonObject {
			put("item", buildJsonObject { put("@microsoft.graph.conflictBehavior", "replace") })
		}.toString().toRequestBody(JSON)
		val session = execute(
			authorized(token, "$GRAPH/me/drive/special/approot:/$name:/createUploadSession").post(body).build(),
		) { it.parse<UploadSession>() }
		var offset = 0
		while (offset < bytes.size) {
			val end = minOf(offset + CHUNK_SIZE, bytes.size)
			// The upload URL is pre-authorized: sending our token to it is both unnecessary and wrong.
			val request = Request.Builder()
				.url(session.uploadUrl)
				.header("Content-Range", "bytes $offset-${end - 1}/${bytes.size}")
				.put(bytes.copyOfRange(offset, end).toRequestBody(OCTET))
				.build()
			val item = execute(request) { if (it.code == 200 || it.code == 201) it.parse<Item>() else null }
			if (end == bytes.size) return item ?: throw SyncApiException(0, "OneDrive didn't confirm the upload of $name")
			offset = end
		}
		throw SyncApiException(0, "Nothing to upload for $name")
	}

	// ---- plumbing ----

	private fun authorized(token: String, url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token")

	private fun get(token: String, url: String): Request = authorized(token, url).get().build()

	/** Transient failures (network, 5xx) and short throttling are retried; the rest surface with the HTTP code. */
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
			} catch (e: Throttled) {
				if (e.retryAfterMs > MAX_THROTTLE_WAIT_MS) throw SyncApiException(e.code, e.message ?: "Throttled")
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

	private class Throttled(val code: Int, message: String, val retryAfterMs: Long) : IOException(message)

	private fun Response.toError(): IOException {
		val details = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
		val text = "OneDrive API error $code: $details"
		if (code == 429 || code == 503) {
			val seconds = header("Retry-After")?.toLongOrNull()
			if (seconds != null) return Throttled(code, text, seconds * 1000)
		}
		return SyncApiException(code, text)
	}

	private inline fun <reified T> Response.parse(): T = json.decodeFromString(body.string())

	companion object {

		const val GRAPH = "https://graph.microsoft.com/v1.0"
		const val AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"
		const val TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"

		/** Graph's simple upload limit is 4 MB; stay a little under. */
		const val SIMPLE_UPLOAD_LIMIT = 4_000_000

		/** 10 x 320 KiB: chunk sizes must be multiples of 320 KiB. */
		const val CHUNK_SIZE = 10 * 320 * 1024
		private const val MAX_ATTEMPTS = 3
		private const val RETRY_DELAY_MS = 1500L
		private const val MAX_THROTTLE_WAIT_MS = 60_000L
		private val JSON = "application/json; charset=utf-8".toMediaType()
		private val OCTET = "application/octet-stream".toMediaType()
	}
}
