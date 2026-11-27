package org.koitharu.kotatsu.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookPayloadsTest {

	private fun items(count: Int) = (1..count).map { WebhookEvent.Item("Title $it", it) }

	private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

	@Test
	fun `discord message is capped and lists at most the item limit`() {
		val event = WebhookEvent.libraryUpdate(items(100))
		val content = parse(WebhookPayloads.discord(event)).getValue("content").jsonPrimitive.content

		assertTrue(content.length <= WebhookPayloads.DISCORD_MAX_CONTENT)
		assertTrue(content.contains("Title 1 (+1)"))
		assertFalse(content.contains("Title 16 "))
		assertTrue(content.contains("…and 85 more"))
	}

	@Test
	fun `discord message cannot ping anyone`() {
		val event = WebhookEvent.libraryUpdate(listOf(WebhookEvent.Item("@everyone", 1)))
		val mentions = parse(WebhookPayloads.discord(event)).getValue("allowed_mentions").jsonObject
		assertEquals(JsonArray(emptyList()), mentions.getValue("parse"))
	}

	@Test
	fun `json payload carries the event id counts and timestamp`() {
		val payload = parse(WebhookPayloads.json(WebhookEvent.libraryUpdate(items(2)), timestampMs = 1234L))

		assertEquals("library_update", payload.getValue("event").jsonPrimitive.content)
		assertEquals("1234", payload.getValue("timestamp").jsonPrimitive.content)
		assertEquals(2, payload.getValue("total_items").jsonPrimitive.int)
		val list = payload.getValue("items").jsonArray
		assertEquals(2, list.size)
		assertEquals(2, list[1].jsonObject.getValue("count").jsonPrimitive.int)
	}

	@Test
	fun `json payload lists at most the item limit but reports the real total`() {
		val payload = parse(WebhookPayloads.json(WebhookEvent.libraryUpdate(items(80)), timestampMs = 0L))
		assertEquals(WebhookPayloads.JSON_MAX_ITEMS, payload.getValue("items").jsonArray.size)
		assertEquals(80, payload.getValue("total_items").jsonPrimitive.int)
	}

	@Test
	fun `failure events never include error details`() {
		for (event in listOf(WebhookEvent.syncFailed(), WebhookEvent.backupFailed())) {
			val text = WebhookPayloads.json(event, 0L) + WebhookPayloads.discord(event)
			assertFalse(text.contains("token", ignoreCase = true))
			assertFalse(text.contains("http", ignoreCase = true))
		}
	}

	@Test
	fun `discord needs https while a generic endpoint may use http`() {
		assertTrue(WebhookPayloads.isValidUrl("https://discord.com/api/webhooks/1/abc", requireHttps = true))
		assertFalse(WebhookPayloads.isValidUrl("http://discord.com/api/webhooks/1/abc", requireHttps = true))
		assertTrue(WebhookPayloads.isValidUrl("http://192.168.1.5:8080/hook", requireHttps = false))
		assertFalse(WebhookPayloads.isValidUrl("ftp://example.com/hook", requireHttps = false))
		assertFalse(WebhookPayloads.isValidUrl("not a url", requireHttps = false))
	}

	@Test
	fun `webhook settings never go into backups or sync`() {
		val keys = listOf(
			org.koitharu.kotatsu.core.prefs.AppSettings.KEY_WEBHOOKS_ENABLED,
			org.koitharu.kotatsu.core.prefs.AppSettings.KEY_WEBHOOK_DISCORD_URL,
			org.koitharu.kotatsu.core.prefs.AppSettings.KEY_WEBHOOK_JSON_URL,
		)
		for (key in keys) {
			assertTrue(key in org.koitharu.kotatsu.core.prefs.AppSettings.SENSITIVE_BACKUP_KEYS)
			assertFalse(org.koitharu.kotatsu.core.prefs.AppSettings.isSyncableKey(key))
		}
	}
}
