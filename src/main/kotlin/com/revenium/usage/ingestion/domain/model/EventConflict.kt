package com.revenium.usage.ingestion.domain.model

import com.revenium.usage.tenancy.TenantId
import java.time.Instant

/**
 * A re-delivery that reused an event id with a different body.
 *
 * This cannot be resolved automatically: accepting the second delivery would bill twice,
 * and discarding it silently would hide a genuine upstream defect. The first delivery
 * wins and the discrepancy is recorded here, where the reconciliation report surfaces it
 * for a human to decide.
 */
data class EventConflict(
    val tenantId: TenantId,
    val rawEventId: Long,
    val originalPayloadHash: String,
    val conflictingPayloadHash: String,
    val conflictingPayload: String,
    val detectedAt: Instant = Instant.now(),
    val id: Long = 0,
) {
    init {
        require(originalPayloadHash != conflictingPayloadHash) {
            "A conflict requires the two payloads to differ"
        }
    }
}
