package com.snapledger.feature.review.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstReviewRepositoryTest {
    @Test
    fun `save succeeds without backend`() = runTest {
        val localStore = FakeLocalReceiptStore()
        val syncQueueStore = FakeSyncQueueStore()
        val repository = LocalFirstReviewRepository(
            localReceiptStore = localStore,
            syncQueueStore = syncQueueStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState())

        assertTrue(result is ReviewSaveResult.Success)
        assertEquals(1, localStore.savedReceipts.size)
        assertEquals(1, syncQueueStore.queuedRecords.size)
    }

    @Test
    fun `save succeeds with partial or empty items`() = runTest {
        val localStore = FakeLocalReceiptStore()
        val syncQueueStore = FakeSyncQueueStore()
        val repository = LocalFirstReviewRepository(
            localReceiptStore = localStore,
            syncQueueStore = syncQueueStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState(includeItems = false))

        assertTrue(result is ReviewSaveResult.Success)
        assertEquals(0, localStore.savedReceipts.single().items.size)
    }

    @Test
    fun `invalid required fields do not save`() = runTest {
        val localStore = FakeLocalReceiptStore()
        val syncQueueStore = FakeSyncQueueStore()
        val repository = LocalFirstReviewRepository(
            localReceiptStore = localStore,
            syncQueueStore = syncQueueStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        val result = repository.saveReviewedReceipt(validReviewState(merchant = ""))

        assertTrue(result is ReviewSaveResult.ValidationFailed)
        assertEquals(0, localStore.savedReceipts.size)
        assertEquals(0, syncQueueStore.queuedRecords.size)
    }

    @Test
    fun `sync metadata is queued separately`() = runTest {
        val localStore = FakeLocalReceiptStore()
        val syncQueueStore = FakeSyncQueueStore()
        val repository = LocalFirstReviewRepository(
            localReceiptStore = localStore,
            syncQueueStore = syncQueueStore,
            syncDispatcher = object : ReviewSyncDispatcher {
                override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
            },
            idGenerator = sequentialIds(),
            clock = { 1000L },
        )

        repository.saveReviewedReceipt(validReviewState())

        val savedReceipt = localStore.savedReceipts.single()
        val syncRecord = syncQueueStore.queuedRecords.single()
        assertEquals(savedReceipt.receiptId, syncRecord.receiptId)
        assertEquals("receipt_upsert", syncRecord.operation)
    }

    @Test
    fun `backend failure does not block local save`() = runTest {
        val localStore = FakeLocalReceiptStore()
        val syncQueueStore = FakeSyncQueueStore()
        val repository = LocalFirstReviewRepository(
            localReceiptStore = localStore,
            syncQueueStore = syncQueueStore,
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
        assertEquals(1, localStore.savedReceipts.size)
        assertEquals(1, syncQueueStore.queuedRecords.size)
    }
}

private class FakeLocalReceiptStore : ReviewLocalReceiptStore {
    val savedReceipts = mutableListOf<LocalReceiptRecord>()

    override suspend fun saveReceipt(record: LocalReceiptRecord) {
        savedReceipts += record
    }
}

private class FakeSyncQueueStore : ReviewSyncQueueStore {
    val queuedRecords = mutableListOf<ReceiptSyncQueueRecord>()

    override suspend fun enqueue(record: ReceiptSyncQueueRecord) {
        queuedRecords += record
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
