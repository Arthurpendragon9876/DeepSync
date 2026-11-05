package org.koitharu.kotatsu.anime.player

import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP proxy that re-issues every stream request with the extension's own OkHttp client
 * and the video's headers (Referer, Cookie, User-Agent...). External players such as VLC ignore
 * intent header extras, so without this a header-gated stream either fails or loses its separate
 * audio rendition. HLS playlists are rewritten so segments, keys and alternate audio tracks also
 * route through here. Idea and playlist handling ported from Zangetsu; the server itself is a small
 * plain-socket HTTP/1.1 implementation so no extra library is needed.
 *
 * The built-in player uses the direct URL first and falls back to this proxy if that fails.
 */
object AnimeStreamProxy {

	private class Session(val client: OkHttpClient, val url: String, val headers: Map<String, String>)

	private val sessions = ConcurrentHashMap<String, Session>()
	private val streamClients = ConcurrentHashMap<OkHttpClient, OkHttpClient>()

	/** VOD manifests never change, so cache the rewritten text to keep seeking snappy. */
	private val playlistCache = ConcurrentHashMap<String, String>()

	private val pool = Executors.newCachedThreadPool { r -> Thread(r, "anime-proxy").apply { isDaemon = true } }

	@Volatile
	private var server: ServerSocket? = null

	private fun streamClient(base: OkHttpClient): OkHttpClient = streamClients.getOrPut(base) {
		base.newBuilder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.callTimeout(0, TimeUnit.MILLISECONDS)
			.dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
			.build()
	}

	@Synchronized
	private fun ensureStarted(): ServerSocket {
		server?.takeIf { !it.isClosed }?.let { return it }
		val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
		server = socket
		pool.execute {
			while (!socket.isClosed) {
				val client = try {
					socket.accept()
				} catch (_: IOException) {
					break
				}
				pool.execute { handle(client) }
			}
		}
		return socket
	}

	/** Returns a `http://127.0.0.1:<port>/s/<token>` URL that serves [url] with [headers]. */
	fun proxyUrl(client: OkHttpClient, url: String, headers: Map<String, String>): String {
		val port = ensureStarted().localPort
		val token = UUID.randomUUID().toString().replace("-", "")
		sessions[token] = Session(client, url, headers)
		val suffix = if (url.substringBefore('?').endsWith(".m3u8", true)) ".m3u8" else ""
		return "http://127.0.0.1:$port/s/$token$suffix"
	}

	// --- tiny HTTP server -----------------------------------------------------------------------

	private fun handle(socket: Socket) {
		socket.use { s ->
			try {
				s.soTimeout = 30_000
				val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
				val requestLine = reader.readLine() ?: return
				val parts = requestLine.split(' ')
				if (parts.size < 2) return
				val method = parts[0].uppercase()
				val path = parts[1]
				val requestHeaders = HashMap<String, String>()
				while (true) {
					val line = reader.readLine() ?: break
					if (line.isEmpty()) break
					val i = line.indexOf(':')
					if (i > 0) requestHeaders[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
				}
				val out = s.getOutputStream().buffered(64 * 1024)
				if (method != "GET" && method != "HEAD") {
					writeSimple(out, 405, "Method Not Allowed", "method not allowed")
				} else {
					serve(out, method == "HEAD", path, requestHeaders)
				}
				out.flush()
			} catch (_: IOException) {
				// player closed the connection (seek / stop) - nothing to do
			} catch (_: Exception) {
			}
		}
	}

	private fun serve(out: OutputStream, headOnly: Boolean, path: String, request: Map<String, String>) {
		val token = path.removePrefix("/s/").substringBefore("?").substringBefore(".")
		val ps = sessions[token] ?: return writeSimple(out, 404, "Not Found", "unknown token")

		val urlPath = ps.url.substringBefore("?")
		val looksHls = urlPath.endsWith(".m3u8", true) || urlPath.endsWith(".m3u", true)
		if (looksHls) {
			playlistCache[ps.url]?.let { return writePlaylist(out, headOnly, it) }
		}

		val upstreamRequest = try {
			Request.Builder().url(ps.url).headers(
				Headers.Builder().apply {
					ps.headers.forEach { (k, v) -> runCatching { add(k, v) } }
					request["range"]?.let { set("Range", it) }
				}.build(),
			).build()
		} catch (e: Exception) {
			return writeSimple(out, 400, "Bad Request", e.message ?: "bad url")
		}
		val upstream = try {
			streamClient(ps.client).newCall(upstreamRequest).execute()
		} catch (e: Exception) {
			return writeSimple(out, 502, "Bad Gateway", e.message ?: "upstream request failed")
		}

		upstream.use { resp ->
			val contentType = resp.header("Content-Type") ?: "application/octet-stream"
			if (looksHls || contentType.contains("mpegurl", ignoreCase = true)) {
				val rewritten = rewrite(resp.body.string(), ps)
				playlistCache[ps.url] = rewritten
				return writePlaylist(out, headOnly, rewritten)
			}
			// Some hosts serve playlists as octet-stream; sniff the first bytes.
			val body = PushbackInputStream(resp.body.byteStream(), 7)
			val peek = ByteArray(7)
			val n = body.read(peek)
			if (n > 0) body.unread(peek, 0, n)
			if (n > 0 && String(peek, 0, n, Charsets.US_ASCII).startsWith("#EXTM3U")) {
				val rewritten = rewrite(String(body.readBytes(), Charsets.UTF_8), ps)
				playlistCache[ps.url] = rewritten
				return writePlaylist(out, headOnly, rewritten)
			}
			val code = resp.code
			val head = StringBuilder("HTTP/1.1 $code ${reason(code)}\r\n")
			head.append("Content-Type: $contentType\r\n")
			resp.header("Content-Length")?.let { head.append("Content-Length: $it\r\n") }
			resp.header("Content-Range")?.let { head.append("Content-Range: $it\r\n") }
			head.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
			out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
			if (!headOnly) copy(body, out)
		}
	}

	private fun copy(input: InputStream, out: OutputStream) {
		val buf = ByteArray(64 * 1024)
		while (true) {
			val read = input.read(buf)
			if (read < 0) break
			out.write(buf, 0, read)
		}
	}

	private fun rewrite(text: String, ps: Session): String =
		HlsRewriter.rewrite(text, ps.url) { abs -> proxyUrl(ps.client, abs, ps.headers) }

	private fun writePlaylist(out: OutputStream, headOnly: Boolean, text: String) {
		val bytes = text.toByteArray(Charsets.UTF_8)
		val head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
			"Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
		out.write(head.toByteArray(Charsets.ISO_8859_1))
		if (!headOnly) copy(ByteArrayInputStream(bytes), out)
	}

	private fun writeSimple(out: OutputStream, code: Int, reason: String, message: String) {
		val bytes = message.toByteArray(Charsets.UTF_8)
		val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
			"Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
		out.write(head.toByteArray(Charsets.ISO_8859_1))
		out.write(bytes)
	}

	private fun reason(code: Int) = when (code) {
		200 -> "OK"
		206 -> "Partial Content"
		301 -> "Moved Permanently"
		302 -> "Found"
		304 -> "Not Modified"
		403 -> "Forbidden"
		404 -> "Not Found"
		416 -> "Range Not Satisfiable"
		else -> "Status"
	}
}
