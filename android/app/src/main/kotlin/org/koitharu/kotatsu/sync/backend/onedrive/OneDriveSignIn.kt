package org.koitharu.kotatsu.sync.backend.onedrive

import android.net.Uri
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.auth.Pkce
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncRepository
import javax.inject.Inject
import javax.inject.Singleton

class OneDriveSignInException(message: String) : Exception(message)

/**
 * Microsoft OAuth 2 with PKCE for personal accounts. [begin] returns the page to open in the browser;
 * Microsoft then redirects to `msal<client id>://auth`, which
 * [org.koitharu.kotatsu.sync.ui.SyncAuthRedirectActivity] hands to [complete]. The pending verifier and
 * state are persisted, single-use, so a process killed while the user is in the browser is harmless.
 */
@Singleton
class OneDriveSignIn @Inject constructor(
	private val api: OneDriveApi,
	private val backend: OneDriveBackend,
	private val settings: SyncSettings,
	private val repository: SyncRepository,
) {

	val isConfigured: Boolean get() = BuildConfig.ONEDRIVE_CLIENT_ID.isNotBlank()

	private val redirectUri: String get() = "msal${BuildConfig.ONEDRIVE_CLIENT_ID}://auth"

	fun begin(): String {
		if (!isConfigured) throw OneDriveSignInException("OneDrive sync isn't configured in this build (ONEDRIVE_CLIENT_ID is empty)")
		val verifier = Pkce.newVerifier()
		val state = Pkce.newState()
		settings.setProviderValue(SyncProvider.ONEDRIVE, KEY_VERIFIER, verifier)
		settings.setProviderValue(SyncProvider.ONEDRIVE, KEY_STATE, state)
		return Uri.parse(OneDriveApi.AUTHORIZE_URL).buildUpon()
			.appendQueryParameter("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
			.appendQueryParameter("response_type", "code")
			.appendQueryParameter("redirect_uri", redirectUri)
			.appendQueryParameter("response_mode", "query")
			.appendQueryParameter("scope", OneDriveBackend.SCOPE)
			.appendQueryParameter("code_challenge", Pkce.challenge(verifier))
			.appendQueryParameter("code_challenge_method", "S256")
			.appendQueryParameter("state", state)
			.appendQueryParameter("prompt", "select_account")
			.build()
			.toString()
	}

	/** Finishes the sign-in from the redirect; throws [OneDriveSignInException] with a user-facing message. */
	suspend fun complete(redirect: Uri) {
		val verifier = settings.providerValue(SyncProvider.ONEDRIVE, KEY_VERIFIER)
		val state = settings.providerValue(SyncProvider.ONEDRIVE, KEY_STATE)
		settings.setProviderValue(SyncProvider.ONEDRIVE, KEY_VERIFIER, null)
		settings.setProviderValue(SyncProvider.ONEDRIVE, KEY_STATE, null)
		if (verifier == null || state == null || redirect.getQueryParameter("state") != state) {
			throw OneDriveSignInException("This sign-in link is no longer valid. Start again from the sync settings.")
		}
		redirect.getQueryParameter("error")?.let {
			throw OneDriveSignInException(
				if (it == "access_denied") "Access was denied on Microsoft." else redirect.getQueryParameter("error_description") ?: "Microsoft sign-in failed: $it",
			)
		}
		val code = redirect.getQueryParameter("code") ?: throw OneDriveSignInException("Microsoft returned no code.")
		val tokens = try {
			api.exchangeCode(BuildConfig.ONEDRIVE_CLIENT_ID, code, verifier, redirectUri, OneDriveBackend.SCOPE)
		} catch (e: SyncApiException) {
			throw OneDriveSignInException(e.message ?: "Microsoft sign-in failed.")
		}
		if (tokens.refreshToken == null) throw OneDriveSignInException("Microsoft returned no refresh token.")
		backend.store(tokens)
		val me = api.me(tokens.accessToken)
		repository.completeSignIn(
			provider = SyncProvider.ONEDRIVE,
			accountId = me.mail?.ifBlank { null } ?: me.userPrincipalName?.ifBlank { null } ?: "OneDrive",
			name = me.displayName,
			photoUrl = null,
		)
	}

	private companion object {

		const val KEY_VERIFIER = "pkce_verifier"
		const val KEY_STATE = "oauth_state"
	}
}
