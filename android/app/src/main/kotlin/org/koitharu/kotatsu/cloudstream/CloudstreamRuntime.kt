package org.koitharu.kotatsu.cloudstream

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.anime.AnimeExtension
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.schabi.newpipe.extractor.NewPipe
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** A plugin archive on disk, as the settings screen lists it. */
data class CloudstreamInstalledPlugin(
	val internalName: String,
	val displayName: String,
	val version: Int,
	val providers: List<String>,
	/** Why it did not load; null when it did. */
	val error: String?,
)

/** One plugin a Cloudstream repository offers. */
data class CloudstreamRepoPlugin(
	val internalName: String,
	val name: String,
	val url: String,
	val version: Int,
	val language: String?,
	val description: String?,
	val types: List<String>,
	val iconUrl: String?,
	/** Cloudstream's own status: 0 means the repository marks the plugin as down. */
	val status: Int,
	val repoUrl: String,
)

/**
 * Loads Cloudstream `.cs3` plugins and keeps them registered in the Cloudstream library.
 *
 * Each plugin provider is exposed to the rest of the app as an Aniyomi-style anime source
 * ([CloudstreamAnimeSource]), so Explore, Home, the library, history and the anime player all work with it unchanged.
 * [org.koitharu.kotatsu.anime.AnimeExtensionManager] asks [loadExtensions] for them on every reload.
 *
 * Plugin archives live in `filesDir/cloudstream/plugins/<internalName>.cs3`.
 */
@Singleton
class CloudstreamRuntime @Inject constructor(
	@ApplicationContext private val context: Context,
	// Built lazily: the client may only be created off the main thread.
	@BaseHttpClient private val httpClient: Lazy<OkHttpClient>,
) {

	private class LoadedPlugin(
		val file: File,
		val signature: String,
		val plugin: BasePlugin,
		val classLoader: ClassLoader,
		val preparedFile: File,
		val providers: List<MainAPI>,
		val extractors: List<ExtractorApi>,
		val manifest: Manifest,
	)

	private class Manifest(
		val pluginClassName: String,
		val name: String?,
		val version: Int,
		val requiresResources: Boolean,
	)

	private val loaded = ConcurrentHashMap<String, LoadedPlugin>()
	private val errors = ConcurrentHashMap<String, String>()

	private val _installed = MutableStateFlow<List<CloudstreamInstalledPlugin>>(emptyList())

	/** What is in the plugin folder, with load errors; updated by every [loadExtensions], install and uninstall. */
	val installed: StateFlow<List<CloudstreamInstalledPlugin>> = _installed.asStateFlow()

	private val prefs = context.getSharedPreferences("deepsync_cloudstream", Context.MODE_PRIVATE)
	private val _repos = MutableStateFlow(readRepos())

	/** Repository URLs the user added. */
	val repos: StateFlow<List<String>> = _repos.asStateFlow()

	private val pluginsDir: File get() = File(File(context.filesDir, "cloudstream"), "plugins").apply { mkdirs() }
	private val preparedDir: File get() = File(context.codeCacheDir, "cloudstream/plugins").apply { mkdirs() }
	private val dexDir: File get() = File(context.codeCacheDir, "cloudstream/dex").apply { mkdirs() }

	@Volatile
	private var configured = false

	init {
		// Plugins load with Cloudstream's activity and may use it for dialogs, so keep the visible one handy.
		(context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
			object : Application.ActivityLifecycleCallbacks {
				override fun onActivityResumed(activity: Activity) {
					CommonActivity.INSTANCE.setActivityInstance(activity)
				}

				override fun onActivityPaused(activity: Activity) {
					if (CommonActivity.INSTANCE.activity === activity) CommonActivity.INSTANCE.setActivityInstance(null)
				}

				override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
				override fun onActivityStarted(activity: Activity) = Unit
				override fun onActivityStopped(activity: Activity) = Unit
				override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
				override fun onActivityDestroyed(activity: Activity) = Unit
			},
		)
	}

	// --- loading ---------------------------------------------------------------------------------

	/**
	 * Loads every archive in the plugin folder (those already loaded and unchanged are kept) and returns one
	 * [AnimeExtension] per plugin that provided at least one source. Call off the main thread.
	 */
	@Synchronized
	fun loadExtensions(): List<AnimeExtension> {
		configure()
		val files = pluginsDir.listFiles()
			?.filter { it.isFile && it.extension.equals("cs3", ignoreCase = true) }
			?.sortedBy { it.name.lowercase() }
			.orEmpty()
		val active = files.mapTo(HashSet()) { it.absolutePath }
		loaded.keys.filter { it !in active }.forEach(::unload)
		errors.keys.retainAll(files.mapTo(HashSet()) { it.name })

		val extensions = ArrayList<AnimeExtension>()
		for (file in files) {
			val plugin = runCatching { load(file) }
				.onFailure { error ->
					Log.e(TAG, "Failed to load ${file.name}", error)
					errors[file.name] = describe(error)
				}
				.getOrNull() ?: continue
			errors.remove(file.name)
			if (plugin.providers.isEmpty()) continue
			val pkg = packageOf(file)
			extensions += AnimeExtension(
				pkg = pkg,
				name = plugin.manifest.name?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
				versionName = "cs3-${plugin.manifest.version}",
				versionCode = plugin.manifest.version.toLong(),
				nsfw = plugin.providers.all { TvType.NSFW in it.supportedTypes },
				privateApk = file.absolutePath,
				sources = plugin.providers.map { CloudstreamAnimeSource(it, pkg, this) },
				providerTypes = plugin.providers.map { api -> api.supportedTypes.mapTo(HashSet()) { it.name } },
			)
		}
		publishInstalled(files)
		return extensions
	}

	private fun configure() {
		if (configured) return
		synchronized(this) {
			if (configured) return
			val client = httpClient.get()
			setContext(WeakReference(context as Any))
			CloudStreamApp.context = context
			val userAgent = com.lagradost.cloudstream3.USER_AGENT
			CloudstreamRequestScope.userAgent = userAgent
			app.baseClient = client.newBuilder()
				.apply { interceptors().add(0, CloudstreamRequestScope.interceptor()) }
				.build()
			app.defaultHeaders = mapOf("User-Agent" to userAgent)
			// As the official app does at start-up: the library's YouTube extractor and YouTube plugins need it.
			runCatching { NewPipe.init(CloudstreamNewPipeDownloader(client)) }
				.onFailure { Log.w(TAG, "NewPipe init failed", it) }
			Globals.layoutProvider = { Globals.PHONE }
			CloudstreamPluginGuard.install(pluginOf = ::pluginBehind, onRestartRequested = {})
			configured = true
		}
	}

	private fun pluginHost(): Context {
		val activity = CommonActivity.INSTANCE.activity
		return activity?.takeUnless { it.isFinishing || it.isDestroyed } ?: context
	}

	private fun load(file: File): LoadedPlugin {
		val signature = "${file.length()}:${file.lastModified()}"
		loaded[file.absolutePath]?.takeIf { it.signature == signature }?.let { return it }
		unload(file.absolutePath)

		val manifest = readManifest(file)
		val prepared = prepareArchive(file, signature)
		val loader = DexClassLoader(prepared.absolutePath, dexDir.absolutePath, null, context.classLoader)
		val plugin = loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance() as BasePlugin
		plugin.filename = prepared.absolutePath
		val providers: List<MainAPI>
		try {
			if (manifest.requiresResources && plugin is Plugin) {
				plugin.resources = loadPluginResources(prepared)
			}
			if (plugin is Plugin) plugin.load(pluginHost()) else plugin.load()
			providers = synchronized(APIHolder.allProviders) {
				APIHolder.allProviders.filter { it.sourcePlugin == plugin.filename }
			}.also { apis -> apis.forEach(MainAPI::init) }
		} catch (error: Throwable) {
			// A plugin can register providers/extractors before throwing from load() or init().
			removeRegistrations(plugin.filename)
			runCatching { plugin.beforeUnload() }
			prepared.setWritable(true)
			prepared.delete()
			throw error
		}
		val extractors = synchronized(extractorApis) { extractorApis.filter { it.sourcePlugin == plugin.filename } }
		return LoadedPlugin(file, signature, plugin, loader, prepared, providers, extractors, manifest).also {
			loaded[file.absolutePath] = it
			Log.i(TAG, "Loaded ${file.name}: providers=${providers.size} extractors=${extractors.size}")
		}
	}

	private fun unload(path: String) {
		val plugin = loaded.remove(path) ?: return
		runCatching { plugin.plugin.beforeUnload() }
			.onFailure { Log.w(TAG, "beforeUnload failed for ${plugin.file.name}", it) }
		removeRegistrations(plugin.plugin.filename)
		plugin.preparedFile.setWritable(true)
		plugin.preparedFile.delete()
	}

	private fun removeRegistrations(filename: String?) {
		if (filename == null) return
		synchronized(APIHolder.allProviders) {
			APIHolder.allProviders.filter { it.sourcePlugin == filename }.forEach(APIHolder::removePluginMapping)
			APIHolder.allProviders.removeIf { it.sourcePlugin == filename }
		}
		synchronized(extractorApis) { extractorApis.removeIf { it.sourcePlugin == filename } }
		synchronized(VideoClickActionHolder.allVideoClickActions) {
			VideoClickActionHolder.allVideoClickActions.removeIf { it.sourcePlugin == filename }
		}
	}

	/** The archive of the loaded plugin whose code is on [error]'s stack, if any. */
	private fun pluginBehind(error: Throwable): String? {
		val plugins = loaded.values.toList()
		if (plugins.isEmpty()) return null
		generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).flatMap { it.stackTrace.asSequence() }
			.map(StackTraceElement::getClassName)
			.distinct()
			.forEach { className ->
				plugins.firstOrNull { plugin ->
					runCatching { plugin.classLoader.loadClass(className).classLoader === plugin.classLoader }
						.getOrDefault(false)
				}?.let { return it.file.name }
			}
		return null
	}

	private fun readManifest(file: File): Manifest {
		ZipFile(file).use { zip ->
			val entry = requireNotNull(zip.getEntry("manifest.json")) { "Not a Cloudstream plugin: no manifest.json" }
			val json = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
			val className = json.optString("pluginClassName").takeIf { it.isNotBlank() }
				?: error("Not a Cloudstream plugin: manifest has no pluginClassName")
			return Manifest(
				pluginClassName = className,
				name = json.optString("name").takeIf { it.isNotBlank() },
				version = json.optInt("version", 0),
				requiresResources = json.optBoolean("requiresResources", false),
			)
		}
	}

	/** Android refuses writable dex files, so the plugin is loaded from a read-only copy. */
	private fun prepareArchive(source: File, signature: String): File {
		val name = source.nameWithoutExtension
		val prepared = File(preparedDir, "$name-${"%08x".format(signature.hashCode())}.cs3")
		val stale = Regex("${Regex.escape(name)}-[0-9a-f]{8}\\.cs3")
		preparedDir.listFiles()?.filter { stale.matches(it.name) && it.name != prepared.name }?.forEach {
			it.setWritable(true)
			it.delete()
		}
		if (prepared.exists()) prepared.setWritable(true)
		source.copyTo(prepared, overwrite = true)
		if (!prepared.setWritable(false, false) && !prepared.setReadOnly()) {
			error("Unable to mark the plugin read-only: ${prepared.absolutePath}")
		}
		return prepared
	}

	@Suppress("DEPRECATION")
	private fun loadPluginResources(pluginFile: File): Resources {
		val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
		val cookie = AssetManager::class.java
			.getMethod("addAssetPath", String::class.java)
			.invoke(assets, pluginFile.absolutePath) as Int
		check(cookie != 0) { "Unable to add plugin resources: ${pluginFile.absolutePath}" }
		return Resources(assets, context.resources.displayMetrics, context.resources.configuration)
	}

	private fun publishInstalled(files: List<File> = listFiles()) {
		_installed.value = files.map { file ->
			val plugin = loaded[file.absolutePath]
			CloudstreamInstalledPlugin(
				internalName = file.nameWithoutExtension,
				displayName = plugin?.manifest?.name?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
				version = plugin?.manifest?.version ?: 0,
				providers = plugin?.providers?.map { it.name }.orEmpty(),
				error = errors[file.name],
			)
		}
	}

	private fun listFiles(): List<File> = pluginsDir.listFiles()
		?.filter { it.isFile && it.extension.equals("cs3", ignoreCase = true) }
		?.sortedBy { it.name.lowercase() }
		.orEmpty()

	private fun describe(error: Throwable): String = generateSequence(error) { it.cause }
		.take(MAX_CAUSE_DEPTH)
		.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

	// --- install / uninstall ---------------------------------------------------------------------

	/** Version of the installed plugin [internalName], or null when it is not installed or did not load. */
	fun installedVersion(internalName: String): Int? =
		loaded[File(pluginsDir, "$internalName.cs3").absolutePath]?.manifest?.version

	/** Downloads a plugin and activates it. Throws, leaving the previous version in place, when it does not load. */
	suspend fun installFromUrl(url: String, internalName: String?) = withContext(Dispatchers.IO) {
		val name = sanitize(internalName ?: url.substringBefore('?').substringAfterLast('/').removeSuffix(".cs3"))
		val request = Request.Builder().url(url).build()
		httpClient.get().newCall(request).execute().use { response ->
			check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
			response.body.byteStream().use { installFromStream(it, name) }
		}
	}

	/** Activates a plugin read from [input]; [name] becomes its file name. */
	suspend fun installFromStream(input: InputStream, name: String) = withContext(Dispatchers.IO) {
		val safeName = sanitize(name.removeSuffix(".cs3"))
		val tmp = File(pluginsDir, "$safeName.cs3.part")
		try {
			tmp.outputStream().use { out -> input.copyTo(out) }
			readManifest(tmp) // fails early on anything that is not a plugin
			activate(tmp, File(pluginsDir, "$safeName.cs3"))
		} finally {
			tmp.delete()
		}
	}

	@Synchronized
	private fun activate(tmp: File, target: File) {
		configure()
		val backup = target.takeIf { it.exists() }?.let { File(it.path + ".bak").also { bak -> it.copyTo(bak, true) } }
		unload(target.absolutePath)
		tmp.copyTo(target, overwrite = true)
		try {
			load(target)
			errors.remove(target.name)
		} catch (error: Throwable) {
			unload(target.absolutePath)
			if (backup != null) {
				backup.copyTo(target, overwrite = true)
				runCatching { load(target) }
			} else {
				target.delete()
			}
			publishInstalled()
			throw error
		} finally {
			backup?.delete()
		}
		publishInstalled()
	}

	/** Removes a plugin and its archive. */
	@Synchronized
	fun uninstall(internalName: String) {
		val file = File(pluginsDir, "$internalName.cs3")
		unload(file.absolutePath)
		errors.remove(file.name)
		file.delete()
		publishInstalled()
	}

	// --- repositories ----------------------------------------------------------------------------

	fun addRepo(input: String): String {
		val url = normalizeRepoUrl(input)
		if (url !in _repos.value) saveRepos(_repos.value + url)
		return url
	}

	fun removeRepo(url: String) = saveRepos(_repos.value - url)

	/** Every plugin the added repositories offer; a repository that fails is skipped and reported in the second part. */
	suspend fun fetchAvailable(): Pair<List<CloudstreamRepoPlugin>, Map<String, String>> = withContext(Dispatchers.IO) {
		val plugins = ArrayList<CloudstreamRepoPlugin>()
		val failures = LinkedHashMap<String, String>()
		for (repo in _repos.value) {
			runCatching { fetchRepo(repo) }
				.onSuccess { plugins += it }
				.onFailure { failures[repo] = it.message ?: it.javaClass.simpleName }
		}
		plugins.distinctBy { it.internalName to it.repoUrl } to failures
	}

	private fun fetchRepo(repoUrl: String): List<CloudstreamRepoPlugin> {
		val text = download(repoUrl).trim()
		if (text.startsWith("[")) return parsePluginList(JSONArray(text), repoUrl)
		val lists = JSONObject(text).optJSONArray("pluginLists")
			?: error("Not a Cloudstream repository: no pluginLists")
		return (0 until lists.length()).flatMap { index ->
			parsePluginList(JSONArray(download(lists.getString(index)).trim()), repoUrl)
		}
	}

	private fun parsePluginList(array: JSONArray, repoUrl: String): List<CloudstreamRepoPlugin> =
		(0 until array.length()).mapNotNull { index ->
			val json = array.optJSONObject(index) ?: return@mapNotNull null
			val url = json.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val name = json.optString("name").ifBlank { json.optString("internalName") }
			val types = json.optJSONArray("tvTypes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
			CloudstreamRepoPlugin(
				internalName = sanitize(json.optString("internalName").ifBlank { name }),
				name = name.ifBlank { url.substringAfterLast('/') },
				url = url,
				version = json.optInt("version", 0),
				language = json.optString("language").takeIf { it.isNotBlank() && it != "null" },
				description = json.optString("description").takeIf { it.isNotBlank() && it != "null" },
				types = types,
				iconUrl = json.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" }
					?.replace("%size%", "128"),
				status = json.optInt("status", 1),
				repoUrl = repoUrl,
			)
		}

	private fun download(url: String): String {
		httpClient.get().newCall(Request.Builder().url(url).build()).execute().use { response ->
			check(response.isSuccessful) { "HTTP ${response.code} for $url" }
			return response.body.string()
		}
	}

	private fun normalizeRepoUrl(input: String): String {
		val value = input.trim()
		return when {
			value.startsWith("cloudstreamrepo://", ignoreCase = true) -> "https://" + value.substringAfter("://")
			value.startsWith("http://") || value.startsWith("https://") -> value
			// Cloudstream's short codes resolve through cutt.ly
			Regex("[A-Za-z0-9_-]+").matches(value) -> "https://cutt.ly/$value"
			else -> throw IllegalArgumentException("Not a repository URL: $value")
		}
	}

	private fun readRepos(): List<String> = runCatching {
		val array = JSONArray(prefs.getString(KEY_REPOS, "[]"))
		(0 until array.length()).map { array.getString(it) }
	}.getOrDefault(emptyList())

	private fun saveRepos(repos: List<String>) {
		prefs.edit().putString(KEY_REPOS, JSONArray(repos).toString()).apply()
		_repos.value = repos
	}

	private fun sanitize(name: String): String =
		name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifBlank { "plugin" }

	companion object {

		private const val TAG = "CloudstreamRuntime"
		private const val KEY_REPOS = "repos"
		private const val MAX_CAUSE_DEPTH = 8

		/** Package name of the [AnimeExtension] made for the plugin archive [file]. */
		fun packageOf(file: File): String = PACKAGE_PREFIX + file.nameWithoutExtension

		const val PACKAGE_PREFIX = "cloudstream."

		fun isCloudstreamPackage(pkg: String): Boolean = pkg.startsWith(PACKAGE_PREFIX)
	}
}
