package org.koitharu.kotatsu.favourites.domain

import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.contentMode
import org.koitharu.kotatsu.core.prefs.AppSettings
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.favourites.data.FavouriteManga
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.domain.LocalObserveMapper
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@Reusable
class LocalFavoritesObserver @Inject constructor(
	localMangaIndex: LocalMangaIndex,
	private val db: MangaDatabase,
	private val settings: AppSettings,
) : LocalObserveMapper<FavouriteManga, Manga>(localMangaIndex) {

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Manga>> = db.getFavouritesDao().observeAll(order, filterOptions, limit).inCurrentMode().mapToLocal()

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Manga>> =
		db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit).inCurrentMode().mapToLocal()

	/**
	 * Keeps the titles of the active mode (Manga / Anime / Novel) while they still carry their original source. Once
	 * mapped to the downloaded copy the source is always "Local", which would read as Manga and drop every
	 * downloaded anime or novel.
	 */
	private fun Flow<List<FavouriteManga>>.inCurrentMode(): Flow<List<FavouriteManga>> = map { list ->
		val mode = settings.homeMode
		list.filter { toManga(it).source.contentMode == mode }
	}

	override fun toManga(e: FavouriteManga) = e.manga.toManga(e.tags.toMangaTags(), null)

	override fun toResult(e: FavouriteManga, manga: Manga) = manga
}
