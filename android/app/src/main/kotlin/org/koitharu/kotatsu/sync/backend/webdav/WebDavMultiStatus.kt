package org.koitharu.kotatsu.sync.backend.webdav

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** One resource of a PROPFIND answer. */
class WebDavEntry(
	val name: String,
	val isCollection: Boolean,
	val etag: String?,
	val size: Long,
	val modifiedAtMs: Long,
) {

	/** Changes whenever the content does: the server's ETag, else size and time (weak, but never stale). */
	val checksum: String get() = etag ?: "$size-$modifiedAtMs"
}

/** Pure parser of a WebDAV `207 Multi-Status` body, so it can be unit-tested without Android. */
object WebDavMultiStatus {

	fun parse(xml: ByteArray): List<WebDavEntry> {
		val factory = DocumentBuilderFactory.newInstance().apply {
			isNamespaceAware = true
			// Servers are untrusted input: no DTDs, no external entities.
			runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
			runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
			runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
			isExpandEntityReferences = false
		}
		val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
		val result = ArrayList<WebDavEntry>()
		val responses = document.getElementsByTagNameNS("*", "response")
		for (i in 0 until responses.length) {
			val response = responses.item(i) as? Element ?: continue
			val href = response.child("href")?.textContent?.trim() ?: continue
			// Several propstat blocks may exist (200 for what it has, 404 for what it hasn't): use the 200 one.
			val prop = response.children("propstat")
				.firstOrNull { it.child("status")?.textContent?.contains(" 200") == true }
				?.child("prop") ?: continue
			result += WebDavEntry(
				name = nameOf(href),
				isCollection = prop.child("resourcetype")?.child("collection") != null,
				etag = prop.child("getetag")?.textContent?.let { cleanEtag(it) },
				size = prop.child("getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0L,
				modifiedAtMs = prop.child("getlastmodified")?.textContent?.let { parseHttpDate(it) } ?: 0L,
			)
		}
		return result
	}

	/** `https://host/dav/DeepSync/replica_a.json.gz` or `/dav/DeepSync/` -> last path segment, decoded. */
	fun nameOf(href: String): String {
		val path = href.substringBefore('?').substringBefore('#').trimEnd('/')
		val segment = path.substringAfterLast('/')
		return runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
	}

	fun cleanEtag(raw: String): String? = raw.trim().removePrefix("W/").trim('"').ifEmpty { null }

	fun parseHttpDate(raw: String): Long =
		runCatching { ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
			.getOrDefault(0L)

	private fun Element.children(localName: String): List<Element> {
		val out = ArrayList<Element>()
		var node: Node? = firstChild
		while (node != null) {
			if (node is Element && node.localName == localName) out += node
			node = node.nextSibling
		}
		return out
	}

	private fun Element.child(localName: String): Element? = children(localName).firstOrNull()
}
