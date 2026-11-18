package eu.kanade.tachiyomi.util.system

import dalvik.system.PathClassLoader
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.Enumeration

class ChildFirstPathClassLoader(
	dexPath: String,
	librarySearchPath: String?,
	parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

	private val systemClassLoader: ClassLoader? = getSystemClassLoader()

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		var loadedClass = findLoadedClass(name)

		// Host-owned ABI (animesource, network, okhttp, kotlin, ...) must resolve to the host's single copy.
		// Pure child-first lets a duplicate inside the extension APK win, which produces LinkageErrors
		// (two loaders, same class). Everything else stays child-first. Mirrors Kototoro's loader policy.
		if (loadedClass == null && shouldDelegateToParent(name)) {
			loadedClass = runCatching { parent.loadClass(name) }.getOrNull()
		}

		val systemLoader = systemClassLoader
		if (loadedClass == null && systemLoader != null) {
			loadedClass = runCatching {
				systemLoader.loadClass(name)
			}.getOrNull()
		}

		if (loadedClass == null) {
			loadedClass = runCatching {
				findClass(name)
			}.getOrElse {
				super.loadClass(name, resolve)
			}
		}

		if (resolve) {
			resolveClass(loadedClass)
		}

		return loadedClass
	}

	private fun shouldDelegateToParent(name: String): Boolean {
		// D8 desugaring artifacts travel inside the extension APK and have no host equivalent.
		if (name.endsWith("\$-CC") || name.contains("\$DefaultImpls")) return false
		return PARENT_PACKAGES.any(name::startsWith)
	}

	override fun getResource(name: String): URL? {
		return systemClassLoader?.getResource(name)
			?: findResource(name)
			?: super.getResource(name)
	}

	override fun getResources(name: String): Enumeration<URL> {
		val systemUrls = systemClassLoader?.getResources(name)
		val localUrls = findResources(name)
		val parentUrls = parent?.getResources(name)
		val urls = buildList {
			while (systemUrls?.hasMoreElements() == true) {
				add(systemUrls.nextElement())
			}
			while (localUrls?.hasMoreElements() == true) {
				add(localUrls.nextElement())
			}
			while (parentUrls?.hasMoreElements() == true) {
				add(parentUrls.nextElement())
			}
		}

		return object : Enumeration<URL> {
			private val iterator = urls.iterator()

			override fun hasMoreElements(): Boolean = iterator.hasNext()

			override fun nextElement(): URL = iterator.next()
		}
	}

	override fun getResourceAsStream(name: String): InputStream? {
		return try {
			getResource(name)?.openStream()
		} catch (_: IOException) {
			null
		}
	}

	private companion object {
		val PARENT_PACKAGES = arrayOf(
			"java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.",
			"org.json.", "org.jsoup.", "okhttp3.", "okio.", "rx.",
			"eu.kanade.tachiyomi.animesource.", "eu.kanade.tachiyomi.source.",
			"eu.kanade.tachiyomi.network.", "eu.kanade.tachiyomi.util.",
			"uy.kohesive.injekt.",
		)
	}
}
