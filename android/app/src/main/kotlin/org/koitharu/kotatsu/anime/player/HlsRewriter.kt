package org.koitharu.kotatsu.anime.player

/**
 * Pure URI-rewriting engine for HLS playlists, used by [AnimeStreamProxy] so segments, keys, init
 * segments and alternate audio/subtitle renditions all route back through the proxy.
 * Ported from Zangetsu (Apache-2.0 / GPL-3.0).
 */
object HlsRewriter {

	fun rewrite(body: String, playlistUrl: String, resolveUri: (String) -> String): String {
		val stripped = playlistUrl.substringBefore("?")
		val baseDir = stripped.let { s ->
			val idx = s.lastIndexOf('/')
			if (idx >= 0) s.substring(0, idx + 1) else "$s/"
		}
		val schemeHost = playlistUrl.let { u ->
			val s = u.indexOf("://")
			if (s < 0) return@let ""
			val slash = u.indexOf('/', s + 3)
			if (slash < 0) u else u.substring(0, slash)
		}

		fun toAbsolute(uri: String): String = when {
			uri.startsWith("http://") || uri.startsWith("https://") -> uri
			uri.startsWith("//") -> "https:$uri"
			uri.startsWith("/") -> "$schemeHost$uri"
			else -> "$baseDir$uri"
		}

		val uriTagPrefix = Regex("""^#EXT-X-(KEY|MEDIA|MAP|I-FRAME-STREAM-INF|SESSION-KEY):""")
		val uriAttr = Regex("""URI="([^"]+)"""")

		return body.lines().joinToString("\n") { line ->
			when {
				line.isBlank() -> line
				uriTagPrefix.containsMatchIn(line) -> uriAttr.replace(line) { m ->
					"""URI="${resolveUri(toAbsolute(m.groupValues[1]))}""""
				}
				line.startsWith("#") -> line
				else -> resolveUri(toAbsolute(line.trim()))
			}
		}
	}
}
