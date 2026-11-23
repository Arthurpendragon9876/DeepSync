package org.koitharu.kotatsu.core.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.util.ext.asArrayList
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val CONTENT_TYPE_APK = "application/vnd.android.package-archive"

// The repository also hosts the desktop apps (shinkai-v*, yomi-v*): only Android tags are updates
private const val ANDROID_TAG_PREFIX = "android-v"

@Singleton
class AppUpdateRepository @Inject constructor(
	@BaseHttpClient private val okHttp: OkHttpClient,
	@ApplicationContext context: Context,
) {

	private val availableUpdate = MutableStateFlow<AppVersion?>(null)
	private val releasesUrl = buildString {
		append("https://api.github.com/repos/")
		append(context.getString(R.string.github_updates_repo))
		append("/releases?page=1&per_page=30")
	}
	private val currentVersionName = context.packageManager
		.getPackageInfo(context.packageName, 0)
		.versionName
		.orEmpty()

	val isUpdateAvailable: Boolean
		get() = availableUpdate.value != null

	fun observeAvailableUpdate() = availableUpdate.asStateFlow()

	suspend fun getAvailableVersions(): List<AppVersion> {
		val request = Request.Builder()
			.get()
			.url(releasesUrl)
		val jsonArray = okHttp.newCall(request.build()).await().parseJsonArray()
		return jsonArray.mapJSONNotNull { json ->
			if (json.optBoolean("draft")) {
				return@mapJSONNotNull null
			}
			val tag = json.optString("tag_name")
			if (!tag.startsWith(ANDROID_TAG_PREFIX)) {
				return@mapJSONNotNull null
			}
			val asset = json.optJSONArray("assets")?.find { jo ->
				jo.optString("content_type") == CONTENT_TYPE_APK ||
					jo.optString("name").endsWith(".apk", ignoreCase = true)
			} ?: return@mapJSONNotNull null
			AppVersion(
				id = json.getLong("id"),
				url = json.getString("html_url"),
				// The release title is "Yugen 0.1.3", so the version is read from the tag (android-v0.1.3)
				name = tag.removePrefix(ANDROID_TAG_PREFIX),
				apkSize = asset.getLong("size"),
				apkUrl = asset.getString("browser_download_url"),
				description = if (json.isNull("body")) "" else json.getString("body"),
			)
		}
	}

	suspend fun fetchUpdate(): AppVersion? = withContext(Dispatchers.Default) {
		if (!isUpdateSupported()) {
			return@withContext null
		}
		runCatchingCancellable {
			val currentVersion = VersionId(currentVersionName)
			val available = getAvailableVersions().asArrayList()
			available.sortBy { it.versionId }
			if (currentVersion.isStable) {
				available.retainAll { it.versionId.isStable }
			}
			available.maxByOrNull { it.versionId }
				?.takeIf { it.versionId > currentVersion }
		}.onFailure {
			it.printStackTraceDebug()
		}.onSuccess {
			availableUpdate.value = it
		}.getOrNull()
	}

	/**
	 * On: the feed is this fork's own repository (github_updates_repo) and only its android-v* releases
	 * count, so the desktop releases and the original DropSauce can never be offered here. Normal
	 * releases and patch releases (0.1.3-patch.1) both arrive through it.
	 */
	@Suppress("RedundantSuspendModifier")
	suspend fun isUpdateSupported(): Boolean {
		return true
	}

	private inline fun JSONArray.find(predicate: (JSONObject) -> Boolean): JSONObject? {
		val size = length()
		for (i in 0 until size) {
			val jo = getJSONObject(i)
			if (predicate(jo)) {
				return jo
			}
		}
		return null
	}
}
