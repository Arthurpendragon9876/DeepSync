package org.koitharu.kotatsu.sync.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.sync.backend.dropbox.DropboxSignIn
import org.koitharu.kotatsu.sync.backend.onedrive.OneDriveSignIn
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

/**
 * Receives the browser redirect that ends an OAuth sign-in (Dropbox `db-<app key>://oauth`, OneDrive
 * `msal<client id>://auth`), finishes it, and
 * disappears. It has no UI: the sync settings screen underneath refreshes itself when it resumes.
 */
@AndroidEntryPoint
class SyncAuthRedirectActivity : ComponentActivity() {

	@Inject
	lateinit var dropboxSignIn: DropboxSignIn

	@Inject
	lateinit var oneDriveSignIn: OneDriveSignIn

	@Inject
	lateinit var scheduler: SyncWorker.Scheduler

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val redirect = intent?.data
		if (redirect == null) {
			finish()
			return
		}
		lifecycleScope.launch {
			try {
				// The redirect scheme says whose sign-in this ends: db-<app key> or msal<client id>.
				when {
					redirect.scheme?.startsWith("msal") == true -> oneDriveSignIn.complete(redirect)
					else -> dropboxSignIn.complete(redirect)
				}
				scheduler.schedule()
				scheduler.syncSoon() // bring the library over now, not at the next periodic run
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				Toast.makeText(
					applicationContext,
					e.message ?: getString(R.string.sync_error),
					Toast.LENGTH_LONG,
				).show()
			}
			finish()
		}
	}
}
