package org.koitharu.kotatsu.backup.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Anikku (Aniyomi/Komikku fork) backup, the subset DeepSync restores. Field numbers are Anikku's own
 * (`eu.kanade.tachiyomi.data.backup.models`); everything not listed - app preferences, custom buttons,
 * saved searches, feeds - is skipped by the protobuf reader.
 */
@Serializable
data class AnikkuBackup(
	@ProtoNumber(501) val backupManga: List<AnikkuBackupAnime> = emptyList(),
	@ProtoNumber(502) val backupCategories: List<AnikkuBackupCategory> = emptyList(),
	@ProtoNumber(503) val backupSources: List<AnikkuBackupSource> = emptyList(),
	@ProtoNumber(505) val backupExtensionRepo: List<AnikkuBackupExtensionRepo> = emptyList(),
)

/** The older Aniyomi layout (still readable by Anikku): same entries under the low field numbers. */
@Serializable
data class AnikkuLegacyBackup(
	@ProtoNumber(3) val backupManga: List<AnikkuBackupAnime> = emptyList(),
	@ProtoNumber(4) val backupCategories: List<AnikkuBackupCategory> = emptyList(),
	@ProtoNumber(103) val backupSources: List<AnikkuBackupSource> = emptyList(),
	@ProtoNumber(107) val backupExtensionRepo: List<AnikkuBackupExtensionRepo> = emptyList(),
)

@Serializable
data class AnikkuBackupAnime(
	@ProtoNumber(1) var source: Long,
	@ProtoNumber(2) var url: String,
	@ProtoNumber(3) var title: String = "",
	@ProtoNumber(4) var artist: String? = null,
	@ProtoNumber(5) var author: String? = null,
	@ProtoNumber(6) var description: String? = null,
	@ProtoNumber(7) var genre: List<String> = emptyList(),
	@ProtoNumber(8) var status: Int = 0,
	@ProtoNumber(9) var thumbnailUrl: String? = null,
	@ProtoNumber(13) var dateAdded: Long = 0,
	@ProtoNumber(16) var episodes: List<AnikkuBackupEpisode> = emptyList(),
	@ProtoNumber(17) var categories: List<Long> = emptyList(),
	@ProtoNumber(18) var tracking: List<AnikkuBackupTracking> = emptyList(),
	@ProtoNumber(100) var favorite: Boolean = true,
	@ProtoNumber(104) var history: List<AnikkuBackupHistory> = emptyList(),
	@ProtoNumber(106) var lastModifiedAt: Long = 0,
	/** Aniyomi's FetchType enum as its ordinal: 0 = Seasons (a container), 1 = Episodes. */
	@ProtoNumber(507) var fetchType: Int = 1,
	@ProtoNumber(800) var customTitle: String? = null,
)

@Serializable
data class AnikkuBackupEpisode(
	@ProtoNumber(1) var url: String,
	@ProtoNumber(2) var name: String,
	@ProtoNumber(3) var scanlator: String? = null,
	@ProtoNumber(4) var seen: Boolean = false,
	@ProtoNumber(5) var bookmark: Boolean = false,
	@ProtoNumber(6) var lastSecondSeen: Long = 0,
	@ProtoNumber(7) var dateFetch: Long = 0,
	@ProtoNumber(8) var dateUpload: Long = 0,
	@ProtoNumber(9) var episodeNumber: Float = 0f,
	@ProtoNumber(10) var sourceOrder: Long = 0,
	@ProtoNumber(11) var lastModifiedAt: Long = 0,
)

@Serializable
data class AnikkuBackupHistory(
	@ProtoNumber(1) var url: String,
	@ProtoNumber(2) var lastRead: Long,
	@ProtoNumber(3) var readDuration: Long = 0,
)

@Serializable
class AnikkuBackupCategory(
	@ProtoNumber(1) var name: String,
	@ProtoNumber(2) var order: Long = 0,
	@ProtoNumber(3) var id: Long = 0,
)

@Serializable
data class AnikkuBackupTracking(
	@ProtoNumber(1) var syncId: Int,
	@ProtoNumber(2) var libraryId: Long,
	@ProtoNumber(3) var mediaIdInt: Int = 0,
	@ProtoNumber(4) var trackingUrl: String = "",
	@ProtoNumber(5) var title: String = "",
	@ProtoNumber(6) var lastEpisodeSeen: Float = 0f,
	@ProtoNumber(7) var totalEpisodes: Int = 0,
	@ProtoNumber(8) var score: Float = 0f,
	@ProtoNumber(9) var status: Int = 0,
	@ProtoNumber(100) var mediaId: Long = 0,
)

@Serializable
data class AnikkuBackupSource(
	@ProtoNumber(1) var name: String = "",
	@ProtoNumber(2) var sourceId: Long,
)

@Serializable
class AnikkuBackupExtensionRepo(
	@ProtoNumber(1) var baseUrl: String,
	@ProtoNumber(2) var name: String,
	@ProtoNumber(3) var shortName: String?,
	@ProtoNumber(4) var website: String,
	@ProtoNumber(5) var signingKeyFingerprint: String,
)
