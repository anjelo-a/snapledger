package com.snapledger.feature.review.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.snapledger.core.sync.ReceiptSyncCursorStore
import com.snapledger.core.sync.ReceiptSyncLocalStore
import com.snapledger.core.sync.ReceiptSyncMutationStore
import com.snapledger.core.sync.ReceiptSyncPendingMutationStore
import com.snapledger.core.sync.RECEIPT_SYNC_CURSOR_KEY
import com.snapledger.core.sync.INITIAL_RECEIPT_SYNC_CURSOR
import com.snapledger.feature.review.domain.LocalReceiptItemRecord
import com.snapledger.feature.review.domain.LocalReceiptRecord
import com.snapledger.feature.review.domain.ReviewAtomicSaveStore
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import com.snapledger.feature.review.domain.ReviewLocalReceiptStore
import com.snapledger.feature.review.domain.ReviewSyncQueueStore
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord.Companion.STATUS_FAILED
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord.Companion.STATUS_IN_FLIGHT
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord.Companion.STATUS_PENDING
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord.Companion.STATUS_SYNCED

@Entity(tableName = "local_receipts")
data class LocalReceiptEntity(
    @PrimaryKey val receiptId: String,
    val merchant: String,
    val expenseDate: String,
    val totalAmountRaw: String,
    val totalAmountMinor: Long,
    val savedAtMillis: Long,
)

@Entity(
    tableName = "local_receipt_items",
    primaryKeys = ["receiptId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = LocalReceiptEntity::class,
            parentColumns = ["receiptId"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("receiptId")],
)
data class LocalReceiptItemEntity(
    val receiptId: String,
    val position: Int,
    val description: String,
    val amountRaw: String?,
    val amountMinor: Long?,
)

@Entity(
    tableName = "receipt_sync_queue",
    indices = [
        Index("receiptId"),
        Index("status"),
        Index("nextRetryAtMillis"),
    ],
)
data class ReceiptSyncQueueEntity(
    @PrimaryKey val queueId: String,
    val idempotencyKey: String,
    val receiptId: String,
    val operation: String,
    val payloadSnapshot: String,
    val status: String,
    val attemptCount: Int,
    val lastError: String?,
    val queuedAtMillis: Long,
    val nextRetryAtMillis: Long?,
)

@Entity(tableName = "receipt_sync_state")
data class ReceiptSyncStateEntity(
    @PrimaryKey val stateKey: String,
    val stateValue: String,
)

@Dao
interface LocalReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(entity: LocalReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(entities: List<LocalReceiptItemEntity>)

    @Query("DELETE FROM local_receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItems(receiptId: String)

    @Query("DELETE FROM local_receipts WHERE receiptId = :receiptId")
    suspend fun deleteReceipt(receiptId: String)

    @Transaction
    suspend fun replaceReceiptWithItems(
        receipt: LocalReceiptEntity,
        items: List<LocalReceiptItemEntity>,
    ) {
        insertReceipt(receipt)
        deleteItems(receipt.receiptId)
        if (items.isNotEmpty()) {
            insertItems(items)
        }
    }

    @Transaction
    suspend fun deleteReceiptWithItems(receiptId: String) {
        deleteItems(receiptId)
        deleteReceipt(receiptId)
    }
}

@Dao
interface ReceiptSyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueRecord(entity: ReceiptSyncQueueEntity)

    @Query(
        """
        SELECT * FROM receipt_sync_queue
        WHERE status IN (:eligibleStatuses)
            AND (nextRetryAtMillis IS NULL OR nextRetryAtMillis <= :nowMillis)
        ORDER BY queuedAtMillis ASC, queueId ASC
        LIMIT :limit
        """
    )
    suspend fun loadDueQueueRecords(
        eligibleStatuses: List<String>,
        nowMillis: Long,
        limit: Int,
    ): List<ReceiptSyncQueueEntity>

    @Query(
        """
        UPDATE receipt_sync_queue
        SET status = :status,
            attemptCount = attemptCount + 1,
            lastError = NULL,
            nextRetryAtMillis = NULL
        WHERE queueId IN (:queueIds)
        """
    )
    suspend fun markInFlight(
        queueIds: List<String>,
        status: String = STATUS_IN_FLIGHT,
    )

    @Query(
        """
        UPDATE receipt_sync_queue
        SET status = :status,
            lastError = NULL,
            nextRetryAtMillis = NULL
        WHERE queueId = :queueId
        """
    )
    suspend fun markSynced(
        queueId: String,
        status: String = STATUS_SYNCED,
    )

    @Query(
        """
        UPDATE receipt_sync_queue
        SET status = :status,
            lastError = :lastError,
            nextRetryAtMillis = :nextRetryAtMillis
        WHERE queueId = :queueId
        """
    )
    suspend fun markFailed(
        queueId: String,
        lastError: String,
        nextRetryAtMillis: Long?,
        status: String = STATUS_FAILED,
    )

    @Query(
        """
        SELECT COUNT(*) > 0 FROM receipt_sync_queue
        WHERE receiptId = :receiptId AND status IN (:activeStatuses)
        """
    )
    suspend fun hasPendingMutation(
        receiptId: String,
        activeStatuses: List<String>,
    ): Boolean
}

@Dao
interface ReceiptSyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: ReceiptSyncStateEntity)

    @Query("SELECT stateValue FROM receipt_sync_state WHERE stateKey = :stateKey LIMIT 1")
    suspend fun loadStateValue(stateKey: String): String?
}

@Database(
    entities = [
        LocalReceiptEntity::class,
        LocalReceiptItemEntity::class,
        ReceiptSyncQueueEntity::class,
        ReceiptSyncStateEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ReviewLocalDatabase : RoomDatabase() {
    abstract fun localReceiptDao(): LocalReceiptDao
    abstract fun receiptSyncQueueDao(): ReceiptSyncQueueDao
    abstract fun receiptSyncStateDao(): ReceiptSyncStateDao

    companion object {
        @Volatile
        private var instance: ReviewLocalDatabase? = null

        fun getInstance(context: Context): ReviewLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context,
                    ReviewLocalDatabase::class.java,
                    "snapledger-review.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
        }
    }
}

class RoomReviewLocalReceiptStore(
    private val database: ReviewLocalDatabase,
) : ReviewLocalReceiptStore, ReceiptSyncLocalStore {
    override suspend fun saveReceipt(record: LocalReceiptRecord) {
        upsertReceipt(record)
    }

    override suspend fun upsertReceipt(record: LocalReceiptRecord) {
        database.localReceiptDao().replaceReceiptWithItems(
            receipt = LocalReceiptEntity(
                receiptId = record.receiptId,
                merchant = record.merchant,
                expenseDate = record.expenseDate,
                totalAmountRaw = record.totalAmountRaw,
                totalAmountMinor = record.totalAmountMinor,
                savedAtMillis = record.savedAtMillis,
            ),
            items = record.items.map { item ->
                item.toEntity(record.receiptId)
            },
        )
    }

    override suspend fun deleteReceipt(receiptId: String) {
        database.localReceiptDao().deleteReceiptWithItems(receiptId)
    }
}

class RoomReviewAtomicSaveStore(
    private val database: ReviewLocalDatabase,
) : ReviewAtomicSaveStore {
    override suspend fun saveReceiptAndQueue(
        receiptRecord: LocalReceiptRecord,
        syncRecord: ReceiptSyncQueueRecord,
    ) {
        database.withTransaction {
            database.localReceiptDao().replaceReceiptWithItems(
                receipt = receiptRecord.toEntity(),
                items = receiptRecord.items.map { item ->
                    item.toEntity(receiptRecord.receiptId)
                },
            )
            database.receiptSyncQueueDao().insertQueueRecord(syncRecord.toEntity())
        }
    }
}

class RoomReviewSyncQueueStore(
    private val database: ReviewLocalDatabase,
) : ReviewSyncQueueStore, ReceiptSyncMutationStore, ReceiptSyncPendingMutationStore {
    override suspend fun enqueue(record: ReceiptSyncQueueRecord) {
        database.receiptSyncQueueDao().insertQueueRecord(
            ReceiptSyncQueueEntity(
                queueId = record.queueId,
                idempotencyKey = record.idempotencyKey,
                receiptId = record.receiptId,
                operation = record.operation,
                payloadSnapshot = record.payloadSnapshot,
                status = record.status,
                attemptCount = record.attemptCount,
                lastError = record.lastError,
                queuedAtMillis = record.queuedAtMillis,
                nextRetryAtMillis = record.nextRetryAtMillis,
            ),
        )
    }

    override suspend fun loadDueMutations(
        nowMillis: Long,
        limit: Int,
    ): List<ReceiptSyncQueueRecord> {
        return database.receiptSyncQueueDao()
            .loadDueQueueRecords(
                eligibleStatuses = listOf(STATUS_PENDING, STATUS_FAILED),
                nowMillis = nowMillis,
                limit = limit,
            )
            .map(ReceiptSyncQueueEntity::toDomain)
    }

    override suspend fun markInFlight(queueIds: List<String>) {
        if (queueIds.isEmpty()) {
            return
        }
        database.receiptSyncQueueDao().markInFlight(queueIds)
    }

    override suspend fun markSynced(queueId: String) {
        database.receiptSyncQueueDao().markSynced(queueId)
    }

    override suspend fun markFailed(
        queueId: String,
        lastError: String,
        nextRetryAtMillis: Long?,
    ) {
        database.receiptSyncQueueDao().markFailed(
            queueId = queueId,
            lastError = lastError,
            nextRetryAtMillis = nextRetryAtMillis,
        )
    }

    override suspend fun hasPendingMutation(receiptId: String): Boolean {
        return database.receiptSyncQueueDao().hasPendingMutation(
            receiptId = receiptId,
            activeStatuses = listOf(STATUS_PENDING, STATUS_FAILED, STATUS_IN_FLIGHT),
        )
    }
}

class RoomReceiptSyncCursorStore(
    private val database: ReviewLocalDatabase,
) : ReceiptSyncCursorStore {
    override suspend fun readCursor(): String {
        return database.receiptSyncStateDao()
            .loadStateValue(RECEIPT_SYNC_CURSOR_KEY)
            ?: INITIAL_RECEIPT_SYNC_CURSOR
    }

    override suspend fun writeCursor(cursor: String) {
        database.receiptSyncStateDao().upsertState(
            ReceiptSyncStateEntity(
                stateKey = RECEIPT_SYNC_CURSOR_KEY,
                stateValue = cursor,
            ),
        )
    }
}

private fun LocalReceiptItemRecord.toEntity(receiptId: String): LocalReceiptItemEntity {
    return LocalReceiptItemEntity(
        receiptId = receiptId,
        position = position,
        description = description,
        amountRaw = amountRaw,
        amountMinor = amountMinor,
    )
}

private fun LocalReceiptRecord.toEntity(): LocalReceiptEntity {
    return LocalReceiptEntity(
        receiptId = receiptId,
        merchant = merchant,
        expenseDate = expenseDate,
        totalAmountRaw = totalAmountRaw,
        totalAmountMinor = totalAmountMinor,
        savedAtMillis = savedAtMillis,
    )
}

private fun ReceiptSyncQueueRecord.toEntity(): ReceiptSyncQueueEntity {
    return ReceiptSyncQueueEntity(
        queueId = queueId,
        idempotencyKey = idempotencyKey,
        receiptId = receiptId,
        operation = operation,
        payloadSnapshot = payloadSnapshot,
        status = status,
        attemptCount = attemptCount,
        lastError = lastError,
        queuedAtMillis = queuedAtMillis,
        nextRetryAtMillis = nextRetryAtMillis,
    )
}

private fun ReceiptSyncQueueEntity.toDomain(): ReceiptSyncQueueRecord {
    return ReceiptSyncQueueRecord(
        queueId = queueId,
        idempotencyKey = idempotencyKey,
        receiptId = receiptId,
        operation = operation,
        payloadSnapshot = payloadSnapshot,
        status = status,
        attemptCount = attemptCount,
        lastError = lastError,
        queuedAtMillis = queuedAtMillis,
        nextRetryAtMillis = nextRetryAtMillis,
    )
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `receipt_sync_queue_new` (
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
        database.execSQL(
            """
            INSERT INTO `receipt_sync_queue_new` (
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
            )
            SELECT
                `queueId`,
                `queueId`,
                `receiptId`,
                CASE
                    WHEN `operation` = 'receipt_upsert' THEN 'create'
                    ELSE `operation`
                END,
                '{"id":"' || `receiptId` || '"}',
                `status`,
                0,
                NULL,
                `queuedAtMillis`,
                NULL
            FROM `receipt_sync_queue`
            """.trimIndent(),
        )
        database.execSQL("DROP TABLE `receipt_sync_queue`")
        database.execSQL("ALTER TABLE `receipt_sync_queue_new` RENAME TO `receipt_sync_queue`")
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_receipt_sync_queue_receiptId`
            ON `receipt_sync_queue` (`receiptId`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_receipt_sync_queue_status`
            ON `receipt_sync_queue` (`status`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_receipt_sync_queue_nextRetryAtMillis`
            ON `receipt_sync_queue` (`nextRetryAtMillis`)
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `receipt_sync_state` (
                `stateKey` TEXT NOT NULL,
                `stateValue` TEXT NOT NULL,
                PRIMARY KEY(`stateKey`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT OR IGNORE INTO `receipt_sync_state` (`stateKey`, `stateValue`)
            VALUES ('$RECEIPT_SYNC_CURSOR_KEY', '$INITIAL_RECEIPT_SYNC_CURSOR')
            """.trimIndent(),
        )
    }
}
