package org.koitharu.kotatsu.sync.backend.webdav

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncRepository
import javax.inject.Inject
import javax.inject.Singleton

class WebDavSignInException(message: String) : Exception(message)

/** Validates a WebDAV address and login, creates the folder, and records the account. */
@Singleton
class WebDavSignIn @Inject constructor(
	private val api: WebDavApi,
	private val backend: WebDavBackend,
	private val repository: SyncRepository,
) {

	fun savedAddress() = backend.savedAddress()

	suspend fun connect(address: String, user: String, password: String) {
		if (user.isBlank() || password.isEmpty()) throw WebDavSignInException("Enter your user name and password.")
		val url = normalize(address)
		val credentials = WebDavCredentials(url, user.trim(), password)
		try {
			api.connect(credentials)
		} catch (e: SyncApiException) {
			throw WebDavSignInException(e.message ?: "Couldn't connect to the server.")
		}
		backend.store(credentials)
		val host = url.toHttpUrlOrNull()?.host ?: url
		repository.completeSignIn(SyncProvider.WEBDAV, "${credentials.user}@$host", host, null)
	}

	companion object {

		/** Adds a missing `https://`, drops the trailing slash, and refuses a password over plain http on the open internet. */
		fun normalize(address: String): String {
			var text = address.trim()
			if (text.isEmpty()) throw WebDavSignInException("Enter the server address.")
			if (!text.contains("://")) text = "https://$text"
			val url = text.toHttpUrlOrNull() ?: throw WebDavSignInException("That isn't a valid address.")
			if (!url.isHttps && !isLocalHost(url.host)) {
				throw WebDavSignInException("Use an https:// address: the password would otherwise travel unencrypted.")
			}
			return text.trimEnd('/')
		}

		/** Plain http is acceptable only inside the user's own network. */
		fun isLocalHost(host: String): Boolean {
			if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
			val parts = host.split('.').mapNotNull { it.toIntOrNull() }
			if (parts.size != 4 || host.split('.').size != 4) return false
			val (a, b) = parts
			return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
		}
	}
}
