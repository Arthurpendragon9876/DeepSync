package org.koitharu.kotatsu.home.ui.model

import org.json.JSONArray
import org.json.JSONObject

/** What the Home layout preview needs to draw one banner title; a trimmed copy of [HeroItem]. */
data class HeroSnap(
	val title: String,
	val coverUrl: String?,
	val bannerUrl: String?,
	val genres: List<String>,
	val badge: String?,
	val rating: Float?,
	val description: String?,
	/** Set when the cover comes from a source: that source's headers and the shared cover cache slot are needed to load it. */
	val mangaId: Long? = null,
	val sourceName: String? = null,
)

/** Saves the banner's titles to a string in settings and reads them back for the preview. */
object HeroSnapshot {

	fun encode(items: List<HeroItem>): String {
		val array = JSONArray()
		items.take(6).forEach { item ->
			array.put(
				JSONObject().apply {
					put("t", item.title)
					put("c", item.coverUrl)
					put("b", item.bannerUrl)
					put("g", JSONArray(item.genres.take(3)))
					put("d", item.badge)
					put("r", item.rating?.toDouble())
					put("s", item.description?.take(160))
					val manga = item.manga
					if (manga != null && item.coverUrl == manga.coverUrl) {
						put("i", manga.id)
						put("n", manga.source.name)
					}
				},
			)
		}
		return array.toString()
	}

	fun decode(json: String?): List<HeroSnap> {
		if (json.isNullOrEmpty()) return emptyList()
		return runCatching {
			val array = JSONArray(json)
			(0 until array.length()).map { i ->
				val o = array.getJSONObject(i)
				val g = o.optJSONArray("g")
				HeroSnap(
					title = o.optString("t"),
					coverUrl = o.optString("c").takeIf { it.isNotEmpty() && it != "null" },
					bannerUrl = o.optString("b").takeIf { it.isNotEmpty() && it != "null" },
					genres = (0 until (g?.length() ?: 0)).map { g!!.getString(it) },
					badge = o.optString("d").takeIf { it.isNotEmpty() && it != "null" },
					rating = if (o.isNull("r")) null else o.optDouble("r").toFloat(),
					description = o.optString("s").takeIf { it.isNotEmpty() && it != "null" },
					mangaId = if (o.has("i")) o.optLong("i") else null,
					sourceName = o.optString("n").takeIf { it.isNotEmpty() && it != "null" },
				)
			}.filter { it.title.isNotEmpty() }
		}.getOrDefault(emptyList())
	}
}
