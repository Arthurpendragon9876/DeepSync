package org.koitharu.kotatsu.anime.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dagger.hilt.android.AndroidEntryPoint
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeMangaRepository
import org.koitharu.kotatsu.anime.AnimeStream
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import javax.inject.Inject

private enum class PlayerDialog { NONE, SETTINGS, SPEED, SUBTITLES, AUDIO, QUALITY }

private class TrackOption(val label: String, val selected: Boolean, val onSelect: () -> Unit)

/**
 * Built-in anime player. Streams are chosen BEFORE this screen (see AnimeStreamPickerActivity).
 * Playback follows CloudStream's approach: media3 ExoPlayer fed by an OkHttp data source using the
 * extension's own client and headers, separate audio tracks merged in, external subtitles, decoder
 * fallback, generous retries. If the direct request fails it retries once through [AnimeStreamProxy]
 * (the same trick Zangetsu uses for Cloudflare-gated hosts) before showing an error.
 */
@AndroidEntryPoint
class AnimePlayerActivity : AppCompatActivity() {

	@Inject lateinit var repositoryFactory: MangaRepository.Factory
	@Inject lateinit var history: HistoryRepository

	private var player: ExoPlayer? = null
	private lateinit var playerView: PlayerView

	// ui state read by the Compose overlay
	private var isPlaying by mutableStateOf(false)
	private var isBuffering by mutableStateOf(true)
	private var position by mutableLongStateOf(0L)
	private var duration by mutableLongStateOf(0L)
	private var controlsVisible by mutableStateOf(true)
	private var locked by mutableStateOf(false)
	private var speed by mutableFloatStateOf(1f)
	private var resizeIdx by mutableIntStateOf(0)
	private var inPip by mutableStateOf(false)
	private var dialog by mutableStateOf(PlayerDialog.NONE)
	private var errorText by mutableStateOf<String?>(null)
	private var episodeTitle by mutableStateOf("")
	private var animeTitle by mutableStateOf("")
	private var episodeChip by mutableStateOf("")
	private var qualityChip by mutableStateOf("")
	private var tracks by mutableStateOf(Tracks.EMPTY)
	private var audioNote by mutableStateOf<String?>(null)

	private var index = 0
	private var streamIndex = 0
	private var triedProxy = false
	private var presetApplied = false

	/** "<stream>:<proxy>:<kind>" combinations already tried, so format fallbacks never loop. */
	private val attempts = mutableSetOf<String>()
	private var activeKind = StreamKind.PROGRESSIVE
	private var activeProxy = false
	private var resumeKey = ""

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (!AnimePlaybackSession.isActive) {
			finish()
			return
		}
		requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		WindowCompat.setDecorFitsSystemWindows(window, false)
		index = AnimePlaybackSession.index
		streamIndex = AnimePlaybackSession.streamIndex

		playerView = PlayerView(this).apply {
			useController = false
			setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
			// Subtitles: plain white text with a thin dark outline and no background box.
			subtitleView?.apply {
				setApplyEmbeddedStyles(false)
				setApplyEmbeddedFontSizes(false)
				setStyle(
					androidx.media3.ui.CaptionStyleCompat(
						android.graphics.Color.WHITE,
						android.graphics.Color.TRANSPARENT,
						android.graphics.Color.TRANSPARENT,
						androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
						android.graphics.Color.BLACK,
						null,
					),
				)
				setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
				setBottomPaddingFraction(0.07f)
			}
			layoutParams = FrameLayout.LayoutParams(-1, -1)
		}
		val overlay = ComposeView(this).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			setContent { DropSauceTheme { Overlay() } }
		}
		setContentView(FrameLayout(this).apply {
			setBackgroundColor(android.graphics.Color.BLACK)
			addView(playerView)
			addView(overlay)
		})
		hideSystemBars()
		startEpisode(resolveStreams = false)

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				while (true) {
					player?.let {
						position = it.currentPosition
						duration = it.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0L
						isPlaying = it.isPlaying
					}
					delay(300)
				}
			}
		}
	}

	// --- episode / stream handling --------------------------------------------------------------

	private val manga get() = AnimePlaybackSession.manga!!
	private val chapters get() = AnimePlaybackSession.chapters

	private fun updateLabels() {
		val chapter = chapters[index]
		val label = AnimePlaybackSession.episodeLabel(chapter)
		animeTitle = manga.title
		episodeChip = label
		episodeTitle = chapter.title?.takeIf { it.isNotBlank() && it != label } ?: label
		val title = AnimePlaybackSession.streams.getOrNull(streamIndex)?.video?.videoTitle.orEmpty()
		qualityChip = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE).find(title)?.let { "${it.groupValues[1]}p" }
			?: title
	}

	/** Loads [index] — re-resolving streams if we moved to another episode — and starts playback. */
	private fun startEpisode(resolveStreams: Boolean) {
		errorText = null
		triedProxy = false
		isBuffering = true
		updateLabels()
		if (!resolveStreams) {
			play()
			return
		}
		val previousTitle = AnimePlaybackSession.streams.getOrNull(streamIndex)?.video?.videoTitle
		lifecycleScope.launch {
			runCatching {
				val repo = repositoryFactory.create(manga.source) as AnimeMangaRepository
				repo.getStreams(chapters[index])
			}.onSuccess { list ->
				if (list.isEmpty()) {
					errorText = "No playable streams for this episode."
					return@onSuccess
				}
				// Keep the same server/quality as the previous episode when it exists.
				streamIndex = list.indexOfFirst { it.video.videoTitle == previousTitle }.takeIf { it >= 0 } ?: 0
				AnimePlaybackSession.begin(manga, chapters, index, list, streamIndex)
				updateLabels()
				play()
			}.onFailure { errorText = "Could not load streams:\n${it.message ?: it::class.java.simpleName}" }
		}
	}

	private fun go(newIndex: Int) {
		if (newIndex !in chapters.indices) return
		saveProgress()
		releasePlayer()
		index = newIndex
		startEpisode(resolveStreams = true)
	}

	// --- playback -------------------------------------------------------------------------------

	private var playJob: Job? = null

	private fun attemptKey(stream: AnimeStream, proxy: Boolean, kind: StreamKind) =
		"${System.identityHashCode(stream)}:$proxy:$kind"

	/**
	 * @param forceKind skip the probe's verdict and treat the URL as this kind (format fallback after
	 * the player rejected the probed one).
	 */
	private fun play(useProxy: Boolean = false, forceKind: StreamKind? = null) {
		releasePlayer()
		val stream: AnimeStream = AnimePlaybackSession.streams.getOrNull(streamIndex) ?: return
		playJob?.cancel()
		playJob = lifecycleScope.launch {
			// Work out what the URL really is (HLS / DASH / file / web page) before ExoPlayer guesses.
			val base = stream.probe ?: AnimeStreamProbe.probe(stream).also { stream.probe = it }
			if (base.kind == StreamKind.ERROR && forceKind == null) {
				isBuffering = false
				errorText = (base.note ?: "This stream can't be played") + "\nGo back and pick another server."
				return@launch
			}
			val probe = if (forceKind != null) StreamProbe(forceKind, base.separateAudio) else base

			// Separate audio renditions need the same treatment: an extension-less HLS audio URL would
			// otherwise be parsed as a plain audio file and kill the whole merged source.
			val audioKinds = HashMap<String, StreamKind>()
			if (stream.video.audioTracks.isNotEmpty()) {
				val client = stream.client ?: Injekt.get<NetworkHelper>().client
				stream.video.audioTracks.forEach { t ->
					audioKinds[t.url] = AnimeStreamProbe.probeUrl(client, t.url, stream.video.headers).kind
				}
			}

			attempts += attemptKey(stream, useProxy, probe.kind)
			activeKind = probe.kind
			activeProxy = useProxy
			startPlayback(stream, useProxy, probe, audioKinds)
		}
	}

	/** Other ways to read a stream the player could not parse as [kind]. */
	private fun alternativeKinds(kind: StreamKind): List<StreamKind> = when (kind) {
		StreamKind.PROGRESSIVE -> listOf(StreamKind.HLS)
		StreamKind.HLS, StreamKind.DASH -> listOf(StreamKind.PROGRESSIVE)
		StreamKind.ERROR -> emptyList()
	}

	private fun startPlayback(
		stream: AnimeStream,
		useProxy: Boolean,
		probe: StreamProbe,
		audioKinds: Map<String, StreamKind>,
	) {
		val video = stream.video
		val client: OkHttpClient = stream.client ?: Injekt.get<NetworkHelper>().client
		val headers = HashMap<String, String>()
		video.headers?.let { h -> for (i in 0 until h.size) headers[h.name(i)] = h.value(i) }

		fun route(url: String) = if (useProxy) AnimeStreamProxy.proxyUrl(client, url, headers) else url

		val dataSourceFactory: DataSource.Factory = if (useProxy) {
			DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
		} else {
			OkHttpDataSource.Factory(client)
				.setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
				.apply { headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { setUserAgent(it.value) } }
		}
		val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
			DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
		)
		val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
			.setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

		val subs = video.subtitleTracks.map { t ->
			MediaItem.SubtitleConfiguration.Builder(Uri.parse(route(t.url)))
				.setMimeType(subtitleMime(t.url))
				.setLanguage(t.lang)
				.setLabel(t.lang)
				.build()
		}
		val mainItem = MediaItem.Builder()
			.setUri(route(video.videoUrl))
			.setMediaMetadata(MediaMetadata.Builder().setTitle("$animeTitle — $episodeChip").build())
			.setSubtitleConfigurations(subs)
			.apply {
				when (probe.kind) {
					StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
					StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
					else -> Unit
				}
			}
			.build()
		var source: MediaSource = sourceFactory.createMediaSource(mainItem)
		// Extensions that serve audio separately (v16 `audioTracks`): merge each as its own track.
		val audioTracks = video.audioTracks.filter { audioKinds[it.url] != StreamKind.ERROR }
		if (audioTracks.isNotEmpty()) {
			val sources = mutableListOf<MediaSource>(source)
			audioTracks.forEach { track ->
				val item = MediaItem.Builder()
					.setUri(route(track.url))
					.apply {
						val isM3u8Url = track.url.substringBefore('?').endsWith(".m3u8", true)
						when {
							isM3u8Url || audioKinds[track.url] == StreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
							audioKinds[track.url] == StreamKind.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
							else -> Unit
						}
					}
					.build()
				sources.add(sourceFactory.createMediaSource(item))
			}
			// Separate audio rarely matches the video's length to the millisecond, so adjust offsets and clip durations.
			// (MergingMediaSource.Builder only exists in media3 >= 1.8; we're on 1.7.1, so use the constructor.)
			source = MergingMediaSource(
				/* adjustPeriodTimeOffsets = */ true,
				/* clipDurations = */ true,
				*sources.toTypedArray(),
			)
		}

		// Hardware decoders first; the bundled FFmpeg decoders step in for AC3/E-AC3/DTS and friends.
		val renderers = NextRenderersFactory(this)
			.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
			.setEnableDecoderFallback(true)
		val trackSelector = DefaultTrackSelector(this).apply {
			setParameters(
				buildUponParameters()
					.setConstrainAudioChannelCountToDeviceCapabilities(false)
					.setAllowAudioMixedMimeTypeAdaptiveness(true),
			)
		}
		val exo = ExoPlayer.Builder(this, renderers)
			.setTrackSelector(trackSelector)
			.setSeekBackIncrementMs(10_000)
			.setSeekForwardIncrementMs(10_000)
			.build()
		exo.addListener(object : Player.Listener {
			override fun onPlaybackStateChanged(state: Int) {
				isBuffering = state == Player.STATE_BUFFERING
				if (state == Player.STATE_ENDED && index + 1 in chapters.indices) go(index + 1)
			}

			override fun onTracksChanged(t: Tracks) {
				tracks = t
				applyAudioPreset(t)
				// Say so when audio exists but nothing playable is selected, instead of staying silent.
				val audio = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
				val playing = audio.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }
				audioNote = if (audio.isNotEmpty() && !playing) {
					val codecs = audio.flatMap { g -> (0 until g.length).mapNotNull { g.getTrackFormat(it).sampleMimeType } }
						.distinct().joinToString()
					"No playable audio track ($codecs). Open Audio to pick another."
				} else {
					null
				}
			}

			override fun onPlayerError(error: PlaybackException) {
				val parseError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
					error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
					error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
					error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
				// 1) The probe may have guessed the wrong format: try the other reading before giving up.
				if (parseError) {
					val alt = alternativeKinds(activeKind).firstOrNull { attemptKey(stream, activeProxy, it) !in attempts }
					if (alt != null) {
						play(useProxy = activeProxy, forceKind = alt)
						return
					}
				}
				// 2) Then retry everything through the header-aware loopback proxy.
				if (!triedProxy) {
					triedProxy = true
					Toast.makeText(this@AnimePlayerActivity, "Retrying through proxy…", Toast.LENGTH_SHORT).show()
					play(useProxy = true)
					return
				}
				val cause = generateSequence<Throwable>(error) { it.cause }.last()
				val server = stream.probe?.detail?.let { "\nServer replied: $it" }.orEmpty()
				errorText = if (parseError) {
					"Playback failed (${error.errorCodeName})\nThe player could not recognise the data this server sends " +
						"as video. Try another server, or Open in VLC.$server"
				} else {
					"Playback failed (${error.errorCodeName})\n${cause.message ?: cause::class.java.simpleName}$server"
				}
			}
		})
		exo.setMediaSource(source)
		resumeKey = "pos_" + manga.id + "_" + chapters[index].id
		val saved = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(resumeKey, 0L)
		if (saved > 5_000) exo.seekTo(saved)
		exo.setPlaybackSpeed(speed)
		exo.prepare()
		exo.playWhenReady = true
		playerView.player = exo
		player = exo
	}

	private fun subtitleMime(url: String): String {
		val u = url.substringBefore('?').lowercase()
		return when {
			u.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
			u.endsWith(".ass") || u.endsWith(".ssa") -> MimeTypes.TEXT_SSA
			else -> MimeTypes.TEXT_VTT
		}
	}

	/**
	 * Sub = Japanese audio with (English) subtitles on; Dub = English audio with subtitles off.
	 * Applied once per playback, when the tracks first show up; the Audio / Subtitles menus still
	 * override it afterwards.
	 */
	private fun applyAudioPreset(t: Tracks) {
		if (presetApplied) return
		val exo = player ?: return
		val audio = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
		val text = t.groups.filter { it.type == C.TRACK_TYPE_TEXT }
		if (audio.isEmpty() && text.isEmpty()) return
		presetApplied = true
		val dub = AnimePlaybackSession.audioPreset(this) == "dub"
		val wanted = if (dub) listOf("en", "eng", "english") else listOf("ja", "jpn", "jp", "japanese")

		fun matches(f: androidx.media3.common.Format, langs: List<String>): Boolean {
			val lang = f.language?.lowercase()
			val label = f.label?.lowercase()
			return (lang != null && langs.any { lang == it || lang.startsWith("$it-") }) ||
				(label != null && langs.any { it.length > 2 && label.contains(it) })
		}

		fun find(groups: List<Tracks.Group>, langs: List<String>): Pair<Tracks.Group, Int>? {
			for (g in groups) for (i in 0 until g.length) {
				if (g.isTrackSupported(i) && matches(g.getTrackFormat(i), langs)) return g to i
			}
			return null
		}

		val params = exo.trackSelectionParameters.buildUpon()
		find(audio, wanted)?.let { (g, i) ->
			params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
		}
		if (dub) {
			params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
		} else {
			val subs = find(text, listOf("en", "eng", "english")) ?: text.firstNotNullOfOrNull { g ->
				(0 until g.length).firstOrNull { g.isTrackSupported(it) }?.let { g to it }
			}
			if (subs != null) {
				params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
					.setOverrideForType(TrackSelectionOverride(subs.first.mediaTrackGroup, subs.second))
			}
		}
		exo.trackSelectionParameters = params.build()
	}

	private fun saveProgress() {
		val exo = player ?: return
		val pos = exo.currentPosition
		val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
		val finished = dur > 0 && pos > dur - 15_000
		getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(resumeKey, if (finished) 0L else pos).apply()
		val percent = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
		val chapter = chapters[index]
		val snapshot = manga.copy(chapters = chapters)
		CoroutineScope(Dispatchers.IO + NonCancellable).launch {
			runCatching { history.addOrUpdate(snapshot, chapter.id, 0, 0, percent, force = false) }
		}
	}

	private fun releasePlayer() {
		presetApplied = false
		player?.release()
		player = null
		playerView.player = null
		tracks = Tracks.EMPTY
		audioNote = null
	}

	// --- track selection ------------------------------------------------------------------------

	private fun trackOptions(type: Int): List<TrackOption> {
		val exo = player ?: return emptyList()
		val out = ArrayList<TrackOption>()
		val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(type)
		if (type == C.TRACK_TYPE_TEXT) {
			out += TrackOption("Off", disabled) {
				exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
					.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
			}
		}
		tracks.groups.filter { it.type == type }.forEach { g ->
			for (i in 0 until g.length) {
				if (!g.isTrackSupported(i)) continue
				val f = g.getTrackFormat(i)
				val label = f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "Track ${out.size + 1}"
				out += TrackOption(label, g.isTrackSelected(i) && !disabled) {
					exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
						.setTrackTypeDisabled(type, false)
						.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
						.build()
				}
			}
		}
		return out
	}

	// --- compose overlay ------------------------------------------------------------------------

	@Composable
	private fun Overlay() {
		val shown = controlsVisible && !inPip
		LaunchedEffect(shown, isPlaying, dialog, locked, position / 4000) {
			if (shown && isPlaying && dialog == PlayerDialog.NONE) {
				delay(4000)
				controlsVisible = false
			}
		}
		Box(
			Modifier.fillMaxSize().pointerInput(locked) {
				detectTapGestures(
					onTap = { controlsVisible = !controlsVisible },
					onDoubleTap = { offset ->
						if (locked) return@detectTapGestures
						val p = player ?: return@detectTapGestures
						val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
						p.seekTo((p.currentPosition + delta).coerceIn(0L, if (p.duration > 0) p.duration else Long.MAX_VALUE))
					},
				)
			},
		) {
			if (isBuffering && errorText == null) {
				CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
			}
			AnimatedVisibility(shown && !locked, enter = fadeIn(), exit = fadeOut()) { Controls() }
			AnimatedVisibility(shown && locked, enter = fadeIn(), exit = fadeOut()) {
				Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
					RoundIcon(R.drawable.ic_ap_lock, "Unlock", Modifier.align(Alignment.CenterStart)) { locked = false }
				}
			}
			audioNote?.let {
				Text(
					it,
					color = Color.White,
					fontSize = 13.sp,
					modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp)
						.clip(RoundedCornerShape(12.dp)).background(Color(0xCCB3261E)).padding(horizontal = 14.dp, vertical = 8.dp),
				)
			}
			errorText?.let { ErrorCard(it, Modifier.align(Alignment.Center)) }
			if (dialog != PlayerDialog.NONE) DialogHost()
		}
	}

	@Composable
	private fun Controls() {
		Box(
			Modifier.fillMaxSize().background(
				Brush.verticalGradient(
					0f to Color(0x99000000), 0.25f to Color(0x22000000), 0.75f to Color(0x22000000), 1f to Color(0xAA000000),
				),
			),
		) {
			Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
				// top bar: back, title + episode, then subtitles / audio / quality / more
				Row(verticalAlignment = Alignment.CenterVertically) {
					PlainIcon(R.drawable.ic_ap_back, "Back") { finish() }
					Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
						Text(
							animeTitle,
							color = Color.White,
							fontSize = 17.sp,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							if (episodeTitle == episodeChip) episodeChip else "$episodeChip  ·  $episodeTitle",
							color = Color(0xFFB8B8C0),
							fontSize = 14.sp,
							fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
					PlainIcon(R.drawable.ic_ap_subtitles, "Subtitles") { dialog = PlayerDialog.SUBTITLES }
					PlainIcon(R.drawable.ic_ap_audio, "Audio") { dialog = PlayerDialog.AUDIO }
					if (AnimePlaybackSession.streams.size > 1) {
						// "HQ": pick another server / quality
						Box(Modifier.size(48.dp).clickable { dialog = PlayerDialog.QUALITY }, contentAlignment = Alignment.Center) {
							Text(
								"HQ",
								color = Color.Black,
								fontSize = 11.sp,
								fontWeight = FontWeight.Bold,
								modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White)
									.padding(horizontal = 4.dp, vertical = 1.dp),
							)
						}
					}
					PlainIcon(R.drawable.ic_ap_more, "More") { dialog = PlayerDialog.SETTINGS }
				}
				Spacer(Modifier.weight(1f))
				// skip +85 s (typical opening length), tonal blue pill
				Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
					Surface(
						shape = RoundedCornerShape(24.dp),
						color = Color(0xFFB4C5F8),
						modifier = Modifier.clickable { seekBy(85_000L) },
					) { Text("+85 s", color = Color(0xFF1B2B5E), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) }
				}
				Spacer(Modifier.height(4.dp))
				// time - seek bar - time on one row
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(formatTime(position), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
					SeekBar(Modifier.weight(1f).padding(horizontal = 8.dp))
					Text(formatTime(duration), color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(end = 4.dp))
				}
				// bottom row: lock, rotate, speed ... pip, aspect
				Row(verticalAlignment = Alignment.CenterVertically) {
					PlainIcon(R.drawable.ic_ap_unlock, "Lock") { locked = true }
					PlainIcon(R.drawable.ic_ap_rotate, "Rotate") { toggleOrientation() }
					Text(
						"%.2fx".format(speed),
						color = Color(0xFFE0E0E6),
						fontSize = 15.sp,
						modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { dialog = PlayerDialog.SPEED }
							.padding(horizontal = 14.dp, vertical = 12.dp),
					)
					Spacer(Modifier.weight(1f))
					PlainIcon(R.drawable.ic_ap_pip, "Picture in picture") { enterPip() }
					PlainIcon(R.drawable.ic_ap_aspect, "Aspect ratio") { cycleResize() }
				}
			}
			// Centre transport sits directly in the full-screen box: the column above is padded by the
			// camera cut-out / system bars, which would pull it off-centre.
			Row(
				Modifier.align(Alignment.Center),
				horizontalArrangement = Arrangement.spacedBy(72.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				PlainIcon(R.drawable.ic_ap_prev, "Previous episode", size = 64.dp, iconSize = 40.dp, enabled = index > 0) { go(index - 1) }
				PlainIcon(
					if (isPlaying) R.drawable.ic_ap_pause else R.drawable.ic_ap_play,
					if (isPlaying) "Pause" else "Play",
					size = 80.dp, iconSize = 56.dp,
				) { togglePlay() }
				PlainIcon(
					R.drawable.ic_ap_next, "Next episode", size = 64.dp, iconSize = 40.dp, enabled = index + 1 < chapters.size,
				) { go(index + 1) }
			}
		}
	}

	@Composable
	private fun SeekBar(modifier: Modifier = Modifier) {
		var dragging by remember { mutableStateOf(false) }
		var dragValue by remember { mutableFloatStateOf(0f) }
		val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
		Slider(
			value = if (dragging) dragValue else fraction,
			onValueChange = { dragging = true; dragValue = it },
			onValueChangeFinished = {
				player?.let { if (duration > 0) it.seekTo((dragValue * duration).toLong()) }
				dragging = false
			},
			colors = SliderDefaults.colors(
				thumbColor = Color(0xFFAEC6FF),
				activeTrackColor = Color(0xFF2F6BE8),
				inactiveTrackColor = Color(0xFF1B2433),
			),
			modifier = modifier.height(28.dp),
		)
	}

	@Composable
	private fun ErrorCard(message: String, modifier: Modifier) {
		Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE6101010), modifier = modifier.padding(32.dp)) {
			Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Text(message, color = Color.White, fontSize = 15.sp)
				Spacer(Modifier.height(12.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					TextButton(onClick = {
						AnimePlaybackSession.streams.getOrNull(streamIndex)?.probe = null
						attempts.clear()
						errorText = null; triedProxy = false; isBuffering = true; play()
					}) { Text("Retry") }
					TextButton(onClick = {
						attempts.clear()
						errorText = null; triedProxy = true; isBuffering = true; play(useProxy = true)
					}) {
						Text("Use proxy")
					}
					TextButton(onClick = {
						val s = AnimePlaybackSession.streams.getOrNull(streamIndex)
						if (s == null || !AnimePlayerLauncher.launchVlc(this@AnimePlayerActivity, s, "$animeTitle — $episodeChip")) {
							Toast.makeText(this@AnimePlayerActivity, "VLC is not installed", Toast.LENGTH_SHORT).show()
						} else {
							finish()
						}
					}) { Text("Open in VLC") }
					TextButton(onClick = { finish() }) { Text("Close") }
				}
			}
		}
	}

	@Composable
	private fun DialogHost() {
		Box(
			Modifier.fillMaxSize().background(Color(0x99000000)).clickable { dialog = PlayerDialog.NONE },
			contentAlignment = Alignment.Center,
		) {
			Surface(
				shape = RoundedCornerShape(24.dp),
				color = Color(0xFF1B1B1F),
				modifier = Modifier.width(320.dp).clickable(enabled = false) {},
			) {
				Column(Modifier.padding(vertical = 12.dp)) {
					when (dialog) {
						PlayerDialog.SETTINGS -> {
							DialogTitle("Settings")
							DialogRow("Playback speed  ·  ${speedLabel(speed)}", false) { dialog = PlayerDialog.SPEED }
							DialogRow("Subtitles", false) { dialog = PlayerDialog.SUBTITLES }
							DialogRow("Audio track", false) { dialog = PlayerDialog.AUDIO }
							DialogRow("Aspect ratio  ·  ${RESIZE_LABELS[resizeIdx]}", false) { cycleResize() }
						}
						PlayerDialog.SPEED -> {
							DialogTitle("Playback speed")
							SPEEDS.forEach { s ->
								DialogRow(speedLabel(s), s == speed) {
									speed = s
									player?.setPlaybackSpeed(s)
									dialog = PlayerDialog.NONE
								}
							}
						}
						PlayerDialog.QUALITY -> {
							DialogTitle("Server / quality")
							LazyColumn(Modifier.height((minOf(AnimePlaybackSession.streams.size, 7) * 52).dp)) {
								itemsIndexed(AnimePlaybackSession.streams) { i, st ->
									DialogRow(st.video.videoTitle.ifBlank { "Stream ${i + 1}" }, i == streamIndex) { switchStream(i) }
								}
							}
						}
						PlayerDialog.SUBTITLES, PlayerDialog.AUDIO -> {
							val isText = dialog == PlayerDialog.SUBTITLES
							DialogTitle(if (isText) "Subtitles" else "Audio track")
							val options = trackOptions(if (isText) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO)
							if (options.isEmpty()) {
								Text("None available", color = Color.Gray, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
							} else {
								LazyColumn(Modifier.height((minOf(options.size, 7) * 52).dp)) {
									items(options) { o ->
										DialogRow(o.label, o.selected) { o.onSelect(); dialog = PlayerDialog.NONE }
									}
								}
							}
						}
						PlayerDialog.NONE -> Unit
					}
				}
			}
		}
	}

	@Composable
	private fun DialogTitle(text: String) {
		Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
	}

	@Composable
	private fun DialogRow(label: String, selected: Boolean, onClick: () -> Unit) {
		Row(
			Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(label, color = if (selected) Color(0xFFEFC32B) else Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
			if (selected) {
				Icon(painterResource(R.drawable.ic_ap_check), null, tint = Color(0xFFEFC32B), modifier = Modifier.size(20.dp))
			}
		}
	}

	@Composable
	private fun RoundIcon(
		icon: Int,
		description: String,
		modifier: Modifier = Modifier,
		size: androidx.compose.ui.unit.Dp = 44.dp,
		enabled: Boolean = true,
		onClick: () -> Unit,
	) {
		Box(
			modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Color(0x66000000))
				.clickable(enabled = enabled, onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painterResource(icon), description,
				tint = if (enabled) Color.White else Color(0x66FFFFFF),
				modifier = Modifier.size(size * 0.5f),
			)
		}
	}

	/** A bare white icon with a generous touch target, no background. */
	@Composable
	private fun PlainIcon(
		icon: Int,
		description: String,
		size: androidx.compose.ui.unit.Dp = 48.dp,
		iconSize: androidx.compose.ui.unit.Dp = 24.dp,
		enabled: Boolean = true,
		onClick: () -> Unit,
	) {
		Box(Modifier.size(size).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
			Icon(
				painterResource(icon), description,
				tint = if (enabled) Color.White else Color(0x55FFFFFF),
				modifier = Modifier.size(iconSize),
			)
		}
	}

	@Composable
	private fun Chip(text: String, modifier: Modifier = Modifier) {
		Text(
			text,
			modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
			color = Color(0xFFD9DCFF),
			fontSize = 13.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}

	@Composable
	private fun Pill(text: String) {
		Text(
			text,
			color = Color.White,
			fontSize = 16.sp,
			modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66000000)).padding(horizontal = 18.dp, vertical = 12.dp),
		)
	}

	@Composable
	private fun GroupPill(content: @Composable () -> Unit) {
		Row(
			Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66000000)).padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) { content() }
	}

	@Composable
	private fun PillIcon(icon: Int, description: String, onClick: () -> Unit) {
		Box(Modifier.size(46.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
			Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size(22.dp))
		}
	}

	// --- actions --------------------------------------------------------------------------------

	private fun togglePlay() {
		val p = player ?: return
		if (p.isPlaying) p.pause() else p.play()
	}

	private fun seekBy(deltaMs: Long) {
		val p = player ?: return
		p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, if (p.duration > 0) p.duration else Long.MAX_VALUE))
	}

	/** Switches to another server / quality of the same episode; the position is saved first and restored on start. */
	private fun switchStream(i: Int) {
		dialog = PlayerDialog.NONE
		if (i == streamIndex) return
		saveProgress()
		streamIndex = i
		AnimePlaybackSession.streamIndex = i
		attempts.clear()
		triedProxy = false
		errorText = null
		updateLabels()
		isBuffering = true
		play()
	}

	private fun toggleOrientation() {
		requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
			ActivityInfo.SCREEN_ORIENTATION_SENSOR
		} else {
			ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
		}
	}

	private fun cycleResize() {
		resizeIdx = (resizeIdx + 1) % RESIZE_MODES.size
		playerView.resizeMode = RESIZE_MODES[resizeIdx]
	}

	private fun enterPip() {
		runCatching {
			enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
		}
	}

	private fun speedLabel(s: Float) = if (s == 1f) "Normal" else "${s}x".replace(".0x", "x")

	private fun formatTime(ms: Long): String {
		val total = (ms / 1000).coerceAtLeast(0)
		val h = total / 3600
		val m = (total % 3600) / 60
		val s = total % 60
		return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
	}

	private fun hideSystemBars() {
		WindowInsetsControllerCompat(window, window.decorView).apply {
			systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
			hide(WindowInsetsCompat.Type.systemBars())
		}
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) hideSystemBars()
	}

	override fun onUserLeaveHint() {
		super.onUserLeaveHint()
		if (player?.isPlaying == true && !locked) enterPip()
	}

	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
		inPip = isInPictureInPictureMode
	}

	override fun onStop() {
		super.onStop()
		saveProgress()
		if (!isInPictureInPictureMode) player?.pause()
	}

	override fun onDestroy() {
		super.onDestroy()
		releasePlayer()
	}

	private companion object {
		const val PREFS = "deepsync_anime_player"
		val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
		val RESIZE_MODES = listOf(
			AspectRatioFrameLayout.RESIZE_MODE_FIT,
			AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
			AspectRatioFrameLayout.RESIZE_MODE_FILL,
		)
		val RESIZE_LABELS = listOf("Fit", "Zoom", "Stretch")
	}
}
