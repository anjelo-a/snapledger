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
import com.snapledger.feature.review.data.RoomReceiptSyncCursorStore
import com.snapledger.feature.review.data.RoomReviewLocalReceiptStore
import com.snapledger.feature.review.data.RoomReviewSyncQueueStore
import com.snapledger.feature.review.domain.LocalReceiptItemRecord
import com.snapledger.feature.review.domain.LocalReceiptRecord
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import com.snapledger.feature.review.domain.ReviewSyncDispatcher
import java.time.Instant
import java.util.concurrent.TimeUnit
import retrofit2.HttpException

const val RECEIPT_SYNC_CURSOR_KEY = "pull_cursor"

interface ReceiptSyncMutationStore {
    suspend fun loadDueMutations(nowMillis: Long, limit: Int): List<ReceiptSyncQueueRecord>

    suspend fun markInFlight(queueIds: List<String>)

    suspend fun markSynced(queueId: String)

    suspend fun markFailed(
        queueId: String,
        lastError: String,
        nextRetryAtMillis: Long?,
    )

    suspend fun markTerminalFailed(
        queueId: String,
        lastError: String,
    )

    suspend fun hasPendingMutation(receiptId: String): Boolean
}

interface ReceiptSyncPushGateway {
    suspend fun push(request: ReceiptSyncPushRequest): ReceiptSyncPushResponse
}

interface ReceiptSyncPullGateway {
    suspend fun pull(cursor: String): ReceiptSyncPullResponse
}

interface ReceiptSyncLocalStore {
    suspend fun upsertReceipt(record: LocalReceiptRecord)

    suspend fun deleteReceipt(receiptId: String)
}

interface ReceiptSyncCursorStore {
    suspend fun readCursor(): String

    suspend fun writeCursor(cursor: String)
}

interface ReceiptSyncPendingMutationStore {
    suspend fun hasPendingMutation(receiptId: String): Boolean
}

class NetworkReceiptSyncPushGateway(
    private val remoteDataSource: ReceiptSyncRemoteDataSource,
) : ReceiptSyncPushGateway {
    override suspend fun push(request: ReceiptSyncPushRequest): ReceiptSyncPushResponse {
        return remoteDataSource.push(request)
    }
}

class NetworkReceiptSyncPullGateway(
    private val remoteDataSource: ReceiptSyncRemoteDataSource,
) : ReceiptSyncPullGateway {
    override suspend fun pull(cursor: String): ReceiptSyncPullResponse {
        return remoteDataSource.pull(cursor)
    }
}

data class ReceiptSyncPushProcessorResult(
    val processedCount: Int,
    val shouldRetry: Boolean,
)

data class ReceiptSyncPullProcessorResult(
    val appliedCount: Int,
    val skippedCount: Int,
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
                val lastError = error.message ?: "Failed to prepare receipt sync payload."
                if (error.isTerminalSyncFailure()) {
                    mutationStore.markTerminalFailed(
                        queueId = record.queueId,
                        lastError = lastError,
                    )
                } else {
                    shouldRetry = true
                    mutationStore.markFailed(
                        queueId = record.queueId,
                        lastError = lastError,
                        nextRetryAtMillis = computeNextRetryAtMillis(
                            nowMillis = now,
                            attemptCount = record.attemptCount + 1,
                        ),
                    )
                }
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
                    mutationStore.markTerminalFailed(
                        queueId = prepared.record.queueId,
                        lastError = responseResult.message
                            ?: responseResult.code
                            ?: "Backend rejected receipt sync mutation.",
                    )
                }
            }

            ReceiptSyncPushProcessorResult(
                processedCount = pushMutations.size,
                shouldRetry = shouldRetry,
            )
        } catch (error: Exception) {
            val errorMessage = error.message ?: "Receipt sync push failed."
            val retryable = error.isRetryableSyncFailure()
            preparedRecordsByIdempotencyKey.values.forEach { prepared ->
                if (retryable) {
                    mutationStore.markFailed(
                        queueId = prepared.record.queueId,
                        lastError = errorMessage,
                        nextRetryAtMillis = computeNextRetryAtMillis(
                            nowMillis = now,
                            attemptCount = prepared.attemptCount,
                        ),
                    )
                } else {
                    mutationStore.markTerminalFailed(
                        queueId = prepared.record.queueId,
                        lastError = errorMessage,
                    )
                }
            }

            ReceiptSyncPushProcessorResult(
                processedCount = pushMutations.size,
                shouldRetry = retryable,
            )
        }
    }

    private data class PreparedQueueRecord(
        val record: ReceiptSyncQueueRecord,
        val attemptCount: Int,
    )
}

class ReceiptSyncPullProcessor(
    private val pullGateway: ReceiptSyncPullGateway,
    private val localStore: ReceiptSyncLocalStore,
    private val cursorStore: ReceiptSyncCursorStore,
    private val pendingMutationStore: ReceiptSyncPendingMutationStore,
) {
    suspend fun pullAndApplyChanges(): ReceiptSyncPullProcessorResult {
        var cursor = cursorStore.readCursor()
        var appliedCount = 0
        var skippedCount = 0
        var pageCount = 0
        var hasMore = false

        return try {
            do {
                val response = pullGateway.pull(cursor)
                response.changes.forEach { change ->
                    if (pendingMutationStore.hasPendingMutation(change.id)) {
                        skippedCount += 1
                    } else {
                        when (change.operation) {
                            ReceiptSyncPullOperation.Upsert -> {
                                val payload = requireNotNull(change.payload) {
                                    "Receipt sync pull change payload is required for upsert."
                                }
                                localStore.upsertReceipt(
                                    payload.toLocalReceiptRecord(change.updatedAt),
                                )
                            }

                            ReceiptSyncPullOperation.Delete -> {
                                localStore.deleteReceipt(change.id)
                            }
                        }
                        appliedCount += 1
                    }
                }

                cursorStore.writeCursor(response.cursor)
                cursor = response.cursor
                hasMore = response.hasMore
                pageCount += 1
            } while (hasMore && pageCount < RECEIPT_SYNC_PULL_MAX_PAGES)

            ReceiptSyncPullProcessorResult(
                appliedCount = appliedCount,
                skippedCount = skippedCount,
                shouldRetry = hasMore,
            )
        } catch (_: Exception) {
            ReceiptSyncPullProcessorResult(
                appliedCount = appliedCount,
                skippedCount = skippedCount,
                shouldRetry = true,
            )
        }
    }
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
        val pushResult = ReceiptSyncPushProcessor(
            mutationStore = dependencies.mutationStore,
            pushGateway = dependencies.pushGateway,
            clock = dependencies.clock,
        ).pushDueMutations()

        if (pushResult.shouldRetry) {
            return Result.retry()
        }

        if (pushResult.processedCount > 0) {
            val pullResult = ReceiptSyncPullProcessor(
                pullGateway = dependencies.pullGateway,
                localStore = dependencies.localStore,
                cursorStore = dependencies.cursorStore,
                pendingMutationStore = dependencies.pendingMutationStore,
            ).pullAndApplyChanges()

            if (pullResult.shouldRetry) {
                return Result.retry()
            }
        }

        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "receipt-sync-push"

        internal var dependencyFactory: ((Context) -> ReceiptSyncWorkerDependencies)? = null

        private fun buildDefaultDependencies(
            context: Context,
        ): ReceiptSyncWorkerDependencies {
            val database = ReviewLocalDatabase.getInstance(context.applicationContext)
            val queueStore = RoomReviewSyncQueueStore(database)
            return ReceiptSyncWorkerDependencies(
                mutationStore = queueStore,
                pushGateway = NetworkReceiptSyncPushGateway(
                    ReceiptSyncRemoteDataSource.create(),
                ),
                pullGateway = NetworkReceiptSyncPullGateway(
                    ReceiptSyncRemoteDataSource.create(),
                ),
                localStore = RoomReviewLocalReceiptStore(database),
                cursorStore = RoomReceiptSyncCursorStore(database),
                pendingMutationStore = queueStore,
            )
        }
    }
}

data class ReceiptSyncWorkerDependencies(
    val mutationStore: ReceiptSyncMutationStore,
    val pushGateway: ReceiptSyncPushGateway,
    val pullGateway: ReceiptSyncPullGateway,
    val localStore: ReceiptSyncLocalStore,
    val cursorStore: ReceiptSyncCursorStore,
    val pendingMutationStore: ReceiptSyncPendingMutationStore,
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

private fun ReceiptSyncUpsertPayload.toLocalReceiptRecord(
    updatedAt: String,
): LocalReceiptRecord {
    return LocalReceiptRecord(
        receiptId = id,
        merchant = merchant,
        expenseDate = expenseDate,
        totalAmountRaw = totalAmount,
        totalAmountMinor = totalAmount.toMinorAmount(),
        savedAtMillis = updatedAt.toEpochMillisOrNow(),
        items = items.mapIndexed { index, item ->
            LocalReceiptItemRecord(
                position = index,
                description = item.name,
                amountRaw = item.amount,
                amountMinor = item.amount.toMinorAmount(),
            )
        },
    )
}

private fun String.toStoredUpsertPayload(): StoredReceiptSyncUpsertPayload {
    try {
        return requireNotNull(storedUpsertPayloadAdapter.fromJson(this)) {
            "Legacy receipt sync payload is incomplete and cannot be pushed. " +
                "Clear the stale queue entry and resave the receipt if sync is still needed."
        }
    } catch (error: Exception) {
        throw InvalidReceiptSyncPayloadException(
            message = "Legacy receipt sync payload is incomplete and cannot be pushed. " +
                "Clear the stale queue entry and resave the receipt if sync is still needed.",
            cause = error,
        )
    }
}

private fun String.toStoredDeletePayload(): StoredReceiptSyncDeletePayload {
    try {
        return requireNotNull(storedDeletePayloadAdapter.fromJson(this)) {
            "Receipt sync delete payload is incomplete and cannot be pushed."
        }
    } catch (error: Exception) {
        throw InvalidReceiptSyncPayloadException(
            message = "Receipt sync delete payload is incomplete and cannot be pushed.",
            cause = error,
        )
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
        throw InvalidReceiptSyncPayloadException(
            "Receipt sync payload snapshot field $fieldName must not be blank.",
        )
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

private fun Throwable.isTerminalSyncFailure(): Boolean {
    return this is InvalidReceiptSyncPayloadException || this is HttpException && code() in 400..499
}

private fun Throwable.isRetryableSyncFailure(): Boolean {
    return !isTerminalSyncFailure()
}

private class InvalidReceiptSyncPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun Long.toIsoInstantString(): String {
    return Instant.ofEpochMilli(this).toString()
}

private fun String.toEpochMillisOrNow(): Long {
    return try {
        Instant.parse(this).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}

private fun String.toMinorAmount(): Long {
    val normalized = trim()
    require(amountPattern.matches(normalized)) {
        "Receipt sync amount must be a positive number with up to 2 decimals."
    }
    val parts = normalized.split('.')
    val wholePart = parts.first().toLong()
    val fractionalPart = parts.getOrElse(1) { "" }.padEnd(2, '0')
    return wholePart * 100 + fractionalPart.toLong()
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

private val amountPattern = Regex("""\d+(?:\.\d{1,2})?""")
private const val RECEIPT_SYNC_PUSH_BATCH_LIMIT = 20
private const val RECEIPT_SYNC_PULL_MAX_PAGES = 5
private const val RECEIPT_SYNC_WORK_BACKOFF_MILLIS = 30_000L
