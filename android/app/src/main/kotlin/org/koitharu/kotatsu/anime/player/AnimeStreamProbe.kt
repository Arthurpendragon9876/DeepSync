package org.koitharu.kotatsu.anime.player

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.anime.AnimeStream
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class StreamKind { HLS, DASH, PROGRESSIVE, ERROR }

/**
 * What a stream URL really is. ExoPlayer decides HLS vs progressive from the URL's extension, but
 * extension-less URLs (`.../playlist?id=...`) make it try an MP4/MKV parser on a playlist and fail
 * with "None of the available extractors could read the stream". CloudStream avoids this because
 * every link carries an explicit type; here the type has to be worked out by looking at the bytes.
 */
class StreamProbe(
	val kind: StreamKind,
	/** HLS master playlist whose audio lives in a separate rendition (VLC 3 cannot merge those). */
	val separateAudio: Boolean = false,
	val note: String? = null,
	/** Human readable "what the server actually sent", shown when playback ultimately fails. */
	val detail: String? = null,
)

object AnimeStreamProbe {

	private const val PEEK_SIZE = 4096

	private val separateAudioMedia = Regex("""#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*URI="[^"]+"""", RegexOption.IGNORE_CASE)
	private val variantWithAudio = Regex("""#EXT-X-STREAM-INF:[^\r\n]*AUDIO="[^"]+"""", RegexOption.IGNORE_CASE)

	private class Peek(val code: Int, val contentType: String, val head: ByteArray)

	suspend fun probe(stream: AnimeStream): StreamProbe {
		val client = stream.client ?: Injekt.get<NetworkHelper>().client
		return probeUrl(client, stream.video.videoUrl, stream.video.headers)
	}

	suspend fun probeUrl(client: OkHttpClient, url: String, headers: Headers?): StreamProbe = withContext(Dispatchers.IO) {
		val path = url.substringBefore('?').lowercase()
		val byExtension = when {
			path.endsWith(".m3u8") || path.endsWith(".m3u") -> StreamKind.HLS
			path.endsWith(".mpd") -> StreamKind.DASH
			else -> StreamKind.PROGRESSIVE
		}
		try {
			var peek = peek(client, url, headers, withRange = true)
			// A few hosts reject the probing Range header outright; the player may still be fine.
			if (peek.code == 416 || peek.code == 400 || peek.code == 501) {
				peek = peek(client, url, headers, withRange = false)
			}
			if (peek.code == 404 || peek.code == 410) {
				return@withContext StreamProbe(
					StreamKind.ERROR,
					note = "The host answered HTTP ${peek.code} for this stream (link expired or removed).",
				)
			}
			if (peek.code !in 200..299) {
				// 403 & co: could be the probe itself being blocked. Let the player try, but remember why.
				return@withContext StreamProbe(byExtension, detail = describe(peek))
			}
			classify(client, url, headers, peek, byExtension)
		} catch (e: Exception) {
			// Probing is best-effort; fall back to what the URL looks like and let the player try.
			StreamProbe(byExtension)
		}
	}

	private fun peek(client: OkHttpClient, url: String, headers: Headers?, withRange: Boolean): Peek {
		val request = Request.Builder().url(url).apply {
			if (headers != null) headers(headers)
			if (withRange) header("Range", "bytes=0-${PEEK_SIZE - 1}")
		}.build()
		return client.newCall(request).execute().use { resp ->
			val buf = ByteArray(PEEK_SIZE)
			var n = 0
			if (resp.code in 200..299) {
				val input = resp.body.byteStream()
				while (n < buf.size) {
					val r = input.read(buf, n, buf.size - n)
					if (r < 0) break
					n += r
				}
			}
			Peek(resp.code, resp.header("Content-Type").orEmpty().lowercase(), buf.copyOf(n))
		}
	}

	private fun classify(
		client: OkHttpClient,
		url: String,
		headers: Headers?,
		peek: Peek,
		byExtension: StreamKind,
	): StreamProbe {
		val bytes = peek.head
		val ctype = peek.contentType
		val detail = describe(peek)
		if (bytes.isEmpty()) return StreamProbe(byExtension, detail = detail)

		// Skip a UTF-8 BOM (as raw bytes - decoding as Latin-1 would turn it into "ï»¿") and whitespace.
		var start = 0
		if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
		while (start < bytes.size && bytes[start].toInt().let { it == 0x20 || it == 0x09 || it == 0x0A || it == 0x0D }) start++
		val text = String(bytes, start, minOf(bytes.size - start, 1024), Charsets.ISO_8859_1)

		return when {
			text.startsWith("#EXTM3U") -> hls(client, url, headers, detail)
			text.contains("<MPD", ignoreCase = true) || ctype.contains("dash+xml") -> StreamProbe(StreamKind.DASH, detail = detail)
			// Content-Type says playlist but the header line is odd (comments first, etc.).
			ctype.contains("mpegurl") && looksLikeText(bytes, start) -> hls(client, url, headers, detail)
			text.startsWith("<!doctype", ignoreCase = true) || text.contains("<html", ignoreCase = true) ->
				StreamProbe(
					StreamKind.ERROR,
					note = "This link returned a web page instead of video (expired link or host protection).",
					detail = detail,
				)
			looksLikeMedia(bytes) -> StreamProbe(StreamKind.PROGRESSIVE, detail = detail)
			looksLikeText(bytes, start) ->
				StreamProbe(
					StreamKind.ERROR,
					note = "The server sent text instead of video: \"${text.take(120).replace(Regex("\\s+"), " ").trim()}\"",
					detail = detail,
				)
			else -> StreamProbe(StreamKind.PROGRESSIVE, detail = detail)
		}
	}

	private fun hls(client: OkHttpClient, url: String, headers: Headers?, detail: String): StreamProbe {
		val text = runCatching {
			val req = Request.Builder().url(url).apply { if (headers != null) headers(headers) }.build()
			client.newCall(req).execute().use { it.body.string() }
		}.getOrDefault("")
		val separate = text.contains("#EXT-X-STREAM-INF") &&
			separateAudioMedia.containsMatchIn(text) && variantWithAudio.containsMatchIn(text)
		return StreamProbe(StreamKind.HLS, separateAudio = separate, detail = detail)
	}

	/** Magic numbers of the containers ExoPlayer can play. */
	private fun looksLikeMedia(b: ByteArray): Boolean {
		fun at(i: Int, s: String) = b.size >= i + s.length && s.indices.all { b[i + it].toInt() == s[it].code }
		fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
		return when {
			b.size >= 8 && (at(4, "ftyp") || at(4, "moov") || at(4, "mdat") || at(4, "styp") || at(4, "moof") ||
				at(4, "free") || at(4, "wide") || at(4, "sidx")) -> true // MP4 / fMP4
			u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> true // Matroska / WebM
			at(0, "FLV") || at(0, "OggS") || at(0, "ID3") || at(0, "RIFF") || at(0, "fLaC") || at(0, "#!AMR") -> true
			u(0) == 0x00 && u(1) == 0x00 && u(2) == 0x01 && u(3) == 0xBA -> true // MPEG-PS
			u(0) == 0x47 && (b.size <= 188 || u(188) == 0x47) -> true // MPEG-TS
			u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 -> true // ADTS / MP3
			else -> false
		}
	}

	private fun looksLikeText(b: ByteArray, from: Int): Boolean {
		val len = b.size - from
		if (len <= 0) return false
		var printable = 0
		for (i in from until b.size) {
			val c = b[i].toInt() and 0xFF
			if (c == 9 || c == 10 || c == 13 || c in 32..126) printable++
		}
		return printable * 100 / len >= 95
	}

	private fun describe(p: Peek): String {
		val hex = p.head.take(8).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
		return "HTTP ${p.code}, ${p.contentType.ifEmpty { "no content-type" }}, first bytes: ${hex.ifEmpty { "(empty)" }}"
	}
}
