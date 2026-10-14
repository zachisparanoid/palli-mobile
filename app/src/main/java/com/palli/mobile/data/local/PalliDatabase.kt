package com.palli.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [EventEntity::class, PendingSendEntity::class], version = 1, exportSchema = true)
abstract class PalliDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun pendingSends(): PendingSendDao
}
