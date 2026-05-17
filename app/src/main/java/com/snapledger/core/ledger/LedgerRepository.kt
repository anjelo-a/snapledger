package com.snapledger.core.ledger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class LedgerTransactionType {
    EXPENSE,
    INCOME,
}

enum class LedgerBudgetPeriod {
    WEEKLY,
    MONTHLY,
}

data class LedgerTransaction(
    val id: String,
    val type: LedgerTransactionType,
    val amount: Double,
    val merchant: String,
    val date: String,
    val note: String?,
    val category: String,
    val createdAtMillis: Long,
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
    ): LedgerTransaction

    suspend fun saveBudgetCategory(
        name: String,
        period: LedgerBudgetPeriod,
        allocated: Double,
    ): LedgerBudgetCategory

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
    private val dataStore: DataStore<Preferences>,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : LedgerRepository {
    override val snapshotFlow: Flow<LedgerSnapshot> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            LedgerSnapshot(
                transactions = decodeTransactions(preferences[TRANSACTIONS_JSON].orEmpty()),
                budgetCategories = decodeBudgetCategories(preferences[BUDGETS_JSON].orEmpty()),
            )
        }

    override suspend fun saveTransaction(
        type: LedgerTransactionType,
        amount: Double,
        merchant: String,
        date: String,
        note: String?,
        category: String,
    ): LedgerTransaction {
        val transaction = LedgerTransaction(
            id = idFactory(),
            type = type,
            amount = amount,
            merchant = merchant.trim(),
            date = date.trim(),
            note = note?.trim()?.ifBlank { null },
            category = category.trim(),
            createdAtMillis = clockMillis(),
        )
        dataStore.edit { preferences ->
            val transactions = decodeTransactions(preferences[TRANSACTIONS_JSON].orEmpty())
            preferences[TRANSACTIONS_JSON] = encodeTransactions(transactions + transaction)
        }
        return transaction
    }

    override suspend fun saveBudgetCategory(
        name: String,
        period: LedgerBudgetPeriod,
        allocated: Double,
    ): LedgerBudgetCategory {
        val category = LedgerBudgetCategory(
            id = idFactory(),
            name = name.trim(),
            period = period,
            allocated = allocated,
            createdAtMillis = clockMillis(),
        )
        dataStore.edit { preferences ->
            val categories = decodeBudgetCategories(preferences[BUDGETS_JSON].orEmpty())
            preferences[BUDGETS_JSON] = encodeBudgetCategories(categories + category)
        }
        return category
    }

    override suspend fun updateBudgetCategory(
        id: String,
        name: String,
        allocated: Double,
    ) {
        dataStore.edit { preferences ->
            val categories = decodeBudgetCategories(preferences[BUDGETS_JSON].orEmpty())
            preferences[BUDGETS_JSON] = encodeBudgetCategories(
                categories.map { category ->
                    if (category.id == id) category.copy(name = name.trim(), allocated = allocated) else category
                },
            )
        }
    }

    override suspend fun deleteBudgetCategory(id: String) {
        dataStore.edit { preferences ->
            val categories = decodeBudgetCategories(preferences[BUDGETS_JSON].orEmpty())
            preferences[BUDGETS_JSON] = encodeBudgetCategories(categories.filterNot { it.id == id })
        }
    }

    companion object {
        fun getInstance(context: Context): DataStoreLedgerRepository {
            return DataStoreLedgerRepository(context.snapLedgerLedgerDataStore)
        }

        private val TRANSACTIONS_JSON = stringPreferencesKey("transactions_json")
        private val BUDGETS_JSON = stringPreferencesKey("budgets_json")
    }
}

private fun encodeTransactions(transactions: List<LedgerTransaction>): String {
    return JSONArray().apply {
        transactions.forEach { transaction ->
            put(
                JSONObject()
                    .put("id", transaction.id)
                    .put("type", transaction.type.name)
                    .put("amount", transaction.amount)
                    .put("merchant", transaction.merchant)
                    .put("date", transaction.date)
                    .put("note", transaction.note)
                    .put("category", transaction.category)
                    .put("createdAtMillis", transaction.createdAtMillis),
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
                        type = LedgerTransactionType.valueOf(item.optString("type", LedgerTransactionType.EXPENSE.name)),
                        amount = item.getDouble("amount"),
                        merchant = item.getString("merchant"),
                        date = item.getString("date"),
                        note = item.optString("note").ifBlank { null },
                        category = item.getString("category"),
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
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
                        period = LedgerBudgetPeriod.valueOf(item.optString("period", LedgerBudgetPeriod.MONTHLY.name)),
                        allocated = item.getDouble("allocated"),
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
