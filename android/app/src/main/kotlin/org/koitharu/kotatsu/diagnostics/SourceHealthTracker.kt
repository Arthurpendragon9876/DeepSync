package org.koitharu.kotatsu.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.anime.AnimeMangaSource
import org.koitharu.kotatsu.cloudstream.CloudstreamRuntime
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only record of how each source has been behaving this session. Nothing is sent anywhere and nothing is
 * written to disk; it only feeds the Source & Plugin Health screen.
 *
 * Repositories reach it through [trackHealth], which uses [instance] so the three plugin repositories do not need
 * a new constructor argument. The instance is set as soon as the repository factory (which injects this) exists.
 */
@Singleton
class SourceHealthTracker @Inject constructor(
	private val networkState: NetworkState,
) {

	private val book = SourceHealthBook()
	private val _entries = MutableStateFlow<List<SourceHealth>>(emptyList())

	val entries: StateFlow<List<SourceHealth>> = _entries.asStateFlow()

	init {
		instance = this
	}

	fun onSuccess(source: MangaSource) {
		if (book.onSuccess(source.name, titleOf(source), runtimeOf(source))) {
			_entries.value = book.snapshot()
		}
	}

	fun onFailure(source: MangaSource, error: Throwable) {
		if (book.onFailure(source.name, titleOf(source), runtimeOf(source), error, networkState.isOnline())) {
			_entries.value = book.snapshot()
		}
	}

	/** Includes timestamp-only updates that [entries] skips. */
	fun snapshot(): List<SourceHealth> = book.snapshot()

	fun clear() {
		book.clear()
		_entries.value = emptyList()
	}

	companion object {

		@Volatile
		var instance: SourceHealthTracker? = null
			private set

		fun runtimeOf(source: MangaSource): PluginRuntime = when (source) {
			is MihonMangaSource -> PluginRuntime.MIHON
			is LnMangaSource -> PluginRuntime.LNREADER
			is AnimeMangaSource -> if (CloudstreamRuntime.isCloudstreamPackage(source.pkgName)) {
				PluginRuntime.CS3
			} else {
				PluginRuntime.ANIYOMI
			}

			else -> PluginRuntime.BUILT_IN
		}

		fun titleOf(source: MangaSource): String = when (source) {
			is MihonMangaSource -> source.displayName
			is LnMangaSource -> source.displayName
			is AnimeMangaSource -> source.displayName
			else -> source.name
		}
	}
}

/** Runs [block] and records its outcome for [source]; the result or exception is passed through unchanged. */
suspend inline fun <T> trackHealth(source: MangaSource, block: () -> T): T {
	val tracker = SourceHealthTracker.instance
	try {
		val result = block()
		tracker?.onSuccess(source)
		return result
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		tracker?.onFailure(source, e)
		throw e
	}
}
