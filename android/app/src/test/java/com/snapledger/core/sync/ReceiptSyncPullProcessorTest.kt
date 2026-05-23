package com.snapledger.core.sync

import com.snapledger.feature.review.domain.LocalReceiptRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ReceiptSyncPullProcessorTest {
    @Test
    fun `pull skips remote changes for receipts with pending local mutations`() = runTest {
        val localStore = FakeReceiptSyncLocalStore(
            receipts = linkedMapOf(
                "receipt-1" to localReceipt(
                    receiptId = "receipt-1",
                    merchant = "Local Coffee",
                    totalAmountRaw = "9.50",
                ),
            ),
        )
        val cursorStore = FakeReceiptSyncCursorStore(cursor = "cursor-0")
        val pendingMutationStore = FakeReceiptSyncPendingMutationStore(
            pendingReceiptIds = setOf("receipt-1"),
        )
        val processor = ReceiptSyncPullProcessor(
            pullGateway = FakeReceiptSyncPullGateway(
                responses = listOf(
                    ReceiptSyncPullResponse(
                        cursor = "cursor-1",
                        hasMore = false,
                        changes = listOf(
                            ReceiptSyncPullChange(
                                entity = RECEIPT_SYNC_ENTITY,
                                operation = ReceiptSyncPullOperation.Upsert,
                                id = "receipt-1",
                                updatedAt = "2026-05-01T00:00:00Z",
                                payload = remoteUpsertPayload(
                                    id = "receipt-1",
                                    merchant = "Remote Coffee",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            localStore = localStore,
            cursorStore = cursorStore,
            pendingMutationStore = pendingMutationStore,
        )

        val result = processor.pullAndApplyChanges()

        assertEquals(0, result.appliedCount)
        assertEquals(1, result.skippedCount)
        assertFalse(result.shouldRetry)
        assertEquals("Local Coffee", localStore.receipts.getValue("receipt-1").merchant)
        assertEquals("cursor-1", cursorStore.cursor)
    }

    @Test
    fun `pull applies remote receipt when no local mutation is pending`() = runTest {
        val localStore = FakeReceiptSyncLocalStore()
        val cursorStore = FakeReceiptSyncCursorStore(cursor = "cursor-0")
        val processor = ReceiptSyncPullProcessor(
            pullGateway = FakeReceiptSyncPullGateway(
                responses = listOf(
                    ReceiptSyncPullResponse(
                        cursor = "cursor-1",
                        hasMore = false,
                        changes = listOf(
                            ReceiptSyncPullChange(
                                entity = RECEIPT_SYNC_ENTITY,
                                operation = ReceiptSyncPullOperation.Upsert,
                                id = "receipt-2",
                                updatedAt = "2026-05-01T01:00:00Z",
                                payload = remoteUpsertPayload(
                                    id = "receipt-2",
                                    merchant = "Remote Bakery",
                                    totalAmount = "14.25",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            localStore = localStore,
            cursorStore = cursorStore,
            pendingMutationStore = FakeReceiptSyncPendingMutationStore(),
        )

        val result = processor.pullAndApplyChanges()

        assertEquals(1, result.appliedCount)
        assertEquals(0, result.skippedCount)
        assertFalse(result.shouldRetry)
        val receipt = localStore.receipts.getValue("receipt-2")
        assertEquals("Remote Bakery", receipt.merchant)
        assertEquals(1425L, receipt.totalAmountMinor)
        assertEquals("cursor-1", cursorStore.cursor)
    }

    @Test
    fun `pull applies tombstone delete when safe`() = runTest {
        val localStore = FakeReceiptSyncLocalStore(
            receipts = linkedMapOf(
                "receipt-3" to localReceipt(receiptId = "receipt-3"),
            ),
        )
        val cursorStore = FakeReceiptSyncCursorStore(cursor = "cursor-0")
        val processor = ReceiptSyncPullProcessor(
            pullGateway = FakeReceiptSyncPullGateway(
                responses = listOf(
                    ReceiptSyncPullResponse(
                        cursor = "cursor-1",
                        hasMore = false,
                        changes = listOf(
                            ReceiptSyncPullChange(
                                entity = RECEIPT_SYNC_ENTITY,
                                operation = ReceiptSyncPullOperation.Delete,
                                id = "receipt-3",
                                updatedAt = "2026-05-01T02:00:00Z",
                                payload = null,
                            ),
                        ),
                    ),
                ),
            ),
            localStore = localStore,
            cursorStore = cursorStore,
            pendingMutationStore = FakeReceiptSyncPendingMutationStore(),
        )

        val result = processor.pullAndApplyChanges()

        assertEquals(1, result.appliedCount)
        assertEquals(0, result.skippedCount)
        assertFalse(result.shouldRetry)
        assertTrue("receipt-3" !in localStore.receipts)
        assertEquals("cursor-1", cursorStore.cursor)
    }
}

private class FakeReceiptSyncPullGateway(
    private val responses: List<ReceiptSyncPullResponse>,
) : ReceiptSyncPullGateway {
    private var index = 0

    override suspend fun pull(cursor: String): ReceiptSyncPullResponse {
        val response = responses.getOrElse(index) {
            error("Unexpected extra pull request for cursor $cursor")
        }
        index += 1
        return response
    }
}

private class FakeReceiptSyncLocalStore(
    val receipts: LinkedHashMap<String, LocalReceiptRecord> = linkedMapOf(),
) : ReceiptSyncLocalStore {
    override suspend fun upsertReceipt(record: LocalReceiptRecord) {
        receipts[record.receiptId] = record
    }

    override suspend fun deleteReceipt(receiptId: String) {
        receipts.remove(receiptId)
    }
}

private class FakeReceiptSyncCursorStore(
    var cursor: String = INITIAL_RECEIPT_SYNC_CURSOR,
) : ReceiptSyncCursorStore {
    override suspend fun readCursor(): String = cursor

    override suspend fun writeCursor(cursor: String) {
        this.cursor = cursor
    }
}

private class FakeReceiptSyncPendingMutationStore(
    private val pendingReceiptIds: Set<String> = emptySet(),
) : ReceiptSyncPendingMutationStore {
    override suspend fun hasPendingMutation(receiptId: String): Boolean {
        return receiptId in pendingReceiptIds
    }
}

private fun remoteUpsertPayload(
    id: String,
    merchant: String = "Remote Coffee",
    totalAmount: String = "10.50",
): ReceiptSyncUpsertPayload {
    return ReceiptSyncUpsertPayload(
        id = id,
        source = "scan",
        merchant = merchant,
        expenseDate = "2026-05-01",
        totalAmount = totalAmount,
        items = listOf(
            ReceiptSyncExpenseItem(
                name = "Latte",
                amount = totalAmount,
            ),
        ),
    )
}

private fun localReceipt(
    receiptId: String,
    merchant: String = "Local Coffee",
    totalAmountRaw: String = "10.50",
): LocalReceiptRecord {
    return LocalReceiptRecord(
        receiptId = receiptId,
        merchant = merchant,
        expenseDate = "2026-05-01",
        totalAmountRaw = totalAmountRaw,
        totalAmountMinor = totalAmountRaw.split('.').let { parts ->
            parts.first().toLong() * 100 + parts.getOrElse(1) { "00" }.padEnd(2, '0').toLong()
        },
        savedAtMillis = 1_000L,
        items = emptyList(),
    )
}
