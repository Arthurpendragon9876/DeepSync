package org.koitharu.kotatsu.settings.sources.catalog

/**
 * What an extension provides. The catalog shows one kind at a time (Anime / Manga / Novel tabs); the
 * extension stores themselves are shared, so a single store can publish more than one kind.
 */
enum class ExtensionKind { ANIME, MANGA, NOVEL }

/** Package prefix every Aniyomi anime extension uses. */
const val ANIME_EXTENSION_PACKAGE_PREFIX = "eu.kanade.tachiyomi.animeextension"

private const val NOVEL_PKG_PREFIX = "eu.kanade.tachiyomi.novelextension"

/** Classifies an installed package, which has no store entry to ask. */
fun extensionKindOfPackage(packageName: String): ExtensionKind = when {
	packageName.startsWith(ANIME_EXTENSION_PACKAGE_PREFIX) -> ExtensionKind.ANIME
	packageName.startsWith(NOVEL_PKG_PREFIX) -> ExtensionKind.NOVEL
	else -> ExtensionKind.MANGA
}

val ExternalExtensionRepoEntry.isAnimeExtension: Boolean
	get() = packageName.startsWith(ANIME_EXTENSION_PACKAGE_PREFIX)

/** Anime is decided by package first: an Aniyomi index never sets the novel markers. */
val ExternalExtensionRepoEntry.kind: ExtensionKind
	get() = when {
		isAnimeExtension -> ExtensionKind.ANIME
		isNovelExtension -> ExtensionKind.NOVEL
		else -> ExtensionKind.MANGA
	}
