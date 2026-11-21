package org.koitharu.kotatsu.reader.ui.epub

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.GZipInterceptor
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Languages offered for translating novels: Google code to display name. */
object NovelTranslationLanguages {

	val all: List<Pair<String, String>> = listOf(
		"en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
		"pt" to "Portuguese", "ru" to "Russian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
		"ar" to "Arabic", "hi" to "Hindi", "id" to "Indonesian", "tr" to "Turkish", "vi" to "Vietnamese",
		"th" to "Thai", "pl" to "Polish", "nl" to "Dutch", "uk" to "Ukrainian", "cs" to "Czech",
		"ro" to "Romanian", "sv" to "Swedish", "el" to "Greek", "he" to "Hebrew", "ms" to "Malay",
		"fil" to "Filipino", "bn" to "Bengali", "hu" to "Hungarian",
	)

	fun nameOf(code: String): String = if (code == AUTO) "Auto-detect" else all.firstOrNull { it.first == code }?.second ?: code

	const val AUTO = "auto"
}

/** A chapter body after translation, and whether anything was actually translated. */
class TranslatedChapter(val html: String, val translatedTo: String?)

/**
 * Translates a chapter's html through Google Translate's public web endpoint (the "scraper" engine in
 * the settings). Only the text nodes are sent, so the markup, images and links come back untouched.
 *
 * Results are cached on disk by a hash of the chapter's html plus the language pair, so reopening a
 * chapter, switching presets or reading it again never asks the network twice.
 */
@Singleton
class NovelTranslator @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient baseClient: OkHttpClient,
) {

	/**
	 * Derived from the app's base client but without two of its interceptors. [GZipInterceptor] stamps
	 * `Content-Encoding: gzip` on every request that has a body, so Google got a plain form body labelled
	 * as gzip and refused it - every translation failed and the chapter silently stayed in the original
	 * language. [RateLimitInterceptor] turns a 429 into an exception before [request] can back off and retry.
	 */
	private val client: OkHttpClient by lazy {
		baseClient.newBuilder()
			.apply { interceptors().removeAll { it is GZipInterceptor || it is RateLimitInterceptor } }
			.callTimeout(40, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build()
	}

	private val failureEvents = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	private var lastFailureAt = 0L

	/** Emits a short message when a chapter could not be translated, so the reader can tell the person why. */
	val failures: SharedFlow<String> get() = failureEvents

	private fun reportFailure(error: Throwable) {
		Log.w(TAG, "Chapter translation failed", error)
		val now = System.currentTimeMillis()
		if (now - lastFailureAt < FAILURE_NOTICE_INTERVAL_MS) return
		lastFailureAt = now
		failureEvents.tryEmit(error.message?.take(120) ?: error.javaClass.simpleName)
	}
	private val cacheDir: File by lazy { File(context.cacheDir, CACHE_DIR).apply { mkdirs() } }
	private val permits = Semaphore(PARALLEL_REQUESTS)

	/** True when [html] already carries a translation to [target] (a download translated at save time). */
	fun isTranslatedTo(html: String, target: String): Boolean =
		html.contains("name=\"$META_NAME\"") && html.contains("content=\"${target.lowercase()}\"")

	/**
	 * Translates a whole chapter document for the reader. Returns [html] itself when there is nothing to
	 * do (already in the target language, no text) or when the network failed, so a chapter always opens.
	 */
	suspend fun translateForReader(html: String, source: String, target: String): String {
		if (isTranslatedTo(html, target)) return html
		val translated = try {
			translate(html, source, target)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			reportFailure(e)
			return html
		}
		return if (translated.translatedTo == null) html else translated.documentHtml
	}

	/**
	 * Translates a chapter being saved: returns just the body (the epub writer supplies its own page
	 * around it) and the language it ended up in, or the original body and null when nothing changed.
	 */
	suspend fun translateForDownload(html: String, source: String, target: String): TranslatedChapter {
		val result = try {
			translate(html, source, target)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			reportFailure(e)
			return TranslatedChapter(html, null)
		}
		if (result.translatedTo == null) return TranslatedChapter(html, null)
		val body = Jsoup.parse(result.documentHtml).apply { outputSettings().prettyPrint(false) }.body().html()
		return TranslatedChapter(body, result.translatedTo)
	}

	private class Outcome(val documentHtml: String, val translatedTo: String?)

	private suspend fun translate(html: String, source: String, target: String): Outcome =
		withContext(Dispatchers.IO) {
			val sl = apiCode(source)
			val tl = apiCode(target)
			val key = hash("$sl|$tl|$html")
			val cached = File(cacheDir, "$key.html")
			if (cached.isFile) {
				val text = cached.readText()
				// A cached "nothing to translate" is stored without the marker, as the original.
				return@withContext Outcome(text, if (isTranslatedTo(text, target)) target.lowercase() else null)
			}

			val document = Jsoup.parse(html)
			document.outputSettings().prettyPrint(false)
			val nodes = collectTextNodes(document)
			if (nodes.isEmpty()) {
				return@withContext Outcome(html, null)
			}
			val cores = nodes.map { Core.of(it.wholeText) }
			val toSend = cores.indices.filter { cores[it].text.isNotEmpty() }
			if (toSend.isEmpty()) {
				return@withContext Outcome(html, null)
			}

			// The first batch goes alone: it tells us which language the chapter is in, and a chapter that
			// is already in the target language is not worth another request.
			val batches = toBatches(toSend.map { cores[it].text })
			val first = translateBatch(batches[0], sl, tl)
			if (sl == "auto" && first.detected != null && sameLanguage(first.detected, tl)) {
				remember(cached, html)
				return@withContext Outcome(html, null)
			}
			val translated = ArrayList<String>(toSend.size)
			translated += first.parts
			if (batches.size > 1) {
				coroutineScope {
					batches.drop(1).map { batch -> async { permits.withPermit { translateBatch(batch, sl, tl).parts } } }
						.forEach { translated += it.await() }
				}
			}
			check(translated.size == toSend.size) { "Translation lost lines" }

			toSend.forEachIndexed { i, nodeIndex ->
				val core = cores[nodeIndex]
				nodes[nodeIndex].text(core.lead + translated[i].ifBlank { core.text } + core.trail)
			}
			document.select("meta[name=$META_NAME]").remove()
			document.head().appendElement("meta").attr("name", META_NAME).attr("content", target.lowercase())
			val out = document.outerHtml()
			remember(cached, out)
			Outcome(out, target.lowercase())
		}

	private fun collectTextNodes(document: Document): List<TextNode> {
		val nodes = ArrayList<TextNode>()
		for (element in document.body().select("*")) {
			if (element.tagName() in SKIPPED_TAGS || element.parents().any { it.tagName() in SKIPPED_TAGS }) continue
			for (node in element.textNodes()) {
				if (node.wholeText.any { it.isLetter() }) nodes += node
			}
		}
		return nodes
	}

	/** Groups the texts so every request stays within what the endpoint accepts. */
	private fun toBatches(texts: List<String>): List<List<String>> {
		val batches = ArrayList<List<String>>()
		var current = ArrayList<String>()
		var size = 0
		for (text in texts) {
			if (current.isNotEmpty() && size + text.length + 1 > BATCH_CHARS) {
				batches += current
				current = ArrayList()
				size = 0
			}
			current += text
			size += text.length + 1
		}
		if (current.isNotEmpty()) batches += current
		return batches
	}

	private class BatchResult(val parts: List<String>, val detected: String?)

	/**
	 * One request for many lines. The lines come back separated the way they went in; if the engine
	 * merged or split any, the batch is halved until each answer lines up with its question again.
	 */
	private suspend fun translateBatch(items: List<String>, sl: String, tl: String): BatchResult {
		if (items.size == 1) {
			val (text, detected) = requestLong(items[0], sl, tl)
			return BatchResult(listOf(text), detected)
		}
		val (joined, detected) = request(items.joinToString("\n"), sl, tl)
		val parts = joined.split("\n")
		if (parts.size == items.size) return BatchResult(parts, detected)
		val mid = items.size / 2
		val left = translateBatch(items.subList(0, mid), sl, tl)
		val right = translateBatch(items.subList(mid, items.size), sl, tl)
		return BatchResult(left.parts + right.parts, detected ?: left.detected)
	}

	/** One very long paragraph is cut at sentence ends so it fits a request. */
	private suspend fun requestLong(text: String, sl: String, tl: String): Pair<String, String?> {
		if (text.length <= BATCH_CHARS) return request(text, sl, tl)
		val pieces = ArrayList<String>()
		var rest = text
		while (rest.length > BATCH_CHARS) {
			var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '。', '！', '？'), BATCH_CHARS)
			if (cut < BATCH_CHARS / 2) cut = rest.lastIndexOf(' ', BATCH_CHARS).takeIf { it > 0 } ?: BATCH_CHARS
			pieces += rest.substring(0, cut + 1)
			rest = rest.substring(cut + 1)
		}
		if (rest.isNotBlank()) pieces += rest
		var detected: String? = null
		val out = StringBuilder()
		for (piece in pieces) {
			val (t, d) = request(piece, sl, tl)
			out.append(t)
			if (detected == null) detected = d
		}
		return out.toString() to detected
	}

	private suspend fun request(text: String, sl: String, tl: String): Pair<String, String?> {
		var failure: Exception? = null
		repeat(ATTEMPTS) { attempt ->
			try {
				// Alternate between the two public hosts so one being blocked or throttled does not sink the chapter.
				val host = ENDPOINTS[attempt % ENDPOINTS.size]
				return withContext(Dispatchers.IO) { requestOnce(host, text, sl, tl) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				failure = e
				delay(600L * (attempt + 1) * (attempt + 1))
			}
		}
		throw failure ?: IOException("Translation failed")
	}

	private fun requestOnce(endpoint: String, text: String, sl: String, tl: String): Pair<String, String?> {
		val url = endpoint.toHttpUrl().newBuilder()
			.addQueryParameter("client", "gtx")
			.addQueryParameter("sl", sl)
			.addQueryParameter("tl", tl)
			.addQueryParameter("dt", "t")
			.addQueryParameter("ie", "UTF-8")
			.addQueryParameter("oe", "UTF-8")
			.build()
		val request = Request.Builder()
			.url(url)
			.post(FormBody.Builder().add("q", text).build())
			.header("User-Agent", USER_AGENT)
			.header("Accept", "*/*")
			.build()
		client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("Translate HTTP ${response.code}")
			val body = response.body?.string().orEmpty()
			val root = runCatching { JSONArray(body) }.getOrElse { throw IOException("Bad translate reply") }
			val segments = root.optJSONArray(0) ?: throw IOException("Empty translate reply")
			val out = StringBuilder()
			for (i in 0 until segments.length()) {
				val segment = segments.optJSONArray(i) ?: continue
				if (!segment.isNull(0)) out.append(segment.optString(0))
			}
			val detected = if (root.length() > 2 && !root.isNull(2)) root.optString(2) else null
			return out.toString() to detected
		}
	}

	private fun remember(file: File, content: String) {
		runCatching {
			val tmp = File(file.path + ".tmp")
			tmp.writeText(content)
			tmp.renameTo(file)
			prune()
		}
	}

	/** Keeps the cache bounded: past the cap the oldest chapters go first. */
	private fun prune() {
		val files = cacheDir.listFiles { f -> f.isFile && f.extension == "html" } ?: return
		var total = files.sumOf { it.length() }
		if (total <= MAX_CACHE_BYTES) return
		for (file in files.sortedBy { it.lastModified() }) {
			total -= file.length()
			file.delete()
			if (total <= MAX_CACHE_BYTES * 3 / 4) break
		}
	}

	private fun hash(value: String): String =
		MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

	private fun apiCode(code: String): String = when (code.lowercase()) {
		"zh" -> "zh-CN"
		else -> code
	}

	private fun sameLanguage(detected: String, target: String): Boolean =
		detected.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)

	/** A text node split into the part worth translating and the whitespace around it. */
	private class Core(val lead: String, val text: String, val trail: String) {
		companion object {
			fun of(raw: String): Core {
				val start = raw.indexOfFirst { !it.isSpace() }.let { if (it < 0) raw.length else it }
				val end = raw.indexOfLast { !it.isSpace() } + 1
				if (end <= start) return Core(raw, "", "")
				val inner = raw.substring(start, end).replace(Regex("\\s*\\n\\s*"), " ")
				return Core(raw.substring(0, start), inner, raw.substring(end))
			}

			private fun Char.isSpace() = isWhitespace() || this == '\u00A0'
		}
	}

	private companion object {
		const val TAG = "NovelTranslator"
		const val FAILURE_NOTICE_INTERVAL_MS = 15_000L
		val ENDPOINTS = listOf(
			"https://translate.googleapis.com/translate_a/single",
			"https://translate.google.com/translate_a/single",
		)
		const val CACHE_DIR = "novel_translations"
		const val META_NAME = "yugen-translated"
		const val BATCH_CHARS = 3800
		const val PARALLEL_REQUESTS = 3
		const val ATTEMPTS = 3
		const val MAX_CACHE_BYTES = 96L * 1024 * 1024
		const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
		val SKIPPED_TAGS = setOf("script", "style", "code", "pre", "noscript", "svg")
	}
}
