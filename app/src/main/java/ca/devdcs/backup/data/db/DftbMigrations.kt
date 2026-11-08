package ca.devdcs.backup.data.db

import androidx.room.migration.Migration

/**
 * Every hand-written schema migration, in order. Applied unconditionally by DatabaseModule so a
 * future version bump that forgets to add its Migration fails loudly at open time instead of
 * silently wiping user data: there is deliberately no destructive fallback anywhere.
 *
 * Schema v1 is the first shipped version, so the array is empty. When bumping
 * [DftbDatabase.VERSION], add `object : Migration(1, 2) { override fun migrate(connection: SQLiteConnection) { ... } }`
 * here and commit the exported JSON under app/schemas.
 */
object DftbMigrations {
    val all: Array<Migration> = emptyArray()
}
