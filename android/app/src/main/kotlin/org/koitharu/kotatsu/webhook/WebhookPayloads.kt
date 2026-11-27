package org.koitharu.kotatsu.webhook

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds webhook request bodies. Pure Kotlin so it can be unit tested without Android. */
object WebhookPayloads {

	const val DISCORD_MAX_CONTENT = 1900 // Discord rejects messages over 2000 characters
	const val DISCORD_MAX_ITEMS = 15
	const val JSON_MAX_ITEMS = 50

	/** Discord needs https; a generic endpoint may be plain http (for example on a home network). */
	fun isValidUrl(url: String, requireHttps: Boolean): Boolean {
		val parsed = url.trim().toHttpUrlOrNull() ?: return false
		return parsed.isHttps || (!requireHttps && parsed.scheme == "http")
	}

	fun discordText(event: WebhookEvent): String {
		val text = StringBuilder()
		text.append("**").append(event.title).append("**\n").append(event.message)
		event.items.take(DISCORD_MAX_ITEMS).forEach { item ->
			text.append("\n• ").append(item.title)
			if (item.count > 0) text.append(" (+").append(item.count).append(')')
		}
		val hidden = event.items.size - DISCORD_MAX_ITEMS
		if (hidden > 0) text.append("\n…and ").append(hidden).append(" more")
		return if (text.length > DISCORD_MAX_CONTENT) text.substring(0, DISCORD_MAX_CONTENT - 1) + "…" else text.toString()
	}

	/** An empty `allowed_mentions.parse` stops a title like "@everyone" from pinging anyone. */
	fun discord(event: WebhookEvent): String = buildJsonObject {
		put("username", "DeepSync")
		put("content", discordText(event))
		putJsonObject("allowed_mentions") { putJsonArray("parse") {} }
	}.toString()

	fun json(event: WebhookEvent, timestampMs: Long): String = buildJsonObject {
		put("app", "DeepSync")
		put("event", event.type.id)
		put("timestamp", timestampMs)
		put("title", event.title)
		put("message", event.message)
		put("total_items", event.items.size)
		putJsonArray("items") {
			event.items.take(JSON_MAX_ITEMS).forEach { item ->
				add(
					buildJsonObject {
						put("title", item.title)
						put("count", item.count)
					},
				)
			}
		}
	}.toString()
}
