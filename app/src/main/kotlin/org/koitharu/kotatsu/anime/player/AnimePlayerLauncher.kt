package org.koitharu.kotatsu.anime.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import eu.kanade.tachiyomi.network.NetworkHelper
import org.koitharu.kotatsu.anime.AnimeStream
import org.koitharu.kotatsu.anime.ui.AnimeStreamPickerActivity
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Entry points for starting playback, plus the "always use VLC" preference. */
object AnimePlayerLauncher {

	private const val PREFS = "deepsync_anime"
	private const val KEY_VLC = "use_vlc"
	private const val VLC_PACKAGE = "org.videolan.vlc"

	fun isVlcDefault(context: Context): Boolean =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VLC, false)

	fun setVlcDefault(context: Context, value: Boolean) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VLC, value).apply()
	}

	/** Opens the stream chooser (outside the player) for [chapterId]; -1 resumes from history. */
	fun openEpisode(context: Context, manga: Manga, chapterId: Long = -1L) {
		val intent = Intent(context, AnimeStreamPickerActivity::class.java)
			.putExtra(AppRouter.KEY_MANGA, ParcelableManga(manga, withDescription = false))
			.putExtra(AnimeStreamPickerActivity.EXTRA_CHAPTER_ID, chapterId)
		if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
	}

	/** Starts playback of the chosen stream: straight into VLC if that is the default, else built-in. */
	fun play(
		context: Context,
		manga: Manga,
		chapters: List<MangaChapter>,
		index: Int,
		streams: List<AnimeStream>,
		streamIndex: Int,
	) {
		AnimePlaybackSession.begin(manga, chapters, index, streams, streamIndex)
		val stream = streams[streamIndex]
		if (isVlcDefault(context)) {
			when {
				stream.video.audioTracks.isNotEmpty() || stream.probe?.separateAudio == true ->
					Toast.makeText(
						context,
						"This stream keeps audio in a separate track, which VLC can't merge: using the built-in player",
						Toast.LENGTH_LONG,
					).show()
				!isVlcInstalled(context) ->
					Toast.makeText(context, "VLC is not installed: using the built-in player", Toast.LENGTH_SHORT).show()
				launchVlc(context, stream, "${manga.title} — ${AnimePlaybackSession.episodeLabel(chapters[index])}") -> return
			}
		}
		val intent = Intent(context, AnimePlayerActivity::class.java)
		if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
	}

	fun isVlcInstalled(context: Context): Boolean = try {
		context.packageManager.getPackageInfo(VLC_PACKAGE, 0)
		true
	} catch (_: PackageManager.NameNotFoundException) {
		false
	}

	/**
	 * Starts VLC on [stream] with no chooser. The URL goes through [AnimeStreamProxy] so VLC gets the
	 * extension's headers and its HLS audio/subtitle renditions (VLC ignores intent header extras).
	 */
	fun launchVlc(context: Context, stream: AnimeStream, title: String): Boolean {
		if (!isVlcInstalled(context)) return false
		val video = stream.video
		val client = stream.client ?: Injekt.get<NetworkHelper>().client
		val headers = buildMap {
			video.headers?.let { h -> for (i in 0 until h.size) put(h.name(i), h.value(i)) }
		}
		val url = AnimeStreamProxy.proxyUrl(client, video.videoUrl, headers)
		val isHls = video.videoUrl.substringBefore('?').endsWith(".m3u8", true)
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setPackage(VLC_PACKAGE)
			setDataAndTypeAndNormalize(Uri.parse(url), if (isHls) "application/x-mpegURL" else "video/*")
			putExtra("title", title)
			video.subtitleTracks.firstOrNull()?.let {
				putExtra("subtitles_location", AnimeStreamProxy.proxyUrl(client, it.url, headers))
			}
			if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		return try {
			context.startActivity(intent)
			true
		} catch (_: ActivityNotFoundException) {
			false
		}
	}
}
