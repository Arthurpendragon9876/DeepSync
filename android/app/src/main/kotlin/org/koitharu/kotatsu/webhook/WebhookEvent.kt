package org.koitharu.kotatsu.webhook

import org.koitharu.kotatsu.core.prefs.AppSettings

/** What happened. [settingKey] is the per-event switch in the webhook settings; null means "always allowed". */
enum class WebhookEventType(val id: String, val settingKey: String?) {
	LIBRARY_UPDATE("library_update", AppSettings.KEY_WEBHOOK_EVENT_LIBRARY),
	SYNC_COMPLETED("sync_completed", AppSettings.KEY_WEBHOOK_EVENT_SYNC),
	SYNC_FAILED("sync_failed", AppSettings.KEY_WEBHOOK_EVENT_SYNC),
	BACKUP_COMPLETED("backup_completed", AppSettings.KEY_WEBHOOK_EVENT_BACKUP),
	BACKUP_FAILED("backup_failed", AppSettings.KEY_WEBHOOK_EVENT_BACKUP),
	TEST("test", null),
}

/**
 * A webhook message. It only ever carries fixed, human-readable fields (a title, a sentence and
 * optionally a list of titles with counts). It deliberately has no field for tokens, cookies,
 * provider keys or raw exception messages, so none of those can leak into a payload.
 */
class WebhookEvent(
	val type: WebhookEventType,
	val title: String,
	val message: String,
	val items: List<Item> = emptyList(),
) {

	/** One title with how many new chapters or episodes it got. */
	class Item(val title: String, val count: Int)

	companion object {

		fun libraryUpdate(items: List<Item>) = WebhookEvent(
			type = WebhookEventType.LIBRARY_UPDATE,
			title = "Library updated",
			message = if (items.size == 1) "1 title has new content." else "${items.size} titles have new content.",
			items = items,
		)

		fun syncCompleted() = WebhookEvent(WebhookEventType.SYNC_COMPLETED, "Sync completed", "Your library was synced.")

		fun syncFailed() = WebhookEvent(WebhookEventType.SYNC_FAILED, "Sync failed", "Sync could not finish. Check the app for details.")

		fun backupCompleted() = WebhookEvent(WebhookEventType.BACKUP_COMPLETED, "Backup completed", "A periodic backup was saved.")

		fun backupFailed() = WebhookEvent(WebhookEventType.BACKUP_FAILED, "Backup failed", "The periodic backup could not be saved.")

		fun test() = WebhookEvent(WebhookEventType.TEST, "Test webhook", "If you can read this, your webhook works.")
	}
}
