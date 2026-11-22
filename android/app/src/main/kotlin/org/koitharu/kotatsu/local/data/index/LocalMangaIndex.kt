package org.koitharu.kotatsu.local.data.index

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LocalMangaIndex @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val db: MangaDatabase,
	@ApplicationContext context: Context,
	private val localMangaRepositoryProvider: Provider<LocalMangaRepository>,
) : FlowCollector<LocalManga?> {

	private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
	private val mutex = Mutex()

	private var currentVersion: Int
		get() = prefs.getInt(KEY_VERSION, 0)
		set(value) = prefs.edit { putInt(KEY_VERSION, value) }

	override suspend fun emit(value: LocalManga?) {
		if (value != null) {
			put(value)
		}
	}

	suspend fun update() = mutex.withLock { rebuild() }

	private suspend fun rebuild() {
		// Taken before scanning: anything added while the scan runs makes the next check rebuild again
		val fingerprint = currentFingerprint()
		db.withTransaction {
			val dao = db.getLocalMangaIndexDao()
			dao.clear()
			localMangaRepositoryProvider.get()
				.getRawListAsFlow()
				.collect { upsert(it) }
		}
		currentVersion = VERSION
		storedFingerprint = fingerprint.orEmpty()
	}

	/**
	 * Rebuilds the index when its format changed or when the storage on disk is no longer the one it was built from.
	 * The index used to be built once, so titles that were already in a folder the app was pointed at later (such as
	 * another app's download folder) were found by the details screen but were missing from the index, and every
	 * filter that reads the index ("On device" in Favourites and History) came back empty.
	 */
	suspend fun updateIfRequired() {
		if (!isUpdateRequired() && !isStorageChanged(throttled = true)) {
			return
		}
		mutex.withLock {
			// Another caller may have rebuilt while this one waited for the lock
			if (isUpdateRequired() || isStorageChanged(throttled = false)) {
				rebuild()
			}
		}
	}

	suspend fun get(mangaId: Long, withDetails: Boolean): LocalManga? {
		updateIfRequired()
		var path = db.getLocalMangaIndexDao().findPath(mangaId)
		if (path == null && mutex.isLocked) { // wait for updating complete
			path = mutex.withLock { db.getLocalMangaIndexDao().findPath(mangaId) }
		}
		if (path == null) {
			return null
		}
		return runCatchingCancellable {
			LocalMangaParser(File(path)).getManga(withDetails)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	suspend operator fun contains(mangaId: Long): Boolean {
		return db.getLocalMangaIndexDao().findPath(mangaId) != null
	}

	suspend fun put(manga: LocalManga) = mutex.withLock {
		db.withTransaction {
			upsert(manga)
		}
	}

	suspend fun delete(mangaId: Long) {
		db.getLocalMangaIndexDao().delete(mangaId)
	}

	suspend fun getAvailableTags(skipNsfw: Boolean): List<String> {
		val dao = db.getLocalMangaIndexDao()
		return if (skipNsfw) {
			dao.findTags(isNsfw = false)
		} else {
			dao.findTags()
		}
	}

	private suspend fun upsert(manga: LocalManga) {
		mangaDataRepository.storeManga(manga.manga, replaceExisting = true)
		db.getLocalMangaIndexDao().upsert(manga.toEntity())
	}

	private fun LocalManga.toEntity() = LocalMangaIndexEntity(
		mangaId = manga.id,
		path = file.path,
	)

	private fun isUpdateRequired() = currentVersion < VERSION

	private var storedFingerprint: String
		get() = prefs.getString(KEY_FINGERPRINT, null).orEmpty()
		set(value) = prefs.edit { putString(KEY_FINGERPRINT, value) }

	private var lastFingerprintCheck = 0L

	private suspend fun currentFingerprint(): String? = runCatchingCancellable {
		localMangaRepositoryProvider.get().getStorageFingerprint()
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	private suspend fun isStorageChanged(throttled: Boolean): Boolean {
		val now = SystemClock.elapsedRealtime()
		// Lists ask for this once per title, so a burst of calls shares one check
		if (throttled && now - lastFingerprintCheck < FINGERPRINT_CHECK_INTERVAL_MS) {
			return false
		}
		lastFingerprintCheck = now
		val current = currentFingerprint() ?: return false
		return current != storedFingerprint
	}

	companion object {

		private const val PREF_NAME = "_local_index"
		private const val KEY_VERSION = "ver"
		private const val KEY_FINGERPRINT = "storage_fp"
		private const val FINGERPRINT_CHECK_INTERVAL_MS = 5_000L
		private const val VERSION = 1
	}
}
