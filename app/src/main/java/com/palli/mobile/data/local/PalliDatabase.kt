package com.palli.mobile.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** Every version a phone may hold opens under the next one with its data kept (MigrationTest): never a destructive one. */
@Database(
    entities = [EventEntity::class, PendingSendEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),                // pending_sends.acceptedAt
    ],
)
abstract class PalliDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun pendingSends(): PendingSendDao
}
