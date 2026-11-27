package org.koitharu.kotatsu.backup.foreign

import android.content.Context
import android.net.Uri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.foreign.ForeignBackupConverter.CategoryPlan
import org.koitharu.kotatsu.backup.foreign.ForeignBackupRules.App
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.Locale
import javax.inject.Inject

/**
 * Turns a Kototoro or Usagi backup into a Yugen one, ready for the normal restore service.
 *
 * The converted file is left in the cache directory because the restore runs afterwards in a foreground service;
 * stale ones are removed the next time something is imported.
 */
@Reusable
class ForeignBackupImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
) {

	class Prepared(
		val uri: Uri,
		val sections: Set<BackupSection>,
		val app: App,
		val report: ForeignBackupConverter.Report,
	)

	suspend fun prepare(source: Uri): Prepared = withContext(Dispatchers.IO) {
		removeStaleFiles()
		val raw = File.createTempFile("foreign_in", ".zip", context.cacheDir)
		val converted = File(context.cacheDir, "$PREFIX${System.currentTimeMillis()}.bk.zip")
		try {
			val input = context.contentResolver.openInputStream(source) ?: throw IOException("Cannot open $source")
			input.use { stream -> raw.outputStream().use { stream.copyTo(it) } }
			val result = converted.outputStream().buffered().use { out ->
				ForeignBackupConverter.importFrom(raw, out, categoryResolver())
			}
			if (result.report.converted == 0) {
				throw IllegalStateException(context.getString(R.string.import_foreign_empty))
			}
			val sections = BackupSection.entries.filterTo(EnumSet.noneOf(BackupSection::class.java)) {
				it != BackupSection.INDEX && it.entryName in result.sections
			}
			Prepared(Uri.fromFile(converted), sections, result.app, result.report)
		} catch (e: Throwable) {
			converted.delete()
			throw e
		} finally {
			raw.delete()
		}
	}

	/** Same title -> the local category; otherwise a fresh id above every id in use, so nothing is overwritten. */
	private suspend fun categoryResolver(): ForeignBackupConverter.CategoryResolver {
		val dao = database.getFavouriteCategoriesDao()
		val local = dao.findAll()
		var nextId = (local + dao.findAllForSync()).maxOfOrNull { it.categoryId }.let { (it ?: 0) + 1 }
		val known = HashMap<String, Int>()
		local.forEach { known[it.title.trim().lowercase(Locale.ROOT)] = it.categoryId }
		return ForeignBackupConverter.CategoryResolver { title, _ ->
			val key = title.trim().lowercase(Locale.ROOT)
			val existing = known[key]
			when {
				existing != null -> CategoryPlan(existing, create = false)
				else -> {
					val id = nextId++
					known[key] = id
					CategoryPlan(id, create = true)
				}
			}
		}
	}

	private fun removeStaleFiles() {
		val limit = System.currentTimeMillis() - STALE_AFTER_MS
		context.cacheDir.listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() < limit }?.forEach { it.delete() }
	}

	private companion object {
		const val PREFIX = "foreign_import_"
		const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
	}
}
