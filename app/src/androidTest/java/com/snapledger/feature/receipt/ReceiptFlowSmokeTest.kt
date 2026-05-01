package com.snapledger.feature.receipt

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapledger.feature.review.domain.LocalFirstReviewRepository
import com.snapledger.feature.review.domain.ReviewAtomicSaveStore
import com.snapledger.feature.review.domain.LocalReceiptRecord
import com.snapledger.feature.review.domain.ReceiptSyncQueueRecord
import com.snapledger.feature.review.domain.ReviewSyncDispatcher
import com.snapledger.feature.review.ui.ReviewRoute
import com.snapledger.feature.review.vm.ReviewViewModel
import com.snapledger.feature.scan.domain.NormalizedOcrLine
import com.snapledger.feature.scan.parser.DeterministicReceiptParserService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptFlowSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mockScanParseReviewAndSaveFlow() {
        val atomicSaveStore = RecordingAtomicSaveStore()
        val repository = LocalFirstReviewRepository(
            atomicSaveStore = atomicSaveStore,
            syncDispatcher = NoOpSyncDispatcher,
            idGenerator = sequentialIds(),
            clock = { 1_777_000_000_000L },
        )

        composeRule.setContent {
            ReceiptFlowSmokeHarness(repository = repository)
        }

        composeRule.onNodeWithText("Use Mock OCR Lines").assertIsDisplayed()
        composeRule.onNodeWithText("Run Deterministic Parser").performClick()
        composeRule.onNodeWithText("Open Review").performClick()

        composeRule.onNodeWithText("BEAN BARN CAFE").assertIsDisplayed()
        composeRule.onNodeWithText("2026-04-29").assertIsDisplayed()
        composeRule.onNodeWithText("7.75").assertIsDisplayed()
        composeRule.onNodeWithText("Required fields are valid. Save is enabled.")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Save Placeholder").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            atomicSaveStore.savedReceipts.isNotEmpty() &&
                atomicSaveStore.queuedRecords.isNotEmpty()
        }

        val savedReceipt = atomicSaveStore.savedReceipts.single()
        assertEquals("BEAN BARN CAFE", savedReceipt.merchant)
        assertEquals("2026-04-29", savedReceipt.expenseDate)
        assertEquals(775L, savedReceipt.totalAmountMinor)
        assertTrue(savedReceipt.items.isNotEmpty())
        assertEquals(
            savedReceipt.receiptId,
            atomicSaveStore.queuedRecords.single().receiptId,
        )

        composeRule.onNodeWithText("Saved locally as receipt-1. Sync metadata queued as sync-2.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}

@Composable
private fun ReceiptFlowSmokeHarness(
    repository: LocalFirstReviewRepository,
) {
    var showReview by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf(false) }
    val parser = remember { DeterministicReceiptParserService() }

    MaterialTheme {
        if (showReview) {
            val reviewViewModel = remember { ReviewViewModel(repository) }
            ReviewRoute(
                viewModel = reviewViewModel,
                onBack = { showReview = false },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(text = "Use Mock OCR Lines")
                Button(
                    onClick = {
                        repository.storeParsedCandidate(
                            parser.parse(
                                mockOcrLines(
                                    "BEAN BARN CAFE",
                                    "04/29/2026",
                                    "Latte 4.50",
                                    "Blueberry Muffin 3.25",
                                    "TOTAL 7.75",
                                ),
                            ),
                        )
                        parsed = true
                    },
                ) {
                    Text(text = "Run Deterministic Parser")
                }
                Button(
                    onClick = { showReview = true },
                    enabled = parsed,
                ) {
                    Text(text = "Open Review")
                }
            }
        }
    }
}

private class RecordingAtomicSaveStore : ReviewAtomicSaveStore {
    val savedReceipts = mutableListOf<LocalReceiptRecord>()
    val queuedRecords = mutableListOf<ReceiptSyncQueueRecord>()

    override suspend fun saveReceiptAndQueue(
        receiptRecord: LocalReceiptRecord,
        syncRecord: ReceiptSyncQueueRecord,
    ) {
        savedReceipts += receiptRecord
        queuedRecords += syncRecord
    }
}

private object NoOpSyncDispatcher : ReviewSyncDispatcher {
    override suspend fun dispatch(record: ReceiptSyncQueueRecord) = Unit
}

private fun sequentialIds(): () -> String {
    var counter = 0
    return {
        counter += 1
        if (counter % 2 == 1) {
            "receipt-$counter"
        } else {
            "sync-$counter"
        }
    }
}

private fun mockOcrLines(vararg values: String): List<NormalizedOcrLine> {
    return values.mapIndexed { index, value ->
        NormalizedOcrLine(
            index = index,
            text = value,
        )
    }
}
