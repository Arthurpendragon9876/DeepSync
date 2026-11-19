package org.koitharu.kotatsu.sync.backend

/** The storage providers sync can run on. Exactly one is active at a time. */
enum class SyncProvider(val key: String) {
	DRIVE("drive"),
	GITHUB("github"),
	DROPBOX("dropbox"),
	ONEDRIVE("onedrive"),
	WEBDAV("webdav"),
	;

	companion object {

		fun fromKey(key: String?): SyncProvider? = entries.firstOrNull { it.key == key }
	}
}

/** How a provider wants the replica file encoded. */
enum class ReplicaFormat(val suffix: String) {

	/** Compact JSON, gzipped: the smallest upload for providers that only store opaque files. */
	GZIP_JSON(".json.gz"),

	/** Sorted, one record per line, uncompressed: git can delta-compress successive versions. */
	CANONICAL_JSON(".json"),
	;

	val isGzip: Boolean get() = this == GZIP_JSON
}

/**
 * A file in the provider's storage.
 *
 * @param id provider-specific handle (Drive file id, GitHub path, Dropbox id, WebDAV file name)
 * @param checksum changes whenever the content changes (md5, git blob sha, content hash, ETag); the merge cursor
 * @param modifiedAt epoch millis, or 0 when the provider can't tell cheaply (GitHub)
 */
class RemoteFile(
	val id: String,
	val name: String,
	val modifiedAt: Long,
	val checksum: String?,
)

/**
 * A provider-agnostic flat file store for one sync account. Names are the logical names the sync
 * engine uses (`replica_<device>...`, `cover_<hash>`); where they live inside the provider is its
 * own business. Adding a provider means implementing this and registering it in
 * [SyncBackendsModule]; the merge engine never looks at which provider it talks to.
 */
interface SyncBackend {

	val provider: SyncProvider

	val replicaFormat: ReplicaFormat

	/** False when this build lacks what the provider needs (e.g. an OAuth client id); it is then not offered. */
	val isConfigured: Boolean

	/** Suffix of this device's replica file. */
	val replicaSuffix: String get() = replicaFormat.suffix

	/**
	 * Runs [block] with an authorized [SyncSession]. Handles token refresh; throws
	 * [org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException] when the user must sign in again.
	 * [block] may run twice (after a refresh or a repair), so it must be safe to repeat.
	 */
	suspend fun <T> session(block: suspend (SyncSession) -> T): T

	/** Disconnects this device (forgets/revokes credentials). Remote data stays. */
	suspend fun signOut()
}

interface SyncSession {

	/** Every sync file (replicas and covers); never other files that may live in the same place. */
	suspend fun list(): List<RemoteFile>

	suspend fun download(file: RemoteFile): ByteArray

	suspend fun create(name: String, bytes: ByteArray): RemoteFile

	suspend fun update(file: RemoteFile, bytes: ByteArray): RemoteFile

	suspend fun delete(file: RemoteFile)
}
