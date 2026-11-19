package org.koitharu.kotatsu.sync.backend.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.domain.SyncApiException
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal GitHub REST client: the OAuth device flow, repository provisioning, and the file
 * operations sync needs. Reads go through the Git Data API (one recursive tree call lists everything,
 * blobs are fetched by sha and have no 1 MB limit); writes use the Contents API.
 */
@Singleton
class GithubApi @Inject constructor(
	@BaseHttpClient private val baseHttpClient: dagger.Lazy<OkHttpClient>,
) {

	// Clean client for the same reasons as the Drive one (manga interceptors/cache interfere), and
	// lazy because screens build this class on the main thread.
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
	class User(
		@SerialName("login") val login: String,
		@SerialName("name") val name: String? = null,
		@SerialName("avatar_url") val avatarUrl: String? = null,
	)

	@Serializable
	class Repo(
		@SerialName("name") val name: String,
		@SerialName("full_name") val fullName: String,
		@SerialName("private") val isPrivate: Boolean,
		@SerialName("default_branch") val defaultBranch: String = "main",
	)

	@Serializable
	class TreeEntry(
		@SerialName("path") val path: String,
		@SerialName("type") val type: String,
		@SerialName("sha") val sha: String,
	)

	@Serializable
	private class Tree(
		@SerialName("tree") val tree: List<TreeEntry> = emptyList(),
		@SerialName("truncated") val truncated: Boolean = false,
	)

	@Serializable
	private class PutResponse(@SerialName("content") val content: Content? = null) {

		@Serializable
		class Content(@SerialName("sha") val sha: String)
	}

	@Serializable
	private class ContentInfo(@SerialName("sha") val sha: String)

	@Serializable
	class DeviceCode(
		@SerialName("device_code") val deviceCode: String,
		@SerialName("user_code") val userCode: String,
		@SerialName("verification_uri") val verificationUri: String,
		@SerialName("expires_in") val expiresIn: Int = 900,
		@SerialName("interval") val interval: Int = 5,
	)

	@Serializable
	class TokenPoll(
		@SerialName("access_token") val accessToken: String? = null,
		@SerialName("error") val error: String? = null,
		@SerialName("interval") val interval: Int? = null,
	)

	sealed interface TreeResult {

		/** The listing is unchanged since the request's ETag (a 304: free against the rate limit). */
		data object NotModified : TreeResult

		class Fresh(val etag: String?, val entries: List<TreeEntry>) : TreeResult
	}

	// ---- OAuth device flow (github.com, no secret needed) ----

	suspend fun requestDeviceCode(clientId: String, scope: String): DeviceCode {
		val body = FormBody.Builder().add("client_id", clientId).add("scope", scope).build()
		val request = Request.Builder().url(DEVICE_CODE_URL).post(body).header("Accept", "application/json").build()
		return execute(request) { it.parse<DeviceCode>() }
	}

	suspend fun pollDeviceToken(clientId: String, deviceCode: String): TokenPoll {
		val body = FormBody.Builder()
			.add("client_id", clientId)
			.add("device_code", deviceCode)
			.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
			.build()
		val request = Request.Builder().url(ACCESS_TOKEN_URL).post(body).header("Accept", "application/json").build()
		// Pending / slow_down / expired are reported as 200 + "error", not as HTTP errors.
		return execute(request) { it.parse<TokenPoll>() }
	}

	// ---- account & repository ----

	suspend fun user(token: String): User =
		execute(api(token, "$API/user").get().build()) { it.parse<User>() }

	/** The repository, or null when it doesn't exist (or the token can't see it). */
	suspend fun repo(token: String, fullName: String): Repo? = try {
		execute(api(token, "$API/repos/$fullName").get().build()) { it.parse<Repo>() }
	} catch (e: SyncApiException) {
		if (e.code == 404) null else throw e
	}

	suspend fun createPrivateRepo(token: String, name: String, description: String): Repo {
		val payload = buildJsonObject {
			put("name", name)
			put("description", description)
			put("private", true)
			put("auto_init", true) // a README gives the branch its first commit
		}.toString()
		val request = api(token, "$API/user/repos").post(payload.toRequestBody(JSON)).build()
		return execute(request) { it.parse<Repo>() }
	}

	// ---- files ----

	suspend fun tree(token: String, repo: String, branch: String, etag: String?): TreeResult {
		val url = "$API/repos/$repo/git/trees/$branch".toHttpUrl().newBuilder().addQueryParameter("recursive", "1").build()
		val request = api(token, url.toString()).get().apply { etag?.let { header("If-None-Match", it) } }.build()
		return try {
			execute(request, allowNotModified = true) { response ->
				if (response.code == 304) {
					TreeResult.NotModified
				} else {
					val tree = response.parse<Tree>()
					if (tree.truncated) throw SyncApiException(0, "The sync repository has too many files")
					TreeResult.Fresh(response.header("ETag"), tree.tree.filter { it.type == "blob" })
				}
			}
		} catch (e: SyncApiException) {
			// 409 "Git Repository is empty": a repo someone created without a first commit.
			if (e.code == 409) TreeResult.Fresh(null, emptyList()) else throw e
		}
	}

	/** Raw blob bytes by sha: immutable, any size up to GitHub's 100 MB limit, no base64. */
	suspend fun blob(token: String, repo: String, sha: String): ByteArray {
		val request = api(token, "$API/repos/$repo/git/blobs/$sha")
			.header("Accept", "application/vnd.github.raw+json")
			.get()
			.build()
		return execute(request) { it.body.bytes() }
	}

	/** Sha of the file on [branch], or null if it doesn't exist. Works for files over 1 MB too. */
	suspend fun currentSha(token: String, repo: String, branch: String, path: String): String? {
		val url = "$API/repos/$repo/contents/$path".toHttpUrl().newBuilder().addQueryParameter("ref", branch).build()
		return try {
			execute(api(token, url.toString()).get().build()) { it.parse<ContentInfo>().sha }
		} catch (e: SyncApiException) {
			if (e.code == 404) null else throw e
		}
	}

	/** Creates the file ([sha] null) or replaces it; returns the new blob sha. */
	suspend fun put(
		token: String,
		repo: String,
		branch: String,
		path: String,
		bytes: ByteArray,
		sha: String?,
		message: String,
	): String {
		val payload = buildJsonObject {
			put("message", message)
			put("content", Base64.getEncoder().encodeToString(bytes))
			put("branch", branch)
			if (sha != null) put("sha", sha)
		}.toString()
		val request = api(token, "$API/repos/$repo/contents/$path").put(payload.toRequestBody(JSON)).build()
		return execute(request) { response ->
			response.parse<PutResponse>().content?.sha ?: throw SyncApiException(response.code, "GitHub returned no file sha")
		}
	}

	suspend fun delete(token: String, repo: String, branch: String, path: String, sha: String, message: String) {
		val payload = buildJsonObject {
			put("message", message)
			put("sha", sha)
			put("branch", branch)
		}.toString()
		val request = api(token, "$API/repos/$repo/contents/$path")
			.method("DELETE", payload.toRequestBody(JSON))
			.build()
		try {
			execute(request) { }
		} catch (e: SyncApiException) {
			if (e.code != 404) throw e // already gone
		}
	}

	// ---- plumbing ----

	private fun api(token: String, url: String) = Request.Builder()
		.url(url)
		.header("Authorization", "Bearer $token")
		.header("Accept", "application/vnd.github+json")
		.header("X-GitHub-Api-Version", API_VERSION)
		.header("User-Agent", USER_AGENT)

	/**
	 * Transient failures (network, 5xx) and rate limits that clear within a minute are retried;
	 * anything else surfaces as [SyncApiException] with the HTTP code.
	 */
	private suspend fun <T> execute(
		request: Request,
		allowNotModified: Boolean = false,
		block: (Response) -> T,
	): T {
		var attempt = 0
		while (true) {
			val wait: Long
			val failure: IOException = try {
				return withContext(Dispatchers.IO) {
					httpClient.newCall(request).execute().use { response ->
						if (allowNotModified && response.code == 304) return@use block(response)
						if (!response.isSuccessful) throw response.toError()
						block(response)
					}
				}
			} catch (e: RateLimitedException) {
				if (e.retryAfterMs > MAX_RATE_LIMIT_WAIT_MS) throw SyncApiException(e.code, e.message ?: "Rate limited")
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

	private class RateLimitedException(code: Int, message: String, val retryAfterMs: Long) : IOException(message) {
		val code = code
	}

	private fun Response.toError(): IOException {
		val details = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
		val text = "GitHub API error $code: $details"
		if (code == 429 || code == 403) {
			// Primary limit: remaining == 0 + reset time. Secondary limit: Retry-After.
			val retryAfter = header("Retry-After")?.toLongOrNull()?.times(1000)
			val resetMs = if (header("X-RateLimit-Remaining") == "0") {
				header("X-RateLimit-Reset")?.toLongOrNull()?.let { it * 1000 - System.currentTimeMillis() }
			} else {
				null
			}
			val waitMs = retryAfter ?: resetMs
			if (waitMs != null) return RateLimitedException(code, text, waitMs.coerceAtLeast(1000L))
		}
		return SyncApiException(code, text)
	}

	private inline fun <reified T> Response.parse(): T = json.decodeFromString(body.string())

	private companion object {

		const val API = "https://api.github.com"
		const val DEVICE_CODE_URL = "https://github.com/login/device/code"
		const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
		const val API_VERSION = "2022-11-28"
		const val USER_AGENT = "DeepSync-Sync"
		const val MAX_ATTEMPTS = 3
		const val RETRY_DELAY_MS = 1500L
		const val MAX_RATE_LIMIT_WAIT_MS = 60_000L
		val JSON = "application/json; charset=UTF-8".toMediaType()
	}
}
