package com.revenium.usage.ingestion.infrastructure

import com.revenium.usage.ingestion.domain.EventConflict
import com.revenium.usage.ingestion.domain.RawEvent
import com.revenium.usage.ingestion.domain.RejectedEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Every query carries the tenant explicitly, even though row-level security would
 * filter it anyway. Defence in depth: the application states its intent, and the
 * database enforces it independently.
 */
@Repository
interface RawEventRepository : JpaRepository<RawEvent, Long> {

    fun findByTenantIdAndEventId(tenantId: String, eventId: UUID): RawEvent?

    fun existsByTenantIdAndEventId(tenantId: String, eventId: UUID): Boolean
}

@Repository
interface RejectedEventRepository : JpaRepository<RejectedEvent, Long> {

    fun findByTenantIdAndEventId(tenantId: String, eventId: UUID): List<RejectedEvent>

    fun countByTenantId(tenantId: String): Long
}

@Repository
interface EventConflictRepository : JpaRepository<EventConflict, Long> {

    fun findByTenantIdAndRawEventId(tenantId: String, rawEventId: Long): List<EventConflict>

    fun countByTenantId(tenantId: String): Long
}
