package org.koitharu.kotatsu.anime.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.anime.AnimeExtension
import org.koitharu.kotatsu.anime.AnimeExtensionManager
import org.koitharu.kotatsu.anime.AnimeRepoEntry
import org.koitharu.kotatsu.anime.player.AnimePlayerLauncher
import javax.inject.Inject

@HiltViewModel
class AnimeExtensionsViewModel @Inject constructor(
	@ApplicationContext context: Context,
	private val extensions: AnimeExtensionManager,
) : ViewModel() {

	val installed = extensions.extensions
	val loadErrors = extensions.errors
	val hidden = extensions.hidden

	private val _repos = MutableStateFlow(extensions.repoUrls)
	val repos: StateFlow<List<String>> = _repos.asStateFlow()

	private val _entries = MutableStateFlow<List<AnimeRepoEntry>>(emptyList())
	val entries: StateFlow<List<AnimeRepoEntry>> = _entries.asStateFlow()

	private val _busy = MutableStateFlow<Set<String>>(emptySet())
	val busy: StateFlow<Set<String>> = _busy.asStateFlow()

	private val _loading = MutableStateFlow(false)
	val loading: StateFlow<Boolean> = _loading.asStateFlow()

	private val _message = MutableStateFlow<String?>(null)
	val message: StateFlow<String?> = _message.asStateFlow()

	private val _useVlc = MutableStateFlow(AnimePlayerLauncher.isVlcDefault(context))
	val useVlc: StateFlow<Boolean> = _useVlc.asStateFlow()

	private val appContext = context.applicationContext

	init {
		viewModelScope.launch { extensions.reload() }
		refreshRepos()
	}

	fun consumeMessage() {
		_message.value = null
	}

	fun setUseVlc(value: Boolean) {
		AnimePlayerLauncher.setVlcDefault(appContext, value)
		_useVlc.value = value
	}

	fun setHidden(pkg: String, hidden: Boolean) {
		extensions.setHidden(pkg, hidden)
	}

	fun rescan() {
		viewModelScope.launch { extensions.reload() }
	}

	fun addRepo(url: String) {
		val clean = url.trim()
		if (clean.isEmpty()) return
		extensions.repoUrls = (extensions.repoUrls + clean).distinct()
		_repos.value = extensions.repoUrls
		refreshRepos()
	}

	fun removeRepo(url: String) {
		extensions.repoUrls = extensions.repoUrls - url
		_repos.value = extensions.repoUrls
		refreshRepos()
	}

	fun refreshRepos() {
		viewModelScope.launch {
			_loading.value = true
			val all = ArrayList<AnimeRepoEntry>()
			for (url in extensions.repoUrls) {
				runCatching { extensions.fetchRepo(url) }
					.onSuccess { all += it }
					.onFailure { _message.value = "Repo failed: ${it.message}" }
			}
			_entries.value = all.distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
			_loading.value = false
		}
	}

	fun install(entry: AnimeRepoEntry) {
		viewModelScope.launch {
			_busy.update { it + entry.pkg }
			runCatching { extensions.install(entry) }
				.onSuccess {
					val err = extensions.errors.value[entry.pkg] ?: extensions.errors.value["${entry.pkg}.apk"]
					_message.value = err?.let { "Installed but failed to load: $it" } ?: "${entry.name} installed"
				}
				.onFailure { _message.value = "Install failed: ${it.message}" }
			_busy.update { it - entry.pkg }
		}
	}

	fun uninstall(ext: AnimeExtension) {
		viewModelScope.launch { extensions.uninstallPrivate(ext.pkg) }
	}
}
