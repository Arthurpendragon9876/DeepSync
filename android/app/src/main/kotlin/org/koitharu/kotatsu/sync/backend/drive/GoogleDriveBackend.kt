package org.koitharu.kotatsu.sync.backend.drive

import org.koitharu.kotatsu.sync.backend.RemoteFile
import org.koitharu.kotatsu.sync.backend.ReplicaFormat
import org.koitharu.kotatsu.sync.backend.SyncBackend
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.SyncSession
import org.koitharu.kotatsu.sync.data.GoogleDriveApi
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import javax.inject.Inject
import javax.inject.Singleton

/** The hidden Drive `appDataFolder`; sign-in itself stays in [GoogleDriveAuth]. */
@Singleton
class GoogleDriveBackend @Inject constructor(
	private val auth: GoogleDriveAuth,
	private val api: GoogleDriveApi,
) : SyncBackend {

	override val provider = SyncProvider.DRIVE

	override val replicaFormat = ReplicaFormat.GZIP_JSON

	/** Google Drive sync is switched off in this build, so it isn't offered and is never used. See [ENABLED]. */
	override val isConfigured = ENABLED

	override suspend fun <T> session(block: suspend (SyncSession) -> T): T =
		auth.withToken { token -> block(DriveSession(token)) }

	override suspend fun signOut() = auth.signOut()

	private inner class DriveSession(private val token: String) : SyncSession {

		override suspend fun list() = api.listAppData(token).map { it.toRemote() }

		override suspend fun download(file: RemoteFile) = api.download(token, file.id)

		override suspend fun create(name: String, bytes: ByteArray) =
			api.create(token, name, GoogleDriveApi.body(bytes)).toRemote()

		override suspend fun update(file: RemoteFile, bytes: ByteArray) =
			api.update(token, file.id, GoogleDriveApi.body(bytes)).toRemote()

		override suspend fun delete(file: RemoteFile) = api.delete(token, file.id)
	}

	private fun GoogleDriveApi.DriveFile.toRemote() = RemoteFile(id, name, modifiedAt, md5)

	companion object {

		/**
		 * Google Drive sign-in was unstable, and the project prefers not to keep user data with Google.
		 * While this is false the provider is hidden from onboarding and Settings and no request is made
		 * to Google. The code stays so it can be switched back on.
		 */
		const val ENABLED = false
	}
}
