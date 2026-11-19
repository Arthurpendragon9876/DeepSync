package org.koitharu.kotatsu.main.ui.welcome

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.dropbox.DropboxSignIn
import org.koitharu.kotatsu.sync.backend.github.GithubDeviceChallenge
import org.koitharu.kotatsu.sync.backend.github.GithubSignIn
import org.koitharu.kotatsu.sync.backend.onedrive.OneDriveSignIn
import org.koitharu.kotatsu.sync.backend.webdav.WebDavSignIn
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sync.domain.SyncRepository
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

data class RestoreBackupResult(
	val report: RestoreReport?,
	val error: Throwable?,
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
	private val settings: AppSettings,
	private val storageManager: LocalStorageManager,
	private val backupManager: MihonBackupManager,
	private val syncRepository: SyncRepository,
	private val syncScheduler: SyncWorker.Scheduler,
	private val syncSettings: SyncSettings,
	private val githubSignIn: GithubSignIn,
	private val dropboxSignIn: DropboxSignIn,
	private val oneDriveSignIn: OneDriveSignIn,
	private val webDavSignIn: WebDavSignIn,
) : BaseViewModel() {

	val selectedTheme = MutableStateFlow(settings.theme)
	val selectedColorScheme = MutableStateFlow(settings.colorScheme)
	val isAmoledEnabled = MutableStateFlow(settings.isAmoledTheme)
	val storageSummary = MutableStateFlow<String?>(null)
	val onBackupRestored = MutableEventFlow<RestoreBackupResult>()
	val onGoogleSignInLaunch = MutableEventFlow<PendingIntent>()
	val onSignInCompleted = MutableEventFlow<Boolean>()
	val onOpenUrl = MutableEventFlow<String>()
	val onSignInError = MutableEventFlow<String>()
	val githubChallenge = MutableStateFlow<GithubDeviceChallenge?>(null)

	/** Providers this build can connect, in display order. */
	val availableProviders: List<SyncProvider> = syncRepository.availableProviders

	private var githubJob: Job? = null
	private var wasSignedIn = syncSettings.isSignedIn

	init {
		launchJob(Dispatchers.Default) {
			refreshStorageSummary()
		}
	}

	fun setTheme(mode: Int) {
		if (selectedTheme.value == mode) {
			return
		}
		selectedTheme.value = mode
		settings.setTheme(mode)
	}

	fun setColorScheme(colorScheme: ColorScheme) {
		if (selectedColorScheme.value == colorScheme) {
			return
		}
		selectedColorScheme.value = colorScheme
		settings.setColorScheme(colorScheme)
	}

	fun setAmoledTheme(isEnabled: Boolean) {
		if (isAmoledEnabled.value == isEnabled) {
			return
		}
		isAmoledEnabled.value = isEnabled
		settings.setAmoledTheme(isEnabled)
	}

	fun refreshStorageSummary() {
		launchJob(Dispatchers.Default) {
			val summary = storageManager.getDefaultWriteableDir()?.let {
				storageManager.getDirectoryDisplayName(it, isFullPath = true)
			}
			storageSummary.value = summary
		}
	}

	fun restoreBackup(uri: Uri) {
		launchLoadingJob(Dispatchers.Default) {
			val result = runCatching {
				backupManager.restoreBackup(uri, MihonBackupManager.Options())
			}
			onBackupRestored.call(
				RestoreBackupResult(
					report = result.getOrNull(),
					error = result.exceptionOrNull(),
				),
			)
		}
	}

	fun launchGoogleSignIn() {
		launchLoadingJob(Dispatchers.Default) {
			runCatchingCancellable { syncRepository.beginSignIn() }
				.onSuccess { consent -> if (consent != null) onGoogleSignInLaunch.call(consent) else onSignedIn() }
				.onFailure { onSignInCompleted.call(false) }
		}
	}

	fun handleGoogleSignInResult(data: Intent?) {
		launchLoadingJob(Dispatchers.Default) {
			if (runCatchingCancellable { syncRepository.finishSignIn(data) }.isSuccess) {
				onSignedIn()
			} else {
				onSignInCompleted.call(false)
			}
		}
	}

	/** Connects [provider]. WebDAV is the exception: the screen collects its login first ([connectWebDav]). */
	fun signInWith(provider: SyncProvider) {
		when (provider) {
			SyncProvider.DRIVE -> launchGoogleSignIn()
			SyncProvider.GITHUB -> signInWithGithub()
			SyncProvider.DROPBOX, SyncProvider.ONEDRIVE -> try {
				onOpenUrl.call(if (provider == SyncProvider.ONEDRIVE) oneDriveSignIn.begin() else dropboxSignIn.begin())
			} catch (e: Exception) {
				onSignInError.call(e.message ?: e.javaClass.simpleName)
			}

			SyncProvider.WEBDAV -> Unit
		}
	}

	fun webDavPrefill(): Pair<String?, String?> = webDavSignIn.savedAddress()

	fun connectWebDav(address: String, user: String, password: String) {
		launchLoadingJob(Dispatchers.Default) {
			try {
				webDavSignIn.connect(address, user, password)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				onSignInError.call(e.message ?: e.javaClass.simpleName)
				return@launchLoadingJob
			}
			onSignedIn()
		}
	}

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
				onSignInError.call(e.message ?: e.javaClass.simpleName)
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

	/**
	 * Browser sign-ins (Dropbox, OneDrive) finish in another activity while this one is paused; on resume
	 * we notice we became signed in and move on exactly as the in-app flows do.
	 */
	fun onResumed() {
		val signedIn = syncSettings.isSignedIn
		if (signedIn && !wasSignedIn) {
			wasSignedIn = true
			onSignInCompleted.call(true)
		}
	}

	private suspend fun onSignedIn() {
		wasSignedIn = true
		syncScheduler.schedule()
		syncScheduler.syncSoon() // bring the library over now, not at the next periodic run
		onSignInCompleted.call(true)
	}

	fun completeOnboarding() {
		settings.isOnboardingCompleted = true
	}
}
