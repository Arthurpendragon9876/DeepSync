package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.ListHeader

class ExtensionCatalogPagesTest {

	@Test
	fun `available stays first and stores retain their configured order`() {
		val stores = listOf(
			store("second", "Second"),
			store("first", "First"),
		)

		assertEquals(
			listOf(
				ExtensionCatalogPage.Available,
				ExtensionCatalogPage.Store("second", "Second"),
				ExtensionCatalogPage.Store("first", "First"),
			),
			buildExtensionCatalogPages(stores),
		)
	}

	@Test
	fun `installed page always exists without stores or extensions`() {
		assertEquals(
			listOf(ExtensionCatalogPage.Available),
			buildExtensionCatalogPages(emptyList()),
		)
	}

	@Test
	fun `available page shows updates before every installed extension`() {
		val update = SourceCatalogItem.Extension(
			packageName = "example.extension",
			title = "Example",
			subtitle = "English",
			action = SourceCatalogItem.Extension.Action.UPDATE,
		)
		val installed = update.copy(action = SourceCatalogItem.Extension.Action.UNINSTALL)

		assertEquals(
			listOf(
				ListHeader(R.string.updates_available),
				update,
				ButtonFooter(R.string.update_all),
				ListHeader(R.string.installed),
				installed,
			),
			buildAvailablePageItems(listOf(update), listOf(installed), isPrivateMode = false),
		)
	}

	@Test
	fun `no store warning appears before installed extensions`() {
		val installed = SourceCatalogItem.Extension(
			packageName = "example.extension",
			title = "Example",
			subtitle = "English",
			action = SourceCatalogItem.Extension.Action.UNINSTALL,
		)

		assertEquals(
			listOf(
				SourceCatalogItem.Hint(
					R.drawable.ic_empty_feed,
					R.string.no_extension_store_found,
					R.string.no_extension_store_found_summary,
				),
				ListHeader(R.string.installed),
				installed,
			),
			buildAvailablePageItems(
				updates = emptyList(),
				installed = listOf(installed),
				isPrivateMode = false,
				hasStores = false,
			),
		)
	}

	@Test
	fun `divider appears only before the first store tab`() {
		assertFalse(shouldShowStoreTabDivider(0, ExtensionCatalogPage.Available))
		assertTrue(shouldShowStoreTabDivider(1, ExtensionCatalogPage.Store("first", "First")))
		assertFalse(shouldShowStoreTabDivider(2, ExtensionCatalogPage.Store("second", "Second")))
	}

	@Test
	fun `store tabs exclude extensions already installed from that store`() {
		assertFalse(isStoreInstallCandidate(isInstalled = true, ownerStoreId = "current", currentStoreId = "current"))
		assertTrue(isStoreInstallCandidate(isInstalled = true, ownerStoreId = "other", currentStoreId = "current"))
		assertTrue(isStoreInstallCandidate(isInstalled = false, ownerStoreId = null, currentStoreId = "current"))
	}

	@Test
	fun `updates remain visible while a store refresh is checking`() {
		assertTrue(canUseStoreCatalogForUpdates(StoreHealth.AVAILABLE))
		assertTrue(canUseStoreCatalogForUpdates(StoreHealth.CHECKING))
		assertFalse(canUseStoreCatalogForUpdates(StoreHealth.UNAVAILABLE))
	}

	@Test
	fun `catalog filters are hidden for the completely empty state`() {
		assertTrue(shouldShowCatalogFilters(listOf(ExtensionCatalogPage.Available)))
	}

	@Test
	fun `search ignores spacing and punctuation on both sides`() {
		assertTrue(matchesExtensionQuery("manga fire", "MangaFire"))
		assertTrue(matchesExtensionQuery("mangafire", "Manga Fire"))
		assertTrue(matchesExtensionQuery("mangafire", null, "eu.kanade.extension.manga-fire"))
		assertTrue(matchesExtensionQuery("  ", "Anything"))
		assertFalse(matchesExtensionQuery("mangadex", "MangaFire"))
	}

	@Test
	fun `novel extensions are recognised by flag, package prefix or js plugin`() {
		val manga = repoEntry(pkg = "eu.kanade.tachiyomi.extension.en.example", apk = "example.apk")
		val novelFlag = repoEntry(pkg = "com.example.novels", apk = "novels.apk", isNovel = true)
		val novelPrefix = repoEntry(
			pkg = "eu.kanade.tachiyomi.novelextension.en.allnovelfull",
			apk = "tsundoku-en.allnovelfull-v1.4.11.apk",
		)
		val plugin = repoEntry(pkg = "somePlugin", apk = "https://example.com/somePlugin.js")

		assertFalse(manga.isNovelExtension)
		assertTrue(novelFlag.isNovelExtension)
		assertTrue(novelPrefix.isNovelExtension)
		assertTrue(plugin.isNovelExtension)
		// Only real plugin code installs as a plugin; a novel APK installs like any other extension.
		assertFalse(novelPrefix.isLnPlugin)

		assertEquals(ExtensionStoreKind.MANGA, listOf(manga).extensionStoreKind())
		assertEquals(ExtensionStoreKind.NOVEL, listOf(novelPrefix, novelFlag, plugin).extensionStoreKind())
		assertEquals(ExtensionStoreKind.MIXED, listOf(manga, novelPrefix).extensionStoreKind())
	}

	@Test
	fun `recommendations only come from the current store catalog`() {
		val references = collectRecommendedExtensionRefs(
			externalSourceNames = listOf("MIHON_42:Known", "MIHON_99:Other"),
			installedSourceIds = emptySet(),
			installedPackages = emptySet(),
			repoSourceIndex = mapOf(42L to ("known.extension" to "Known")),
		)

		assertEquals(listOf(RecommendedExtensionRef("known.extension", "Known")), references)
	}

	@Test
	fun `adapter updates are deferred while recycler view is computing layout`() {
		var updated = false
		var deferredUpdate: (() -> Unit)? = null

		dispatchRecyclerAdapterUpdate(
			isComputingLayout = true,
			post = { deferredUpdate = it },
			update = { updated = true },
		)

		assertEquals(false, updated)
		deferredUpdate?.invoke()
		assertEquals(true, updated)
	}

	@Test
	fun `cloudstream repositories follow the stores as tabs`() {
		val pages = buildExtensionCatalogPages(
			listOf(store("first", "First")),
			listOf("https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/master/repo.json"),
		)

		assertEquals(
			listOf(
				ExtensionCatalogPage.Available,
				ExtensionCatalogPage.Store("first", "First"),
				ExtensionCatalogPage.Cloudstream(
					"https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/master/repo.json",
					"phisher98",
				),
			),
			pages,
		)
		assertTrue(pages.last().id.startsWith(CLOUDSTREAM_PAGE_PREFIX))
		assertTrue(shouldShowStoreTabDivider(1, pages.last()))
	}

	@Test
	fun `cloudstream repository label falls back to the host`() {
		assertEquals("example.com", cloudstreamRepoLabel("https://www.example.com/repo.json"))
		assertEquals("not a url", cloudstreamRepoLabel("not a url"))
	}

	@Test
	fun `engine filter cycles through every engine`() {
		assertEquals(StreamingEngine.ANIYOMI, StreamingEngine.ALL.next())
		assertEquals(StreamingEngine.CLOUDSTREAM, StreamingEngine.ANIYOMI.next())
		assertEquals(StreamingEngine.ALL, StreamingEngine.CLOUDSTREAM.next())
	}

	@Test
	fun `cloudstream update picks the newest version ahead of the installed one`() {
		val available = listOf(plugin("Foo", 3), plugin("Foo", 5), plugin("Bar", 9))

		assertEquals(5, cloudstreamUpdateFor(available, "Foo", 3)?.version)
		assertEquals(null, cloudstreamUpdateFor(available, "Foo", 5))
		assertEquals(null, cloudstreamUpdateFor(available, "Missing", 0))
	}

	private fun plugin(name: String, version: Int) = org.koitharu.kotatsu.cloudstream.CloudstreamRepoPlugin(
		internalName = name,
		name = name,
		url = "https://example.com/$name-$version.cs3",
		version = version,
		language = "en",
		description = null,
		types = emptyList(),
		iconUrl = null,
		status = 1,
		repoUrl = "https://example.com/repo.json",
	)

	private fun repoEntry(pkg: String, apk: String, isNovel: Boolean = false) = ExternalExtensionRepoEntry(
		name = pkg.substringAfterLast('.'),
		packageName = pkg,
		apkName = apk,
		versionCode = 1L,
		versionName = "1.4.1",
		isNovel = isNovel,
	)

	private fun store(id: String, name: String) = ExtensionStoreRecord(
		id = id,
		indexUrl = "https://example.com/$id/index.min.json",
		name = name,
	)
}
