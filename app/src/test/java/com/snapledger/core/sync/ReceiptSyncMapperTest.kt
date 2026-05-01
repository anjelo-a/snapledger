package com.snapledger.core.sync

import com.snapledger.core.network.dto.ReceiptSyncExpenseItemDto
import com.snapledger.core.network.dto.ReceiptSyncPullChangeDto
import com.snapledger.core.network.dto.ReceiptSyncPullResponseDto
import com.snapledger.core.network.dto.ReceiptSyncPushResponseDto
import com.snapledger.core.network.dto.ReceiptSyncPushResultDto
import com.snapledger.core.network.dto.ReceiptSyncUpsertPayloadDto
import com.snapledger.core.network.mapper.toDomain
import com.snapledger.core.network.mapper.toDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptSyncMapperTest {
    @Test
    fun `push request maps to expense only dto payloads`() {
        val request = ReceiptSyncPushRequest(
            mutations = listOf(
                ReceiptSyncPushMutation(
                    idempotencyKey = "sync-create-001",
                    operation = ReceiptSyncPushOperation.Create,
                    occurredAt = "2026-05-01T00:00:00Z",
                    payload = ReceiptSyncUpsertPayload(
                        id = "receipt-1",
                        source = "scan",
                        merchant = "Bean Barn",
                        expenseDate = "2026-05-01",
                        totalAmount = "42.50",
                        currency = "PHP",
                        categoryId = null,
                        notes = "Phase 4 network layer",
                        items = listOf(
                            ReceiptSyncExpenseItem(
                                name = "Coffee",
                                amount = "42.50",
                            ),
                        ),
                    ),
                ),
                ReceiptSyncPushMutation(
                    idempotencyKey = "sync-delete-001",
                    operation = ReceiptSyncPushOperation.Delete,
                    occurredAt = "2026-05-01T00:05:00Z",
                    payload = ReceiptSyncDeletePayload(id = "receipt-2"),
                ),
            ),
        )

        val dto = request.toDto()

        assertEquals(2, dto.mutations.size)
        assertEquals(RECEIPT_SYNC_ENTITY, dto.mutations[0].entity)
        assertEquals("create", dto.mutations[0].operation)
        assertEquals("receipt-1", dto.mutations[0].payload["id"])
        assertEquals("scan", dto.mutations[0].payload["source"])
        assertEquals("Bean Barn", dto.mutations[0].payload["merchant"])
        assertEquals("delete", dto.mutations[1].operation)
        assertEquals(mapOf("id" to "receipt-2"), dto.mutations[1].payload)
    }

    @Test
    fun `push response dto maps to domain results`() {
        val response = ReceiptSyncPushResponseDto(
            accepted = 1,
            rejected = 1,
            results = listOf(
                ReceiptSyncPushResultDto(
                    idempotencyKey = "sync-create-001",
                    entity = RECEIPT_SYNC_ENTITY,
                    operation = "create",
                    status = "accepted",
                    entityId = "receipt-1",
                ),
                ReceiptSyncPushResultDto(
                    idempotencyKey = "sync-category-001",
                    entity = "category",
                    operation = "update",
                    status = "rejected",
                    code = "unsupported_entity_phase4",
                    message = "Only expense sync is supported.",
                ),
            ),
        )

        val domain = response.toDomain()

        assertEquals(1, domain.accepted)
        assertEquals(1, domain.rejected)
        assertEquals(ReceiptSyncPushOperation.Create, domain.results[0].operation)
        assertEquals("receipt-1", domain.results[0].entityId)
        assertEquals("unsupported_entity_phase4", domain.results[1].code)
    }

    @Test
    fun `pull response dto maps upsert and delete changes`() {
        val response = ReceiptSyncPullResponseDto(
            cursor = "opaque-cursor",
            hasMore = true,
            changes = listOf(
                ReceiptSyncPullChangeDto(
                    entity = RECEIPT_SYNC_ENTITY,
                    operation = "upsert",
                    id = "receipt-1",
                    updatedAt = "2026-05-01T00:00:00Z",
                    payload = ReceiptSyncUpsertPayloadDto(
                        source = "scan",
                        merchant = "Bean Barn",
                        expenseDate = "2026-05-01",
                        totalAmount = "42.50",
                        currency = "PHP",
                        categoryId = null,
                        notes = "ready to sync",
                        items = listOf(
                            ReceiptSyncExpenseItemDto(
                                name = "Coffee",
                                amount = "42.50",
                            ),
                        ),
                    ),
                ),
                ReceiptSyncPullChangeDto(
                    entity = RECEIPT_SYNC_ENTITY,
                    operation = "delete",
                    id = "receipt-2",
                    updatedAt = "2026-05-01T00:05:00Z",
                    payload = null,
                ),
            ),
        )

        val domain = response.toDomain()

        assertEquals("opaque-cursor", domain.cursor)
        assertEquals(true, domain.hasMore)
        assertEquals(ReceiptSyncPullOperation.Upsert, domain.changes[0].operation)
        assertEquals("receipt-1", domain.changes[0].payload?.id)
        assertEquals("Coffee", domain.changes[0].payload?.items?.single()?.name)
        assertEquals(ReceiptSyncPullOperation.Delete, domain.changes[1].operation)
        assertNull(domain.changes[1].payload)
    }
}
