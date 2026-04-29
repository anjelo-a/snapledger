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
import com.snapledger.feature.review.domain.LocalReceiptItemRecord
import com.snapledger.feature.review.domain.LocalReceiptRecord
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import com.snapledger.feature.review.domain.ReviewLocalReceiptStore
import com.snapledger.feature.review.domain.ReviewSyncDispatcher
import com.snapledger.feature.review.domain.ReviewSyncQueueStore

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
    indices = [Index("receiptId")],
)
data class ReceiptSyncQueueEntity(
    @PrimaryKey val queueId: String,
    val receiptId: String,
    val queuedAtMillis: Long,
    val operation: String,
    val status: String,
)

@Dao
interface LocalReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceipt(entity: LocalReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(entities: List<LocalReceiptItemEntity>)

    @Query("DELETE FROM local_receipt_items WHERE receiptId = :receiptId")
    suspend fun deleteItems(receiptId: String)

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
}

@Dao
interface ReceiptSyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueueRecord(entity: ReceiptSyncQueueEntity)
}

@Database(
    entities = [
        LocalReceiptEntity::class,
        LocalReceiptItemEntity::class,
        ReceiptSyncQueueEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ReviewLocalDatabase : RoomDatabase() {
    abstract fun localReceiptDao(): LocalReceiptDao
    abstract fun receiptSyncQueueDao(): ReceiptSyncQueueDao

    companion object {
        @Volatile
        private var instance: ReviewLocalDatabase? = null

        fun getInstance(context: Context): ReviewLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context,
                    ReviewLocalDatabase::class.java,
                    "snapledger-review.db",
                ).build().also { instance = it }
            }
        }
    }
}

class RoomReviewLocalReceiptStore(
    private val database: ReviewLocalDatabase,
) : ReviewLocalReceiptStore {
    override suspend fun saveReceipt(record: LocalReceiptRecord) {
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
}

class RoomReviewSyncQueueStore(
    private val database: ReviewLocalDatabase,
) : ReviewSyncQueueStore {
    override suspend fun enqueue(record: ReceiptSyncQueueRecord) {
        database.receiptSyncQueueDao().insertQueueRecord(
            ReceiptSyncQueueEntity(
                queueId = record.queueId,
                receiptId = record.receiptId,
                queuedAtMillis = record.queuedAtMillis,
                operation = record.operation,
                status = record.status,
            ),
        )
    }
}

object NoOpReviewSyncDispatcher : ReviewSyncDispatcher {
    override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
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
