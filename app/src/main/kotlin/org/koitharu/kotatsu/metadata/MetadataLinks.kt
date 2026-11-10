package org.koitharu.kotatsu.metadata

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which source title the user chose for a tracker title ("One Piece" on AniList is "One Piece" on Re:ANIME).
 * Opening that tracker title again goes straight there instead of matching by name.
 */
@Singleton
class MetadataLinks @Inject constructor(@ApplicationContext context: Context) {

	private val prefs = context.getSharedPreferences("metadata_links", Context.MODE_PRIVATE)

	fun find(key: String): Long? = prefs.getLong(key, 0L).takeIf { it != 0L }

	fun set(key: String, mangaId: Long) = prefs.edit { putLong(key, mangaId) }

	fun clear(key: String) = prefs.edit { remove(key) }

	companion object {

		fun key(ref: MetaRef) = "${ref.provider.name}:${ref.id}:${ref.mode.name}"
	}
}
