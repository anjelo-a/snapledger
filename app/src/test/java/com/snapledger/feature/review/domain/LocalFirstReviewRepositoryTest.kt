package com.snapledger.feature.review.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstReviewRepositoryTest {
    @Test
    fun `save succeeds without backend`() = runTest {
        val atomicSaveStore = FakeAtomicSaveStore()
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState())

        assertTrue(result is ReviewSaveResult.Success)
        assertEquals(1, atomicSaveStore.savedReceipts.size)
        assertEquals(1, atomicSaveStore.queuedRecords.size)
    }

    @Test
    fun `save succeeds with partial or empty items`() = runTest {
        val atomicSaveStore = FakeAtomicSaveStore()
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState(includeItems = false))

        assertTrue(result is ReviewSaveResult.Success)
        assertEquals(0, atomicSaveStore.savedReceipts.single().items.size)
    }

    @Test
    fun `invalid required fields do not save`() = runTest {
        val atomicSaveStore = FakeAtomicSaveStore()
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState(merchant = ""))

        assertTrue(result is ReviewSaveResult.ValidationFailed)
        assertEquals(0, atomicSaveStore.savedReceipts.size)
        assertEquals(0, atomicSaveStore.queuedRecords.size)
    }

    @Test
    fun `queue record is created separately from local receipt`() = runTest {
        val atomicSaveStore = FakeAtomicSaveStore()
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        repository.saveReviewedReceipt(validReviewState())

        val savedReceipt = atomicSaveStore.savedReceipts.single()
        val syncRecord = atomicSaveStore.queuedRecords.single()
        assertEquals("id-2", syncRecord.queueId)
        assertEquals("id-2", syncRecord.idempotencyKey)
        assertEquals(savedReceipt.receiptId, syncRecord.receiptId)
        assertEquals(ReceiptSyncQueueRecord.OPERATION_CREATE, syncRecord.operation)
        assertEquals(ReceiptSyncQueueRecord.STATUS_PENDING, syncRecord.status)
        assertEquals(0, syncRecord.attemptCount)
        assertEquals(1000L, syncRecord.queuedAtMillis)
        assertEquals(null, syncRecord.nextRetryAtMillis)
        assertTrue(syncRecord.payloadSnapshot.contains("\"id\":\"${savedReceipt.receiptId}\""))
        assertTrue(syncRecord.payloadSnapshot.contains("\"merchant\":\"Bean Barn\""))
    }

    @Test
    fun `save still succeeds when sync api fails`() = runTest {
        val atomicSaveStore = FakeAtomicSaveStore()
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) {
                    error("backend unavailable")
                }
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState())

        assertTrue(result is ReviewSaveResult.Success)
        val success = result as ReviewSaveResult.Success
        assertFalse(success.dispatchedSyncAttempt)
        assertNotNull(success.syncDispatchError)
        assertEquals(1, atomicSaveStore.savedReceipts.size)
        assertEquals(1, atomicSaveStore.queuedRecords.size)
    }

    @Test
    fun `atomic save failure leaves no receipt or queue record`() = runTest {
        val atomicSaveStore = FakeAtomicSaveStore(
            onSave = { _, _ -> error("database write failed") },
        )
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        try {
            repository.saveReviewedReceipt(validReviewState())
            fail("Expected atomic save failure to be thrown.")
        } catch (error: IllegalStateException) {
            assertEquals("database write failed", error.message)
        }

        assertEquals(0, atomicSaveStore.savedReceipts.size)
        assertEquals(0, atomicSaveStore.queuedRecords.size)
    }
}

private class FakeAtomicSaveStore(
    private val onSave: (suspend (LocalReceiptRecord, ReceiptSyncQueueRecord) -> Unit)? = null,
) : ReviewAtomicSaveStore {
    val savedReceipts = mutableListOf<LocalReceiptRecord>()
    val queuedRecords = mutableListOf<ReceiptSyncQueueRecord>()

    override suspend fun saveReceiptAndQueue(
        receiptRecord: LocalReceiptRecord,
        syncRecord: ReceiptSyncQueueRecord,
    ) {
        val handler = onSave
        if (handler != null) {
            handler(receiptRecord, syncRecord)
            return
        }

        savedReceipts += receiptRecord
        queuedRecords += syncRecord
    }
}

private fun validReviewState(
    merchant: String = "Bean Barn",
    expenseDate: String = "2026-04-29",
    totalAmount: String = "12.50",
    includeItems: Boolean = true,
): ReviewUiState {
    return validateReviewState(
        ReviewUiState(
            merchant = ReviewEditableFieldState(
                label = "Merchant",
                value = merchant,
            ),
            expenseDate = ReviewEditableFieldState(
                label = "Expense date",
                value = expenseDate,
            ),
            totalAmount = ReviewEditableFieldState(
                label = "Total amount",
                value = totalAmount,
            ),
            items = if (includeItems) {
                listOf(
                    ReviewItemFieldState(
                        id = 0,
                        description = "Coffee",
                        amount = "10.00",
                    ),
                    ReviewItemFieldState(
                        id = 1,
                        description = "Muffin",
                        amount = "",
                    ),
                )
            } else {
                emptyList()
            },
        ),
    )
}

private fun sequentialIds(): () -> String {
    var counter = 0
    return {
        counter += 1
        "id-$counter"
    }
}
