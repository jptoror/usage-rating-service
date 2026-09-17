package com.revenium.usage.ingestion.infrastructure.persistence

import com.revenium.usage.ingestion.domain.model.EventConflict
import com.revenium.usage.ingestion.domain.model.RawEvent
import com.revenium.usage.ingestion.domain.model.RejectedEvent
import com.revenium.usage.ingestion.domain.port.out.EventStore
import com.revenium.usage.shared.domain.EventId
import com.revenium.usage.tenancy.TenantId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Every query carries the tenant explicitly, even though row-level security would
 * filter it anyway. Defence in depth: the application states its intent, and the
 * database enforces it independently.
 */
@Repository
interface RawEventJpaRepository : JpaRepository<RawEventEntity, Long> {

    fun findByTenantIdAndEventId(tenantId: String, eventId: UUID): RawEventEntity?

    fun existsByTenantIdAndEventId(tenantId: String, eventId: UUID): Boolean
}

@Repository
interface RejectedEventJpaRepository : JpaRepository<RejectedEventEntity, Long> {

    fun findByTenantIdAndEventId(tenantId: String, eventId: UUID): List<RejectedEventEntity>

    fun countByTenantId(tenantId: String): Long
}

@Repository
interface EventConflictJpaRepository : JpaRepository<EventConflictEntity, Long> {

    fun findByTenantIdAndRawEventId(tenantId: String, rawEventId: Long): List<EventConflictEntity>

    fun countByTenantId(tenantId: String): Long
}

/**
 * The JPA adapter for [EventStore].
 *
 * Translates between the domain model and the persistence entities. None of the entities
 * escapes this package.
 */
@Repository
class JpaEventStore(
    private val rawEvents: RawEventJpaRepository,
    private val rejectedEvents: RejectedEventJpaRepository,
    private val conflicts: EventConflictJpaRepository,
) : EventStore {

    override fun record(event: RawEvent): RawEvent =
        // saveAndFlush, not save: the constraint violation must surface here, inside the
        // caller's transaction, rather than at commit time where a duplicate could no
        // longer be distinguished from a genuine failure.
        rawEvents.saveAndFlush(RawEventEntity.fromDomain(event)).toDomain()

    override fun findByEventId(tenant: TenantId, eventId: EventId): RawEvent? =
        rawEvents.findByTenantIdAndEventId(tenant.value, eventId.value)?.toDomain()

    override fun recordDuplicateDelivery(event: RawEvent): RawEvent =
        rawEvents.save(RawEventEntity.fromDomain(event)).toDomain()

    override fun recordConflict(conflict: EventConflict): EventConflict =
        conflicts.save(EventConflictEntity.fromDomain(conflict)).toDomain()

    override fun recordRejection(rejected: RejectedEvent): RejectedEvent =
        rejectedEvents.save(RejectedEventEntity.fromDomain(rejected)).toDomain()
}
