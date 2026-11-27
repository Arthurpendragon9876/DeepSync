package org.koitharu.kotatsu.settings.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.anime.AnimeExtensionManager
import org.koitharu.kotatsu.cloudstream.CloudstreamRuntime
import org.koitharu.kotatsu.diagnostics.PluginLoadIssue
import org.koitharu.kotatsu.diagnostics.PluginRuntime
import org.koitharu.kotatsu.diagnostics.SourceHealth
import org.koitharu.kotatsu.diagnostics.SourceHealthTracker
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import javax.inject.Inject

data class DiagnosticsState(
	val sources: List<SourceHealth> = emptyList(),
	val issues: List<PluginLoadIssue> = emptyList(),
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
	private val tracker: SourceHealthTracker,
	mihon: MihonExtensionManager,
	anime: AnimeExtensionManager,
	cloudstream: dagger.Lazy<CloudstreamRuntime>,
) : ViewModel() {

	private val refreshTick = MutableStateFlow(0)

	val state: StateFlow<DiagnosticsState> = combine(
		tracker.entries,
		mihon.failedExtensions,
		anime.errors,
		cloudstream.get().installed,
		refreshTick,
	) { _, mihonErrors, animeErrors, cs3, _ ->
		val issues = ArrayList<PluginLoadIssue>()
		mihonErrors.mapTo(issues) { PluginLoadIssue(PluginRuntime.MIHON, it.pkgName, it.message) }
		animeErrors.mapTo(issues) { (name, message) ->
			PluginLoadIssue(if (name == "cloudstream") PluginRuntime.CS3 else PluginRuntime.ANIYOMI, name, message)
		}
		cs3.filter { it.error != null }.mapTo(issues) { PluginLoadIssue(PluginRuntime.CS3, it.displayName, it.error.orEmpty()) }
		DiagnosticsState(
			// Re-read so timestamp-only updates show too; worst first
			sources = tracker.snapshot().sortedWith(compareByDescending<SourceHealth> { it.state.ordinal }.thenBy { it.title.lowercase() }),
			issues = issues,
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsState())

	fun refresh() {
		refreshTick.value++
	}

	fun clear() {
		tracker.clear()
		refresh()
	}
}
