package org.koitharu.kotatsu.backup.foreign

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.foreign.ForeignBackupRules.App
import org.koitharu.kotatsu.backup.local.data.LocalBackupRepository
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Writes the library as a Kototoro or Usagi `.bk.zip`.
 *
 * It takes a regular backup first (so the library is dumped by the one code path that already knows every table)
 * and lets [ForeignBackupConverter] rewrite it for the target app.
 */
@Reusable
class ForeignBackupExporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val localBackup: LocalBackupRepository,
	private val sourceMap: KotatsuSourceMap,
) {

	suspend fun export(target: App, uri: Uri): ForeignBackupConverter.Report = withContext(Dispatchers.IO) {
		val staging = File.createTempFile("foreign_export", ".bk.zip", context.cacheDir)
		try {
			ZipOutputStream(staging.outputStream().buffered()).use { localBackup.createBackup(it, null) }
			val legacyNames = if (target == App.USAGI) sourceMap.legacyNamesById() else emptyMap()
			val report = try {
				val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
				output.use { ForeignBackupConverter.exportTo(target, staging, it, legacyNames) }
			} catch (e: Throwable) {
				deleteQuietly(uri, e)
				throw e
			}
			if (report.converted == 0) {
				val error = IllegalStateException(context.getString(R.string.export_foreign_empty, target.displayName()))
				deleteQuietly(uri, error)
				throw error
			}
			report
		} finally {
			staging.delete()
		}
	}

	private fun deleteQuietly(uri: Uri, cause: Throwable) {
		try {
			DocumentFile.fromSingleUri(context, uri)?.delete()
		} catch (e: Throwable) {
			cause.addSuppressed(e)
		}
	}

	companion object {

		fun App.displayName(): String = when (this) {
			App.KOTOTORO -> "Kototoro"
			App.USAGI -> "Usagi"
		}

		fun generateFileName(target: App): String = buildString {
			append("yugen_")
			append(target.name.lowercase(Locale.ROOT))
			append('_')
			append(SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()))
			append(".bk.zip")
		}
	}
}
