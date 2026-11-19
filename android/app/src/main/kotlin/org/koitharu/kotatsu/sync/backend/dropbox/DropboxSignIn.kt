package org.koitharu.kotatsu.sync.backend.dropbox

import android.net.Uri
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.auth.Pkce
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.SyncRepository
import javax.inject.Inject
import javax.inject.Singleton

class DropboxSignInException(message: String) : Exception(message)

/**
 * Dropbox OAuth 2 with PKCE. [begin] returns the page to open in the browser; Dropbox then redirects
 * to `db-<app key>://oauth`, which [org.koitharu.kotatsu.sync.ui.SyncAuthRedirectActivity] hands to
 * [complete]. The pending verifier and state are persisted so a process killed while the user is in
 * the browser doesn't break the sign-in.
 */
@Singleton
class DropboxSignIn @Inject constructor(
	private val api: DropboxApi,
	private val backend: DropboxBackend,
	private val settings: SyncSettings,
	private val repository: SyncRepository,
) {

	val isConfigured: Boolean get() = BuildConfig.DROPBOX_APP_KEY.isNotBlank()

	private val redirectUri: String get() = "db-${BuildConfig.DROPBOX_APP_KEY}://oauth"

	fun begin(): String {
		if (!isConfigured) throw DropboxSignInException("Dropbox sync isn't configured in this build (DROPBOX_APP_KEY is empty)")
		val verifier = Pkce.newVerifier()
		val state = Pkce.newState()
		settings.setProviderValue(SyncProvider.DROPBOX, KEY_VERIFIER, verifier)
		settings.setProviderValue(SyncProvider.DROPBOX, KEY_STATE, state)
		return Uri.Builder()
			.scheme("https")
			.authority("www.dropbox.com")
			.path("/oauth2/authorize")
			.appendQueryParameter("client_id", BuildConfig.DROPBOX_APP_KEY)
			.appendQueryParameter("response_type", "code")
			.appendQueryParameter("redirect_uri", redirectUri)
			.appendQueryParameter("code_challenge", Pkce.challenge(verifier))
			.appendQueryParameter("code_challenge_method", "S256")
			.appendQueryParameter("token_access_type", "offline") // gives a refresh token
			.appendQueryParameter("state", state)
			.build()
			.toString()
	}

	/** Finishes the sign-in from the redirect; throws [DropboxSignInException] with a user-facing message. */
	suspend fun complete(redirect: Uri) {
		val verifier = settings.providerValue(SyncProvider.DROPBOX, KEY_VERIFIER)
		val state = settings.providerValue(SyncProvider.DROPBOX, KEY_STATE)
		// One try per begin(): a replayed or forged redirect finds nothing pending.
		settings.setProviderValue(SyncProvider.DROPBOX, KEY_VERIFIER, null)
		settings.setProviderValue(SyncProvider.DROPBOX, KEY_STATE, null)
		if (verifier == null || state == null || redirect.getQueryParameter("state") != state) {
			throw DropboxSignInException("This sign-in link is no longer valid. Start again from the sync settings.")
		}
		redirect.getQueryParameter("error")?.let {
			throw DropboxSignInException(
				if (it == "access_denied") "Access was denied on Dropbox." else "Dropbox sign-in failed: $it",
			)
		}
		val code = redirect.getQueryParameter("code") ?: throw DropboxSignInException("Dropbox returned no code.")
		val tokens = api.exchangeCode(BuildConfig.DROPBOX_APP_KEY, code, verifier, redirectUri)
		if (tokens.refreshToken == null) throw DropboxSignInException("Dropbox returned no refresh token.")
		backend.store(tokens)
		val account = api.account(tokens.accessToken)
		repository.completeSignIn(
			provider = SyncProvider.DROPBOX,
			accountId = account.email?.ifBlank { null } ?: "Dropbox",
			name = account.name?.displayName,
			photoUrl = account.photoUrl,
		)
	}

	private companion object {

		const val KEY_VERIFIER = "pkce_verifier"
		const val KEY_STATE = "oauth_state"
	}
}
