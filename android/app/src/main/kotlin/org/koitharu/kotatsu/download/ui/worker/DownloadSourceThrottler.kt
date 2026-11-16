package org.koitharu.kotatsu.download.ui.worker

import android.content.Context
import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every running download polite to the site it reads from: at most
 * [MAX_CONCURRENT_REQUESTS_PER_SOURCE] requests in flight per source across the whole app, plus the
 * optional per-source slowdown.
 *
 * The per-chapter page parallelism inside a download is unaware of other downloads, so without a
 * shared limit two or three downloads from the same site would multiply it and earn a rate-limit
 * block, which costs far more time than it saves.
 */
@Singleton
class DownloadSourceThrottler @Inject constructor(
	@ApplicationContext private val context: Context,
	private val appSettings: AppSettings,
) {

	private val nextSlot = MutableObjectLongMap<MangaSource>()
	private val semaphores = HashMap<MangaSource, Semaphore>()
	private val settingsMap = HashMap<MangaSource, SourceSettings>()
	private val novelPace = HashMap<MangaSource, NovelPace>()

	private class NovelPace {
		var used = 0
		var nextFree = 0L
	}

	/**
	 * Waits for [source]'s next slowdown slot without taking a request permit. For background work
	 * that should be paced but must never queue behind a download, such as reader prefetch.
	 */
	suspend fun pace(source: MangaSource) = awaitSlot(source)

	/** Runs [block] as one request against [source], waiting for a free slot first. */
	suspend fun <T> withRequestPermit(source: MangaSource, block: suspend () -> T): T {
		val semaphore = synchronized(semaphores) {
			semaphores.getOrPut(source) { Semaphore(MAX_CONCURRENT_REQUESTS_PER_SOURCE) }
		}
		return semaphore.withPermit {
			awaitSlot(source)
			block()
		}
	}

	private suspend fun awaitSlot(source: MangaSource) {
		// Novel sources use the Advanced novel settings: a burst of requests, then a delay (with
		// per-source overrides). Everything else keeps the per-source slowdown below.
		if (source.isNovelSource) {
			awaitNovelSlot(source)
			return
		}
		val settings = synchronized(settingsMap) {
			settingsMap.getOrPut(source) { SourceSettings(context, source) }
		}
		if (!settings.isSlowdownEnabled) {
			return
		}
		val now = SystemClock.elapsedRealtime()
		// Reserve the next free slot up front. Stamping "now" and only then sleeping made every
		// waiting request compute the same wake-up time and fire as one burst - the opposite of what
		// a slowdown is for.
		val slot = synchronized(nextSlot) {
			val reserved = maxOf(nextSlot.getOrDefault(source, 0L), now)
			nextSlot[source] = reserved + REQUEST_INTERVAL
			reserved
		}
		delay(slot - now)
	}

	private suspend fun awaitNovelSlot(source: MangaSource) {
		if (!appSettings.isNovelThrottleEnabled) return
		val delayMs = (appSettings.novelDelayOverrides[source.name] ?: appSettings.novelRequestDelayMs).toLong()
		if (delayMs <= 0L) return
		val burst = appSettings.novelRequestsBeforeDelay
		val waitMs = synchronized(novelPace) {
			val pace = novelPace.getOrPut(source) { NovelPace() }
			val now = SystemClock.elapsedRealtime()
			// The delay has passed while idle: the burst refills.
			if (pace.used >= burst && now >= pace.nextFree) pace.used = 0
			if (pace.used < burst) {
				pace.used++
				if (pace.used == burst) pace.nextFree = now + delayMs
				0L
			} else {
				val slot = maxOf(pace.nextFree, now)
				pace.nextFree = slot + delayMs
				slot - now
			}
		}
		if (waitMs > 0) delay(waitMs)
	}

	private companion object {

		const val MAX_CONCURRENT_REQUESTS_PER_SOURCE = 5
		const val REQUEST_INTERVAL = 1_600L
	}
}
