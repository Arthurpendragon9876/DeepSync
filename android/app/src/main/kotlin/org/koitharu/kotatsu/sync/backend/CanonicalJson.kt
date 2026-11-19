package org.koitharu.kotatsu.sync.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic, git-friendly JSON: keys sorted at every level, and each record of a top-level list on
 * its own line, with the records themselves sorted. Equal data always yields identical bytes, and a
 * changed record touches exactly one line, so git can delta-compress the history of a replica instead
 * of storing a new blob per commit. (Gzip would defeat that, which is why GitHub gets plain text.)
 *
 * Only top-level lists are sorted: they are sets of keyed records. Lists nested inside a record keep
 * their order.
 */
object CanonicalJson {

	fun encode(root: JsonObject): String = buildString {
		append("{\n")
		val keys = root.keys.sorted()
		keys.forEachIndexed { index, key ->
			append(quote(key)).append(':')
			val value = root.getValue(key)
			if (value is JsonArray && value.isNotEmpty()) {
				append("[\n")
				val lines = value.map { compact(it) }.sorted()
				lines.forEachIndexed { i, line ->
					append(line)
					if (i < lines.lastIndex) append(',')
					append('\n')
				}
				append(']')
			} else {
				append(compact(value))
			}
			if (index < keys.lastIndex) append(',')
			append('\n')
		}
		append("}\n")
	}

	private fun compact(element: JsonElement): String = when (element) {
		is JsonObject -> element.keys.sorted().joinToString(",", "{", "}") { key ->
			quote(key) + ":" + compact(element.getValue(key))
		}

		is JsonArray -> element.joinToString(",", "[", "]") { compact(it) }
		is JsonPrimitive -> element.toString() // strings come out quoted and escaped, numbers raw
	}

	private fun quote(key: String): String = JsonPrimitive(key).toString()
}
