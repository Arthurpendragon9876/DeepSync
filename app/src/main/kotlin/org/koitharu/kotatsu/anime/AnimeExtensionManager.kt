package org.koitharu.kotatsu.anime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.model.ContentWarning
import org.koitharu.kotatsu.mihon.compat.KotoInjektBridge
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** One extension APK that was loaded into the anime runtime. */
data class AnimeExtension(
	val pkg: String,
	val name: String,
	val versionName: String,
	val versionCode: Long,
	val nsfw: Boolean,
	/** Absolute path when the APK is app-private (installed from a repo inside the app). */
	val privateApk: String?,
	val sources: List<AnimeSource>,
) {

	/** The one language its sources share, "all" when they differ; falls back to the package name. */
	val lang: String
		get() {
			val langs = sources.map { it.lang }.filter { it.isNotBlank() }.toSet()
			return when (langs.size) {
				0 -> pkg.substringAfter("animeextension.", "").substringBefore('.').ifEmpty { "all" }
				1 -> langs.first()
				else -> "all"
			}
		}

	val contentWarning: ContentWarning
		get() = if (nsfw) ContentWarning.NSFW else ContentWarning.SAFE
}

/**
 * Loads Aniyomi anime extensions and keeps the registry of their sources.
 *
 * Two origins are supported:
 *  - regular Android packages carrying the `tachiyomi.animeextension` feature (e.g. installed by
 *    Aniyomi or via the system installer);
 *  - APKs downloaded by this app into `filesDir/anime-ext/` (no system installer prompt).
 *
 * Adapted from Zangetsu's AniyomiExtensionLoader (Apache-2.0 portions retain upstream headers).
 */
@Singleton
class AnimeExtensionManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val injektBridge: Lazy<KotoInjektBridge>,
) {

	private val _extensions = MutableStateFlow<List<AnimeExtension>>(emptyList())
	val extensions: StateFlow<List<AnimeExtension>> = _extensions.asStateFlow()

	private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
	val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

	private val _mangaSources = MutableStateFlow<List<AnimeMangaSource>>(emptyList())

	/** Every anime source as a [org.koitharu.kotatsu.parsers.model.MangaSource]; feeds Home/Explore. */
	val mangaSources: StateFlow<List<AnimeMangaSource>> = _mangaSources.asStateFlow()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val initialized = AtomicBoolean(false)

	init {
		activeInstance = this
	}

	/** Starts the first extension scan; later calls are no-ops. Safe from any thread. */
	fun initialize() {
		if (initialized.compareAndSet(false, true)) scope.launch { reload() }
	}

	fun getMangaSources(): List<AnimeMangaSource> = _mangaSources.value

	private val mutex = Mutex()
	private val prefs = context.getSharedPreferences("deepsync_anime", Context.MODE_PRIVATE)

	private val _hidden = MutableStateFlow(prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet())

	/** Package names of extensions hidden from Explore/Home (still installed and listed here). */
	val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

	fun setHidden(pkg: String, hidden: Boolean) {
		val updated = if (hidden) _hidden.value + pkg else _hidden.value - pkg
		prefs.edit().putStringSet(KEY_HIDDEN, updated).apply()
		_hidden.value = updated
		publishSources()
	}

	private fun publishSources() {
		val hiddenPkgs = _hidden.value
		_mangaSources.value = _extensions.value.filter { it.pkg !in hiddenPkgs }.flatMap { ext ->
			ext.sources.filterIsInstance<AnimeCatalogueSource>()
				.map { AnimeMangaSource(it, ext.pkg, ext.name, ext.nsfw) }
		}
	}
	private val privateDir: File get() = File(context.filesDir, "anime-ext").apply { mkdirs() }

	/**
	 * Repository URLs the old, separate anime manager kept. Repositories now live in the shared extension
	 * store registry; these are only read once to be moved there, and cleared when that succeeds.
	 */
	var legacyRepoUrls: List<String>
		get() = prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty().toList().sorted()
		set(value) = prefs.edit().putStringSet(KEY_REPOS, value.toSet()).apply()

	/** The store an extension was installed from, so updates prefer it. Null for sideloaded or older installs. */
	fun ownerOf(pkg: String): String? = prefs.getString("$KEY_OWNER_PREFIX$pkg", null)

	private fun setOwner(pkg: String, storeId: String?) {
		prefs.edit().apply {
			if (storeId == null) remove("$KEY_OWNER_PREFIX$pkg") else putString("$KEY_OWNER_PREFIX$pkg", storeId)
		}.apply()
	}

	fun isInstalled(pkg: String): Boolean = _extensions.value.any { it.pkg == pkg }

	/** True when the extension is an APK this app downloaded itself, so it can be removed without a system prompt. */
	fun isPrivate(pkg: String): Boolean = _extensions.value.firstOrNull { it.pkg == pkg }?.privateApk != null

	/** Stored `ANIME_<id>` name of the first browsable source an installed extension provides. */
	fun firstSourceName(pkg: String): String? = _extensions.value.firstOrNull { it.pkg == pkg }
		?.sources?.filterIsInstance<AnimeCatalogueSource>()?.firstOrNull()?.let { "ANIME_${it.id}" }

	fun allSources(): List<AnimeSource> = _extensions.value.flatMap { it.sources }

	fun catalogueSources(): List<AnimeCatalogueSource> = allSources().filterIsInstance<AnimeCatalogueSource>()

	fun getSource(id: Long): AnimeSource? = allSources().firstOrNull { it.id == id }

	/** Rescans installed packages and the private APK folder. Safe to call repeatedly. */
	suspend fun reload() = mutex.withLock {
		withContext(Dispatchers.IO) {
			injektBridge.get().initialize()
			val errors = HashMap<String, String>()
			val loaded = LinkedHashMap<String, AnimeExtension>()

			// Private APKs first so an in-app install wins over a stale system copy.
			privateDir.listFiles { f -> f.extension == "apk" }?.forEach { apk ->
				runCatching { loadPrivate(apk) }
					.onSuccess { loaded[it.pkg] = it }
					.onFailure {
						Log.e(TAG, "load failed: ${apk.name}", it)
						errors[apk.name] = rootMessage(it)
					}
			}
			installedPackages().forEach { info ->
				if (loaded.containsKey(info.packageName)) return@forEach
				runCatching { loadInstalled(info) }
					.onSuccess { loaded[it.pkg] = it }
					.onFailure {
						Log.e(TAG, "load failed: ${info.packageName}", it)
						errors[info.packageName] = rootMessage(it)
					}
			}
			_errors.value = errors
			_extensions.value = loaded.values.toList()
			publishSources()
		}
	}

	suspend fun uninstallPrivate(pkg: String) {
		withContext(Dispatchers.IO) {
			val file = File(privateDir, "$pkg.apk")
			// A loaded copy is read-only (Android refuses writable dex files).
			file.setWritable(true)
			file.delete()
			setOwner(pkg, null)
		}
		reload()
	}

	/**
	 * Downloads an anime extension APK into private storage and loads it; no system installer prompt.
	 * [url] comes from the shared extension store catalog, [storeId] is remembered as the owner.
	 */
	suspend fun installFromUrl(pkg: String, url: String, storeId: String?) {
		withContext(Dispatchers.IO) {
			injektBridge.get().initialize()
			val tmp = File(privateDir, "$pkg.apk.part")
			val target = File(privateDir, "$pkg.apk")
			try {
				Injekt.get<NetworkHelper>().client.newCall(GET(url)).execute().use { resp ->
					check(resp.isSuccessful) { "Download failed: HTTP ${resp.code}" }
					tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
				}
				// A previously loaded copy is read-only (Android refuses writable dex files).
				if (target.exists()) {
					target.setWritable(true)
					target.delete()
				}
				check(tmp.renameTo(target)) { "Could not move APK into place" }
				target.setReadOnly()
			} finally {
				tmp.delete()
			}
			setOwner(pkg, storeId)
		}
		reload()
	}

	// ---------------------------------------------------------------------------------------------

	@Suppress("DEPRECATION")
	private fun installedPackages(): List<PackageInfo> {
		val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
		val pm = context.packageManager
		val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
		} else {
			pm.getInstalledPackages(flags)
		}
		return all.filter { p -> p.reqFeatures?.any { it.name == FEATURE } == true }
	}

	private fun loadInstalled(info: PackageInfo): AnimeExtension {
		val appInfo = info.applicationInfo ?: error("No applicationInfo")
		val loader = ChildFirstPathClassLoader(appInfo.sourceDir, appInfo.nativeLibraryDir, context.classLoader)
		return build(info, appInfo, loader, privateApk = null)
	}

	@Suppress("DEPRECATION")
	private fun loadPrivate(apk: File): AnimeExtension {
		val info = context.packageManager.getPackageArchiveInfo(
			apk.absolutePath,
			PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS,
		) ?: error("Not an APK: ${apk.name}")
		require(info.reqFeatures?.any { it.name == FEATURE } == true) {
			"Not an Aniyomi anime extension (missing $FEATURE)"
		}
		val appInfo = info.applicationInfo ?: error("No applicationInfo")
		appInfo.sourceDir = apk.absolutePath
		appInfo.publicSourceDir = apk.absolutePath
		apk.setReadOnly()
		val loader = ChildFirstPathClassLoader(apk.absolutePath, null, context.classLoader)
		return build(info, appInfo, loader, privateApk = apk.absolutePath)
	}

	@Suppress("DEPRECATION")
	private fun build(
		info: PackageInfo,
		appInfo: ApplicationInfo,
		loader: ClassLoader,
		privateApk: String?,
	): AnimeExtension {
		val versionName = info.versionName ?: error("Missing versionName")
		val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			info.longVersionCode
		} else {
			info.versionCode.toLong()
		}
		val lib = versionName.substringBeforeLast('.').toDoubleOrNull()
			?: error("Cannot parse lib version from \"$versionName\"")
		require(lib in LIB_MIN..LIB_MAX) { "Unsupported extensions-lib $lib (supported $LIB_MIN..$LIB_MAX)" }

		val meta = appInfo.metaData
		val classList = meta?.getString("$FEATURE.class").orEmpty().trim()
		require(classList.isNotBlank()) { "No source classes declared" }
		val nsfw = listOf("tachiyomi.animeextensionn.nsfw", "$FEATURE.nsfw")
			.firstOrNull { meta?.containsKey(it) == true }
			?.let { meta?.getInt(it, 0) == 1 } ?: false

		val pkg = info.packageName
		val sources = classList.split(";").map { it.trim() }.filter { it.isNotBlank() }.flatMap { raw ->
			val className = if (raw.startsWith(".")) pkg + raw else raw
			when (val instance = loader.loadClass(className).getDeclaredConstructor().newInstance()) {
				is AnimeSource -> listOf(instance)
				is AnimeSourceFactory -> instance.createSources()
				else -> emptyList()
			}
		}
		require(sources.isNotEmpty()) { "Extension produced no sources" }
		val label = runCatching { appInfo.loadLabel(context.packageManager).toString() }.getOrDefault(pkg)
		return AnimeExtension(
			pkg = pkg,
			name = label.removePrefix("Aniyomi: "),
			versionName = versionName,
			versionCode = versionCode,
			nsfw = nsfw,
			privateApk = privateApk,
			sources = sources,
		)
	}

	private fun rootMessage(t: Throwable): String {
		val root = generateSequence(t) { it.cause }.last()
		return "${root::class.java.simpleName}: ${root.message}"
	}

	companion object {

		@Volatile
		private var activeInstance: AnimeExtensionManager? = null

		/** Resolves a stored `ANIME_<id>` source name without DI, mirroring the Mihon/LN lookups. */
		fun getByName(name: String): AnimeMangaSource? {
			val id = name.removePrefix("ANIME_").toLongOrNull() ?: return null
			return activeInstance?.getMangaSources()?.firstOrNull { it.sourceId == id }
		}

		private const val TAG = "AnimeExtensionManager"
		private const val KEY_REPOS = "repos"
		private const val KEY_HIDDEN = "hidden_pkgs"
		private const val KEY_OWNER_PREFIX = "owner_"

		private const val FEATURE = "tachiyomi.animeextension"
		private const val LIB_MIN = 12.0
		private const val LIB_MAX = 17.0
	}
}
