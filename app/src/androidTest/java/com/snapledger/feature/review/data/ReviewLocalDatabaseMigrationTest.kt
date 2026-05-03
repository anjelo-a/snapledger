package com.snapledger.feature.review.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewLocalDatabaseMigrationTest {
    @Test
    fun migrate1To2_upgrades_sync_queue_to_durable_mutation_schema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "review-local-migration-test.db"
        context.deleteDatabase(databaseName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `local_receipts` (
                                `receiptId` TEXT NOT NULL,
                                `merchant` TEXT NOT NULL,
                                `expenseDate` TEXT NOT NULL,
                                `totalAmountRaw` TEXT NOT NULL,
                                `totalAmountMinor` INTEGER NOT NULL,
                                `savedAtMillis` INTEGER NOT NULL,
                                PRIMARY KEY(`receiptId`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `local_receipt_items` (
                                `receiptId` TEXT NOT NULL,
                                `position` INTEGER NOT NULL,
                                `description` TEXT NOT NULL,
                                `amountRaw` TEXT,
                                `amountMinor` INTEGER,
                                PRIMARY KEY(`receiptId`, `position`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `receipt_sync_queue` (
                                `queueId` TEXT NOT NULL,
                                `receiptId` TEXT NOT NULL,
                                `queuedAtMillis` INTEGER NOT NULL,
                                `operation` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                PRIMARY KEY(`queueId`)
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        try {
            helper.writableDatabase.use { database ->
                database.execSQL(
                    """
                    INSERT INTO `receipt_sync_queue` (
                        `queueId`,
                        `receiptId`,
                        `queuedAtMillis`,
                        `operation`,
                        `status`
                    ) VALUES (
                        'queue-1',
                        'receipt-1',
                        1234,
                        'receipt_upsert',
                        'pending'
                    )
                    """.trimIndent(),
                )

                MIGRATION_1_2.migrate(database)

                val columns = mutableSetOf<String>()
                database.query("PRAGMA table_info(`receipt_sync_queue`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    }
                }

                assertTrue(columns.contains("idempotencyKey"))
                assertTrue(columns.contains("payloadSnapshot"))
                assertTrue(columns.contains("attemptCount"))
                assertTrue(columns.contains("nextRetryAtMillis"))

                database.query(
                    """
                    SELECT
                        `queueId`,
                        `idempotencyKey`,
                        `receiptId`,
                        `operation`,
                        `payloadSnapshot`,
                        `status`,
                        `attemptCount`,
                        `lastError`,
                        `queuedAtMillis`,
                        `nextRetryAtMillis`
                    FROM `receipt_sync_queue`
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(
                        "queue-1",
                        cursor.getString(cursor.getColumnIndexOrThrow("queueId")),
                    )
                    assertEquals(
                        "queue-1",
                        cursor.getString(cursor.getColumnIndexOrThrow("idempotencyKey")),
                    )
                    assertEquals(
                        "receipt-1",
                        cursor.getString(cursor.getColumnIndexOrThrow("receiptId")),
                    )
                    assertEquals(
                        "create",
                        cursor.getString(cursor.getColumnIndexOrThrow("operation")),
                    )
                    assertEquals(
                        "{\"id\":\"receipt-1\"}",
                        cursor.getString(cursor.getColumnIndexOrThrow("payloadSnapshot")),
                    )
                    assertEquals(
                        "pending",
                        cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    )
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("attemptCount")))
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("lastError")))
                    assertEquals(
                        1234L,
                        cursor.getLong(cursor.getColumnIndexOrThrow("queuedAtMillis")),
                    )
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("nextRetryAtMillis")))
                }
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrate2To3_creates_sync_cursor_state_table() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "review-local-migration-v3-test.db"
        context.deleteDatabase(databaseName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `local_receipts` (
                                `receiptId` TEXT NOT NULL,
                                `merchant` TEXT NOT NULL,
                                `expenseDate` TEXT NOT NULL,
                                `totalAmountRaw` TEXT NOT NULL,
                                `totalAmountMinor` INTEGER NOT NULL,
                                `savedAtMillis` INTEGER NOT NULL,
                                PRIMARY KEY(`receiptId`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `local_receipt_items` (
                                `receiptId` TEXT NOT NULL,
                                `position` INTEGER NOT NULL,
                                `description` TEXT NOT NULL,
                                `amountRaw` TEXT,
                                `amountMinor` INTEGER,
                                PRIMARY KEY(`receiptId`, `position`)
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `receipt_sync_queue` (
                                `queueId` TEXT NOT NULL,
                                `idempotencyKey` TEXT NOT NULL,
                                `receiptId` TEXT NOT NULL,
                                `operation` TEXT NOT NULL,
                                `payloadSnapshot` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `attemptCount` INTEGER NOT NULL,
                                `lastError` TEXT,
                                `queuedAtMillis` INTEGER NOT NULL,
                                `nextRetryAtMillis` INTEGER,
                                PRIMARY KEY(`queueId`)
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        try {
            helper.writableDatabase.use { database ->
                MIGRATION_2_3.migrate(database)

                val columns = mutableSetOf<String>()
                database.query("PRAGMA table_info(`receipt_sync_state`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    }
                }

                assertTrue(columns.contains("stateKey"))
                assertTrue(columns.contains("stateValue"))

                database.query(
                    """
                    SELECT `stateKey`, `stateValue`
                    FROM `receipt_sync_state`
                    WHERE `stateKey` = 'pull_cursor'
                    """.trimIndent(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(
                        "pull_cursor",
                        cursor.getString(cursor.getColumnIndexOrThrow("stateKey")),
                    )
                    assertEquals(
                        "0",
                        cursor.getString(cursor.getColumnIndexOrThrow("stateValue")),
                    )
                }
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }
}
