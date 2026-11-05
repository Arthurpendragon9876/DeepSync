package org.koitharu.kotatsu.scrobbling.common.data

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerCandidate
import org.koitharu.kotatsu.scrobbling.common.domain.model.TrackerReadingEntry

interface ScrobblerRepository {

	val oauthUrl: String

	val isAuthorized: Boolean

	val cachedUser: ScrobblerUser?

	suspend fun authorize(code: String?)

	suspend fun loadUser(): ScrobblerUser

	fun logout()

	suspend fun unregister(mangaId: Long)

	suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga>

	suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo

	/**
	 * Registers the manga on the service, or adopts the entry already there.
	 *
	 * @return `true` when the service already had an entry, whose status, rating and progress were
	 * stored as-is. Callers must not overwrite them; `false` means the entry was just created and its
	 * fields are placeholders that still need filling in.
	 */
	suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean

	suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity

	suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int)

	/**
	 * @param setStartDate writes today as the reading start date. Left alone otherwise, so a date the
	 * user set on the website is never disturbed by an unrelated rating or note edit.
	 */
	suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	)

	/**
	 * The titles this account is currently reading, newest activity first. Services without a
	 * usable list endpoint keep the default (empty) and simply get no Home row.
	 */
	suspend fun getReadingList(limit: Int, anime: Boolean = false): List<TrackerReadingEntry> = emptyList()

	/**
	 * The whole list of this account, every status. Services that cannot list everything fall back
	 * to the reading list.
	 */
	suspend fun getLibrary(anime: Boolean = false): List<TrackerReadingEntry> = getReadingList(Int.MAX_VALUE, anime)

	/**
	 * Like [findManga] but each result carries all of its names. Services that only know one or two
	 * names per title use this default.
	 */
	suspend fun findCandidates(query: String, type: ScrobblerMangaType): List<TrackerCandidate> =
		findManga(query, 0, type).map { TrackerCandidate(it, listOfNotNull(it.name, it.altName)) }
}
