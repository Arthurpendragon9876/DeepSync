package org.koitharu.kotatsu.sync.backend.onedrive

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
 * Sync in the app's own folder of a personal OneDrive (`Apps/<app name>`), which the app can't leave.
 * Access tokens last about an hour and are renewed with the stored refresh token (Microsoft rotates it
 * on every renewal, so the new one is always stored); the user signs in again only if they remove the
 * app at account.microsoft.com.
 */
@Singleton
class OneDriveBackend @Inject constructor(
	private val api: OneDriveApi,
	private val secrets: SecretStore,
	private val settings: SyncSettings,
) : SyncBackend {

	override val provider = SyncProvider.ONEDRIVE

	override val replicaFormat = ReplicaFormat.GZIP_JSON

	override val isConfigured: Boolean get() = BuildConfig.ONEDRIVE_CLIENT_ID.isNotBlank()

	private val refreshMutex = Mutex()

	override suspend fun <T> session(block: suspend (SyncSession) -> T): T {
		val token = accessToken(forceRefresh = false)
		return try {
			block(OneDriveSession(token))
		} catch (e: SyncApiException) {
			if (e.code != 401) throw e
			block(OneDriveSession(accessToken(forceRefresh = true)))
		}
	}

	override suspend fun signOut() {
		// Microsoft has no per-app revoke for public clients; the user can remove the app at
		// account.microsoft.com/privacy/app-access. Forgetting the tokens ends this device's access.
		forgetTokens()
	}

	/** Stores a fresh token pair (from the code exchange or a refresh). */
	fun store(tokens: OneDriveApi.Tokens) {
		secrets.set(ACCESS_SECRET, tokens.accessToken)
		tokens.refreshToken?.let { secrets.set(REFRESH_SECRET, it) }
		settings.setProviderValue(
			SyncProvider.ONEDRIVE,
			KEY_EXPIRES_AT,
			(System.currentTimeMillis() + tokens.expiresIn * 1000L).toString(),
		)
	}

	private suspend fun accessToken(forceRefresh: Boolean): String = refreshMutex.withLock {
		val current = secrets.get(ACCESS_SECRET)
		val expiresAt = settings.providerValue(SyncProvider.ONEDRIVE, KEY_EXPIRES_AT)?.toLongOrNull() ?: 0L
		if (!forceRefresh && current != null && expiresAt - System.currentTimeMillis() > EXPIRY_MARGIN_MS) {
			return@withLock current
		}
		val refreshToken = secrets.get(REFRESH_SECRET) ?: throw SyncSignInRequiredException("OneDrive authorization is required")
		val tokens = try {
			api.refresh(BuildConfig.ONEDRIVE_CLIENT_ID, refreshToken, SCOPE)
		} catch (e: SyncApiException) {
			// invalid_grant: the app was removed from the Microsoft account, or the token expired unused (90 days).
			if (e.code == 400 || e.code == 401) {
				forgetTokens()
				throw SyncSignInRequiredException("OneDrive authorization was revoked")
			}
			throw e
		}
		store(tokens)
		tokens.accessToken
	}

	private fun forgetTokens() {
		secrets.set(ACCESS_SECRET, null)
		secrets.set(REFRESH_SECRET, null)
		settings.setProviderValue(SyncProvider.ONEDRIVE, KEY_EXPIRES_AT, null)
	}

	private inner class OneDriveSession(private val token: String) : SyncSession {

		override suspend fun list(): List<RemoteFile> = api.list(token)
			.filter { it.name.startsWith(REPLICA_PREFIX) || it.name.startsWith(COVER_PREFIX) }
			.map { it.toRemote() }

		override suspend fun download(file: RemoteFile): ByteArray = api.download(token, file.id)

		override suspend fun create(name: String, bytes: ByteArray): RemoteFile = api.upload(token, name, bytes).toRemote()

		// Only a device's own replica is ever updated, so replacing by name can't clobber another device.
		override suspend fun update(file: RemoteFile, bytes: ByteArray): RemoteFile = api.upload(token, file.name, bytes).toRemote()

		override suspend fun delete(file: RemoteFile) = api.delete(token, file.id)
	}

	private fun OneDriveApi.Item.toRemote() = RemoteFile(id, name, modifiedAtMs, checksum)

	companion object {

		/** `Files.ReadWrite.AppFolder` limits access to this app's own folder; `offline_access` yields the refresh token. */
		const val SCOPE = "offline_access Files.ReadWrite.AppFolder User.Read"
		const val ACCESS_SECRET = "onedrive_access"
		const val REFRESH_SECRET = "onedrive_refresh"
		const val KEY_EXPIRES_AT = "expires_at"
		private const val EXPIRY_MARGIN_MS = 60_000L
		private const val REPLICA_PREFIX = "replica_"
		private const val COVER_PREFIX = "cover_"
	}
}
