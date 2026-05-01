package com.snapledger.core.sync

import com.snapledger.feature.review.domain.LocalReceiptItemRecord
import com.snapledger.feature.review.domain.LocalReceiptRecord
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ReceiptSyncPushProcessorTest {
    @Test
    fun `worker marks successful push records as synced`() = runTest {
        val queueStore = FakeReceiptSyncMutationStore(
            records = mutableListOf(validQueuedCreateRecord()),
        )
        val processor = ReceiptSyncPushProcessor(
            mutationStore = queueStore,
            pushGateway = object : ReceiptSyncPushGateway {
                override suspend fun push(
                    request: ReceiptSyncPushRequest,
                ): ReceiptSyncPushResponse {
                    return ReceiptSyncPushResponse(
                        accepted = 1,
                        rejected = 0,
                        results = listOf(
                            ReceiptSyncPushResult(
                                idempotencyKey = request.mutations.single().idempotencyKey,
                                entity = RECEIPT_SYNC_ENTITY,
                                operation = ReceiptSyncPushOperation.Create,
                                status = "accepted",
                                entityId = "receipt-1",
                            ),
                        ),
                    )
                }
            },
            clock = { 2_000L },
        )

        val result = processor.pushDueMutations()

        val synced = queueStore.records.single()
        assertEquals(1, result.processedCount)
        assertTrue(
            "Expected synced status but was ${synced.status} with error ${synced.lastError}",
            synced.status == ReceiptSyncQueueRecord.STATUS_SYNCED,
        )
        assertEquals(1, synced.attemptCount)
        assertEquals(null, synced.lastError)
        assertEquals(false, result.shouldRetry)
    }

    @Test
    fun `worker records failure and leaves local receipt intact`() = runTest {
        val localReceipts = mutableListOf(
            LocalReceiptRecord(
                receiptId = "receipt-1",
                merchant = "Bean Barn",
                expenseDate = "2026-05-01",
                totalAmountRaw = "12.50",
                totalAmountMinor = 1250L,
                savedAtMillis = 1_000L,
                items = listOf(
                    LocalReceiptItemRecord(
                        position = 0,
                        description = "Coffee",
                        amountRaw = "12.50",
                        amountMinor = 1250L,
                    ),
                ),
            ),
        )
        val queueStore = FakeReceiptSyncMutationStore(
            records = mutableListOf(validQueuedCreateRecord()),
        )
        val processor = ReceiptSyncPushProcessor(
            mutationStore = queueStore,
            pushGateway = object : ReceiptSyncPushGateway {
                override suspend fun push(
                    request: ReceiptSyncPushRequest,
                ): ReceiptSyncPushResponse {
                    error("sync api unavailable")
                }
            },
            clock = { 2_000L },
        )

        val result = processor.pushDueMutations()

        assertEquals(true, result.shouldRetry)
        val failed = queueStore.records.single()
        assertEquals(ReceiptSyncQueueRecord.STATUS_FAILED, failed.status)
        assertEquals(1, failed.attemptCount)
        assertNotNull(failed.lastError)
        assertTrue((failed.nextRetryAtMillis ?: 0L) > 2_000L)
        assertEquals(1, localReceipts.size)
        assertEquals("receipt-1", localReceipts.single().receiptId)
    }

    @Test
    fun `worker terminal-fails legacy queue payload without retry loop`() = runTest {
        val queueStore = FakeReceiptSyncMutationStore(
            records = mutableListOf(
                ReceiptSyncQueueRecord(
                    queueId = "legacy-queue-1",
                    idempotencyKey = "legacy-idem-1",
                    receiptId = "legacy-receipt-1",
                    operation = ReceiptSyncQueueRecord.OPERATION_CREATE,
                    payloadSnapshot = """{"id":"legacy-receipt-1"}""",
                    queuedAtMillis = 1_000L,
                ),
            ),
        )
        val processor = ReceiptSyncPushProcessor(
            mutationStore = queueStore,
            pushGateway = object : ReceiptSyncPushGateway {
                override suspend fun push(
                    request: ReceiptSyncPushRequest,
                ): ReceiptSyncPushResponse {
                    error("push should not be attempted for invalid legacy payloads")
                }
            },
            clock = { 2_000L },
        )

        val firstResult = processor.pushDueMutations()
        val failed = queueStore.records.single()
        val secondResult = processor.pushDueMutations()

        assertEquals(1, firstResult.processedCount)
        assertEquals(false, firstResult.shouldRetry)
        assertEquals(ReceiptSyncQueueRecord.STATUS_FAILED, failed.status)
        assertEquals(1, failed.attemptCount)
        assertNull(failed.nextRetryAtMillis)
        assertTrue(
            failed.lastError?.contains("incomplete and cannot be pushed") == true,
        )
        assertEquals(0, secondResult.processedCount)
        assertEquals(false, secondResult.shouldRetry)
    }
}

private class FakeReceiptSyncMutationStore(
    val records: MutableList<ReceiptSyncQueueRecord>,
) : ReceiptSyncMutationStore {
    override suspend fun loadDueMutations(
        nowMillis: Long,
        limit: Int,
    ): List<ReceiptSyncQueueRecord> {
        return records
            .filter { record ->
                record.status == ReceiptSyncQueueRecord.STATUS_PENDING ||
                    (
                        record.status == ReceiptSyncQueueRecord.STATUS_FAILED &&
                            record.nextRetryAtMillis != null &&
                            record.nextRetryAtMillis <= nowMillis
                        )
            }
            .sortedBy { it.queuedAtMillis }
            .take(limit)
    }

    override suspend fun markInFlight(queueIds: List<String>) {
        records.replaceAll { record ->
            if (record.queueId in queueIds) {
                record.copy(
                    status = ReceiptSyncQueueRecord.STATUS_IN_FLIGHT,
                    attemptCount = record.attemptCount + 1,
                    lastError = null,
                    nextRetryAtMillis = null,
                )
            } else {
                record
            }
        }
    }

    override suspend fun markSynced(queueId: String) {
        records.replaceAll { record ->
            if (record.queueId == queueId) {
                record.copy(
                    status = ReceiptSyncQueueRecord.STATUS_SYNCED,
                    lastError = null,
                    nextRetryAtMillis = null,
                )
            } else {
                record
            }
        }
    }

    override suspend fun markFailed(
        queueId: String,
        lastError: String,
        nextRetryAtMillis: Long?,
    ) {
        records.replaceAll { record ->
            if (record.queueId == queueId) {
                record.copy(
                    status = ReceiptSyncQueueRecord.STATUS_FAILED,
                    lastError = lastError,
                    nextRetryAtMillis = nextRetryAtMillis,
                )
            } else {
                record
            }
        }
    }

    override suspend fun hasPendingMutation(receiptId: String): Boolean {
        return records.any { record ->
            record.receiptId == receiptId &&
                (
                    record.status == ReceiptSyncQueueRecord.STATUS_PENDING ||
                        record.status == ReceiptSyncQueueRecord.STATUS_IN_FLIGHT ||
                        (
                            record.status == ReceiptSyncQueueRecord.STATUS_FAILED &&
                                record.nextRetryAtMillis != null
                            )
                    )
        }
    }
}

private fun validQueuedCreateRecord(): ReceiptSyncQueueRecord {
    return ReceiptSyncQueueRecord(
        queueId = "queue-1",
        idempotencyKey = "idem-1",
        receiptId = "receipt-1",
        operation = ReceiptSyncQueueRecord.OPERATION_CREATE,
        payloadSnapshot = """
            {
              "id":"receipt-1",
              "source":"scan",
              "merchant":"Bean Barn",
              "expense_date":"2026-05-01",
              "total_amount":"12.50",
              "currency":"PHP",
              "items":[{"name":"Coffee","amount":"12.50"}]
            }
        """.trimIndent(),
        queuedAtMillis = 1_000L,
    )
}
