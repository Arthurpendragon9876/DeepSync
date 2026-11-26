package org.koitharu.kotatsu.anime.player

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/** One skippable part of an episode (opening, ending, recap), in milliseconds. */
data class SkipStamp(val kind: Kind, val startMs: Long, val endMs: Long) {

	enum class Kind(val label: String) {
		OPENING("Skip Opening"),
		ENDING("Skip Ending"),
		RECAP("Skip Recap"),
	}
}

/**
 * Opening / ending / recap timestamps for the "Skip Opening" pill, fetched the way CloudStream does it:
 * the community AniSkip database, keyed by MyAnimeList id + episode number + episode length. The MAL id comes
 * from AniList's public search (no login needed). Everything fails soft: no match means no pill.
 */
object AnimeSkipTimes {

	private val malIds = ConcurrentHashMap<String, Int>()
	private val stamps = ConcurrentHashMap<String, List<SkipStamp>>()
	private val JSON = "application/json".toMediaType()

	suspend fun fetch(titles: List<String>, episode: Float, durationMs: Long): List<SkipStamp> {
		if (episode <= 0f || durationMs <= 0L) return emptyList()
		val ep = episode.toInt()
		val names = titles.filter { it.isNotBlank() }.distinct().take(3)
		if (names.isEmpty()) return emptyList()
		val cacheKey = names.first() + "#" + ep + "#" + durationMs / 5000L
		stamps[cacheKey]?.let { return it }
		return withContext(Dispatchers.IO) {
			runCatching {
				val mal = names.firstNotNullOfOrNull { malIdOf(it) } ?: return@runCatching emptyList()
				loadStamps(mal, ep, durationMs)
			}.getOrDefault(emptyList()).also { if (it.isNotEmpty()) stamps[cacheKey] = it }
		}
	}

	private fun malIdOf(title: String): Int? {
		malIds[title]?.let { return it }
		val client = Injekt.get<NetworkHelper>().client
		val body = JSONObject()
			.put("query", "query(\$s:String){Media(search:\$s,type:ANIME){idMal}}")
			.put("variables", JSONObject().put("s", title))
			.toString().toRequestBody(JSON)
		val request = Request.Builder().url("https://graphql.anilist.co").post(body).build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) return null
			val id = JSONObject(response.body?.string() ?: return null)
				.optJSONObject("data")?.optJSONObject("Media")?.optInt("idMal", 0) ?: 0
			if (id <= 0) return null
			malIds[title] = id
			return id
		}
	}

	private fun loadStamps(malId: Int, episode: Int, durationMs: Long): List<SkipStamp> {
		val client = Injekt.get<NetworkHelper>().client
		val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode" +
			"?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap&episodeLength=${durationMs / 1000L}"
		client.newCall(Request.Builder().url(url).build()).execute().use { response ->
			if (!response.isSuccessful) return emptyList()
			val json = JSONObject(response.body?.string() ?: return emptyList())
			val results = json.optJSONArray("results") ?: return emptyList()
			val out = ArrayList<SkipStamp>()
			for (i in 0 until results.length()) {
				val item = results.getJSONObject(i)
				val kind = when (item.optString("skipType")) {
					"op", "mixed-op" -> SkipStamp.Kind.OPENING
					"ed", "mixed-ed" -> SkipStamp.Kind.ENDING
					"recap" -> SkipStamp.Kind.RECAP
					else -> continue
				}
				val interval = item.optJSONObject("interval") ?: continue
				val start = (interval.optDouble("startTime", -1.0) * 1000).toLong()
				val end = (interval.optDouble("endTime", -1.0) * 1000).toLong()
				if (start >= 0 && end > start) out += SkipStamp(kind, start, end)
			}
			return out.sortedBy { it.startMs }
		}
	}
}
