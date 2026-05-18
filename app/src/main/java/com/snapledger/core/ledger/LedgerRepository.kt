package com.snapledger.core.ledger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.snapledger.core.sync.WorkManagerReviewSyncDispatcher
import com.snapledger.feature.review.data.ReceiptSyncStateEntity
import com.snapledger.feature.review.data.ReviewLocalDatabase
import com.snapledger.feature.review.data.RoomReviewSyncQueueStore
import com.snapledger.feature.review.data.toDomain
import com.snapledger.feature.review.data.toEntity
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import com.snapledger.feature.review.domain.ReviewSyncDispatcher
import com.snapledger.feature.review.domain.ReviewSyncQueueStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class LedgerTransactionType {
    EXPENSE,
    INCOME,
}

enum class LedgerTransactionSource {
    MANUAL,
    SCAN,
}

enum class LedgerBudgetPeriod {
    WEEKLY,
    MONTHLY,
}

enum class LedgerIncomePeriod {
    WEEKLY,
    MONTHLY,
    BOTH,
}

data class LedgerTransaction(
    val id: String,
    val type: LedgerTransactionType,
    val source: LedgerTransactionSource,
    val amount: Double,
    val merchant: String,
    val date: String,
    val note: String?,
    val category: String,
    val createdAtMillis: Long,
    val incomePeriod: LedgerIncomePeriod = LedgerIncomePeriod.BOTH,
)

data class LedgerBudgetCategory(
    val id: String,
    val name: String,
    val period: LedgerBudgetPeriod,
    val allocated: Double,
    val createdAtMillis: Long,
)

data class LedgerSnapshot(
    val transactions: List<LedgerTransaction> = emptyList(),
    val budgetCategories: List<LedgerBudgetCategory> = emptyList(),
)

interface LedgerRepository {
    val snapshotFlow: Flow<LedgerSnapshot>

    suspend fun saveTransaction(
        type: LedgerTransactionType,
        amount: Double,
        merchant: String,
        date: String,
        note: String?,
        category: String,
        incomePeriod: LedgerIncomePeriod = LedgerIncomePeriod.BOTH,
        transactionId: String? = null,
        source: LedgerTransactionSource = LedgerTransactionSource.MANUAL,
        syncToBackend: Boolean = true,
    ): LedgerTransaction

    suspend fun saveBudgetCategory(
        name: String,
        period: LedgerBudgetPeriod,
        allocated: Double,
    ): LedgerBudgetCategory

    suspend fun updateTransaction(
        id: String,
        amount: Double,
        merchant: String,
        date: String,
        note: String?,
        category: String,
    )

    suspend fun deleteTransaction(id: String)

    suspend fun updateBudgetCategory(
        id: String,
        name: String,
        allocated: Double,
    )

    suspend fun deleteBudgetCategory(id: String)
}

private val Context.snapLedgerLedgerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "snapledger_ledger",
)

class DataStoreLedgerRepository(
    private val database: ReviewLocalDatabase,
    private val legacyDataStore: DataStore<Preferences>,
    private val syncQueueStore: ReviewSyncQueueStore? = null,
    private val syncDispatcher: ReviewSyncDispatcher? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : LedgerRepository {
    private val migrationMutex = Mutex()

    override val snapshotFlow: Flow<LedgerSnapshot> = combine(
        database.ledgerTransactionDao().observeTransactions(),
        database.ledgerBudgetCategoryDao().observeBudgetCategories(),
    ) { transactions, budgets ->
        LedgerSnapshot(
            transactions = transactions.map { it.toDomain() },
            budgetCategories = budgets.map { it.toDomain() },
        )
    }.onStart {
        ensureMigrated()
    }

    override suspend fun saveTransaction(
        type: LedgerTransactionType,
        amount: Double,
        merchant: String,
        date: String,
        note: String?,
        category: String,
        incomePeriod: LedgerIncomePeriod,
        transactionId: String?,
        source: LedgerTransactionSource,
        syncToBackend: Boolean,
    ): LedgerTransaction {
        ensureMigrated()
        val transaction = LedgerTransaction(
            id = transactionId ?: idFactory(),
            type = type,
            source = source,
            amount = amount,
            merchant = merchant.trim(),
            date = date.trim(),
            note = note?.trim()?.ifBlank { null },
            category = category.trim(),
            createdAtMillis = clockMillis(),
            incomePeriod = incomePeriod,
        )
        database.ledgerTransactionDao().upsert(transaction.toEntity())
        enqueueExpenseMutation(
            transaction = transaction,
            operation = ReceiptSyncQueueRecord.OPERATION_CREATE,
            syncToBackend = syncToBackend,
        )
        return transaction
    }

    override suspend fun saveBudgetCategory(
        name: String,
        period: LedgerBudgetPeriod,
        allocated: Double,
    ): LedgerBudgetCategory {
        ensureMigrated()
        val category = LedgerBudgetCategory(
            id = idFactory(),
            name = name.trim(),
            period = period,
            allocated = allocated,
            createdAtMillis = clockMillis(),
        )
        database.ledgerBudgetCategoryDao().upsert(category.toEntity())
        return category
    }

    override suspend fun updateTransaction(
        id: String,
        amount: Double,
        merchant: String,
        date: String,
        note: String?,
        category: String,
    ) {
        ensureMigrated()
        val existing = database.ledgerTransactionDao().getById(id) ?: return
        val updated = existing.toDomain().copy(
            amount = amount,
            merchant = merchant.trim(),
            date = date.trim(),
            note = note?.trim()?.ifBlank { null },
            category = category.trim(),
        )
        database.ledgerTransactionDao().upsert(updated.toEntity())
        enqueueExpenseMutation(
            transaction = updated,
            operation = ReceiptSyncQueueRecord.OPERATION_UPDATE,
        )
    }

    override suspend fun deleteTransaction(id: String) {
        ensureMigrated()
        val deletedTransaction = database.ledgerTransactionDao().getById(id)?.toDomain()
        database.ledgerTransactionDao().deleteById(id)
        if (deletedTransaction?.type == LedgerTransactionType.EXPENSE) {
            enqueueDeleteMutation(deletedTransaction.id)
        }
    }

    override suspend fun updateBudgetCategory(
        id: String,
        name: String,
        allocated: Double,
    ) {
        ensureMigrated()
        val existing = database.ledgerBudgetCategoryDao().getById(id) ?: return
        val updated = existing.toDomain().copy(
            name = name.trim(),
            allocated = allocated,
        )
        database.ledgerBudgetCategoryDao().upsert(updated.toEntity())
    }

    override suspend fun deleteBudgetCategory(id: String) {
        ensureMigrated()
        database.ledgerBudgetCategoryDao().deleteById(id)
    }

    private suspend fun ensureMigrated() {
        migrationMutex.withLock {
            val stateDao = database.receiptSyncStateDao()
            if (stateDao.loadStateValue(LEDGER_MIGRATION_STATE_KEY) == LEDGER_MIGRATION_COMPLETE) {
                return
            }

            val preferences = legacyDataStore.data
                .catch { error ->
                    if (error is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw error
                    }
                }
                .first()

            val legacyTransactions = decodeTransactions(preferences[TRANSACTIONS_JSON].orEmpty())
            val legacyBudgets = decodeBudgetCategories(preferences[BUDGETS_JSON].orEmpty())

            database.withTransaction {
                if (legacyTransactions.isNotEmpty()) {
                    database.ledgerTransactionDao().upsertAll(
                        legacyTransactions.map(LedgerTransaction::toEntity),
                    )
                }
                if (legacyBudgets.isNotEmpty()) {
                    database.ledgerBudgetCategoryDao().upsertAll(
                        legacyBudgets.map(LedgerBudgetCategory::toEntity),
                    )
                }
                stateDao.upsertState(
                    ReceiptSyncStateEntity(
                        stateKey = LEDGER_MIGRATION_STATE_KEY,
                        stateValue = LEDGER_MIGRATION_COMPLETE,
                    ),
                )
            }
        }
    }

    private suspend fun enqueueExpenseMutation(
        transaction: LedgerTransaction,
        operation: String,
        syncToBackend: Boolean = true,
    ) {
        if (!syncToBackend || transaction.type != LedgerTransactionType.EXPENSE) {
            return
        }

        val queueRecord = ReceiptSyncQueueRecord(
            queueId = idFactory(),
            receiptId = transaction.id,
            operation = operation,
            payloadSnapshot = transaction.toSyncPayloadSnapshot(),
            queuedAtMillis = clockMillis(),
        )
        syncQueueStore?.enqueue(queueRecord)
        syncDispatcher?.dispatch(queueRecord)
    }

    private suspend fun enqueueDeleteMutation(receiptId: String) {
        val queueRecord = ReceiptSyncQueueRecord(
            queueId = idFactory(),
            receiptId = receiptId,
            operation = ReceiptSyncQueueRecord.OPERATION_DELETE,
            payloadSnapshot = JSONObject()
                .put("id", receiptId)
                .toString(),
            queuedAtMillis = clockMillis(),
        )
        syncQueueStore?.enqueue(queueRecord)
        syncDispatcher?.dispatch(queueRecord)
    }

    companion object {
        fun getInstance(context: Context): DataStoreLedgerRepository {
            val appContext = context.applicationContext
            return DataStoreLedgerRepository(
                database = ReviewLocalDatabase.getInstance(appContext),
                legacyDataStore = appContext.snapLedgerLedgerDataStore,
                syncQueueStore = RoomReviewSyncQueueStore(ReviewLocalDatabase.getInstance(appContext)),
                syncDispatcher = WorkManagerReviewSyncDispatcher(appContext),
            )
        }

        private val TRANSACTIONS_JSON = stringPreferencesKey("transactions_json")
        private val BUDGETS_JSON = stringPreferencesKey("budgets_json")
        private const val LEDGER_MIGRATION_COMPLETE = "done"
        private const val LEDGER_MIGRATION_STATE_KEY = "ledger_datastore_migration_v1"
    }
}

private fun encodeTransactions(transactions: List<LedgerTransaction>): String {
    return JSONArray().apply {
        transactions.forEach { transaction ->
            put(
                JSONObject()
                    .put("id", transaction.id)
                    .put("type", transaction.type.name)
                    .put("source", transaction.source.name)
                    .put("amount", transaction.amount)
                    .put("merchant", transaction.merchant)
                    .put("date", transaction.date)
                    .put("note", transaction.note)
                    .put("category", transaction.category)
                    .put("createdAtMillis", transaction.createdAtMillis)
                    .put("incomePeriod", transaction.incomePeriod.name),
            )
        }
    }.toString()
}

private fun decodeTransactions(json: String): List<LedgerTransaction> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    LedgerTransaction(
                        id = item.getString("id"),
                        type = LedgerTransactionType.valueOf(
                            item.optString("type", LedgerTransactionType.EXPENSE.name),
                        ),
                        source = LedgerTransactionSource.valueOf(
                            item.optString("source", LedgerTransactionSource.MANUAL.name),
                        ),
                        amount = item.getDouble("amount"),
                        merchant = item.getString("merchant"),
                        date = item.getString("date"),
                        note = item.optString("note").ifBlank { null },
                        category = item.getString("category"),
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                        incomePeriod = LedgerIncomePeriod.valueOf(
                            item.optString("incomePeriod", LedgerIncomePeriod.BOTH.name),
                        ),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun LedgerTransaction.toSyncPayloadSnapshot(): String {
    return JSONObject()
        .put("id", id)
        .put("source", source.name.lowercase())
        .put("merchant", merchant)
        .put("expense_date", date)
        .put("total_amount", amount.toSyncAmountString())
        .put("currency", "PHP")
        .put("notes", note)
        .put("items", JSONArray())
        .toString()
}

private fun Double.toSyncAmountString(): String {
    return String.format(java.util.Locale.US, "%.2f", this)
}

private fun encodeBudgetCategories(categories: List<LedgerBudgetCategory>): String {
    return JSONArray().apply {
        categories.forEach { category ->
            put(
                JSONObject()
                    .put("id", category.id)
                    .put("name", category.name)
                    .put("period", category.period.name)
                    .put("allocated", category.allocated)
                    .put("createdAtMillis", category.createdAtMillis),
            )
        }
    }.toString()
}

private fun decodeBudgetCategories(json: String): List<LedgerBudgetCategory> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    LedgerBudgetCategory(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        period = LedgerBudgetPeriod.valueOf(
                            item.optString("period", LedgerBudgetPeriod.MONTHLY.name),
                        ),
                        allocated = item.getDouble("allocated"),
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
