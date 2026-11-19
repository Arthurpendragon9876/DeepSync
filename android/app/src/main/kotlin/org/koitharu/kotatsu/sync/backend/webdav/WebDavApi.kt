package org.koitharu.kotatsu.sync.backend.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.domain.SyncApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where and as whom to talk to a WebDAV server. [baseUrl] is any folder the user can write to. */
class WebDavCredentials(val baseUrl: String, val user: String, val password: String) {

	/** The folder DeepSync keeps its files in, inside [baseUrl]. */
	val folderUrl: HttpUrl
		get() = baseUrl.trimEnd('/').plus("/$FOLDER/").toHttpUrl()

	companion object {

		const val FOLDER = "DeepSync"
	}
}

/** The few WebDAV verbs sync needs (PROPFIND, GET, PUT, DELETE, MKCOL) over OkHttp, with Basic auth. */
@Singleton
class WebDavApi @Inject constructor(
	@BaseHttpClient private val baseHttpClient: dagger.Lazy<OkHttpClient>,
) {

	private val httpClient by lazy {
		baseHttpClient.get().newBuilder().apply {
			interceptors().clear()
			networkInterceptors().clear()
			cache(null)
			followRedirects(true)
			connectTimeout(20, TimeUnit.SECONDS)
			readTimeout(60, TimeUnit.SECONDS)
			writeTimeout(60, TimeUnit.SECONDS)
			callTimeout(0, TimeUnit.SECONDS)
		}.build()
	}

	/** Result of a write: the ETag the server reported, if it did. */
	class Written(val etag: String?)

	/** Checks the address and login, then makes sure the DeepSync folder exists. Throws with a user-facing message. */
	suspend fun connect(credentials: WebDavCredentials) {
		val base = credentials.baseUrl.trimEnd('/').plus("/").toHttpUrl()
		val probe = propfind(credentials, base, depth = 0, allowMissing = true)
		if (probe == null) throw SyncApiException(404, "That folder doesn't exist on the server.")
		if (propfind(credentials, credentials.folderUrl, depth = 0, allowMissing = true) == null) {
			mkcol(credentials, credentials.folderUrl)
		}
	}

	/** Files directly inside the DeepSync folder. A missing folder is created and reads as empty. */
	suspend fun list(credentials: WebDavCredentials): List<WebDavEntry> {
		val folder = credentials.folderUrl
		val entries = propfind(credentials, folder, depth = 1, allowMissing = true)
		if (entries == null) {
			mkcol(credentials, folder)
			return emptyList()
		}
		return entries.filter { !it.isCollection }
	}

	suspend fun download(credentials: WebDavCredentials, name: String): ByteArray =
		execute(credentials, fileRequest(credentials, name).get().build()) { it.body.bytes() }

	suspend fun put(credentials: WebDavCredentials, name: String, bytes: ByteArray): Written {
		val request = fileRequest(credentials, name).put(bytes.toRequestBody(OCTET)).build()
		return execute(credentials, request) { Written(it.header("ETag")?.let { tag -> WebDavMultiStatus.cleanEtag(tag) }) }
	}

	suspend fun delete(credentials: WebDavCredentials, name: String) {
		try {
			execute(credentials, fileRequest(credentials, name).delete().build()) { }
		} catch (e: SyncApiException) {
			if (e.code != 404) throw e // already gone
		}
	}

	private suspend fun mkcol(credentials: WebDavCredentials, url: HttpUrl) {
		val request = Request.Builder().url(url).method("MKCOL", null).header("Authorization", auth(credentials)).build()
		try {
			execute(credentials, request) { }
		} catch (e: SyncApiException) {
			if (e.code != 405) throw e // 405: it exists already (created by another device meanwhile)
		}
	}

	/** Null when [url] doesn't exist (404). */
	private suspend fun propfind(credentials: WebDavCredentials, url: HttpUrl, depth: Int, allowMissing: Boolean): List<WebDavEntry>? {
		val request = Request.Builder()
			.url(url)
			.method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
			.header("Depth", depth.toString())
			.header("Authorization", auth(credentials))
			.build()
		return try {
			execute(credentials, request) { response ->
				runCatching { WebDavMultiStatus.parse(response.body.bytes()) }
					.getOrElse { throw SyncApiException(0, "The server's answer isn't WebDAV. Check the address.") }
			}
		} catch (e: SyncApiException) {
			if (allowMissing && e.code == 404) null else throw e
		}
	}

	private fun fileRequest(credentials: WebDavCredentials, name: String): Request.Builder {
		val url = credentials.folderUrl.newBuilder().addPathSegment(name).build()
		return Request.Builder().url(url).header("Authorization", auth(credentials))
	}

	private fun auth(credentials: WebDavCredentials) = Credentials.basic(credentials.user, credentials.password, Charsets.UTF_8)

	private suspend fun <T> execute(credentials: WebDavCredentials, request: Request, block: (Response) -> T): T {
		var attempt = 0
		while (true) {
			val failure: IOException = try {
				return withContext(Dispatchers.IO) {
					httpClient.newCall(request).execute().use { response ->
						if (!response.isSuccessful) throw response.toError()
						block(response)
					}
				}
			} catch (e: SyncApiException) {
				if (e.code < 500) throw e
				e
			} catch (e: IOException) {
				e
			}
			if (++attempt >= MAX_ATTEMPTS) throw failure
			delay(RETRY_DELAY_MS shl (attempt - 1))
		}
	}

	private fun Response.toError(): SyncApiException {
		val text = when (code) {
			401 -> "Wrong username or password."
			403 -> "The server refused access to that folder."
			404 -> "Not found on the server."
			405 -> "The server doesn't allow this (405)."
			507 -> "The server is out of storage space."
			else -> "WebDAV error $code: $message"
		}
		return SyncApiException(code, text)
	}

	private companion object {

		const val MAX_ATTEMPTS = 3
		const val RETRY_DELAY_MS = 1500L
		val XML = "application/xml; charset=utf-8".toMediaType()
		val OCTET = "application/octet-stream".toMediaType()
		const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"""
	}
}
