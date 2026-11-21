package org.koitharu.kotatsu.sync.backend.github

import kotlinx.coroutines.delay
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.sync.backend.ReplicaFormat
import org.koitharu.kotatsu.sync.backend.SecretStore
import org.koitharu.kotatsu.sync.backend.RemoteFile
import org.koitharu.kotatsu.sync.backend.SyncBackend
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.SyncSession
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync on a private GitHub repository the user owns. Layout:
 * `replicas/replica_<device>.json` (plain, canonical JSON: git delta-compresses text, not gzip) and
 * `covers/cover_<hash>`. Anything else in the repo (README, ...) is never listed or touched.
 */
@Singleton
class GithubBackend @Inject constructor(
	private val api: GithubApi,
	private val secrets: SecretStore,
	private val settings: SyncSettings,
	private val provisioner: GithubRepoProvisioner,
) : SyncBackend {

	override val provider = SyncProvider.GITHUB

	override val replicaFormat = ReplicaFormat.CANONICAL_JSON

	override val isConfigured: Boolean get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

	// A 304 on the tree listing costs no rate limit, so background checks are nearly free. Kept in
	// memory only: the listing it stands for has to be in memory too.
	private var treeEtag: String? = null
	private var treeCache: List<RemoteFile>? = null
	private var lastWriteAt = 0L

	override suspend fun <T> session(block: suspend (SyncSession) -> T): T {
		// A token that is merely unreadable for a moment throws a plain error (retried later); only a token that is
		// really gone asks the user to sign in.
		val token = secrets.getStrict(TOKEN_SECRET) ?: throw SyncSignInRequiredException("GitHub authorization is required")
		return try {
			try {
				run(token, block)
			} catch (e: SyncApiException) {
				if (e.code != 404) throw e
				// The repository was deleted or renamed, or its branch is gone: find or recreate it
				// (a deleted one comes back empty and the next upload refills it), then retry once.
				invalidate()
				provisioner.ensure(token)
				run(token, block)
			}
		} catch (e: SyncApiException) {
			// OAuth App tokens don't expire on their own, but GitHub revokes the least recently used one once an app
			// has issued ten for the same user and scope, and the user can revoke it too. Only forget the token when
			// GitHub confirms it is dead: one stray 401 must not log the user out.
			if (e.code == 401 && isTokenRejected(token)) {
				secrets.set(TOKEN_SECRET, null)
				throw SyncSignInRequiredException("GitHub authorization was revoked")
			}
			throw e
		}
	}

	/** Asks GitHub who the token belongs to: only a 401 there means it is revoked (a network error says nothing). */
	private suspend fun isTokenRejected(token: String): Boolean = try {
		api.user(token)
		false
	} catch (e: SyncApiException) {
		e.code == 401
	} catch (e: IOException) {
		false
	}

	private suspend fun <T> run(token: String, block: suspend (SyncSession) -> T): T {
		val repo = settings.providerValue(SyncProvider.GITHUB, GithubRepoProvisioner.KEY_REPO)
			?: provisioner.ensure(token).repo.fullName
		val branch = settings.providerValue(SyncProvider.GITHUB, GithubRepoProvisioner.KEY_BRANCH) ?: "main"
		return block(GithubSession(token, repo, branch))
	}

	override suspend fun signOut() {
		// Revoking needs the app's client secret, which never ships in the APK: forget the token here;
		// the user can also remove the app under GitHub > Settings > Applications.
		secrets.set(TOKEN_SECRET, null)
		invalidate()
	}

	private inner class GithubSession(
		private val token: String,
		private val repo: String,
		private val branch: String,
	) : SyncSession {

		override suspend fun list(): List<RemoteFile> {
			return when (val result = api.tree(token, repo, branch, treeEtag.takeIf { treeCache != null })) {
				GithubApi.TreeResult.NotModified -> treeCache ?: emptyList()
				is GithubApi.TreeResult.Fresh -> result.entries
					.filter { it.path.startsWith(REPLICAS_DIR) || it.path.startsWith(COVERS_DIR) }
					.map { RemoteFile(id = it.path, name = it.path.substringAfterLast('/'), modifiedAt = 0L, checksum = it.sha) }
					.also {
						treeEtag = result.etag
						treeCache = it
					}
			}
		}

		override suspend fun download(file: RemoteFile): ByteArray =
			api.blob(token, repo, file.checksum ?: throw SyncApiException(0, "No sha for ${file.id}"))

		override suspend fun create(name: String, bytes: ByteArray): RemoteFile {
			val path = pathOf(name)
			return write(name, path, bytes, sha = null)
		}

		override suspend fun update(file: RemoteFile, bytes: ByteArray): RemoteFile =
			write(file.name, file.id, bytes, sha = file.checksum)

		override suspend fun delete(file: RemoteFile) {
			val sha = file.checksum ?: api.currentSha(token, repo, branch, file.id) ?: return
			throttleWrites()
			api.delete(token, repo, branch, file.id, sha, MESSAGE)
			invalidate()
		}

		private suspend fun write(name: String, path: String, bytes: ByteArray, sha: String?): RemoteFile {
			if (bytes.size > MAX_FILE_BYTES) throw SyncApiException(0, "$name is too large for GitHub sync")
			var knownSha = sha
			var attempt = 0
			while (true) {
				throttleWrites()
				try {
					val newSha = api.put(token, repo, branch, path, bytes, knownSha, MESSAGE)
					invalidate()
					return RemoteFile(path, name, 0L, newSha)
				} catch (e: SyncApiException) {
					if (++attempt >= MAX_WRITE_ATTEMPTS) throw e
					when (e.code) {
						// Another commit landed on the branch at the same moment (another device): retry.
						409 -> delay(CONFLICT_DELAY_MS * attempt)
						// Missing or stale sha: the file exists (or changed) behind our cached listing.
						422 -> {
							val current = api.currentSha(token, repo, branch, path)
							// Covers are content-addressed: if it's there already, it is the same file.
							if (current != null && name.startsWith(COVER_PREFIX) && knownSha == null) {
								return RemoteFile(path, name, 0L, current)
							}
							knownSha = current
						}

						else -> throw e
					}
				}
			}
		}
	}

	private fun invalidate() {
		treeEtag = null
		treeCache = null
	}

	/** GitHub asks integrations to leave at least a second between writes (secondary rate limits). */
	private suspend fun throttleWrites() {
		val wait = lastWriteAt + WRITE_SPACING_MS - System.currentTimeMillis()
		if (wait > 0) delay(wait)
		lastWriteAt = System.currentTimeMillis()
	}

	private fun pathOf(name: String) = (if (name.startsWith(COVER_PREFIX)) COVERS_DIR else REPLICAS_DIR) + name

	companion object {

		const val TOKEN_SECRET = "github_token"

		private const val REPLICAS_DIR = "replicas/"
		private const val COVERS_DIR = "covers/"
		private const val COVER_PREFIX = "cover_"
		private const val MESSAGE = "DeepSync sync"
		private const val MAX_FILE_BYTES = 50 * 1024 * 1024 // GitHub's hard limit is 100 MB; stay far below
		private const val MAX_WRITE_ATTEMPTS = 4
		private const val CONFLICT_DELAY_MS = 2000L
		private const val WRITE_SPACING_MS = 1000L
	}
}
