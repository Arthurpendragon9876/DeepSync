package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Library subcategories, per-category update interval and per-category content tag.
 *
 * All three columns are nullable with no default, so every existing category keeps its old behaviour: top level,
 * global update schedule, tag chosen automatically.
 */
class Migration39To40 : Migration(39, 40) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE favourite_categories ADD COLUMN parent_id INTEGER")
		db.execSQL("ALTER TABLE favourite_categories ADD COLUMN update_interval TEXT")
		db.execSQL("ALTER TABLE favourite_categories ADD COLUMN media_tag TEXT")
		// The sync update trigger lists the columns it watches; drop it so onOpen re-creates it with the new ones.
		db.execSQL("DROP TRIGGER IF EXISTS sync_favourite_categories_upd")
	}
}
