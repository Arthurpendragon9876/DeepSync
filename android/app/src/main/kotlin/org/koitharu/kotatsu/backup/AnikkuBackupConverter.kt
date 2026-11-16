package org.koitharu.kotatsu.backup

import kotlinx.serialization.protobuf.ProtoBuf
import org.koitharu.kotatsu.backup.model.AnikkuBackup
import org.koitharu.kotatsu.backup.model.AnikkuBackupAnime
import org.koitharu.kotatsu.backup.model.AnikkuBackupTracking
import org.koitharu.kotatsu.backup.model.AnikkuLegacyBackup
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupTracking

/**
 * Reads an Anikku backup and re-expresses it as a [MihonBackup] flagged `isAnime`, so the one restore
 * pipeline (categories, favourites, chapters, history, tracking) serves both. Anikku keeps anime as
 * "manga" entries with `episodes`, so the mapping is field-for-field.
 */
object AnikkuBackupConverter {

	/** Null when [payload] is not an Anikku/Aniyomi-layout backup with anything in it. */
	fun convert(proto: ProtoBuf, payload: ByteArray): MihonBackup? {
		val current = runCatching { proto.decodeFromByteArray(AnikkuBackup.serializer(), payload) }.getOrNull()
		if (current != null && current.hasContent()) {
			return build(current.backupManga, current.backupCategories, current.backupSources, current.backupExtensionRepo)
		}
		val legacy = runCatching { proto.decodeFromByteArray(AnikkuLegacyBackup.serializer(), payload) }.getOrNull()
		if (legacy != null && (legacy.backupManga.isNotEmpty() || legacy.backupCategories.isNotEmpty())) {
			return build(legacy.backupManga, legacy.backupCategories, legacy.backupSources, legacy.backupExtensionRepo)
		}
		return null
	}

	private fun AnikkuBackup.hasContent() = backupManga.isNotEmpty() || backupCategories.isNotEmpty() ||
		backupSources.isNotEmpty()

	private fun build(
		anime: List<AnikkuBackupAnime>,
		categories: List<org.koitharu.kotatsu.backup.model.AnikkuBackupCategory>,
		sources: List<org.koitharu.kotatsu.backup.model.AnikkuBackupSource>,
		repos: List<org.koitharu.kotatsu.backup.model.AnikkuBackupExtensionRepo>,
	) = MihonBackup(
		// A "Seasons" container has no episodes of its own; its seasons are separate entries.
		backupManga = anime.filterNot { it.fetchType == 0 && it.episodes.isEmpty() }.map { it.toEntry() },
		backupCategories = categories.map { MihonBackupCategory(name = it.name, order = it.order, id = it.id) },
		backupSources = sources.map { MihonBackupSource(name = it.name, sourceId = it.sourceId) },
		backupExtensionRepo = repos.map {
			MihonBackupExtensionRepo(it.baseUrl, it.name, it.shortName, it.website, it.signingKeyFingerprint)
		},
		isAnime = true,
	)

	private fun AnikkuBackupAnime.toEntry() = MihonBackupManga(
		source = source,
		url = url,
		title = customTitle?.takeIf { it.isNotBlank() } ?: title,
		artist = artist,
		author = author,
		description = description,
		genre = genre,
		status = status,
		thumbnailUrl = thumbnailUrl,
		dateAdded = dateAdded,
		chapters = episodes.map {
			MihonBackupChapter(
				url = it.url,
				name = it.name,
				scanlator = it.scanlator,
				read = it.seen,
				bookmark = it.bookmark,
				lastPageRead = it.lastSecondSeen,
				dateFetch = it.dateFetch,
				dateUpload = it.dateUpload,
				chapterNumber = it.episodeNumber,
				sourceOrder = it.sourceOrder,
				lastModifiedAt = it.lastModifiedAt,
			)
		},
		categories = categories,
		tracking = tracking.map { it.toTracking() },
		favorite = favorite,
		history = history.map { MihonBackupHistory(it.url, it.lastRead, it.readDuration) },
		lastModifiedAt = lastModifiedAt,
	)

	private fun AnikkuBackupTracking.toTracking() = MihonBackupTracking(
		syncId = syncId,
		libraryId = libraryId,
		mediaIdInt = mediaIdInt,
		trackingUrl = trackingUrl,
		title = title,
		lastChapterRead = lastEpisodeSeen,
		totalChapters = totalEpisodes,
		score = score,
		status = normalizeStatus(syncId, status),
		mediaId = mediaId,
	)

	/**
	 * Anikku's anime status numbers differ per tracker (MAL/AniList/Kitsu use 11/15/16/17, Shikimori
	 * 1..6). They are folded into the 1..6 vocabulary the restore pipeline decodes: 1 planned,
	 * 2 watching, 3 completed, 4 on hold, 5 dropped, 6 rewatching; 0 = unknown.
	 */
	private fun normalizeStatus(syncId: Int, status: Int): Int = when (syncId) {
		4 -> when (status) { // Shikimori
			1 -> 2
			2 -> 3
			3 -> 4
			4 -> 5
			5 -> 1
			6 -> 6
			else -> 0
		}

		else -> when (status) { // MyAnimeList, AniList, Kitsu
			11 -> 2
			2 -> 3
			3 -> 4
			4 -> 5
			15 -> 1
			16 -> if (syncId == 1) 1 else 6 // MAL plan-to-watch is 16; AniList rewatching is 16
			17 -> 6
			else -> 0
		}
	}
}
