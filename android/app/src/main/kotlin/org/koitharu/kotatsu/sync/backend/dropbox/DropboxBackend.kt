package org.koitharu.kotatsu.sync.backend.dropbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.BuildConfig
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
 * Sync in the app's own Dropbox folder (`Apps/<app name>`), which the app can't leave. Access tokens
 * last a few hours and are renewed with the stored refresh token; the user signs in again only if
 * they revoke the app in their Dropbox settings.
 */
@Singleton
class DropboxBackend @Inject constructor(
	private val api: DropboxApi,
	private val secrets: SecretStore,
	private val settings: SyncSettings,
) : SyncBackend {

	override val provider = SyncProvider.DROPBOX

	override val replicaFormat = ReplicaFormat.GZIP_JSON

	override val isConfigured: Boolean get() = BuildConfig.DROPBOX_APP_KEY.isNotBlank()

	private val refreshMutex = Mutex()

	override suspend fun <T> session(block: suspend (SyncSession) -> T): T {
		val token = accessToken(forceRefresh = false)
		return try {
			block(DropboxSession(token))
		} catch (e: SyncApiException) {
			if (e.code != 401) throw e
			// Revoked or expired earlier than we expected: renew once and run again.
			block(DropboxSession(accessToken(forceRefresh = true)))
		}
	}

	override suspend fun signOut() {
		secrets.get(ACCESS_SECRET)?.let { api.revoke(it) }
		forgetTokens()
	}

	/** Stores a fresh token pair (from the code exchange or a refresh). */
	fun store(tokens: DropboxApi.Tokens) {
		secrets.set(ACCESS_SECRET, tokens.accessToken)
		tokens.refreshToken?.let { secrets.set(REFRESH_SECRET, it) }
		settings.setProviderValue(
			SyncProvider.DROPBOX,
			KEY_EXPIRES_AT,
			(System.currentTimeMillis() + tokens.expiresIn * 1000L).toString(),
		)
	}

	private suspend fun accessToken(forceRefresh: Boolean): String = refreshMutex.withLock {
		val current = secrets.get(ACCESS_SECRET)
		val expiresAt = settings.providerValue(SyncProvider.DROPBOX, KEY_EXPIRES_AT)?.toLongOrNull() ?: 0L
		if (!forceRefresh && current != null && expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS) {
			return@withLock current
		}
		val refreshToken = secrets.get(REFRESH_SECRET) ?: throw SyncSignInRequiredException("Dropbox authorization is required")
		val tokens = try {
			api.refresh(BuildConfig.DROPBOX_APP_KEY, refreshToken)
		} catch (e: SyncApiException) {
			// invalid_grant: the user removed the app from their Dropbox.
			if (e.code == 400 || e.code == 401) {
				forgetTokens()
				throw SyncSignInRequiredException("Dropbox authorization was revoked")
			}
			throw e
		}
		store(tokens)
		tokens.accessToken
	}

	private fun forgetTokens() {
		secrets.set(ACCESS_SECRET, null)
		secrets.set(REFRESH_SECRET, null)
		settings.setProviderValue(SyncProvider.DROPBOX, KEY_EXPIRES_AT, null)
	}

	private inner class DropboxSession(private val token: String) : SyncSession {

		override suspend fun list(): List<RemoteFile> = api.list(token)
			.filter { it.name.startsWith(REPLICA_PREFIX) || it.name.startsWith(COVER_PREFIX) }
			.map { it.toRemote() }

		override suspend fun download(file: RemoteFile): ByteArray = api.download(token, file.id)

		override suspend fun create(name: String, bytes: ByteArray): RemoteFile = api.upload(token, name, bytes).toRemote()

		// Only a device's own replica is ever updated, so overwriting by name can't clobber another device.
		override suspend fun update(file: RemoteFile, bytes: ByteArray): RemoteFile = api.upload(token, file.name, bytes).toRemote()

		override suspend fun delete(file: RemoteFile) = api.delete(token, file.id)
	}

	private fun DropboxApi.Entry.toRemote() = RemoteFile(id, name, modifiedAtMs, contentHash)

	companion object {

		const val ACCESS_SECRET = "dropbox_access"
		const val REFRESH_SECRET = "dropbox_refresh"
		const val KEY_EXPIRES_AT = "expires_at"
		private const val EXPIRY_MARGIN_MS = 60_000L
		private const val REPLICA_PREFIX = "replica_"
		private const val COVER_PREFIX = "cover_"
	}
}
