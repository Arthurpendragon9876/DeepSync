package org.koitharu.kotatsu.sync.domain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sync.backend.CanonicalJson
import org.koitharu.kotatsu.sync.backend.RemoteFile
import org.koitharu.kotatsu.sync.backend.ReplicaFormat
import org.koitharu.kotatsu.sync.backend.SyncBackend
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.SyncSession
import org.koitharu.kotatsu.sync.data.GoogleDriveApi
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.db.SyncRemoteEntity
import org.koitharu.kotatsu.sync.data.db.SyncTriggers
import org.koitharu.kotatsu.sync.data.model.LegacySnapshot
import org.koitharu.kotatsu.sync.data.model.RPref
import org.koitharu.kotatsu.sync.data.model.RTombstone
import org.koitharu.kotatsu.sync.data.model.Replica
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.data.model.SyncRecord
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
	data object Success : SyncResult
	data object SignInRequired : SyncResult

	/** [retryable] is false for errors that won't fix themselves (e.g. a newer remote format). */
	data class Error(val message: String?, val retryable: Boolean = true) : SyncResult
}

/**
 * Two-way sync on the one active [SyncBackend] (Google Drive, GitHub, Dropbox or WebDAV). Every device publishes its own replica file and merges everyone
 * else's; per record the newest version wins (see [ReplicaMerger]). Only this device's file is ever
 * written, so devices can't overwrite each other, and only replicas that changed are downloaded.
 */
@Singleton
class SyncRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val syncSettings: SyncSettings,
	private val auth: GoogleDriveAuth,
	private val api: GoogleDriveApi,
	private val backends: @JvmSuppressWildcards Map<SyncProvider, SyncBackend>,
	private val builder: ReplicaBuilder,
	private val applier: ReplicaApplier,
	private val syncPrefs: SyncPrefs,
) {

	private val json = Json {
		encodeDefaults = true // the schema must always be written, it is what readers probe first
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
	}

	/** Whether a sync is currently running (so the UI can show progress). */
	val isSyncing = MutableStateFlow(false)
	private val syncMutex = Mutex()

	/** Only one provider is ever active: two would each keep their own cursors and fight. */
	private val backend: SyncBackend?
		get() = syncSettings.provider?.let { backends[it] }?.takeIf { it.isConfigured }

	/** Providers this build can offer, in the order the sign-in screen lists them. */
	val availableProviders: List<SyncProvider>
		get() = SyncProvider.entries.filter { backends[it]?.isConfigured == true }

	/**
	 * Records a finished sign-in. Signing in to a different provider or account means other files:
	 * the merge cursors and join state of the old one must not leak into it.
	 */
	suspend fun completeSignIn(provider: SyncProvider, accountId: String, name: String?, photoUrl: String?) {
		if (syncSettings.provider != provider || syncSettings.accountEmail != accountId) {
			syncSettings.clearRemoteState()
			database.getSyncDao().clearRemote()
		}
		syncSettings.provider = provider
		syncSettings.accountEmail = accountId
		syncSettings.accountName = name
		syncSettings.accountPhotoUrl = photoUrl
		syncSettings.needsReauth = false
		syncSettings.lastSyncError = null
		Log.i(TAG, "signed in to ${provider.key} as $accountId")
	}

	/**
	 * Starts connecting Drive: returns the account chooser / consent screen to launch (then pass its
	 * result to [finishSignIn]), or null when access was already granted and the account is recorded.
	 */
	suspend fun beginSignIn(): PendingIntent? {
		val result = auth.authorize()
		if (result.hasResolution()) return result.pendingIntent
		onAuthorized(result)
		return null
	}

	/** Completes [beginSignIn] with the consent screen's result; throws if access wasn't granted. */
	suspend fun finishSignIn(data: Intent?) = onAuthorized(auth.resultFromIntent(data))

	private suspend fun onAuthorized(result: AuthorizationResult) {
		val token = result.accessToken ?: throw SyncSignInRequiredException()
		val user = api.userInfo(token)
		completeSignIn(SyncProvider.DRIVE, user.email?.ifBlank { null } ?: "Google Drive", user.name, user.picture)
	}

	/**
	 * @param force wait for a running sync and then sync again (user-initiated); otherwise a sync
	 * that is already running makes this a no-op.
	 */
	suspend fun sync(force: Boolean = false): SyncResult {
		val backend = backend
		if (!syncSettings.isSignedIn || backend == null) return SyncResult.SignInRequired
		// Credentials were refused: don't hammer the server from every app start until the user signs in again.
		if (syncSettings.needsReauth && !force) return SyncResult.SignInRequired
		if (force) syncMutex.lock() else if (!syncMutex.tryLock()) return SyncResult.Success
		isSyncing.value = true
		return try {
			backend.session { session -> performSync(backend, session) }
			syncSettings.lastSyncTimestamp = System.currentTimeMillis()
			syncSettings.lastSyncError = null
			syncSettings.needsReauth = false
			SyncResult.Success
		} catch (e: SyncSignInRequiredException) {
			Log.w(TAG, "sign-in required", e)
			// Persist it like any failure, or a revoked grant kills background sync silently.
			syncSettings.lastSyncError = context.getString(R.string.sync_sign_in_required)
			syncSettings.needsReauth = true
			SyncResult.SignInRequired
		} catch (e: SyncSchemaException) {
			Log.e(TAG, "remote schema too new", e)
			syncSettings.lastSyncError = e.message
			SyncResult.Error(e.message, retryable = false)
		} catch (e: Exception) {
			if (e is kotlinx.coroutines.CancellationException) throw e
			Log.e(TAG, "sync failed", e)
			syncSettings.lastSyncError = e.message ?: e.javaClass.simpleName
			SyncResult.Error(e.message ?: e.javaClass.simpleName)
		} finally {
			isSyncing.value = false
			syncMutex.unlock()
		}
	}

	private suspend fun performSync(backend: SyncBackend, session: SyncSession) {
		val contentKeys = syncSettings.enabledContent
		val content = SyncContent.fromKeys(contentKeys)
		val syncDao = database.getSyncDao()
		val ownName = REPLICA_PREFIX + syncSettings.deviceId + backend.replicaSuffix
		// Joining an account's cloud (first sync of this install/account) adopts it on unknown-age ties.
		val isJoining = syncSettings.replicaSince == 0L
		if (syncSettings.lastSyncedContent != contentKeys) {
			// A type just turned on must still receive what earlier merges skipped: re-read every replica.
			syncDao.clearRemote()
			syncSettings.lastBuildFingerprint = null
		}

		val files = session.list()
		// A create retried after a timeout that actually succeeded leaves duplicates; keep the newest.
		val ownFiles = files.filter { it.name == ownName }
		val own = ownFiles.maxByOrNull { it.modifiedAt }
		val merged = syncDao.findRemote().associate { it.fileId to it.md5 }
		// Our own last upload too: it's what a wiped or corrupted database is restored from.
		val others = files.filter { (it.name.startsWith(REPLICA_PREFIX) && it.name != ownName) || it.name == LEGACY_NAME } +
			listOfNotNull(own)
		val changed = others.filter { it.checksum == null || merged[it.id] != it.checksum }

		// Download failures throw: a transient error must never look like "nothing remote".
		val remotes = ArrayList<Pair<RemoteFile, Replica>>(changed.size)
		for (file in changed) {
			val replica = decode(file, session.download(file)) ?: continue
			remotes += file to replica
		}
		noteLegacyWriter(files.firstOrNull { it.name == LEGACY_NAME })

		var categoryUids = builder.categoryUids()
		var prefs = if (SyncContent.SETTINGS in content) syncPrefs.detect(categoryUids) else emptyList()
		var fingerprint = syncDao.fingerprint()
		if (remotes.isEmpty() && own != null && fingerprint == syncSettings.lastBuildFingerprint) {
			Log.i(TAG, "nothing changed")
			housekeeping(backend, session, files)
			return
		}

		var local = builder.build(syncSettings.deviceId, content, categoryUids, prefs)
		val isRestoring = remotes.any { it.first.name == ownName }
		var complete = true
		if (remotes.isNotEmpty()) {
			val sources = remotes.map { (file, replica) ->
				if (file.name == ownName) missingFrom(local.replica, replica) else replica
			}
			val changes = merge(local.replica, sources, content, isJoining)
			complete = if (changes.isEmpty) {
				true
			} else {
				val manga = HashMap<Long, MangaBackup>()
				for ((_, replica) in remotes) {
					for (m in replica.manga) {
						val known = manga[m.id]
						if (known == null || m.detailsUpdatedAt > known.detailsUpdatedAt) manga[m.id] = m
					}
				}
				val coverFiles = files.filter { it.name.startsWith(COVER_PREFIX) }.associateBy { it.name }
				applier.apply(changes, manga, categoryUids) { hash ->
					coverFiles[COVER_PREFIX + hash]?.let { session.download(it) }
				}
			}
			if (complete) {
				for ((file, _) in remotes) {
					file.checksum?.let { syncDao.upsertRemote(SyncRemoteEntity(file.id, it)) }
				}
			}
			Log.i(TAG, "merged ${remotes.size} replica(s), changes=${!changes.isEmpty}, complete=$complete")
			if (!changes.isEmpty) {
				categoryUids = builder.categoryUids()
				prefs = if (SyncContent.SETTINGS in content) syncPrefs.detect(categoryUids) else emptyList()
				fingerprint = syncDao.fingerprint()
				local = builder.build(syncSettings.deviceId, content, categoryUids, prefs)
			}
		}

		if (isRestoring && !complete) {
			// Uploading now would replace our last upload with a database lacking what didn't apply yet.
			Log.w(TAG, "restore from own replica incomplete, upload postponed")
			return
		}

		val existingCovers = files.mapNotNullTo(HashSet()) { it.name.takeIf { n -> n.startsWith(COVER_PREFIX) } }
		for (cover in local.covers) {
			val name = COVER_PREFIX + cover.hash
			if (name in existingCovers) continue
			val raw = builder.coverBytes(cover) ?: continue
			session.create(name, raw)
		}

		val payload = encode(backend, local.replica)
		val hash = sha1(payload)
		var ownId = own?.id
		if (own == null || hash != syncSettings.lastUploadHash) {
			val body = if (backend.replicaFormat.isGzip) gzip(payload) else payload
			val uploaded = if (own == null) {
				session.create(ownName, body)
			} else {
				session.update(own, body)
			}
			ownId = uploaded.id
			syncSettings.lastUploadHash = hash
			// Our own upload is already in the database: don't download it back next time.
			uploaded.checksum?.let { syncDao.upsertRemote(SyncRemoteEntity(uploaded.id, it)) }
			Log.i(TAG, "uploaded replica (${payload.size} bytes raw) to ${uploaded.id}")
		}
		for (duplicate in ownFiles) {
			if (duplicate.id != ownId) runCatchingCancellable { session.delete(duplicate) }
		}
		syncSettings.lastBuildFingerprint = fingerprint
		syncSettings.lastSyncedContent = contentKeys
		if (syncSettings.replicaSince == 0L) {
			syncSettings.replicaSince = System.currentTimeMillis()
		}
		housekeeping(backend, session, files)
	}

	private fun merge(local: Replica, remotes: List<Replica>, content: Set<SyncContent>, isJoining: Boolean): SyncChanges {
		fun tombstones(replica: Replica, table: String) = replica.tombstones.filter { it.table == table }
		fun <T : SyncRecord> section(
			type: SyncContent,
			table: String?,
			pick: (Replica) -> List<T>,
		): ReplicaMerger.Changes<T> = if (type !in content) {
			ReplicaMerger.Changes(emptyList(), emptyList())
		} else {
			val none = emptyList<RTombstone>()
			ReplicaMerger.merge(
				local = pick(local),
				localTombstones = table?.let { tombstones(local, it) } ?: none,
				remotes = remotes.map { pick(it) to (table?.let { t -> tombstones(it, t) } ?: none) },
				isJoining = isJoining,
			)
		}
		return SyncChanges(
			categories = section(SyncContent.FAVOURITES, SyncTriggers.CATEGORIES) { it.categories },
			favourites = section(SyncContent.FAVOURITES, SyncTriggers.FAVOURITES) { it.favourites },
			history = section(SyncContent.HISTORY, SyncTriggers.HISTORY) { it.history },
			stats = section(SyncContent.STATS, SyncTriggers.STATS) { it.stats },
			bookmarks = section(SyncContent.BOOKMARKS, SyncTriggers.BOOKMARKS) { it.bookmarks },
			scrobblings = section(SyncContent.TRACKING, SyncTriggers.SCROBBLINGS) { it.scrobblings },
			feed = section(SyncContent.FEED, SyncTriggers.FEED) { it.feed },
			mangaPrefs = section(SyncContent.CUSTOM_COVERS, SyncTriggers.MANGA_PREFS) { it.mangaPrefs },
			prefs = section(SyncContent.SETTINGS, null) { it.prefs }.let { if (isJoining) adoptRemoteFiles(it, local, remotes) else it },
		)
	}

	/**
	 * The part of this device's last upload that the database has no dated trace of (no versioned record,
	 * no tombstone). Normally nothing; after the database was wiped or corrupted, everything — a rebuilt
	 * database only has unknown-age defaults. Whatever was edited or deleted here since has a newer record
	 * of its own and is left alone.
	 */
	private fun missingFrom(local: Replica, own: Replica): Replica {
		val known = HashSet<String>()
		fun <T : SyncRecord> List<T>.remember(table: String) = forEach { if (it.v > 0L) known += "$table/${it.key}" }
		local.categories.remember(SyncTriggers.CATEGORIES)
		local.favourites.remember(SyncTriggers.FAVOURITES)
		local.history.remember(SyncTriggers.HISTORY)
		local.bookmarks.remember(SyncTriggers.BOOKMARKS)
		local.scrobblings.remember(SyncTriggers.SCROBBLINGS)
		local.stats.remember(SyncTriggers.STATS)
		local.feed.remember(SyncTriggers.FEED)
		local.mangaPrefs.remember(SyncTriggers.MANGA_PREFS)
		local.prefs.remember(PREFS)
		local.tombstones.forEach { known += "${it.table}/${it.key}" }
		fun <T : SyncRecord> List<T>.missing(table: String) = filterNot { "$table/${it.key}" in known }
		return Replica(
			deviceId = own.deviceId,
			content = own.content,
			manga = own.manga,
			categories = own.categories.missing(SyncTriggers.CATEGORIES),
			favourites = own.favourites.missing(SyncTriggers.FAVOURITES),
			history = own.history.missing(SyncTriggers.HISTORY),
			bookmarks = own.bookmarks.missing(SyncTriggers.BOOKMARKS),
			scrobblings = own.scrobblings.missing(SyncTriggers.SCROBBLINGS),
			stats = own.stats.missing(SyncTriggers.STATS),
			feed = own.feed.missing(SyncTriggers.FEED),
			mangaPrefs = own.mangaPrefs.missing(SyncTriggers.MANGA_PREFS),
			prefs = own.prefs.missing(PREFS),
			tombstones = own.tombstones.filterNot { "${it.table}/${it.key}" in known },
		)
	}

	/**
	 * A joining device takes the cloud's copy of each settings file whole: its own never-synced keys
	 * that the cloud lacks are dropped. Absence carries meaning (a disabled tap-grid area has no key),
	 * so publishing them would bring such settings back on every other device. Extension stores are
	 * records, not settings: a store only this device has is kept and shared.
	 */
	private fun adoptRemoteFiles(changes: ReplicaMerger.Changes<RPref>, local: Replica, remotes: List<Replica>): ReplicaMerger.Changes<RPref> {
		val remoteKeys = remotes.flatMapTo(HashSet()) { replica -> replica.prefs.map { it.key } }
		val remoteFiles = remotes.flatMapTo(HashSet()) { replica -> replica.prefs.map { it.file } }
		remoteFiles -= SyncedStores.FILE
		val removals = local.prefs.filter {
			it.v == 0L && it.value != null && it.file in remoteFiles && it.key !in remoteKeys
		}.map { it.copy(value = null) }
		return if (removals.isEmpty()) changes else ReplicaMerger.Changes(changes.upserts + removals, changes.deletes)
	}

	/**
	 * Returns null for a same-schema file that can't be read (skipped and retried next sync, never
	 * deleted). Throws [SyncSchemaException] for a newer format so this build never merges data it
	 * doesn't understand.
	 */
	private fun decode(file: RemoteFile, bytes: ByteArray): Replica? {
		if (file.name == LEGACY_NAME) {
			return runCatching {
				json.decodeFromString(LegacySnapshot.serializer(), bytes.decodeToString()).toReplica()
			}.onFailure { Log.w(TAG, "legacy sync file unreadable", it) }.getOrNull()
		}
		// Drive may hand the file back already decompressed; only gunzip what is still gzip.
		val text = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
			GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.decodeToString()
		} else {
			bytes.decodeToString()
		}
		val schema = runCatching { json.decodeFromString(SchemaProbe.serializer(), text).schema }.getOrNull()
		if (schema != null && schema > Replica.SCHEMA) {
			throw SyncSchemaException(schema)
		}
		return runCatching { json.decodeFromString(Replica.serializer(), text) }
			.onFailure { Log.w(TAG, "replica ${file.name} unreadable", it) }
			.getOrNull()
	}

	/** Remembers when a device on the old version last wrote the old single-file format. */
	private fun noteLegacyWriter(legacy: RemoteFile?) {
		val since = syncSettings.replicaSince
		val modifiedAt = legacy?.modifiedAt ?: 0L
		if (since != 0L && modifiedAt > since) {
			syncSettings.legacyWriterSeenAt = modifiedAt
		}
	}

	/** Daily: GC old tombstones locally, drop files of long-gone devices and stale merge cursors. */
	private suspend fun housekeeping(backend: SyncBackend, session: SyncSession, files: List<RemoteFile>) {
		val now = System.currentTimeMillis()
		if (now - syncSettings.lastHousekeeping < DAY_MS) return
		val cutoff = now - TOMBSTONE_TTL_MS
		runCatchingCancellable {
			database.getFavouritesDao().gc(cutoff)
			database.getFavouriteCategoriesDao().gc(cutoff)
			database.getHistoryDao().gc(cutoff)
			database.getSyncDao().gcTombstones(cutoff)
			database.getSyncDao().gcRemovedPrefs(cutoff)
			val ids = files.mapTo(HashSet()) { it.id }
			for (cursor in database.getSyncDao().findRemote()) {
				if (cursor.fileId !in ids) database.getSyncDao().deleteRemote(cursor.fileId)
			}
			val ownName = REPLICA_PREFIX + syncSettings.deviceId + backend.replicaSuffix
			for (file in files) {
				if (file.name.startsWith(REPLICA_PREFIX) && file.name != ownName && file.modifiedAt in 1 until cutoff) {
					Log.i(TAG, "removing replica of a device unseen since ${file.modifiedAt}: ${file.name}")
					session.delete(file)
				}
			}
			syncSettings.lastHousekeeping = now
		}.onFailure { Log.w(TAG, "housekeeping failed", it) }
	}

	/** Deletes the synced data from the cloud and forgets local sync bookkeeping. */
	suspend fun deleteRemoteData(): SyncResult = try {
		val backend = backend ?: return SyncResult.SignInRequired
		backend.session { session ->
			for (file in session.list()) {
				session.delete(file)
			}
		}
		database.getSyncDao().clearRemote()
		syncSettings.clearRemoteState()
		SyncResult.Success
	} catch (e: SyncSignInRequiredException) {
		SyncResult.SignInRequired
	} catch (e: Exception) {
		if (e is kotlinx.coroutines.CancellationException) throw e
		SyncResult.Error(e.message)
	}

	suspend fun signOut() {
		// Drive: signOut + revokeAccess so the next sign-in shows the account chooser / consent again.
		// Switching provider is exactly this: disconnect, forget cursors (clearRemote) and the join state
		// (replicaSince), then sign in elsewhere, whose first sync uploads everything.
		backend?.signOut()
		syncSettings.clearAccount()
		// Another account means other files: forget which ones were already merged.
		database.getSyncDao().clearRemote()
	}

	/**
	 * Compact JSON for gzip providers (gzipped by the caller); canonical, line-per-record JSON for
	 * git-backed ones so successive commits delta-compress. Equal data always encodes to equal bytes.
	 */
	private fun encode(backend: SyncBackend, replica: Replica): ByteArray =
		if (backend.replicaFormat == ReplicaFormat.CANONICAL_JSON) {
			CanonicalJson.encode(json.encodeToJsonElement(Replica.serializer(), replica) as JsonObject).encodeToByteArray()
		} else {
			json.encodeToString(Replica.serializer(), replica).encodeToByteArray()
		}

	private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream(bytes.size / 4).also { out ->
		GZIPOutputStream(out).use { it.write(bytes) }
	}.toByteArray()

	private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
		.digest(bytes)
		.joinToString("") { "%02x".format(it) }

	@Serializable
	private class SchemaProbe(@SerialName("schema") val schema: Int = 0)

	private companion object {

		const val TAG = "Sync"
		const val REPLICA_PREFIX = "replica_"
		const val COVER_PREFIX = "cover_"
		const val LEGACY_NAME = "dropsauce_sync.json"
		const val PREFS = "prefs"
		const val DAY_MS = 24L * 60 * 60 * 1000

		/**
		 * How long deletions are remembered. A device offline for longer may bring back something
		 * deleted meanwhile — the price of keeping the files from growing forever.
		 */
		const val TOMBSTONE_TTL_MS = 180L * DAY_MS
	}
}
