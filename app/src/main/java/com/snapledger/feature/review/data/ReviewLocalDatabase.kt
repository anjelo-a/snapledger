package com.snapledger.feature.review.data

import android.content.Context
import androidx.room.ColumnInfo
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
import com.snapledger.core.ledger.LedgerBudgetCategory
import com.snapledger.core.ledger.LedgerBudgetPeriod
import com.snapledger.core.ledger.LedgerIncomePeriod
import com.snapledger.core.ledger.LedgerTransaction
import com.snapledger.core.ledger.LedgerTransactionSource
import com.snapledger.core.ledger.LedgerTransactionType
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
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord.Companion.STATUS_TERMINAL_FAILED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToLong

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

@Entity(
    tableName = "ledger_transactions",
    indices = [
        Index("type"),
        Index("category"),
        Index("date"),
        Index("createdAtMillis"),
    ],
)
data class LedgerTransactionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val source: String,
    val amountMinor: Long,
    val merchant: String,
    val date: String,
    val note: String?,
    val category: String,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "'BOTH'")
    val incomePeriod: String,
)

@Entity(
    tableName = "ledger_budget_categories",
    indices = [
        Index("period"),
        Index("name"),
    ],
)
data class LedgerBudgetCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val period: String,
    val allocatedMinor: Long,
    val createdAtMillis: Long,
)

@Dao
interface LocalReceiptDao {
    @Query("SELECT * FROM local_receipts")
    suspend fun getAllReceipts(): List<LocalReceiptEntity>

    @Query("SELECT * FROM local_receipt_items")
    suspend fun getAllItems(): List<LocalReceiptItemEntity>

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
    @Query("SELECT * FROM receipt_sync_queue")
    suspend fun getAllQueueRecords(): List<ReceiptSyncQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueRecord(entity: ReceiptSyncQueueEntity)

    @Query(
        """
        SELECT * FROM receipt_sync_queue
        WHERE status = :pendingStatus
            OR (
                status = :failedStatus
                AND nextRetryAtMillis IS NOT NULL
                AND nextRetryAtMillis <= :nowMillis
            )
        ORDER BY queuedAtMillis ASC, queueId ASC
        LIMIT :limit
        """
    )
    suspend fun loadDueQueueRecords(
        pendingStatus: String = STATUS_PENDING,
        failedStatus: String = STATUS_FAILED,
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
        UPDATE receipt_sync_queue
        SET status = :status,
            lastError = :lastError,
            nextRetryAtMillis = NULL
        WHERE queueId = :queueId
        """
    )
    suspend fun markTerminalFailed(
        queueId: String,
        lastError: String,
        status: String = STATUS_TERMINAL_FAILED,
    )

    @Query(
        """
        SELECT COUNT(*) > 0 FROM receipt_sync_queue
        WHERE receiptId = :receiptId
            AND (
                status IN (:blockingStatuses)
                OR (status = :failedStatus AND nextRetryAtMillis IS NOT NULL)
            )
        """
    )
    suspend fun hasPendingMutation(
        receiptId: String,
        blockingStatuses: List<String>,
        failedStatus: String = STATUS_FAILED,
    ): Boolean
}

@Dao
interface ReceiptSyncStateDao {
    @Query("SELECT * FROM receipt_sync_state")
    suspend fun getAllStates(): List<ReceiptSyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(entity: ReceiptSyncStateEntity)

    @Query("SELECT stateValue FROM receipt_sync_state WHERE stateKey = :stateKey LIMIT 1")
    suspend fun loadStateValue(stateKey: String): String?
}

@Dao
interface LedgerTransactionDao {
    @Query("SELECT * FROM ledger_transactions")
    suspend fun getAll(): List<LedgerTransactionEntity>

    @Query("SELECT * FROM ledger_transactions ORDER BY createdAtMillis ASC, id ASC")
    fun observeTransactions(): Flow<List<LedgerTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LedgerTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LedgerTransactionEntity>)

    @Query("SELECT * FROM ledger_transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LedgerTransactionEntity?

    @Query("DELETE FROM ledger_transactions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface LedgerBudgetCategoryDao {
    @Query("SELECT * FROM ledger_budget_categories")
    suspend fun getAll(): List<LedgerBudgetCategoryEntity>

    @Query("SELECT * FROM ledger_budget_categories ORDER BY createdAtMillis ASC, id ASC")
    fun observeBudgetCategories(): Flow<List<LedgerBudgetCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LedgerBudgetCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<LedgerBudgetCategoryEntity>)

    @Query("SELECT * FROM ledger_budget_categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LedgerBudgetCategoryEntity?

    @Query("DELETE FROM ledger_budget_categories WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(
    entities = [
        LocalReceiptEntity::class,
        LocalReceiptItemEntity::class,
        ReceiptSyncQueueEntity::class,
        ReceiptSyncStateEntity::class,
        LedgerTransactionEntity::class,
        LedgerBudgetCategoryEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ReviewLocalDatabase : RoomDatabase() {
    abstract fun localReceiptDao(): LocalReceiptDao
    abstract fun receiptSyncQueueDao(): ReceiptSyncQueueDao
    abstract fun receiptSyncStateDao(): ReceiptSyncStateDao
    abstract fun ledgerTransactionDao(): LedgerTransactionDao
    abstract fun ledgerBudgetCategoryDao(): LedgerBudgetCategoryDao

    companion object {
        @Volatile
        private var legacyInstance: ReviewLocalDatabase? = null
        private val profileInstances = mutableMapOf<String, ReviewLocalDatabase>()

        fun getInstance(
            context: Context,
            profileId: String,
        ): ReviewLocalDatabase {
            val appContext = context.applicationContext
            return synchronized(this) {
                profileInstances[profileId] ?: buildProfileDatabase(
                    context = appContext,
                    profileId = profileId,
                ).also { profileInstances[profileId] = it }
            }
        }

        private fun buildProfileDatabase(
            context: Context,
            profileId: String,
        ): ReviewLocalDatabase {
            val databaseName = profileDatabaseName(profileId)
            val profileDbPath = context.getDatabasePath(databaseName)
            val shouldSeedFromLegacy = !profileDbPath.exists() && context.getDatabasePath(LEGACY_DATABASE_NAME).exists()
            val database = buildDatabase(
                context = context,
                databaseName = databaseName,
            )
            if (shouldSeedFromLegacy) {
                seedProfileDatabaseFromLegacy(
                    profileDatabase = database,
                    legacyDatabase = getLegacySharedInstance(context),
                )
            }
            return database
        }

        private fun getLegacySharedInstance(context: Context): ReviewLocalDatabase {
            return legacyInstance ?: synchronized(this) {
                legacyInstance ?: buildDatabase(
                    context = context.applicationContext,
                    databaseName = LEGACY_DATABASE_NAME,
                ).also { legacyInstance = it }
            }
        }

        private fun buildDatabase(
            context: Context,
            databaseName: String,
        ): ReviewLocalDatabase {
            return Room.databaseBuilder(
                context,
                ReviewLocalDatabase::class.java,
                databaseName,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }

        private fun seedProfileDatabaseFromLegacy(
            profileDatabase: ReviewLocalDatabase,
            legacyDatabase: ReviewLocalDatabase,
        ) {
            runBlocking {
                profileDatabase.withTransaction {
                    val localReceipts = legacyDatabase.localReceiptDao().getAllReceipts()
                    val receiptItems = legacyDatabase.localReceiptDao().getAllItems()
                    val queueRecords = legacyDatabase.receiptSyncQueueDao().getAllQueueRecords()
                    val states = legacyDatabase.receiptSyncStateDao().getAllStates()
                    val transactions = legacyDatabase.ledgerTransactionDao().getAll()
                    val budgetCategories = legacyDatabase.ledgerBudgetCategoryDao().getAll()

                    localReceipts.forEach { receipt ->
                        profileDatabase.localReceiptDao().insertReceipt(receipt)
                    }
                    if (receiptItems.isNotEmpty()) {
                        profileDatabase.localReceiptDao().insertItems(receiptItems)
                    }
                    queueRecords.forEach { queueRecord ->
                        profileDatabase.receiptSyncQueueDao().insertQueueRecord(queueRecord)
                    }
                    states.forEach { state ->
                        profileDatabase.receiptSyncStateDao().upsertState(state)
                    }
                    if (transactions.isNotEmpty()) {
                        profileDatabase.ledgerTransactionDao().upsertAll(transactions)
                    }
                    if (budgetCategories.isNotEmpty()) {
                        profileDatabase.ledgerBudgetCategoryDao().upsertAll(budgetCategories)
                    }
                }
            }
        }

        private fun profileDatabaseName(profileId: String): String {
            val normalizedProfileId = profileId.trim()
                .ifBlank { "default" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            return "snapledger-review-$normalizedProfileId.db"
        }

        private const val LEGACY_DATABASE_NAME = "snapledger-review.db"
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

    override suspend fun markTerminalFailed(
        queueId: String,
        lastError: String,
    ) {
        database.receiptSyncQueueDao().markTerminalFailed(
            queueId = queueId,
            lastError = lastError,
        )
    }

    override suspend fun hasPendingMutation(receiptId: String): Boolean {
        return database.receiptSyncQueueDao().hasPendingMutation(
            receiptId = receiptId,
            blockingStatuses = listOf(STATUS_PENDING, STATUS_IN_FLIGHT),
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ledger_transactions` (
                `id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL,
                `merchant` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                `note` TEXT,
                `category` TEXT NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_ledger_transactions_type`
            ON `ledger_transactions` (`type`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_ledger_transactions_category`
            ON `ledger_transactions` (`category`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_ledger_transactions_date`
            ON `ledger_transactions` (`date`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_ledger_transactions_createdAtMillis`
            ON `ledger_transactions` (`createdAtMillis`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ledger_budget_categories` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `period` TEXT NOT NULL,
                `allocatedMinor` INTEGER NOT NULL,
                `createdAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_ledger_budget_categories_period`
            ON `ledger_budget_categories` (`period`)
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_ledger_budget_categories_name`
            ON `ledger_budget_categories` (`name`)
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE `ledger_transactions`
            ADD COLUMN `incomePeriod` TEXT NOT NULL DEFAULT '${LedgerIncomePeriod.BOTH.name}'
            """.trimIndent(),
        )
    }
}

fun LedgerTransactionEntity.toDomain(): LedgerTransaction {
    return LedgerTransaction(
        id = id,
        type = LedgerTransactionType.valueOf(type),
        source = LedgerTransactionSource.valueOf(source),
        amount = amountMinor.toDouble() / 100.0,
        merchant = merchant,
        date = date,
        note = note,
        category = category,
        createdAtMillis = createdAtMillis,
        incomePeriod = LedgerIncomePeriod.valueOf(incomePeriod),
    )
}

fun LedgerTransaction.toEntity(): LedgerTransactionEntity {
    return LedgerTransactionEntity(
        id = id,
        type = type.name,
        source = source.name,
        amountMinor = (amount * 100.0).roundToLong(),
        merchant = merchant,
        date = date,
        note = note,
        category = category,
        createdAtMillis = createdAtMillis,
        incomePeriod = incomePeriod.name,
    )
}

fun LedgerBudgetCategoryEntity.toDomain(): LedgerBudgetCategory {
    return LedgerBudgetCategory(
        id = id,
        name = name,
        period = LedgerBudgetPeriod.valueOf(period),
        allocated = allocatedMinor.toDouble() / 100.0,
        createdAtMillis = createdAtMillis,
    )
}

fun LedgerBudgetCategory.toEntity(): LedgerBudgetCategoryEntity {
    return LedgerBudgetCategoryEntity(
        id = id,
        name = name,
        period = period.name,
        allocatedMinor = (allocated * 100.0).roundToLong(),
        createdAtMillis = createdAtMillis,
    )
}
