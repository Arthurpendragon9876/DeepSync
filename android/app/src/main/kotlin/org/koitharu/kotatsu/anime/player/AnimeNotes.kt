package org.koitharu.kotatsu.anime.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One note on the watch page: tied to a moment of an episode, or "general" for the whole anime. */
data class AnimeNote(
	val id: Long,
	/** Position in the episode in ms, or -1 for a general note. */
	val positionMs: Long,
	/** "Episode 7" or "Anime note". */
	val episode: String,
	val createdAt: Long,
	val text: String,
)

/** Notes live on the device, per anime, in a small JSON blob in SharedPreferences. */
object AnimeNotes {

	private const val PREFS = "deepsync_anime_notes"

	fun load(context: Context, mangaId: Long): List<AnimeNote> {
		val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("notes_$mangaId", null)
			?: return emptyList()
		return runCatching {
			val array = JSONArray(raw)
			List(array.length()) { i ->
				val o = array.getJSONObject(i)
				AnimeNote(
					id = o.getLong("id"),
					positionMs = o.optLong("pos", -1L),
					episode = o.optString("ep", "Anime note"),
					createdAt = o.optLong("at", 0L),
					text = o.optString("text", ""),
				)
			}
		}.getOrDefault(emptyList())
	}

	fun save(context: Context, mangaId: Long, notes: List<AnimeNote>) {
		val array = JSONArray()
		notes.forEach { n ->
			array.put(
				JSONObject()
					.put("id", n.id)
					.put("pos", n.positionMs)
					.put("ep", n.episode)
					.put("at", n.createdAt)
					.put("text", n.text),
			)
		}
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("notes_$mangaId", array.toString()).apply()
	}
}
