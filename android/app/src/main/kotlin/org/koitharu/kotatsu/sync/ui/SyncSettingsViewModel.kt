package org.koitharu.kotatsu.sync.ui

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.dropbox.DropboxSignIn
import org.koitharu.kotatsu.sync.backend.github.GithubDeviceChallenge
import org.koitharu.kotatsu.sync.backend.onedrive.OneDriveSignIn
import org.koitharu.kotatsu.sync.backend.github.GithubSignIn
import org.koitharu.kotatsu.sync.backend.webdav.WebDavSignIn
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.domain.SyncRepository
import org.koitharu.kotatsu.sync.domain.SyncResult
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

data class SyncUiState(
	val isSignedIn: Boolean = false,
	val provider: SyncProvider? = null,
	val availableProviders: List<SyncProvider> = emptyList(),
	val needsReauth: Boolean = false,
	val accountEmail: String? = null,
	val accountName: String? = null,
	val accountPhotoUrl: String? = null,
	val isEmailHidden: Boolean = false,
	val intervalMinutes: Int = 0,
	val isWifiOnly: Boolean = false,
	val enabledContent: Set<String> = SyncContent.DEFAULT,
	val lastSyncTimestamp: Long = 0L,
	val lastError: String? = null,
	val isLegacyDeviceActive: Boolean = false,
)

sealed interface SyncEvent {
	data class LaunchSignIn(val consent: PendingIntent) : SyncEvent
	data class OpenUrl(val url: String) : SyncEvent
	data class Message(val resId: Int) : SyncEvent
	data class Error(val message: String?) : SyncEvent
}

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
	private val syncSettings: SyncSettings,
	private val repository: SyncRepository,
	private val githubSignIn: GithubSignIn,
	private val dropboxSignIn: DropboxSignIn,
	private val oneDriveSignIn: OneDriveSignIn,
	private val webDavSignIn: WebDavSignIn,
	private val scheduler: SyncWorker.Scheduler,
) : BaseViewModel() {

	val uiState = MutableStateFlow(readState())

	val isSyncing = repository.isSyncing
	val events = MutableEventFlow<SyncEvent>()

	/** Non-null while a GitHub device-flow code is waiting to be approved in the browser. */
	val githubChallenge = MutableStateFlow<GithubDeviceChallenge?>(null)
	private var githubJob: Job? = null

	init {
		// Refresh the displayed "last synced" / error info whenever a background sync finishes.
		viewModelScope.launch {
			isSyncing.drop(1).collect { running ->
				if (!running) refresh()
			}
		}
	}

	/** Starts connecting [provider]. WebDAV is the exception: the screen collects its login first ([connectWebDav]). */
	fun signInWith(provider: SyncProvider) {
		when (provider) {
			SyncProvider.DRIVE -> signIn()
			SyncProvider.GITHUB -> signInWithGithub()
			SyncProvider.DROPBOX, SyncProvider.ONEDRIVE -> signInWithBrowser(provider)
			SyncProvider.WEBDAV -> Unit
		}
	}

	/** Address and user name to prefill the WebDAV form with when the login has to be re-entered. */
	fun webDavPrefill(): Pair<String?, String?> = webDavSignIn.savedAddress()

	fun connectWebDav(address: String, user: String, password: String) {
		launchLoadingJob(Dispatchers.Default) {
			try {
				webDavSignIn.connect(address, user, password)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
				return@launchLoadingJob
			}
			onSignedIn()
		}
	}

	/** Opens the provider's sign-in page in the browser; the redirect is finished by [SyncAuthRedirectActivity]. */
	private fun signInWithBrowser(provider: SyncProvider) {
		try {
			val url = if (provider == SyncProvider.ONEDRIVE) oneDriveSignIn.begin() else dropboxSignIn.begin()
			events.call(SyncEvent.OpenUrl(url))
		} catch (e: Exception) {
			events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
		}
	}

	private fun signIn() {
		launchLoadingJob(Dispatchers.Default) {
			val consent = try {
				repository.beginSignIn()
			} catch (e: Exception) {
				events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
				return@launchLoadingJob
			}
			if (consent != null) events.call(SyncEvent.LaunchSignIn(consent)) else onSignedIn()
		}
	}

	/** Two taps: pick GitHub, approve the code on github.com. The repo is created behind the scenes. */
	private fun signInWithGithub() {
		githubJob?.cancel()
		githubJob = launchJob(Dispatchers.Default) {
			try {
				val challenge = githubSignIn.begin()
				githubChallenge.value = challenge
				githubSignIn.awaitAuthorization(challenge)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				githubChallenge.value = null
				events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
				return@launchJob
			}
			githubChallenge.value = null
			onSignedIn()
		}
	}

	fun cancelGithubSignIn() {
		githubJob?.cancel()
		githubChallenge.value = null
	}

	fun onSignInResult(data: Intent?) {
		launchLoadingJob(Dispatchers.Default) {
			try {
				repository.finishSignIn(data)
			} catch (e: Exception) {
				events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
				return@launchLoadingJob
			}
			onSignedIn()
		}
	}

	private suspend fun onSignedIn() {
		refresh()
		scheduler.schedule()
		runSync() // pull existing data right away, surfacing any error
	}

	/** Runs a sync in the foreground and reports the outcome to the user. */
	private suspend fun runSync() {
		when (val result = repository.sync(force = true)) {
			is SyncResult.Success -> events.call(SyncEvent.Message(R.string.sync_completed))
			is SyncResult.SignInRequired -> events.call(SyncEvent.Error(null))
			is SyncResult.Error -> events.call(SyncEvent.Error(result.message))
		}
		refresh()
	}

	fun signOut() {
		launchJob(Dispatchers.Default) {
			repository.signOut()
			scheduler.unschedule()
			refresh()
		}
	}

	fun syncNow() {
		if (!syncSettings.isSignedIn) return
		launchLoadingJob(Dispatchers.Default) { runSync() }
	}

	fun setInterval(minutes: Int) {
		syncSettings.intervalMinutes = minutes
		launchJob(Dispatchers.Default) { scheduler.schedule() }
		refresh()
	}

	fun setWifiOnly(value: Boolean) {
		syncSettings.isWifiOnly = value
		launchJob(Dispatchers.Default) { scheduler.schedule() }
		refresh()
	}

	fun setEmailHidden(value: Boolean) {
		syncSettings.isEmailHidden = value
		refresh()
	}

	fun setEnabledContent(content: Set<String>) {
		syncSettings.enabledContent = content
		refresh()
	}

	fun deleteAllData() {
		launchLoadingJob(Dispatchers.Default) {
			when (val result = repository.deleteRemoteData()) {
				is SyncResult.Success -> events.call(SyncEvent.Message(R.string.sync_data_deleted))
				is SyncResult.SignInRequired -> events.call(SyncEvent.Error(null))
				is SyncResult.Error -> events.call(SyncEvent.Error(result.message))
			}
			refresh()
		}
	}

	/** Re-reads the settings; the screen calls it on resume, after an OAuth redirect may have signed us in. */
	fun refresh() {
		uiState.value = readState()
	}

	private fun readState() = SyncUiState(
		isSignedIn = syncSettings.isSignedIn,
		provider = syncSettings.provider,
		availableProviders = repository.availableProviders,
		needsReauth = syncSettings.needsReauth,
		accountEmail = syncSettings.accountEmail,
		accountName = syncSettings.accountName,
		accountPhotoUrl = syncSettings.accountPhotoUrl,
		isEmailHidden = syncSettings.isEmailHidden,
		intervalMinutes = syncSettings.intervalMinutes,
		isWifiOnly = syncSettings.isWifiOnly,
		enabledContent = syncSettings.enabledContent,
		lastSyncTimestamp = syncSettings.lastSyncTimestamp,
		lastError = syncSettings.lastSyncError,
		isLegacyDeviceActive = System.currentTimeMillis() - syncSettings.legacyWriterSeenAt < LEGACY_NOTICE_MS,
	)

	private companion object {

		const val LEGACY_NOTICE_MS = 14L * 24 * 60 * 60 * 1000
	}
}
