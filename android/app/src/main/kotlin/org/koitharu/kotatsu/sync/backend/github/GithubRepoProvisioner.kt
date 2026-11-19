package org.koitharu.kotatsu.sync.backend.github

import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.SyncApiException
import javax.inject.Inject
import javax.inject.Singleton

class GithubSignInException(message: String) : Exception(message)

/**
 * Finds or creates the private `deepsync-sync` repository of the token's owner and records where it
 * is. Used after sign-in, and again when the repository later goes missing (deleted, renamed, or its
 * branch changed), so the user never has to repair anything by hand.
 */
@Singleton
class GithubRepoProvisioner @Inject constructor(
	private val api: GithubApi,
	private val settings: SyncSettings,
) {

	data class Result(val user: GithubApi.User, val repo: GithubApi.Repo)

	suspend fun ensure(token: String): Result {
		val user = api.user(token)
		val fullName = "${user.login}/$REPO_NAME"
		val repo = api.repo(token, fullName) ?: try {
			api.createPrivateRepo(token, REPO_NAME, REPO_DESCRIPTION)
		} catch (e: SyncApiException) {
			// 422 "name already exists": another device created it a moment ago.
			if (e.code == 422) api.repo(token, fullName) ?: throw e else throw e
		}
		// Never put a library into a public repository, whatever the name says.
		if (!repo.isPrivate) {
			throw GithubSignInException("$fullName exists but is public. Make it private or rename it, then try again.")
		}
		settings.setProviderValue(SyncProvider.GITHUB, KEY_REPO, repo.fullName)
		settings.setProviderValue(SyncProvider.GITHUB, KEY_BRANCH, repo.defaultBranch)
		return Result(user, repo)
	}

	companion object {

		const val REPO_NAME = "deepsync-sync"
		const val KEY_REPO = "repo"
		const val KEY_BRANCH = "branch"
		const val REPO_DESCRIPTION = "DeepSync library sync (private, managed by the app)"
	}
}
