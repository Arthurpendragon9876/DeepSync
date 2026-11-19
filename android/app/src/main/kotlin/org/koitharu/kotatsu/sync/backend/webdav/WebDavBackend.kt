package org.koitharu.kotatsu.sync.backend.webdav

import org.koitharu.kotatsu.sync.backend.RemoteFile
import org.koitharu.kotatsu.sync.backend.ReplicaFormat
import org.koitharu.kotatsu.sync.backend.SecretStore
import org.koitharu.kotatsu.sync.backend.SyncBackend
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.SyncSession
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync on any WebDAV server (Nextcloud, ownCloud, a NAS, Synology...) in a `DeepSync` folder the app
 * creates. No app registration or OAuth: just an address, a user name and a password (for Nextcloud,
 * an app password). The password is kept encrypted in [SecretStore].
 */
@Singleton
class WebDavBackend @Inject constructor(
	private val api: WebDavApi,
	private val secrets: SecretStore,
	private val settings: SyncSettings,
) : SyncBackend {

	override val provider = SyncProvider.WEBDAV

	override val replicaFormat = ReplicaFormat.GZIP_JSON

	override val isConfigured = true

	override suspend fun <T> session(block: suspend (SyncSession) -> T): T {
		val credentials = credentials() ?: throw SyncSignInRequiredException("WebDAV login is required")
		return try {
			block(WebDavSession(credentials))
		} catch (e: SyncApiException) {
			if (e.code == 401) throw SyncSignInRequiredException("The WebDAV server rejected the login")
			throw e
		}
	}

	override suspend fun signOut() {
		secrets.set(PASSWORD_SECRET, null)
	}

	/** Stores a verified login. */
	fun store(credentials: WebDavCredentials) {
		settings.setProviderValue(SyncProvider.WEBDAV, KEY_URL, credentials.baseUrl)
		settings.setProviderValue(SyncProvider.WEBDAV, KEY_USER, credentials.user)
		secrets.set(PASSWORD_SECRET, credentials.password)
	}

	/** What the sign-in form can prefill when the login has to be entered again. */
	fun savedAddress(): Pair<String?, String?> =
		settings.providerValue(SyncProvider.WEBDAV, KEY_URL) to settings.providerValue(SyncProvider.WEBDAV, KEY_USER)

	private fun credentials(): WebDavCredentials? {
		val url = settings.providerValue(SyncProvider.WEBDAV, KEY_URL) ?: return null
		val user = settings.providerValue(SyncProvider.WEBDAV, KEY_USER) ?: return null
		val password = secrets.get(PASSWORD_SECRET) ?: return null
		return WebDavCredentials(url, user, password)
	}

	private inner class WebDavSession(private val credentials: WebDavCredentials) : SyncSession {

		override suspend fun list(): List<RemoteFile> = api.list(credentials)
			.filter { it.name.startsWith(REPLICA_PREFIX) || it.name.startsWith(COVER_PREFIX) }
			.map { RemoteFile(id = it.name, name = it.name, modifiedAt = it.modifiedAtMs, checksum = it.checksum) }

		override suspend fun download(file: RemoteFile): ByteArray = api.download(credentials, file.name)

		override suspend fun create(name: String, bytes: ByteArray): RemoteFile = write(name, bytes)

		override suspend fun update(file: RemoteFile, bytes: ByteArray): RemoteFile = write(file.name, bytes)

		override suspend fun delete(file: RemoteFile) = api.delete(credentials, file.name)

		private suspend fun write(name: String, bytes: ByteArray): RemoteFile {
			val written = api.put(credentials, name, bytes)
			// No ETag in the answer is fine: a null checksum just makes the next sync read the file back once.
			return RemoteFile(id = name, name = name, modifiedAt = System.currentTimeMillis(), checksum = written.etag)
		}
	}

	companion object {

		const val PASSWORD_SECRET = "webdav_password"
		const val KEY_URL = "url"
		const val KEY_USER = "user"
		private const val REPLICA_PREFIX = "replica_"
		private const val COVER_PREFIX = "cover_"
	}
}
