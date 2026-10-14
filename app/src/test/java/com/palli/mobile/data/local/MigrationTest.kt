package com.palli.mobile.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The database a phone already holds opens under the new schema with everything in it kept: never a destructive
 * migration. The old database is built exactly as Room built it, from the schema exported for that version (the
 * committed `schemas/…/<n>.json`), then opened by this build's Room, which runs its migrations and then checks that
 * every table is exactly what this build's entities say, throwing if not.
 *
 * (Room's MigrationTestHelper reads the schemas from the instrumentation's assets, and a local test is given only the
 * app's own merged assets; shipping the schemas in the app to reach them is not worth it.)
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After fun tearDown() { context.deleteDatabase(name) }

    /** Version [version] of the database, created as that version's Room created it, with [rows] written into it. */
    private fun databaseAt(version: Int, vararg rows: String) {
        val schema = Json.parseToJsonElement(File("schemas/${PalliDatabase::class.qualifiedName}/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach {
                    db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            rows.forEach(db::execSQL)
            db.version = version
        }
    }

    @Test fun `version 1 opens as version 2 with her timeline and the queue kept, the old sends with no time of acceptance`() = runTest {
        databaseAt(1,
            "INSERT INTO events (id, kind, at, clientId, json) VALUES " +
                "(5, 'message', 5.0, NULL, '{\"id\":5,\"kind\":\"message\",\"at\":5.0,\"text\":\"hi\"}')",
            "INSERT INTO pending_sends (clientId, text, attachmentsJson, state, failure, createdAt, attempts) " +
                "VALUES ('c1', 'on its way', '[]', 'accepted', NULL, 42, 1)",
        )

        // Exactly the app's own builder, less the file name: no fallback to a destructive migration.
        val db = Room.databaseBuilder(context, PalliDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            assertEquals("hi", db.events().observeLatest(10).first().single().toDto().text)
            val send = db.pendingSends().get("c1")!!
            assertEquals("on its way", send.text)
            assertEquals(SendStates.ACCEPTED, send.state)
            assertEquals(42L, send.createdAt)
            assertNull(send.acceptedAt)
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }
}
