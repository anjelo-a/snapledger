package com.snapledger.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.snapledger.feature.review.data.ReviewLocalDatabase
import com.snapledger.feature.review.data.RoomReviewSyncQueueStore
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import com.snapledger.feature.review.domain.ReviewSyncDispatcher
import java.util.concurrent.TimeUnit

interface ReceiptSyncMutationStore {
    suspend fun loadDueMutations(nowMillis: Long, limit: Int): List<ReceiptSyncQueueRecord>

    suspend fun markInFlight(queueIds: List<String>)

    suspend fun markSynced(queueId: String)

    suspend fun markFailed(
        queueId: String,
        lastError: String,
        nextRetryAtMillis: Long?,
    )
}

interface ReceiptSyncPushGateway {
    suspend fun push(request: ReceiptSyncPushRequest): ReceiptSyncPushResponse
}

class NetworkReceiptSyncPushGateway(
    private val remoteDataSource: ReceiptSyncRemoteDataSource,
) : ReceiptSyncPushGateway {
    override suspend fun push(request: ReceiptSyncPushRequest): ReceiptSyncPushResponse {
        return remoteDataSource.push(request)
    }
}

data class ReceiptSyncPushProcessorResult(
    val processedCount: Int,
    val shouldRetry: Boolean,
)

class ReceiptSyncPushProcessor(
    private val mutationStore: ReceiptSyncMutationStore,
    private val pushGateway: ReceiptSyncPushGateway,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun pushDueMutations(
        limit: Int = RECEIPT_SYNC_PUSH_BATCH_LIMIT,
    ): ReceiptSyncPushProcessorResult {
        val now = clock()
        val dueRecords = mutationStore.loadDueMutations(
            nowMillis = now,
            limit = limit,
        )
        if (dueRecords.isEmpty()) {
            return ReceiptSyncPushProcessorResult(
                processedCount = 0,
                shouldRetry = false,
            )
        }

        mutationStore.markInFlight(dueRecords.map(ReceiptSyncQueueRecord::queueId))

        val pushMutations = mutableListOf<ReceiptSyncPushMutation>()
        val preparedRecordsByIdempotencyKey = linkedMapOf<String, PreparedQueueRecord>()
        var shouldRetry = false

        dueRecords.forEach { record ->
            try {
                val attemptCount = record.attemptCount + 1
                val mutation = record.toPushMutation()
                pushMutations += mutation
                preparedRecordsByIdempotencyKey[record.idempotencyKey] = PreparedQueueRecord(
                    record = record,
                    attemptCount = attemptCount,
                )
            } catch (error: Exception) {
                shouldRetry = true
                mutationStore.markFailed(
                    queueId = record.queueId,
                    lastError = error.message ?: "Failed to prepare receipt sync payload.",
                    nextRetryAtMillis = computeNextRetryAtMillis(
                        nowMillis = now,
                        attemptCount = record.attemptCount + 1,
                    ),
                )
            }
        }

        if (pushMutations.isEmpty()) {
            return ReceiptSyncPushProcessorResult(
                processedCount = dueRecords.size,
                shouldRetry = shouldRetry,
            )
        }

        return try {
            val response = pushGateway.push(
                ReceiptSyncPushRequest(mutations = pushMutations),
            )
            val resultsByIdempotencyKey = response.results.associateBy { it.idempotencyKey }

            preparedRecordsByIdempotencyKey.values.forEach { prepared ->
                val responseResult = resultsByIdempotencyKey[prepared.record.idempotencyKey]
                if (responseResult == null) {
                    shouldRetry = true
                    mutationStore.markFailed(
                        queueId = prepared.record.queueId,
                        lastError = "Backend response omitted sync result.",
                        nextRetryAtMillis = computeNextRetryAtMillis(
                            nowMillis = now,
                            attemptCount = prepared.attemptCount,
                        ),
                    )
                } else if (responseResult.status == "accepted") {
                    mutationStore.markSynced(prepared.record.queueId)
                } else {
                    shouldRetry = true
                    mutationStore.markFailed(
                        queueId = prepared.record.queueId,
                        lastError = responseResult.message
                            ?: responseResult.code
                            ?: "Backend rejected receipt sync mutation.",
                        nextRetryAtMillis = computeNextRetryAtMillis(
                            nowMillis = now,
                            attemptCount = prepared.attemptCount,
                        ),
                    )
                }
            }

            ReceiptSyncPushProcessorResult(
                processedCount = pushMutations.size,
                shouldRetry = shouldRetry,
            )
        } catch (error: Exception) {
            val errorMessage = error.message ?: "Receipt sync push failed."
            preparedRecordsByIdempotencyKey.values.forEach { prepared ->
                mutationStore.markFailed(
                    queueId = prepared.record.queueId,
                    lastError = errorMessage,
                    nextRetryAtMillis = computeNextRetryAtMillis(
                        nowMillis = now,
                        attemptCount = prepared.attemptCount,
                    ),
                )
            }

            ReceiptSyncPushProcessorResult(
                processedCount = pushMutations.size,
                shouldRetry = true,
            )
        }
    }

    private data class PreparedQueueRecord(
        val record: ReceiptSyncQueueRecord,
        val attemptCount: Int,
    )
}

class WorkManagerReviewSyncDispatcher(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
) : ReviewSyncDispatcher {
    override suspend fun dispatch(record: ReceiptSyncQueueRecord) {
        workManager.enqueueUniqueWork(
            ReceiptSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReceiptSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RECEIPT_SYNC_WORK_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build(),
        )
    }
}

class ReceiptSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val dependencies = dependencyFactory?.invoke(applicationContext)
            ?: buildDefaultDependencies(applicationContext)
        val result = ReceiptSyncPushProcessor(
            mutationStore = dependencies.mutationStore,
            pushGateway = dependencies.pushGateway,
            clock = dependencies.clock,
        ).pushDueMutations()

        return if (result.shouldRetry) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "receipt-sync-push"

        internal var dependencyFactory: ((Context) -> ReceiptSyncWorkerDependencies)? = null

        private fun buildDefaultDependencies(
            context: Context,
        ): ReceiptSyncWorkerDependencies {
            val database = ReviewLocalDatabase.getInstance(context.applicationContext)
            return ReceiptSyncWorkerDependencies(
                mutationStore = RoomReviewSyncQueueStore(database),
                pushGateway = NetworkReceiptSyncPushGateway(
                    ReceiptSyncRemoteDataSource.create(),
                ),
            )
        }
    }
}

data class ReceiptSyncWorkerDependencies(
    val mutationStore: ReceiptSyncMutationStore,
    val pushGateway: ReceiptSyncPushGateway,
    val clock: () -> Long = { System.currentTimeMillis() },
)

private fun ReceiptSyncQueueRecord.toPushMutation(): ReceiptSyncPushMutation {
    return when (operation) {
        ReceiptSyncQueueRecord.OPERATION_CREATE -> {
            ReceiptSyncPushMutation(
                idempotencyKey = idempotencyKey,
                operation = ReceiptSyncPushOperation.Create,
                occurredAt = queuedAtMillis.toIsoInstantString(),
                payload = payloadSnapshot
                    .toStoredUpsertPayload()
                    .validate()
                    .toDomain(id = receiptId),
            )
        }

        ReceiptSyncQueueRecord.OPERATION_UPDATE -> {
            ReceiptSyncPushMutation(
                idempotencyKey = idempotencyKey,
                operation = ReceiptSyncPushOperation.Update,
                occurredAt = queuedAtMillis.toIsoInstantString(),
                payload = payloadSnapshot
                    .toStoredUpsertPayload()
                    .validate()
                    .toDomain(id = receiptId),
            )
        }

        ReceiptSyncQueueRecord.OPERATION_DELETE -> {
            ReceiptSyncPushMutation(
                idempotencyKey = idempotencyKey,
                operation = ReceiptSyncPushOperation.Delete,
                occurredAt = queuedAtMillis.toIsoInstantString(),
                payload = ReceiptSyncDeletePayload(
                    id = payloadSnapshot.toStoredDeletePayload().id.requireNotBlank("id"),
                ),
            )
        }

        else -> error("Unsupported receipt sync operation: $operation")
    }
}

private fun String.toStoredUpsertPayload(): StoredReceiptSyncUpsertPayload {
    return requireNotNull(storedUpsertPayloadAdapter.fromJson(this)) {
        "Receipt sync payload snapshot is missing required upsert fields."
    }
}

private fun String.toStoredDeletePayload(): StoredReceiptSyncDeletePayload {
    return requireNotNull(storedDeletePayloadAdapter.fromJson(this)) {
        "Receipt sync payload snapshot is missing required delete fields."
    }
}

private fun StoredReceiptSyncUpsertPayload.toDomain(id: String): ReceiptSyncUpsertPayload {
    return ReceiptSyncUpsertPayload(
        id = id,
        source = source,
        merchant = merchant,
        expenseDate = expenseDate,
        totalAmount = totalAmount,
        currency = currency.ifBlank { "PHP" },
        categoryId = categoryId,
        notes = notes,
        items = items.map(StoredReceiptSyncExpenseItem::toDomain),
    )
}

private fun StoredReceiptSyncExpenseItem.toDomain(): ReceiptSyncExpenseItem {
    return ReceiptSyncExpenseItem(
        name = name,
        amount = amount,
    )
}

@JsonClass(generateAdapter = true)
internal data class StoredReceiptSyncUpsertPayload(
    val source: String,
    val merchant: String,
    @Json(name = "expense_date")
    val expenseDate: String,
    @Json(name = "total_amount")
    val totalAmount: String,
    val currency: String = "PHP",
    @Json(name = "category_id")
    val categoryId: String? = null,
    val notes: String? = null,
    val items: List<StoredReceiptSyncExpenseItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
internal data class StoredReceiptSyncExpenseItem(
    val name: String,
    val amount: String,
)

@JsonClass(generateAdapter = true)
internal data class StoredReceiptSyncDeletePayload(
    val id: String,
)

private val storedPayloadMoshi: Moshi = Moshi.Builder().build()
private val storedUpsertPayloadAdapter = storedPayloadMoshi.adapter(
    StoredReceiptSyncUpsertPayload::class.java,
)
private val storedDeletePayloadAdapter = storedPayloadMoshi.adapter(
    StoredReceiptSyncDeletePayload::class.java,
)

private fun String.requireNotBlank(fieldName: String): String {
    return if (isBlank()) {
        error("Receipt sync payload snapshot field $fieldName must not be blank.")
    } else {
        this
    }
}

private fun StoredReceiptSyncUpsertPayload.validate(): StoredReceiptSyncUpsertPayload = apply {
    source.requireNotBlank("source")
    merchant.requireNotBlank("merchant")
    expenseDate.requireNotBlank("expense_date")
    totalAmount.requireNotBlank("total_amount")
    currency.requireNotBlank("currency")
}

private fun Long.toIsoInstantString(): String {
    return java.time.Instant.ofEpochMilli(this).toString()
}

private fun computeNextRetryAtMillis(
    nowMillis: Long,
    attemptCount: Int,
): Long {
    val multiplier = 1L shl (attemptCount - 1).coerceAtLeast(0)
    val backoffMillis = RECEIPT_SYNC_WORK_BACKOFF_MILLIS
        .times(multiplier)
        .coerceAtMost(TimeUnit.HOURS.toMillis(24))
    return nowMillis + backoffMillis
}

private const val RECEIPT_SYNC_PUSH_BATCH_LIMIT = 20
private const val RECEIPT_SYNC_WORK_BACKOFF_MILLIS = 30_000L
