package org.koitharu.kotatsu.anime.player

import android.content.Context
import org.koitharu.kotatsu.anime.AnimeStream
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

/**
 * Hand-off between the stream picker and the player. Streams hold live source objects and cannot
 * be parcelled, so they travel through this process-local holder instead of the intent.
 */
object AnimePlaybackSession {

	@Volatile var manga: Manga? = null
	@Volatile var chapters: List<MangaChapter> = emptyList()
	@Volatile var index: Int = 0
	@Volatile var streams: List<AnimeStream> = emptyList()
	@Volatile var streamIndex: Int = 0

	/** Incognito watching: nothing is saved - no history, no resume position, no tracker update. */
	@Volatile var incognito: Boolean = false

	fun begin(manga: Manga, chapters: List<MangaChapter>, index: Int, streams: List<AnimeStream>, streamIndex: Int) {
		this.manga = manga
		this.chapters = chapters
		this.index = index
		this.streams = streams
		this.streamIndex = streamIndex
	}

	val isActive: Boolean get() = manga != null && chapters.isNotEmpty() && streams.isNotEmpty()

	private const val PREFS = "deepsync_anime"

	/** Title of the stream the user ticked "Remember server" for, per anime. */
	fun rememberedServer(context: Context, manga: Manga): String? =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server_${manga.id}", null)

	fun setRememberedServer(context: Context, manga: Manga, title: String?) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
			if (title == null) remove("server_${manga.id}") else putString("server_${manga.id}", title)
		}.apply()
	}

	/** "sub" = Japanese audio with subtitles, "dub" = English audio without subtitles. */
	fun audioPreset(context: Context): String =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("audio_preset", "sub") ?: "sub"

	fun setAudioPreset(context: Context, preset: String) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("audio_preset", preset).apply()
	}

	fun episodeLabel(chapter: MangaChapter): String {
		val n = chapter.number
		val num = if (n > 0f) (if (n % 1f == 0f) n.toInt().toString() else n.toString()) else null
		return when {
			num != null -> "Episode $num"
			!chapter.title.isNullOrBlank() -> chapter.title!!
			else -> "Episode"
		}
	}
}
