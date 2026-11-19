package org.koitharu.kotatsu.sync.backend.github

import kotlinx.coroutines.delay
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.SecretStore
import org.koitharu.kotatsu.sync.domain.SyncRepository
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows during the device flow: type [userCode] at [verificationUri]. */
class GithubDeviceChallenge(
	val userCode: String,
	val verificationUri: String,
	internal val deviceCode: String,
	internal val expiresAtMs: Long,
	internal val intervalSec: Int,
)

/**
 * GitHub OAuth device flow plus the invisible repository setup: after authorization the private
 * `deepsync-sync` repo is found or created, so the user never touches a token or a git command.
 */
@Singleton
class GithubSignIn @Inject constructor(
	private val api: GithubApi,
	private val secrets: SecretStore,
	private val provisioner: GithubRepoProvisioner,
	private val repository: SyncRepository,
) {

	val isConfigured: Boolean get() = BuildConfig.GITHUB_CLIENT_ID.isNotBlank()

	suspend fun begin(): GithubDeviceChallenge {
		if (!isConfigured) throw GithubSignInException("GitHub sync isn't configured in this build (GITHUB_CLIENT_ID is empty)")
		// `repo` is the only OAuth scope that reaches private repositories.
		val code = api.requestDeviceCode(BuildConfig.GITHUB_CLIENT_ID, SCOPE)
		return GithubDeviceChallenge(
			userCode = code.userCode,
			verificationUri = code.verificationUri,
			deviceCode = code.deviceCode,
			expiresAtMs = System.currentTimeMillis() + code.expiresIn * 1000L,
			intervalSec = code.interval,
		)
	}

	/**
	 * Polls until the user approves in the browser, then provisions the repo and records the account.
	 * Cancelling the calling coroutine abandons the flow. Throws [GithubSignInException] on denial/expiry.
	 */
	suspend fun awaitAuthorization(challenge: GithubDeviceChallenge) {
		var interval = challenge.intervalSec
		var token: String? = null
		while (token == null) {
			if (System.currentTimeMillis() > challenge.expiresAtMs) throw GithubSignInException("The code expired. Try again.")
			delay(interval * 1000L)
			val poll = api.pollDeviceToken(BuildConfig.GITHUB_CLIENT_ID, challenge.deviceCode)
			when (poll.error) {
				null -> token = poll.accessToken ?: throw GithubSignInException("GitHub returned no token")
				"authorization_pending" -> Unit
				"slow_down" -> interval = poll.interval ?: (interval + 5)
				"expired_token" -> throw GithubSignInException("The code expired. Try again.")
				"access_denied" -> throw GithubSignInException("Access was denied on GitHub.")
				"device_flow_disabled" ->
					throw GithubSignInException("Enable \"Device Flow\" in the GitHub OAuth App settings.")

				else -> throw GithubSignInException("GitHub sign-in failed: ${poll.error}")
			}
		}
		finish(token)
	}

	private suspend fun finish(token: String) {
		// Provision first: a public name clash must fail before anything is stored.
		val (user, _) = provisioner.ensure(token)
		secrets.set(GithubBackend.TOKEN_SECRET, token)
		repository.completeSignIn(SyncProvider.GITHUB, "github.com/${user.login}", user.name?.ifBlank { null } ?: user.login, user.avatarUrl)
	}

	private companion object {

		const val SCOPE = "repo"
	}
}
